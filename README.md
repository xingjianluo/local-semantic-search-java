# local-semantic-search-java

完全本地、可离线复现的语义检索服务：文本向量化、文档索引与相似度查询均不依赖任何外部服务或模型文件，并发读写、索引重建与可见性过滤下的结果始终自洽且可解释。

## 架构

```
api/        SearchController      REST 入口
core/       DocumentStore         并发读写（copy-on-write 快照）+ 分页遍历
index/      IndexSnapshot         不可变索引快照 + 相似度检索
paging/     PageToken/SearchPageResult  无状态翻页游标与分页结果
embedding/  HashingEmbeddingModel 本地确定性向量化
rebuild/    RebuildService        索引重建与失败回退
```

## 向量化与排序规则

**向量化（`HashingEmbeddingModel`）**

- 分词：英文/数字小写化后按连续字母数字成词；CJK 字符产生单字与相邻二字组（bigram），兼顾中英文。
- 每个词项经 FNV-1a 哈希映射到 256 维桶，另一路哈希决定正负号（signed hashing，降低碰撞偏差）。
- 词项权重 `1 + ln(tf)`，向量做 L2 归一化。
- 相同输入永远产生相同向量（无随机性、无外部状态）；空文本产生零向量。

**排序（`IndexSnapshot.search`）**

- 相似度为余弦相似度；任一为零向量时得分为 0。
- 排序键：得分降序；同分按文档 id 字典序升序。文档在快照内按 id 有序存储，因此相同输入下排序稳定可复现。
- 先按可见性过滤、再打分排序、最后按 `topK` 截断。

## 并发与一致性语义

- 写操作（upsert/delete）在写锁内构建全新不可变快照并原子替换 volatile 引用；读操作不加锁。
- 因此写入进行中的查询只会看到旧快照或新快照其中之一，绝不出现部分可见、重复命中或已删除内容仍被召回。
- 幂等：`id` 缺省时按内容 SHA-256 派生；相同 id 且内容、可见范围未变的重复写入是 no-op（`applied=false`），不推进索引版本与修改计数。

## 索引重建与回退语义

`POST /api/rebuild` 触发全量重建：

1. 读取当前文档源（id、内容、可见范围）与修改计数；
2. 不持锁地在后台重新向量化并构建全新快照；
3. 校验新索引（默认校验：文档数与源一致）；
4. 乐观提交：仅当修改计数未变时原子切换；并发写冲突时重试，仍冲突则在写锁内完成最终构建与切换。

重建期间服务保持可读写，新索引在查询视角下原子生效。任何一步失败都不触碰当前生效索引，返回 `FAILED_ROLLED_BACK` 及可区分的失败原因：

| failureReason       | 含义                       |
|---------------------|----------------------------|
| `SOURCE_UNREADABLE` | 文档源读取失败             |
| `EMBEDDING_FAILED`  | 向量化过程失败             |
| `VALIDATION_FAILED` | 新索引校验失败             |
| `UNKNOWN`           | 其他未知原因               |

失败结果中的 `documentCount` / `indexVersion` 始终指向回退后仍在生效的原有索引。

## 可见性边界

- 每篇文档归属一个 `scope`；查询必须给出 `visibleScopes` 集合。
- 文档可见当且仅当其 `scope ∈ visibleScopes`；空集合返回空结果。
- 不可见文档不会出现在结果、`count` 或任何返回信息中（过滤先于打分与截断）。

## 分页遍历（scroll）与相关度门槛

`POST /api/search/page` 支持把某个查询下所有符合条件的结果完整翻走（导出、逐页巡检），
不再受固定 `topK` 上限约束。

**请求**：`{query, visibleScopes[], minScore?, pageSize?, pageToken?}`

- 首页不传 `pageToken`；把上一页响应里的 `nextPageToken` 原样回传取下一页，
  直到 `nextPageToken` 为 null（`hasMore=false`）即一轮翻完。
- `pageSize` 默认 50、上限 200；`minScore` 为最低余弦相似度，**边界含等号**
  （score == minScore 的文档保留），缺省表示不设门槛。

**响应**：`{hits[], count, totalCount, indexVersion, nextPageToken, hasMore, status}`

- `hits`/`count`：本页内容与本页条数；`totalCount`：当前请求的可见范围 + 门槛下的全部命中数
  （低于门槛与越界的内容既不出现在任何一页，也不计入 `totalCount`），一轮内各页恒定。
- `status`：`OK` 正常；`STALE`（HTTP 409）表示本轮锚定的索引已变化，结果作废需重开；
  `INVALID_TOKEN`（HTTP 400）表示令牌损坏或被截断。

**语义约定**

1. **不重不漏、顺序一致**：排序键仍是（得分降序、同分 id 升序），与一次性全量检索完全相同；
   游标记录上一页最后一条的排序键，跨页拼接顺序与一次性拿到整体逐位一致，几千条量级经过验证。
2. **顺序跨进程可复现**：游标是无服务端状态的不透明令牌（Base64URL 编码的定长二进制，
   内含索引版本、查询指纹 SHA-256、上一页排序键与本轮已返回 id 列表）。服务重启后，
   只要数据与写入序列相同（索引版本一致）、查询相同，旧令牌可继续使用，先后顺序与重启前一致。
3. **翻页期间的一致性**：首页锚定当时的索引快照版本，续页始终只在该版本的不可变快照上计算。
   翻页途中若发生写入、删除或索引重建（版本推进），或用与令牌不匹配的查询续翻，
   服务**不会**在新旧数据之间拼接，而是明确返回 `STALE` 要求丢弃旧令牌从头重开。
   因此一轮遍历要么整体冻结在开始时的状态，要么整体失败重来——不会重复、不会跳过一整段。
4. **过滤规则在翻页中叠加生效**：`visibleScopes` 与 `minScore` 随每页请求给出，
   先过滤后排序计数。可见范围允许在一轮中**收紧**，即使收紧发生在某一页"中途"
   （首页已返回的内容里排序交错着即将越界的文档）也成立：续页在收紧后的结果上，
   从游标排序键之后继续扫描，同时跳过令牌里记录的"本轮已返回 id"，
   因此排序更靠前、此前未返回的仍可见文档会被补回而不是被整段跳过，
   已返回内容不会重复，越界内容不再出现在任何后续页。
   放宽范围或改变查询不在语义保证内，需要时应作为新查询从头开一轮。
5. 门槛可在轮次中调整：提高门槛只会让更多内容被过滤，游标仍按全局排序续取，安全；
   降低门槛会引入游标之前可能被跳过的条目，应重开一轮。

**与原有行为的兼容范围**

- `/api/search`、`/api/documents`、`DELETE`、`/api/rebuild` 的请求与响应完全不变；
  分页是叠加能力，不改变既有 topK 检索、幂等写入与重建语义。
- 排序规则（得分降序、同分 id 升序）、可见性语义（先过滤后打分计数）保持不变，
  分页逐页拼接结果与对同一查询做一次足够大 topK 的全量检索逐位相同。
- 索引版本号语义不变：任何真实写/删或成功的重建都会推进版本，分页以此判断一轮是否失效。
- 令牌只在相同数据与相同查询下有意义；格式带魔数与版本号，截断或损坏返回 400，
  版本/查询不匹配返回 409。令牌内含已返回 id 列表，体积随一轮总返回量线性增长
  （实测 3000 条、每 id 9 字符时约 66 KiB，可直接放在请求体中）；
  一轮翻完或收到 STALE 后令牌即作废，无需长期持有。

## REST API

| 方法 | 路径                | 说明                                       |
|------|---------------------|--------------------------------------------|
| POST | `/api/documents`    | 写入/更新 `{id?, content, scope}`，幂等    |
| DELETE | `/api/documents/{id}` | 删除                                     |
| POST | `/api/search`       | 检索 `{query, visibleScopes[], topK?}`     |
| POST | `/api/search/page`  | 分页遍历 `{query, visibleScopes[], minScore?, pageSize?, pageToken?}` |
| POST | `/api/rebuild`      | 重建索引，返回状态与可区分失败原因         |

示例：

```bash
curl -X POST localhost:8080/api/documents -H 'Content-Type: application/json' \
  -d '{"content":"本地语义检索服务","scope":"team-a"}'
curl -X POST localhost:8080/api/search -H 'Content-Type: application/json' \
  -d '{"query":"语义检索","visibleScopes":["team-a"],"topK":10}'

# 分页遍历：首页不传 pageToken，随后把上一页的 nextPageToken 原样回传
curl -X POST localhost:8080/api/search/page -H 'Content-Type: application/json' \
  -d '{"query":"语义检索","visibleScopes":["team-a"],"minScore":0.2,"pageSize":100}'
curl -X POST localhost:8080/api/search/page -H 'Content-Type: application/json' \
  -d '{"query":"语义检索","visibleScopes":["team-a"],"minScore":0.2,"pageSize":100,"pageToken":"<nextPageToken>"}'
```

## 本地验证

```bash
mvn test          # 全部测试（无需网络与外部服务）
mvn spring-boot:run   # 本地启动服务
```

测试覆盖：

- `EmbeddingModelTest`：向量化确定性、归一化、相关文本得分更高；
- `DocumentStoreTest`：重复写入幂等、更新替换、删除不再召回、越界可见性、排序稳定可复现、topK 先过滤后截断；
- `ConcurrencyTest`：并发写入与查询下无重复命中、无越界、快照版本单调、命中数落在自洽区间；
- `RebuildServiceTest`：重建原子生效；校验/向量化/源读取三类注入失败均可区分原因并回退到原索引；重建与并发写并行时终态自洽；
- `PageTokenTest`：游标编解码往返一致、推进不改变锚点、空白/截断/魔数篡改被拒、查询指纹确定；
- `PagingTest`：3000 条规模下多种页大小翻页与全量检索逐位一致、不重不漏；跨"重启"（新存储重放相同数据）旧令牌续翻顺序一致；
  相关度门槛边界含等号、零向量查询、与可见范围叠加；范围在页中途收紧不跳过排序更靠前的范围内文档、不重复不越界；门槛中途提高；
- `PagingConcurrencyTest`：并发写/删/重建下翻页要么整轮冻结同一版本、拼接数=totalCount，要么明确 STALE 重来；
  写/删/重建/换查询四种情形确定性触发 STALE；STALE 后重开可完整翻完；
- `PagingApiTest`：REST 端到端分页拼接、门槛叠加、范围收紧、写入后 409 STALE、坏令牌 400 INVALID_TOKEN；
- `ApiTest`：REST 端到端（写入→可见性检索→重建→删除）。

所有测试均在日志中打印输入与判定依据（`输入: ...；判定依据: ...`）。
