package com.github.xingjianluo.localsemanticsearchjava.semantic.model;

import java.util.List;

/**
 * 写入/更新文档请求。
 *
 * @param id      文档唯一标识
 * @param content 文本内容
 * @param scopes  可见范围
 */
public record DocumentUpsert(String id, String content, List<String> scopes) {
}
