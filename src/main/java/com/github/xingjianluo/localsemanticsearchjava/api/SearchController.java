package com.github.xingjianluo.localsemanticsearchjava.api;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildResult;
import com.github.xingjianluo.localsemanticsearchjava.rebuild.RebuildService;
import com.github.xingjianluo.localsemanticsearchjava.scroll.ScrollPage;
import com.github.xingjianluo.localsemanticsearchjava.scroll.ScrollService;
import com.github.xingjianluo.localsemanticsearchjava.scroll.ScrollSessionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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
    private final ScrollService scrollService;

    public SearchController(DocumentStore store, RebuildService rebuildService,
                            ScrollService scrollService) {
        this.store = store;
        this.rebuildService = rebuildService;
        this.scrollService = scrollService;
    }

    public record UpsertRequest(String id, String content, String scope) {}
    public record UpsertResponse(String id, boolean applied) {}
    public record SearchRequest(String query, Set<String> visibleScopes, Integer topK,
                                Double minScore) {}
    public record SearchResponse(List<SearchHit> hits, int count, long indexVersion) {}
    public record DeleteResponse(boolean deleted) {}

    /** 开启一轮分页遍历的请求。 */
    public record ScrollStartRequest(String query, Set<String> visibleScopes, Integer pageSize,
                                     Double minScore) {}
    /** 翻下一页的请求；visibleScopes 省略时沿用初始范围，给定时只能收紧。 */
    public record ScrollNextRequest(Set<String> visibleScopes) {}

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
        double minScore = req.minScore() == null ? IndexSnapshot.NO_MIN_SCORE : req.minScore();
        List<SearchHit> hits = store.search(req.query(), req.visibleScopes(), topK, minScore);
        return new SearchResponse(hits, hits.size(), store.currentSnapshot().version());
    }

    /** 开启分页遍历，返回第一页与 scrollId。 */
    @PostMapping("/scroll")
    public ScrollPage startScroll(@RequestBody ScrollStartRequest req) {
        return scrollService.startScroll(req.query(), req.visibleScopes(),
                req.pageSize(), req.minScore());
    }

    /** 翻下一页。 */
    @PostMapping("/scroll/{scrollId}")
    public ScrollPage nextScroll(@PathVariable String scrollId,
                                 @RequestBody(required = false) ScrollNextRequest req) {
        Set<String> visibleScopes = req == null ? null : req.visibleScopes();
        return scrollService.next(scrollId, visibleScopes);
    }

    /** 显式结束一轮遍历（释放其固定快照）。 */
    @DeleteMapping("/scroll/{scrollId}")
    public Map<String, Boolean> closeScroll(@PathVariable String scrollId) {
        scrollService.close(scrollId);
        return Map.of("closed", true);
    }

    @PostMapping("/rebuild")
    public RebuildResult rebuild() {
        return rebuildService.rebuild();
    }

    /** 分页凭据未知/已关闭/已过期 → 404；非法参数（如试图扩大范围）→ 400。 */
    @RestControllerAdvice
    static class ScrollExceptionHandler {
        @ExceptionHandler(ScrollSessionException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        public Map<String, String> handleSession(ScrollSessionException e) {
            return Map.of("error", "SCROLL_NOT_FOUND", "message", e.getMessage());
        }

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String, String> handleBadRequest(IllegalArgumentException e) {
            return Map.of("error", "BAD_REQUEST", "message", e.getMessage());
        }
    }
}
