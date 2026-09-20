# ADR 0011：独立资产 MCP 服务的协议与只读查询工具

- 状态：已接受
- 日期：2026-09-16
- 决策范围：`flowdesk-mcp-asset` 的 MCP 协议接入（Streamable HTTP）、`asset_get` 只读工具的
  输入/输出契约、演示数据与真实数据的边界、监听地址与跨源安全限制

## 背景

`flowdesk-mcp-asset` 此前只是一个「有健康检查的 Web 骨架」：能启动、能被探活，
但**不能被任何 MCP 客户端连接**，也没有任何工具。本任务把它变成真正可用的独立 MCP 服务，
范围限定为**协议 + 工具契约 + 安全边界**。

具体问题：

1. 用哪个 MCP Server starter？哪个协议？端点与端口是什么？
2. 工具输入怎么约束？工具怎么注册才不会「顺手」多注册出写能力？
3. 工具协议与资产查询逻辑怎么分层？
4. 没有真实资产系统时，服务应该返回什么？
5. 「资产不存在」与「工具执行失败」在 MCP 协议里怎么区分？
6. 服务监听在哪？浏览器能不能调用？
7. 本任务**不**做什么？

本任务**不做**：接入主 Agent 或 DeepSeek（不把 MCP 工具全局注册给 ChatClient，也不让 RAG 问答
自动调用资产工具）、实现真实企业资产数据源、鉴权/授权、写工具、SSE 传输、多工具编排。

## 决策

1. **使用 Spring AI 1.1.2 的 `spring-ai-starter-mcp-server-webmvc`**（版本由既有 BOM 管理，
   不升级任何依赖），协议为 **STREAMABLE**（Streamable HTTP），端点为 **`/mcp`**，
   端口仍为 **8091**，服务器类型 `SYNC`；
2. **只注册一个工具 `asset_get`**：唯一入参 `assetId`，格式固定 `AST-[0-9]{6}`；工具只读，
   没有新增、修改、删除能力（连底层端口都没有写方法）；
3. **协议适配与查询逻辑分离**：`AssetDirectory` 端口负责「查一条资产」，
   `AssetGetTool`（Spring AI `ToolCallback`）负责输入校验、错误码与载荷形状；
4. **默认没有数据源**：`flowdesk.asset.directory.mode=unavailable`（默认）时，
   每次查询都以稳定的 `ASSET_SOURCE_UNAVAILABLE` 失败；只有显式设为 `demo` 才使用内置虚构数据，
   且所有结果都带 `source=DEMO`；
5. **成功与失败的内容形状统一为 JSON**：成功 `isError=false`，失败 `isError=true`；
   错误码只有 `INVALID_ASSET_ID` 与 `ASSET_SOURCE_UNAVAILABLE` 两个，
   `ASSET_NOT_FOUND` **不是**错误（见下）；
6. **只监听本机回环**：`server.address` 必须是无歧义的回环字面量，否则在
   **Web 服务器创建之前**就拒绝启动；
7. **带 `Origin` 的请求一律 403**：不提供 CORS，也不把端点暴露给浏览器。

## 理由

### 为什么是 WebMVC + Streamable HTTP

- 本模块本来就是 Servlet 栈（`spring-boot-starter-web`）。选 WebFlux 变体会让同一个进程里
  同时存在两套 Web 运行时；
- Streamable HTTP 是当前 MCP 的主推传输（可被 IDE、CLI、桌面客户端直接连接），
  比已进入维护期的 SSE 更合适；
- 端点固定为 `/mcp` 并写进配置（`spring.ai.mcp.server.streamable-http.mcp-endpoint`），
  不依赖框架默认值 —— 端口与路径都是对外契约的一部分。

### 为什么只注册一个工具、并且是只读的

- **能力面越小越好**：这是一个独立进程，暴露给的是「本机上的 MCP 客户端」。
  只读查询已经能满足「查资产状态」这一件事，写能力（新增/修改/删除）会立刻带来
  鉴权、审计、幂等、回滚等一整套要求，而本阶段明确不做鉴权；
- **「注册几个工具」必须是可数的**：工具通过 Spring AI `ToolCallback` Bean 注册
  （`ToolCallbackConverterAutoConfiguration.syncTools` 会把容器里的所有 `ToolCallback`
  转成 MCP 工具规格），因此本模块**只有一个** `ToolCallback` Bean；
- 回归测试同时断言两件事：容器里恰好一个 `ToolCallback`，且 **MCP 服务器自己的工具表**
  里恰好一个 `asset_get` —— 只断言 Bean 存在无法排除「Bean 有但没挂到服务器上」。

### 为什么分层：端口 + 工具

- **工具层不认识数据从哪来**：它只认识 `AssetDirectory`（返回一条记录或「没有」），
  因此换数据源不需要动协议代码；
- **目录层不认识 MCP**：`AssetDirectory` 是纯 Java 接口，没有任何协议、注解或 JSON 概念；
- **输入校验只有一处**：`AssetId` 负责格式，工具层负责把结论映射成错误码。

### 为什么默认没有数据源，而不是「返回空结果」

- `Optional.empty()` 的含义是「**查过了，这个资产不存在**」。在没有数据源的情况下给出这个答案，
  等于把「我们没有数据」说成「这个资产不存在」—— 调用方无法区分，会把一次能力缺失当成业务结论；
- 因此默认装配的是「每次查询都明确失败」的实现，返回稳定的 `ASSET_SOURCE_UNAVAILABLE`；
- 演示数据必须**显式**打开。默认安全、显式开启，比「默认假装有数据」更难出错。

### DEMO 与真实数据的边界

- 演示目录只提供三条固定的虚构资产（`AST-900001`~`AST-900003`，`AST-9xxxxx` 是明显的保留段），
  每条都带 `source=DEMO`；
- **成功结果必须带 `source`**：调用方一眼就能看出这不是真实企业资产。
  「未找到」也带 `source`（问的是哪个目录），因为「演示目录说没有」与「真实资产系统说没有」
  是完全不同的两件事 —— 为此端口上有一个只读的 `source()` 方法；
- 没有真实数据源时**不产生任何 `source` 声明**（错误载荷里没有 `source` 字段），
  因为那时没有任何来源可以背书。

### 「未找到」为什么不是错误

MCP 的 `isError` 表达的是「这次**调用**有没有失败」，不是「查询结果好不好」：

| 情形 | `isError` | 内容 |
| --- | --- | --- |
| 命中 | `false` | `{"assetId":"AST-900001","assetType":"SERVER","status":"IN_SERVICE","source":"DEMO"}` |
| 合法但不存在 | `false` | `{"assetId":"AST-999999","found":false,"error":"ASSET_NOT_FOUND","message":"未找到该资产","source":"DEMO"}` |
| 输入非法 | `true` | `{"error":"INVALID_ASSET_ID","message":"assetId 必须形如 AST-000001（AST- 加 6 位数字）"}` |
| 数据源不可用 | `true` | `{"error":"ASSET_SOURCE_UNAVAILABLE","message":"资产数据源当前不可用"}` |

- 资产不存在是查询正常给出的答案（与检索接口「无命中返回 200 空列表」同一条原则）。
  把它标成错误会让调用方以为工具坏了，从而去重试或降级；
- **真正的执行失败**（输入非法、数据源不可用或内部异常）才置 `isError=true`；
- 错误内容里的文案是**固定字符串**，来自 `AssetToolError` 枚举：不含路径、配置、凭据、
  异常消息或上游原文。内部异常一律收敛为 `ASSET_SOURCE_UNAVAILABLE`，
  日志里也只出现异常**类名**（不出现消息与堆栈）。

### 为什么「错误内容」用异常消息承载

Spring AI 的 MCP 桥接层（`McpToolUtils`）在工具抛出异常时，会把
`exception.getMessage()` 原样放进 `CallToolResult` 的文本内容，并把 `isError` 置为 `true`。
因此这里的消息**不是**诊断文本，而是一段固定、安全、可解析的 JSON 载荷
（`AssetToolException` 的 `getMessage()` 就是它）。这样成功与失败的内容形状一致，
调用方可以统一解析；而「异常消息泄漏内部细节」这个常见风险被结构性排除：
消息只来自枚举常量，不拼接任何输入或内部状态。

### 监听地址：必须是回环字面量，而且在最早就拒绝

- 服务只面向本机：`server.address` 必须是**完整的四段十进制 IPv4 回环字面量**
  （`127.0.0.0/8`）或 IPv6 回环 `::1` 的完整写法；
- **不接受主机名**（包括 `localhost`）：绑定到哪取决于 hosts/DNS，而那是可以被指向任何地方的；
  也拒绝 `0.0.0.0`、`::`、具体外网地址与含糊写法（`0127.0.0.1`、`127.5`、`127.example.com` 等）；
- **必须在创建 Web 服务器之前校验**：Spring Boot 的刷新顺序是
  「环境准备 → … → `onRefresh()`（**此刻创建并绑定 Web 服务器**）→ 单例 Bean 实例化」。
  如果只写成普通的装配期校验 Bean，Tomcat **已经绑定完端口**了。
  因此主闸门是 `ApplicationEnvironmentPreparedEvent` 监听器（`AssetMcpBindingGuard`），
  装配期 Bean 只作为第二道闸门兜住「绕过监听器直接刷新上下文」的路径；
- 判定只做字面匹配，**不**做 DNS 解析；错误信息不回显配置值。

### 跨源：带 Origin 就 403

- 本服务面向本机 MCP 客户端，不面向浏览器。请求只要带 `Origin` 头就返回 **403**
  （固定 problem 响应，不回显 Origin 值），请求不会进入 MCP 端点；
- 刻意**不**配宽松 CORS：那会把一个可枚举的资产接口开放给任何网页；
  也不选择「什么都不配」——那会让浏览器请求以语义模糊的方式失败。
  显式 403 + 明确文案，让「浏览器不能调用本服务」成为可断言、可解释的契约；
- 无 `Origin` 的正常 MCP SDK 客户端不受影响（有回归测试：真实 SDK 客户端既能初始化，
  也能正常调用工具）。

## 影响

正面：`flowdesk-mcp-asset` 从骨架变成可被真实 MCP 客户端连接、列出工具并调用工具的服务；
工具面最小（一个只读工具）；协议与查询逻辑分离；「没有数据源」与「资产不存在」被明确区分；
监听地址与跨源两条安全边界在最早期、且有两道闸门；不依赖主服务的任何模块。

代价：本阶段只有演示数据，真实资产系统尚未接入（`ASSET_SOURCE_UNAVAILABLE` 是默认结果）；
没有鉴权与授权（因此严格限制在本机回环、且拒绝浏览器上下文）；工具只有单条查询，
没有列表、模糊搜索与分页。

## 已知边界（如实记录）

1. **没有真实资产数据源**：演示数据是虚构的，测试也只验证了演示路径；
   本文档不使用「已接入企业资产系统」这类表述；
2. **没有鉴权**：任何能访问回环端口的本机进程都可以调用工具；
3. **不做写操作**：没有新增/修改/删除工具，端口也没有写方法；
4. **单工具**：只有 `asset_get`，没有批量查询、列表、分页或模糊搜索；
5. **无 MCP 资源与提示（resources/prompts）**：只声明 tools 能力；
6. **不接入主 Agent**：MCP 工具不会注册到 DeepSeek ChatClient，RAG 问答也不会自动调用它
   （模块之间不共享容器，也没有任何跨模块注册）；
7. **未验证项**：`MCP_LIVE=NOT_RUN`（未对接真实 MCP 客户端之外的任何外部服务），
   `POSTGRES_LIVE`/`DASHSCOPE_LIVE`/`LIVE_SMOKE` 与本模块无关但全仓仍为 `NOT_RUN`。

## 重新评估条件

1. 接入真实资产系统时：新增 `AssetDirectory` 实现并把 `mode` 扩展为 `real`，
   同时定义超时、重试与降级策略（届时 `source` 必须变成真实来源标识）；
2. 需要非本机访问时：**先**设计鉴权与授权（MCP 的 HTTP 授权规范或前端代理），
   再放开回环限制——顺序不能反过来；
3. 需要浏览器调用时：改成显式 CORS 白名单 + 鉴权，并重新评估 CSRF 面；
4. 工具数量增长时：把「恰好一个工具」的断言改成「白名单集合」，并保持只读默认；
5. 需要写操作时：先补审计、幂等与权限模型（不能只加一个工具）。

## 修订（FD-0014-R1）：执行校验、会话生命周期与能力声明

本阶段是一次返工，修三件事，并如实记两条框架边界。

### 1. 运行时输入校验必须等于公布的 schema

原实现只用 `root.path("assetId")` 取值，于是**多传字段会被静默忽略**：客户端送
`{"assetId":"AST-900001","asAdmin":true}` 照样拿到资产。schema 里写着
`additionalProperties=false`，执行却不检查 ——「文档说不行、运行期可以」比不写 schema 更糟。

现在 `asset_get` 只接受**恰好**一个 `assetId` 字符串字段的 JSON 对象：

| 输入 | 结果 |
| --- | --- |
| 不是 JSON 对象（数组、字符串、数字、布尔、`null`） | `INVALID_ASSET_ID` |
| 字段数不是 1，或那一个字段不叫 `assetId`（含任意额外字段、值为 `null` 的额外字段） | `INVALID_ASSET_ID` |
| `assetId` 不是字符串（缺失、`null`、数字、对象、数组） | `INVALID_ASSET_ID` |
| 非法 JSON、空串、纯空白、两段 JSON 拼接 | `INVALID_ASSET_ID` |
| `assetId` 不匹配 `AST-[0-9]{6}` | `INVALID_ASSET_ID` |

配套三点：

- schema 补上 `pattern` 与 `maxLength`，并且 **pattern 显式锚定**（`^AST-[0-9]{6}$`）：
  JSON Schema 的 `pattern` 是部分匹配语义，不锚定就会比执行校验更宽松；这两个值直接取自
  `AssetId` 常量，避免文档与代码各写一份而漂移；
- 解析器打开 `FAIL_ON_TRAILING_TOKENS`，两段 JSON 拼接不再被「只看第一段」放过；
- 失败内容仍然只有固定枚举文案（`INVALID_ASSET_ID`），不回显输入、不说明是哪个字段错了。

**两层输入的边界要说清**：MCP 协议层先把 `params.arguments` 反序列化成 Map，因此
「`arguments` 根本不是对象」的请求在进入工具之前就被 SDK 拒绝（实测 500、响应体为空）；
工具层「非对象」的判断由 `ToolCallback` 单元测试覆盖 —— 客户端可以不看 schema 直接发请求，
真正兜底的是执行校验，而不是 schema。

### 2. 允许 `DELETE` 结束会话：`disallow-delete` 不是「写操作开关」

原配置写的是 `spring.ai.mcp.server.streamable-http.disallow-delete: true`，注释理由是
「delete 请求在本阶段没有语义」。这是把**协议层的会话清理**当成了**资产写操作**：
`DELETE /mcp` 结束的是调用方自己的会话，不会碰任何资产数据。

代价是实测出来的，不是推测：

1. Streamable HTTP 里 JSON-RPC **请求**的应答由 `text/event-stream` 承载，流随会话结束而结束；
2. `disallow-delete=true` 时 SDK 传输层对 `DELETE` 直接返回 **405**，并且**不把会话从会话表移除**；
3. 于是客户端 `close()` 之后，会话与它打开的流都留在服务端，成为一个永久的活跃请求；
4. Tomcat 优雅关停（`Commencing graceful shutdown. Waiting for active requests to complete`）
   一直等这个请求，测试 JVM 在 `System.exit(0)` 之后 30 秒仍未退出，最后被 Surefire 强杀：
   `Surefire is going to kill self fork JVM`。

改成 `disallow-delete: false` 之后：真实 SDK 客户端 `close()` 会异步发出 `DELETE /mcp`
（实测：`close()` 返回瞬间会话还在，250 ms 内消失），服务端会话被移除、流被关闭，
关停时 `Commencing` 与 `Graceful shutdown complete` 之间只隔 **9 ms**。

证据放在 `AssetMcpSessionTests`：真实 HTTP 的「会话可用 → `DELETE` 200 → 旧标识 404」，
以及「SDK 客户端 `close()` 后服务端会话表回到基线」。异步收敛用**有界轮询**（最多 5 秒、每 25 ms）
等待，会话真的留下就会失败 —— 没有调大任何 JVM/测试超时，也没有强制退出。

### 3. 只声明实现过的能力（`logging` 是例外，如实记录）

Spring AI 1.1.2 里 `spring.ai.mcp.server.capabilities` 的 `resource` / `prompt` / `completion`
默认值都是 `true`，而本模块只实现了 tools —— 原来的 `initialize` 响应因此声明了三个并不存在的能力。
现在显式关掉这三项，只留 `tool: true`。

**但 `logging` 关不掉**：MCP Java SDK 0.17.0 的 `McpAsyncServer` 构造器无条件执行
`serverCapabilities.mutate().logging().build()`，Spring AI 1.1.2 也没有对应开关。
所以真实 `initialize` 响应的能力集是 **`tools` + `logging`**，而不是「只有 tools」。
本文件与 README 按实测写，不再声称「仅声明 tools」。`AssetMcpCapabilitiesTests`
同时断言原始报文字段集合（`containsExactlyInAnyOrder("logging", "tools")`）、
`ServerCapabilities` 的逐项取值，以及容器里没有注册任何
resource / resource-template / prompt / completion 处理器。

## 修订（FD-0014-R1）后记录的两条边界（**已由 FD-0014-R2 修复**）

下面两条在 FD-0014-R1 时只做了记录，R2 已在**我们自己的传输入口**上收口（见本文最后一节）：

1. **调用未实现的方法会留下一条永不结束的响应流。** 实测：`tools/list`、`tools/call`
   （含工具返回 `isError=true`）的 POST 应答在 200 ms 内 EOF；而 `resources/list`、`prompts/list`
   这类未实现的方法，SDK 只把 `-32601 Method not found` 写进流里却**不结束它**
   （3 秒后仍无 EOF，会话被 `DELETE` 之后依然如此），服务端因此一直挂着一个活跃请求 ——
   这是排查 30 秒强杀时发现的第二条链路。
2. **畸形 JSON-RPC 报文的 400 响应体会带服务端堆栈。** 传输层把 `McpError`
   （一个 `RuntimeException`）直接当作响应体返回，Jackson 按 `Throwable` 序列化，于是响应里
   含服务端堆栈（SDK 与我们自己的类名、行号），部分路径甚至转发原始异常消息。

## 修订（FD-0014-R2）：收口传输层错误与悬挂响应流

### 最小复现（基线 `e994418`，真实 HTTP + 真实流读取）

用原始 HTTP 客户端逐个发请求，并在 1.2 秒窗口内观察响应流是否 EOF：

| 请求 | 状态 | 响应流 | 泄漏 |
| --- | --- | --- | --- |
| `ping` / `tools/list` / `tools/call`（合法）/ `logging/setLevel` | 200 SSE | **EOF 立即到达** | 无 |
| `resources/list` / `prompts/list` / `completion/complete` / `foo/bar` | 200 SSE | **永不结束**（会话 DELETE 之后依旧） | 无 |
| 非对象 `arguments`（字符串、数组） | **500**，体为空，`Content-Type: text/event-stream` | EOF | 无 |
| 畸形 JSON / 顶层数组 / 缺 `method` / 缺会话标识 | 400 JSON | EOF | **`stackTrace` + 类名 + 文件名 + 行号** |
| 未知通知 | 202 | EOF | 无 |

后果是实测的：四条「永不结束」的流让服务端一直持有活跃请求，Tomcat 优雅关停等满 30 秒，
测试 JVM 在 `System.exit(0)` 之后被 Surefire 强杀（`Surefire is going to kill self fork JVM`）。

### 决策

1. **入口闸门 `McpRequestGateFilter`**（只作用于 `POST /mcp`，顺序在 Origin 过滤器之后）：
   在请求进入 SDK 传输实现之前解析报文，用固定 JSON-RPC 错误回答三类请求，其余**原样放行**
   （请求体经包装器完整重放，传输层读到的原文一字不差）。规则：

   | 请求 | 回答 | 状态码 |
   | --- | --- | --- |
   | 不是合法 JSON（含两段拼接） | `-32700 Parse error` | 400 |
   | 合法 JSON 但不是 JSON-RPC 请求对象（数组、裸值、缺 `jsonrpc`/`method`） | `-32600 Invalid request` | 400 |
   | 带 `id` 且方法不在实现清单（`initialize`、`ping`、`tools/list`、`tools/call`、`logging/setLevel`） | `-32601 Method not found[: 方法名]` | **200，普通 JSON** |
   | `tools/call` 的 `params`/`name`/`arguments` 形状不合法 | `-32602 Invalid params…` | **200，普通 JSON** |
   | 请求体超过 1 MiB | `-32600 Request body too large` | 413 |
   | 通知（没有 `id` 成员，含 `notifications/initialized`） | **原样放行** —— 握手语义不能被闸门吞掉 | 传输层决定 |
   | 其余（含合法工具调用、非对象之外的参数） | **原样放行** | 传输层决定 |

   > 上表是 **R2 当时**的规则（那时还没有会话与协议版本校验，也还没有把版本校验放到分流之前）。
   > 当前生效的完整规则见 FD-0014-R4 章节：R2 的行为在 R3/R4 中只被补上了这两处前置条件。

   方法名只在形状受限（`[A-Za-z0-9_./-]{1,64}`）时才回显，避免把任意客户端字符串写回响应。

2. **`McpErrorJsonSerializer`**（Jackson 定制器，兜底）：SDK 仍会在若干路径上把 `McpError`
   （`RuntimeException`）直接作为响应体返回（缺会话标识、Accept 头不合法、SDK 内部异常等）。
   该序列化器把这类响应体固定成 `{"code":…,"message":…}`：错误码沿用 SDK 的 JSON-RPC 码
   （没有就给 `-32600`），**文案只取固定枚举，从不转发 SDK 或异常自己的消息**。
   细节留在服务端日志。

**错误响应没有被丢弃，也没有被统一成一种**：闸门给出的是标准 JSON-RPC 错误码
（-32700/-32600/-32601/-32602），工具层错误仍是 `isError=true` + 固定内容 + 200 的 SSE，
框架错误仍是它原本的状态码（400/404/413）—— 只是不再夹带任何内部信息，且不再有永不结束的流。

### 为什么不在依赖里修（备选方案与影响）

| 方案 | 结论 |
| --- | --- |
| 升级 Spring AI / MCP SDK | **不做**：任务明确禁止升级依赖；且新版本是否修好流结束与 Throwable 序列化未经核实，升级会同时改动协议行为 |
| 用同名类覆盖 SDK 的 `WebMvcStreamableServerTransportProvider` | **不做**：需要劫持包名，依赖升级时会静默失效，属于比缺陷本身更危险的做法 |
| 自己实现 `McpStreamableServerTransportProvider` | **不做**：等于重写传输层（会话、SSE、断线续传），风险远超收益 |
| 在入口闸门 + 序列化器收口（**采用**） | 只碰我们自己的代码，依赖保持不变；三类已知缺陷有测试钉住；SDK 内部缺陷仍会在升级时被重新评估 |

### 影响与残留边界

- 新增的每请求开销是「读一次 ≤1 MiB 的请求体 + 一次 JSON 解析」，只作用于 `POST /mcp`；
- `GET /mcp`（服务端事件流）与 `DELETE /mcp` 不经闸门，行为与 FD-0014-R1 一致：
  GET 的流按协议在会话结束时结束，会话清理依赖客户端 `DELETE`（R1 已允许）；
- **SDK 内部的缺陷仍然存在**（未结束的流、Throwable 序列化、`ex.getMessage()` 直传）。
  我们只是让它们**到不了客户端**；一旦升级 MCP SDK，应当先重跑
  `AssetMcpTransportErrorTests`，确认这些补偿是否可以撤掉，而不是无条件保留；
- 未实现方法的「客户端侧」防线还有一层：规范客户端在服务端未声明该能力时**根本不会发请求**
  （实测 SDK 客户端抛 `IllegalStateException: Server does not provide the resources capability`）。
  闸门面向的是不走客户端库、或故意构造请求的调用方。

## 修订（FD-0014-R3）：闸门不得绕过传输层的会话与版本校验

### 缺陷（R2 引入的优先级错误）

R2 的闸门把「提前回答」放在了会话校验<b>之前</b>：只要报文能被解析、方法未实现或
`tools/call` 参数形状不对，它就回 `200` + `-32601` / `-32602` —— 不管请求有没有会话。
而传输层对同样的请求会回 `400` / `404`。实测对照（真实 HTTP，基线 `0424a0c`）：

| 请求 | 传输层（用会放行的方法观测） | 闸门（两条提前回答路径） |
| --- | --- | --- |
| 无 `Mcp-Session-Id` | **400** `{"code":-32600,"message":"Invalid request"}` | **200** `-32601` / `-32602` ← 绕过 |
| 伪造的会话标识 | **404** | **200** `-32601` / `-32602` ← 绕过 |
| 空串会话标识 | **404** | **200** ← 绕过 |
| `DELETE` 之后的会话标识 | **404** | **200** ← 绕过 |
| 活跃会话 + 不支持的 `MCP-Protocol-Version` | **200（照常处理：传输层根本不校验这个头）** | **200** `-32601` / `-32602` |

也就是说：闸门把「没有会话、或会话不存在」的调用方，当成了「只是问了一个不存在的方法」。

### 与传输层一致的两条校验（实测依据）

| 事实 | 实测结果 | 闸门据此怎么做 |
| --- | --- | --- |
| 非 `initialize` 的 POST 必须有 `Mcp-Session-Id` | 缺失 → 400 | **不回答**，原样放行 → 传输层 400 |
| 会话必须在会话表里 | 伪造 / 空串 / 已 `DELETE` → 404 | **不回答**，原样放行 → 传输层 404 |
| 解析先于会话校验 | 畸形报文 + 无会话 → 仍是解析失败 400 | 解析与「不是 JSON-RPC 请求」的错误**不要求会话**，直接回答 |
| 服务端不校验 `MCP-Protocol-Version` | 带 `1999-01-01` 仍返回 200 | 会话有效后再校验：受支持 → 闸门照旧；**不受支持 → 400 `Unsupported protocol version`** |
| 受支持的版本由传输层公布 | `protocolVersions()` = `[2024-11-05, 2025-03-26, 2025-06-18]` | 用它判定，不自己维护一份版本清单 |

**会话状态怎么查**：SDK 0.17.0 的会话表是传输实现（`WebMvcStreamableServerTransportProvider`）
的私有字段，接口上没有查询方法（`McpStreamableServerTransportProvider` 只有
`setSessionFactory`/`notifyClients`/`closeGracefully`），因此
`McpTransportState` 只读反射读它，返回三态：`LIVE` / `ABSENT` / `INDETERMINATE`。
**读不到就放行**（`INDETERMINATE`）：闸门宁可少回答，也不猜。

### 最终规则（POST /mcp）

> **注意**：R3 当时的处理顺序是「会话 → 通知分流 → 版本校验」，因此通知不受版本规则约束。
> FD-0014-R4 已把版本校验移到「会话之后、分流之前」，下面的表是**当次（R3）**的记录，
> 当前生效的顺序与规则见本文最后的 FD-0014-R4 章节。

| 请求 | 条件 | 回答 |
| --- | --- | --- |
| 任意 | 请求体 > 1 MiB | 413 固定（体积守卫，与容器上限同类，不依赖会话） |
| 任意 | 不是合法 JSON | 400 `-32700`（传输层同样先解析） |
| 任意 | 合法 JSON 但不是 JSON-RPC 请求对象 | 400 `-32600`（同上） |
| `initialize` | — | 原样放行（握手不要求会话；版本在会话里协商） |
| 其他 | 会话缺失 / 空串 / 伪造 / 已删除 / 读不到会话表 | **原样放行** → 传输层 400 / 404（已脱敏） |
| 其他（通知） | 会话有效 | 原样放行（传输层回 202） |
| 其他（请求） | 会话有效 + 版本不受支持 | 400 固定 `Unsupported protocol version` |
| 其他（请求） | 会话有效 + 版本受支持或缺省 + 方法未实现 | 200 `-32601`（有界、脱敏） |
| 其他（请求） | 会话有效 + 版本受支持或缺省 + `tools/call` 形状不合法 | 200 `-32602`（有界、脱敏） |
| 其他 | 会话有效 + 版本受支持或缺省 | 原样放行 |

**保留**：有效会话下的有界、脱敏 JSON-RPC 错误响应与 R2 完全一致（`-32601` / `-32602` / `-32700` / `-32600`）。

### 残留与重估条件

- 会话表读取依赖 SDK 私有字段：升级 MCP SDK 后必须重跑 `AssetMcpGateSessionTests`；
  `McpTransportState` 找不到字段时只会 WARN 并放行（不会 500，也不会误判成「会话无效」）；
- 协议版本校验比 SDK 0.17.0 的传输层**更严格**（后者完全不校验这个头）。这是有意为之：
  规范要求对不受支持的版本回 400，而且只有在这里拒绝，「未实现的方法」才不会再退化成
  一条永不结束的响应流。**缺省版本头仍然放行**，以免凭空拒绝正常客户端；
- `GET /mcp` 与 `DELETE /mcp` 依然不经闸门（R2 起如此）。

## 修订（FD-0014-R4）：通知与请求遵守同一条协议版本规则

### 缺陷（R3 的校验顺序制造了第二套规则）

R3 把版本校验放在「请求/通知分流之后」，于是同一条连接上的两类报文遵守了两套规则：
活跃会话 + 不受支持的版本，请求被 400 拒绝，而通知照旧被接受（实测：`notifications/initialized`
与 `foo/notify` 都返回 **202**）。版本是传输层的前置条件，不该按报文类型区别对待。

### 决策：校验位置前移到分流之前

闸门现在的处理顺序是：

```
解析（不要求会话） → initialize 放行 → 会话有效性 → 协议版本 → 请求/通知分流 → 方法分派
```

| 报文 | 条件 | 回答 |
| --- | --- | --- |
| 请求 | 会话有效 + 版本不受支持 | 400 + `{"error":{"code":-32600,"message":"Unsupported protocol version"}}`，回显请求的 `id` |
| **通知** | 会话有效 + 版本不受支持 | 400 + 同一错误体，但 **`id` 为 `null`** |
| 请求 / 通知 | 会话有效 + 版本受支持或**缺失** | 照旧（请求走方法分派，通知原样放行 → 202） |
| 请求 / 通知 | 版本头**存在但为空白** | 按**无效版本**处理 → 400（空串不是合法版本值，不能因为「看起来像没给」就当作缺失） |
| 请求 / 通知 | 会话缺失 / 伪造 / 已删除 | **优先级不变**：会话先判，仍由传输层回答 400 / 404（不含版本错误文案） |
| `initialize` | 任意版本 | 原样放行（版本在会话里协商，不是在这里过滤） |

三个实现要点：

1. **区分「缺失」与「空值」**：Servlet 的 `getHeader` 对两者都可能返回 `null`/空串，
   因此闸门读 `getHeaders(...)` 的全部取值 —— 一个都没有才算缺失（传 `null`，兼容老客户端），
   有取值就取第一个（空串或纯空白一律判为无效版本）。实测 Tomcat 会把这个头原样交给应用，
   所以这条规则在真实链路上是可观测、可断言的；
2. **通知的错误体**：`idOf(root)` 对没有 `id` 成员的报文返回 `null`，因此通知的 400 天然是
   `id:null` + 固定错误码 + 固定文案，不回显版本值，也不夹带任何内部信息；
3. **不改变优先级**：会话有效性仍在版本之前，缺失/伪造/已删除会话的 400 / 404 与 R3 完全一致；
   `initialize` 依然不参与版本校验。

### 证据（真实 HTTP）

- `notifications/initialized`、`foo/notify` + 活跃会话 + `1999-01-01` → **400**，
  `{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Unsupported protocol version"}}`（有界、无泄漏）；
- 同一条件下 `tools/list` → 400，`id` 回显为 `91`；
- 传输层公布的 `[2024-11-05, 2025-03-26, 2025-06-18]` 与**缺省版本头**下：通知 202、`tools/list` 200 且含 `asset_get`；
- 空白版本头（`""`、`" "`、`"   "`）→ 400（请求与通知都是）；
- 无会话 + 不受支持版本 → 400 `Invalid request`（传输层），伪造/已删除 → 404，均不含版本错误文案；
- 被拒的通知不影响会话：随后缺省版本的通知 202、`tools/call` 正常、`DELETE` 200；
- `initialize` + `1999-01-01` → 200（握手不做版本过滤）。

### 残留

与 R3 相同：会话表读取依赖 SDK 私有字段；版本校验比 SDK 0.17.0 的传输层更严格，属有意为之；
升级 SDK 时应重跑 `AssetMcpNotificationVersionTests`、`AssetMcpGateSessionTests` 与
`AssetMcpTransportErrorTests`，确认这些补偿能否撤掉。
