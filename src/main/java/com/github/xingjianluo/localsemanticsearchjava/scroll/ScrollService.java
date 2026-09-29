package com.github.xingjianluo.localsemanticsearchjava.scroll;

import com.github.xingjianluo.localsemanticsearchjava.core.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.embedding.EmbeddingModel;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分页遍历（scroll）服务。
 *
 * <p>用于把某个查询下所有符合条件的结果不重不漏地完整取走（导出、逐页巡检）。</p>
 *
 * <h3>重复/重试语义（幂等翻页）</h3>
 * <ul>
 *   <li>每一页都有独立凭据（token = 会话 id + 页码）。第 N 页的凭据永远返回第 N 页，
 *       无论同一个请求被重发多少次，游标都不会因此前进——只有调用方改用响应里
 *       第 N+1 页的凭据主动请求时，结果才往前推进。因此超时重发绝不会跳过整整一页。</li>
 *   <li>服务端缓存每一页已生成的内容（含当时的范围与数量信息）；重放直接返回缓存，
 *       与首次响应逐字段一致。</li>
 *   <li>跳页（页码不连续）、凭据未知、会话已结束/过期一律抛
 *       {@link ScrollSessionException}（HTTP 404），明确要求重新开启一轮，
 *       绝不静默跳段或重复返回。</li>
 * </ul>
 *
 * <h3>一致性约定</h3>
 * <ul>
 *   <li>每一轮遍历在创建时即固定当时的不可变 {@link IndexSnapshot}、查询向量
 *       与最低相似度门槛；此后写入、删除、重建如何发生，这一轮始终按开始时的
 *       状态给出前后一致的结果，因此不会重复命中，也不会整段跳过，整轮版本恒定。</li>
 *   <li>跨页拼接顺序与一次性整体检索完全一致：得分降序，同分按文档 id 升序；
 *       向量化与排序均为确定性算法，相同数据 + 相同查询在进程重启后顺序相同。</li>
 * </ul>
 *
 * <h3>可见范围收紧与数量一致性</h3>
 * <ul>
 *   <li>可见范围在一轮内只允许逐页收紧，且收紧是<em>粘性</em>的：一旦在某页收紧，
 *       后续请求即使不再声明范围，也沿用收紧后的范围（不会退回初始范围而让越界内容复活）。</li>
 *   <li>每一页的 {@code totalHits} 始终按<em>当前真正生效</em>的条件
 *       （当前可见范围 + 创建时固定的门槛）计算，与一次性按该条件检索的条数一致；
 *       被收紧排除的内容既不会出现在后续任何一页，也不会再计入数量信息。</li>
 *   <li>门槛只在创建时指定，全轮固定。</li>
 * </ul>
 *
 * <p>会话保存在内存中，空闲超过 {@link #SESSION_TTL_MS} 自动回收并释放其固定快照；
 * 调用方也可显式 {@link #close(String)}。会话不跨进程存活（服务重启后旧凭据
 * 得到 404，需重新开启一轮），但遍历顺序本身跨进程可复现。</p>
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
     * 开启一轮分页遍历，返回第一页（页码 0）。
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

        // 空范围 / 无命中：首页即末页（空页、无下一页凭据），无需建立会话。
        if (scopes.isEmpty() || allHits.isEmpty()) {
            return new ScrollPage(List.of(), null, 0, snapshot.version(), 0);
        }

        String id = UUID.randomUUID().toString();
        Session session = new Session(id, snapshot, scopes, allHits, snapshot.version(), size);
        sessions.put(id, session);
        synchronized (session) {
            return producePage(session, scopes);
        }
    }

    /**
     * 按凭据取某一页。
     *
     * <p>凭据编码了目标页码：重复发送同一个凭据永远返回同一页（幂等，不推进游标）；
     * 要前进到下一页，必须使用上一页响应中携带的新凭据。</p>
     *
     * @param token         上一页返回的 nextScrollId（内部含会话 id 与目标页码）
     * @param visibleScopes 本页可见范围；为 null 时沿用当前生效范围；非 null 时必须是
     *                      当前生效范围的子集（只允许收紧，且收紧对后续页持续生效）。
     *                      重放已生成的页时不允许改变范围。
     * @throws ScrollSessionException 凭据未知/格式非法/已关闭/已过期/跳页/轮次已结束
     * @throws IllegalArgumentException 试图扩大可见范围，或重放时改变范围
     */
    public ScrollPage next(String token, Set<String> visibleScopes) {
        sweepExpired();
        if (token == null || token.isBlank()) {
            throw new ScrollSessionException("scrollId must not be blank");
        }
        TokenRef ref = parseToken(token);
        Session session = sessions.get(ref.sessionId());
        if (session == null) {
            throw new ScrollSessionException("scroll session not found or expired: " + token);
        }
        Set<String> requested = visibleScopes == null ? null : Set.copyOf(visibleScopes);
        synchronized (session) {
            if (session.closed) {
                sessions.remove(session.id);
                throw new ScrollSessionException("scroll session already closed: " + token);
            }
            session.lastAccessMs = System.currentTimeMillis();

            CachedPage cached = session.pages.get(ref.pageIndex());
            if (cached != null) {
                // 幂等重放：逐字段返回首次响应，绝不推进游标；范围必须与当时一致。
                if (requested != null && !requested.equals(cached.scopes)) {
                    throw new IllegalArgumentException(
                            "a repeated request for the same page must not change visibleScopes; "
                                    + "page was produced with " + cached.scopes
                                    + " but requested " + requested);
                }
                return toScrollPage(session, cached);
            }
            // 未缓存：只允许恰好是顺序的下一页，否则视为跳页/失效。
            if (session.finished) {
                throw new ScrollSessionException(
                        "scroll round already finished; start a new scroll round: " + token);
            }
            if (ref.pageIndex() != session.pages.size()) {
                throw new ScrollSessionException(
                        "non-sequential scroll page token (got page " + ref.pageIndex()
                                + ", next expected " + session.pages.size()
                                + "); start a new scroll round: " + token);
            }
            return producePage(session, requested);
        }
    }

    /**
     * 显式结束一轮遍历并释放其固定的快照；未知/已关闭凭据视为成功（幂等）。
     * 传入的凭据可以是该轮任意一页的 token。
     */
    public void close(String token) {
        if (token == null) {
            return;
        }
        String sessionId;
        try {
            sessionId = parseToken(token).sessionId();
        } catch (ScrollSessionException e) {
            return; // 非法/未知凭据：关闭视为幂等成功
        }
        Session session = sessions.remove(sessionId);
        if (session != null) {
            synchronized (session) {
                session.closed = true;
            }
        }
    }

    /**
     * 在会话锁内生成顺序的下一页并缓存。
     *
     * <p>从当前游标开始按全局顺序逐篇扫描：属于本页生效范围的收入本页，
     * 被收紧排除的跳过。游标只向前，因此对仍然可见的文档绝不重复、绝不跳过；
     * 扫描到末尾则本轮结束（下一页凭据为 null），并释放固定快照（缓存页只保留
     * 已返回的命中内容，不再需要向量与全量序列）。</p>
     */
    private ScrollPage producePage(Session session, Set<String> requestedScopes) {
        Set<String> scopes = requestedScopes == null
                ? session.currentScopes
                : tighten(session.currentScopes, requestedScopes);
        session.currentScopes = scopes;

        List<SearchHit> page = new ArrayList<>(session.pageSize);
        List<SearchHit> allHits = session.allHits;
        while (session.position < allHits.size() && page.size() < session.pageSize) {
            SearchHit hit = allHits.get(session.position);
            session.position++;
            if (scopes.contains(hit.scope())) {
                page.add(hit);
            }
        }
        session.returned += page.size();

        int pageIndex = session.pages.size();
        boolean finished = session.position >= allHits.size();
        String nextToken = finished ? null : formatToken(session.id, pageIndex + 1);
        int totalHits = countInScope(allHits, scopes);

        CachedPage cached = new CachedPage(
                Collections.unmodifiableList(page), scopes, totalHits, session.returned, nextToken);
        session.pages.put(pageIndex, cached);
        if (finished) {
            session.finished = true;
            // 整轮已翻完：释放固定快照与全量序列；缓存页足以支持末页幂等重放。
            session.allHits = null;
            session.snapshot = null;
        }
        return toScrollPage(session, cached);
    }

    private static ScrollPage toScrollPage(Session session, CachedPage page) {
        return new ScrollPage(page.hits, page.nextToken, page.totalHits,
                session.indexVersion, page.returned);
    }

    /** 当前生效条件（固定门槛已体现在 allHits 内）下的总数。 */
    private static int countInScope(List<SearchHit> allHits, Set<String> scopes) {
        int count = 0;
        for (SearchHit hit : allHits) {
            if (scopes.contains(hit.scope())) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> tighten(Set<String> current, Set<String> requested) {
        if (!current.containsAll(requested)) {
            throw new IllegalArgumentException(
                    "visibleScopes can only be tightened within the currently effective set; "
                            + "current=" + current + " requested=" + requested);
        }
        return requested;
    }

    private static String formatToken(String sessionId, int pageIndex) {
        return sessionId + ":" + pageIndex;
    }

    /** token = 会话 UUID（不含冒号）+ ":" + 页码。 */
    private static TokenRef parseToken(String token) {
        int sep = token.lastIndexOf(':');
        if (sep <= 0 || sep == token.length() - 1) {
            throw new ScrollSessionException("malformed scroll token: " + token);
        }
        String sessionId = token.substring(0, sep);
        int pageIndex;
        try {
            pageIndex = Integer.parseInt(token.substring(sep + 1));
        } catch (NumberFormatException e) {
            throw new ScrollSessionException("malformed scroll token: " + token);
        }
        if (pageIndex < 0) {
            throw new ScrollSessionException("malformed scroll token: " + token);
        }
        return new TokenRef(sessionId, pageIndex);
    }

    private record TokenRef(String sessionId, int pageIndex) {}

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 机会式回收过期会话，避免固定快照长期驻留。 */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        sessions.forEach((id, session) -> {
            synchronized (session) {
                if (now - session.lastAccessMs > SESSION_TTL_MS) {
                    sessions.remove(id);
                }
            }
        });
    }

    /** 已生成的一页（用于幂等重放，内容与首次响应完全一致）。 */
    private static final class CachedPage {
        final List<SearchHit> hits;
        final Set<String> scopes;
        final int totalHits;
        final int returned;
        final String nextToken;

        CachedPage(List<SearchHit> hits, Set<String> scopes, int totalHits,
                   int returned, String nextToken) {
            this.hits = hits;
            this.scopes = scopes;
            this.totalHits = totalHits;
            this.returned = returned;
            this.nextToken = nextToken;
        }
    }

    /** 一轮遍历的服务端状态。可变字段均在会话锁内访问。 */
    private static final class Session {
        final String id;
        final long indexVersion;
        final int pageSize;
        final TreeMap<Integer, CachedPage> pages = new TreeMap<>();

        IndexSnapshot snapshot;
        List<SearchHit> allHits;
        Set<String> currentScopes;
        int position;
        int returned;
        boolean finished;
        boolean closed;
        long lastAccessMs;

        Session(String id, IndexSnapshot snapshot, Set<String> initialScopes,
                List<SearchHit> allHits, long indexVersion, int pageSize) {
            this.id = id;
            this.snapshot = snapshot;
            this.allHits = allHits;
            this.currentScopes = initialScopes;
            this.indexVersion = indexVersion;
            this.pageSize = pageSize;
            this.position = 0;
            this.returned = 0;
            this.finished = false;
            this.closed = false;
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
