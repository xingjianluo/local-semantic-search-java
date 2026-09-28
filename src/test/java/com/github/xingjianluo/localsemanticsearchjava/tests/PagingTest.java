package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.embedding.HashingEmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.paging.PageToken;
import com.github.xingjianluo.localsemanticsearchjava.paging.SearchPageResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分页遍历：完整翻完不重不漏、顺序与全量一致、跨进程可复现、
 * 相关度门槛边界、门槛与可见范围叠加及范围收紧。
 */
class PagingTest {

    private static final Logger log = LoggerFactory.getLogger(PagingTest.class);

    private static final String QUERY = "分页 遍历 文档";

    /** 把一轮分页完整翻完，返回（按顺序拼接的命中, 页数, 最后一页的总数信息）。 */
    private static Walk walk(DocumentStore store, Set<String> scopes, Double minScore,
                             int pageSize) {
        List<SearchHit> all = new ArrayList<>();
        PageToken token = null;
        SearchPageResult last = null;
        int pages = 0;
        while (true) {
            last = store.searchPage(QUERY, scopes, minScore, pageSize, token);
            assertFalse(last.stale(), "未发生索引变更时不得报 stale");
            assertTrue(last.hits().size() <= pageSize);
            all.addAll(last.hits());
            pages++;
            token = last.nextToken();
            if (token == null) {
                break;
            }
            assertTrue(pages < 100_000, "翻页未在合理页数内结束，可能死循环");
        }
        return new Walk(all, pages, last.totalCount(), last.indexVersion());
    }

    private record Walk(List<SearchHit> hits, int pages, int totalCount, long indexVersion) {}

    private static void assertSorted(List<SearchHit> hits) {
        for (int i = 1; i < hits.size(); i++) {
            SearchHit a = hits.get(i - 1);
            SearchHit b = hits.get(i);
            assertTrue(a.score() > b.score()
                            || (a.score() == b.score() && a.id().compareTo(b.id()) < 0),
                    "顺序必须满足 得分降序 / 同分 id 升序: " + a + " vs " + b);
        }
    }

    @Test
    void pagingCoversAllExactlyOnceInSameOrderAsFullSearch() {
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        int n = 3000;
        for (int i = 0; i < n; i++) {
            // 共同词项 + 差异化词项，制造丰富但确定的得分层次；id 用零填充保证字典序与数字序一致
            store.upsert(String.format("doc-%05d", i),
                    "分页 遍历 文档 共同 内容 差异词 " + (i % 97) + " " + (i % 101), "team-a");
        }
        List<SearchHit> full = store.search(QUERY, Set.of("team-a"), n + 10);

        Walk walk = walk(store, Set.of("team-a"), null, 137);
        log.info("输入: {} 条文档，pageSize=137 翻页；总页数={}，拼接条数={}，totalCount={}；"
                + "判定依据: 拼接结果与一次性全量检索逐位相同、无重复无遗漏，"
                + "除最后一页外每页均满页，末页 nextToken 为 null",
                n, walk.pages(), walk.hits().size(), walk.totalCount());
        assertEquals(n, walk.hits().size());
        assertEquals(n, walk.totalCount());
        assertEquals(full, walk.hits());
        assertEquals(n, new LinkedHashSet<>(walk.hits().stream().map(SearchHit::id).toList()).size(),
                "跨页不得出现重复 id");
        assertSorted(walk.hits());
        // 3000/137 = 21 余 123 => 22 页
        assertEquals(22, walk.pages());
    }

    @Test
    void differentPageSizesYieldSameOrderAndCoverage() {
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < 500; i++) {
            store.upsert(String.format("d-%04d", i), "分页 遍历 文档 差异 " + (i % 53), "team-a");
        }
        List<SearchHit> full = store.search(QUERY, Set.of("team-a"), 1000);
        for (int size : List.of(1, 2, 7, 100, 499, 500, 1000)) {
            Walk walk = walk(store, Set.of("team-a"), null, size);
            assertEquals(full, walk.hits(), "pageSize=" + size + " 时拼接顺序必须与全量一致");
            assertEquals(full.size(), walk.totalCount());
        }
        log.info("输入: 500 条文档，分别以 pageSize=1/2/7/100/499/500/1000 翻完；"
                + "判定依据: 任何页大小下拼接结果与全量检索逐位一致、不重不漏");
    }

    @Test
    void pagingOrderIsReproducibleAcrossRestart() {
        // 第一轮：写入数据，分页取前 3 页并记录游标字符串（模拟服务把令牌交给客户端）。
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < 400; i++) {
            store.upsert(String.format("doc-%04d", i),
                    "分页 遍历 文档 重启 复现 差异 " + (i % 61), i % 3 == 0 ? "team-a" : "team-b");
        }
        Set<String> scopes = Set.of("team-a", "team-b");

        List<SearchHit> before = new ArrayList<>();
        List<String> tokenStrings = new ArrayList<>();
        PageToken token = null;
        for (int p = 0; p < 3; p++) {
            SearchPageResult page = store.searchPage(QUERY, scopes, null, 23, token);
            before.addAll(page.hits());
            token = page.nextToken();
            tokenStrings.add(token == null ? null : token.encode());
        }
        String carriedToken = tokenStrings.get(2);
        long anchorVersion = token.indexVersion();

        // 模拟重启：新建存储（无任何服务端会话状态），按相同数据重放。
        DocumentStore restarted = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < 400; i++) {
            restarted.upsert(String.format("doc-%04d", i),
                    "分页 遍历 文档 重启 复现 差异 " + (i % 61), i % 3 == 0 ? "team-a" : "team-b");
        }
        assertEquals(anchorVersion, restarted.currentSnapshot().version(),
                "相同写入序列必须产生相同索引版本");

        // 用重启前带出的旧令牌继续翻完剩余页。
        List<SearchHit> after = new ArrayList<>();
        PageToken resumed = PageToken.decode(carriedToken);
        while (true) {
            SearchPageResult page = restarted.searchPage(QUERY, scopes, null, 23, resumed);
            assertFalse(page.stale());
            after.addAll(page.hits());
            resumed = page.nextToken();
            if (resumed == null) {
                break;
            }
        }
        List<SearchHit> combined = new ArrayList<>();
        combined.addAll(before);
        combined.addAll(after);
        List<SearchHit> full = restarted.search(QUERY, scopes, 1000);
        log.info("输入: 400 条文档，翻 3 页后用旧令牌在'重启后'的存储里继续翻完；"
                + "重启前 {} 条 + 重启后 {} 条 = {}；判定依据: 跨进程令牌可续用，"
                + "拼接顺序与整体全量结果逐位一致且不重不漏",
                before.size(), after.size(), combined.size());
        assertEquals(full, combined);
        assertEquals(combined.size(), combined.stream().map(SearchHit::id).distinct().count());
    }

    @Test
    void minScoreBoundaryIsInclusiveAndCountExcludesBelow() {
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < 60; i++) {
            store.upsert(String.format("doc-%03d", i),
                    "分页 遍历 文档 门槛 " + (i % 17) + " 无关词 " + i, "team-a");
        }
        List<SearchHit> full = store.search(QUERY, Set.of("team-a"), 1000);
        assertFalse(full.isEmpty());
        SearchHit pivot = full.get(10);
        double exact = pivot.score();

        // 门槛恰好等于某条得分：该条必须包含（边界含等号）。
        Walk atExact = walk(store, Set.of("team-a"), exact, 16);
        Walk justAbove = walk(store, Set.of("team-a"), Math.nextUp(exact), 16);
        log.info("输入: 60 条文档，门槛取第 11 名得分 exact={}（id={}）与 nextUp={}；"
                + "含门槛条数={}，略高门槛条数={}，全量={}；判定依据: 边界含等号——"
                + "等于门槛的文档保留，高一 ulp 即被排除；低于门槛的文档不计入 totalCount",
                exact, pivot.id(), Math.nextUp(exact), atExact.hits().size(),
                justAbove.hits().size(), full.size());
        assertTrue(atExact.hits().stream().anyMatch(h -> h.id().equals(pivot.id())));
        assertTrue(atExact.hits().stream().allMatch(h -> h.score() >= exact));
        assertEquals(atExact.hits().size(), atExact.totalCount());
        assertFalse(justAbove.hits().stream().anyMatch(h -> h.id().equals(pivot.id())));
        assertTrue(justAbove.hits().stream().allMatch(h -> h.score() > exact));
        // 合成数据可能存在同分并列：两组结果之差恰好是等于门槛的整个同分组
        long tiedAtExact = full.stream().filter(h -> h.score() == exact).count();
        assertTrue(tiedAtExact >= 1);
        assertEquals(atExact.hits().size(), justAbove.hits().size() + (int) tiedAtExact);
        assertSorted(atExact.hits());
        assertSorted(justAbove.hits());

        // 高于最高分：空结果且 totalCount=0、无续页令牌。
        SearchPageResult none = store.searchPage(QUERY, Set.of("team-a"), 2.0, 16, null);
        assertTrue(none.hits().isEmpty());
        assertEquals(0, none.totalCount());
        assertNull(none.nextToken());

        // 零向量查询（空白查询词）所有得分均为 0：门槛 0.0（含边界）全含，
        // 任意正门槛全部排除。
        List<SearchHit> collectedZero = new ArrayList<>();
        PageToken t = null;
        do {
            SearchPageResult page = store.searchPage("   ", Set.of("team-a"), 0.0, 16, t);
            assertFalse(page.stale());
            assertTrue(page.hits().stream().allMatch(h -> h.score() == 0.0));
            collectedZero.addAll(page.hits());
            t = page.nextToken();
        } while (t != null);
        assertEquals(full.size(), collectedZero.size(), "零向量查询下门槛 0.0 必须全部包含");

        SearchPageResult positiveThreshold = store.searchPage(QUERY, Set.of("team-a"), 0.0001, 1000, null);
        assertTrue(positiveThreshold.hits().stream().allMatch(h -> h.score() >= 0.0001));
        assertEquals(positiveThreshold.hits().size(), positiveThreshold.totalCount());

        SearchPageResult zeroQueryPositiveCut =
                store.searchPage("   ", Set.of("team-a"), 0.0001, 16, null);
        assertEquals(0, zeroQueryPositiveCut.totalCount());
        assertTrue(zeroQueryPositiveCut.hits().isEmpty());
        assertNull(zeroQueryPositiveCut.nextToken());
    }

    @Test
    void thresholdAndVisibilityCombine() {
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < 30; i++) {
            store.upsert("a-" + i, "分页 遍历 文档 甲 " + i, "team-a");
            store.upsert("b-" + i, "分页 遍历 文档 乙 " + i, "team-b");
        }
        List<SearchHit> onlyA = store.search(QUERY, Set.of("team-a"), 1000);
        double threshold = onlyA.get(7).score();

        Walk walk = walk(store, Set.of("team-a"), threshold, 9);
        log.info("输入: 两个范围各 30 条，仅可见 team-a 且取门槛={}；翻出 {} 条；"
                + "判定依据: 每条必须同时满足可见与门槛，越界/低于门槛的内容既不出现也不计数",
                threshold, walk.hits().size());
        assertTrue(walk.hits().stream().allMatch(h -> h.scope().equals("team-a")));
        assertTrue(walk.hits().stream().allMatch(h -> h.score() >= threshold));
        assertEquals(walk.hits().size(), walk.totalCount());
        // 与一次性带门槛全量检索一致
        List<SearchHit> expected = store.currentSnapshot()
                .rankedAll(new HashingEmbeddingModel().embed(QUERY), Set.of("team-a"), threshold);
        assertEquals(expected, walk.hits());
    }

    @Test
    void tighteningScopeMidPageDoesNotSkipAheadInRank() {
        // 关键边界：收紧发生在某一页"中途"。构造 a/b 文档在排序中交替出现，
        // 使首页只返回 a1、b1、a2（pageSize=3），收紧为仅 a 后续页时，
        // 排在 b1（游标位置）之前、但首页没返回过的 a 文档绝不能被跳过。
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        // 受控写入：让 a/b 文档在全局排序中交错，确保收紧点落在首页内部
        store.upsert("a-1", "分页 遍历 文档 甲 最强相关 核心", "team-a");
        store.upsert("b-1", "分页 遍历 文档 乙 次强相关 核心", "team-b");
        store.upsert("a-2", "分页 遍历 文档 甲 再次相关 核心", "team-a");
        store.upsert("b-2", "分页 遍历 文档 乙 一般相关 内容", "team-b");
        store.upsert("a-3", "分页 遍历 文档 甲 一般相关 内容", "team-a");
        store.upsert("b-3", "分页 遍历 文档 乙 较弱 内容 填充", "team-b");
        store.upsert("a-4", "分页 遍历 文档 甲 较弱 内容 填充", "team-a");

        List<SearchHit> wide = store.search(QUERY, Set.of("team-a", "team-b"), 1000);
        log.info("wide 全量排序={}", wide.stream().map(SearchHit::id).toList());

        PageToken token = null;
        SearchPageResult first = store.searchPage(QUERY, Set.of("team-a", "team-b"), null, 3, token);
        assertEquals(3, first.hits().size());
        List<String> firstIds = first.hits().stream().map(SearchHit::id).toList();
        token = first.nextToken();
        assertNotNull(token);

        // 第二页起收紧为仅 a：无论收紧点是否在首页内部，a 范围都必须完整且按全量顺序补齐
        List<SearchHit> rest = new ArrayList<>();
        while (token != null) {
            SearchPageResult page = store.searchPage(QUERY, Set.of("team-a"), null, 3, token);
            assertFalse(page.stale());
            assertTrue(page.hits().stream().allMatch(h -> h.scope().equals("team-a")),
                    "收紧后不得再出现 b 范围内容");
            rest.addAll(page.hits());
            token = page.nextToken();
        }
        List<String> aIds = new ArrayList<>();
        for (SearchHit h : first.hits()) {
            if (h.scope().equals("team-a")) {
                aIds.add(h.id());
            }
        }
        rest.stream().map(SearchHit::id).forEach(aIds::add);

        List<String> aFull = store.search(QUERY, Set.of("team-a"), 1000)
                .stream().map(SearchHit::id).toList();
        log.info("输入: 首页可见 [a,b] pageSize=3 得到 {}，第二页起收紧为 [a]；"
                + "拼接出的 a 序列={}；a 全量序列={}；判定依据: 收紧点若在页中途，"
                + "排在游标之前但未返回过的 a 文档也必须补回（无整段跳过），且不重复",
                firstIds, aIds, aFull);
        assertEquals(aFull, aIds, "a 范围必须完整且顺序与全量一致");
        assertEquals(aIds.size(), new LinkedHashSet<>(aIds).size(), "不得重复");

        // 无 b 文档泄漏到收紧之后
        assertTrue(rest.stream().noneMatch(h -> h.scope().equals("team-b")));
        // 首页中若含有 b 文档，它们是收紧前合法返回的；不得在后续再出现
        Set<String> firstB = first.hits().stream()
                .filter(h -> h.scope().equals("team-b")).map(SearchHit::id)
                .collect(java.util.stream.Collectors.toSet());
        for (SearchHit h : rest) {
            assertFalse(firstB.contains(h.id()));
        }
    }

    @Test
    void minScoreCanBeRaisedMidWalkWithoutDuplicatesOrSkips() {
        // 门槛中途提高：被新高门槛过滤掉的后续页内容直接消失，
        // 保留下来的内容不重不漏，相对顺序不变。
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < 40; i++) {
            store.upsert(String.format("doc-%03d", i),
                    "分页 遍历 文档 门槛 中途 " + (i % 13) + " 差异 " + i, "team-a");
        }
        PageToken token = null;
        List<SearchHit> collected = new ArrayList<>();
        SearchPageResult first = store.searchPage(QUERY, Set.of("team-a"), null, 10, null);
        collected.addAll(first.hits());
        token = first.nextToken();

        double raised = first.hits().get(first.hits().size() - 1).score();
        while (token != null) {
            SearchPageResult page = store.searchPage(QUERY, Set.of("team-a"), raised, 10, token);
            assertFalse(page.stale());
            assertTrue(page.hits().stream().allMatch(h -> h.score() >= raised));
            collected.addAll(page.hits());
            token = page.nextToken();
        }
        List<SearchHit> expected = store.currentSnapshot()
                .rankedAll(new HashingEmbeddingModel().embed(QUERY), Set.of("team-a"), raised);
        // 首页可能包含后来低于 raised 的内容（收紧前已返回，允许保留）；
        // 之后的内容必须恰好是高门槛结果中尚未返回过的部分。
        List<String> expectedIds = new ArrayList<>(expected.stream().map(SearchHit::id).toList());
        Set<String> firstIds = first.hits().stream().map(SearchHit::id)
                .collect(java.util.stream.Collectors.toSet());
        expectedIds.removeAll(firstIds);
        List<String> restIds = collected.subList(first.hits().size(), collected.size())
                .stream().map(SearchHit::id).toList();
        log.info("输入: 首页无门槛取 10 条，第 2 页起把门槛提高到 {}；首页 {} 条，后续 {} 条；"
                + "判定依据: 后续内容恰好等于高门槛全量结果中未返回的部分，不重不漏、顺序不变",
                raised, first.hits().size(), restIds.size());
        assertEquals(expectedIds, restIds);
        assertEquals(collected.size(),
                new LinkedHashSet<>(collected.stream().map(SearchHit::id).toList()).size());
    }

    @Test
    void visibilityCanBeTightenedMidWalkAndStaysConsistent() {
        // 3 个范围，初始可见 a/b，翻到一半收紧为仅 a：b 的内容既不能被翻到两次，
        // 也不能被跳过一整段，更不能因前面翻过而继续带出。
        DocumentStore store = new DocumentStore(new HashingEmbeddingModel());
        for (int i = 0; i < 90; i++) {
            String scope = i % 3 == 0 ? "team-a" : i % 3 == 1 ? "team-b" : "team-c";
            store.upsert(String.format("doc-%03d", i), "分页 遍历 文档 收紧 " + i, scope);
        }
        Set<String> wide = Set.of("team-a", "team-b");
        Set<String> tight = Set.of("team-a");
        List<SearchHit> aOnlyFull = store.search(QUERY, tight, 1000);

        List<SearchHit> collected = new ArrayList<>();
        List<String> seenIds = new ArrayList<>();
        PageToken token = null;
        boolean tightened = false;
        for (int p = 0; p < 100; p++) {
            Set<String> scopes = tightened ? tight : wide;
            SearchPageResult page = store.searchPage(QUERY, scopes, null, 11, token);
            assertFalse(page.stale());
            for (SearchHit h : page.hits()) {
                assertTrue(scopes.contains(h.scope()), "收紧后不得再翻出越界内容");
                assertFalse(seenIds.contains(h.id()), "同一内容不得翻到两次: " + h.id());
                seenIds.add(h.id());
            }
            collected.addAll(page.hits());
            token = page.nextToken();
            if (p == 2) {
                tightened = true; // 第 3 页之后收紧
            }
            if (token == null) {
                break;
            }
        }
        log.info("输入: a/b/c 三范围共 90 条，前 3 页可见 [a,b]，之后收紧为 [a]；"
                + "共翻出 {} 条（其中收紧前可能已翻过部分 b）；a 范围全量 {} 条；"
                + "判定依据: 无重复、无越界；收紧后 a 范围中尚未翻过的部分必须完整出现（无整段跳过）",
                collected.size(), aOnlyFull.size());
        // 收紧后：最终拼出的 a 文档集合必须恰好等于 a 的全量集合
        // （b 文档只允许在收紧前出现）；顺序在公共部分上与全量排序一致。
        List<SearchHit> aFromWalk = collected.stream()
                .filter(h -> h.scope().equals("team-a")).toList();
        assertEquals(aOnlyFull.stream().map(SearchHit::id).toList(),
                aFromWalk.stream().map(SearchHit::id).toList(),
                "收紧范围后，a 范围内文档必须完整且按全量顺序出现");
        assertTrue(collected.stream().filter(h -> h.scope().equals("team-b"))
                        .allMatch(h -> seenIds.indexOf(h.id()) >= 0),
                "b 范围文档只可能在收紧前出现，且不重复");
        assertSorted(collected);
    }
}
