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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分页遍历（scroll）服务。
 *
 * <p>用于把某个查询下所有符合条件的结果不重不漏地完整取走（导出、逐页巡检）。</p>
 *
 * <h3>一致性约定</h3>
 * <ul>
 *   <li>每一轮遍历在创建时即固定当时的不可变 {@link IndexSnapshot}、查询向量、
 *       初始可见范围与最低相似度门槛；此后写入、删除、重建如何发生，这一轮始终
 *       按开始时的状态给出前后一致的结果，因此不会重复命中，也不会整段跳过。</li>
 *   <li>跨页拼接顺序与一次性整体检索完全一致：得分降序，同分按文档 id 升序；
 *       向量化与排序均为确定性算法，相同数据 + 相同查询在进程重启后顺序相同。</li>
 *   <li>可见范围在一轮内允许逐页收紧（必须是初始范围的子集）；被收紧排除的
 *       内容不会被返回，也不会因前面翻过而被带出来。门槛只在创建时指定，全轮固定。</li>
 * </ul>
 *
 * <p>会话保存在内存中，空闲超过 {@link #SESSION_TTL_MS} 自动回收并释放其固定快照；
 * 调用方也可显式 {@link #close(String)}。会话不跨进程存活（凭据重启后失效，
 * 需重新开启一轮），但遍历顺序本身跨进程可复现。</p>
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
     * 开启一轮分页遍历，返回第一页。
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
            return new ScrollPage(List.of(), null, allHits.size(), snapshot.version(), 0);
        }

        String id = UUID.randomUUID().toString();
        Session session = new Session(id, snapshot, scopes, allHits, snapshot.version(), size);
        sessions.put(id, session);
        return readPage(session, scopes);
    }

    /**
     * 取下一页。
     *
     * @param scrollId      上一页返回的凭据
     * @param visibleScopes 本页可见范围；为 null 时沿用初始范围；非 null 时必须是
     *                      初始范围的子集（只允许收紧）
     * @throws ScrollSessionException 凭据未知、已关闭或已过期
     * @throws IllegalArgumentException 试图扩大可见范围
     */
    public ScrollPage next(String scrollId, Set<String> visibleScopes) {
        sweepExpired();
        if (scrollId == null || scrollId.isBlank()) {
            throw new ScrollSessionException("scrollId must not be blank");
        }
        Session session = sessions.get(scrollId);
        if (session == null) {
            throw new ScrollSessionException("scroll session not found or expired: " + scrollId);
        }
        Set<String> scopes = visibleScopes == null
                ? session.initialScopes
                : tighten(session.initialScopes, Set.copyOf(visibleScopes));
        synchronized (session) {
            if (session.closed) {
                sessions.remove(scrollId);
                throw new ScrollSessionException("scroll session already closed: " + scrollId);
            }
            session.lastAccessMs = System.currentTimeMillis();
            return readPage(session, scopes);
        }
    }

    /** 显式结束一轮遍历并释放其固定的快照；未知/已关闭凭据视为成功（幂等）。 */
    public void close(String scrollId) {
        if (scrollId == null) {
            return;
        }
        Session session = sessions.remove(scrollId);
        if (session != null) {
            synchronized (session) {
                session.closed = true;
            }
        }
    }

    /**
     * 在会话锁内顺序扫描出下一页。
     *
     * <p>从当前游标开始按全局顺序逐篇扫描：属于本页可见范围的收入本页，
     * 被收紧排除的跳过。游标只向前，因此对仍然可见的文档绝不重复、绝不跳过；
     * 扫描到末尾即关闭会话并返回 null 凭据。</p>
     */
    private ScrollPage readPage(Session session, Set<String> pageScopes) {
        List<SearchHit> page = new ArrayList<>(session.pageSize);
        int end = session.allHits.size();
        while (session.position < end && page.size() < session.pageSize) {
            SearchHit hit = session.allHits.get(session.position);
            session.position++;
            if (pageScopes.contains(hit.scope())) {
                page.add(hit);
            }
        }
        session.returned += page.size();
        boolean finished = session.position >= end;
        String nextId = finished ? null : session.id;
        if (finished) {
            session.closed = true;
            sessions.remove(session.id);
        }
        return new ScrollPage(Collections.unmodifiableList(page), nextId,
                end, session.indexVersion, session.returned);
    }

    private static Set<String> tighten(Set<String> initial, Set<String> requested) {
        if (!initial.containsAll(requested)) {
            throw new IllegalArgumentException(
                    "visibleScopes can only be tightened within the initial set; "
                            + "initial=" + initial + " requested=" + requested);
        }
        return requested;
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

    /** 机会式回收过期会话，避免固定快照长期驻留。 */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        sessions.forEach((id, session) -> {
            if (now - session.lastAccessMs > SESSION_TTL_MS) {
                sessions.remove(id);
            }
        });
    }

    /** 一轮遍历的服务端状态。position/returned/closed 在会话锁内访问。 */
    private static final class Session {
        final String id;
        final IndexSnapshot snapshot;
        final Set<String> initialScopes;
        final List<SearchHit> allHits;
        final long indexVersion;
        final int pageSize;
        int position;
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
            this.position = 0;
            this.returned = 0;
            this.closed = false;
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
