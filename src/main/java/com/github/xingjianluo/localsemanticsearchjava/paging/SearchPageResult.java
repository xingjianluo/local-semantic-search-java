package com.github.xingjianluo.localsemanticsearchjava.paging;

import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;

import java.util.List;

/**
 * 一页检索结果。
 */
public record SearchPageResult(List<SearchHit> hits,
                               int totalCount,
                               long indexVersion,
                               PageToken nextToken,
                               boolean stale) {
}
