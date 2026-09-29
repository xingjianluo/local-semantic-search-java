package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.embedding.HashingEmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.DefaultIndexValidator;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildService;
import com.github.xingjianluo.localsemanticsearchjava.scroll.ScrollPage;
import com.github.xingjianluo.localsemanticsearchjava.scroll.ScrollService;
import com.github.xingjianluo.localsemanticsearchjava.scroll.ScrollSessionException;
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
 * 翻页重试语义、数量与过滤条件一致性、范围收紧粘滞、重启作废、
 * 并发下整轮自洽的专项回归测试。
 */
class ScrollRetryTest {

    private static final Logger log = LoggerFactory.getLogger(ScrollRetryTest.class);

    private static final String QUERY = "语义 检索 引擎";

    private static DocumentStore newStoreWithDocs(int n) {
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < n; i++) {
            String scope = i % 2 == 0 ? "team-a" : "team-b";
            store.upsert("doc-" + i, "语义 检索 引擎 文档 编号 " + i + " token" + i, scope);
        }
        return store;
    }

    private static List<String> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::id).toList();
    }

    /** 同一凭据连续重发多次必须逐字节返回同一页，且不影响后续正常推进。 */
    @Test
    void resendingSamePageTokenNeverSkipsAPage() {
        int n = 50;
        DocumentStore store = newStoreWithDocs(n);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        ScrollService service = new ScrollService(store, embedding);
        Set<String> scopes = Set.of("team-a", "team-b");
        List<SearchHit> full = store.currentSnapshot()
                .search(embedding.embed(QUERY), scopes, Integer.MAX_VALUE);

        ScrollPage page0 = service.startScroll(QUERY, scopes, 10, null);
        assertEquals(full.subList(0, 10).stream().map(SearchHit::id).toList(),
                ids(page0.hits()));
        String token1 = page0.nextScrollId();
        assertNotNull(token1);

        // 客户端超时，把同一个翻页请求原样重发三次。
        List<ScrollPage> replays = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            replays.add(service.next(token1, null));
        }
        for (ScrollPage replay : replays) {
            assertEquals(ids(full.subList(10, 20)), ids(replay.hits()),
                    "重发必须返回同一页（第 2 页），不能跳到第 3 页");
            assertEquals(replays.get(0).nextScrollId(), replay.nextScrollId(),
                    "每次重发放出的下一凭据必须相同");
        }
        log.info("输入: 50 篇，页大小 10，对第 2 页凭据连续重发 3 次；"
                + "判定依据: 每次都是第 2 页同一份内容，游标不前进");

        // 之后拿新凭据继续推进，整轮仍不重不漏、顺序与整体检索一致。
        List<SearchHit> collected = new ArrayList<>(page0.hits());
        collected.addAll(replays.get(0).hits());
        String token = replays.get(0).nextScrollId();
        while (token != null) {
            ScrollPage page = service.next(token, null);
            collected.addAll(page.hits());
            token = page.nextScrollId();
        }
        assertEquals(ids(full), ids(collected), "重发后继续翻完仍必须等于整体检索");
        assertEquals(n, new HashSet<>(ids(collected)).size(), "不得重复");
        log.info("重发后继续翻完收得 {} 条，与整体检索完全一致", collected.size());
    }

    /** 末页凭据可反复重放；收紧导致的空末页同样可重放；起始即无结果返回空页。 */
    @Test
    void lastPageAndEmptyFinalPageAreReplayable() {
        DocumentStore store = newStoreWithDocs(20);
        ScrollService service = new ScrollService(store, new HashingEmbeddingModel());
        Set<String> scopes = Set.of("team-a", "team-b");

        ScrollPage page0 = service.startScroll(QUERY, scopes, 10, null);
        String token1 = page0.nextScrollId();
        ScrollPage last = service.next(token1, null);
        assertEquals(10, last.hits().size());
        assertNull(last.nextScrollId(), "已到末页");
        // 末页凭据重放：内容相同、仍为 null 凭据，不会重复"再翻一页"。
        for (int i = 0; i < 3; i++) {
            ScrollPage again = service.next(token1, null);
            assertEquals(ids(last.hits()), ids(again.hits()));
            assertNull(again.nextScrollId());
            assertEquals(last.returned(), again.returned());
            assertEquals(last.totalHits(), again.totalHits());
        }
        log.info("末页（恰好整页）凭据重放 3 次内容一致");

        // 收紧为空范围：剩余位置全部被排除，产生空的末页，且空页可重放。
        DocumentStore store2 = newStoreWithDocs(5);
        ScrollService service2 = new ScrollService(store2, new HashingEmbeddingModel());
        ScrollPage p0 = service2.startScroll(QUERY, scopes, 2, null);
        assertEquals(2, p0.hits().size());
        String t1 = p0.nextScrollId();
        ScrollPage emptyFinal = service2.next(t1, Set.of());
        assertTrue(emptyFinal.hits().isEmpty(), "收紧为空后剩余全部排除，末页为空");
        assertNull(emptyFinal.nextScrollId());
        ScrollPage emptyReplay = service2.next(t1, Set.of());
        assertTrue(emptyReplay.hits().isEmpty());
        assertNull(emptyReplay.nextScrollId());
        assertEquals(emptyFinal.totalHits(), emptyReplay.totalHits());
        assertEquals(emptyFinal.returned(), emptyReplay.returned());
        log.info("收紧为空范围产生空末页，空页重放一致（totalHits={}）",
                emptyFinal.totalHits());

        // 开启时即无任何命中：直接空页、无凭据、总数 0。
        ScrollPage none = service.startScroll(QUERY, Set.of("team-zzz"), 10, null);
        assertTrue(none.hits().isEmpty());
        assertNull(none.nextScrollId());
        assertEquals(0, none.totalHits());
    }

    /** 收紧后数量信息立即按当前生效条件统计，且收紧粘滞、越界内容永不回弹。 */
    @Test
    void tighteningUpdatesTotalHitsAndSticksAcrossLaterPages() {
        int n = 300;
        DocumentStore store = newStoreWithDocs(n);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        Set<String> both = Set.of("team-a", "team-b");
        float[] qv = embedding.embed(QUERY);
        List<SearchHit> full = store.currentSnapshot().search(qv, both, Integer.MAX_VALUE);
        List<SearchHit> onlyA = store.currentSnapshot()
                .search(qv, Set.of("team-a"), Integer.MAX_VALUE);
        long countA = full.stream().filter(h -> h.scope().equals("team-a")).count();
        assertEquals(onlyA.size(), countA);

        ScrollService service = new ScrollService(store, embedding);
        ScrollPage page0 = service.startScroll(QUERY, both, 20, null);
        assertEquals(full.size(), page0.totalHits(), "初始范围下总数为全部命中");

        String token1 = page0.nextScrollId();
        // 第二页起收紧为仅 team-a：数量立即变成 team-a 的总数。
        ScrollPage page1 = service.next(token1, Set.of("team-a"));
        assertEquals(countA, page1.totalHits(), "收紧后 totalHits 必须按新范围统计");
        assertTrue(page1.hits().stream().allMatch(h -> h.scope().equals("team-a")));

        // 后续页省略范围：必须粘滞在 team-a，b 不得"冒回来"；总数保持 team-a 计数。
        String token2 = page1.nextScrollId();
        ScrollPage page2 = service.next(token2, null);
        assertEquals(countA, page2.totalHits());
        assertTrue(page2.hits().stream().allMatch(h -> h.scope().equals("team-a")),
                "省略 visibleScopes 时收紧必须粘滞，被排除内容不得回弹");

        // 即使请求里把 b 又带回来（仍是初始范围子集），生效范围只做交集，b 依旧不回。
        String token3 = page2.nextScrollId();
        ScrollPage page3 = service.next(token3, both);
        assertEquals(countA, page3.totalHits(), "收紧不可放松，数量不得回升");
        assertTrue(page3.hits().stream().allMatch(h -> h.scope().equals("team-a")));
        log.info("输入: 初始 {} 两范围，首页后收紧为 team-a；totalHits {} -> {}，"
                        + "省略范围与再带 b 均不回弹；判定依据: 数量随生效条件、收紧单调粘滞",
                n, full.size(), countA);

        // 翻完：team-a 不重不漏，相对顺序等于一次性 team-a 检索。
        List<SearchHit> collectedA = new ArrayList<>();
        page0.hits().stream().filter(h -> h.scope().equals("team-a")).forEach(collectedA::add);
        collectedA.addAll(page1.hits());
        collectedA.addAll(page2.hits());
        collectedA.addAll(page3.hits());
        String token = page3.nextScrollId();
        while (token != null) {
            ScrollPage page = service.next(token, null); // 一路省略，持续粘滞
            page.hits().forEach(h -> assertEquals("team-a", h.scope()));
            collectedA.addAll(page.hits());
            token = page.nextScrollId();
        }
        assertEquals(ids(onlyA), ids(collectedA), "收紧后 team-a 不重不漏且顺序一致");
    }

    /** 收紧 + 门槛同时生效时，数量只统计当前范围且达到门槛的命中。 */
    @Test
    void tighteningCountAlsoRespectsMinScore() {
        int n = 200;
        DocumentStore store = newStoreWithDocs(n);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        Set<String> both = Set.of("team-a", "team-b");
        float[] qv = embedding.embed(QUERY);
        List<SearchHit> full = store.currentSnapshot().search(qv, both, Integer.MAX_VALUE);
        double threshold = full.get(full.size() / 2).score();
        int expectedA = store.currentSnapshot()
                .search(qv, Set.of("team-a"), Integer.MAX_VALUE, threshold).size();
        int expectedBoth = store.currentSnapshot()
                .search(qv, both, Integer.MAX_VALUE, threshold).size();

        ScrollService service = new ScrollService(store, embedding);
        ScrollPage page0 = service.startScroll(QUERY, both, 15, threshold);
        assertEquals(expectedBoth, page0.totalHits());
        ScrollPage page1 = service.next(page0.nextScrollId(), Set.of("team-a"));
        assertEquals(expectedA, page1.totalHits(),
                "收紧后的数量必须同时满足当前范围与全轮固定门槛");
        assertTrue(page1.hits().stream().allMatch(h -> h.score() >= threshold));
        log.info("门槛+收紧叠加: totalHits {} -> {}（门槛={}）",
                expectedBoth, expectedA, threshold);
    }

    /** 服务重启（会话丢失）后旧凭据明确报错要求重来；相同数据相同查询重开顺序一致。 */
    @Test
    void restartInvalidatesOldTokenAndFreshRoundReproducesOrder() {
        int n = 100;
        DocumentStore store = newStoreWithDocs(n);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        ScrollService serviceA = new ScrollService(store, embedding);
        ScrollPage first = serviceA.startScroll(QUERY, Set.of("team-a", "team-b"), 13, null);
        List<SearchHit> beforeRestart = new ArrayList<>(first.hits());
        String token = first.nextScrollId();
        beforeRestart.addAll(serviceA.next(token, null).hits());

        // 模拟重启：同样的数据，但会话表是全新空表。
        ScrollService serviceB = new ScrollService(store, embedding);
        ScrollSessionException ex = assertThrows(ScrollSessionException.class,
                () -> serviceB.next(token, null));
        assertTrue(ex.getMessage().toLowerCase().contains("start a new scroll")
                        || ex.getMessage().contains("重新开启"),
                "必须明确告知调用方这一轮已失效、需要重新开启: " + ex.getMessage());
        log.info("重启后旧凭据被明确拒绝: {}", ex.getMessage());

        // 用同样数据同样查询重新开始：顺序与重启前整体结果一致。
        List<SearchHit> expected = store.currentSnapshot()
                .search(embedding.embed(QUERY), Set.of("team-a", "team-b"), Integer.MAX_VALUE);
        ScrollPage fresh = serviceB.startScroll(QUERY, Set.of("team-a", "team-b"), 31, null);
        List<SearchHit> rebuilt = new ArrayList<>(fresh.hits());
        String t = fresh.nextScrollId();
        while (t != null) {
            ScrollPage page = serviceB.next(t, null);
            rebuilt.addAll(page.hits());
            t = page.nextScrollId();
        }
        assertEquals(ids(expected), ids(rebuilt));
    }

    /** 翻页期间并发写入/删除/重建，且每页凭据被多线程同时重放：整轮固定、重放一致。 */
    @Test
    void concurrentWritesAndDuplicateRequestsStayConsistent() throws Exception {
        int n = 500;
        DocumentStore store = newStoreWithDocs(n);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        RebuildService rebuild = new RebuildService(store, embedding, new DefaultIndexValidator());
        ScrollService service = new ScrollService(store, embedding);
        Set<String> scopes = Set.of("team-a", "team-b");

        IndexSnapshot pinned = store.currentSnapshot();
        float[] qv = embedding.embed(QUERY);
        List<SearchHit> expected = pinned.search(qv, scopes, Integer.MAX_VALUE);
        long pinnedVersion = pinned.version();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService writers = Executors.newFixedThreadPool(2);
        writers.submit(() -> {
            await(start);
            for (int i = 0; i < 400; i++) {
                store.upsert("doc-" + (i % 600), "被改写的全新内容 rewrite " + i,
                        i % 2 == 0 ? "team-a" : "team-b");
                if (i % 7 == 0) {
                    store.delete("doc-" + ((i + 3) % 500));
                }
            }
        });
        writers.submit(() -> {
            await(start);
            rebuild.rebuild();
            rebuild.rebuild();
        });
        start.countDown();

        ScrollPage page0 = service.startScroll(QUERY, scopes, 41, null);
        List<SearchHit> collected = new ArrayList<>(page0.hits());
        Set<Long> versions = new HashSet<>(List.of(page0.indexVersion()));
        String token = page0.nextScrollId();

        int hammerThreads = 8;
        ExecutorService hammers = Executors.newFixedThreadPool(hammerThreads);
        while (token != null) {
            final String currentToken = token;
            ConcurrentLinkedQueue<List<String>> replies = new ConcurrentLinkedQueue<>();
            CountDownLatch done = new CountDownLatch(hammerThreads);
            for (int i = 0; i < hammerThreads; i++) {
                hammers.submit(() -> {
                    try {
                        ScrollPage p = service.next(currentToken, null);
                        List<String> replyIds = ids(p.hits());
                        replies.add(replyIds);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            // 同一时刻 8 个重复请求拿到的必须是同一页（可能其中 1 个触发生成，其余重放）。
            List<List<String>> allReplies = new ArrayList<>(replies);
            assertEquals(hammerThreads, allReplies.size());
            for (List<String> reply : allReplies) {
                assertEquals(allReplies.get(0), reply, "并发重复请求必须返回同一页");
            }
            // 用一个规范响应推进，避免重复累加同一页。
            ScrollPage canonical = service.next(currentToken, null);
            assertEquals(allReplies.get(0), ids(canonical.hits()));
            collected.addAll(canonical.hits());
            versions.add(canonical.indexVersion());
            token = canonical.nextScrollId();
        }
        hammers.shutdown();
        writers.shutdown();
        assertTrue(writers.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(hammers.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(ids(expected), ids(collected), "并发写入/删除/重建下整轮必须自洽");
        assertEquals(Set.of(pinnedVersion), versions, "整轮版本恒定");
        log.info("并发改写/删除/2 次重建 + 每页 8 路同时重放：收得 {} 条，版本集合 {}，不重不漏",
                collected.size(), versions);
    }

    /** 乱序/伪造页码被拒绝；损坏凭据按会话失效处理（404）。 */
    @Test
    void outOfOrderAndMalformedTokensAreRejected() {
        DocumentStore store = newStoreWithDocs(30);
        ScrollService service = new ScrollService(store, new HashingEmbeddingModel());
        ScrollPage first = service.startScroll(QUERY, Set.of("team-a", "team-b"), 10, null);
        String token1 = first.nextScrollId();
        String sessionPrefix = token1.substring(0, token1.lastIndexOf('.'));

        // 第 9 页从未签发：页码乱序 → 400。
        assertThrows(IllegalArgumentException.class,
                () -> service.next(sessionPrefix + ".9", null));
        // 凭据损坏无法解析：按失效处理 → ScrollSessionException（404）。
        assertThrows(ScrollSessionException.class, () -> service.next("garbage", null));
        assertThrows(ScrollSessionException.class, () -> service.next(null, null));
        log.info("乱序页码拒绝(400)、损坏/空凭据拒绝(404)");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
