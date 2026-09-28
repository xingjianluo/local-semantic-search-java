package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.embedding.HashingEmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.paging.PageToken;
import com.github.xingjianluo.localsemanticsearchjava.paging.SearchPageResult;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.DefaultIndexValidator;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 翻页期间发生写入、删除或重建时，遍历必须自洽：
 * 要么整轮锚定开始时的同一份快照（前后一致），
 * 要么明确返回 stale 要求重开；不得重复或跳过。
 */
class PagingConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(PagingConcurrencyTest.class);

    private static DocumentStore seededStore(int n) {
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < n; i++) {
            store.upsert(String.format("seed-%05d", i),
                    "并发 翻页 文档 初始 差异 " + (i % 89), i % 2 == 0 ? "team-a" : "team-b");
        }
        return store;
    }

    private static void assertPageInternallyConsistent(List<SearchHit> page, int pageSize) {
        assertTrue(page.size() <= pageSize);
        Set<String> ids = new HashSet<>();
        for (SearchHit h : page) {
            assertTrue(ids.add(h.id()), "单页内不得重复: " + h.id());
        }
    }

    @Test
    void walkersUnderConcurrentWritesEitherStayFrozenOrGetStale() throws Exception {
        final int seed = 800;
        DocumentStore store = seededStore(seed);
        final int walkers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(walkers + 1);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        AtomicInteger frozen = new AtomicInteger();
        AtomicInteger restarted = new AtomicInteger();

        // 写线程：持续 upsert + delete，制造版本推进。
        pool.submit(() -> {
            await(start);
            for (int i = 0; i < 400; i++) {
                String id = (i % 3 == 0) ? String.format("seed-%05d", i % seed)
                        : String.format("new-%05d", i);
                String scope = i % 2 == 0 ? "team-a" : "team-b";
                if (i % 4 == 3) {
                    store.delete(id);
                } else {
                    store.upsert(id, "并发 翻页 文档 写入 差异 " + (i % 53), scope);
                }
            }
        });

        for (int w = 0; w < walkers; w++) {
            final int walker = w;
            pool.submit(() -> {
                await(start);
                int restarts = 0;
                int attempts = 0;
                while (attempts < 500) {
                    attempts++;
                    List<SearchHit> collected = new ArrayList<>();
                    PageToken token = null;
                    Set<String> seen = new HashSet<>();
                    boolean gotStale = false;
                    Long anchorVersion = null;
                    Integer totalCount = null;
                    while (true) {
                        SearchPageResult page = store.searchPage("并发 翻页 文档",
                                Set.of("team-a", "team-b"), null, 31 + walker, token);
                        if (page.stale()) {
                            gotStale = true;
                            break;
                        }
                        if (anchorVersion == null) {
                            anchorVersion = page.indexVersion();
                        } else if (!anchorVersion.equals(page.indexVersion())) {
                            violations.add("同一轮内锚定版本发生变化: "
                                    + anchorVersion + " -> " + page.indexVersion());
                        }
                        if (totalCount == null) {
                            totalCount = page.totalCount();
                        } else if (!totalCount.equals(page.totalCount())) {
                            violations.add("同一轮各页 totalCount 不稳定: "
                                    + totalCount + " -> " + page.totalCount());
                        }
                        assertPageInternallyConsistent(page.hits(), 31 + walker);
                        for (SearchHit h : page.hits()) {
                            if (!seen.add(h.id())) {
                                violations.add("翻页重复命中: " + h.id());
                            }
                        }
                        collected.addAll(page.hits());
                        token = page.nextToken();
                        if (token == null) {
                            break;
                        }
                    }
                    if (gotStale) {
                        restarts++;
                        // 写者仍在推进版本：短暂让出，避免在写窗口内空转耗尽重试次数
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    // 完整翻完（锚定同一份不可变快照）：拼接数必须等于 totalCount，
                    // 且逐页顺序满足全局排序（无整段跳过/重复的更强自洽性）。
                    if (collected.size() != totalCount) {
                        violations.add("完整翻页拼接数 " + collected.size()
                                + " != totalCount " + totalCount);
                    }
                    assertCollectedSorted(collected, violations);
                    frozen.incrementAndGet();
                    break;
                }
                if (attempts >= 500) {
                    violations.add("walker 持续 stale，重试上限内未能完成一轮");
                }
                restarted.addAndGet(restarts);
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务应在限时内完成");
        log.info("输入: {} 篇初始文档，1 个写线程 400 次写/删，{} 个翻页线程并发遍历；"
                + "冻结翻完={} 轮，stale 重来={} 次，违规={}；判定依据: 未报 stale 的一轮必须"
                + "锚定同一版本、各页 totalCount 恒定、不重不漏且拼接数=totalCount；"
                + "版本一旦推进只能明确 stale",
                seed, walkers, frozen.get(), restarted.get(), violations);
        assertTrue(violations.isEmpty(), () -> "发现一致性违规: " + violations);
        assertEquals(walkers, frozen.get(), "每个翻页线程最终都应能完成一轮");
        assertTrue(restarted.get() > 0, "持续写入下应当至少有遍历观察到 stale（否则未验证到分支）");
    }

    private static void assertCollectedSorted(List<SearchHit> hits,
                                              ConcurrentLinkedQueue<String> violations) {
        for (int i = 1; i < hits.size(); i++) {
            SearchHit a = hits.get(i - 1);
            SearchHit b = hits.get(i);
            boolean ordered = a.score() > b.score()
                    || (a.score() == b.score() && a.id().compareTo(b.id()) < 0);
            if (!ordered) {
                violations.add("跨页顺序不满足排序键: " + a + " vs " + b);
                return;
            }
        }
    }

    @Test
    void staleResultIsRaisedWhenIndexVersionMoves() {
        // 确定性场景：翻页途中分别发生 upsert / delete / rebuild，都必须 stale。
        // 1) 写入
        DocumentStore store = seededStore(200);
        PageToken token = firstPageToken(store);
        store.upsert("extra-1", "并发 翻页 文档 额外", "team-a");
        SearchPageResult afterWrite = store.searchPage("并发 翻页 文档",
                Set.of("team-a", "team-b"), null, 25, token);
        assertTrue(afterWrite.stale(), "翻页途中写入必须明确告知本轮作废");
        assertTrue(afterWrite.hits().isEmpty());

        // 2) 删除
        store = seededStore(200);
        token = firstPageToken(store);
        store.delete("seed-00001");
        SearchPageResult afterDelete = store.searchPage("并发 翻页 文档",
                Set.of("team-a", "team-b"), null, 25, token);
        assertTrue(afterDelete.stale(), "翻页途中删除必须明确告知本轮作废");

        // 3) 重建
        store = seededStore(200);
        token = firstPageToken(store);
        RebuildService rebuild = new RebuildService(store, new HashingEmbeddingModel(),
                new DefaultIndexValidator());
        assertEquals("SUCCESS", rebuild.rebuild().status().name());
        SearchPageResult afterRebuild = store.searchPage("并发 翻页 文档",
                Set.of("team-a", "team-b"), null, 25, token);
        assertTrue(afterRebuild.stale(), "翻页途中重建必须明确告知本轮作废");

        // 4) 查询指纹不一致也必须 stale，防止拿别的查询的令牌续翻
        store = seededStore(200);
        token = firstPageToken(store);
        SearchPageResult otherQuery = store.searchPage("完全 不同 的 查询",
                Set.of("team-a", "team-b"), null, 25, token);
        assertTrue(otherQuery.stale());

        // 5) stale 后丢弃旧令牌从头开一轮，能够完整翻完
        SearchPageResult restart = store.searchPage("并发 翻页 文档",
                Set.of("team-a", "team-b"), null, 25, null);
        assertFalse(restart.stale());
        List<SearchHit> all = drain(store, "并发 翻页 文档", Set.of("team-a", "team-b"), 25);
        assertEquals(all.size(), new HashSet<>(all.stream().map(SearchHit::id).toList()).size());
        log.info("输入: 翻页途中分别写入/删除/重建/换查询；判定依据: 四种情形都返回 stale=true "
                + "且本页为空；stale 后丢弃令牌重开可完整翻完（{} 条，无重复）", all.size());
    }

    @Test
    void rebuildDuringWalkDoesNotDuplicateOrSkipOnRestartedWalk() throws Exception {
        final int seed = 500;
        DocumentStore store = seededStore(seed);
        RebuildService rebuild = new RebuildService(store, new HashingEmbeddingModel(),
                new DefaultIndexValidator());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        AtomicInteger rebuilds = new AtomicInteger();

        pool.submit(() -> {
            await(start);
            for (int i = 0; i < 20; i++) {
                if ("SUCCESS".equals(rebuild.rebuild().status().name())) {
                    rebuilds.incrementAndGet();
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        pool.submit(() -> {
            await(start);
            int attempts = 0;
            while (attempts < 30) {
                attempts++;
                List<SearchHit> collected = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                PageToken token = null;
                boolean stale = false;
                while (true) {
                    SearchPageResult page = store.searchPage("并发 翻页 文档",
                            Set.of("team-a", "team-b"), null, 47, token);
                    if (page.stale()) {
                        stale = true;
                        break;
                    }
                    for (SearchHit h : page.hits()) {
                        if (!seen.add(h.id())) {
                            violations.add("重建期间翻页重复: " + h.id());
                        }
                    }
                    collected.addAll(page.hits());
                    token = page.nextToken();
                    if (token == null) {
                        break;
                    }
                }
                if (stale) {
                    continue; // 重建推进了版本：按约定重来，不允许从断点续翻
                }
                List<SearchHit> full = store.search("并发 翻页 文档",
                        Set.of("team-a", "team-b"), 1000);
                if (!full.equals(collected)) {
                    violations.add("重建背景下完整翻页结果与全量检索不一致（漏/重/乱序）");
                }
                return;
            }
            violations.add("30 次重试内未能避开重建完成一轮");
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        log.info("输入: {} 篇文档，后台连续重建 {} 次，翻页线程按 stale 即重来策略遍历；违规={}；"
                + "判定依据: 重建只允许整轮冻结或整轮重来；成功轮拼接结果与全量检索逐位一致",
                seed, rebuilds.get(), violations);
        assertTrue(violations.isEmpty(), () -> "发现一致性违规: " + violations);
        assertTrue(rebuilds.get() > 0, "应当确有重建发生");
    }

    private static PageToken firstPageToken(DocumentStore store) {
        SearchPageResult first = store.searchPage("并发 翻页 文档",
                Set.of("team-a", "team-b"), null, 25, null);
        assertFalse(first.stale());
        assertNotNull(first.nextToken(), "200 篇文档一页 25 条应当还有下一页");
        return first.nextToken();
    }

    private static List<SearchHit> drain(DocumentStore store, String query, Set<String> scopes,
                                         int pageSize) {
        List<SearchHit> all = new ArrayList<>();
        PageToken token = null;
        while (true) {
            SearchPageResult page = store.searchPage(query, scopes, null, pageSize, token);
            assertFalse(page.stale());
            all.addAll(page.hits());
            token = page.nextToken();
            if (token == null) {
                return all;
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
