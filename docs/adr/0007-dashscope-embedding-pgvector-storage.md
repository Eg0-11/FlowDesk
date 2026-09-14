# ADR 0007：DashScope Embedding 与 pgvector 向量存储

- 状态：已接受
- 日期：2026-09-14
- 决策范围：知识切片（RAG 第 3/6 步）的向量生成、pgvector 落库、索引状态机与同步索引链路

## 背景

RAG 第 2 步把文档变成了切片，但切片本身还只是文本。第 3 步要回答的是：
**怎么把切片变成可检索的向量，并且这件事必须可校验、可重试、可原子提交。**

具体问题：

1. 谁负责生成向量？DeepSeek 已经接进来了，能不能直接用它？
2. 用哪个模型、多少维？维度写在哪里？
3. 向量存哪？为什么不用 Spring AI 的 PgVectorStore？
4. 模型调用与数据库事务的边界在哪？
5. 切片在「生成向量」与「写入向量」之间被换掉了怎么办？
6. 为什么 PostgreSQL 专用迁移要单独放一个目录？
7. 同步索引有什么限制？后续怎么异步化？

本任务**不做**：向量相似度查询、RAG 问答、Query Embedding、Rerank、引用来源、
MCP 改造、前端、鉴权、Redis/MQ、文档更新/删除/重新解析、已索引文档的主动重建。

## 决策

1. **DeepSeek 只负责 Chat/Agent，DashScope 负责 Embedding**：两者各用自己的 starter，
   不互相替换；Embedding 不走 DeepSeek 的 `/embeddings`；
2. **模型固定 `text-embedding-v4`、维度固定 1024、文档侧语义 `document`**；
3. **不使用 Spring AI 的 PgVectorStore 自动建表**，而是「Spring AI Alibaba 生成向量 +
   自定义 JDBC 适配器写入业务 pgvector 表 + Flyway 管理表结构」；
4. **模型调用在事务外，最终写入在一个短事务内**；
5. **完成索引是一个原子端口方法**：校验状态与版本、二次证明切片摘要、替换向量、
   推进状态到 `INDEXED`，全部在一个事务里；
6. **两份迁移**：H2 可执行的通用部分（V5）与 PostgreSQL 专用的 pgvector 部分（V6）分离；
7. **索引是同步的**：HTTP 请求返回时向量已经落库，接口返回 200 而不是 202。

## 理由

### 为什么 DeepSeek 负责 Chat、DashScope 负责 Embedding

- **DeepSeek 的公开接口提供的是 Chat Completions，不是 Embedding**。
  现有传输适配器是 OpenAI 兼容的 chat 适配器（见 ADR 0001），把它「扩展」成 embedding
  客户端等于自己拼一个未经验证的上游协议实现 —— 上游一旦调整，坏的是整条知识链路。
- **DashScope 的 Embedding 是一等公民**：Spring AI Alibaba 已经提供了
  `DashScopeEmbeddingModel` 与完整的自动配置，模型、维度、文档/查询语义都是显式参数。
- **两者互不替换**：如果为了「统一模型供应商」而把 DeepSeek ChatModel 换成 DashScope
  ChatModel，Chat 侧的提示词行为、工具调用闭环与已交付的 ADR 0001 全部作废。
  现在的分工是：**chat 走 DeepSeek（OpenAI 兼容传输），embedding 走 DashScope**，
  两者可以在同一个进程里共存（已有上下文测试证明两个 Bean 同时存在且类型不混淆）。
- **端口在自己的基础设施层**：`KnowledgeEmbeddingPort` 由 infrastructure 的
  DashScope 适配器实现，application/domain 不认识 Spring AI / DashScope 的任何类型。
  将来换模型供应商只换一个适配器。

### 为什么是 text-embedding-v4 / 1024 维

- **维度是数据库契约的一部分**：`vector(1024)` 列、领域不变量（`EmbeddingDescriptor`）、
  配置项 `flowdesk.knowledge.embedding.dimensions` 三处必须一致，任何一处被改坏都会在
  写入时立刻失败，而不是等到检索阶段才出现「不同长度的向量无法比较」。
- **为什么固定而不是「按模型探测」**：`EmbeddingModel.dimensions()` 的默认实现
  可能发起一次远端请求去探测维度。启动阶段做网络探测会让「没有 Key 也能启动」
  这条默认环境契约失效，也让启动时间与上游可用性耦合。因此维度只来自本项目配置，
  并且**适配器从不调用 `dimensions()`**（有测试断言）。
- **文本规模**：1024 维在中文语义检索上是常见选择，存储与 HNSW 构建成本都可控；
  维度提高会线性放大存储与索引构建时间，而本项目当前没有需要更高维度的证据。
- `text-embedding-v4` 是 DashScope 当前推荐的通用文本向量模型；`provider` 与 `model`
  会随向量一起持久化（`embedding_provider` / `embedding_model`），
  因此后续更换模型时，能区分「库里的向量由哪个模型生成」。

### 为什么不用自动初始化的通用 vector_store

Spring AI 的 `PgVectorStore` 会自动建一张 `vector_store(id, content, metadata, embedding)`：

1. **没有与切片的关联**：那张表不知道自己存的是哪个文档、哪个切片的向量；
   「向量与切片是否一致」无法由数据库证明，只能靠应用层自觉；
2. **没有状态与版本语义**：本项目的关键是「写入向量 + 推进文档状态」必须同一事务提交，
   通用 store 的 API 是「存一条向量」，不参与业务状态机；
3. **`initialize-schema` 与 Flyway 的所有权冲突**：表结构只能有一个主人。
   本项目的所有 DDL 都在 Flyway 里，`initialize-schema` 必须关闭（也就不引入它）。

因此本项目的向量表是**业务表**：

```sql
knowledge_document_chunk_embeddings (
    document_id UUID, chunk_index INTEGER,
    chunk_sha256 CHAR(64), embedding vector(1024),
    provider, model, embedding_dimensions, created_at,
    PRIMARY KEY (document_id, chunk_index),
    FOREIGN KEY (document_id, chunk_index)
        REFERENCES knowledge_document_chunks (document_id, chunk_index) ON DELETE CASCADE,
    CHECK (embedding_dimensions = 1024),
    CHECK (vector_dims(embedding) = embedding_dimensions)
)
```

- **复合外键**保证「没有切片的向量不可能存在」，删除文档时向量随切片级联删除；
- **`vector_dims` 约束**保证列宽与元数据声明一致；
- **HNSW + `vector_cosine_ops` 索引**与数据一起建立：事后补建会让已有数据重新排序，
  而构建开销随数据量增长（本阶段只写入、不查询，但索引必须提前就位）。

### 为什么模型调用在事务外、最终写入在短事务内

- **模型调用是慢操作**（一次 10 条的批次可能持续数百毫秒到数秒）。
  把它放在事务里等于用上游的延迟去占用数据库连接。
- **顺序固定**：读文档 → 版本比对 → 领取（CAS）→ **事务外**分批读切片 + 调模型 →
  原子完成（短事务）。事务只包住真正的原子单元。
- **代价是「领取」与「完成」之间存在窗口**：此时文档是 `INDEXING`。
  窗口内崩溃会留下悬挂状态，见「已知边界」。

### 为什么需要切片 SHA-256 的二次证明

- 向量是**旧文本**的函数。如果「生成向量」和「写入向量」之间切片被替换
  （重新解析、人工修数据、并发写入），那么写进去的向量描述的是已经不存在的文本 ——
  检索时会召回语义完全不相干的片段，而且这种错误**不会报错**。
- 因此完成事务里逐个比对 `(chunk_index, chunk_sha256)` 与库中当前值：
  数量、序号、摘要三者必须完全一致，否则整体拒绝并回滚。
- 适配器**不做任何纠错**：不按位置重排、不跳过不匹配的行 ——
  静默纠正会把「数据错配」变成「看起来正确但语义错误」的结果。

### 为什么 PostgreSQL 专用迁移与 H2 通用迁移分离

- 默认环境（本地开发与自动化测试）跑 H2，而 H2 **没有** `CREATE EXTENSION`
  也没有 `vector(1024)` 类型；把 V6 放进 `db/migration` 会让默认环境根本无法迁移。
- 同时，H2 上的 `knowledge_documents` 生命周期字段（V5）是**通用**的：
  状态机、时间线、字段一致性约束与数据库无关，H2 与 PostgreSQL 都应当有。
- 因此 `application-postgres.yml` 把 Flyway locations 配成
  `classpath:db/migration` + `classpath:db/postgresql-migration`，
  版本号连续（V1~V5 / V6），不会出现同版本两个脚本的歧义。
- 三条环境边界因此很清楚：默认 H2（可启动、索引接口 503、没有向量表）、
  PostgreSQL 无 pgvector（迁移/扩展创建失败，明确报错）、
  PostgreSQL + pgvector + dashscope-embedding（真实链路可用）。

### 同步索引的限制与后续异步化方向

- **限制**：请求会一直等到全部批次完成并落库；超大文档可能触及客户端或反向代理超时。
  本阶段不引入异步任务与消息队列，因此这是一个**已知的、刻意接受的**限制。
- **异步化方向**（后续任务）：
  1. 把「领取 → 生成 → 完成」放到后台执行器，接口改为 202 + 状态查询；
  2. 引入重试与超时回收，把悬挂的 `INDEXING` 文档按 `index_started_at` 超时退回
     `INDEX_FAILED`；
  3. 批次并行化（当前是串行分批，顺序确定、便于断言）；
  4. 增量索引（只对变化的切片重新生成向量）需要切片版本化，属于更大的改动。

## 失败语义与补偿

| 失败点 | 数据库里留下什么 | 处理 |
| --- | --- | --- |
| 命令非法 / 文档不存在 / 版本过期 | 什么都没变 | 400 / 404 / 412，**不发生任何写入** |
| 未启用向量化 | 什么都没变（**不读仓储**） | 503 `KNOWLEDGE_EMBEDDING_DISABLED` |
| 领取 CAS 失败（并发） | 什么都没变 | 412，**不写失败态** |
| 状态不允许索引 | 什么都没变 | 409 `KNOWLEDGE_DOCUMENT_NOT_INDEXABLE` |
| 上游失败 / 响应非法 / 切片不自洽 | 文档 `INDEX_FAILED` + 稳定失败码 | 502 或 500；**没有向量** |
| 完成阶段写入失败 | 向量与状态整体回滚，然后文档被 CAS 成 `INDEX_FAILED` | 500；文档可重试 |
| 完成阶段版本冲突 | 文档保持 `INDEXING`（不写失败态） | 412；由后续重试或人工处理 |
| 补偿写库也失败 | 文档可能停在 `INDEXING` | 原始异常照常抛出，补偿失败只作为 suppressed |
| 进程在 `INDEXING` 期间被杀 | 文档停在 `INDEXING`（**悬挂**） | 本任务不实现超时回收，如实记录 |

- 「版本冲突不写失败态」是刻意的：说明文档已经被别人改过，当前请求无权给它盖失败戳。
- 失败码是**稳定枚举**（`KnowledgeIndexFailureCode`）：上游响应体、SQL、路径与密钥
  绝不进入数据库或响应；响应里额外返回 `failureCode` 供调用方分支。

## 已知边界（如实记录）

1. **`INDEXING` 悬挂**：进程在索引期间崩溃会让文档停在 `INDEXING`，既不会自动重试也不能被
   再次领取。本任务**不实现**超时回收；恢复方式是人工把状态改回 `PARSED`/`INDEX_FAILED`。
2. **同步索引的时间上限**：见上文「限制与后续异步化方向」。
3. **批次串行**：分批是串行的（顺序确定、便于断言），没有并发调用上游。
4. **`INDEXED` 不可重建**：主动重建索引不在本阶段范围内；重新索引需要人工把状态退回
   `INDEX_FAILED`（会自动替换旧向量，有集成测试覆盖）。
5. **PostgreSQL 未在本机验证**：本机没有 PostgreSQL 与 Docker，
   pgvector 相关断言由 Testcontainers 集成测试覆盖，无 Docker 时**跳过**并如实标注
   `POSTGRESQL_PGVECTOR_IT=NOT_RUN`。
6. **真实 DashScope 未调用**：没有 `DASHSCOPE_API_KEY`，全部自动化测试都不访问真实上游，
   `DASHSCOPE_LIVE=NOT_RUN`。

## 影响

正面：向量与切片的对应关系由数据库外键与摘要证明强制；状态推进与向量写入原子；
失败可重试且不留半批向量；模型供应商与向量库都可以整体替换而不触及用例；
默认环境仍然零依赖可启动。

代价：多了一个上游依赖（DashScope）与一个数据库扩展（pgvector）；
DOCX/PDF 解析之外的又一次「同步长请求」；`INDEXING` 悬挂需要人工介入；
两次上传之间的模型版本变化需要靠 `embedding_model` 列自行判断。

## 重新评估条件

1. 出现「索引耗时超过请求超时」的真实需求时，引入异步任务与状态轮询（同一套机制可解决悬挂问题）；
2. 向量规模增长到 HNSW 内存不可接受时，评估 `ivfflat` 或分区；
3. 需要 Query Embedding 与检索时（RAG 4/6），把 `text_type=query` 与 `document` 严格分开；
4. 需要更换 Embedding 模型时，先确认是否必须重算全部向量（维度或语义空间变化即必须重算）。
