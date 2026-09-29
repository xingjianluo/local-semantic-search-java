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
 * <p>凭据 {@link #nextScrollId()} 编码「会话 + 下一页页码」：用同一个凭据
 * 重复请求（超时重试）返回<strong>同一页</strong>，游标不前进；只有改用本响应
 * 携带的新凭据，结果才推进到下一页。</p>
 *
 * @param hits          本页命中（可能为空：可以是收紧范围后末页前的空页，
 *                      也可以是轮次结束的末页）
 * @param nextScrollId  下一页凭据；为 null 表示已翻到末页（本轮结束）。
 *                      末页（含空页）已被缓存，用其上一凭据继续重放仍得到
 *                      相同的末页内容
 * @param totalHits     本页首次生成时，按当前真正生效条件（当前可见范围叠加
 *                      全轮固定的最低相似度门槛）统计的命中总数；范围收紧后
 *                      越界内容不再计入。已生成页被缓存，重放时该值与首次一致
 * @param indexVersion  本轮固定的索引快照版本，整轮恒定
 * @param returned      截至本页（含）累计已实际返回的条数（按各页可见内容累计）
 */
public record ScrollPage(List<SearchHit> hits,
                         String nextScrollId,
                         int totalHits,
                         long indexVersion,
                         int returned) {
}
