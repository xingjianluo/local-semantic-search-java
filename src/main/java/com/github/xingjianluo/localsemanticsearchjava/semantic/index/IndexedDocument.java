package com.github.xingjianluo.localsemanticsearchjava.semantic.index;

import java.util.Map;
import java.util.Set;

/**
 * 索引中的单文档：归一化稀疏向量 + 可见范围。
 */
public record IndexedDocument(String id, String content, Map<Integer, Double> vector, Set<String> scopes) {
}
