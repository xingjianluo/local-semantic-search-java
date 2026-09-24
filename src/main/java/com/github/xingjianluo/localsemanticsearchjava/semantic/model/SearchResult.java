package com.github.xingjianluo.localsemanticsearchjava.semantic.model;

import java.util.List;

/**
 * 查询结果：命中列表与计数均只统计调用方可见范围。
 */
public record SearchResult(String query, List<SearchHit> hits, int totalHits, List<String> scopes) {
}
