package com.github.xingjianluo.localsemanticsearchjava.semantic.model;

/**
 * 写入结果。
 *
 * @param id 文档 id
 * @param changed 是否发生实际变更（重复写入为 false）
 * @param version 写入后版本
 */
public record WriteResult(String id, boolean changed, long version) {
}
