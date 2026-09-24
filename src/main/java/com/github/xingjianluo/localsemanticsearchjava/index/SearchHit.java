package com.github.xingjianluo.localsemanticsearchjava.index;

/**
 * 一条检索命中。
 *
 * @param id      文档 id
 * @param content 文档内容
 * @param scope   文档可见范围标签
 * @param score   余弦相似度得分
 */
public record SearchHit(String id, String content, String scope, double score) {
}
