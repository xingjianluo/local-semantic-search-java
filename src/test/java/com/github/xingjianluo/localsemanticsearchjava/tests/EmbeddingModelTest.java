package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.github.xingjianluo.localsemanticsearchjava.embedding.HashingEmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 向量化规则：确定性、归一化、相同文本得分恒为 1。
 */
class EmbeddingModelTest {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelTest.class);

    private final HashingEmbeddingModel model = new HashingEmbeddingModel();

    @Test
    void embedIsDeterministic() {
        String input = "本地语义检索 local semantic search";
        float[] v1 = model.embed(input);
        float[] v2 = model.embed(input);
        log.info("输入: [{}]；判定依据: 相同输入两次向量化的每一维必须完全相等", input);
        assertArrayEquals(v1, v2);
        assertEquals(HashingEmbeddingModel.DEFAULT_DIMENSION, v1.length);
    }

    @Test
    void embedProducesUnitVector() {
        String input = "the quick brown fox jumps over the lazy dog";
        float[] v = model.embed(input);
        double norm = 0.0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        log.info("输入: [{}]；向量 L2 范数={}；判定依据: 非空文本的向量必须为单位向量(范数≈1)", input, norm);
        assertEquals(1.0, norm, 1e-5);
    }

    @Test
    void identicalTextHasCosineOne() {
        String input = "语义检索 排序稳定";
        double score = IndexSnapshot.cosine(model.embed(input), model.embed(input));
        log.info("输入: [{}]；自相似度={}；判定依据: 同一文本与自身的余弦相似度必须为 1", input, score);
        assertEquals(1.0, score, 1e-6);
    }

    @Test
    void emptyTextHasZeroVectorAndZeroScore() {
        float[] zero = model.embed("");
        double score = IndexSnapshot.cosine(zero, model.embed("anything"));
        log.info("输入: 空文本；判定依据: 空文本产生零向量，与任何向量的余弦相似度为 0");
        assertArrayEquals(new float[model.dimension()], zero);
        assertEquals(0.0, score);
    }

    @Test
    void relatedTextsScoreHigherThanUnrelated() {
        float[] cat1 = model.embed("猫 喜欢 吃 鱼");
        float[] cat2 = model.embed("猫 吃 鱼 很 开心");
        float[] other = model.embed("quantum computing qubit entanglement");
        double related = IndexSnapshot.cosine(cat1, cat2);
        double unrelated = IndexSnapshot.cosine(cat1, other);
        log.info("输入: [猫 喜欢 吃 鱼] vs [猫 吃 鱼 很 开心] 得分={}，vs 无关英文文本 得分={}；"
                + "判定依据: 共享词项多的文本对得分必须更高", related, unrelated);
        assertTrue(related > unrelated);
    }
}
