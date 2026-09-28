package com.github.xingjianluo.localsemanticsearchjava.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 不可变索引快照。
 *
 * <p>快照一旦构建不再变化；查询总是针对某一个完整快照执行，
 * 因此写入进行中查询只会看到旧快照或新快照，绝不出现部分可见。
 * 文档按 id 排序存储，保证遍历顺序与排序结果稳定可复现。</p>
 */
public final class IndexSnapshot {

    /** 排序规则：得分降序；得分相同按文档 id 字典序升序，保证稳定可复现。 */
    private static final Comparator<SearchHit> HIT_ORDER =
            Comparator.comparingDouble(SearchHit::score).reversed()
                    .thenComparing(SearchHit::id);

    private final Map<String, IndexedDocument> documents;
    private final long version;

    public IndexSnapshot(List<IndexedDocument> documents, long version) {
        Map<String, IndexedDocument> byId = new TreeMap<>();
        for (IndexedDocument doc : documents) {
            byId.put(doc.id(), doc);
        }
        this.documents = Map.copyOf(byId);
        this.version = version;
    }

    public long version() {
        return version;
    }

    public int size() {
        return documents.size();
    }

    public IndexedDocument get(String id) {
        return documents.get(id);
    }

    /** 按 id 升序返回全部文档（确定性遍历顺序）。 */
    public List<IndexedDocument> documents() {
        return documents.values().stream().toList();
    }

    /**
     * 在可见范围内做余弦相似度检索。
     *
     * <p>可见性过滤先于打分与截断：不在 visibleScopes 内的文档
     * 不会出现在结果中，也不会计入任何计数。</p>
     *
     * @param queryVector   查询向量（应为单位向量）
     * @param visibleScopes 调用方可见范围；文档 scope 属于该集合才可见
     * @param topK          返回的最大条数
     */
    public List<SearchHit> search(float[] queryVector, Set<String> visibleScopes, int topK) {
        if (topK <= 0 || visibleScopes == null || visibleScopes.isEmpty()) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        for (IndexedDocument doc : documents.values()) {
            if (!visibleScopes.contains(doc.scope())) {
                continue;
            }
            double score = cosine(queryVector, doc.vector());
            hits.add(new SearchHit(doc.id(), doc.content(), doc.scope(), score));
        }
        hits.sort(HIT_ORDER);
        if (hits.size() > topK) {
            return List.copyOf(hits.subList(0, topK));
        }
        return List.copyOf(hits);
    }

    /**
     * 返回可见范围内、达到最低相似度的全部命中，按
     * （得分降序、id 升序）排好序，不做条数截断。
     *
     * <p>分页遍历与一次性全量检索共用同一份排序，因此逐页拼接的顺序
     * 与整体结果完全一致。可见性与门槛先于排序、计数生效：越界或低于
     * 门槛的文档不会出现在结果中，也不计入总数。</p>
     *
     * @param queryVector   查询向量（应为单位向量）
     * @param visibleScopes 调用方可见范围；文档 scope 属于该集合才可见
     * @param minScore      最低相似度（含边界）；null 表示不设门槛
     */
    public List<SearchHit> rankedAll(float[] queryVector, Set<String> visibleScopes, Double minScore) {
        if (visibleScopes == null || visibleScopes.isEmpty()) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        for (IndexedDocument doc : documents.values()) {
            if (!visibleScopes.contains(doc.scope())) {
                continue;
            }
            double score = cosine(queryVector, doc.vector());
            if (minScore != null && score < minScore) {
                continue;
            }
            hits.add(new SearchHit(doc.id(), doc.content(), doc.scope(), score));
        }
        hits.sort(HIT_ORDER);
        return List.copyOf(hits);
    }

    /**
     * 游标定位谓词：命中是否严格排在（afterScore, afterId）之后。
     * 排序方向为得分降序、同分 id 升序，与 {@link #HIT_ORDER} 一致。
     */
    public static boolean strictlyAfter(SearchHit hit, double afterScore, String afterId) {
        if (hit.score() != afterScore) {
            return hit.score() < afterScore;
        }
        return hit.id().compareTo(afterId) > 0;
    }

    /** 余弦相似度；任一向量为零向量时得分为 0。 */
    public static double cosine(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
