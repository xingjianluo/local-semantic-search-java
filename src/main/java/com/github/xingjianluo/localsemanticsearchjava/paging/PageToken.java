package com.github.xingjianluo.localsemanticsearchjava.paging;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 翻页游标（不透明令牌）：自描述、无服务端会话状态。
 *
 * <p>承载一轮遍历锚定的索引版本、查询指纹、上一页最后一条结果的排序键
 * （得分、文档 id），以及本轮已经返回过的文档 id 排除集。排除集让翻页期间
 * 收紧可见范围仍然不重不漏：续页在收紧后的结果上跳过已返回项，
 * 排序更靠前、此前未返回的范围内文档会被正常补回，而不会被游标跳过。</p>
 *
 * <p>同样的数据、同样的查询在服务重启后，只要索引版本仍匹配，旧令牌可继续
 * 翻页，且翻页顺序与重启前一致。</p>
 *
 * @param indexVersion 本轮遍历锚定的索引快照版本
 * @param queryHash    查询文本指纹（防止拿别的查询的令牌续翻）
 * @param afterScore   上一页最后一条的得分；首页为 null（从头开始）
 * @param afterId      上一页最后一条的文档 id；首页为 null
 * @param returnedIds  本轮已经返回过的文档 id（按返回顺序）
 */
public record PageToken(long indexVersion, String queryHash, Double afterScore, String afterId,
                        List<String> returnedIds) {

    private static final byte[] MAGIC = {'L', 'S', 'S', 'P'};
    private static final byte FORMAT_VERSION = 2;
    private static final byte ABSENT = 0;
    private static final byte PRESENT = 1;

    public PageToken {
        returnedIds = returnedIds == null ? List.of() : List.copyOf(returnedIds);
    }

    /** 首页游标：锚定某个索引版本与查询，从头开始。 */
    public static PageToken start(long indexVersion, String queryHash) {
        return new PageToken(indexVersion, queryHash, null, null, List.of());
    }

    /**
     * 续页游标：锚点（版本、查询）不变，排序键推进到（score, id），
     * 并把本页新返回的 id 追加进排除集。
     */
    public PageToken advance(List<String> pageReturnedIds, double score, String id) {
        List<String> merged = new ArrayList<>(returnedIds);
        for (String returned : pageReturnedIds) {
            if (!merged.contains(returned)) {
                merged.add(returned);
            }
        }
        return new PageToken(indexVersion, queryHash, score, id, merged);
    }

    public boolean isAtStart() {
        return afterScore == null && afterId == null;
    }

    /** 查询指纹：SHA-256 全量 hex，纯函数，跨进程一致。 */
    public static String hashQuery(String query) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(query).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 编码为 URL 安全的不透明字符串（无填充 Base64URL）。 */
    public String encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytes(out, MAGIC);
        out.write(FORMAT_VERSION);
        writeLong(out, indexVersion);
        writeString(out, queryHash);
        if (afterScore == null) {
            out.write(ABSENT);
        } else {
            if (afterScore.isNaN() || afterScore.isInfinite()) {
                throw new IllegalStateException("cursor score must be finite");
            }
            out.write(PRESENT);
            writeLong(out, Double.doubleToRawLongBits(afterScore));
        }
        writeNullableString(out, afterId);
        writeLong(out, returnedIds.size());
        for (String id : returnedIds) {
            writeString(out, id);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** 解码令牌；任何截断、篡改或格式不符都抛出 {@link IllegalArgumentException}。 */
    public static PageToken decode(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("page token must not be blank");
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(token.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed page token", e);
        }
        CursorReader in = new CursorReader(raw);
        for (byte m : MAGIC) {
            if (in.readByte() != m) {
                throw new IllegalArgumentException("malformed page token: bad magic");
            }
        }
        if (in.readByte() != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported page token format");
        }
        long version = in.readLong();
        String queryHash = in.readString();
        Double afterScore = null;
        if (in.readByte() == PRESENT) {
            double score = Double.longBitsToDouble(in.readLong());
            if (Double.isNaN(score) || Double.isInfinite(score)) {
                throw new IllegalArgumentException("malformed page token: non-finite score");
            }
            afterScore = score;
        }
        String afterId = in.readNullableString();
        long count = in.readLong();
        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("malformed page token: bad returned-id count");
        }
        List<String> returnedIds = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            returnedIds.add(in.readString());
        }
        if (!in.isFullyConsumed()) {
            throw new IllegalArgumentException("malformed page token: trailing bytes");
        }
        return new PageToken(version, queryHash, afterScore, afterId, returnedIds);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift));
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeLong(out, bytes.length);
        writeBytes(out, bytes);
    }

    private static void writeNullableString(ByteArrayOutputStream out, String value) {
        if (value == null) {
            out.write(ABSENT);
        } else {
            out.write(PRESENT);
            writeString(out, value);
        }
    }

    /** 最小二进制读取器：越界即判定令牌损坏。 */
    private static final class CursorReader {
        private final byte[] data;
        private int pos;

        CursorReader(byte[] data) {
            this.data = data;
        }

        byte readByte() {
            if (pos >= data.length) {
                throw new IllegalArgumentException("truncated page token");
            }
            return data[pos++];
        }

        long readLong() {
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (readByte() & 0xFFL);
            }
            return value;
        }

        String readString() {
            long len = readLong();
            if (len < 0 || len > Integer.MAX_VALUE || pos + len > data.length) {
                throw new IllegalArgumentException("malformed page token: bad length");
            }
            String value = new String(data, pos, (int) len, StandardCharsets.UTF_8);
            pos += (int) len;
            return value;
        }

        String readNullableString() {
            byte flag = readByte();
            if (flag == ABSENT) {
                return null;
            }
            if (flag != PRESENT) {
                throw new IllegalArgumentException("malformed page token: bad flag");
            }
            return readString();
        }

        boolean isFullyConsumed() {
            return pos == data.length;
        }
    }
}
