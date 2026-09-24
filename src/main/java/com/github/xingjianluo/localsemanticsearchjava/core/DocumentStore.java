package com.github.xingjianluo.localsemanticsearchjava.core;

import com.github.xingjianluo.localsemanticsearchjava.embedding.EmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexedDocument;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
