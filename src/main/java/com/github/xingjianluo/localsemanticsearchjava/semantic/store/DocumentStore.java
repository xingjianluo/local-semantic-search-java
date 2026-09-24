package com.github.xingjianluo.localsemanticsearchjava.semantic.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.github.xingjianluo.localsemanticsearchjava.semantic.model.DeleteResult;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.Document;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.DocumentUpsert;
import com.github.xingjianluo.localsemanticsearchjava.semantic.model.WriteResult;

/**
 * 文档权威存储。
 *
 * <p>使用 {@link ConcurrentHashMap} 持有当前文档，版本号由 {@link AtomicLong} 单调递增。
 * 写入/删除方法在存储实例上串行化，保证"读-判断-写"原子；
 * {@link #all()} 返回按 id 排序的快照，供索引重建确定性遍历。</p>
 *
 * <p>幂等规则：相同 id 再次写入时，若内容 SHA-256 哈希相同且可见范围集合相同，
 * 视为重复写入，不递增版本、不产生变更。</p>
 */
public class DocumentStore {

    private final ConcurrentHashMap<String, Document> documents = new ConcurrentHashMap<>();
    private final AtomicLong versionSequence = new AtomicLong(0);

    public DocumentStore() {
    }

    /**
     * 写入或更新文档；方法串行化。
     *
     * @return 是否发生实际变更；重复写入 changed=false
     */
    public synchronized WriteResult upsert(DocumentUpsert request) {
        validate(request);
        Set<String> normalizedScopes = normalizeScopes(request.scopes());
        String hash = sha256(request.content());
        Document existing = documents.get(request.id());
        if (existing != null
                && existing.contentHash().equals(hash)
                && existing.scopes().equals(normalizedScopes)) {
            // 完全相同的重复写入：幂等无操作
            return new WriteResult(request.id(), false, existing.version());
        }
        long version = versionSequence.incrementAndGet();
        Document doc = new Document(request.id(), request.content(),
                Set.copyOf(normalizedScopes), version, hash);
        documents.put(request.id(), doc);
        return new WriteResult(request.id(), true, version);
    }

    /**
     * 删除文档；不存在返回 deleted=false。
     */
    public synchronized DeleteResult delete(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("文档 id 不能为空");
        }
        return new DeleteResult(id, documents.remove(id) != null);
    }

    public Optional<Document> get(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    /**
     * 返回按 id 字典序排序的不可变快照列表。
     */
    public List<Document> all() {
        List<Document> snapshot = new ArrayList<>(documents.values());
        snapshot.sort(java.util.Comparator.comparing(Document::id));
        return List.copyOf(snapshot);
    }

    public int size() {
        return documents.size();
    }

    private static void validate(DocumentUpsert request) {
        if (request == null) {
            throw new IllegalArgumentException("写入请求不能为空");
        }
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("文档 id 不能为空");
        }
        if (request.content() == null) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
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
        return result;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
