package com.github.xingjianluo.localsemanticsearchjava.embedding;

/**
 * 本地文本向量化模型：完全离线、确定性。
 */
public interface EmbeddingModel {

    /**
     * 将文本映射为固定维度的向量。相同输入必须产生相同输出。
     */
    float[] embed(String text);

    /**
     * 向量维度。
     */
    int dimension();
}
