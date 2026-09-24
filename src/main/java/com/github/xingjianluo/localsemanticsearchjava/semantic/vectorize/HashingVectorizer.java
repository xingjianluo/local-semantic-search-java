package com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于特征哈希（feature hashing / hashing trick）的确定性稀疏向量化实现。
 *
 * <p>完全本地、无外部服务、无随机过程：相同文本在任意机器、任意时刻得到完全相同的向量。</p>
 *
 * <ol>
 *   <li>Unicode NFKC 归一化 + 小写，按非字母数字字符切分得到 unigram；</li>
 *   <li>追加相邻 bigram 以保留局部词序；每个 token 同时加入字符三元组以支持模糊/子串匹配；</li>
 *   <li>用两个独立 FNV-1a 32 位哈希：一个决定桶下标，一个决定符号（signed hashing，降低哈希碰撞偏差）；</li>
 *   <li>词频使用次线性变换 {@code 1 + log(tf)}；</li>
 *   <li>最后做 L2 归一化，因此向量点积即余弦相似度。</li>
 * </ol>
 */
public class HashingVectorizer implements TextVectorizer {

    /** 固定哈希空间维度，保证可复现；不随语料变化。 */
    public static final int DIMENSIONS = 1024;

    private static final int FNV_OFFSET = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    public HashingVectorizer() {
    }

    @Override
    public Map<Integer, Double> vectorize(String text) {
        if (text == null) {
            throw new VectorizationException("待向量化文本为 null");
        }
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            throw new VectorizationException("文本归一化后没有可向量化的词元: " + abbreviate(text));
        }

        // 每个特征的词频
        Map<String, Integer> featureTf = new HashMap<>();
        for (String token : tokens) {
            featureTf.merge(token, 1, Integer::sum);
        }

        // signed feature hashing：桶累加 sign * 次线性词频权重
        Map<Integer, Double> raw = new HashMap<>();
        for (Map.Entry<String, Integer> e : featureTf.entrySet()) {
            double weight = 1.0 + Math.log(e.getValue());
            int h1 = fnv1a(e.getKey(), 0x00000000);
            int h2 = fnv1a(e.getKey(), 0x9E3779B9);
            int bucket = Math.floorMod(h1, DIMENSIONS);
            double sign = (h2 & 1) == 0 ? 1.0 : -1.0;
            raw.merge(bucket, sign * weight, Double::sum);
        }

        // L2 归一化：归一化后点积即余弦相似度
        double norm = 0.0;
        for (double w : raw.values()) {
            norm += w * w;
        }
        if (norm == 0.0) {
            throw new VectorizationException("向量范数为 0: " + abbreviate(text));
        }
        double inv = 1.0 / Math.sqrt(norm);
        Map<Integer, Double> normalized = new HashMap<>();
        for (Map.Entry<Integer, Double> e : raw.entrySet()) {
            double v = e.getValue() * inv;
            if (v != 0.0) {
                normalized.put(e.getKey(), v);
            }
        }
        return Map.copyOf(normalized);
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /**
     * 归一化并切分词元：unigram + bigram + 字符三元组。
     */
    static List<String> tokenize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase();
        List<String> words = new ArrayList<>();
        int len = normalized.length();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < len; ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp);
            } else if (word.length() > 0) {
                words.add(word.toString());
                word.setLength(0);
            }
        }
        if (word.length() > 0) {
            words.add(word.toString());
        }

        List<String> features = new ArrayList<>();
        for (String w : words) {
            features.add("u:" + w);
            // 单词内部字符三元组，让部分匹配/拼写近似也有信号
            if (w.length() > 3) {
                for (int i = 0; i + 3 <= w.length(); i++) {
                    features.add("t:" + w.substring(i, i + 3));
                }
            }
        }
        for (int i = 0; i + 1 < words.size(); i++) {
            features.add("b:" + words.get(i) + "_" + words.get(i + 1));
        }
        return features;
    }

    private static int fnv1a(String s, int seed) {
        int hash = FNV_OFFSET ^ seed;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? "[" + oneLine + "]" : "[" + oneLine.substring(0, 40) + "...]";
    }
}
