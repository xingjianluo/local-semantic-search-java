package com.github.xingjianluo.localsemanticsearchjava.semantic.model;

/**
 * 索引重建结果。
 *
 * @param status  重建状态
 * @param reason  失败原因（成功时为 null）
 * @param message 说明信息
 * @param indexedDocs 生效索引中的文档数（失败时为旧索引文档数）
 */
public record RebuildOutcome(RebuildStatus status, RebuildFailureReason reason, String message, int indexedDocs) {
}
