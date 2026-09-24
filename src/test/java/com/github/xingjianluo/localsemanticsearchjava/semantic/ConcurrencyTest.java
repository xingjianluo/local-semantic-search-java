package com.github.xingjianluo.localsemanticsearchjava.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.localsemanticsearchjava.semantic.core.SemanticSearchService;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchRequest;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.store.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.HashingVectorizer;

/**
 * 并发自洽性测试：并发写入与持续查询期间，不得出现重复命中、越界内容、
 * 总数与列表不一致、已删除文档被召回。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private SemanticSearchService service;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        service = new SemanticSearchService(new DocumentStore(), new HashingVectorizer());
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentWritesAndQueriesStayConsistent() throws Exception {
        int writers = 4;
        int docsPerWriter = 25;
        int queryThreads = 3;
        int queriesPerThread = 100;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(writers + queryThreads);
        AtomicInteger failures = new AtomicInteger();
        List<String> violations = Collections.synchronizedList(new ArrayList<>());

        // 写线程：每个写线程写自己 id 段的文档，scope 在 public / private 间二选一
        for (int w = 0; w < writers; w++) {
            final int writer = w;
            pool.submit(() -> {
                try {
                    awaitQuiet(start);
                    for (int i = 0; i < docsPerWriter; i++) {
                        String id = "w" + writer + "-d" + i;
                        String scope = i % 2 == 0 ? "public" : "private";
                        service.upsert(id, "offline local semantic search note " + writer + " " + i,
                                List.of(scope));
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    log.error("写线程异常", e);
                } finally {
                    finished.countDown();
                }
            });
        }

        // 查询线程：只用 public 范围调用，持续校验自洽性
        for (int q = 0; q < queryThreads; q++) {
            pool.submit(() -> {
                try {
                    awaitQuiet(start);
                    for (int i = 0; i < queriesPerThread; i++) {
                        SearchResult result = service.search(new SearchRequest(
                                "offline local semantic search note", 50, List.of("public")));
                        check(result, violations);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    log.error("查询线程异常", e);
                } finally {
                    finished.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(finished.await(30, TimeUnit.SECONDS), "并发任务必须在超时前结束");

        // 最终状态自洽
        SearchResult finalResult = service.search(new SearchRequest(
                "offline local semantic search note", 100, List.of("public")));
        check(finalResult, violations);

        log.info("[并发读写] 写线程={} 每线程文档={} 查询线程={} 每线程查询={} 最终public命中={} 存储文档数={} 索引文档数={} 违规={}",
                writers, docsPerWriter, queryThreads, queriesPerThread,
                finalResult.totalHits(), service.storedDocumentCount(), service.indexedDocumentCount(),
                violations.size());
        if (!violations.isEmpty()) {
            log.warn("[并发违规样例] {}", violations.subList(0, Math.min(5, violations.size())));
        }
        assertEquals(0, failures.get(), "并发期间不得有异常");
        assertEquals(0, violations.size(), "并发期间查询结果必须始终自洽");
        assertEquals(writers * docsPerWriter, service.storedDocumentCount());
        assertEquals(writers * docsPerWriter, service.indexedDocumentCount());
        int expectedPublic = writers * ((docsPerWriter + 1) / 2); // i 为偶数时 public：0..24 有 13 个
        assertEquals(expectedPublic, finalResult.totalHits(),
                "所有 public 文档都应被召回，private 文档不得越界");
    }

    @Test
    void concurrentDuplicateWritesAreIdempotent() throws Exception {
        int threads = 8;
        int repeats = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Long> versions = ConcurrentHashMap.newKeySet();
        AtomicInteger changedCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    awaitQuiet(start);
                    for (int i = 0; i < repeats; i++) {
                        var r = service.upsert("same-id", "identical local semantic content", List.of("public"));
                        versions.add(r.version());
                        if (r.changed()) {
                            changedCount.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));

        SearchResult result = service.search(
                new SearchRequest("identical local semantic content", 10, List.of("public")));
        long hits = result.hits().stream().filter(h -> h.documentId().equals("same-id")).count();
        log.info("[并发幂等] 线程={} 每线程重复={} changed次数={} 版本集合={} 命中次数={} 判定: changed=1,版本唯一,命中=1",
                threads, repeats, changedCount.get(), versions, hits);
        assertEquals(1, changedCount.get(), "并发重复写入只有一次实际变更");
        assertEquals(1, versions.size(), "只允许产生一个版本");
        assertEquals(1, hits, "不得重复命中");
    }

    @Test
    void concurrentDeleteAndQueryNeverRecallsDeletedDoc() throws Exception {
        service.upsert("victim", "offline local semantic search victim document", List.of("public"));
        int queryThreads = 4;
        int queriesPerThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(queryThreads + 1);
        List<String> violations = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean deleted = new java.util.concurrent.atomic.AtomicBoolean(false);

        for (int q = 0; q < queryThreads; q++) {
            pool.submit(() -> {
                try {
                    awaitQuiet(start);
                    for (int i = 0; i < queriesPerThread; i++) {
                        // 精确边界：查询开始于删除操作完成之后，则绝不能召回 victim。
                        // 删除完成之前已抓取旧快照的在途查询返回旧内容是合法的快照语义。
                        boolean afterDelete = deleted.get();
                        SearchResult r = service.search(new SearchRequest(
                                "victim document", 10, List.of("public")));
                        if (afterDelete
                                && r.hits().stream().anyMatch(h -> h.documentId().equals("victim"))) {
                            violations.add("删除完成之后开始的查询仍召回已删除文档");
                        }
                        check(r, violations);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        pool.submit(() -> {
            try {
                awaitQuiet(start);
                service.delete("victim");
                deleted.set(true);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        SearchResult after = service.search(
                new SearchRequest("victim document", 10, List.of("public")));
        log.info("[并发删除] 删除后召回数={} 期间违规={} 判定全部为0",
                after.hits().stream().filter(h -> h.documentId().equals("victim")).count(),
                violations.size());
        assertTrue(violations.isEmpty(), "删除生效后任何时刻都不得再召回");
        assertEquals(0, service.indexedDocumentCount());
    }

    private static void awaitQuiet(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** 对单个查询快照做自洽性检查。 */
    private static void check(SearchResult result, List<String> violations) {
        List<SearchHit> hits = result.hits();
        Set<String> unique = new HashSet<>();
        for (SearchHit hit : hits) {
            if (!unique.add(hit.documentId())) {
                violations.add("重复命中: " + hit.documentId());
            }
            if (hit.documentId().startsWith("w") && hit.documentId().contains("-d")
                    && Integer.parseInt(hit.documentId().split("-d")[1]) % 2 == 1) {
                violations.add("private 文档越界可见: " + hit.documentId());
            }
        }
        if (hits.size() > result.totalHits()) {
            violations.add("返回条数超过 totalHits");
        }
    }
}
