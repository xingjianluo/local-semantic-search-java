package com.github.xingjianluo.localsemanticsearchjava.semantic.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.xingjianluo.localsemanticsearchjava.semantic.core.SemanticSearchService;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.DeleteResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.DocumentUpsert;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.RebuildOutcome;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchRequest;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.WriteResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.VectorizationException;

/**
 * 本地语义检索 HTTP 接口。
 */
@RestController
@RequestMapping("/api/semantic")
public class SemanticSearchController {

    private final SemanticSearchService service;

    public SemanticSearchController(SemanticSearchService service) {
        this.service = service;
    }

    /** 写入或更新文档（重复写入幂等）。 */
    @PostMapping("/documents")
    public WriteResult upsert(@RequestBody DocumentUpsert request) {
        return service.upsert(request.id(), request.content(), request.scopes());
    }

    /** 删除文档。 */
    @DeleteMapping("/documents/{id}")
    public DeleteResult delete(@PathVariable String id) {
        return service.delete(id);
    }

    /** 相似度查询，按 scopes 做可见性过滤。 */
    @PostMapping("/search")
    public SearchResult search(@RequestBody SearchRequest request) {
        return service.search(request);
    }

    /** 重建索引（失败自动回退，返回值中 reason 可区分失败原因）。 */
    @PostMapping("/rebuild")
    public RebuildOutcome rebuild() {
        return service.rebuildIndex();
    }

    /** 健康状态：存储与索引文档数（正常应一致）。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("storedDocuments", service.storedDocumentCount());
        status.put("indexedDocuments", service.indexedDocumentCount());
        return status;
    }

    @ExceptionHandler({IllegalArgumentException.class, VectorizationException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "" : e.getMessage()));
    }
}
