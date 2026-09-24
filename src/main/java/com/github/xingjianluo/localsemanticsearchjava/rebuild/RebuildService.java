package com.github.xingjianluo.localsemanticsearchjava.rebuild;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.embedding.EmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexedDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 索引重建。
 *
 * <p>流程：读取当前文档源 → 在后台（不持锁）重新向量化并构建全新
 * 不可变快照 → 校验 → 以乐观并发方式原子提交。提交期间若有并发写入，
 * 重试若干次；仍冲突则在写锁内完成最终构建与切换。任何一步失败都
 * 不触碰当前生效索引，调用方得到带可区分失败原因的结果。</p>
 */
@Service
public class RebuildService {

    private static final int MAX_OPTIMISTIC_ATTEMPTS = 3;

    private final DocumentStore store;
    private final EmbeddingModel embedding;
    private final IndexValidator validator;

    public RebuildService(DocumentStore store, EmbeddingModel embedding, IndexValidator validator) {
        this.store = store;
        this.embedding = embedding;
        this.validator = validator;
    }

    /**
     * 从当前文档源全量重建索引；重建期间服务保持可读写。
     */
    public RebuildResult rebuild() {
        try {
            for (int attempt = 0; attempt < MAX_OPTIMISTIC_ATTEMPTS; attempt++) {
                DocumentStore.RebuildSource source = readSource();
                IndexSnapshot built = buildAndValidate(source.documents());
                if (store.commitSnapshot(source.modification(), built)) {
                    return RebuildResult.success(built.size(), built.version());
                }
            }
            // 并发写持续冲突：在写锁内完成最终构建与原子切换。
            IndexSnapshot built = store.rebuildLocked(this::buildAndValidate);
            return RebuildResult.success(built.size(), built.version());
        } catch (RebuildValidationException e) {
            return failed(RebuildFailureReason.VALIDATION_FAILED, e);
        } catch (EmbeddingFailedException e) {
            return failed(RebuildFailureReason.EMBEDDING_FAILED, e);
        } catch (SourceReadException e) {
            return failed(RebuildFailureReason.SOURCE_UNREADABLE, e);
        } catch (Exception e) {
            return failed(RebuildFailureReason.UNKNOWN, e);
        }
    }

    private DocumentStore.RebuildSource readSource() {
        try {
            return store.lockedSource();
        } catch (RuntimeException e) {
            throw new SourceReadException(e);
        }
    }

    private IndexSnapshot buildAndValidate(List<IndexedDocument> source) {
        List<IndexedDocument> docs = new ArrayList<>(source.size());
        for (IndexedDocument doc : source) {
            final float[] vector;
            try {
                vector = embedding.embed(doc.content());
            } catch (RuntimeException e) {
                throw new EmbeddingFailedException(doc.content(), e);
            }
            docs.add(new IndexedDocument(doc.id(), doc.content(), doc.scope(), vector));
        }
        IndexSnapshot built = new IndexSnapshot(docs, store.currentSnapshot().version() + 1);
        validator.validate(source, built);
        return built;
    }

    private RebuildResult failed(RebuildFailureReason reason, Exception e) {
        IndexSnapshot current = store.currentSnapshot();
        return RebuildResult.failed(reason, String.valueOf(e.getMessage()),
                current.size(), current.version());
    }

    /** 向量化失败（内部用于原因分类）。 */
    static final class EmbeddingFailedException extends RuntimeException {
        EmbeddingFailedException(String content, Throwable cause) {
            super("embedding failed for content: " + abbreviate(content), cause);
        }

        private static String abbreviate(String content) {
            return content == null ? "null"
                    : content.length() <= 40 ? content : content.substring(0, 40) + "...";
        }
    }

    /** 文档源读取失败（内部用于原因分类）。 */
    static final class SourceReadException extends RuntimeException {
        SourceReadException(Throwable cause) {
            super("failed to read document source: " + cause.getMessage(), cause);
        }
    }
}
