package com.github.xingjianluo.localsemanticsearchjava.semantic.model;

import java.util.List;

/**
 * 相似度查询请求。
 *
 * @param query  查询文本
 * @param topK   返回条数上限
 * @param scopes 调用方可见范围
 */
public record SearchRequest(String query, int topK, List<String> scopes) {
}
