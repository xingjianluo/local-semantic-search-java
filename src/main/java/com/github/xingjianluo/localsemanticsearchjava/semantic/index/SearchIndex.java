package com.github.xingjianluo.localsemanticsearchjava.semantic.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.github.xingjianluo.localsemanticsearchjava.semantic.model.SearchHit;

/**
 * 不可变语义索引快照。
 *
 * <p>每次写入/更新/删除/重建都会产生一个全新的 {@code SearchIndex} 实例，并通过单个
 * volatile 引用对外发布；查询线程拿到的永远是某个完整快照，不会看到写入过程中的中间状态，
 * 也不会出现重复命中或已删除文档被召回。</p>
 *
 * <p>检索规则：文档向量与查询向量均已 L2 归一化，点积即余弦相似度；
 * 仅保留相似度严格大于 0 的文档；排序按 (相似度降序, documentId 升序) 的确定性全序，
 * 相同输入在任何时刻、任何机器上排序一致。</p>
 */
public final class SearchIndex {

    private static final SearchIndex EMPTY = new SearchIndex(Map.of());

    private final Map<String, IndexedDocument> documents;

    private SearchIndex(Map<String, IndexedDocument> documents) {
        this.documents = Collections.unmodifiableMap(documents);
    }

    public static SearchIndex empty() {
        return EMPTY;
    }

    /**
     * 从全量索引文档构建快照（重建使用）。按 id 排序，迭代顺序确定。
     */
    public static SearchIndex from(Collection<IndexedDocument> docs) {
        TreeMap<String, IndexedDocument> sorted = new TreeMap<>();
        for (IndexedDocument doc : docs) {
            sorted.put(doc.id(), doc);
        }
        return new SearchIndex(new LinkedHashMap<>(sorted));
    }

    /**
     * 返回写入/更新单个文档后的新快照，当前快照不变。
     */
    public SearchIndex withUpsert(IndexedDocument doc) {
        Map<String, IndexedDocument> next = new TreeMap<>(documents);
        next.put(doc.id(), doc);
        return new SearchIndex(new LinkedHashMap<>(next));
    }

    /**
     * 返回删除单个文档后的新快照；文档不存在时返回当前实例。
     */
    public SearchIndex withRemoved(String id) {
        if (!documents.containsKey(id)) {
            return this;
        }
        Map<String, IndexedDocument> next = new TreeMap<>(documents);
        next.remove(id);
        return new SearchIndex(new LinkedHashMap<>(next));
    }

    public int size() {
        return documents.size();
    }

    public boolean contains(String id) {
        return documents.containsKey(id);
    }

    public IndexedDocument get(String id) {
        return documents.get(id);
    }

    /**
     * 在给定调用方可见范围下做相似度检索。
     *
     * @param queryVector  已归一化查询向量
     * @param callerScopes 调用方拥有的可见范围；仅当文档 scope 集合与之有交集时可见
     * @param topK         返回条数上限，&lt;=0 表示不返回明细（但仍统计 totalHits）
     * @return 确定性排序后的命中（相似度严格大于 0）
     */
    public List<SearchHit> search(Map<Integer, Double> queryVector, Set<String> callerScopes, int topK) {
        List<Scored> scored = new ArrayList<>();
        for (IndexedDocument doc : documents.values()) {
            if (!visible(doc, callerScopes)) {
                continue;
            }
            double score = cosine(queryVector, doc.vector());
            if (score > 0.0) {
                scored.add(new Scored(doc.id(), doc.content(), score));
            }
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed()
                .thenComparing(s -> s.documentId));
        List<SearchHit> hits = new ArrayList<>();
        int limit = topK <= 0 ? 0 : Math.min(topK, scored.size());
        for (int i = 0; i < limit; i++) {
            Scored s = scored.get(i);
            hits.add(new SearchHit(s.documentId, s.score, s.content));
        }
        return List.copyOf(hits);
    }

    /**
     * 统计查询在给定可见范围下相似度 &gt; 0 的命中总数（不受 topK 截断影响）。
     */
    public int countMatches(Map<Integer, Double> queryVector, Set<String> callerScopes) {
        int count = 0;
        for (IndexedDocument doc : documents.values()) {
            if (visible(doc, callerScopes) && cosine(queryVector, doc.vector()) > 0.0) {
                count++;
            }
        }
        return count;
    }

    private static boolean visible(IndexedDocument doc, Set<String> callerScopes) {
        if (callerScopes == null || callerScopes.isEmpty()) {
            return false;
        }
        for (String scope : doc.scopes()) {
            if (callerScopes.contains(scope)) {
                return true;
            }
        }
        return false;
    }

    private static double cosine(Map<Integer, Double> a, Map<Integer, Double> b) {
        // 双方都已 L2 归一化，点积即余弦
        Map<Integer, Double> smaller = a.size() <= b.size() ? a : b;
        Map<Integer, Double> larger = smaller == a ? b : a;
        double dot = 0.0;
        for (Map.Entry<Integer, Double> e : smaller.entrySet()) {
            dot += e.getValue() * larger.getOrDefault(e.getKey(), 0.0);
        }
        return dot;
    }

    private record Scored(String documentId, String content, double score) {
    }
}
