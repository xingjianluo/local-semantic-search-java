package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.embedding.EmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.embedding.HashingEmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexedDocument;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.DefaultIndexValidator;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.IndexValidator;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildFailureReason;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildResult;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildService;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildStatus;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildValidationException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 索引重建：原子生效、失败回退、失败原因可区分、重建期间可读写。
 */
class RebuildServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RebuildServiceTest.class);

    private DocumentStore newStoreWithDocs() {
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        store.upsert("d1", "语义 检索 引擎", "team-a");
        store.upsert("d2", "本地 向量 索引", "team-a");
        store.upsert("d3", "rebuild atomic swap", "team-b");
        return store;
    }

    @Test
    void successfulRebuildSwapsAtomically() {
        DocumentStore store = newStoreWithDocs();
        RebuildService service = new RebuildService(store, new HashingEmbeddingModel(),
                new DefaultIndexValidator());
        long versionBefore = store.currentSnapshot().version();
        RebuildResult result = service.rebuild();
        List<SearchHit> hits = store.search("语义 检索", Set.of("team-a"), 10);
        log.info("输入: 3 个文档的索引执行重建；结果={}；版本 {} -> {}；重建后检索命中={}；"
                + "判定依据: 重建成功、版本前进、文档数不变、检索结果保持可用",
                result, versionBefore, store.currentSnapshot().version(), hits);
        assertEquals(RebuildStatus.SUCCESS, result.status());
        assertNull(result.failureReason());
        assertEquals(3, result.documentCount());
        assertTrue(store.currentSnapshot().version() > versionBefore);
        assertEquals(3, store.currentSnapshot().size());
        assertFalse(hits.isEmpty());
    }

    @Test
    void validationFailureRollsBackToOriginalIndex() {
        DocumentStore store = newStoreWithDocs();
        IndexSnapshot before = store.currentSnapshot();
        IndexValidator alwaysReject = (source, newIndex) -> {
            throw new RebuildValidationException("injected validation failure");
        };
        RebuildService service = new RebuildService(store, new HashingEmbeddingModel(), alwaysReject);
        RebuildResult result = service.rebuild();
        log.info("输入: 注入总是失败的校验器执行重建；结果={}；判定依据: 状态必须为 FAILED_ROLLED_BACK，"
                + "原因可区分为 VALIDATION_FAILED，且生效索引保持原快照不变", result);
        assertEquals(RebuildStatus.FAILED_ROLLED_BACK, result.status());
        assertEquals(RebuildFailureReason.VALIDATION_FAILED, result.failureReason());
        assertSame(before, store.currentSnapshot());
        assertEquals(3, store.currentSnapshot().size());
    }

    @Test
    void embeddingFailureIsDistinguishedAndRolledBack() {
        DocumentStore store = newStoreWithDocs();
        IndexSnapshot before = store.currentSnapshot();
        EmbeddingModel exploding = new HashingEmbeddingModel() {
            @Override
            public float[] embed(String text) {
                throw new IllegalStateException("injected embedding failure");
            }
        };
        RebuildService service = new RebuildService(store, exploding, new DefaultIndexValidator());
        RebuildResult result = service.rebuild();
        log.info("输入: 注入总是抛异常的向量化模型执行重建；结果={}；判定依据: 原因必须可区分为 "
                + "EMBEDDING_FAILED，且生效索引保持原快照不变", result);
        assertEquals(RebuildStatus.FAILED_ROLLED_BACK, result.status());
        assertEquals(RebuildFailureReason.EMBEDDING_FAILED, result.failureReason());
        assertSame(before, store.currentSnapshot());
    }

    @Test
    void unreadableSourceIsDistinguishedAndRolledBack() {
        DocumentStore store = newStoreWithDocs();
        IndexSnapshot before = store.currentSnapshot();
        DocumentStore brokenStore = new DocumentStore(new HashingEmbeddingModel()) {
            @Override
            public RebuildSource lockedSource() {
                throw new IllegalStateException("injected source read failure");
            }

            @Override
            public IndexSnapshot currentSnapshot() {
                return before;
            }
        };
        RebuildService service = new RebuildService(brokenStore, new HashingEmbeddingModel(),
                new DefaultIndexValidator());
        RebuildResult result = service.rebuild();
        log.info("输入: 注入读取文档源即失败的存储执行重建；结果={}；判定依据: 原因必须可区分为 "
                + "SOURCE_UNREADABLE，且结果中的文档数与版本仍指向原有可用索引", result);
        assertEquals(RebuildStatus.FAILED_ROLLED_BACK, result.status());
        assertEquals(RebuildFailureReason.SOURCE_UNREADABLE, result.failureReason());
        assertEquals(before.size(), result.documentCount());
        assertEquals(before.version(), result.indexVersion());
    }

    @Test
    void rebuildStaysConsistentUnderConcurrentWrites() throws InterruptedException {
        DocumentStore store = newStoreWithDocs();
        RebuildService service = new RebuildService(store, new HashingEmbeddingModel(),
                new DefaultIndexValidator());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        var writerDone = new java.util.concurrent.atomic.AtomicBoolean(false);

        var rebuildFuture = pool.submit(() -> {
            await(start);
            return service.rebuild();
        });
        pool.submit(() -> {
            await(start);
            for (int i = 0; i < 300; i++) {
                store.upsert("w-" + (i % 30), "并发 重建 期间 写入 " + i, "team-a");
                writes.incrementAndGet();
            }
            writerDone.set(true);
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        RebuildResult result;
        try {
            result = rebuildFuture.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        int finalSize = store.currentSnapshot().size();
        log.info("输入: 重建与 {} 次并发写入同时执行；重建结果={}；终态索引大小={}；"
                + "判定依据: 重建不得留下中间态——要么成功且终态索引完整自洽"
                + "（大小=3 篇初始文档+30 个写入 id），要么明确失败回退",
                writes.get(), result, finalSize);
        assertEquals(RebuildStatus.SUCCESS, result.status());
        assertEquals(3 + 30, finalSize);
        // team-a 下应有 30 个写入文档 + 2 个初始文档（零分文档也会被召回）。
        List<SearchHit> hits = store.search("并发 重建 期间 写入", Set.of("team-a"), 1000);
        assertEquals(32, hits.size());
        assertEquals(30, hits.stream().map(SearchHit::id).filter(id -> id.startsWith("w-")).count());
        assertEquals(30, hits.stream().map(SearchHit::id).filter(id -> id.startsWith("w-")).distinct().count());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
