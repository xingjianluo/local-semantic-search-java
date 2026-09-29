package com.github.xingjianluo.localsemanticsearchjava.scroll;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.embedding.EmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分页遍历（scroll）服务。
 *
 * <p>用于把某个查询下所有符合条件的结果不重不漏地完整取走（导出、逐页巡检）。</p>
 *
 * <h3>翻页与重试语义</h3>
 * <ul>
 *   <li>凭据是不透明的，编码「会话 + 页码」：上一页响应里的 {@code nextScrollId}
 *       永远指向下一页本身，而不是"继续"动作。同一个凭据重发（超时重试、网络
 *       抖动后客户端重发）任意次，都返回<strong>同一页的同一份内容</strong>，
 *       游标不会因为重发而前进；只有拿着本页响应里新的 {@code nextScrollId}
 *       再请求，结果才向前推进一页。</li>
 *   <li>每一页在首次生成时整体缓存（命中列表、当时的数量信息、累计返回数、
 *       是否末页），因此连续重发多次、翻到最后一页、以及结尾空页的重试，
 *       得到的响应逐字节一致。</li>
 *   <li>页必须按顺序请求：拿着尚未签发的页码请求会被拒绝（400）。</li>
 * </ul>
 *
 * <h3>一致性约定</h3>
 * <ul>
 *   <li>每一轮遍历在创建时即固定当时的不可变 {@link IndexSnapshot}、查询向量、
 *       初始可见范围与最低相似度门槛；此后写入、删除、重建如何发生，这一轮始终
 *       按开始时的状态给出前后一致的结果，因此不会重复命中，也不会整段跳过，
 *       各页 {@code indexVersion} 恒定。</li>
 *   <li>跨页拼接顺序与一次性整体检索完全一致：得分降序，同分按文档 id 升序；
 *       向量化与排序均为确定性算法，相同数据 + 相同查询在进程重启后顺序相同。</li>
 *   <li>可见范围在一轮内只允许单调收紧，且收紧后<strong>粘滞</strong>：一旦某页
 *       在范围 S 下生成，后续请求即使省略 {@code visibleScopes}（沿用当前生效
 *       范围）或又把早先排除的范围带回来，生效范围也只做交集，被排除的内容在
 *       后续任何一页都不会再出现。试图超出初始集合扩大范围返回 400。</li>
 *   <li>{@code totalHits} 始终按该页首次生成时<strong>真正生效的条件</strong>
 *       （当前可见范围 ∩ 初始范围，加上全轮固定的最低相似度门槛）统计；范围
 *       一旦收紧，越界内容不再计入。已生成的页（含其数量信息）被缓存，重放
 *       时与首次响应完全一致。门槛只在创建时指定，全轮固定。</li>
 * </ul>
 *
 * <p>会话保存在内存中，空闲超过 {@link #SESSION_TTL_MS} 自动回收并释放其固定快照；
 * 调用方也可显式 {@link #close(String)}。会话不跨进程存活：服务重启、TTL 过期或
 * 显式关闭后，旧凭据一律得到明确的 {@link ScrollSessionException}（HTTP 404
 * SCROLL_NOT_FOUND），调用方必须丢弃旧凭据、用相同数据相同查询重新开启一轮；
 * 遍历顺序本身跨进程可复现。</p>
 */
@Service
public class ScrollService {

    /** 默认页大小。 */
    public static final int DEFAULT_PAGE_SIZE = 100;
    /** 页大小上限。 */
    public static final int MAX_PAGE_SIZE = 1000;
    /** 会话空闲存活时间：5 分钟。 */
    public static final long SESSION_TTL_MS = 5 * 60_000L;

    private final DocumentStore store;
    private final EmbeddingModel embedding;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public ScrollService(DocumentStore store, EmbeddingModel embedding) {
        this.store = store;
        this.embedding = embedding;
    }

    /**
     * 开启一轮分页遍历，返回第一页（页码 0）并缓存。
     *
     * <p>注意：本接口每次调用都会开启一轮<strong>新的</strong>遍历（新会话、
     * 固定当前快照）。它不是幂等的；第一页的超时重试应直接复用首次响应里的
     * {@code nextScrollId} 去翻第二页，而不是重新开启。</p>
     *
     * @param query         查询文本
     * @param visibleScopes 初始可见范围（非空集合）
     * @param pageSize      每页最大条数；null 用默认值，超出上限按上限
     * @param minScore      最低相似度（含边界）；null 表示不设门槛
     */
    public ScrollPage startScroll(String query, Set<String> visibleScopes, Integer pageSize,
                                  Double minScore) {
        sweepExpired();
        int size = normalizePageSize(pageSize);
        Set<String> scopes = visibleScopes == null ? Set.of() : Set.copyOf(visibleScopes);
        double threshold = minScore == null ? IndexSnapshot.NO_MIN_SCORE : minScore;

        IndexSnapshot snapshot = store.currentSnapshot();
        float[] queryVector = embedding.embed(query);
        // 需要全量匹配，不做 topK 截断（门槛与可见性已在此处过滤）。
        List<SearchHit> allHits = snapshot.search(
                queryVector, scopes, Integer.MAX_VALUE, threshold);

        if (scopes.isEmpty() || allHits.isEmpty()) {
            return new ScrollPage(List.of(), null, 0, snapshot.version(), 0);
        }

        String id = UUID.randomUUID().toString();
        Session session = new Session(id, snapshot, scopes, allHits, snapshot.version(), size);
        sessions.put(id, session);
        synchronized (session) {
            // 第一页在初始范围下生成并缓存，与后续页走同一条按页缓存路径。
            return buildPage(session, 0, scopes);
        }
    }

    /**
     * 请求凭据所指的那一页。
     *
     * <p>同一凭据重复请求返回同一页（幂等重放）；只有页码递增的新凭据才会
     * 生成并推进到下一页。</p>
     *
     * @param scrollId      上一页返回的凭据（编码会话与页码）
     * @param visibleScopes 本页可见范围；null 表示沿用当前生效范围（粘滞，不回弹）；
     *                      非 null 时必须是初始范围的子集（只允许收紧），实际生效
     *                      范围取其与当前生效范围的交集——一旦收紧永不放松
     * @throws ScrollSessionException 凭据损坏、会话未知/已关闭/已过期（服务重启后
     *                                同样如此）：调用方必须重新开启一轮
     * @throws IllegalArgumentException 试图超出初始范围，或页码乱序
     */
    public ScrollPage next(String scrollId, Set<String> visibleScopes) {
        sweepExpired();
        Token token = parseToken(scrollId);
        Session session = sessions.get(token.sessionId());
        if (session == null) {
            throw new ScrollSessionException(
                    "scroll session not found, expired or discarded by a server restart: "
                            + token.sessionId()
                            + " — discard this scrollId and start a new scroll round");
        }
        Set<String> requested = visibleScopes == null ? null : Set.copyOf(visibleScopes);
        if (requested != null && !session.initialScopes.containsAll(requested)) {
            throw new IllegalArgumentException(
                    "visibleScopes can only be tightened within the initial set; "
                            + "initial=" + session.initialScopes + " requested=" + requested);
        }
        synchronized (session) {
            session.lastAccessMs = System.currentTimeMillis();
            CachedPage cached = session.pages.get(token.pageNo());
            if (cached != null) {
                // 幂等重放：同一凭据（含超时重试、连续多次重发）永远返回同一页。
                return toResponse(session, token.pageNo(), cached);
            }
            if (token.pageNo() != session.expectedPage) {
                throw new IllegalArgumentException(
                        "scroll pages must be requested in order; expected page "
                                + session.expectedPage + " but got page " + token.pageNo());
            }
            // 单调收紧：与当前生效范围取交集；null 表示沿用（粘滞）。
            Set<String> effective = requested == null
                    ? session.effectiveScopes
                    : intersect(session.effectiveScopes, requested);
            return buildPage(session, token.pageNo(), effective);
        }
    }

    /** 显式结束一轮遍历并释放其固定快照；未知/已关闭/损坏凭据视为成功（幂等）。 */
    public void close(String scrollId) {
        if (scrollId == null) {
            return;
        }
        String sessionId;
        try {
            sessionId = parseToken(scrollId).sessionId();
        } catch (ScrollSessionException malformed) {
            return;
        }
        Session session = sessions.remove(sessionId);
        if (session != null) {
            synchronized (session) {
                session.closed = true;
            }
        }
    }

    /**
     * 在会话锁内顺序扫描生成指定页，并整体缓存。
     *
     * <p>从当前游标开始按全局顺序逐篇扫描：属于本页生效范围的收入本页，
     * 已被收紧排除的位置永久消费掉（不会再出现在后续任何一页）。游标只向前，
     * 因此对仍然可见的文档绝不重复、绝不跳过；扫描到末尾即末页（可能是空页：
     * 剩余位置全部被收紧排除），响应不再携带新凭据，但会话仍保留到 TTL/显式
     * 关闭，以便末页（含空页）还能被幂等重放。</p>
     */
    private ScrollPage buildPage(Session session, int pageNo, Set<String> effectiveScopes) {
        List<SearchHit> page = new ArrayList<>(session.pageSize);
        int end = session.allHits.size();
        while (session.position < end && page.size() < session.pageSize) {
            SearchHit hit = session.allHits.get(session.position);
            session.position++;
            if (effectiveScopes.contains(hit.scope())) {
                page.add(hit);
            }
        }
        session.returned += page.size();
        boolean finished = session.position >= end;
        int total = totalFor(session, effectiveScopes);
        CachedPage cached = new CachedPage(
                List.copyOf(page), total, session.returned, finished);
        session.effectiveScopes = effectiveScopes;
        session.pages.put(pageNo, cached);
        if (!finished) {
            session.expectedPage = pageNo + 1;
        }
        return toResponse(session, pageNo, cached);
    }

    private static ScrollPage toResponse(Session session, int pageNo, CachedPage page) {
        String nextId = page.finished() ? null : encodeToken(session.id, pageNo + 1);
        return new ScrollPage(page.hits(), nextId,
                page.totalHits(), session.indexVersion, page.returned());
    }

    /** 当前生效范围（叠加全轮固定门槛后的）命中总数：对初始全量命中按 scope 计数求和。 */
    private static int totalFor(Session session, Set<String> effectiveScopes) {
        int total = 0;
        for (Map.Entry<String, Integer> e : session.countsByScope.entrySet()) {
            if (effectiveScopes.contains(e.getKey())) {
                total += e.getValue();
            }
        }
        return total;
    }

    private static Set<String> intersect(Set<String> current, Set<String> requested) {
        Set<String> result = new HashSet<>(requested);
        result.retainAll(current);
        return Set.copyOf(result);
    }

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 凭据 = 会话 id + "." + 页码；UUID 不含 '.'，可无歧义解析。 */
    private static String encodeToken(String sessionId, int pageNo) {
        return sessionId + "." + pageNo;
    }

    private static Token parseToken(String scrollId) {
        if (scrollId == null || scrollId.isBlank()) {
            throw new ScrollSessionException("scrollId must not be blank");
        }
        int dot = scrollId.lastIndexOf('.');
        if (dot <= 0 || dot == scrollId.length() - 1) {
            throw new ScrollSessionException("malformed scrollId: " + scrollId);
        }
        int pageNo;
        try {
            pageNo = Integer.parseInt(scrollId.substring(dot + 1));
        } catch (NumberFormatException e) {
            throw new ScrollSessionException("malformed scrollId: " + scrollId);
        }
        if (pageNo < 0) {
            throw new ScrollSessionException("malformed scrollId: " + scrollId);
        }
        return new Token(scrollId.substring(0, dot), pageNo);
    }

    /** 机会式回收过期会话，避免固定快照长期驻留。 */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        sessions.forEach((id, session) -> {
            if (now - session.lastAccessMs > SESSION_TTL_MS) {
                sessions.remove(id);
            }
        });
    }

    /** 解析后的凭据。 */
    private record Token(String sessionId, int pageNo) {
    }

    /** 已生成的一页（不可变）；重放时原样返回。 */
    private record CachedPage(List<SearchHit> hits, int totalHits, int returned,
                              boolean finished) {
    }

    /** 一轮遍历的服务端状态。可变字段均在会话锁内访问。 */
    private static final class Session {
        final String id;
        final IndexSnapshot snapshot;
        final Set<String> initialScopes;
        final List<SearchHit> allHits;
        final Map<String, Integer> countsByScope;
        final long indexVersion;
        final int pageSize;
        final Map<Integer, CachedPage> pages = new HashMap<>();
        Set<String> effectiveScopes;
        int position;
        int expectedPage;
        int returned;
        boolean closed;
        long lastAccessMs;

        Session(String id, IndexSnapshot snapshot, Set<String> initialScopes,
                List<SearchHit> allHits, long indexVersion, int pageSize) {
            this.id = id;
            this.snapshot = snapshot;
            this.initialScopes = initialScopes;
            this.allHits = allHits;
            this.indexVersion = indexVersion;
            this.pageSize = pageSize;
            this.effectiveScopes = initialScopes;
            Map<String, Integer> counts = new HashMap<>();
            for (SearchHit hit : allHits) {
                counts.merge(hit.scope(), 1, Integer::sum);
            }
            this.countsByScope = Map.copyOf(counts);
            this.position = 0;
            this.expectedPage = 0;
            this.returned = 0;
            this.closed = false;
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
