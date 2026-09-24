package com.github.xingjianluo.localsemanticsearchjava.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.localsemanticsearchjava.semantic.core.SemanticSearchService;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchRequest;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.WriteResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.store.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.HashingVectorizer;

/**
 * 核心服务的基础一致性测试：写入、幂等、删除、排序稳定。
 */
class SemanticSearchServiceTest {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchServiceTest.class);

    private SemanticSearchService service;

    @BeforeEach
    void setUp() {
        service = new SemanticSearchService(new DocumentStore(), new HashingVectorizer());
    }

    @Test
    void writeSearchDeleteAreConsistent() {
        WriteResult r1 = service.upsert("d1", "offline local semantic search engine", List.of("public"));
        WriteResult r2 = service.upsert("d2", "how to cook italian pasta with tomato sauce", List.of("public"));
        log.info("[写入] d1.version={} d2.version={} 索引文档数={}", r1.version(), r2.version(),
                service.indexedDocumentCount());
        assertTrue(r1.changed());
        assertTrue(r2.changed());

        SearchResult result = service.search(
                new SearchRequest("local semantic search offline", 10, List.of("public")));
        log.info("[查询] 输入='local semantic search offline' scopes=public totalHits={} 首条={}",
                result.totalHits(), result.hits().get(0).documentId());
        assertEquals("d1", result.hits().get(0).documentId(), "语义更相近的 d1 必须排第一");
        assertTrue(result.totalHits() >= 1);

        service.delete("d1");
        SearchResult afterDelete = service.search(
                new SearchRequest("local semantic search offline", 10, List.of("public")));
        boolean d1Recalled = afterDelete.hits().stream().anyMatch(h -> h.documentId().equals("d1"));
        log.info("[删除后查询] d1是否仍被召回={} totalHits={} 判定要求=false", d1Recalled, afterDelete.totalHits());
        assertFalse(d1Recalled, "已删除文档不得再被召回");
    }

    @Test
    void duplicateWriteIsIdempotent() {
        WriteResult first = service.upsert("d1", "same content", List.of("a", "b"));
        WriteResult second = service.upsert("d1", "same content", List.of("b", "a"));
        log.info("[幂等] 首次 changed={} version={}；重复写入 changed={} version={} 判定要求changed=false且版本不变",
                first.changed(), first.version(), second.changed(), second.version());
        assertTrue(first.changed());
        assertFalse(second.changed(), "相同内容+相同范围的重复写入必须幂等");
        assertEquals(first.version(), second.version(), "幂等写入不得递增版本");

        SearchResult result = service.search(new SearchRequest("same content", 10, List.of("a")));
        long count = result.hits().stream().filter(h -> h.documentId().equals("d1")).count();
        log.info("[幂等] 重复写入后 d1 命中次数={} 判定要求=1", count);
        assertEquals(1, count, "重复写入不得造成重复命中");
    }

    @Test
    void sameQueryOrderingIsStableAcrossCalls() {
        service.upsert("d1", "local semantic search runs offline", List.of("public"));
        service.upsert("d2", "offline semantic search on local machine", List.of("public"));
        service.upsert("d3", "completely unrelated topic about dolphins", List.of("public"));
        List<String> order1 = ids(service.search(
                new SearchRequest("offline local semantic search", 10, List.of("public"))));
        List<String> order2 = ids(service.search(
                new SearchRequest("offline local semantic search", 10, List.of("public"))));
        log.info("[排序稳定] 第一次={} 第二次={} 判定要求相等", order1, order2);
        assertEquals(order1, order2, "相同输入排序必须稳定可复现");
    }

    @Test
    void topKTruncatesHitsButTotalHitsReflectsAllVisibleMatches() {
        for (int i = 0; i < 5; i++) {
            service.upsert("d" + i, "shared local semantic token phrase " + i, List.of("public"));
        }
        SearchResult result = service.search(
                new SearchRequest("shared local semantic token phrase", 2, List.of("public")));
        log.info("[计数] topK=2 返回={} totalHits={} 判定要求返回2且totalHits>=2",
                result.hits().size(), result.totalHits());
        assertEquals(2, result.hits().size());
        assertTrue(result.totalHits() >= 2);
    }

    private static List<String> ids(SearchResult result) {
        return result.hits().stream().map(SearchHit::documentId).toList();
    }
}
