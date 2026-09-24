package com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize;

import java.util.Map;

/**
 * 纯本地、确定性的文本向量化接口。
 */
public interface TextVectorizer {

    /**
     * 将文本转为稀疏向量（维度 -> 权重）。
     *
     * @throws VectorizationException 内容无法向量化
     */
    Map<Integer, Double> vectorize(String text);

    /**
     * 向量维度数。
     */
    int dimensions();
}
