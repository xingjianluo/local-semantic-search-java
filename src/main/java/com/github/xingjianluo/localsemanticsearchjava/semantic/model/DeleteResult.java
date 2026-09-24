package com.github.xingjianluo.localsemanticsearchjava.semantic.model;

/**
 * 删除结果。
 *
 * @param id 文档 id
 * @param deleted 是否实际删除（不存在为 false）
 */
public record DeleteResult(String id, boolean deleted) {
}
