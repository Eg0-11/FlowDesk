# ADR 0015：事件研判的多来源证据编排（真实 Agent Graph）

- 状态：已接受
- 日期：2026-09-22
- 决策范围：主服务如何用**真实的** Spring AI Alibaba `StateGraph`/`CompiledGraph` 编排一次
  「事件研判」—— 为什么这一刻才引入 Graph（与 ADR 0014「还不是 Agent Graph」的结论如何衔接）、
  完整拓扑与条件路由、状态键与合并策略、三个证据来源（知识检索 / 资产 / 监控）的成功与失败语义、
  引用协议及其边界、图内异常到应用层失败的收敛、并发隔离；
  以及 FD-0018-B 的 HTTP 契约 —— 端点与开关、状态码语义、响应 DTO 的字段映射规则、
  与既有资产诊断/检索响应体的复用关系，以及 HTTP 阶段与核心阶段的测试边界

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
本阶段**不**实现 HTTP 接口（那是 FD-0018-B，**已交付，见下**），**不**改动 FD-0016 的 MCP 客户端与两个 MCP 服务，
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
7. **「输入失败 → 400」的来源边界（R1 修正）**：输入校验失败只由这两个输入节点产生，
   并且必须**记在调用上下文里**（`IncidentTriageCall.rejectInput`）；
   服务层只认这条记录，**不**沿异常 cause 链搜索 `AiRequestException` ——
   模型阶段抛出的任何（直接或间接包含）`AiRequestException` 的异常都必须保持
   `MODEL_CALL_FAILED`，对外是携带 `requestId`、固定文案的 `AiProviderException`。
8. **知识失败分两类（R1 修正）**：**已声明**的业务失败收敛为知识分支的稳定分类，绝不改写成「没查到」：
   `KNOWLEDGE_EMBEDDING_DISABLED → DISABLED`、`EMBEDDING_PROVIDER_ERROR → EMBEDDING_PROVIDER_UNAVAILABLE`、
   `RERANK_PROVIDER_ERROR → RERANK_PROVIDER_UNAVAILABLE`、`KNOWLEDGE_RETRIEVAL_FAILURE → RETRIEVAL_FAILURE`；
   而**未声明**的运行期异常与 `null` 结果属于**端口违约**（端口只允许返回视图或抛
   `KnowledgeApplicationException`），不与「知识检索失败」混为一谈。
9. **端口违约有自己的出口**：知识、资产、监控任一侧返回 `null` 或抛出未声明的异常都记为
   「端口契约违约」，**其余证据节点照常执行**（各一次），但在 `verify_contracts` 之后路由到
   `contract_violation` 节点，在调用模型之前整次失败（502 + `requestId`），
   不补成 `NOT_FOUND`、不伪造 `FAILED`、不让 `NullPointerException` 穿透。
10. **应用层契约先于编排落地**：新增 `IncidentTriageCommand`、`IncidentTriageUseCase`、
    `IncidentTriageResult`、`KnowledgeEvidence`、`KnowledgeFailure`；不变量由记录的紧凑构造器强制。
11. **引用协议**：知识沿用检索给出的 `K1…Kn`，资产固定 `A1`，监控固定 `M1`；
    输出侧独立校验，失败即整次失败，**不修正、不补引用、不重新调用模型**。
    **嵌套方括号**（`[[A1]]`、`[[K1]]`、`[ [M1] ]`）一律判畸形，**不得**忽略外层括号后
    从内层提取成功编号（R1 修正）。
12. **异常边界**：图内只抛稳定分类的 `IncidentTriageException`；
    服务层把它收敛成 `AiProviderException`（502 + `requestId`，固定文案、不含 cause），
    把**输入节点记录的** `AiRequestException` 原样上抛（400）。
13. **失败路径的审计信息来自真实执行（R1 修正）**：三个来源状态、`graphRoute`、`evidenceCount`
    取自调用上下文里已经发生的进度；`modelCalled` 在**发起模型调用之前**置位（不是调用成功后更新，
    也不是按计划路由推测）；未执行到的部分才写成 `NOT_QUERIED`/`none`；
    引用校验失败时 `usedEvidenceCount` 必然是 0（不伪造一个「已通过校验」的数量）。
14. **日志固定两条模板**，只记录元数据与异常**类名**；业务数据没有任何位置可放（见下）。
15. **HTTP 只做 DTO 转换（FD-0018-B）**：`IncidentTriageController` 只依赖
    `IncidentTriageUseCase`，不注入 Graph、`ChatClient`、三个证据来源端口、数据库与 MCP 客户端；
    不复制任何输入校验（不 trim、不规范化、不补默认值），也不声明任何异常处理器 ——
    `AiRequestException` → 400、`AiProviderException` → 502 由全局 `AiExceptionHandler` 统一给出。
16. **响应按状态决定字段集合（FD-0018-B）**：`knowledge` 的 `FOUND`/`NOT_FOUND` 输出
    `status` + `retrieval`，`FAILED` 只输出 `status` + `failure`（**不输出 `retrieval`**，
    不伪造一次空的检索成功）；`asset`/`monitoring` 沿用资产诊断已验收的三态字段集合；
    取值为 `null` 的字段直接省略。任何状态都**不**把 `FAILED`/`DISABLED` 写成 `NOT_FOUND`。
17. **能复用就复用（FD-0018-B）**：`retrieval` 直接复用检索接口的响应体
    `KnowledgeSearchResponse`（含 `citations` 的 `KnowledgeCitationResponse` 白名单映射），
    资产/监控两侧直接复用 `AssetDiagnosisAssetResponse`/`AssetDiagnosisMonitoringResponse`。
    字段契约因此只有一处定义，不会出现「同一份检索参数在两处各自映射」的漂移。
18. **映射不做二次加工（FD-0018-B）**：`requestId` 与 `grounded` 直接取自用例结果
    （不重新生成、不重新推断），`usedEvidenceIds` 与 `executionPath` 保持用例给出的顺序，
    并在响应构造时再做一次防御性复制。
19. **`executionPath` 是公开的审计事实（FD-0018-B）**：它是节点**真实执行**过的序列，
    调用方据此可以区分「走了模型」与「直接降级」两条路径；它随分支变化，
    因此**不是**稳定契约（见「已知边界」）。
20. **状态码语义（FD-0018-B）**：所有可审计结果（完整 / 部分 / 三个来源全 `NOT_FOUND` /
    无命中且存在 `FAILED`）都是 **200** —— 包括三个依赖都没成功的情形；
    输入非法 400，模型/引用/图失败与端口违约 502（携带 `requestId`），AI 关闭 404。
    **200 只表示编排正常完成，不表示依赖成功**。

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
| `validate_asset` | 用既有 `AssetIdentifier` 校验 `assetId` | 非法即把输入失败记进调用上下文并抛出，后续节点与模型零调用 |
| `retrieve_knowledge` | 调用检索用例一次 | 输入非法 → 记录输入失败（400）；**已声明**失败 → 知识分支 `FAILED` 并**继续**；未声明异常/`null` → 端口违约 |
| `query_asset` | 调用资产端口一次 | 不重试、不缓存；`null`/异常记为端口违约但**继续** |
| `query_monitoring` | 调用监控端口一次 | 同上；即使资产侧违约也照常执行 |
| `verify_contracts` | 有违约 → 路由 `contract_violation`；否则校验本次上下文形状并算出闸门路由 | 形状缺失 → `GRAPH_FAILURE`；路由同时写进状态与调用上下文 |
| `evidence_gate` | **条件边**的源节点（真实的过路节点） | 三个出口全部由 `route` 决定 |
| `generate_answer` | 构造提示词、调用 DeepSeek **一次** | 调用前置位 `modelCalled`；模型异常 → `MODEL_CALL_FAILED`；空答案 → `ANSWER_EMPTY` |
| `validate_citations` | 独立校验引用 | 失败即整次失败，不重试、不修正 |
| `fallback_answer` | 两条固定降级文案 | **不**调用模型 |
| `contract_violation` | 违约终止节点 | **不**调用模型；服务统一抛稳定失败 |
| `finish` | 正常终点 | —— |

## 状态键与合并策略

| key | 类型 | 合并策略 | 写入者 |
| --- | --- | --- | --- |
| `call` | `IncidentTriageCall` | **REPLACE** | 调用方创建（输入）；各节点在执行中写入自己的产物与执行进度 |
| `route` | `String` | **REPLACE** | `verify_contracts` |
| `executionPath` | `List<String>` | **APPEND** | 每个节点各自追加自己的节点名 |

三个键、类型与策略只在 `IncidentTriageStateKeys` 里声明一次，节点与服务都引用常量 ——
散落的魔法字符串会让「谁写了这个键、用什么策略合并」无法审计。
`executionPath` 是唯一的 APPEND 键，因此 `IncidentTriageResult.executionPath` 是**真实执行**的轨迹，
不是结束后拼出来的。

`call` 里除了三个证据结果与答案，还携带**执行进度**（R1 补充）：实际路由、是否已经发起过模型调用、
以及输入校验失败（如果有）。原因见「失败路径的审计信息」一节：图内抛异常时框架不交出最终状态，
失败日志只能从调用上下文读真实进度。

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
| 任一来源返回 `null` 或抛出**未声明**的运行期异常 | 不调用 | `contract_violation` | 502 + `requestId`，`failure=PORT_CONTRACT_VIOLATION` |

两条「没有证据」的文案必须分开：**「都没查到」是可以接受的事实**，而
**「有来源没查成」意味着这次研判不完整**，调用方可能需要重试或升级。
把两者合并成一句「没有数据」，正是本仓库一路在防的错误结论。

**部分命中**走第一条路径：允许基于可用证据作答，但 `availability` 会如实写出三类来源的状态，
结果对象里也保留失败侧的分类 —— 模型只知道「这一类没有证据」，不该知道也不需要知道失败细节。

## 三个来源的失败语义

| 来源 | 成功 | 未命中 | 失败 |
| --- | --- | --- | --- |
| 知识（检索用例） | `FOUND` + 非空检索视图（可引用 `K1…Kn`） | `NOT_FOUND` + 空检索视图（「查过了没有」也能审计） | **已声明**的 `KnowledgeApplicationException` → `FAILED` + 稳定 `KnowledgeFailure`，**没有**检索视图；**未声明**的运行期异常或 `null` → **端口违约**（不是知识失败） |
| 资产（`AssetQueryPort`） | `FOUND`（可引用 `A1`） | `NOT_FOUND` | `FAILED` + `QueryFailure`；`null`/异常 → **端口违约** |
| 监控（`MonitoringSnapshotQueryPort`） | `FOUND`（可引用 `M1`） | `NOT_FOUND` | `FAILED` + `QueryFailure`；`null`/异常 → **端口违约** |

三态互斥且携带的数据形状固定（由记录的构造器强制）：`FAILED` 不可能携带视图或结果，
否则「没查成」就有了看起来正常的证据。**`FAILED`/`DISABLED` 永远不会出现在 `NOT_FOUND` 的位置上。**

**「已声明失败」与「端口违约」必须分开**（R1 修正）：检索用例的契约是「返回视图，或抛
`KnowledgeApplicationException`」。因此：

- 抛**已声明**异常 → 这是**业务失败**，如实收敛为该分支的 `FAILED` + 稳定分类，
  其余来源照常用作证据（部分命中）；
- 抛**未声明**的运行期异常、或返回 `null` → 这是**实现违约**：写成 `FAILED` 会让一次
  **没查成的**检索看起来像「查过了、失败了」，还会让研判继续基于不完整输入给出 `grounded=true` 的结论。
  按违约处理：后续证据节点照常执行，但在调用模型之前整次失败。


## 引用协议：保证什么、不保证什么

- 编号固定：`A1` = 资产记录、`M1` = 监控快照；知识沿用检索最终顺序给出的 `K1…Kn`。
- 模型答案里的引用被独立校验（`IncidentTriageCitationValidator`）：
  空答案 → `ANSWER_EMPTY`；一条引用都没有 → `ANSWER_WITHOUT_CITATION`；
  畸形引用（`[k1]`、`[K01]`、`[K 1]`、`[K1x]`、`[K1` 未闭合、全角数字、
  **嵌套方括号 `[[A1]]`/`[[K1]]`/`[ [M1] ]`** 等）→ `INVALID_CITATION_FORMAT`；
  引用本次并不存在的编号 → `UNKNOWN_CITATION`；本次有证据的某一类完全没有被引用 →
  `EVIDENCE_FAMILY_NOT_CITED`。重复引用按**首次出现顺序**去重（不静默重排）。
- **嵌套方括号必须整体失败**（R1 修正）：校验器按「成对方括号组」扫描（先求配对的右括号，
  组内再出现方括号即为嵌套）。此前逐左括号扫描的写法会**忽略外层、把内层编号当成合法引用提取**，
  于是一条畸形答案也能通过校验；现在只要嵌套结构里出现引用意图，整条答案即
  `INVALID_CITATION_FORMAT`，**不**从畸形结构里提取任何编号。
- 只处理 ASCII 方括号；不做 Markdown/HTML 实体解码，也不做 Unicode 同形字符归一
  （全角 `［K1］`、西里尔字母不算引用意图）。
- 普通英文方括号词（`[API]`、`[Known]`）按普通文本忽略；被再包一层但没有引用意图的
  （`[[API]]`）同样忽略，而括号之外的孤立 `]`（`[K1]]` 的第二个右括号）也按普通文本处理 ——
  只有**方括号组内部**参与解释。
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
| `assetId` 非法 | `validate_asset` 记录输入失败并抛 `AiRequestException` | **400**（`INVALID_REQUEST`），端口与模型零调用 |
| 检索输入非法（`INVALID_RETRIEVAL_QUERY`） | `retrieve_knowledge` 记录输入失败并抛 `AiRequestException`（沿用检索给出文案） | **400**，此时资产、监控与模型都还没被调用 |
| 检索的**已声明**业务失败 | 知识分支 `FAILED` + 稳定分类，继续执行 | 正常路径；失败如实保留，绝不改写成未命中 |
| 检索抛出**未声明**异常或返回 `null` | 记违约，其余证据节点照常执行 | **502**，`failure=PORT_CONTRACT_VIOLATION`，模型零调用 |
| 资产/监控端口返回 `null` 或抛异常 | 记违约，其余证据节点照常执行 | **502**，`failure=PORT_CONTRACT_VIOLATION`，模型零调用 |
| `verify_contracts` 时上下文形状缺失 | `GRAPH_FAILURE` | **502**（服务端缺陷，不伪装成查询状态） |
| 模型调用失败（含直接/间接包含 `AiRequestException` 的异常） | `MODEL_CALL_FAILED` | **502** + `requestId`，不重试，**不**降级成 400 |
| 模型返回空/空白 | `ANSWER_EMPTY` | **502** |
| 引用校验失败（含嵌套方括号） | 见引用协议的五类 | **502**，不修正、不重新调用模型 |
| 图返回空状态、路由/执行路径缺失或类型不符 | `GRAPH_FAILURE` | **502**（有界收敛，不出现 `NullPointerException`） |

### 为什么输入失败不能靠异常链识别（R1 修正）

第一版实现里，服务层在整条 cause 链上搜索 `AiRequestException`：只要链上任何一层是输入异常，
就原样上抛（400）。这看起来「宽容」，实际上把**异常类型当成了来源证明**：

- 模型调用阶段抛出的 `AiRequestException`（供应商、SDK 或适配层完全可能抛出这个类型），
  会被当成「用户输入不合法」，于是**用户的合法请求**得到 400，模型故障被伪装成参数问题；
- 更糟的是它会**覆盖**外层已经确定的分类 —— `generate_answer` 明明已经把它包装成
  `MODEL_CALL_FAILED`，却因为链上存在一个输入异常而被降级成 400。

因此「输入失败 → 400」必须由**来源**决定，而不是由**类型**决定：只有
`validate_asset` 与 `retrieve_knowledge` 这两个输入校验节点会在调用上下文里留下输入失败记录，
服务层只认这条记录（`IncidentTriageCall.rejectInput`）。模型阶段抛出的任何异常
（包括直接或间接包含 `AiRequestException` 的异常）都保持 `MODEL_CALL_FAILED`，
对外是携带 `requestId`、固定文案的 `AiProviderException`。

### 失败路径的审计信息必须来自真实执行（R1 修正）

第一版在失败时硬编码 `NOT_QUERIED ×3`、`graphRoute=none`、`modelCalled=false`、`evidenceCount=0`。
后果是失败日志**与真实执行不符**：完整证据之后的模型失败被记成「什么都没查」，
引用校验失败也看不出模型确实被调用过一次、证据确实有三条。

原因是图内抛异常时框架**不会交出最终状态**，于是实现偷懒按「计划形状」写死。修正办法是把执行进度
写进调用上下文（服务层始终持有同一个实例）：

| 字段 | 何时写入 | 失败时的语义 |
| --- | --- | --- |
| 三个来源状态 | 各自的证据节点执行时（**调用之前**先记「已开始查询」） | 已经查过的如实记录；只有**真的没调用**才写 `NOT_QUERIED`；调用了但没有合法结论写 `PORT_CONTRACT_VIOLATION` |
| `route` | `verify_contracts` 算出路由时（同时写状态与上下文） | 真实路由；没走到这一步才是 `none` |
| `modelCalled` | **发起模型调用之前**置位 | 空答案、模型异常、引用校验失败都必须是 `true`；不按路由推测 |
| `usedEvidenceCount` | 引用校验通过后写入 | 校验失败时必然为 0 —— 不伪造一个「已通过校验」的数量 |
| `evidenceCount` | 由已知的三个来源实时算出 | 记录本次**真实存在**的证据数量 |

这**不**扩大日志面：仍然只有那两类固定模板与固定参数位，仍然不记录 assetId、问题原文、
知识正文、资产详情、监控数值、模型答案、提示词与异常消息。

### 来源状态由「查询进度」决定，而不是由「结果是不是 null」决定（R2）

第一版用 `结果 == null → NOT_QUERIED` 推断来源状态。这在两种真实情况下直接说谎：
端口**返回 `null`**、端口**抛出未声明的异常**（包括检索用例抛运行时异常）时，查询明明已经执行了。
更糟的是 `IncidentTriageLoggingTests` 当时把这个错误行为**断言**了下来。

修正：每个来源在**真正被调用之前**把「该来源已开始查询」写进调用上下文
（`IncidentTriageCall.markQueryStarted`），日志据此映射：

| 查询进度 | 日志取值 | 含义 |
| --- | --- | --- |
| `NOT_QUERIED` | `NOT_QUERIED` | 这个来源**从来没有被调用**（例如前面的输入校验就失败了） |
| `QUERIED` | 结果自身的 `FOUND`/`NOT_FOUND`/`FAILED` | 已调用并取得合法结论 |
| `CONTRACT_VIOLATION` | `PORT_CONTRACT_VIOLATION` | 已调用，但返回 `null` 或抛出未声明的异常 |
| `INPUT_REJECTED` | `INVALID_INPUT` | 已调用，但输入在它内部被拒绝（`INVALID_RETRIEVAL_QUERY`） |

这四种进度是**内部审计状态**：不修改 `QueryOutcome`、`KnowledgeEvidence` 或任何公开业务错误码，
也不伪造失败结果 —— 违约的来源在结果对象里仍然「没有结果」，只是日志不再谎称它「没查过」。
`QUERIED` 却没有结果属于不可能的组合；万一出现，日志按 `PORT_CONTRACT_VIOLATION` 记录
（宁可按「没有合法结论」记，也不谎报「未查询」）。

## 日志与脱敏

每次研判只记录一条结构化日志（成功/失败各一条固定模板）：
`operation`、`requestId`、三个来源状态、`graphRoute`、`modelCalled`、`evidenceCount`、
`usedEvidenceCount`、`success`、`durationMs`；失败时再加稳定 `failure` 与异常**类名**
（本模块的 `IncidentTriageException` 只是分类包装层，因此上报它包住的原始异常类名）。

这些字段全部取自调用上下文里**已经发生**的执行进度（见上）：失败不是「按计划形状」补出来的日志，
而是这次调用真实走到了哪一步的记录。故障排查时读法很直接：某个来源显示 `NOT_QUERIED`
就是**没有调用过**，显示 `PORT_CONTRACT_VIOLATION` 就是**调用了但没有合法结论**（R2）。

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

## HTTP 契约：状态码与 DTO 映射（FD-0018-B）

端点 `POST /api/v1/ai/incident-triage`，`Content-Type` 与 `Accept` 均为 `application/json`，
只在 `flowdesk.ai.enabled=true` 时注册（默认关闭 → 404）。请求体四个字段
（`assetId`、`question`、`topK`、`minScore`）**不在 HTTP 层校验**：
`assetId` 的规则只存在于 `AssetIdentifier`（由图的 `validate_asset` 调用），
`question`/`topK`/`minScore` 的规则只存在于检索用例。空 body 映射为「四个字段全为 `null`」的命令，
与 `{}` 得到同一条 400。

### 状态码

| 情形 | 状态码 | 说明 |
| --- | --- | --- |
| 完整证据 / 部分证据 / 三个来源全 `NOT_FOUND` / 无命中且存在 `FAILED` | **200** | 可审计结果；数据状态由三个来源表达 |
| 非法 `assetId`（含空 body、`{}`、`null`、空白、位数或大小写不符） | 400 `INVALID_REQUEST` | 三个证据来源与模型**零调用** |
| 非法 `question`/`topK`/`minScore`（由**真实检索用例**判定） | 400 `INVALID_REQUEST` | detail 原样透传检索用例的文案；Embedding / 数据库 / 两个 MCP 端口 / 模型**零调用** |
| 请求体不是合法 JSON | 400 `INVALID_REQUEST` | 全局 JSON 契约，本接口不改动未知字段/类型转换/重复字段规则 |
| 模型失败、空答案、引用校验失败、图执行失败、端口违约 | 502 `AI_PROVIDER_ERROR` | 携带 `requestId`，detail 固定、不含 cause，**不回显模型答案** |
| `Content-Type` / `Accept` 不受支持 | 415 / 406 | 全局媒体类型契约 |
| AI 未启用 | 404 | 端点不注册 |

### DTO 映射

| JSON 字段 | 来源 | 规则 |
| --- | --- | --- |
| `requestId`、`answer`、`grounded` | `IncidentTriageResult` | **原样**，不重新生成、不重新推断 |
| `usedEvidenceIds` | `IncidentTriageResult` | 保持用例给出的**首次出现顺序**，防御性复制 |
| `executionPath` | `IncidentTriageResult` | 保持节点**真实执行**顺序，防御性复制 |
| `knowledge.status` | `KnowledgeEvidence.status()` | `FOUND`/`NOT_FOUND`/`FAILED` |
| `knowledge.retrieval` | `KnowledgeRetrievalView` | **复用** `KnowledgeSearchResponse`；仅 `FOUND`/`NOT_FOUND` |
| `knowledge.failure` | `KnowledgeFailure` | 稳定枚举名；仅 `FAILED`；此时**没有** `retrieval` |
| `asset` / `monitoring` | 两个查询结果 | **复用**资产诊断的 `AssetDiagnosis*Response`：`FOUND` 给白名单详情 + `source`，`NOT_FOUND` 只给编号 + `source`，`FAILED` 只给 `failure` |

`null` 字段由 `NON_NULL` 省略；监控数值使用包装类型，因此未命中时**省略**而不是序列化成 `0`。

### 为什么保留「真实执行轨迹」与「三个来源的真实状态」

- **`executionPath` 让「走了模型」与「直接降级」可区分**：`fallback_answer` 结尾说明本次没有
  调用模型，`generate_answer → validate_citations` 说明走了模型且引用通过了校验。
  它由节点在执行时追加（见「状态键与合并策略」），事后无法伪造；但它随分支变化，
  因此**不是**稳定契约，调用方不该按它做逻辑分支。
- **三个来源状态必须一起返回**：`grounded=true` 只说明「答案用了本次给出的证据」，
  不等于三个依赖都成功。部分命中（知识 `FAILED`、资产与监控命中）时答案依然 grounded，
  而那次研判是**不完整的** —— 调用方只能通过 `knowledge.status` 与两个 `outcome` 判断。
- **200 不等于依赖成功**：三个来源全 `NOT_FOUND`（「都没查到」）与「无命中且至少一个失败」
  （「有来源没查成」）是**两个不同的结论**，也是两条不同的固定文案；
  它们的 HTTP 状态码都是 200，区别只在结果内容里。

### 为什么用明确的 DTO 而不是直接序列化内部对象

Graph 状态（`OverAllState`）、调用上下文（`IncidentTriageCall`）、来源查询进度与输入拒绝记录
都是**内部审计状态**：它们不修改任何公开业务错误码，也不属于对外契约。
直接序列化会让「内部实现细节」变成事实上的公开字段，并在下次重构时被动破坏兼容性。
因此响应由 HTTP 层显式构造，`question` 原文、向量、SQL、异常与 cause、端点、会话标识与凭证
都没有位置可放。

### 这一层的测试边界（FD-0018-B）

- **映射层**：`@WebMvcTest` + `IncidentTriageUseCase` 替身，证明响应形状、字段集合、
  顺序保持与状态码；替身按真实规则拒绝非法输入，因此「非法输入 400」仍由应用层规则决定。
- **关闭装配**：真实上下文证明端点 404 且上下文里没有任何模型基础设施。
- **真实接入**：真实 Spring 上下文 + MockMvc + **真实 `IncidentTriageService`/`CompiledGraph`/
  `KnowledgeRetrievalService`/`ChatClient`**，只替换四个**出站端口**；
  「某分支零调用」用**计数 + 正向对照**证明（降级路径与命中路径都断言过这些替身确实被调用过），
  而不是靠一个「永远不调用」的替身宣称。
- **仍然是合成的**：模型端是本机合成 OpenAI 兼容端点（**不是** DeepSeek），
  向量与切片来自替身（**不是** DashScope 与 pgvector），资产/监控不经过真实 MCP 服务。
  这些测试**不**改变 `LIVE_SMOKE`/`MCP_LIVE`/`POSTGRES_LIVE`/`DASHSCOPE_LIVE` 的 `NOT_RUN` 状态。

## 影响

正面：编排形状第一次真的是「有分叉的图」，节点/边/条件路由可审计、可断言；
三个来源的成功与失败在结果与提示词里都保持区分；引用协议与端口契约完全复用 ADR 0014 的成果；
图不依赖 MCP SDK；并发调用零共享状态。

代价：图带来一条必须记住的约束（状态快照会克隆，富对象只能集中放在一个上下文对象里）；
本阶段的拓扑是固定的，改路径要改代码；不重试、不并行、无缓存；
研判质量仍然只由引用校验兜底，不等于事实正确。

## 已知边界与未验证项

1. **HTTP 由 FD-0018-B 交付且没有鉴权**：FD-0018-A 只交付应用层契约 + 图 + 模型生成 + 测试；
   FD-0018-B 提供端点与状态码语义（见「HTTP 契约：状态码与 DTO 映射（FD-0018-B）」），
   但接口没有身份与权限概念 —— 能调用就能拿到该资产与知识库的研判结果；
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
   调用方必须读 `knowledge.status`，不能只看答案；
9. **「已声明失败」与「端口违约」的区分依赖检索用例的契约**：本阶段以
   `KnowledgeApplicationException` 作为唯一「已声明」的信号，未声明的异常一律按违约处理；
   如果后续给该用例增加新的异常类型，必须同步更新这条边界（否则新的业务失败会被当成违约）；
10. **括号之外多余的 `]` 仍按普通文本处理**：只有方括号组**内部**参与解释，
    `[K1]]` 的第二个右括号不算引用结构（嵌套是「组内有方括号」，不是「括号数不配平」）；
11. **`executionPath` 不是稳定契约（FD-0018-B）**：它是本次调用的真实轨迹（随分支变化），
    调用方可以用它做审计与排障，但不该按它做逻辑分支 —— 拓扑改变时它就会变；
12. **`grounded=true` 不等于研判完整（FD-0018-B）**：部分命中时答案仍然 grounded，
    调用方必须同时读 `knowledge.status` 与两个 `outcome` 才能判断这次研判是否完整。

## 重新评估条件

1. 出现**真正需要更多图能力**的需求时（按资产类型分支、多轮补充查询、并行节点、循环重试），
   在现有拓扑上扩展，并复用本阶段的端口契约与引用协议；
2. 需要跨请求记忆或人工审批时，必须先设计状态持久化与审计，再打开 checkpoint，
   不能顺手复用内存 saver；
3. 需要结论级正确性时，引入人工复核或断言式校验，而不是继续加强引用校验；
4. 需要把远端能力交给模型时，先设计权限与审计，再显式注册工具。
