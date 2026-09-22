# ADR 0015：事件研判的多来源证据编排（真实 Agent Graph）

- 状态：已接受
- 日期：2026-09-22
- 决策范围：主服务如何用**真实的** Spring AI Alibaba `StateGraph`/`CompiledGraph` 编排一次
  「事件研判」—— 为什么这一刻才引入 Graph（与 ADR 0014「还不是 Agent Graph」的结论如何衔接）、
  完整拓扑与条件路由、状态键与合并策略、三个证据来源（知识检索 / 资产 / 监控）的成功与失败语义、
  引用协议及其边界、图内异常到应用层失败的收敛、并发隔离，以及本阶段
  （FD-0018-A）与 HTTP 阶段（FD-0018-B）的边界

## 背景

FD-0017 让主服务能对一个资产做确定性诊断（两个 MCP 查询端口 → 一次模型调用 → 引用校验），
并在 FD-0017-B 暴露了 HTTP 接口。ADR 0014 当时明确写下：**这一阶段还不是 Agent Graph**，
因为「两次查询 → 一次生成 → 一次校验」是一条线性无分支的路径，用图框架表达只会增加间接。

FD-0018-A 要求把编排升级为「事件研判」，与资产诊断相比多了三件事：

1. **第三个证据来源**：企业知识切片（走既有检索用例，而不是 MCP），
   它有自己完整的三态与失败分类体系（向量化关闭、Embedding 上游故障、重排上游故障、内部失败）；
2. **真正的分支**：「有可用证据 → 让模型研判」与「没有可用证据 → 固定降级文案」是两条**不同**的路径，
   第三条「端口违约」必须在调用模型**之前**终止；
3. **明确要求用真实的 Graph**：节点、边与条件路由必须是声明式的，
   **不允许**用普通 service 里的 `switch`/`if` 冒充 Graph，也不允许用 `ReactAgent`。

因此本阶段第一次真正用上 Agent Graph：不是为了「有个框架」，而是因为编排形状本身变了。
本阶段**不**实现 HTTP 接口（那是 FD-0018-B），**不**改动 FD-0016 的 MCP 客户端与两个 MCP 服务，
**不**新增数据库迁移，**不**引入会话记忆、流式输出、工具调用、重试或缓存。

## 决策

1. **用真实图，而不是伪装的 if/else**：装配期构造 `StateGraph`，注册 11 个节点、
   11 条普通边（含 `START`/`END`）与 1 组**条件边**，再 `compile(CompileConfig)` 得到 `CompiledGraph`；
   路由值由节点算好写进状态，由条件边映射到目标节点 —— 调度与状态合并交给框架。
2. **图编译一次、复用**：`IncidentTriageGraph` 在装配期编译，之后只读；
   每次调用只新建「调用上下文」和一份独立的 `RunnableConfig`。
3. **显式关闭 checkpoint**：`CompileConfig` 里传**空的** `SaverConfig`
   —— 框架默认会注册内存 saver，那会带来跨请求状态；本阶段不需要，
   也**不允许**出现「上一次请求的记忆影响这一次研判」。
4. **状态只有三个键**：`call`（REPLACE，本次调用上下文对象）、`route`（REPLACE）、
   `executionPath`（**APPEND**，每个节点执行时追加自己的节点名）。
5. **拓扑固定**（见下），三个证据来源**各调用一次**、顺序固定（知识 → 资产 → 监控），
   前一个失败或未命中都必须继续执行后面两个。
6. **输入合法性只有两个入口**：`assetId` 由 `validate_asset` 用既有 `AssetIdentifier` 校验；
   检索输入的合法性（空问题、`topK`/`minScore` 范围）仍由检索用例判定，
   本层**不复制第二套规则**。两者非法一律 `AiRequestException`（400），
   **后续节点与模型零调用**。
7. **知识失败收敛为稳定分类**（`KnowledgeFailure`），绝不改写成「没查到」：
   `KNOWLEDGE_EMBEDDING_DISABLED → DISABLED`、`EMBEDDING_PROVIDER_ERROR → EMBEDDING_PROVIDER_UNAVAILABLE`、
   `RERANK_PROVIDER_ERROR → RERANK_PROVIDER_UNAVAILABLE`、其余（含 `KNOWLEDGE_RETRIEVAL_FAILURE`
   与检索链路上任何未预期运行期异常）`→ RETRIEVAL_FAILURE`。
8. **端口违约有自己的出口**：查询端口返回 `null` 或抛异常记为「端口契约违约」，
   **其余证据节点照常执行**，但在 `verify_contracts` 之后路由到 `contract_violation` 节点，
   在调用模型之前整次失败（502 + `requestId`），不补成 `NOT_FOUND`、不伪造 `FAILED`、不让 `NullPointerException` 穿透。
9. **应用层契约先于编排落地**：新增 `IncidentTriageCommand`、`IncidentTriageUseCase`、
   `IncidentTriageResult`、`KnowledgeEvidence`、`KnowledgeFailure`；不变量由记录的紧凑构造器强制。
10. **引用协议**：知识沿用检索给出的 `K1…Kn`，资产固定 `A1`，监控固定 `M1`；
    输出侧独立校验，失败即整次失败，**不修正、不补引用、不重新调用模型**。
11. **异常边界**：图内只抛稳定分类的 `IncidentTriageException`；
    服务层把它收敛成 `AiProviderException`（502 + `requestId`，固定文案、不含 cause），
    把 `AiRequestException` 原样上抛（400）。
12. **日志固定两条模板**，只记录元数据与异常**类名**；业务数据没有任何位置可放（见下）。
13. **本阶段不提供 HTTP**：控制器、DTO 与状态码语义留给 FD-0018-B（可参照 ADR 0014 的 HTTP 契约）。

## 拓扑

```
START → validate_asset → retrieve_knowledge → query_asset → query_monitoring
      → verify_contracts → evidence_gate ─┬─ evidence_available → generate_answer
                                         │                       → validate_citations → finish
                                         ├─ no_evidence        → fallback_answer  → finish
                                         └─ contract_violation → contract_violation（节点）→ finish
      finish → END
```

| 节点 | 职责 | 关键约束 |
| --- | --- | --- |
| `validate_asset` | 用既有 `AssetIdentifier` 校验 `assetId` | 非法即抛，后续节点与模型零调用 |
| `retrieve_knowledge` | 调用检索用例一次 | 输入非法 → 400；其它失败收敛为知识分支 `FAILED` 并**继续** |
| `query_asset` | 调用资产端口一次 | 不重试、不缓存；`null`/异常记为端口违约但**继续** |
| `query_monitoring` | 调用监控端口一次 | 同上；即使资产侧违约也照常执行 |
| `verify_contracts` | 有违约 → 路由 `contract_violation`；否则校验本次上下文形状并算出闸门路由 | 形状缺失 → `GRAPH_FAILURE` |
| `evidence_gate` | **条件边**的源节点（真实的过路节点） | 三个出口全部由 `route` 决定 |
| `generate_answer` | 构造提示词、调用 DeepSeek **一次** | 模型异常 → `MODEL_CALL_FAILED`；空答案 → `ANSWER_EMPTY` |
| `validate_citations` | 独立校验引用 | 失败即整次失败，不重试 |
| `fallback_answer` | 两条固定降级文案 | **不**调用模型 |
| `contract_violation` | 违约终止节点 | **不**调用模型；服务统一抛稳定失败 |
| `finish` | 正常终点 | —— |

## 状态键与合并策略

| key | 类型 | 合并策略 | 写入者 |
| --- | --- | --- | --- |
| `call` | `IncidentTriageCall` | **REPLACE** | 调用方创建（输入）；各节点在执行中写入自己的产物 |
| `route` | `String` | **REPLACE** | `verify_contracts` |
| `executionPath` | `List<String>` | **APPEND** | 每个节点各自追加自己的节点名 |

三个键、类型与策略只在 `IncidentTriageStateKeys` 里声明一次，节点与服务都引用常量 ——
散落的魔法字符串会让「谁写了这个键、用什么策略合并」无法审计。
`executionPath` 是唯一的 APPEND 键，因此 `IncidentTriageResult.executionPath` 是**真实执行**的轨迹，
不是结束后拼出来的。

### 为什么只有一个「富对象」键（框架的硬约束）

Graph 框架为每个 `NodeOutput` 生成状态快照时会对状态做**序列化克隆**（JSON 往返）。
于是：

- 在节点**内部**读到的状态是活对象，记录类型可以正常读；
- 从**最终状态**读回时，被克隆过的域对象已经退化成 `Map`，强类型读取会失败。

第一版设计把三个证据结果与答案直接放进状态，17 条图测试里有 9 条因此以 `GRAPH_FAILURE` 失败。
最终设计是：状态里只放**一个**集中携带本次调用全部产物的上下文对象（`IncidentTriageCall`），
服务层读的是**自己持有的那个实例**（节点写入的就是同一个引用，天然免疫克隆），
另有 `route` 与 `executionPath` 两个简单标量键，它们从最终状态读回是安全的。

这条约束是「本图不把域对象散落在状态里」的原因，也是后续扩展图时必须先想清楚的事。

## 三条路径

| 情形 | 是否调用模型 | 路由 | 结果 |
| --- | --- | --- | --- |
| 至少一个来源有可用证据 | **调用一次** | `evidence_available` | `grounded=true`，引用本次存在的编号；三个来源的真实状态原样保留 |
| 三个来源都没有可用证据且都不是失败 | 不调用 | `no_evidence` | 固定回答「未找到可用于事件研判的资产、监控或知识证据。」 |
| 三个来源都没有可用证据，且至少一个 `FAILED`/`DISABLED` | 不调用 | `no_evidence` | 固定降级回答「当前无法获得足够证据，暂时不能完成事件研判。」 |
| 任一端口返回 `null` 或抛异常 | 不调用 | `contract_violation` | 502 + `requestId`，`failure=PORT_CONTRACT_VIOLATION` |

两条「没有证据」的文案必须分开：**「都没查到」是可以接受的事实**，而
**「有来源没查成」意味着这次研判不完整**，调用方可能需要重试或升级。
把两者合并成一句「没有数据」，正是本仓库一路在防的错误结论。

**部分命中**走第一条路径：允许基于可用证据作答，但 `availability` 会如实写出三类来源的状态，
结果对象里也保留失败侧的分类 —— 模型只知道「这一类没有证据」，不该知道也不需要知道失败细节。

## 三个来源的失败语义

| 来源 | 成功 | 未命中 | 失败 |
| --- | --- | --- | --- |
| 知识（检索用例） | `FOUND` + 非空检索视图（可引用 `K1…Kn`） | `NOT_FOUND` + 空检索视图（「查过了没有」也能审计） | `FAILED` + 稳定 `KnowledgeFailure`，**没有**检索视图 |
| 资产（`AssetQueryPort`） | `FOUND`（可引用 `A1`） | `NOT_FOUND` | `FAILED` + `QueryFailure` |
| 监控（`MonitoringSnapshotQueryPort`） | `FOUND`（可引用 `M1`） | `NOT_FOUND` | `FAILED` + `QueryFailure` |

三态互斥且携带的数据形状固定（由记录的构造器强制）：`FAILED` 不可能携带视图或结果，
否则「没查成」就有了看起来正常的证据。**`FAILED`/`DISABLED` 永远不会出现在 `NOT_FOUND` 的位置上。**

## 引用协议：保证什么、不保证什么

- 编号固定：`A1` = 资产记录、`M1` = 监控快照；知识沿用检索最终顺序给出的 `K1…Kn`。
- 模型答案里的引用被独立校验（`IncidentTriageCitationValidator`）：
  空答案 → `ANSWER_EMPTY`；一条引用都没有 → `ANSWER_WITHOUT_CITATION`；
  畸形引用（`[k1]`、`[K01]`、`[K 1]`、`[K1x]`、`[K1` 未闭合、全角数字等）→ `INVALID_CITATION_FORMAT`；
  引用本次并不存在的编号 → `UNKNOWN_CITATION`；本次有证据的某一类完全没有被引用 →
  `EVIDENCE_FAMILY_NOT_CITED`。重复引用按**首次出现顺序**去重（不静默重排）。
- 只处理 ASCII 方括号；不做 Markdown/HTML 实体解码，也不做 Unicode 同形字符归一
  （全角 `［K1］`、西里尔字母不算引用意图）。
- 失败**不**修正、**不**补引用、**不**重新调用模型（模型调用次数始终 ≤ 1）。

**必须说清楚的限制**：引用校验只证明**编号来源**（每个引用都能回到本次给出的证据），
它**不证明**模型结论在事实上正确 —— 模型仍然可能「引用了正确的证据却推理错误」。
本阶段没有任何环节能证明研判结论的工程正确性，也没有人工复核环节。

## 提示词与证据白名单

- 系统消息**只有规则**：不出现问题原文、`assetId`、知识正文、资产/监控字段或任何失败详情。
- 用户消息是确定性 JSON，字段顺序固定为 `question`、`availability`、`evidence`：
  - `availability` 只放三类的 outcome 与稳定失败枚举；
  - `evidence` 只放**命中侧**的白名单字段：知识 `citationId`/`documentTitle`/`chunkIndex`/`content`
    （**不发送** documentId、documentVersion、chunkSha256、向量与分数），资产与监控沿用 FD-0017 已批准的白名单；
  - 未命中与失败**不产生** evidence 项。
- 送给模型的问题与送给检索链路的问题**逐字符相同**（同一个 `KnowledgeQueryNormalizer`）。
- JSON 外再包一层服务端生成的边界标记（`<<<FLOWDESK_TRIAGE_DATA_BEGIN>>>`/`..._END>>>`）；
  数据中出现同名标记会被中和为 `[[FLOWDESK_MARKER_NEUTRALIZED]]`，因此最终提示词里两个标记各恰好出现一次。
  结构化隔离与系统规则只**降低**注入风险：真正兜底的是输出侧引用校验，而引用校验也只证明编号来源。
- **不发送**端点、密钥、异常消息、堆栈、MCP 会话信息或 SQL。

## 图内异常到应用层失败的收敛

| 触发 | 图内表现 | 应用层结果 |
| --- | --- | --- |
| `assetId` 非法 | `validate_asset` 抛 `AiRequestException` | **400**（`INVALID_REQUEST`），端口与模型零调用 |
| 检索输入非法（`INVALID_RETRIEVAL_QUERY`） | `retrieve_knowledge` 抛 `AiRequestException`（沿用检索给出文案） | **400**，此时资产、监控与模型都还没被调用 |
| 检索的其它失败 | 知识分支 `FAILED` + 稳定分类，继续执行 | 正常路径；失败如实保留，绝不改写成未命中 |
| 端口返回 `null` 或抛异常 | 记违约，其余证据节点照常执行 | **502**，`failure=PORT_CONTRACT_VIOLATION`，模型零调用 |
| `verify_contracts` 时上下文形状缺失 | `GRAPH_FAILURE` | **502**（服务端缺陷，不伪装成查询状态） |
| 模型调用失败 | `MODEL_CALL_FAILED` | **502** + `requestId`，不重试 |
| 模型返回空/空白 | `ANSWER_EMPTY` | **502** |
| 引用校验失败 | 见引用协议的四类 | **502**，不修正、不重新调用模型 |
| 图返回空状态、路由/执行路径缺失或类型不符 | `GRAPH_FAILURE` | **502**（有界收敛，不出现 `NullPointerException`） |

## 日志与脱敏

每次研判只记录一条结构化日志（成功/失败各一条固定模板）：
`operation`、`requestId`、三个来源状态、`graphRoute`、`modelCalled`、`evidenceCount`、
`usedEvidenceCount`、`success`、`durationMs`；失败时再加稳定 `failure` 与异常**类名**
（本模块的 `IncidentTriageException` 只是分类包装层，因此上报它包住的原始异常类名）。

**没有位置可放**：`assetId`、问题原文、知识正文、资产详情、监控数值、模型答案、提示词、
异常消息、端点、密钥、SQL 与堆栈。日志测试用哨兵先证明这些材料确实进入过结果，
再证明它们没有进入日志。

## 并发隔离

- 编译好的图是**只读**的，装配期一次、之后复用；
- 每次调用新建 `IncidentTriageCall`、新建输入 `Map`、新建 `RunnableConfig`
  （`threadId` 就是本次 `requestId`）；
- 没有 checkpoint 持久化，因此并发调用之间不共享任何状态。

测试用 12 个并发调用（4 线程）断言：`requestId` 互不重复、每次调用的资产证据与答案都属于自己、
`executionPath` 完全一致、三个来源与模型各被调用 12 次。

## 影响

正面：编排形状第一次真的是「有分叉的图」，节点/边/条件路由可审计、可断言；
三个来源的成功与失败在结果与提示词里都保持区分；引用协议与端口契约完全复用 ADR 0014 的成果；
图不依赖 MCP SDK；并发调用零共享状态。

代价：图带来一条必须记住的约束（状态快照会克隆，富对象只能集中放在一个上下文对象里）；
本阶段的拓扑是固定的，改路径要改代码；不重试、不并行、无缓存；
研判质量仍然只由引用校验兜底，不等于事实正确。

## 已知边界与未验证项

1. **没有 HTTP**：本阶段只交付应用层契约 + 图 + 模型生成 + 测试，
   FD-0018-B 才提供端点与状态码语义（可参照 ADR 0014 的 HTTP 契约）；
2. **没有真实 DeepSeek**：自动化测试用本地合成的 OpenAI 兼容端点，
   `LIVE_SMOKE=NOT_RUN`（未对真实模型发起过任何请求）；
3. **没有真实企业数据源**：三个来源在测试里都是替身/合成端点，
   `MCP_LIVE=NOT_RUN`、`POSTGRES_LIVE=NOT_RUN`、`DASHSCOPE_LIVE=NOT_RUN`
   （pgvector 集成测试在无 Docker 时跳过）；
4. **引用校验不等于事实正确**（见上），也没有人工复核；
5. **没有会话记忆、流式输出、工具调用、重试、缓存、并行节点与人工审批**；
6. **知识编号依赖检索顺序**：`K1…Kn` 就是本次检索返回的顺序（重排开启时即重排后的顺序），
   编号只在这一次调用内有意义，不能跨请求比较；
7. **没有逐节点超时**：一次研判的总耗时由模型调用主导，节点级超时留给后续需要时再加；
8. **`FAILED` 的知识分支会让答案不 `grounded`**：此时仍可能基于资产/监控作答（部分命中），
   调用方必须读 `knowledge.status`，不能只看答案。

## 重新评估条件

1. 出现**真正需要更多图能力**的需求时（按资产类型分支、多轮补充查询、并行节点、循环重试），
   在现有拓扑上扩展，并复用本阶段的端口契约与引用协议；
2. 需要跨请求记忆或人工审批时，必须先设计状态持久化与审计，再打开 checkpoint，
   不能顺手复用内存 saver；
3. 需要结论级正确性时，引入人工复核或断言式校验，而不是继续加强引用校验；
4. 需要把远端能力交给模型时，先设计权限与审计，再显式注册工具。
