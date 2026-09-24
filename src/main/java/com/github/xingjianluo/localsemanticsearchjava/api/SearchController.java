package com.github.xingjianluo.localsemanticsearchjava.api;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildResult;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildService;
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

    @PostMapping("/rebuild")
    public RebuildResult rebuild() {
        return rebuildService.rebuild();
    }
}
