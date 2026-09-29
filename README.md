# local-semantic-search-java

完全本地、可离线复现的语义检索服务：文本向量化、文档索引与相似度查询均不依赖任何外部服务或模型文件，并发读写、索引重建与可见性过滤下的结果始终自洽且可解释。

## 架构

```
api/        SearchController      REST 入口（检索 / 分页遍历 / 重建）
core/       DocumentStore         并发读写（copy-on-write 快照）
index/      IndexSnapshot         不可变索引快照 + 相似度检索
embedding/  HashingEmbeddingModel 本地确定性向量化
rebuild/    RebuildService        索引重建与失败回退
scroll/     ScrollService         分页遍历（scroll）会话
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
- 先按可见性过滤、再打分、随后按最低相似度门槛过滤、最后按 `topK` 截断。

## 相关度门槛（minScore）

- 普通检索与分页遍历都支持可选的 `minScore`：仅保留 `score >= minScore` 的文档（**含边界**）。
- 门槛与可见范围一起在截断前生效：低于门槛的文档不会出现在任何一页，也不计入 `count` / `totalHits`。
- 省略或传 `null` 表示不设门槛（与原有行为完全一致）。

## 分页遍历（scroll）

用于把某个查询下**所有**符合条件的结果不重不漏地完整取走（导出、逐页巡检），不再受固定 `topK` 限制。

- `POST /api/scroll` 开启一轮遍历并返回第一页；响应携带 `nextScrollId` 作为下一页凭据，翻到末页时该字段为 `null`。
- `POST /api/scroll/{scrollId}` 请求凭据所指的那一页；请求体可省略（`{}`）。
- `DELETE /api/scroll/{scrollId}` 显式结束一轮遍历（幂等），释放其固定快照。

响应字段：

| 字段           | 含义                                                         |
|----------------|--------------------------------------------------------------|
| `hits`         | 本页命中（末页可能为空）                                     |
| `nextScrollId` | 下一页凭据；末页为 `null`，表示本轮结束                       |
| `totalHits`    | 本页首次生成时按**当前真正生效条件**（当前可见范围 + 全轮固定门槛）统计的命中总数 |
| `indexVersion` | 本轮固定的索引快照版本，整轮恒定                             |
| `returned`     | 截至本页（含）累计已实际返回的条数                           |

**重复/重试语义（重要）**

- 凭据是不透明的，内部编码「会话 + 页码」：上一页响应里的 `nextScrollId` 指向的永远是**下一页本身**，而不是"继续向后翻"的动作。
- 因此同一个翻页请求（同一个 URL 与请求体）因超时、网络抖动被重发任意次，返回的都是**同一页的同一份内容**，服务端游标不会因为重发而前进；只有改用本次响应新携带的 `nextScrollId` 再请求，结果才向前推进一页。客户端可以安全地对翻页请求做至少一次投递 + 重试。
- 每一页在首次生成时整体缓存（命中列表、`totalHits`、`returned`、是否末页），所以：同一请求连续重发多次、翻到最后一页、以及结尾空页，拿到的响应逐字节一致；末页（含空末页）在会话存活期间用其凭据反复重放都得到相同结果。页必须按顺序请求，伪造/跳跃尚未签发的页码返回 `400`，凭据损坏返回 `404`。
- 注意 `POST /api/scroll` 本身**不**幂等：每次调用都会开启一轮新遍历。第一页成功返回后若客户端不确定，应直接使用其 `nextScrollId` 继续，而不是重新 `POST /api/scroll`（那会开启另一轮）。

**顺序与复现**

- 会话创建时即一次性算好全局命中序列（得分降序、同分 id 升序），分页只是顺序切片，因此跨页拼接顺序与一次性整体检索**完全一致**，数千条量级也不重不漏、不会越翻越乱。
- 向量化与排序均为确定性算法：服务重启后，用相同数据与相同查询重新开启一轮，先后顺序（含得分）与重启前一致。注意 scroll 凭据本身保存在内存中、**不跨进程存活**，重启后需重新开启一轮。

**翻页期间的一致性约定**

- 每一轮在创建时固定当时的不可变快照、查询向量、初始可见范围与门槛；翻页期间即使有并发写入、删除或索引重建，这一轮始终按**开始时的状态**给出前后一致的结果——既不会把同一篇内容翻到两次，也不会整段跳过；各页 `indexVersion` 恒定。并发的重复翻页请求要么触发生成一次、其余得到同一份缓存页，绝不会生成两份不同结果。
- 重新开启一轮才会看到变更后的最新状态（新轮固定新快照）。

**数量信息与过滤条件一致 / 可见范围收紧**

- 门槛只在创建时指定，全轮固定；可见范围允许在翻页时逐页**收紧**：翻页请求可带 `visibleScopes`，但必须是初始集合的子集，试图超出初始集合扩大返回 `400`。
- 收紧是**单调且粘滞**的：一旦某页在范围 S 下生成，后续请求即使省略 `visibleScopes`（沿用当前生效范围）或又把早先排除的标签带回来（仍是初始子集），服务端生效范围也只取交集——被排除的内容在后续**任何一页**都不会再出现，数量也不会回升。
- `totalHits` 始终按该页首次生成时真正生效的条件统计（当前可见范围叠加全轮固定门槛）：范围收紧后，越界文档既不出现在后续页面，也不再计入 `totalHits`；门槛之下的文档同理。已生成页（含其数量信息）被缓存，对该凭据的重放与首次响应完全一致。
- 收紧后对仍可见的文档依旧不重不漏，其相对顺序与一次性按该范围检索一致；收紧可能产生空的末页（剩余位置全部被排除），空末页同样可幂等重放。

**本轮作废、必须重来**

- 出现以下情况时这一轮明确作废，服务端绝不静默跳段或重复返回：凭据未知/格式损坏、会话已被 `DELETE` 显式关闭、空闲超过 5 分钟被回收、**服务重启**（会话只存内存，不跨进程存活）。
- 这些情况下翻页返回 `404 {"error":"SCROLL_NOT_FOUND"}`（响应消息会提示重新开启）。调用方约定：丢弃旧凭据，用**相同数据、相同查询**重新 `POST /api/scroll` 开始一轮；由于向量化与排序确定，新一轮的先后顺序与原轮一致，可据此继续完成导出。

**兼容范围**

- 不传 `minScore` 时，普通 `/api/search` 的过滤、排序、计数与旧版本完全一致；分页是新增的独立接口，不改变原有写入、删除、重建、检索语义。

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

## REST API

| 方法 | 路径                | 说明                                       |
|------|---------------------|--------------------------------------------|
| POST | `/api/documents`    | 写入/更新 `{id?, content, scope}`，幂等    |
| DELETE | `/api/documents/{id}` | 删除                                     |
| POST | `/api/search`       | 检索 `{query, visibleScopes[], topK?, minScore?}` |
| POST | `/api/scroll`       | 开启分页遍历 `{query, visibleScopes[], pageSize?, minScore?}`，返回首页与 `nextScrollId` |
| POST | `/api/scroll/{scrollId}` | 请求凭据所指页（同一凭据重发返回同一页），请求体 `{visibleScopes?}`（只允许收紧，且收紧粘滞） |
| DELETE | `/api/scroll/{scrollId}` | 结束一轮遍历，释放固定快照             |
| POST | `/api/rebuild`      | 重建索引，返回状态与可区分失败原因         |

示例：

```bash
curl -X POST localhost:8080/api/documents -H 'Content-Type: application/json' \
  -d '{"content":"本地语义检索服务","scope":"team-a"}'
curl -X POST localhost:8080/api/search -H 'Content-Type: application/json' \
  -d '{"query":"语义检索","visibleScopes":["team-a"],"topK":10}'

# 分页遍历：开启（第一页），再用上一页的 nextScrollId 请求下一页
# 注意：同一个 nextScrollId 请求重发任意次都返回同一页，只有换新凭据才推进
curl -X POST localhost:8080/api/scroll -H 'Content-Type: application/json' \
  -d '{"query":"语义检索","visibleScopes":["team-a","team-b"],"pageSize":100,"minScore":0.2}'
curl -X POST localhost:8080/api/scroll/<nextScrollId> -H 'Content-Type: application/json' \
  -d '{"visibleScopes":["team-a"]}'   # 可选：把范围收紧为初始集合的子集（收紧后粘滞）
# 若返回 404 SCROLL_NOT_FOUND（重启/过期/关闭）：丢弃旧凭据，用相同数据相同查询重新开启一轮
```

## 本地验证

```bash
mvn test          # 全部测试（无需网络与外部服务）
mvn spring-boot:run   # 本地启动服务
```

针对翻页重试/收紧语义的手工验证（启动服务后）：

```bash
# 1) 灌入若干文档后开启一轮（pageSize 故意调小）
curl -s -X POST localhost:8080/api/scroll -H 'Content-Type: application/json' \
  -d '{"query":"语义检索","visibleScopes":["team-a","team-b"],"pageSize":2}'
# 2) 拿到 nextScrollId 后，把同一条翻页命令原样执行多次：
#    每次响应的 hits / nextScrollId / returned 必须完全相同（不跳页、不漏页）
curl -s -X POST localhost:8080/api/scroll/<nextScrollId> -H 'Content-Type: application/json' -d '{}'
curl -s -X POST localhost:8080/api/scroll/<nextScrollId> -H 'Content-Type: application/json' -d '{}'
# 3) 收紧范围：totalHits 立即变小；之后再用 {}（省略范围）翻页，被排除的 scope 不会冒回来
curl -s -X POST localhost:8080/api/scroll/<nextScrollId> -H 'Content-Type: application/json' \
  -d '{"visibleScopes":["team-a"]}'
# 4) 重启服务后再用旧凭据翻页：404 SCROLL_NOT_FOUND，提示重新开启一轮
```

测试覆盖：

- `EmbeddingModelTest`：向量化确定性、归一化、相关文本得分更高；
- `DocumentStoreTest`：重复写入幂等、更新替换、删除不再召回、越界可见性、排序稳定可复现、topK 先过滤后截断、minScore 门槛含边界且不计入计数；
- `ConcurrencyTest`：并发写入与查询下无重复命中、无越界、快照版本单调、命中数落在自洽区间；
- `RebuildServiceTest`：重建原子生效；校验/向量化/源读取三类注入失败均可区分原因并回退到原索引；重建与并发写并行时终态自洽；
- `ScrollServiceTest`：3000 条量级跨页拼接等于整体检索且不重不漏；顺序跨“进程重启”（两份相同数据）逐位可复现；minScore 边界（含/不含、总数不计被过滤项）；门槛与可见范围叠加、翻页中收紧只出子范围且拒绝扩大；翻页期间并发改写/删除/重建时整轮固定在开始快照、版本恒定、新轮见新状态；未知/关闭凭据报错；
- `ScrollRetryTest`：同一凭据连续重发 3 次返回同一页不跳页，重发后继续翻完仍不重不漏；末页（恰好整页）与收紧产生的空末页可反复重放；开启即空结果；收紧后 `totalHits` 立即按新范围统计、省略范围或再带回旧标签都粘滞不回弹；收紧 + 门槛叠加计数；服务重启后旧凭据明确报 404 并提示重来、重开顺序逐位一致；每页 8 路并发重放返回同一份页且并发写入/删除/重建下版本恒定、整轮自洽；乱序页码 400、损坏/空凭据 404；
- `ScrollApiTest`：REST 端到端翻完顺序一致、minScore 在 search 与 scroll 上都生效、范围收紧、404/400 错误语义；
- `ScrollRetryApiTest`：REST 端到端同一翻页 HTTP 请求重发返回同一页（修复前重发会丢一整页）、收紧后 `totalHits` 与越界处理在 HTTP 层粘滞一致；
- `ApiTest`：REST 端到端（写入→可见性检索→重建→删除）。

所有测试均在日志中打印输入与判定依据（`输入: ...；判定依据: ...`）。
