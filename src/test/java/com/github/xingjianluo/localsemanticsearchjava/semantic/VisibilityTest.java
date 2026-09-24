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
import com.github.xingjianluo.localsemanticsearchjava.semantic.store.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.HashingVectorizer;

/**
 * 可见性边界测试：scope 交集模型，过滤同时作用于结果列表与计数。
 */
class VisibilityTest {

    private static final Logger log = LoggerFactory.getLogger(VisibilityTest.class);

    private SemanticSearchService service;

    @BeforeEach
    void setUp() {
        service = new SemanticSearchService(new DocumentStore(), new HashingVectorizer());
        service.upsert("secret", "confidential merger acquisition plan", List.of("exec", "legal"));
        service.upsert("internal", "internal weekly team status report", List.of("staff"));
        service.upsert("open", "public product announcement blog post", List.of("public"));
    }

    @Test
    void callerOnlySeedsDocumentsWithIntersectingScopes() {
        SearchResult staff = service.search(
                new SearchRequest("confidential merger internal public report plan", 10, List.of("staff")));
        boolean seesSecret = has(staff, "secret");
        boolean seesInternal = has(staff, "internal");
        boolean seesOpen = has(staff, "open");
        log.info("[可见边界] 调用方scopes=staff 命中ids={} totalHits={} | 看到secret={} internal={} open={} | 判定: secret/open必须为false",
                ids(staff), staff.totalHits(), seesSecret, seesInternal, seesOpen);
        assertFalse(seesSecret, "staff 不得看到 exec/legal 文档");
        assertFalse(seesOpen, "staff 不得看到 public 文档");
        assertTrue(seesInternal, "staff 应看到 staff 文档");
        assertEquals(staff.hits().size(), staff.totalHits(),
                "totalHits 与可见结果必须一致，越界文档不得计入计数");
    }

    @Test
    void multiScopeCallerSeesUnionOfAllowedDocuments() {
        SearchResult multi = service.search(
                new SearchRequest("confidential merger internal public report plan announcement", 10,
                        List.of("exec", "public")));
        log.info("[多范围] 调用方scopes=[exec,public] 命中ids={} totalHits={} | 判定: 可见secret+open, 不可见internal",
                ids(multi), multi.totalHits());
        assertTrue(has(multi, "secret"));
        assertTrue(has(multi, "open"));
        assertFalse(has(multi, "internal"));
    }

    @Test
    void emptyScopesSeeNothingAndLeakNoCounts() {
        SearchResult noScope = service.search(
                new SearchRequest("confidential merger internal public report plan", 10, List.of()));
        log.info("[无范围] 调用方scopes=[] hits={} totalHits={} | 判定: 必须全部为0",
                noScope.hits().size(), noScope.totalHits());
        assertEquals(0, noScope.hits().size());
        assertEquals(0, noScope.totalHits(), "无可见范围时计数也不得泄露");
    }

    @Test
    void scopeChangeOnUpdateTakesEffectAtomically() {
        // 将 open 文档收紧为仅 exec 可见
        service.upsert("open", "public product announcement blog post", List.of("exec"));
        SearchResult publicView = service.search(
                new SearchRequest("public product announcement blog post", 10, List.of("public")));
        SearchResult execView = service.search(
                new SearchRequest("public product announcement blog post", 10, List.of("exec")));
        log.info("[范围更新] open改限exec后 public侧命中={} exec侧命中={} | 判定: public=0 exec>=1",
                publicView.totalHits(), execView.totalHits());
        assertEquals(0, publicView.totalHits(), "收紧范围后旧范围不得再看到");
        assertTrue(execView.totalHits() >= 1);
    }

    private static boolean has(SearchResult result, String id) {
        return result.hits().stream().anyMatch(h -> h.documentId().equals(id));
    }

    private static List<String> ids(SearchResult result) {
        return result.hits().stream().map(SearchHit::documentId).toList();
    }
}
