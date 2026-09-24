package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.embedding.HashingEmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档存储：幂等写入、可见性边界、排序稳定、删除不再召回。
 */
class DocumentStoreTest {

    private static final Logger log = LoggerFactory.getLogger(DocumentStoreTest.class);

    private DocumentStore store;

    @BeforeEach
    void setUp() {
        store = new DocumentStore(new HashingEmbeddingModel());
    }

    @Test
    void duplicateWriteWithSameIdIsIdempotent() {
        var first = store.upsert("doc-1", "本地语义检索", "team-a");
        long versionAfterFirst = store.currentSnapshot().version();
        long modAfterFirst = store.modificationCount();
        var second = store.upsert("doc-1", "本地语义检索", "team-a");
        log.info("输入: 同一 id=[doc-1] 相同内容重复写入两次；"
                + "第一次 applied={}，第二次 applied={}；版本 {} -> {}；"
                + "判定依据: 内容未变的重复写入必须是 no-op，不推进版本与修改计数",
                first.applied(), second.applied(), versionAfterFirst, store.currentSnapshot().version());
        assertTrue(first.applied());
        assertFalse(second.applied());
        assertEquals(versionAfterFirst, store.currentSnapshot().version());
        assertEquals(modAfterFirst, store.modificationCount());
        assertEquals(1, store.currentSnapshot().size());
    }

    @Test
    void duplicateWriteWithoutIdDerivesSameContentId() {
        var first = store.upsert(null, "同一段内容", "team-a");
        var second = store.upsert(null, "同一段内容", "team-a");
        log.info("输入: 不提供 id 重复写入同一内容两次；派生 id 第一次={} 第二次={}；"
                + "判定依据: id 按内容哈希派生，同一内容必须落到同一 id 且第二次为 no-op",
                first.id(), second.id());
        assertEquals(first.id(), second.id());
        assertFalse(second.applied());
        assertEquals(1, store.currentSnapshot().size());
    }

    @Test
    void updateSameIdReplacesContent() {
        store.upsert("doc-1", "旧内容 old", "team-a");
        var updated = store.upsert("doc-1", "新内容 new", "team-a");
        List<SearchHit> hits = store.search("new", Set.of("team-a"), 10);
        log.info("输入: id=[doc-1] 先写旧内容再更新为新内容；检索词=[new] 命中={}；"
                + "判定依据: 更新必须替换而非追加，索引中仍只有 1 个文档且内容为新值", hits);
        assertTrue(updated.applied());
        assertEquals(1, store.currentSnapshot().size());
        assertEquals(1, hits.size());
        assertEquals("新内容 new", hits.get(0).content());
    }

    @Test
    void deletedDocumentIsNotRecalled() {
        store.upsert("doc-1", "将被删除的文档", "team-a");
        boolean deleted = store.delete("doc-1");
        List<SearchHit> hits = store.search("将被删除的文档", Set.of("team-a"), 10);
        log.info("输入: 写入后删除 id=[doc-1]，再以原文检索；命中数={}；"
                + "判定依据: 已删除文档不得再被召回", hits.size());
        assertTrue(deleted);
        assertTrue(hits.isEmpty());
        assertFalse(store.delete("doc-1"));
    }

    @Test
    void outOfScopeDocumentsAreInvisible() {
        store.upsert("a1", "语义检索 文档 甲", "team-a");
        store.upsert("b1", "语义检索 文档 乙", "team-b");
        store.upsert("b2", "语义检索 文档 丙", "team-b");
        List<SearchHit> hits = store.search("语义检索 文档", Set.of("team-a"), 10);
        log.info("输入: 3 个文档分属 team-a/team-b，调用方可见范围=[team-a]，检索词=[语义检索 文档]；"
                + "命中={}；判定依据: 不在可见范围内的文档不得出现在结果与计数中", hits);
        assertEquals(1, hits.size());
        assertEquals("a1", hits.get(0).id());
        assertTrue(hits.stream().allMatch(h -> h.scope().equals("team-a")));
    }

    @Test
    void emptyVisibleScopeReturnsNothing() {
        store.upsert("a1", "任何内容", "team-a");
        List<SearchHit> hits = store.search("任何内容", Set.of(), 10);
        log.info("输入: 可见范围为空集；命中数={}；判定依据: 空可见范围必须返回空结果", hits.size());
        assertTrue(hits.isEmpty());
    }

    @Test
    void rankingIsStableAndReproducible() {
        for (int i = 0; i < 20; i++) {
            store.upsert("doc-" + i, "共同 词项 文档 编号 " + i, "team-a");
        }
        List<SearchHit> run1 = store.search("共同 词项 文档", Set.of("team-a"), 10);
        List<SearchHit> run2 = store.search("共同 词项 文档", Set.of("team-a"), 10);
        log.info("输入: 20 个相似文档，同一查询执行两次；第一次={}；第二次={}；"
                + "判定依据: 相同输入下排序必须稳定可复现（得分降序，同分按 id 升序）",
                run1.stream().map(SearchHit::id).toList(), run2.stream().map(SearchHit::id).toList());
        assertEquals(run1, run2);
        for (int i = 1; i < run1.size(); i++) {
            SearchHit prev = run1.get(i - 1);
            SearchHit curr = run1.get(i);
            assertTrue(prev.score() > curr.score()
                    || (prev.score() == curr.score() && prev.id().compareTo(curr.id()) < 0),
                    "排序必须满足 得分降序 / 同分 id 升序: " + prev + " vs " + curr);
        }
    }

    @Test
    void topKTruncatesAfterVisibilityFilter() {
        for (int i = 0; i < 10; i++) {
            store.upsert("a-" + i, "共同 词项 " + i, "team-a");
            store.upsert("b-" + i, "共同 词项 " + i, "team-b");
        }
        List<SearchHit> hits = store.search("共同 词项", Set.of("team-a"), 3);
        log.info("输入: 两个范围各 10 个文档，可见范围=[team-a]，topK=3；命中数={}；"
                + "判定依据: 先按可见性过滤再截断，结果数不超过 topK 且全部属于可见范围", hits.size());
        assertEquals(3, hits.size());
        assertTrue(hits.stream().allMatch(h -> h.scope().equals("team-a")));
    }
}
