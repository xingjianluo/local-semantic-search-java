package com.github.xingjianluo.localsemanticsearchjava.scroll;

import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;

import java.util.List;

/**
 * 一轮分页遍历中的一页结果。
 *
 * <p>会话在创建时即固定所遍历的不可变快照、查询向量、初始可见范围与
 * 最低相似度门槛，因此整轮遍历对写入、删除与重建免疫，跨页拼接结果
 * 与一次性整体检索完全一致。</p>
 *
 * @param hits          本页命中（可能为空，表示已翻到末页）
 * @param nextScrollId  下一页凭据；为 null 表示已无更多结果（本轮结束）
 * @param totalHits     本轮（创建时的初始条件下）符合条件的总数，每页都相同
 * @param indexVersion  本轮固定的索引快照版本
 * @param returned      截至本页（含）累计已返回条数
 */
public record ScrollPage(List<SearchHit> hits,
                         String nextScrollId,
                         int totalHits,
                         long indexVersion,
                         int returned) {
}
