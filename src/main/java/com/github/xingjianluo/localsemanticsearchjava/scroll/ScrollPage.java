package com.github.xingjianluo.localsemanticsearchjava.scroll;

import com.github.xingjianluo.localsemanticsearchjava.index.SearchHit;

import java.util.List;

/**
 * 一轮分页遍历中的一页结果。
 *
 * <p>会话在创建时即固定所遍历的不可变快照、查询向量与最低相似度门槛，
 * 因此整轮遍历对写入、删除与重建免疫，跨页拼接结果与一次性整体检索完全一致。
 * 可见范围允许逐页收紧（粘性，后续页持续生效），收紧后本页的数量信息按当前
 * 生效条件重新计算。</p>
 *
 * <p>每个 {@code nextScrollId} 只对应<em>下一页</em>：重复发送同一个凭据永远
 * 返回同一页（幂等，不推进游标），只有换用响应中的新凭据才会前进。</p>
 *
 * @param hits          本页命中（末页可能为空）
 * @param nextScrollId  下一页（仅此一页）的凭据；为 null 表示本轮结束
 * @param totalHits     按当前生效条件（当前可见范围 + 固定门槛）符合条件的总数；
 *                      范围收紧后随之减小，且与一次性按该条件检索的条数一致
 * @param indexVersion  本轮固定的索引快照版本，整轮恒定
 * @param returned      截至本页（含）累计已返回条数（按实际翻页过程累计，
 *                      含收紧前已按较宽范围返回的内容）
 */
public record ScrollPage(List<SearchHit> hits,
                         String nextScrollId,
                         int totalHits,
                         long indexVersion,
                         int returned) {
}
