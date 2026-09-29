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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分页遍历（scroll）：完整不重不漏、顺序跨进程可复现、相关度门槛边界、
 * 门槛与可见范围叠加及收紧、并发写入/删除/重建下整轮自洽。
 */
class ScrollServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ScrollServiceTest.class);

    private static final String QUERY = "语义 检索 引擎";

    private static DocumentStore newStoreWithDocs(int n) {
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < n; i++) {
            String scope = i % 2 == 0 ? "team-a" : "team-b";
            store.upsert("doc-" + i, "语义 检索 引擎 文档 编号 " + i + " token" + i, scope);
        }
        return store;
    }

    /** 把一整轮从第一页翻到末页，拼接全部命中。 */
    private static List<SearchHit> drain(ScrollService service, ScrollPage first,
                                        java.util.function.Function<String, ScrollPage> nextFn) {
        List<SearchHit> all = new ArrayList<>(first.hits());
        String id = first.nextScrollId();
        while (id != null) {
            ScrollPage page = nextFn.apply(id);
            all.addAll(page.hits());
            id = page.nextScrollId();
        }
        return all;
    }

    private static List<String> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::id).toList();
    }

    @Test
    void pagingDrainsAllResultsWithoutDuplicateOrGap() {
        int n = 3000;
        DocumentStore store = newStoreWithDocs(n);
        ScrollService service = new ScrollService(store, new HashingEmbeddingModel());
        Set<String> scopes = Set.of("team-a", "team-b");

        IndexSnapshot snapshot = store.currentSnapshot();
        float[] qv = new HashingEmbeddingModel().embed(QUERY);
        List<SearchHit> full = snapshot.search(qv, scopes, Integer.MAX_VALUE);

        ScrollPage first = service.startScroll(QUERY, scopes, 37, null);
        log.info("输入: {} 篇文档（两个范围），页大小 37，开启遍历；首页返回 {} 条，totalHits={}；"
                + "判定依据: 跨页拼接必须等于一次性整体检索，不重不漏，顺序完全一致，"
                + "数千条量级也成立",
                n, first.hits().size(), first.totalHits());

        List<SearchHit> paged = drain(service, first, id -> service.next(id, null));

        assertEquals(full.size(), first.totalHits());
        assertEquals(full.size(), paged.size(), "翻页拼起来必须覆盖全部命中");
        assertEquals(ids(full), ids(paged), "跨页顺序必须与整体检索完全一致");
        assertEquals(full.size(), new HashSet<>(ids(paged)).size(), "不得有重复命中");
        for (int i = 1; i < paged.size(); i++) {
            SearchHit prev = paged.get(i - 1);
            SearchHit curr = paged.get(i);
            assertTrue(prev.score() > curr.score()
                            || (prev.score() == curr.score() && prev.id().compareTo(curr.id()) < 0),
                    "拼接结果必须满足全局排序: " + prev + " vs " + curr);
        }
        log.info("校验通过: 拼接 {} 条 = 整体 {} 条，无重复无缺口，顺序一致", paged.size(), full.size());
    }

    @Test
    void orderIsReproducibleAcrossProcessRestart() {
        int n = 500;
        // 进程 A
        DocumentStore storeA = newStoreWithDocs(n);
        ScrollService serviceA = new ScrollService(storeA, new HashingEmbeddingModel());
        List<SearchHit> runA = drain(serviceA,
                serviceA.startScroll(QUERY, Set.of("team-a", "team-b"), 50, null),
                id -> serviceA.next(id, null));

        // 进程 B（模拟重启：全新 store，写入相同数据）
        DocumentStore storeB = newStoreWithDocs(n);
        ScrollService serviceB = new ScrollService(storeB, new HashingEmbeddingModel());
        List<SearchHit> runB = drain(serviceB,
                serviceB.startScroll(QUERY, Set.of("team-a", "team-b"), 128, null),
                id -> serviceB.next(id, null));

        log.info("输入: 相同 {} 篇数据 + 相同查询，两个独立进程分别翻页（页大小 50 vs 128）；"
                + "A={} 条 B={} 条；判定依据: 确定性向量化与排序使重启后先后顺序完全相同，"
                + "且与页大小无关", n, runA.size(), runB.size());
        assertEquals(ids(runA), ids(runB));
        // 得分也逐位相同。
        for (int i = 0; i < runA.size(); i++) {
            assertEquals(runA.get(i).score(), runB.get(i).score(), 0.0,
                    "相同输入下得分必须逐位一致");
        }
    }

    @Test
    void minScoreBoundaryIsInclusiveAndFiltersCount() {
        DocumentStore store = newStoreWithDocs(200);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        Set<String> scopes = Set.of("team-a", "team-b");
        IndexSnapshot snapshot = store.currentSnapshot();
        float[] qv = embedding.embed(QUERY);
        List<SearchHit> full = snapshot.search(qv, scopes, Integer.MAX_VALUE);
        SearchHit boundary = full.get(full.size() / 2);
        double threshold = boundary.score(); // 取一个真实存在的得分做边界

        ScrollService service = new ScrollService(store, embedding);
        ScrollPage first = service.startScroll(QUERY, scopes, 25, threshold);
        List<SearchHit> paged = drain(service, first, id -> service.next(id, null));
        List<SearchHit> expected = snapshot.search(qv, scopes, Integer.MAX_VALUE, threshold);

        log.info("输入: 门槛取中位文档得分={}（含边界）；整体 {} 条，门槛后期望 {} 条，翻页 {} 条，"
                + "totalHits={}；判定依据: 门槛为 >=（含边界），低于门槛者既不出现也不计入总数",
                threshold, full.size(), expected.size(), paged.size(), first.totalHits());
        assertEquals(expected.size(), paged.size());
        assertEquals(expected.size(), first.totalHits());
        assertEquals(ids(expected), ids(paged));
        assertTrue(paged.stream().allMatch(h -> h.score() >= threshold));
        assertTrue(paged.stream().anyMatch(h -> Math.abs(h.score() - threshold) < 1e-12),
                "恰好等于门槛的文档必须保留（含边界）");

        // 门槛再高一丁点：边界文档被排除。
        ScrollPage above = service.startScroll(QUERY, scopes, 25, Math.nextUp(threshold));
        List<SearchHit> pagedAbove = drain(service, above, id -> service.next(id, null));
        assertTrue(pagedAbove.stream().noneMatch(h -> h.score() <= threshold),
                "高于边界的门槛必须排除边界及以下文档");
        log.info("边界上移一ulp 后剩余 {} 条；判定依据: 门槛之上才保留", pagedAbove.size());
    }

    @Test
    void minScoreCombinesWithVisibilityAndScopeTightening() {
        DocumentStore store = newStoreWithDocs(300);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        Set<String> initial = Set.of("team-a", "team-b");
        float[] qv = embedding.embed(QUERY);
        IndexSnapshot snapshot = store.currentSnapshot();
        List<SearchHit> full = snapshot.search(qv, initial, Integer.MAX_VALUE);
        double threshold = full.get(full.size() / 3).score();
        // 收紧后（仅 team-a）且叠加门槛的期望序列。
        List<SearchHit> expectedA = snapshot.search(qv, Set.of("team-a"),
                Integer.MAX_VALUE, threshold);

        ScrollService service = new ScrollService(store, embedding);
        // 第一页先以初始范围（a+b）+门槛翻；从第二页起收紧为仅 team-a。
        ScrollPage first = service.startScroll(QUERY, initial, 31, threshold);
        List<SearchHit> collected = new ArrayList<>();
        // 第一页里属于 team-a 的部分（b 在收紧前可能已被翻过，这部分允许带出）。
        first.hits().stream().filter(h -> h.scope().equals("team-a")).forEach(collected::add);
        String id = first.nextScrollId();
        while (id != null) {
            ScrollPage page = service.next(id, Set.of("team-a")); // 收紧
            for (SearchHit h : page.hits()) {
                assertEquals("team-a", h.scope(), "收紧后越界内容不得再被翻出");
            }
            collected.addAll(page.hits());
            id = page.nextScrollId();
        }

        log.info("输入: 初始范围 {}，门槛={}，第一页后收紧为 [team-a]；收紧后(team-a)期望 {} 条，"
                + "实际收得 team-a {} 条；判定依据: 收紧后不越界、对仍可见文档不重不漏，"
                + "其相对顺序与一次性 team-a 检索一致",
                initial, threshold, expectedA.size(), collected.size());
        // 收紧后翻到的 team-a 文档：不重复，且是期望序列的一个前缀子集（首页 team-a 部分 + 后续）。
        assertEquals(collected.size(), new HashSet<>(ids(collected)).size(), "不得重复");
        assertEquals(ids(expectedA), ids(collected),
                "收紧后 team-a 的相对顺序/覆盖必须与一次性 team-a 检索一致");

        // 试图扩大范围必须被拒绝。
        ScrollPage probe = service.startScroll(QUERY, Set.of("team-a"), 10, threshold);
        assert probe.nextScrollId() != null;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.next(probe.nextScrollId(), Set.of("team-a", "team-b")));
        log.info("扩大范围被拒绝: {}；判定依据: 一轮内只允许收紧可见范围", ex.getMessage());
    }

    @Test
    void scrollStaysConsistentUnderConcurrentWritesDeletesAndRebuild() throws Exception {
        DocumentStore store = newStoreWithDocs(500);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        RebuildService rebuild = new RebuildService(store, embedding, new DefaultIndexValidator());
        ScrollService service = new ScrollService(store, embedding);
        Set<String> scopes = Set.of("team-a", "team-b");

        // 遍历开始前固定期望：当时快照上的整体结果。
        IndexSnapshot pinned = store.currentSnapshot();
        float[] qv = embedding.embed(QUERY);
        List<SearchHit> expected = pinned.search(qv, scopes, Integer.MAX_VALUE);
        long pinnedVersion = pinned.version();

        ScrollPage first = service.startScroll(QUERY, scopes, 41, null);
        List<SearchHit> collected = new ArrayList<>(first.hits());
        List<Long> versions = new ArrayList<>();
        versions.add(first.indexVersion());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            await(start);
            for (int i = 0; i < 400; i++) {
                store.upsert("doc-" + (i % 600), "被改写的全新内容 rewrite " + i,
                        i % 2 == 0 ? "team-a" : "team-b");
                if (i % 7 == 0) {
                    store.delete("doc-" + ((i + 3) % 500));
                }
            }
        });
        pool.submit(() -> {
            await(start);
            rebuild.rebuild();
            rebuild.rebuild();
        });
        start.countDown();

        String id = first.nextScrollId();
        while (id != null) {
            ScrollPage page = service.next(id, null);
            collected.addAll(page.hits());
            versions.add(page.indexVersion());
            id = page.nextScrollId();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        log.info("输入: 翻页期间并发 400 次改写/删除并重建 2 次；遍历固定版本={}，收得 {} 条，"
                + "期望 {} 条；各页版本集合={}；判定依据: 整轮按开始时快照给出一致结果，"
                + "不重不漏、版本恒定，不受写入/删除/重建搅乱",
                pinnedVersion, collected.size(), expected.size(),
                new HashSet<>(versions));
        assertEquals(expected.size(), collected.size());
        assertEquals(ids(expected), ids(collected));
        assertEquals(expected.size(), new HashSet<>(ids(collected)).size());
        assertEquals(Set.of(pinnedVersion), new HashSet<>(versions), "整轮必须固定在开始时的快照版本");

        // 新一轮遍历看到的是变更后的当前状态（固定的是"开始时"状态）。
        ScrollPage fresh = service.startScroll(QUERY, scopes, 1000, null);
        List<SearchHit> freshAll = drain(service, fresh, sid -> service.next(sid, null));
        float[] qv2 = embedding.embed(QUERY);
        List<SearchHit> currentFull = store.currentSnapshot().search(qv2, scopes, Integer.MAX_VALUE);
        assertEquals(ids(currentFull), ids(freshAll));
        log.info("新一轮遍历反映最新状态: {} 条；判定依据: 旧轮固定旧状态，新轮固定新状态",
                freshAll.size());
    }

    @Test
    void unknownAndClosedScrollIdsAreRejected() {
        DocumentStore store = newStoreWithDocs(50);
        ScrollService service = new ScrollService(store, new HashingEmbeddingModel());
        assertThrows(ScrollSessionException.class, () -> service.next("no-such-id", null));

        ScrollPage first = service.startScroll(QUERY, Set.of("team-a"), 10, null);
        String id = first.nextScrollId();
        assertNotNull(id);
        service.close(id);
        assertThrows(ScrollSessionException.class, () -> service.next(id, null));
        // close 幂等。
        assertDoesNotThrow(() -> service.close(id));
        log.info("输入: 未知凭据、显式关闭后再翻页、重复关闭；判定依据: 未知/失效会话明确报错，"
                + "关闭幂等");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 缺陷一：同一个翻页请求被超时重发时，必须返回同一页（幂等），
    // 只有换用响应里的新凭据主动翻页时才推进；不重不漏、末页/空页同理。
    // ------------------------------------------------------------------

    @Test
    void repeatedSamePageRequestReturnsSamePageAndDoesNotSkip() {
        int n = 100;
        DocumentStore store = newStoreWithDocs(n);
        ScrollService service = new ScrollService(store, new HashingEmbeddingModel());
        Set<String> scopes = Set.of("team-a", "team-b");
        IndexSnapshot snapshot = store.currentSnapshot();
        List<SearchHit> full = snapshot.search(new HashingEmbeddingModel().embed(QUERY),
                scopes, Integer.MAX_VALUE);

        ScrollPage p0 = service.startScroll(QUERY, scopes, 20, null);
        String token1 = p0.nextScrollId();
        assertNotNull(token1);

        // 同一页凭据连续重发 5 次：逐字段一致，游标绝不前进。
        ScrollPage p1 = service.next(token1, null);
        for (int i = 0; i < 5; i++) {
            ScrollPage replay = service.next(token1, null);
            assertEquals(p1, replay, "第 " + i + " 次重发必须返回同一页");
            assertEquals(p1.nextScrollId(), replay.nextScrollId());
            assertEquals(p1.totalHits(), replay.totalHits());
            assertEquals(p1.returned(), replay.returned());
        }

        // 只有主动使用新凭据才推进；每页都再重发一次，拼起来依旧不重不漏。
        List<SearchHit> collected = new ArrayList<>(p0.hits());
        collected.addAll(p1.hits());
        String token = p1.nextScrollId();
        while (token != null) {
            ScrollPage page = service.next(token, null);
            ScrollPage replay = service.next(token, null); // 重放
            assertEquals(page, replay);
            collected.addAll(page.hits());
            token = page.nextScrollId();
        }

        log.info("输入: {} 篇，页大小 20，每个凭据至少请求两次（含连续 6 次请求第 2 页）；"
                + "收得 {} 条，整体 {} 条；判定依据: 重发同一凭据永远返回同一页不跳页，"
                + "主动换新凭据才推进，整轮不重不漏且顺序一致",
                n, collected.size(), full.size());
        assertEquals(ids(full), ids(collected));
        assertEquals(full.size(), new HashSet<>(ids(collected)).size());
    }

    @Test
    void lastPageAndEmptyTailPagesAreReplayable() {
        // 10 篇、页大小 3：3,3,3,1 —— 末页只有 1 条且 nextScrollId 为 null。
        DocumentStore store = newStoreWithDocs(10);
        ScrollService service = new ScrollService(store, new HashingEmbeddingModel());
        Set<String> scopes = Set.of("team-a", "team-b");

        ScrollPage p0 = service.startScroll(QUERY, scopes, 3, null);
        String lastToken = null;
        String token = p0.nextScrollId();
        ScrollPage last = null;
        while (token != null) {
            last = service.next(token, null);
            lastToken = token;
            token = last.nextScrollId();
        }
        assertNotNull(last);
        assertEquals(1, last.hits().size());
        assertNull(last.nextScrollId(), "末页凭据必须为 null");

        // 末页凭据可反复重放，内容稳定。
        for (int i = 0; i < 3; i++) {
            ScrollPage replay = service.next(lastToken, null);
            assertEquals(last, replay);
            assertNull(replay.nextScrollId());
        }

        // 收紧到空集合：中间页与末页都可能是空页；空页也要能稳定重放，
        // 直到 nextScrollId 为 null，且 totalHits 随收紧变为 0。
        ScrollPage q0 = service.startScroll(QUERY, scopes, 3, null);
        assertEquals(10, q0.totalHits());
        String t = q0.nextScrollId();
        ScrollPage page = service.next(t, Set.of()); // 收紧为空
        assertEquals(0, page.totalHits(), "收紧为空后数量必须立即变为 0");
        String emptyFinalToken = t;
        while (page.nextScrollId() != null) {
            t = page.nextScrollId();
            page = service.next(t, null); // 粘性：不再声明范围也维持空范围
            assertEquals(0, page.hits().size(), "收紧为空后不得再带出任何文档");
            assertEquals(0, page.totalHits());
            emptyFinalToken = t;
        }
        assertNull(page.nextScrollId());
        ScrollPage emptyReplay = service.next(emptyFinalToken, null);
        assertEquals(page, emptyReplay, "空末页必须可幂等重放");
        assertNull(emptyReplay.nextScrollId());

        // 开启时就没有任何命中：首页即末页，空页且无凭据。
        ScrollPage none = service.startScroll(QUERY, Set.of("team-c"), 3, null);
        assertTrue(none.hits().isEmpty());
        assertNull(none.nextScrollId());
        assertEquals(0, none.totalHits());
        assertEquals(0, none.returned());
        log.info("输入: 末页重放、收紧到空范围后的空页链路、零命中开启；判定依据: "
                + "末页/空页均可稳定重放，空页 totalHits=0，null 凭据标志结束");
    }

    @Test
    void jumpAndMalformedTokensAreRejectedRatherThanSilentlySkipping() {
        DocumentStore store = newStoreWithDocs(50);
        ScrollService service = new ScrollService(store, new HashingEmbeddingModel());
        ScrollPage first = service.startScroll(QUERY, Set.of("team-a", "team-b"), 10, null);
        String token1 = first.nextScrollId();

        // 格式非法。
        assertThrows(ScrollSessionException.class, () -> service.next("not-a-token", null));
        assertThrows(ScrollSessionException.class, () -> service.next("uuid:notanint", null));

        // 跳页：第 2 页还没生成就直接要第 5 页 —— 必须明确报错，绝不静默跳段。
        String sid = token1.substring(0, token1.lastIndexOf(':'));
        assertThrows(ScrollSessionException.class, () -> service.next(sid + ":4", null));

        // 整轮结束后再拿未来页码来要 —— 明确告知轮次已结束。
        service.next(token1, null);
        String t = token1;
        ScrollPage p = service.next(t, null);
        while (p.nextScrollId() != null) {
            p = service.next(p.nextScrollId(), null);
        }
        assertThrows(ScrollSessionException.class, () -> service.next(sid + ":99", null));
        log.info("输入: 非法凭据、跳页凭据、轮次结束后的未来页码；判定依据: 一律明确报错，"
                + "不静默跳段或重复返回");
    }

    // ------------------------------------------------------------------
    // 缺陷二：收紧可见范围后，数量必须与当前生效条件一致，且收紧是粘性的。
    // ------------------------------------------------------------------

    @Test
    void tighteningIsStickyAndTotalHitsAlwaysReflectsEffectiveConditions() {
        int n = 300;
        DocumentStore store = newStoreWithDocs(n);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        Set<String> initial = Set.of("team-a", "team-b");
        IndexSnapshot snapshot = store.currentSnapshot();
        float[] qv = embedding.embed(QUERY);
        List<SearchHit> expectedA = snapshot.search(qv, Set.of("team-a"), Integer.MAX_VALUE);

        ScrollService service = new ScrollService(store, embedding);
        ScrollPage p0 = service.startScroll(QUERY, initial, 17, null);
        assertEquals(n, p0.totalHits(), "初始 totalHits 按初始范围计算");

        List<SearchHit> collected = new ArrayList<>();
        p0.hits().stream().filter(h -> h.scope().equals("team-a")).forEach(collected::add);

        // 第二页收紧为 team-a；此后所有请求都不再声明范围（null），验证粘性。
        String token = p0.nextScrollId();
        boolean firstTightened = true;
        while (token != null) {
            ScrollPage page = firstTightened
                    ? service.next(token, Set.of("team-a"))
                    : service.next(token, null); // 不重新声明，范围也不得回退
            firstTightened = false;
            assertEquals(expectedA.size(), page.totalHits(),
                    "收紧后每一页 totalHits 必须等于当前生效条件下的总数");
            for (SearchHit h : page.hits()) {
                assertEquals("team-a", h.scope(), "粘性收紧：越界内容不得复活");
            }
            collected.addAll(page.hits());
            token = page.nextScrollId();
        }

        log.info("输入: {} 篇（a/b 各半），第二页收紧为 team-a 后不再声明范围；"
                + "收紧后 totalHits={}，收得 team-a {} 条；判定依据: 收紧粘性、"
                + "数量始终对应当前范围、越界内容永不复活、team-a 不重不漏",
                n, expectedA.size(), collected.size());
        assertEquals(expectedA.size(), collected.size());
        assertEquals(ids(expectedA), ids(collected));
        assertEquals(expectedA.size(), new HashSet<>(ids(collected)).size());
    }

    @Test
    void totalHitsAfterTighteningAlsoAccountsForFixedThreshold() {
        int n = 200;
        DocumentStore store = newStoreWithDocs(n);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        IndexSnapshot snapshot = store.currentSnapshot();
        float[] qv = embedding.embed(QUERY);
        List<SearchHit> full = snapshot.search(qv, Set.of("team-a", "team-b"),
                Integer.MAX_VALUE);
        double threshold = full.get(full.size() / 3).score();
        List<SearchHit> expectedA = snapshot.search(qv, Set.of("team-a"),
                Integer.MAX_VALUE, threshold);

        ScrollService service = new ScrollService(store, embedding);
        ScrollPage p0 = service.startScroll(QUERY, Set.of("team-a", "team-b"), 13, threshold);
        List<SearchHit> expectedAll = snapshot.search(qv, Set.of("team-a", "team-b"),
                Integer.MAX_VALUE, threshold);
        assertEquals(expectedAll.size(), p0.totalHits(), "首页数量按初始范围+门槛");

        String token = p0.nextScrollId();
        while (token != null) {
            ScrollPage page = service.next(token, Set.of("team-a"));
            assertEquals(expectedA.size(), page.totalHits(),
                    "收紧后数量必须同时反映收紧范围与固定门槛");
            assertTrue(page.hits().stream().allMatch(h -> h.score() >= threshold));
            token = page.nextScrollId();
        }
        log.info("输入: {} 篇，门槛={}，翻页中收紧为 team-a；收紧后 totalHits={}；"
                + "判定依据: 数量信息 = 当前可见范围 + 固定门槛下的真实条数",
                n, threshold, expectedA.size());
    }

    // ------------------------------------------------------------------
    // 缺陷三：并发写/删/重建下整轮自洽；重放并发安全。
    // ------------------------------------------------------------------

    @Test
    void concurrentRepeatedRequestsStayIdempotentAndRoundStaysPinned() throws Exception {
        DocumentStore store = newStoreWithDocs(500);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        RebuildService rebuild = new RebuildService(store, embedding, new DefaultIndexValidator());
        ScrollService service = new ScrollService(store, embedding);
        Set<String> scopes = Set.of("team-a", "team-b");

        IndexSnapshot pinned = store.currentSnapshot();
        List<SearchHit> expected = pinned.search(embedding.embed(QUERY), scopes,
                Integer.MAX_VALUE);

        ScrollPage first = service.startScroll(QUERY, scopes, 41, null);
        List<SearchHit> collected = new ArrayList<>(first.hits());
        List<Long> versions = new ArrayList<>();
        versions.add(first.indexVersion());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService mutators = Executors.newFixedThreadPool(2);
        mutators.submit(() -> {
            await(start);
            for (int i = 0; i < 300; i++) {
                store.upsert("doc-" + (i % 600), "并发改写 brand new rewrite " + i,
                        i % 2 == 0 ? "team-a" : "team-b");
                if (i % 5 == 0) {
                    store.delete("doc-" + ((i + 9) % 500));
                }
            }
        });
        mutators.submit(() -> {
            await(start);
            rebuild.rebuild();
        });
        start.countDown();

        // 每一页用 6 个线程并发重放同一凭据：所有响应必须完全相同。
        ExecutorService readers = Executors.newFixedThreadPool(6);
        String token = first.nextScrollId();
        while (token != null) {
            final String t = token;
            List<java.util.concurrent.Future<ScrollPage>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(readers.submit(() -> service.next(t, null)));
            }
            ScrollPage canonical = null;
            for (java.util.concurrent.Future<ScrollPage> f : futures) {
                ScrollPage page = f.get(60, TimeUnit.SECONDS);
                if (canonical == null) {
                    canonical = page;
                } else {
                    assertEquals(canonical, page, "并发重放同一凭据必须返回同一页");
                }
            }
            collected.addAll(canonical.hits());
            versions.add(canonical.indexVersion());
            token = canonical.nextScrollId();
        }
        readers.shutdown();
        mutators.shutdown();
        assertTrue(mutators.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(readers.awaitTermination(60, TimeUnit.SECONDS));

        log.info("输入: 翻页期间并发改写/删除/重建，且每页凭据被 6 线程并发重放；"
                + "收得 {} 条，期望 {} 条，版本集合={}；判定依据: 重放结果一致，"
                + "整轮固定在开始快照，不重不漏、版本恒定",
                collected.size(), expected.size(), new HashSet<>(versions));
        assertEquals(ids(expected), ids(collected));
        assertEquals(expected.size(), new HashSet<>(ids(collected)).size());
        assertEquals(Set.of(pinned.version()), new HashSet<>(versions));
    }

    // ------------------------------------------------------------------
    // 中断/重启：凭据不跨进程存活，丢失后必须明确告知重来；重来顺序可复现。
    // ------------------------------------------------------------------

    @Test
    void restartLosesTokensButRestartedRoundReproducesSameOrder() {
        int n = 300;
        DocumentStore store = newStoreWithDocs(n);
        HashingEmbeddingModel embedding = new HashingEmbeddingModel();
        Set<String> scopes = Set.of("team-a", "team-b");
        ScrollService serviceA = new ScrollService(store, embedding);

        ScrollPage first = serviceA.startScroll(QUERY, scopes, 23, null);
        List<SearchHit> partial = new ArrayList<>(first.hits());
        String token = first.nextScrollId();
        for (int i = 0; i < 3 && token != null; i++) {
            ScrollPage page = serviceA.next(token, null);
            partial.addAll(page.hits());
            token = page.nextScrollId();
        }
        String survivingToken = token; // 遍历中途被打断（超时/重启）

        // 模拟服务重启：内存会话与内存数据全部丢失，重新写入相同数据。
        DocumentStore restartedStore = newStoreWithDocs(n);
        ScrollService serviceB = new ScrollService(restartedStore, new HashingEmbeddingModel());
        ScrollSessionException ex = assertThrows(ScrollSessionException.class,
                () -> serviceB.next(survivingToken, null));
        log.info("输入: 翻到中途重启后沿用旧凭据；结果: {}；判定依据: 必须明确告知该轮已失效、"
                + "需要重新开启，绝不静默跳段或重复", ex.getMessage());

        // 用同样数据同样查询重新开始：整轮顺序与重启前一次性整体结果完全一致。
        List<SearchHit> full = restartedStore.currentSnapshot()
                .search(new HashingEmbeddingModel().embed(QUERY), scopes, Integer.MAX_VALUE);
        ScrollPage restarted = serviceB.startScroll(QUERY, scopes, 77, null);
        List<SearchHit> redone = drain(serviceB, restarted, id -> serviceB.next(id, null));
        assertEquals(ids(full), ids(redone), "重启后重新开始的顺序必须可复现");
        assertEquals(full.size(), new HashSet<>(ids(redone)).size());
        assertTrue(redone.size() > partial.size(), "重新开始必须完整取回，不丢段");
    }
}
