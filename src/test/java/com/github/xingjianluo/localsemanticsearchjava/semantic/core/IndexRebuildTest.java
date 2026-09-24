package com.github.xingjianluo.localsemanticsearchjava.semantic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.localsemanticsearchjava.semantic.core.RebuildHook;
import com.github.xingjianluo.localsemanticsearchjava.semantic.core.SemanticSearchService;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.RebuildFailureReason;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.RebuildOutcome;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.RebuildStatus;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchRequest;
import com.github.xingjianluo.localsemanticsearchjava.semantic.store.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.HashingVectorizer;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.TextVectorizer;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.VectorizationException;

/**
 * 索引重建测试：成功原子生效、中途失败回退、失败原因可区分、重建期间可读写。
 */
class IndexRebuildTest {

    private static final Logger log = LoggerFactory.getLogger(IndexRebuildTest.class);

    private SemanticSearchService service;

    @BeforeEach
    void setUp() {
        service = new SemanticSearchService(new DocumentStore(), new HashingVectorizer());
        for (int i = 0; i < 8; i++) {
            service.upsert("d" + i, "offline local semantic search document number " + i, List.of("public"));
        }
    }

    @AfterEach
    void clearHook() {
        service.setRebuildHook(RebuildHook.NOOP);
    }

    @Test
    void successfulRebuildPublishesCompleteIndex() {
        int before = service.indexedDocumentCount();
        RebuildOutcome outcome = service.rebuildIndex();
        log.info("[重建成功] status={} 重建前文档数={} 重建后文档数={}", outcome.status(), before, outcome.indexedDocs());
        assertEquals(RebuildStatus.SUCCESS, outcome.status());
        assertEquals(8, outcome.indexedDocs());
        assertTrue(service.search(new SearchRequest("offline local semantic search", 10, List.of("public")))
                .totalHits() >= 8);
    }

    @Test
    void hookFailureMidRebuildRollsBackToOldIndex() {
        Object oldIndex = service.currentIndex();
        service.setRebuildHook((doc, processed, total) -> {
            if (processed == 4) {
                throw new IllegalStateException("模拟磁盘故障");
            }
        });
        RebuildOutcome outcome = service.rebuildIndex();
        log.info("[中途失败回退] status={} reason={} message='{}' 旧索引文档数={} 引用未变={}",
                outcome.status(), outcome.reason(), outcome.message(), outcome.indexedDocs(),
                service.currentIndex() == oldIndex);
        assertEquals(RebuildStatus.FAILED, outcome.status());
        assertEquals(RebuildFailureReason.HOOK_ABORTED, outcome.reason(), "失败原因必须可区分");
        assertEquals(8, outcome.indexedDocs(), "回退后旧索引必须仍可用且完整");
        assertEquals(oldIndex, service.currentIndex(), "失败时不得切换索引引用");
        assertTrue(service.search(new SearchRequest("offline local semantic search", 10, List.of("public")))
                .totalHits() >= 8, "回退后查询必须仍能召回全部旧文档");
    }

    @Test
    void vectorizationFailureIsDistinctFromHookFailure() {
        // 直接在存储中植入不可向量化文档，重建时必定向量化失败
        DocumentStore store = new DocumentStore();
        TextVectorizer strict = new HashingVectorizer();
        SemanticSearchService svc = new SemanticSearchService(store, strict);
        store.upsert(new com.github.xingjianluo.localsemanticsearchjava.semantic.model.DocumentUpsert(
                "bad", "   ??? !!!   ", List.of("public")));
        RebuildOutcome outcome = svc.rebuildIndex();
        log.info("[向量化失败] status={} reason={} message='{}'", outcome.status(), outcome.reason(),
                outcome.message());
        assertEquals(RebuildStatus.FAILED, outcome.status());
        assertEquals(RebuildFailureReason.VECTORIZATION_FAILED, outcome.reason());
        assertNotEquals(RebuildFailureReason.HOOK_ABORTED, outcome.reason());
    }

    @Test
    void interruptedFailureIsDistinct() {
        service.setRebuildHook((doc, processed, total) -> {
            if (processed == 2) {
                throw new InterruptedException("模拟中断");
            }
        });
        RebuildOutcome outcome = service.rebuildIndex();
        log.info("[中断失败] status={} reason={} message='{}'", outcome.status(), outcome.reason(),
                outcome.message());
        assertEquals(RebuildFailureReason.INTERRUPTED, outcome.reason());
    }

    @Test
    void concurrentRebuildIsRejectedWithAlreadyRunning() throws Exception {
        CountDownLatch insideHook = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        service.setRebuildHook((doc, processed, total) -> {
            if (processed == 1) {
                insideHook.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RebuildOutcome> running = pool.submit(service::rebuildIndex);
            assertTrue(insideHook.await(5, TimeUnit.SECONDS));
            RebuildOutcome rejected = service.rebuildIndex();
            log.info("[并发重建] 第二次重建 status={} reason={}", rejected.status(), rejected.reason());
            assertEquals(RebuildFailureReason.ALREADY_RUNNING, rejected.reason());
            release.countDown();
            assertEquals(RebuildStatus.SUCCESS, running.get(5, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void writesDuringRebuildAreVisibleAfterAtomicSwitch() throws Exception {
        CountDownLatch insideHook = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        service.setRebuildHook((doc, processed, total) -> {
            if (processed == 4) {
                insideHook.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RebuildOutcome> rebuildFuture = pool.submit(service::rebuildIndex);
            assertTrue(insideHook.await(5, TimeUnit.SECONDS), "重建应已进入中途检查点");

            // 重建进行中写入仍可立即生效（写入走旧索引的增量更新）
            service.upsert("during", "offline local semantic search added during rebuild", List.of("public"));
            int visibleDuring = service.search(
                    new SearchRequest("added during rebuild", 10, List.of("public"))).totalHits();
            log.info("[重建期间写入] 发布前新文档可见命中数={} 判定要求>=1", visibleDuring);
            assertTrue(visibleDuring >= 1, "重建期间服务必须保持可写且新写入可查");

            // 同时删除一个基线文档
            service.delete("d0");

            release.countDown();
            RebuildOutcome outcome = rebuildFuture.get(5, TimeUnit.SECONDS);
            log.info("[增量追平] 重建结果={} 文档数={} 判定要求=8(8-1+1)", outcome.status(),
                    outcome.indexedDocs());
            assertEquals(RebuildStatus.SUCCESS, outcome.status());
            assertEquals(8, outcome.indexedDocs(), "重建必须追平期间的新增与删除");
            assertTrue(service.search(new SearchRequest("added during rebuild", 10, List.of("public")))
                    .totalHits() >= 1, "新索引必须包含重建期间写入");
            assertEquals(0, service.search(new SearchRequest("document number 0", 10, List.of("public")))
                    .hits().stream().filter(h -> h.documentId().equals("d0")).count(),
                    "新索引不得包含重建期间删除的文档");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
