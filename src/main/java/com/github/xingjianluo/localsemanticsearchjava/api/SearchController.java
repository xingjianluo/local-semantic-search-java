package com.github.xingjianluo.localsemanticsearchjava.api;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.paging.PageToken;
import com.github.xingjianluo.localsemanticsearchjava.paging.SearchPageResult;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildResult;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 本地语义检索 REST API。
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final DocumentStore store;
    private final RebuildService rebuildService;

    public SearchController(DocumentStore store, RebuildService rebuildService) {
        this.store = store;
        this.rebuildService = rebuildService;
    }

    public record UpsertRequest(String id, String content, String scope) {}
    public record UpsertResponse(String id, boolean applied) {}
    public record SearchRequest(String query, Set<String> visibleScopes, Integer topK) {}
    public record SearchResponse(List<SearchHit> hits, int count, long indexVersion) {}
    public record SearchPageRequest(String query, Set<String> visibleScopes,
                                    Double minScore, Integer pageSize, String pageToken) {}
    public record SearchPageResponse(List<SearchHit> hits, int count, int totalCount,
                                     long indexVersion, String nextPageToken, boolean hasMore,
                                     String status) {}
    public record DeleteResponse(boolean deleted) {}

    @PostMapping("/documents")
    public UpsertResponse upsert(@RequestBody UpsertRequest req) {
        DocumentStore.UpsertOutcome outcome = store.upsert(req.id(), req.content(), req.scope());
        return new UpsertResponse(outcome.id(), outcome.applied());
    }

    @DeleteMapping("/documents/{id}")
    public DeleteResponse delete(@PathVariable String id) {
        return new DeleteResponse(store.delete(id));
    }

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest req) {
        int topK = req.topK() == null ? DEFAULT_TOP_K : Math.min(req.topK(), MAX_TOP_K);
        List<SearchHit> hits = store.search(req.query(), req.visibleScopes(), topK);
        return new SearchResponse(hits, hits.size(), store.currentSnapshot().version());
    }

    /**
     * 分页遍历。首页不传 pageToken；把上一页返回的 nextPageToken 原样回传
     * 取下一页，直至 nextPageToken 为 null。
     *
     * <p>翻页期间索引发生写/删/重建，或 pageToken 与查询不匹配时返回
     * 409 + {@code status=STALE}，本轮结果作废，需丢弃旧令牌从头重开。</p>
     */
    @PostMapping("/search/page")
    public ResponseEntity<SearchPageResponse> searchPage(@RequestBody SearchPageRequest req) {
        PageToken token;
        try {
            token = (req.pageToken() == null || req.pageToken().isBlank())
                    ? null : PageToken.decode(req.pageToken());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new SearchPageResponse(
                    List.of(), 0, 0, store.currentSnapshot().version(),
                    null, false, "INVALID_TOKEN"));
        }
        int pageSize = req.pageSize() == null
                ? DEFAULT_PAGE_SIZE : Math.min(Math.max(1, req.pageSize()), MAX_PAGE_SIZE);
        SearchPageResult result = store.searchPage(
                req.query(), req.visibleScopes(), req.minScore(), pageSize, token);
        SearchPageResponse body = new SearchPageResponse(
                result.hits(),
                result.hits().size(),
                result.totalCount(),
                result.indexVersion(),
                result.nextToken() == null ? null : result.nextToken().encode(),
                result.nextToken() != null,
                result.stale() ? "STALE" : "OK");
        return result.stale() ? ResponseEntity.status(409).body(body) : ResponseEntity.ok(body);
    }

    @PostMapping("/rebuild")
    public RebuildResult rebuild() {
        return rebuildService.rebuild();
    }
}
