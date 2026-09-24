package com.github.xingjianluo.localsemanticsearchjava.semantic.model;

import java.util.Set;

/**
 * 已写入的文档（含元数据）。
 *
 * @param id      文档唯一标识
 * @param content 文本内容
 * @param scopes  可见范围集合
 * @param version 单调递增版本号
 * @param contentHash 内容哈希（幂等判定用）
 */
public record Document(String id, String content, Set<String> scopes, long version, String contentHash) {
}
