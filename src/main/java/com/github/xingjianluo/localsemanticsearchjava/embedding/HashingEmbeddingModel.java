package com.github.xingjianluo.localsemanticsearchjava.embedding;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于哈希技巧（hashing trick）的本地确定性向量化：
 *
 * <ul>
 *   <li>完全离线，不依赖任何外部服务或模型文件；</li>
 *   <li>分词：英文/数字按连续字母数字成词（小写化），
 *       CJK 字符取单字与相邻二字组（bigram），提升中文召回；</li>
 *   <li>每个词项经 FNV-1a 哈希映射到固定维度桶，并用另一路哈希决定正负号
 *       （signed hashing，降低桶碰撞带来的系统性偏差）；</li>
 *   <li>词项权重为 1 + ln(tf)，最终向量做 L2 归一化；</li>
 *   <li>相同输入永远产生相同向量，空文本产生零向量。</li>
 * </ul>
 */
@Component
public class HashingEmbeddingModel implements EmbeddingModel {

    public static final int DEFAULT_DIMENSION = 256;

    private final int dimension;

    public HashingEmbeddingModel() {
        this(DEFAULT_DIMENSION);
    }

    public HashingEmbeddingModel(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        this.dimension = dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            return vector;
        }
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokenize(text)) {
            termFreq.merge(token, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String term = entry.getKey();
            long h1 = fnv1a(term, 0x811c9dc5L);
            long h2 = fnv1a(term, 0xcbf29ce4L ^ 0x9e3779b9L);
            int bucket = (int) Math.floorMod(h1, dimension);
            double sign = (h2 & 1L) == 0L ? 1.0 : -1.0;
            double weight = 1.0 + Math.log(entry.getValue());
            vector[bucket] += (float) (sign * weight);
        }
        normalize(vector);
        return vector;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    /**
     * 分词：ASCII 字母数字成词；CJK 字符产生单字与相邻二字组。
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String lower = text.toLowerCase();
        StringBuilder asciiWord = new StringBuilder();
        String prevCjk = null;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isAsciiAlphaNumeric(c)) {
                if (prevCjk != null) {
                    tokens.add(prevCjk);
                    prevCjk = null;
                }
                asciiWord.append(c);
            } else if (isCjk(c)) {
                if (asciiWord.length() > 0) {
                    tokens.add(asciiWord.toString());
                    asciiWord.setLength(0);
                }
                String unigram = String.valueOf(c);
                tokens.add(unigram);
                if (prevCjk != null) {
                    tokens.add(prevCjk + unigram);
                }
                prevCjk = unigram;
            } else {
                if (asciiWord.length() > 0) {
                    tokens.add(asciiWord.toString());
                    asciiWord.setLength(0);
                }
                prevCjk = null;
            }
        }
        if (asciiWord.length() > 0) {
            tokens.add(asciiWord.toString());
        }
        return tokens;
    }

    private static boolean isAsciiAlphaNumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /** FNV-1a 64 位哈希：确定性、分布均匀、无外部依赖。 */
    static long fnv1a(String s, long seed) {
        long hash = seed;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static void normalize(float[] vector) {
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }
        if (sumSq == 0.0) {
            return;
        }
        double norm = Math.sqrt(sumSq);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}
