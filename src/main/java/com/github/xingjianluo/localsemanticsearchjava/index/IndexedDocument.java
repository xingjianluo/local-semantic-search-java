package com.github.xingjianluo.localsemanticsearchjava.index;

/**
 * 已索引文档：不可变。
 *
 * @param id     文档唯一标识（调用方未提供时按内容哈希派生）
 * @param content 原始文本
 * @param scope  可见范围标签；查询方给出的可见集合包含该标签时文档才可见
 * @param vector 归一化后的向量
 */
public record IndexedDocument(String id, String content, String scope, float[] vector) {
}
