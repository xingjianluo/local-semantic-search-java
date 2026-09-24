# local-semantic-search-java

完全本地、可离线复现的语义检索服务：文本向量化、文档索引与相似度查询均不依赖任何外部服务或模型文件，并发读写、索引重建与可见性过滤下的结果始终自洽且可解释。

## 架构

```
api/        SearchController      REST 入口
core/       DocumentStore         并发读写（copy-on-write 快照）
index/      IndexSnapshot         不可变索引快照 + 相似度检索
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

## REST API

| 方法 | 路径                | 说明                                       |
|------|---------------------|--------------------------------------------|
| POST | `/api/documents`    | 写入/更新 `{id?, content, scope}`，幂等    |
| DELETE | `/api/documents/{id}` | 删除                                     |
| POST | `/api/search`       | 检索 `{query, visibleScopes[], topK?}`     |
| POST | `/api/rebuild`      | 重建索引，返回状态与可区分失败原因         |

示例：

```bash
curl -X POST localhost:8080/api/documents -H 'Content-Type: application/json' \
  -d '{"content":"本地语义检索服务","scope":"team-a"}'
curl -X POST localhost:8080/api/search -H 'Content-Type: application/json' \
  -d '{"query":"语义检索","visibleScopes":["team-a"],"topK":10}'
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
- `ApiTest`：REST 端到端（写入→可见性检索→重建→删除）。

所有测试均在日志中打印输入与判定依据（`输入: ...；判定依据: ...`）。
