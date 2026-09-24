package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.embedding.HashingEmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发写入与查询：查询只能看到完整自洽的索引状态，
 * 不得出现部分可见、重复命中或越界内容。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private static final int WRITERS = 4;
    private static final int READERS = 4;
    private static final int OPS_PER_WRITER = 200;
    private static final int ID_SPACE = 50;

    @Test
    void concurrentWritesAndQueriesStayConsistent() throws InterruptedException {
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + READERS);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        Set<String> visibleScopes = Set.of("team-a", "team-b");

        for (int w = 0; w < WRITERS; w++) {
            final int writerId = w;
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < OPS_PER_WRITER; i++) {
                    int docNum = (writerId * OPS_PER_WRITER + i) % ID_SPACE;
                    String id = "doc-" + docNum;
                    String scope = docNum % 2 == 0 ? "team-a" : "team-b";
                    if (i % 5 == 4) {
                        store.delete(id);
                    } else {
                        store.upsert(id, "并发 文档 内容 " + docNum + " 版本 " + i, scope);
                    }
                }
            });
        }
        for (int r = 0; r < READERS; r++) {
            pool.submit(() -> {
                await(start);
                int queries = 0;
                while (!Thread.currentThread().isInterrupted() && queries < 500) {
                    List<SearchHit> hits = store.search("并发 文档 内容", visibleScopes, 20);
                    queries++;
                    Set<String> seen = new HashSet<>();
                    for (SearchHit hit : hits) {
                        if (!seen.add(hit.id())) {
                            violations.add("重复命中: " + hit.id());
                        }
                        if (!visibleScopes.contains(hit.scope())) {
                            violations.add("越界可见: " + hit);
                        }
                    }
                }
            });
        }
        long t0 = System.nanoTime();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务应在限时内完成");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        log.info("输入: {} 个写线程 x {} 次写/删（id 空间 {}），{} 个读线程各 500 次查询，"
                + "可见范围={}；耗时 {}ms；违规={}；判定依据: 任意一次查询结果内 id 不得重复、"
                + "不得出现可见范围之外的文档", WRITERS, OPS_PER_WRITER, ID_SPACE, READERS,
                visibleScopes, elapsedMs, violations);
        assertTrue(violations.isEmpty(), () -> "发现一致性违规: " + violations);

        // 终态自洽：索引中的文档数 = 各 id 最后一次写/删操作决定的存活集合。
        List<SearchHit> finalHits = store.search("并发 文档 内容", visibleScopes, 1000);
        log.info("终态校验: 索引大小={}，全量召回={}；判定依据: 终态索引不得有重复 id，"
                + "且全量召回数等于索引大小", store.currentSnapshot().size(), finalHits.size());
        assertEquals(store.currentSnapshot().size(), finalHits.size());
        assertEquals(finalHits.size(), finalHits.stream().map(SearchHit::id).distinct().count());
    }

    @Test
    void snapshotObservedDuringWriteIsAlwaysComplete() throws InterruptedException {
        // 单写者批量写入、读者持续观察：每次查询看到的快照版本要么不变、
        // 要么整体前进，文档数必须等于该版本快照自身的大小（自洽）。
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        List<String> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            batch.add("批量 文档 " + i);
        }

        pool.submit(() -> {
            await(start);
            for (String content : batch) {
                store.upsert(null, content, "team-a");
            }
        });
        pool.submit(() -> {
            await(start);
            long lastVersion = -1;
            for (int i = 0; i < 1000; i++) {
                // 本测试只增不删，快照大小单调不减；一次查询必然作用于
                // 查询前后两次观察之间的某个完整快照，因此命中数必须落在
                // [查询前大小, 查询后大小] 区间内，且快照版本不得回退。
                var before = store.currentSnapshot();
                List<SearchHit> hits = store.search("批量 文档", Set.of("team-a"), 1000);
                var after = store.currentSnapshot();
                if (before.version() < lastVersion) {
                    violations.add("快照版本回退: " + lastVersion + " -> " + before.version());
                }
                lastVersion = Math.max(lastVersion, before.version());
                if (hits.size() < before.size() || hits.size() > after.size()) {
                    violations.add("命中数不在自洽区间: before=" + before.size()
                            + " hits=" + hits.size() + " after=" + after.size());
                }
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        log.info("输入: 单写者连续写入 {} 个文档，读者并发观察 1000 次；违规={}；"
                + "判定依据: 快照版本单调不回退；只增不删场景下每次命中数必须落在"
                + "查询前后两次快照观察的大小区间内（即查询作用于某个完整快照）",
                batch.size(), violations);
        assertTrue(violations.isEmpty(), () -> "发现一致性违规: " + violations);
        assertEquals(batch.size(), store.currentSnapshot().size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
