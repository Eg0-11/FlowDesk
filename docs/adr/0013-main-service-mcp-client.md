# ADR 0013：主服务 MCP 客户端接入（分层、按次会话与固定工具契约）

- 状态：已接受
- 日期：2026-09-21
- 决策范围：主服务如何调用两个独立 MCP 服务（资产 `127.0.0.1:8091/mcp`、
  监控 `127.0.0.1:8092/mcp`）：应用层端口与结果契约、基础设施层的 MCP 适配器、
  按次会话的生命周期与超时/关闭语义、固定工具白名单、载荷校验与错误分类、
  默认关闭的装配方式，以及后续 Agent 复用的方向

## 背景

FD-0014 与 FD-0015 交付了两个独立进程里的 MCP 服务，各自只读、各有一个固定工具。
主服务本阶段要把它们**用起来**：让应用层能拿到「某个资产的资产记录」和「某个资产的监控快照」，
并且明确区分「查到了」「查过了没有」「这次没查成」。

约束（来自任务边界）：

1. 本阶段不接模型、不建 Agent Graph、不新增 HTTP 业务接口，也不把远端工具注册进 `ChatClient`；
2. 不依赖两个 MCP 服务模块（不共享代码、不引入它们的类），也不内嵌 MCP Server；
3. 默认没有 API Key、没有 MCP 服务时，主服务必须照常启动；
4. 不升级 Spring Boot / Spring AI / MCP SDK。

## 决策

1. **端口在应用层，传输在基础设施层**：`flowdesk-application` 定义
   `AssetQueryPort` / `MonitoringSnapshotQueryPort` 两个只读端口、不可变结果对象与失败分类；
   `flowdesk-infrastructure` 用官方 MCP Java SDK 的 Streamable HTTP 客户端实现它们
   （`com.flowdesk.infrastructure.mcp.client`）。应用层不引用 Spring AI、MCP SDK 或两个服务模块的类型。
2. **三态结果**：每个端口返回 `FOUND` / `NOT_FOUND` / `FAILED`（`QueryOutcome`）。
   `NOT_FOUND` 必须带远端给出的 `source`；`FAILED` 必须带稳定分类（`QueryFailure`）。
3. **按次会话**：每次查询独立建立客户端 → `initialize()` → **一次** `tools/call` → 释放会话
   （`closeGracefully()`，会发出 `DELETE /mcp`）。没有连接池、没有后台保活与重连、没有应用级重试。
4. **固定工具白名单**：工具名与入参名写死在适配器里（`asset_get` / `monitoring_snapshot_get`，
   参数只有 `assetId`），调用方无法指定工具名或任意参数对象；本阶段不做 `tools/list`。
5. **默认关闭**：`flowdesk.mcp.client.enabled=false`（默认）时装配 `DISABLED` 适配器 ——
   不创建 SDK 客户端、不绑定 MCP 配置、不连接、不发请求，但端口仍然存在并明确回答 `DISABLED`。
6. **端点只允许回环 HTTP 字面量**：`http` + 完整字面量回环地址（四段 IPv4 `127.0.0.0/8`
   或 IPv6 `::1`）+ 显式端口；拒绝主机名、非回环、userinfo、query、fragment 与自定义路径；
   不跟随重定向。MCP 路径本阶段固定 `/mcp`。
7. **载荷严格校验**：只接受两个服务已公布的固定形状，字段集合必须**完全一致**（多字段即拒绝），
   类型、封闭枚举、数值范围、编号回显一致性、`source` 非空且只能是 `DEMO`/`REAL` 全部校验；
   内容形状（单个 text content、无 `structuredContent`）也校验。
8. **六个稳定失败分类**：`INVALID_INPUT`、`DISABLED`、`TIMEOUT`、`UNAVAILABLE`、`INVALID_RESPONSE`、
   `REMOTE_TOOL_ERROR`。任何失败都**不得**被当作 `NOT_FOUND`。

## 理由

### 为什么端口在应用层、适配器在基础设施层

后续的 Agent 需要的是「资产与监控的结构化事实」，而不是「MCP 客户端」。把端口放在应用层有三个直接好处：
调用方不必知道协议细节；换传输（例如换成 HTTP 直连或本地缓存）不影响用例；
测试可以只针对端口契约，不必起协议栈。代价是适配器里要做一次「远端载荷 → 应用对象」的映射，
这份映射正是本阶段最需要被测试锁定的部分。

### 为什么是「按次会话」，而不是连接池 / 长连接

- **本阶段调用频率极低**：查询由人（或后续 Agent 的单次决策）触发，不是每秒数百次的路径；
- **两个服务是只读、无状态的**：没有「会话内累积状态」需要复用，长连接的收益只有省一次握手；
- **生命周期最简单**：一次调用一轮 `initialize → call → DELETE`，任何一次调用泄漏会话都能被测试数出来
  （计数端点上的 `liveSessions` 必须为空）；
- **故障隔离天然成立**：每次调用独立建连，一个服务不可达不会污染另一个服务的连接状态。

代价如实记录：每次调用多一次握手往返（实测本机 < 10 ms，首次约 200–500 ms）；
没有连接复用与重连；上游若限流，本客户端不会退避重试 —— 重试策略属于调用方（后续 Agent）的决策，
不应该藏在适配器里。

### 超时与关闭语义（实测 SDK 0.17.0）

| 阶段 | 由谁约束 | 本项目的取值 |
| --- | --- | --- |
| `initialize` 握手 | SDK 的 `initializationTimeout`（`LifecycleInitializer` 对该 Mono 施加 `timeout`） | `request-timeout` |
| 单次请求（`tools/call`） | SDK 的 `requestTimeout`（`McpClientSession.sendRequest` 对该 Mono 施加 `timeout`） | `request-timeout` |
| TCP 建连 | 传输层 `connectTimeout`（默认 10 秒） | `request-timeout` |
| 单个 HTTP 请求（含 `DELETE`） | SDK **不设**；本项目通过 `customizeRequest(...timeout(...))` 显式设置 | `request-timeout` |
| 关闭 | SDK 的 `closeGracefully()` 自带 10 秒上限（`DEFAULT_CLOSE_TIMEOUT_MS`） | 实际为 min(`request-timeout`, 10s) |

`request-timeout` 必须是正数且不超过 **30 秒**；配置错误在启动期以固定文案失败，
且文案不回显端点或配置原值。

### 固定工具白名单，而不是 `tools/list` 后动态选择

本阶段两个服务的工具集是**冻结的**（各一个只读工具）。动态列工具会引入「服务公布了别的工具怎么办」
这个新问题，而调用方在本阶段根本没有选择权。因此工具名与参数名写死在适配器里：
调用方只能给 `assetId`，无法表达「调用别的工具」或「传别的参数」。
将来接 Agent 时，如果再出现第三个工具，应当先在这里显式扩白名单，而不是把选择权交给调用方。

### 载荷校验：严格到「字段集合完全一致」

两个服务公布的载荷是固定字段集合，因此客户端要求：

| 情形 | 结果 |
| --- | --- |
| 命中形状（资产 `assetId,assetType,status,source`；监控 7 字段） | 校验类型/枚举/范围/编号一致/`source` 合法 → `FOUND` |
| 未找到形状（`assetId,found,error,message,source`） | `found` 必须为 `false`、`error` 必须是该服务的未找到码、编号必须与请求一致、`source` 必须存在 → `NOT_FOUND` |
| 远端 `isError=true` + `{error,message}` | 错误码是 `*_SOURCE_UNAVAILABLE` → `UNAVAILABLE`；其它码 → `REMOTE_TOOL_ERROR` |
| 远端 `isError=true` 但形状不合法 | `REMOTE_TOOL_ERROR`（远端已声明失败，绝不能被解读成任何「成功」） |
| JSON-RPC 错误（如 `-32601`） | `REMOTE_TOOL_ERROR` |
| 缺字段、多字段、类型不符、未知枚举、数值越界、编号错配、`source` 缺失/为 `null`/未知、非法 JSON、两段 JSON 拼接、非对象、多个 content、非 text content、非空 `structuredContent` | `INVALID_RESPONSE` |
| 连接被拒、5xx、传输层异常、远端声明数据源不可用 | `UNAVAILABLE` |
| 超过上界未收到响应 | `TIMEOUT` |
| 输入不合法（**发出请求之前**） | `INVALID_INPUT`（零请求） |
| 功能关闭 | `DISABLED`（零请求） |

**多字段为什么拒绝**：未知字段意味着远端契约发生了变化（可能是新增字段，也可能是塞进了不该塞的东西）。
静默忽略会让主服务在不知情的情况下继续按旧契约行事 —— 这类漂移越早暴露越好。
**`source` 为什么不能缺失**：来源是这条数据的血缘，缺失或为 `null` 时「查过了没有」这句话没有出处；
资产服务在「未命中且数据源给不出来源」时会写 `"source":null`（FD-0015-R1 修的是监控服务那一侧，
本阶段按边界不改动已验收的资产服务），客户端对它按 `INVALID_RESPONSE` 拒绝，而不是补一个默认值。

### 关闭时不解析配置

关闭状态下连 `base-url` / `request-timeout` 都不绑定、不校验：功能没开时，
一个写错的 URL 不应该阻止主服务启动（关闭状态的「新增失败面」为零）。启用时反过来 ——
任何配置错误都在启动期失败，那时还没有建立任何连接（「启动期只校验配置，首次查询才连接」）。

## 影响

正面：主服务第一次能用真实协议读两个独立服务；调用链、结果三态、失败分类、载荷校验、
端点边界与日志形状都被测试锁定；默认关闭且关闭语义明确（`DISABLED` ≠ `NOT_FOUND`）；
两个服务互相独立；不引入 Web 栈、不内嵌 MCP Server、不注册模型工具。

代价：每次查询一轮握手（无连接复用、无重连、无重试）；载荷契约在客户端**独立实现了一份**校验
（与两个服务的实现各自演进，靠测试而不是共享类保持一致）；严格拒绝未知字段意味着
服务端一旦新增字段，客户端会先失败，需要同步更新客户端。

## 已知边界（如实记录）

1. **没有接入模型与 Agent**：远端工具不会出现在 `ChatClient` 的工具集里，
   也没有任何 HTTP 端点暴露这两个端口（本阶段只交付端口与适配器）；
2. **只有两个固定工具**：不列工具、不支持其它工具、不支持其它参数；
3. **没有真实数据源**：两个服务目前只有演示数据（`source=DEMO`）或明确不可用，
   因此端到端验证覆盖的是演示路径与不可用路径；
4. **没有鉴权**：端点只允许本机回环字面量，因此信任边界是「本机进程」；
5. **没有重试与退避**：远端抖动会直接表现为 `UNAVAILABLE`/`TIMEOUT`，由调用方决定要不要重试；
6. **`source:null` 的资产响应会被拒绝**：这是客户端的有意严格，不是资产服务的缺陷在本阶段的修复；
7. **未验证项**：`MCP_LIVE=NOT_RUN`（未对接任何真实企业资产/监控系统，只对接了本仓库的两个演示服务）、
   `POSTGRES_LIVE`、`DASHSCOPE_LIVE`、`LIVE_SMOKE` 与本任务无关但全仓仍为 `NOT_RUN`。

## 后续复用方向

1. **Agent 直接依赖这两个端口**（而不是 MCP SDK）：Agent 需要的是结构化事实与稳定分类；
2. 若需要把远端能力暴露给模型，应当在 Agent 层显式注册工具，并复用本层的载荷校验与失败分类，
   而不是把 MCP 客户端塞进模型工具链；
3. 若出现第三个 MCP 服务或第三个工具，先把工具白名单集中到一处（例如一个 `McpToolCatalog`），
   再考虑抽象；
4. 若两个服务的载荷契约开始演进，应先把「客户端校验版本」与「服务端载荷版本」绑定起来
   （例如在 `instructions` 或工具描述里带版本），再放宽严格校验。
