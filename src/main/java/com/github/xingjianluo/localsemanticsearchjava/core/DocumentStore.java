package com.github.xingjianluo.localsemanticsearchjava.core;

import com.github.xingjianluo.localsemanticsearchjava.embedding.EmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexedDocument;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import com.github.xingjianluo.localsemanticsearchjava.paging.PageToken;
import com.github.xingjianluo.localsemanticsearchjava.paging.SearchPageResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文档存储。
 *
 * <p>并发模型：写操作在写锁内以 copy-on-write 方式构建全新
 * {@link IndexSnapshot} 并原子替换 volatile 引用；读操作不加锁，
 * 只会看到某个完整快照，因此写入进行中的查询绝不看到部分写入、
 * 重复命中或已删除内容。</p>
 *
 * <p>幂等：相同 id 且内容、可见范围均未变化的重复写入是无操作，
 * 不推进修改计数与索引版本。</p>
 */
@Service
public class DocumentStore {

    private final EmbeddingModel embedding;
    private final ReentrantLock writeLock = new ReentrantLock();

    private volatile IndexSnapshot snapshot = new IndexSnapshot(List.of(), 0L);
    private long modificationCount = 0L;

    public DocumentStore(EmbeddingModel embedding) {
        this.embedding = embedding;
    }

    /** 写入结果：id 与是否真正应用了变更（幂等命中时为 false）。 */
    public record UpsertOutcome(String id, boolean applied) {}

    /**
     * 幂等写入/更新。id 为 null 或空白时按内容 SHA-256 派生，
     * 因此同一内容的重复写入天然落到同一 id 上。
     */
    public UpsertOutcome upsert(String id, String content, String scope) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        String docId = (id == null || id.isBlank()) ? contentId(content) : id;
        String docScope = (scope == null || scope.isBlank()) ? "default" : scope;
        writeLock.lock();
        try {
            IndexedDocument existing = snapshot.get(docId);
            if (existing != null
                    && existing.content().equals(content)
                    && existing.scope().equals(docScope)) {
                return new UpsertOutcome(docId, false);
            }
            Map<String, IndexedDocument> docs = new LinkedHashMap<>();
            for (IndexedDocument doc : snapshot.documents()) {
                docs.put(doc.id(), doc);
            }
            docs.put(docId, new IndexedDocument(docId, content, docScope, embedding.embed(content)));
            commitLocked(new ArrayList<>(docs.values()));
            return new UpsertOutcome(docId, true);
        } finally {
            writeLock.unlock();
        }
    }

    /** 删除；文档不存在时返回 false。 */
    public boolean delete(String id) {
        writeLock.lock();
        try {
            if (snapshot.get(id) == null) {
                return false;
            }
            List<IndexedDocument> docs = new ArrayList<>();
            for (IndexedDocument doc : snapshot.documents()) {
                if (!doc.id().equals(id)) {
                    docs.add(doc);
                }
            }
            commitLocked(docs);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /** 查询：对当前快照做相似度检索，不加锁。 */
    public List<SearchHit> search(String query, Set<String> visibleScopes, int topK) {
        float[] queryVector = embedding.embed(query);
        return snapshot.search(queryVector, visibleScopes, topK);
    }

    /**
     * 分页遍历：按游标取下一页结果。
     *
     * <p>首页（token 为 null）锚定当前快照版本；续页只在该版本的快照上
     * 计算。翻页期间若发生过写入、删除或重建（版本推进），续页会得到
     * {@code stale=true}，调用方应当放弃本轮、从头重开——因此一轮遍历
     * 要么始终基于开始时的同一份不可变快照，前后一致；要么明确失败重来，
     * 绝不会重复或跳过。</p>
     *
     * <p>可见范围与最低相似度在每一页都随请求给出：可见范围可在轮次中
     * 收紧（越界内容后续页不再出现，也不会被前面翻过而带出）；
     * 排序顺序只取决于快照与查询，不因过滤收紧而改变。</p>
     *
     * @param minScore 最低相似度（含边界），null 表示不设门槛
     * @param token    上一页返回的游标；null 表示从头开始
     */
    public SearchPageResult searchPage(String query, Set<String> visibleScopes,
                                       Double minScore, int pageSize, PageToken token) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (minScore != null && (minScore.isNaN() || minScore.isInfinite())) {
            throw new IllegalArgumentException("minScore must be finite");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        IndexSnapshot snap = snapshot;
        String queryHash = PageToken.hashQuery(query);

        PageToken anchor;
        if (token == null) {
            anchor = PageToken.start(snap.version(), queryHash);
        } else {
            if (snap.version() != token.indexVersion() || !queryHash.equals(token.queryHash())) {
                // 索引已变（写/删/重建）或查询不一致：本轮作废，明确要求重来。
                return new SearchPageResult(List.of(), 0, snap.version(), null, true);
            }
            anchor = token;
        }

        float[] queryVector = embedding.embed(query);
        List<SearchHit> ranked = snap.rankedAll(queryVector, visibleScopes, minScore);

        // 续页定位：从游标排序键之后开始，并排除本轮已返回过的 id。
        // 排序键负责"一直向后翻"的效率与主顺序；排除集负责在过滤条件
        // （可见范围收紧、门槛提高）后仍保证不重不漏——收紧后排序更靠前、
        // 此前未返回的范围内文档会被正常补回，而不是被游标跳过。
        Set<String> returned = new HashSet<>(anchor.returnedIds());
        int start = 0;
        if (!anchor.isAtStart()) {
            start = cursorPosition(ranked, anchor.afterScore(), anchor.afterId());
        }

        List<SearchHit> page = new ArrayList<>(pageSize);
        if (start < ranked.size()) {
            int lastExamined = start - 1;
            for (int i = start; i < ranked.size(); i++) {
                lastExamined = i;
                SearchHit hit = ranked.get(i);
                if (returned.contains(hit.id())) {
                    continue;
                }
                page.add(hit);
                if (page.size() == pageSize) {
                    break;
                }
            }
            // 游标推进到本次检查过的最后一个位置（无论它是否被排除）。
            SearchHit frontier = page.isEmpty() ? ranked.get(lastExamined)
                    : page.get(page.size() - 1);
            // 还有未检查的候选时才续页；已返回项即便排在前面也不会再产生新结果。
            boolean hasMore = lastExamined + 1 < ranked.size();
            PageToken nextToken = hasMore
                    ? anchor.advance(page.stream().map(SearchHit::id).toList(),
                            frontier.score(), frontier.id())
                    : null;
            // totalCount 为当前请求的可见范围 + 门槛下的全部命中数；
            // 门槛之下与越界的内容不计入。
            return new SearchPageResult(List.copyOf(page), ranked.size(), snap.version(),
                    nextToken, false);
        }
        return new SearchPageResult(List.of(), ranked.size(), snap.version(), null, false);
    }

    /**
     * 返回游标位置：第一个严格排在（afterScore, afterId）之后的下标。
     * 列表已按（得分降序、id 升序）排序。
     */
    private static int cursorPosition(List<SearchHit> ranked, double afterScore, String afterId) {
        int lo = 0;
        int hi = ranked.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (IndexSnapshot.strictlyAfter(ranked.get(mid), afterScore, afterId)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    public IndexSnapshot currentSnapshot() {
        return snapshot;
    }

    /** 单调递增的修改计数：每次真实写变更 +1，供重建做乐观并发校验。 */
    public long modificationCount() {
        return modificationCount;
    }

    /** 当前文档源（重建的输入）。 */
    public List<IndexedDocument> sourceDocuments() {
        return snapshot.documents();
    }

    /**
     * 乐观提交：仅当修改计数与期望一致时才原子切换快照。
     *
     * @return true 表示切换成功
     */
    public boolean commitSnapshot(long expectedModification, IndexSnapshot newSnapshot) {
        writeLock.lock();
        try {
            if (modificationCount != expectedModification) {
                return false;
            }
            snapshot = newSnapshot;
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /** 在写锁内读取文档源与修改计数（重建兜底路径使用，保证二者一致）。 */
    public RebuildSource lockedSource() {
        writeLock.lock();
        try {
            return new RebuildSource(snapshot.documents(), modificationCount);
        } finally {
            writeLock.unlock();
        }
    }

    /** 重建兜底提交：切换快照并保持修改计数不变（内容未变，仅索引重建）。 */
    public record RebuildSource(List<IndexedDocument> documents, long modification) {}

    /**
     * 重建兜底路径：在写锁内用 builder 基于当前文档源构建新快照并原子切换，
     * 期间写操作被短暂阻塞，读者仍看到旧快照直至切换完成。
     */
    public IndexSnapshot rebuildLocked(java.util.function.Function<List<IndexedDocument>, IndexSnapshot> builder) {
        writeLock.lock();
        try {
            IndexSnapshot newSnapshot = builder.apply(snapshot.documents());
            snapshot = newSnapshot;
            return newSnapshot;
        } finally {
            writeLock.unlock();
        }
    }

    private void commitLocked(List<IndexedDocument> docs) {
        modificationCount++;
        snapshot = new IndexSnapshot(docs, snapshot.version() + 1);
    }

    /** 内容派生 id：SHA-256 前 16 字节 hex。 */
    static String contentId(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
