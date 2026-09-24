# 本地语义检索（Local Semantic Search）

一套**完全本地、无需任何外部服务、可离线复现**的语义检索能力：本地确定性文本向量化、
不可变索引快照、可见性过滤下的相似度查询，以及可读写不中断的索引重建与失败回退。

- 运行时只有 Spring Boot Web + JDK，无数据库、无嵌入模型下载、无网络调用。
- 相同输入在任意机器、任意时刻产生完全相同的向量与排序。

## 1. 整体结构

```
semantic/
├── vectorize/   TextVectorizer / HashingVectorizer   本地确定性稀疏向量化
├── index/       IndexedDocument / SearchIndex         不可变索引快照 + 检索
├── store/       DocumentStore                         文档权威存储、版本号、幂等
├── core/        SemanticSearchService, RebuildHook    并发控制 / 重建 / 回退
├── model/       Document/SearchRequest/SearchResult/RebuildOutcome ...
└── api/         SemanticSearchController              HTTP 接口
```

数据流：`写入内容 → HashingVectorizer 归一化稀疏向量 → 生成新的不可变 SearchIndex 快照
→ volatile 引用原子发布`；查询只读某一个快照。

## 2. 向量化规则（确定性、可复现）

`HashingVectorizer` 使用固定算法，无随机数、无语料依赖：

1. **文本归一化**：Unicode NFKC + 小写，按非字母/数字字符切词得到 unigram。
2. **特征**：unigram（`u:` 前缀）、相邻 bigram（`b:`，保留局部词序）、
   长度大于 3 的单词字符三元组（`t:`，提供子串/近似匹配信号）。
3. **特征哈希（hashing trick）**：两个带不同种子的 FNV-1a 32 位哈希，
   一个决定桶下标（固定 **1024** 维空间），一个决定符号（signed hashing，降低碰撞偏差）。
4. **权重**：次线性词频 `1 + log(tf)`；最后做 **L2 归一化**。
5. 归一化后没有任何词元（如纯标点）或向量范数为 0 时抛出 `VectorizationException`，
   失败原因信息中携带原始输入摘要。

因为文档向量与查询向量都已 L2 归一化，**点积即余弦相似度**。

## 3. 排序规则（稳定、可解释）

- 只保留余弦相似度**严格大于 0** 的文档；
- 排序是确定性全序：**相似度降序，相似度并列时按 documentId 字典序升序**；
- `totalHits` 是当前可见范围下相似度 > 0 的文档总数（不受 `topK` 截断影响），
  `hits` 为前 `topK` 条。
- 查询文本无法向量化时，确定性返回空结果（`hits=[]、totalHits=0`），不报错。

## 4. 写入、更新、删除与幂等

- 存储层为每个文档分配**单调递增版本号**，并保存内容的 SHA-256。
- **幂等判定**：相同 `id` 再次写入，若内容哈希相同且可见范围集合相同，
  则判定为重复写入：`changed=false`、不递增版本、不切换索引。
- 写入顺序：**先向量化校验 → 落库 → 原子切换索引快照**。向量化失败时存储与索引都不变。
- 删除不存在的文档返回 `deleted=false`（幂等）。

## 5. 并发自洽性（为什么不会读到中间状态）

- 索引是一个 `volatile SearchIndex`，指向**不可变对象**。查询开始时取一次快照引用，
  整个查询都基于该快照：
  - 写入进行中的查询要么看到写入前、要么看到写入后的完整索引；
  - 不存在部分可见、重复命中（快照内 id 唯一）、已删除文档仍被召回。
- 写入/删除在写锁下串行化，保证“读-判断-写-换索引”原子。
- 可见性过滤在同一份快照上同时作用于 `hits` 与 `totalHits`，计数也不会泄露越界内容。
- 一致性边界（快照隔离）：在删除/更新操作**完成之后才开始**的查询必定看到新状态；
  在操作完成前已抓取旧快照的在途查询返回旧内容属于合法的快照隔离语义。任何查询都不会
  看到混合了新旧状态的中间结果。

## 6. 索引重建与回退语义

`POST /api/semantic/rebuild`：

1. 取存储全量快照（按 id 排序，确定性遍历）与版本基线，构建**候选索引**；
   构建阶段**不持有写锁**，期间服务始终可读可写（写入增量更新当前旧索引）。
2. 构建完成后短暂获取写锁，按版本号**追平重建期间的增量**
   （期间新增/更新的文档按当前数据重算，期间删除的文档从候选移除），
   然后**一次性原子发布**新索引，查询视角无中间状态。
3. **失败回退**：构建期任何失败都只丢弃候选，旧索引引用全程不变、持续可用，
   不会留下半建索引。

失败原因通过返回体 `reason` **明确区分**（`RebuildFailureReason`）：

| reason | 含义 |
| --- | --- |
| `VECTORIZATION_FAILED` | 某文档内容无法向量化 |
| `HOOK_ABORTED` | 重建检查点钩子主动中止（模拟磁盘/资源等中途故障） |
| `INTERRUPTED` | 重建线程被中断 |
| `ALREADY_RUNNING` | 已有重建在进行，拒绝并发重建 |
| `INTERNAL_ERROR` | 其他未分类内部错误 |

成功返回 `status=SUCCESS, reason=null, indexedDocs=<生效索引文档数>`；
失败返回 `status=FAILED, reason=<上表>, indexedDocs=<旧索引文档数>`。

## 7. 可见性边界

- 每个文档携带 `scopes` 集合；查询携带调用方 `scopes`。
- 采用**交集模型**：仅当文档 scope 集合与调用方 scope 集合存在非空交集时可见。
- 调用方 scope 为空时看不到任何内容（`hits` 与 `totalHits` 均为 0）。
- 更新文档范围后通过原子换索引生效：旧范围立即不再可见，新范围立即可见。
- 越界文档既不会出现在 `hits`，也不会计入 `totalHits` 或任何返回字段。

## 8. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/semantic/documents` | 写入/更新，body `{id,content,scopes[]}`，返回 `{id,changed,version}` |
| DELETE | `/api/semantic/documents/{id}` | 删除，返回 `{id,deleted}` |
| POST | `/api/semantic/search` | 查询，body `{query,topK,scopes[]}` |
| POST | `/api/semantic/rebuild` | 重建索引，返回 `{status,reason,message,indexedDocs}` |
| GET | `/api/semantic/health` | `{status,storedDocuments,indexedDocuments}` |

### 本地手动验证（离线）

```bash
# 启动（无需网络）
mvn spring-boot:run

# 写入
curl -s -XPOST localhost:8080/api/semantic/documents -H 'Content-Type: application/json' \
  -d '{"id":"d1","content":"offline local semantic search engine","scopes":["public"]}'

# 可见范围查询
curl -s -XPOST localhost:8080/api/semantic/search -H 'Content-Type: application/json' \
  -d '{"query":"local semantic search offline","topK":5,"scopes":["public"]}'

# 越界范围（totalHits=0）
curl -s -XPOST localhost:8080/api/semantic/search -H 'Content-Type: application/json' \
  -d '{"query":"local semantic search offline","topK":5,"scopes":["secret"]}'

# 删除 / 重建 / 健康检查
curl -s -XDELETE localhost:8080/api/semantic/documents/d1
curl -s -XPOST localhost:8080/api/semantic/rebuild
curl -s localhost:8080/api/semantic/health
```

## 9. 测试与本地验证方法

```bash
mvn test
```

测试全部为纯本地单测/集成测试，**不访问网络**。测试日志（SLF4J 控制台输出）会打印
每次操作的**输入与判定依据**：

- `HashingVectorizerTest`：确定性（同输入同向量）、L2 范数=1、近似文本余弦 > 无关文本、
  自相似=1、空白文本失败原因。
- `SemanticSearchServiceTest`：写入/查询/删除自洽、重复写入幂等不产生重复命中、
  相同输入排序稳定、`topK` 截断与 `totalHits` 关系。
- `VisibilityTest`：交集模型、多范围并集、空范围零泄露、范围更新原子生效。
- `ConcurrencyTest`：4 写线程 × 25 文档与 3 个持续查询线程并发（无重复命中/越界/计数不一致）、
  8 线程并发重复同一写入仅一次变更且一个版本、删除与查询并发时删除生效后绝不召回。
- `core/IndexRebuildTest`：重建成功；钩子在处理第 4 个文档时注入失败 →
  `HOOK_ABORTED` 且索引引用不变、旧索引仍完整可查；不可向量化内容 →
  `VECTORIZATION_FAILED`；中断 → `INTERRUPTED`；并发重建 → `ALREADY_RUNNING`；
  重建中途写入/删除在原子切换后被完整追平。
- `api/SemanticSearchControllerTest`：HTTP 全流程（含幂等、越界、删除、重建、健康检查）。
