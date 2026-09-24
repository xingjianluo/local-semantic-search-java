package com.github.xingjianluo.localsemanticsearchjava.semantic.model;

/**
 * 单条命中结果。
 *
 * @param documentId 文档 id
 * @param score      余弦相似度
 * @param content    文档内容
 */
public record SearchHit(String documentId, double score, String content) {
}
