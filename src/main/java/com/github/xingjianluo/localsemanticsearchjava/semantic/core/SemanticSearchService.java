package com.github.xingjianluo.localsemanticsearchjava.semantic.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.localsemanticsearchjava.semantic.index.IndexedDocument;
import com.github.xingjianluo.localsemanticsearchjava.semantic.index.SearchIndex;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.DeleteResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.Document;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.DocumentUpsert;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.RebuildFailureReason;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.RebuildOutcome;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.RebuildStatus;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchRequest;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.WriteResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.store.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.TextVectorizer;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.VectorizationException;

/**
 * 本地语义检索核心服务。
 *
 * <h3>并发与自洽性</h3>
 * <ul>
 *   <li>{@code index} 是单个 volatile 引用，指向不可变 {@link SearchIndex} 快照；
 *       每次查询只取一次快照引用，因此写入进行中的查询要么看到写入前、要么看到写入后的完整索引，
 *       不会出现部分可见、重复命中或已删除文档被召回。</li>
 *   <li>写入/删除在 {@code writeLock} 下串行化，且"先向量化校验、后落库、最后原子换索引"，
 *       向量化失败时存储与索引都不变。</li>
 *   <li>重建不持有写锁：基于全量快照构建候选索引，期间写入照常进行并增量更新旧索引；
 *       候选构建完成后短暂获取写锁，追平重建期间的增量，再一次性原子发布。
 *       构建阶段失败则候选被丢弃，旧索引全程在线，服务无中间不可用状态。</li>
 * </ul>
 *
 * <h3>失败原因</h3>
 * 见 {@link RebuildFailureReason}：向量化失败 / 钩子中止 / 中断 / 已有重建 / 内部错误，彼此可区分。
 */
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    private final DocumentStore store;
    private final TextVectorizer vectorizer;

    private volatile SearchIndex index;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock rebuildLock = new ReentrantLock();
    private volatile RebuildHook rebuildHook = RebuildHook.NOOP;

    public SemanticSearchService(DocumentStore store, TextVectorizer vectorizer) {
        this.store = store;
        this.vectorizer = vectorizer;
        this.index = buildFullIndex(store.all());
    }

    // ------------------------------------------------------------------
    // 写入 / 更新 / 删除
    // ------------------------------------------------------------------

    /**
     * 写入或更新文档。相同 id + 相同内容 + 相同可见范围的重复写入幂等无操作。
     */
    public WriteResult upsert(String id, String content, List<String> scopes) {
        // 1) 先向量化，失败则存储与索引均不变
        Map<Integer, Double> vector = vectorizer.vectorize(content);
        Set<String> normalizedScopes = normalizeScopes(scopes);

        writeLock.lock();
        try {
            // 2) 落库（内部做幂等判定）
            WriteResult result = store.upsert(new DocumentUpsert(id, content, new ArrayList<>(normalizedScopes)));
            // 3) 仅在实际变更时原子切换到新快照
            if (result.changed()) {
                Document doc = store.get(id).orElseThrow(() -> new IllegalStateException("写入后文档缺失: " + id));
                index = index.withUpsert(new IndexedDocument(id, content, vector, doc.scopes()));
                log.info("文档已写入并发布新索引 id={} version={} scopes={}", id, result.version(), doc.scopes());
            } else {
                log.info("重复写入幂等忽略 id={} version={}", id, result.version());
            }
            return result;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 删除文档；文档不存在时幂等返回 deleted=false。
     */
    public DeleteResult delete(String id) {
        writeLock.lock();
        try {
            DeleteResult result = store.delete(id);
            if (result.deleted()) {
                index = index.withRemoved(id);
                log.info("文档已删除并发布新索引 id={}", id);
            } else {
                log.info("删除不存在的文档，幂等忽略 id={}", id);
            }
            return result;
        } finally {
            writeLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 相似度查询。整个查询使用同一个索引快照；命中列表与 totalHits 都只统计
     * 与调用方 scopes 有交集的文档。查询文本无法向量化时确定性地返回空结果。
     */
    public SearchResult search(SearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("查询请求不能为空");
        }
        int topK = request.topK();
        if (topK < 0) {
            throw new IllegalArgumentException("topK 不能为负");
        }
        Set<String> callerScopes = normalizeScopes(request.scopes());
        SearchIndex snapshot = this.index;

        Map<Integer, Double> queryVector;
        try {
            queryVector = vectorizer.vectorize(request.query());
        } catch (VectorizationException e) {
            log.info("查询文本无法向量化，确定性返回空结果 query='{}' 原因={}", request.query(), e.getMessage());
            return new SearchResult(request.query(), List.of(), 0, new ArrayList<>(callerScopes));
        }

        List<SearchHit> hits = snapshot.search(queryVector, callerScopes, topK);
        int total = snapshot.countMatches(queryVector, callerScopes);
        log.info("查询 query='{}' scopes={} 快照文档数={} 可见命中总数={} 返回条数={}",
                request.query(), callerScopes, snapshot.size(), total, hits.size());
        return new SearchResult(request.query(), hits, total, new ArrayList<>(callerScopes));
    }

    // ------------------------------------------------------------------
    // 索引重建
    // ------------------------------------------------------------------

    /**
     * 基于存储全量数据重建索引并原子生效。
     *
     * <p>重建期间写入不受阻：候选索引基于某一时刻的全量快照构建，发布前在写锁保护下
     * 追平期间增量。任何构建期失败都只丢弃候选，旧索引继续服务。</p>
     */
    public RebuildOutcome rebuildIndex() {
        if (!rebuildLock.tryLock()) {
            RebuildOutcome outcome = new RebuildOutcome(RebuildStatus.FAILED,
                    RebuildFailureReason.ALREADY_RUNNING, "已有索引重建在进行中", index.size());
            log.warn("重建被拒绝：{}", outcome.message());
            return outcome;
        }
        try {
            // 1) 全量快照 + 版本基线
            List<Document> base = store.all();
            Map<String, Long> baseVersions = new HashMap<>();
            for (Document d : base) {
                baseVersions.put(d.id(), d.version());
            }

            // 2) 构建候选（不持写锁，失败即回退：旧索引从未被动过）
            List<IndexedDocument> built;
            try {
                built = vectorizeAll(base);
            } catch (RebuildAbortion abortion) {
                return failure(abortion.reason(), abortion.getMessage(), abortion.getCause());
            }

            // 3) 写锁内追平增量并原子发布
            writeLock.lock();
            try {
                Map<String, IndexedDocument> candidate = new java.util.LinkedHashMap<>();
                for (IndexedDocument d : built) {
                    candidate.put(d.id(), d);
                }
                List<Document> current = store.all();
                Set<String> currentIds = new TreeSet<>();
                for (Document d : current) {
                    currentIds.add(d.id());
                    Long baseVersion = baseVersions.get(d.id());
                    if (baseVersion == null || baseVersion != d.version()) {
                        // 重建期间新增或被更新：以当前数据重算
                        candidate.put(d.id(), toIndexed(d));
                    }
                }
                // 重建期间被删除的文档
                candidate.keySet().retainAll(currentIds);

                SearchIndex fresh = SearchIndex.from(candidate.values());
                index = fresh;
                RebuildOutcome ok = new RebuildOutcome(RebuildStatus.SUCCESS, null,
                        "索引重建完成并已原子生效", fresh.size());
                log.info("{}，文档数={}", ok.message(), fresh.size());
                return ok;
            } catch (VectorizationException e) {
                return failure(RebuildFailureReason.VECTORIZATION_FAILED,
                        "追平增量时向量化失败: " + e.getMessage(), e);
            } finally {
                writeLock.unlock();
            }
        } catch (RuntimeException e) {
            return failure(RebuildFailureReason.INTERNAL_ERROR, "重建发生内部错误: " + e.getMessage(), e);
        } finally {
            rebuildLock.unlock();
        }
    }

    public int indexedDocumentCount() {
        return index.size();
    }

    public int storedDocumentCount() {
        return store.size();
    }

    /** 仅供同包测试注入重建检查点。 */
    void setRebuildHook(RebuildHook hook) {
        this.rebuildHook = hook == null ? RebuildHook.NOOP : hook;
    }

    /** 仅供同包测试读取当前快照。 */
    SearchIndex currentIndex() {
        return index;
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private List<IndexedDocument> vectorizeAll(List<Document> docs) {
        List<IndexedDocument> result = new ArrayList<>(docs.size());
        int total = docs.size();
        RebuildHook hook = this.rebuildHook;
        for (Document d : docs) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RebuildAbortion(RebuildFailureReason.INTERRUPTED,
                        "重建线程被中断: " + d.id(), null);
            }
            IndexedDocument indexed;
            try {
                indexed = toIndexed(d);
            } catch (VectorizationException e) {
                throw new RebuildAbortion(RebuildFailureReason.VECTORIZATION_FAILED,
                        "文档向量化失败 id=" + d.id() + " 原因=" + e.getMessage(), e);
            }
            result.add(indexed);
            try {
                hook.onDocumentIndexed(d, result.size(), total);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RebuildAbortion(RebuildFailureReason.INTERRUPTED,
                        "重建在检查点被中断 id=" + d.id(), e);
            } catch (VectorizationException e) {
                throw new RebuildAbortion(RebuildFailureReason.VECTORIZATION_FAILED,
                        "重建钩子报告向量化失败 id=" + d.id(), e);
            } catch (Exception e) {
                throw new RebuildAbortion(RebuildFailureReason.HOOK_ABORTED,
                        "重建钩子中止了重建 id=" + d.id() + " 原因=" + e.getMessage(), e);
            }
        }
        return result;
    }

    private IndexedDocument toIndexed(Document d) {
        return new IndexedDocument(d.id(), d.content(), vectorizer.vectorize(d.content()), d.scopes());
    }

    private SearchIndex buildFullIndex(List<Document> docs) {
        List<IndexedDocument> indexed = new ArrayList<>(docs.size());
        for (Document d : docs) {
            indexed.add(toIndexed(d));
        }
        return SearchIndex.from(indexed);
    }

    private RebuildOutcome failure(RebuildFailureReason reason, String message, Throwable cause) {
        // 回退语义：不显式触碰 index，旧索引保持在线
        RebuildOutcome outcome = new RebuildOutcome(RebuildStatus.FAILED, reason, message, index.size());
        log.warn("索引重建失败并回退到旧索引 reason={} message={} 旧索引文档数={}",
                reason, message, outcome.indexedDocs(), cause);
        return outcome;
    }

    private static Set<String> normalizeScopes(List<String> scopes) {
        TreeSet<String> result = new TreeSet<>();
        if (scopes != null) {
            for (String scope : scopes) {
                if (scope != null && !scope.isBlank()) {
                    result.add(scope.trim());
                }
            }
        }
        return Set.copyOf(result);
    }

    /** 内部控制流：重建构建阶段失败。 */
    private static final class RebuildAbortion extends RuntimeException {
        private final RebuildFailureReason reason;

        RebuildAbortion(RebuildFailureReason reason, String message, Throwable cause) {
            super(message, cause, false, false);
            this.reason = reason;
        }

        RebuildFailureReason reason() {
            return reason;
        }
    }
}
