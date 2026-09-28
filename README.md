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
- `POST /api/scroll/{scrollId}` 取下一页；请求体可省略（`{}`）。
- `DELETE /api/scroll/{scrollId}` 显式结束一轮遍历（幂等），释放其固定快照。

响应字段：

| 字段           | 含义                                                         |
|----------------|--------------------------------------------------------------|
| `hits`         | 本页命中                                                     |
| `nextScrollId` | 下一页凭据；末页为 `null`，表示本轮结束                       |
| `totalHits`    | 本轮（创建时初始条件下）符合条件的总数，整轮每一页都相同     |
| `indexVersion` | 本轮固定的索引快照版本，整轮恒定                             |
| `returned`     | 截至本页（含）累计已返回条数                                 |

**顺序与复现**

- 会话创建时即一次性算好全局命中序列（得分降序、同分 id 升序），分页只是顺序切片，因此跨页拼接顺序与一次性整体检索**完全一致**，数千条量级也不重不漏、不会越翻越乱。
- 向量化与排序均为确定性算法：服务重启后，用相同数据与相同查询重新开启一轮，先后顺序（含得分）与重启前一致。注意 scroll 凭据本身保存在内存中、**不跨进程存活**，重启后需重新开启一轮。

**翻页期间的一致性约定**

- 每一轮在创建时固定当时的不可变快照、查询向量、初始可见范围与门槛；翻页期间即使有并发写入、删除或索引重建，这一轮始终按**开始时的状态**给出前后一致的结果——既不会把同一篇内容翻到两次，也不会整段跳过；各页 `indexVersion` 恒定。
- 重新开启一轮才会看到变更后的最新状态（新轮固定新快照）。
- 凭据未知、已显式关闭或空闲超过 5 分钟被回收后再翻页，返回 `404 {"error":"SCROLL_NOT_FOUND"}`，调用方应重新开启一轮。

**可见范围收紧**

- 门槛只在创建时指定，全轮固定；可见范围允许在翻页时逐页**收紧**：下一页请求可带 `visibleScopes`，但必须是初始集合的子集，试图扩大返回 `400`。
- 收紧后越界内容不会再被翻出，也不会因前面翻过而被带出；对仍可见的文档依旧不重不漏，其相对顺序与一次性按该范围检索一致。

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
| POST | `/api/scroll/{scrollId}` | 翻下一页，请求体 `{visibleScopes?}`（只允许收紧） |
| DELETE | `/api/scroll/{scrollId}` | 结束一轮遍历，释放固定快照             |
| POST | `/api/rebuild`      | 重建索引，返回状态与可区分失败原因         |

示例：

```bash
curl -X POST localhost:8080/api/documents -H 'Content-Type: application/json' \
  -d '{"content":"本地语义检索服务","scope":"team-a"}'
curl -X POST localhost:8080/api/search -H 'Content-Type: application/json' \
  -d '{"query":"语义检索","visibleScopes":["team-a"],"topK":10}'

# 分页遍历：开启（第一页），再用上一页的 nextScrollId 翻后续页
curl -X POST localhost:8080/api/scroll -H 'Content-Type: application/json' \
  -d '{"query":"语义检索","visibleScopes":["team-a","team-b"],"pageSize":100,"minScore":0.2}'
curl -X POST localhost:8080/api/scroll/<nextScrollId> -H 'Content-Type: application/json' \
  -d '{"visibleScopes":["team-a"]}'   # 可选：把范围收紧为初始集合的子集
```

## 本地验证

```bash
mvn test          # 全部测试（无需网络与外部服务）
mvn spring-boot:run   # 本地启动服务
```

测试覆盖：

- `EmbeddingModelTest`：向量化确定性、归一化、相关文本得分更高；
- `DocumentStoreTest`：重复写入幂等、更新替换、删除不再召回、越界可见性、排序稳定可复现、topK 先过滤后截断、minScore 门槛含边界且不计入计数；
- `ConcurrencyTest`：并发写入与查询下无重复命中、无越界、快照版本单调、命中数落在自洽区间；
- `RebuildServiceTest`：重建原子生效；校验/向量化/源读取三类注入失败均可区分原因并回退到原索引；重建与并发写并行时终态自洽；
- `ScrollServiceTest`：3000 条量级跨页拼接等于整体检索且不重不漏；顺序跨“进程重启”（两份相同数据）逐位可复现；minScore 边界（含/不含、总数不计被过滤项）；门槛与可见范围叠加、翻页中收紧只出子范围且拒绝扩大；翻页期间并发改写/删除/重建时整轮固定在开始快照、版本恒定、新轮见新状态；未知/关闭凭据报错；
- `ScrollApiTest`：REST 端到端翻完顺序一致、minScore 在 search 与 scroll 上都生效、范围收紧、404/400 错误语义；
- `ApiTest`：REST 端到端（写入→可见性检索→重建→删除）。

所有测试均在日志中打印输入与判定依据（`输入: ...；判定依据: ...`）。
