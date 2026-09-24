package com.github.xingjianluo.localsemanticsearchjava.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.HashingVectorizer;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.TextVectorizer;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.VectorizationException;

/**
 * 本地向量化规则的单测。
 */
class HashingVectorizerTest {

    private static final Logger log = LoggerFactory.getLogger(HashingVectorizerTest.class);

    private final TextVectorizer vectorizer = new HashingVectorizer();

    @Test
    void sameInputProducesSameVector() {
        String text = "Local semantic search runs fully offline";
        Map<Integer, Double> v1 = vectorizer.vectorize(text);
        Map<Integer, Double> v2 = vectorizer.vectorize(text);
        log.info("[确定性] 输入='{}' 非零维度={} 两次向量相等={}", text, v1.size(), v1.equals(v2));
        assertEquals(v1, v2, "相同输入必须产生相同向量");
        assertEquals(HashingVectorizer.DIMENSIONS, vectorizer.dimensions());
    }

    @Test
    void vectorIsL2Normalized() {
        String text = "the quick brown fox jumps over the lazy dog";
        Map<Integer, Double> v = vectorizer.vectorize(text);
        double norm = Math.sqrt(v.values().stream().mapToDouble(x -> x * x).sum());
        log.info("[L2归一化] 输入='{}' 范数={}", text, norm);
        assertEquals(1.0, norm, 1e-12, "向量必须 L2 归一化");
    }

    @Test
    void similarTextsScoreHigherThanUnrelated() {
        Map<Integer, Double> doc = vectorizer.vectorize("offline local semantic search engine");
        Map<Integer, Double> close = vectorizer.vectorize("local semantic search works offline");
        Map<Integer, Double> far = vectorizer.vectorize("banana pizza quantum dolphin");
        double sClose = cosine(doc, close);
        double sFar = cosine(doc, far);
        log.info("[相似度排序依据] doc='offline local semantic search engine' | 近似余弦={} | 无关余弦={} | 判定: {} > {}",
                sClose, sFar, sClose > sFar ? "PASS" : "FAIL", sFar);
        assertTrue(sClose > sFar, "语义相近文本相似度必须高于无关文本");
    }

    @Test
    void identicalTextsHaveUnitCosine() {
        String text = "deterministic reproducible embeddings";
        double score = cosine(vectorizer.vectorize(text), vectorizer.vectorize(text));
        log.info("[自相似度] 输入='{}' 余弦={}", text, score);
        assertEquals(1.0, score, 1e-12);
    }

    @Test
    void blankOrNullTextIsRejectedWithDistinctError() {
        VectorizationException ex = assertThrows(VectorizationException.class,
                () -> vectorizer.vectorize("   !!! ??? "));
        log.info("[向量化失败原因] 输入='   !!! ??? ' 异常信息='{}'", ex.getMessage());
        assertNotNull(ex.getMessage());

        VectorizationException nullEx = assertThrows(VectorizationException.class,
                () -> vectorizer.vectorize(null));
        log.info("[向量化失败原因] 输入=null 异常信息='{}'", nullEx.getMessage());
    }

    static double cosine(Map<Integer, Double> a, Map<Integer, Double> b) {
        double dot = 0.0;
        Map<Integer, Double> smaller = a.size() <= b.size() ? a : b;
        Map<Integer, Double> larger = smaller == a ? b : a;
        for (Map.Entry<Integer, Double> e : smaller.entrySet()) {
            dot += e.getValue() * larger.getOrDefault(e.getKey(), 0.0);
        }
        return dot;
    }
}
