# FlowDesk 企业智能工单与知识运营平台

> **当前阶段：FD-0011-R2 —— 收紧向量检索端口的应用异常分类（RAG 4/6 修订，已完成）**
> 已完成：Maven 多模块骨架与版本基线（FD-0001）、DeepSeek 接入与工具调用闭环（FD-0002）、
> 工单领域状态机（FD-0003）、工单应用用例与乐观并发契约（FD-0004）、
> JDBC 持久化适配器 + Flyway 迁移 + Spring 装配（FD-0005）、
> 工单 REST 接口 + ETag 并发协议 + 统一错误契约（FD-0006）、
> 列表接口 + offset 分页 + 排序白名单 + 条件搜索（FD-0007）、
> 知识文档上传 + 原始文件存储 + 元数据查询（FD-0008）、
> 文档解析（PDF/DOCX/Markdown/TXT，含 OOXML 包类型验证与显式关闭 OCR）+
> 确定性切片 + 解析状态机与原子落库（FD-0009）、
> 切片向量化（DashScope text-embedding-v4）+ pgvector 原子落库 + 索引状态机（FD-0010）、
> 索引链路修订（Key fail-fast、按 index 归位、关闭依赖库正文日志、失败码贯通、真正的 JDBC 批处理）（FD-0010-R1）、
> provider 血缘校验与文档契约修正（只允许规范值 `dashscope`、Key 优先级改为模态级优先、失败码措辞不再绝对化）（FD-0010-R2）、
> 顺序契约统一与过期阶段说明清理（协议层归位 vs 持久化层拒绝错配，纯文档修订）（FD-0010-R3）、
> 知识检索（Query Embedding + pgvector 余弦检索 + 稳定引用编号）（FD-0011）、
> 检索契约修订（公开硬上限写进用例构造器、行映射异常统一归类为内部失败、空请求体统一为检索契约）（FD-0011-R1）、
> 检索端口错误分类收口（端口只允许 `KNOWLEDGE_RETRIEVAL_FAILURE`，其它错误码一律收敛为内部失败）（FD-0011-R2）。
> 尚未实现：Rerank、全文检索与混合检索、基于检索结果的答案生成、
> 任意切片读取接口、文档列表/下载/删除、孤立文件清理任务、`PARSING`/`INDEXING` 悬挂的恢复扫描、
> 游标分页、PostgreSQL 全文检索与 pg_trgm、MCP 能力、Agent Graph、鉴权与前端。

## 一、项目简介

FlowDesk 面向企业 IT 服务与运营场景，规划能力包括：智能化工单流转、知识库运营、RAG 检索增强、
Tool 调用、MCP 资产/监控服务以及基于 Agent Graph 的自动化编排。

当前仓库已经完成六件事：一是打通的 AI 垂直链路
（**HTTP → 用例 → Agent 编排 → Spring AI ChatClient → DeepSeek（OpenAI 兼容传输）→
本地只读工具 → 模型汇总 → HTTP 响应**），二是纯 Java 的工单领域核心
（工单聚合与生命周期状态机）与完整的工单 REST 链路（ETag 乐观并发、分页与条件搜索），
三是知识文档的**安全上传链路**（流式落盘、内容键与路径收敛、失败补偿），
四是文档的**解析与确定性切片**（真实 PDF/DOCX/Markdown/TXT → 纯文本 → 确定性切片 → 原子落库），
五是切片**向量化与 pgvector 落库**（分批调用 DashScope text-embedding-v4 → 校验 → 单事务替换向量并推进为 INDEXED），
六是**知识检索**（Query Embedding（textType=query）→ pgvector 余弦检索 → 稳定引用编号 K1、K2……），
并保留了清晰的模块边界、单向依赖方向与统一的版本基线。

## 二、模块职责

| 模块 | 职责 | 当前状态 |
| --- | --- | --- |
| `flowdesk-shared` | 通用异常、基础类型、工具类 | 仅模块与 `package-info.java` |
| `flowdesk-domain` | 领域实体、值对象、领域规则（**不依赖 Spring**） | 已实现工单聚合与生命周期状态机（`com.flowdesk.domain.ticket`）、知识文档聚合与解析/索引状态机、切片与向量不变量、查询向量（`com.flowdesk.domain.knowledge`） |
| `flowdesk-application` | 用例服务、输入输出端口 | 已实现 AI 用例（`…application.ai`）、工单用例 + 乐观并发契约（`…application.ticket`）、知识文档上传/查询/解析/索引用例与端口（`…application.knowledge`，含 `KnowledgeEmbeddingPort`）、知识检索用例与端口（`…application.knowledge`，含 `KnowledgeQueryEmbeddingPort` 与 `KnowledgeVectorSearchPort`） |
| `flowdesk-agent` | AI 编排：实现 application 的 AI 用例，用 ChatClient 编排提示词与本地工具 | 已实现普通聊天与工具冒烟 |
| `flowdesk-infrastructure` | 持久化与模型适配器 | 已提供 JDBC 工单存储（`…ticket.persistence.jdbc`）、DeepSeek 传输适配，以及知识文档的 JDBC 存储、本地文件系统内容读写、Tika 解析适配器、确定性切片器、DashScope 文档/查询 Embedding 适配器、pgvector 向量写入与相似度检索适配器（`…knowledge.*`） |
| `flowdesk-bootstrap` | FlowDesk 主服务启动模块（Web + Validation + Actuator + AI 接口 + 工单 REST 接口 + 知识文档 REST 接口 + 知识检索接口） | 可启动，端口 8080 |
| `flowdesk-mcp-asset` | 独立资产 MCP 服务（Web + Actuator） | 可启动，端口 8091 |
| `flowdesk-mcp-monitoring` | 独立监控 MCP 服务（Web + Actuator） | 可启动，端口 8092 |

## 三、模块依赖关系

```
flowdesk-shared
    ↑
flowdesk-domain
    ↑
flowdesk-application
    ↑                 ↑
flowdesk-agent        flowdesk-infrastructure
        \             /
        flowdesk-bootstrap

flowdesk-mcp-asset          ← 只依赖 flowdesk-shared
flowdesk-mcp-monitoring     ← 只依赖 flowdesk-shared
```

约束：

- 依赖方向单向，无循环依赖。
- `flowdesk-domain` 不依赖 Spring、JPA、Web、AI，也不依赖任何基础设施模块。
- `flowdesk-mcp-asset` 与 `flowdesk-mcp-monitoring` 只允许依赖 `flowdesk-shared`。
- Spring Boot Maven Plugin 只作用于 `flowdesk-bootstrap`、`flowdesk-mcp-asset`、`flowdesk-mcp-monitoring`。

## 四、技术版本表

| 组件 | 版本 |
| --- | --- |
| JDK | 17（Release 固定 17，Enforcer 校验 `[17,18)`） |
| Maven | 3.9+（Enforcer 校验 `[3.9,)`） |
| Spring Boot | 3.5.8（父 POM + BOM） |
| Spring AI | 1.1.2（BOM 管理；实际使用 `spring-ai-client-chat` 与 `spring-ai-starter-model-openai`） |
| Spring AI Alibaba | 1.1.2.2（BOM 管理；实际使用 `spring-ai-alibaba-starter-dashscope`，只在 `dashscope-embedding` profile 下提供 `EmbeddingModel`；Agent Framework 留待后续阶段） |
| Spring AI Alibaba Extensions | 1.1.2.2（**仅导入 BOM**） |
| DeepSeek 传输 | OpenAI 兼容 Chat Completions（`spring-ai-starter-model-openai`，见 [ADR 0001](docs/adr/0001-deepseek-openai-compatible-transport.md)） |
| Apache Tika | 3.3.2（`tika-core` + `tika-parsers-standard-package`，版本由根 pom 的 `tika.version` 单点锁定；**不使用 `tika-app`**；已排除 `commons-logging` 与 `jcl-over-slf4j`，只保留 Spring 自带的 `spring-jcl`，见 [ADR 0006](docs/adr/0006-document-parsing-and-deterministic-chunking.md)） |
| JUnit | JUnit 5（由 `spring-boot-starter-test` 统一提供） |
| 编码 | UTF-8（源码与报告输出） |

坐标：`com.flowdesk:flowdesk:0.1.0-SNAPSHOT`

## 五、本地构建命令

```bash
# Windows
mvnw.cmd clean test
mvnw.cmd clean package

# macOS / Linux
./mvnw clean test
./mvnw clean package

# 只构建单个模块（含其依赖）
./mvnw -pl flowdesk-bootstrap -am clean package
```

首次构建会由 Maven Wrapper 下载 Maven 发行版并解析依赖，需要可访问 Maven 仓库。

## 六、服务端口

| 服务 | 模块 | 端口 | 健康检查 |
| --- | --- | --- | --- |
| FlowDesk 主服务 | `flowdesk-bootstrap` | 8080 | `http://localhost:8080/actuator/health` |
| 资产 MCP 服务 | `flowdesk-mcp-asset` | 8091 | `http://localhost:8091/actuator/health` |
| 监控 MCP 服务 | `flowdesk-mcp-monitoring` | 8092 | `http://localhost:8092/actuator/health` |

启动方式（示例）：

```bash
java -jar flowdesk-bootstrap/target/flowdesk-bootstrap-0.1.0-SNAPSHOT.jar
java -jar flowdesk-mcp-asset/target/flowdesk-mcp-asset-0.1.0-SNAPSHOT.jar
java -jar flowdesk-mcp-monitoring/target/flowdesk-mcp-monitoring-0.1.0-SNAPSHOT.jar
```

配置约定：三个 `application.yml` 只声明应用名、端口，并只暴露 `health`、`info` 两个 Actuator 端点；
不写入任何密码、Token 或 API Key 字面量。DeepSeek 相关配置集中在
`flowdesk-bootstrap/src/main/resources/application-deepseek.yml`，其中的 Key 只引用环境变量
`${DEEPSEEK_API_KEY}`，默认 profile 下完全不会被激活。

## 七、AI 链路（FD-0002）

本阶段打通了 FlowDesk 的第一条 AI 垂直链路：

```
HTTP → Bootstrap Controller → Application 用例接口 → Agent 编排 → Spring AI ChatClient
     → DeepSeek（OpenAI 兼容传输）→ lookup_support_policy 本地工具
     → DeepSeek 汇总工具结果 → HTTP 响应
```

### 7.1 模型接入方式

DeepSeek 通过 **OpenAI 兼容的 Chat Completions 接口**访问，使用
`spring-ai-starter-model-openai` 而非原生 DeepSeek 适配器。
决策背景、理由与重新评估条件见
[`docs/adr/0001-deepseek-openai-compatible-transport.md`](docs/adr/0001-deepseek-openai-compatible-transport.md)。

### 7.2 HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/ai/chat` | 普通聊天。`message` 去空白后不能为空、最长 4000 字符；**不注册任何工具** |
| POST | `/api/v1/ai/tool-smoke` | 工具调用冒烟。`issueType` 允许值 `ACCOUNT_LOCK`、`VPN_FAILURE`、`DEVICE_OFFLINE` |

```bash
curl -X POST http://localhost:8080/api/v1/ai/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"请用一句话介绍 FlowDesk"}'

curl -X POST http://localhost:8080/api/v1/ai/tool-smoke \
  -H 'Content-Type: application/json' \
  -d '{"issueType":"VPN_FAILURE"}'
```

错误契约（Spring `ProblemDetail`）：

| 场景 | 状态码 | `code` |
| --- | --- | --- |
| 参数非法 | 400 | `INVALID_REQUEST` |
| 上游模型调用失败 | 502 | `AI_PROVIDER_ERROR` |

响应中不会出现供应商原始报文、堆栈或配置。

### 7.3 配置与启用方式

默认 profile **不装配任何模型适配器**，因此没有 API Key 也能启动与运行测试，
并且不存在任何出网可能：

```yaml
spring:
  ai:
    model:
      chat: none
      embedding: none
      image: none
      moderation: none
      audio:
        speech: none
        transcription: none
flowdesk:
  ai:
    enabled: false
```

> `spring.ai.model.chat: none` 只关闭对话模型；OpenAI starter 在类路径上时还会自动装配
> embedding / image / audio / moderation，它们同样要求 API Key，因此必须一并关闭，
> 否则默认 profile 会启动失败。

真实调用 DeepSeek 时启用 `deepseek` profile（配置见
`flowdesk-bootstrap/src/main/resources/application-deepseek.yml`）。

**推荐方式：先打包，再直接运行 jar。** 这是唯一不依赖「兄弟模块已 install」的方式，
干净仓库里也能直接跑通：

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY = 'sk-...'
.\mvnw.cmd clean package
java -jar flowdesk-bootstrap/target/flowdesk-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=deepseek

# macOS / Linux
export DEEPSEEK_API_KEY=sk-...
./mvnw clean package
java -jar flowdesk-bootstrap/target/flowdesk-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=deepseek
```

**备选方式：先 install 再 `spring-boot:run`。** 注意必须先 `install` 把兄弟模块装进本地仓库，
否则单独运行 `-pl flowdesk-bootstrap` 时 `flowdesk-agent` / `flowdesk-infrastructure`
尚未解析得到，构建会失败：

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY = 'sk-...'
.\mvnw.cmd -DskipTests clean install
.\mvnw.cmd -pl flowdesk-bootstrap spring-boot:run "-Dspring-boot.run.profiles=deepseek"
```

> 不要写成 `-pl flowdesk-bootstrap -am spring-boot:run`：`-am` 会让 `spring-boot:run`
> 这个 goal 同时在上游库模块上执行，而那些模块没有可运行的主类，必然失败。

**PowerShell 下 `-D` 参数的写法**：`-D` 参数的**值**里只要含有点号或等号，就必须用引号包住，
否则 PowerShell 会把它拆开，Maven 会报 `Unknown lifecycle phase`：

```powershell
# 正确
.\mvnw.cmd -pl flowdesk-bootstrap spring-boot:run "-Dspring-boot.run.profiles=deepseek"

# 错误（已实测）：被拆成 .run.profiles=deepseek
.\mvnw.cmd -pl flowdesk-bootstrap spring-boot:run -Dspring-boot.run.profiles=deepseek
```

`-DskipTests` 这类不含点号与等号的参数，加不加引号都可以（`-DskipTests` 与 `"-DskipTests"` 均已实测通过）。
本仓库所有示例与 `.env.example` 遵循同一条规则。

环境变量：

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `DEEPSEEK_API_KEY` | 是（deepseek profile） | 无 | 缺失时启动失败 |
| `DEEPSEEK_BASE_URL` | 否 | `https://api.deepseek.com` | 兼容接口根地址 |
| `DEEPSEEK_MODEL` | 否 | `deepseek-flash` | 模型名，不写死旧名称 |

`deepseek` profile 会显式关闭 DeepSeek 的 thinking：

```yaml
spring:
  ai:
    openai:
      chat:
        options:
          extra-body:
            thinking:
              type: disabled
```

> **重要**：Spring Boot **不会**自动读取 `.env` 文件。仓库根目录的
> [`.env.example`](.env.example) 只是一份「需要哪些变量」的清单，
> 必须由 Shell、IDE 运行配置或容器编排显式注入。
> 严禁把真实 API Key 提交进仓库或在日志/响应中输出。

### 7.4 已知的 Spring AI 1.1.2 传输层缺陷与绕行

`OpenAiChatModel.createRequest` 在**注册了工具**时会执行第二次 `ModelOptionsUtils.merge`
（把 `tools` 数组并进请求体），而这次合并会把 `extraBody` 清空：

```
第一次 merge 后: {"messages":[],"model":"deepseek-flash","thinking":{"type":"disabled"}}
第二次 merge 后: {"messages":[],"model":"deepseek-flash"}     ← thinking 被抹掉
```

也就是说配置里的 `extra-body` 在普通聊天路径有效，一旦携带工具就失效，
而携带工具恰恰是必须关闭 thinking 的场景。FlowDesk 因此在传输层加入了
`DeepSeekThinkingDisabledInterceptor`：在请求真正发出前补齐该字段。

**该拦截器的作用域是严格限定的**，只有同时满足以下全部条件的请求才会被修改：

1. HTTP 方法为 `POST`；
2. `Content-Type` 为 JSON（`application/json` 或 `application/*+json`）；
3. scheme、host 与有效端口和配置的 DeepSeek `base-url` 完全一致；
4. path 与「`base-url` 的 path + 配置的 `completions-path`」完全一致。

不使用 `endsWith("/chat/completions")` 这类宽松判断 —— 同一个 JVM 里的其它客户端
（向量库、其它 OpenAI 兼容提供方、本地回环服务）都可能命中同名路径。
请求体已声明 `thinking.type=disabled` 时按原始字节透传，不重复写入也不破坏报文。

作用域由 `DeepSeekThinkingDisabledInterceptorTests` 覆盖（正确端点注入、
异 host 同 path 不注入、同 host 错 path 不注入、非 POST/非 JSON 不注入、重复不破坏），
真实链路由 `ToolCallingLoopTests` 断言。Spring AI 修复该缺陷后，拦截器可整体删除。

### 7.5 上游故障的失败时效

Spring AI 默认重试为 `max-attempts=10`、`multiplier=5`、初始退避 2000ms，
累计退避约 4 分钟。这意味着上游故障时客户端等到的不是 502 而是自己的超时 ——
与接口契约相悖。`deepseek` profile 因此显式收窄为有界重试：

```yaml
spring:
  ai:
    retry:
      max-attempts: 3
      backoff:
        initial-interval: 500ms
        multiplier: 2
        max-interval: 2000ms
```

典型故障（连接被拒）下约 1.5 秒即返回 502。该时效由
`ProviderFailureBoundedTests` 锁死。

## 八、工单领域模型

`flowdesk-domain` 中的 `com.flowdesk.domain.ticket` 是纯 Java（只用 JDK）实现的工单聚合，
不依赖 Spring、持久化框架或任何外部系统，时间与标识一律由调用方传入，
聚合内部从不读取系统时钟 —— 因此行为完全确定，可直接单元测试。

**状态机**：

```
NEW --assign--> ASSIGNED --start--> IN_PROGRESS --resolve--> RESOLVED --close--> CLOSED
                    |                     |
                    +------ reassign -----+      （状态不变，仅更换处理人）
```

除上述转换外，任何组合都抛 `ILLEGAL_STATUS_TRANSITION`。状态字段没有公共 setter。

**聚合不变量**：

- 时间线为单链：`createdAt <= resolvedAt <= closedAt <= updatedAt`；
  `resolvedAt`、`closedAt` 不存在时跳过对应比较（`updatedAt` 始终存在，是链条末端）；
- 状态与可选字段严格共存：`NEW` 无处理人/结论/时间戳；`ASSIGNED`、`IN_PROGRESS` 有处理人、
  无结论与时间戳；`RESOLVED` 有处理人、结论与 `resolvedAt`、无 `closedAt`；`CLOSED` 全部齐备。

> `resolvedAt <= updatedAt` 与 `closedAt <= updatedAt` 这两条不可省：若只校验下界，
> 一个 `resolvedAt` 晚于 `updatedAt` 的 `RESOLVED` 快照就能被恢复，随后 `close(updatedAt)`
> 会把 `closedAt` 写到 `resolvedAt` 之前，让时间倒流。

**两种构造入口**：`Ticket.create(...)` 用于新工单（产出 `NEW` 状态）；
`Ticket.restore(...)` 用于数据库适配器恢复快照，会完整校验字段规则、状态一致性
与上述完整时间链，任何不自洽都抛 `INVALID_RESTORED_STATE`。

**错误契约**：所有失败都抛 `TicketDomainException` 并携带 `TicketErrorCode`，
上层据此做稳定映射，不必解析异常文案；异常信息不回显调用方传入的原始非法值。

**实体语义**：相等性只由 `TicketId` 决定；`toString` 只输出标识、状态与时间线，
不含标题、描述与处理结论。

## 九、工单应用层与乐观并发

`com.flowdesk.application.ticket` 是框架无关的纯 Java 应用层，只依赖 JDK 与领域模块：

```
输入端口 TicketCommandUseCase / TicketQueryUseCase
   → TicketApplicationService（无状态，构造器注入存储、标识、时间三个端口）
   → Ticket 领域聚合
   → TicketRepository 输出端口（乐观并发）
   → TicketView（不可变只读视图）
```

**不使用** `UUID.randomUUID()`、`Instant.now()`、`Clock.system*`、Spring 注解或静态全局依赖 ——
标识与时间都来自端口，因此同一份输入永远得到同一份输出。

### 9.1 端口契约

| 端口 | 契约 |
| --- | --- |
| `TicketRepository.findById` | 返回**独立恢复**的聚合；不存在返回 `Optional.empty()`，绝不返回 `null`；适配器不得交出内部可变存储引用 |
| `TicketRepository.insert` | 新工单初始版本为 0；**原子拒绝**重复标识，失败抛 `TICKET_ALREADY_EXISTS` |
| `TicketRepository.update` | **原子 compare-and-set**：仅当存储版本等于 `expectedVersion` 时写入并**严格加 1**；记录不存在抛 `TICKET_NOT_FOUND`，版本不匹配抛 `TICKET_VERSION_CONFLICT`（两者必须区分） |
| `TicketRepository.search` | 分页 / 条件搜索：返回「当前页 + 总数」，两者来自**同一个独立（`REQUIRES_NEW`）只读 `REPEATABLE_READ` 事务**的同一数据库快照；**最多两条语句**（一条 `COUNT`、一条分页查询，零结果时只有 `COUNT`），**无 N+1**；筛选值全部参数绑定，排序按白名单映射固定列名，每行仍经 `Ticket.restore` 恢复成独立聚合 |

### 9.2 用例流程与顺序保证

创建：校验命令 → 取标识与时间 → `Ticket.create(...)` → `insert` → 视图（版本 0）。

状态变更：校验命令/标识/版本 → `findById`（不存在即 `TICKET_NOT_FOUND`）→ 比对版本
（不一致即 `TICKET_VERSION_CONFLICT`，**此时不读时间、不改聚合、不写存储**）→
读取一次时间 → 调用领域方法 → `update(ticket, expectedVersion)` → 用返回的新版本生成视图。

领域规则失败时异常原样向上抛出（携带 `TicketErrorCode`），**不写存储**；
读取后写入前发生并发写入时，存储抛出的 `TICKET_VERSION_CONFLICT` 原样传播。
查询只读，不读时间、不写存储、不推进版本。

列表 / 搜索：校验并规范化查询（套默认值、strip、解析枚举与排序白名单；不合法即报错且**不触碰存储**）
→ `search(条件)` 一次取回「当前页 + 总数」→ 映射成视图并推导分页元数据。
全过程**不生成标识、不读取时间、不写存储、不推进版本**，因此列表查询永远无副作用。

### 9.3 错误契约

| 错误码 | 触发条件 |
| --- | --- |
| `INVALID_COMMAND` | 命令／查询为 `null`、工单标识为 `null`、`expectedVersion < 0` |
| `INVALID_QUERY` | 列表查询条件不合法（页码、页大小、枚举取值、排序白名单、字符串格式）；HTTP 层据此映射为 400 `INVALID_REQUEST` |
| `TICKET_NOT_FOUND` | 目标工单不存在（用例读取时或存储写入时） |
| `TICKET_ALREADY_EXISTS` | 插入的工单标识已存在 |
| `TICKET_VERSION_CONFLICT` | 调用方版本过期，或并发写入导致 compare-and-set 失败 |

异常始终携带非空错误码，文案不回显标题、描述、结论或用户原始输入；
领域层异常**不被包装**，`TicketErrorCode` 直接到达调用方。

## 十、工单持久化（JDBC + Flyway + H2/PostgreSQL）

技术选型与理由见 [`docs/adr/0002-ticket-persistence-spring-jdbc.md`](docs/adr/0002-ticket-persistence-spring-jdbc.md)。

### 10.1 组件职责

| 组件 | 职责 |
| --- | --- |
| Spring JDBC `JdbcClient` | 显式 SQL + 参数绑定，实现 `TicketRepository`；**不使用 JPA/Hibernate/MyBatis** |
| Flyway | `flowdesk-infrastructure/src/main/resources/db/migration` 下的迁移；默认启用 |
| PostgreSQL | 目标数据库；通过 `postgres` profile 连接 |
| H2（PostgreSQL 兼容模式） | 默认 profile 的内存数据库，用于本地开发与自动化集成测试；**不是生产数据库** |

### 10.2 表结构与约束

`V1__create_tickets.sql` 建立 `tickets` 表（`id UUID` 主键、`version BIGINT`、三列时间戳
`TIMESTAMP(6) WITH TIME ZONE`），索引：

- V1：`(status, updated_at)`、`(assignee_id, status)`、`(created_at)`
- V2（FD-0007，列表与搜索）：`(updated_at DESC, id ASC)`、`(requester_id, updated_at DESC, id ASC)`

索引取舍与「为什么某些候选索引刻意不加」见
[`docs/adr/0004-ticket-search-pagination.md`](docs/adr/0004-ticket-search-pagination.md)。

数据库层面的 CHECK 约束（标准 SQL，PostgreSQL 与 H2 兼容模式都能执行，不使用数据库专属枚举类型）：

| 约束 | 内容 |
| --- | --- |
| 版本 | `version >= 0` |
| 枚举 | `category` / `priority` / `status` 只能是领域枚举取值 |
| 状态组合 | `NEW` 无处理人/结论/时间戳；`ASSIGNED`、`IN_PROGRESS` 有处理人、无结论与时间戳；`RESOLVED` 有处理人、结论与 `resolved_at`、无 `closed_at`；`CLOSED` 全部齐备 |
| 时间链 | `created_at <= resolved_at <= closed_at <= updated_at` |
| 文本 | 必填文本不能是空字符串或纯空格；长度与领域上限一致（200 / 4000 / 2000 / 64） |

> 已知边界：SQL 标准的 `TRIM()` 只去空格，不去制表符等其它空白；领域层的 `strip()` 更严格。
> 两层是「领域更严、数据库兜底」，不是完全等价。

### 10.3 乐观锁链路

```
TicketApplicationService
  → TicketRepository（端口）
  → JdbcTicketRepository（适配器）
  → 同一事务内：SELECT version ... FOR UPDATE
              → 不存在：TICKET_NOT_FOUND
              → 版本不匹配：TICKET_VERSION_CONFLICT（不产生任何写入）
              → UPDATE ... WHERE id = ? AND version = ?（影响行数必须为 1）
              → 重新读取并返回独立恢复的聚合
```

`insert` 固定写入 `version = 0`，以主键唯一约束作为并发插入的最终防线；
适配器只把主键重复映射为 `TICKET_ALREADY_EXISTS`，其它约束异常原样抛出。

### 10.4 启动方式

**默认（内存 H2，无需数据库密码）**：

```powershell
.\mvnw.cmd clean package
java -jar flowdesk-bootstrap/target/flowdesk-bootstrap-0.1.0-SNAPSHOT.jar
```

**连接 PostgreSQL**：

```powershell
$env:FLOWDESK_DB_URL = 'jdbc:postgresql://localhost:5432/flowdesk'
$env:FLOWDESK_DB_USERNAME = '<your-user>'
$env:FLOWDESK_DB_PASSWORD = '<your-password>'
java -jar flowdesk-bootstrap/target/flowdesk-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres
```

环境变量清单见 [`.env.example`](.env.example)（该文件不会被 Spring Boot 自动加载）。

### 10.5 当前验证状态

| 项 | 状态 | 说明 |
| --- | --- | --- |
| `H2_INTEGRATION` | ✅ 已执行 | 真实 JDBC + 真实 Flyway 迁移 + 真实并发线程（两个连接） |
| `POSTGRES_LIVE` | ⚠️ **NOT_RUN** | 本机没有 PostgreSQL 服务、没有 Docker、没有 psql，未做任何跳过式伪装 |

> **`POSTGRES_LIVE=NOT_RUN` 不等于 PostgreSQL 已验收。** 目前只在 H2 的 PostgreSQL 兼容模式上
> 验证过 SQL、约束与并发行为；迁移脚本与 SQL 都是按标准 SQL 编写、预期在 PostgreSQL 上同样成立，
> 但在真实 PostgreSQL 上跑通之前，不应认为它已被验证。

## 十一、工单 REST 接口

决策理由见 [`docs/adr/0003-ticket-http-etag-concurrency.md`](docs/adr/0003-ticket-http-etag-concurrency.md)；
列表与搜索的设计取舍见 [`docs/adr/0004-ticket-search-pagination.md`](docs/adr/0004-ticket-search-pagination.md)。

### 11.1 接口表

统一前缀 `/api/v1/tickets`，成功响应统一 `Content-Type: application/json`
（类级 `produces`，见 11.3 的 406 说明）。

| 方法 | 路径 | 请求体 | 成功响应 |
| --- | --- | --- | --- |
| GET | `/api/v1/tickets` | 无（查询参数见 11.5） | 200 OK，**无 ETag** |
| POST | `/api/v1/tickets` | 创建工单 | 201 Created + `Location` + `ETag` |
| GET | `/api/v1/tickets/{ticketId}` | 无 | 200 OK + `ETag` |
| POST | `/api/v1/tickets/{ticketId}/assign` | `assigneeId` | 200 OK + `ETag` |
| POST | `/api/v1/tickets/{ticketId}/reassign` | `assigneeId` | 200 OK + `ETag` |
| POST | `/api/v1/tickets/{ticketId}/start` | 无 | 200 OK + `ETag` |
| POST | `/api/v1/tickets/{ticketId}/resolve` | `resolution` | 200 OK + `ETag` |
| POST | `/api/v1/tickets/{ticketId}/close` | 无 | 200 OK + `ETag` |

请求示例：

```json
POST /api/v1/tickets
{
  "title": "无法登录办公系统",
  "description": "输入正确密码后仍提示认证失败",
  "category": "ACCOUNT_ACCESS",
  "priority": "P2",
  "requesterId": "alice"
}
```

```json
POST /api/v1/tickets/{ticketId}/assign      →  { "assigneeId": "bob" }
POST /api/v1/tickets/{ticketId}/resolve     →  { "resolution": "已重置认证状态" }
```

响应体字段：`id`、`title`、`description`、`category`、`priority`、`requesterId`、`assigneeId`、
`status`、`resolution`、`createdAt`、`updatedAt`、`resolvedAt`、`closedAt`、`version`。
其中 `id` 是 UUID 字符串，用户标识是普通字符串，枚举使用领域枚举名称，时间是 ISO-8601，
可空字段输出 JSON `null`，**`version` 与响应头 `ETag` 永远一致**。

### 11.2 ETag 乐观并发

所有状态变更接口必须携带 `If-Match`，**请求体中不存在 `expectedVersion`**：

```powershell
# 读取当前版本
curl.exe http://localhost:8080/api/v1/tickets/$id
# → ETag: "0"

# 带上该版本做变更
curl.exe -X POST "http://localhost:8080/api/v1/tickets/$id/assign" `
  -H 'Content-Type: application/json' -H 'If-Match: "0"' -d '{"assigneeId":"bob"}'
# → 200 OK, ETag: "1"
```

只接受**单个、强类型、规范十进制** ETag（如 `"0"`、`"1"`、`"25"`，允许去除头值首尾空格）。
以下一律 400 `INVALID_IF_MATCH`：弱 ETag `W/"0"`、`*`、多个 ETag、未加引号、负数、
非数字、小数、前导零、超出 `long` 范围、空值。缺少 `If-Match` 返回 428 `PRECONDITION_REQUIRED`，
版本过期返回 412 `TICKET_VERSION_CONFLICT`。

### 11.3 错误契约

所有错误都是 `application/problem+json`，包含 `type`（`urn:flowdesk:problem:<code 小写连字符>`）、
`title`、`status`、`detail`、`instance`、`code`。

| 场景 | HTTP | code |
| --- | --- | --- |
| JSON、UUID、枚举、Bean Validation 错误 | 400 | `INVALID_REQUEST` |
| 列表查询参数非法（页码、页大小、枚举、排序字段/方向、空白或超长的字符串） | 400 | `INVALID_REQUEST` |
| 非法 `If-Match` | 400 | `INVALID_IF_MATCH` |
| 缺少 `If-Match` | 428 | `PRECONDITION_REQUIRED` |
| 应用层命令不合法 | 400 | `INVALID_COMMAND` |
| 工单不存在 | 404 | `TICKET_NOT_FOUND` |
| 工单标识已存在 | 409 | `TICKET_ALREADY_EXISTS` |
| 版本冲突 | 412 | `TICKET_VERSION_CONFLICT` |
| 非法状态转换 | 409 | `ILLEGAL_STATUS_TRANSITION` |
| 相同处理人 | 409 | `SAME_ASSIGNEE` |
| 字段级领域校验失败 | 422 | 保留对应领域错误码 |
| 持久化快照不自洽 | 500 | `INVALID_PERSISTED_TICKET` |
| 知识文档：标题/文件名等非法输入、空文件 | 400 | `INVALID_REQUEST` |
| 知识文档：超过大小限制（含容器侧 multipart 超限） | 413 | `DOCUMENT_TOO_LARGE` |
| 知识文档：格式不受支持或声明与实际内容不一致 | 415 | `UNSUPPORTED_DOCUMENT_TYPE` |
| 知识文档不存在 | 404 | `KNOWLEDGE_DOCUMENT_NOT_FOUND` |
| 路径不存在 | 404 | `ENDPOINT_NOT_FOUND` |
| 路径存在但方法不支持 | 405 | `METHOD_NOT_ALLOWED`（保留标准 `Allow` 头） |
| `Accept` 无法被满足 | 406 | `NOT_ACCEPTABLE` |
| `Content-Type` 不受支持 | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 未预期异常 | 500 | `INTERNAL_SERVER_ERROR` |

上表覆盖 FlowDesk 自行处理（以及兜底处理）的全部错误来源：工单业务错误、知识文档业务错误、
应用层错误、请求解析与 Bean Validation 失败，以及五类框架错误（404 / 405 / 406 / 415 / 500）。
除 405 按 RFC 9110 保留 `Allow` 头外，所有错误响应体形状一致。

**406 与 415 方向相反**：415 是「你发来的请求体我读不懂」（`Content-Type`），
406 是「你要的响应表示我给不了」（`Accept`）。工单接口在类级声明
`produces = application/json`，因此内容协商由 `RequestMappingHandlerMapping` 在
**进入 Controller 之前**完成：不可接受的 `Accept` 直接得到 406，
既不执行用例，也不会残留 `ETag`、`Location` 等成功响应头（有集成测试断言表行数与版本不变）。

错误响应不包含异常类名、堆栈、SQL、表结构、数据库驱动信息，也不回显请求中的标题、描述或处理结论原文；
500 兜底响应的 `detail` 是固定文案「服务暂时不可用，请稍后重试」，异常信息只进服务端日志。

### 11.4 输入规范化与标识格式

- **文本规范化**：`title`、`description`、`requesterId`、`assigneeId`、`resolution` 在进入校验前
  先做首尾去空白（`strip`，覆盖 ASCII 空白与 `U+3000` 等 Unicode 空白），且 `null` 会被安全处理。
  「非空」与「最大长度」判定都基于**规范化之后**的值：`"  abc  "` 按 `abc` 判定，
  `"   "` 判定为空，`"  " + 超长内容 + "  "` 仍然 400。响应体回显的是规范化后的值。
- **工单标识**：路径参数 `{ticketId}` 只接受**规范的 36 位连字符 UUID**（大小写不敏感）。
  `1-1-1-1-1`、缺少连字符、含首尾空白等宽松写法一律 400 `INVALID_REQUEST`，
  不会被静默补齐或当成合法标识处理。
- **枚举查询参数不做规范化**：`status`、`category`、`priority`、`sortBy`、`direction` 必须与
  文档给出的取值**完全一致**（区分大小写、不接受首尾空白）。`status=new` 或 `" NEW"` 一律 400，
  而不是被静默纠正为 `NEW` —— 宽松纠正会让客户端永远发现不了自己的拼写问题。

### 11.5 列表与条件搜索

`GET /api/v1/tickets` 支持分页、排序与条件筛选。所有参数可选，**缺省即不过滤**；
多个筛选条件以 **AND** 组合。

| 参数 | 默认 | 取值与规则 |
| --- | --- | --- |
| `page` | `0` | 从 0 开始，不得为负 |
| `size` | `20` | 1～100 |
| `status` | — | `TicketStatus` 枚举名精确匹配 |
| `category` | — | `TicketCategory` 枚举名精确匹配 |
| `priority` | — | `TicketPriority` 枚举名精确匹配 |
| `requesterId` | — | `strip` 后精确匹配；提供但为空白或超过 64 字符 → 400 |
| `assigneeId` | — | 同上 |
| `keyword` | — | `strip` 后在 `title`、`description` 中做**大小写不敏感**的包含搜索；提供但为空白 → 400；最长 200 |
| `sortBy` | `updatedAt` | `createdAt`、`updatedAt`、`priority`、`status` |
| `direction` | `desc` | `asc`、`desc` |

响应：

```json
{
  "items": [ { "id": "…", "title": "…", "version": 1, "…": "…" } ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3,
  "hasNext": true,
  "hasPrevious": false,
  "sort": { "field": "updatedAt", "direction": "desc" }
}
```

- `items` **永不为 `null`**（空结果是 `[]`），每一项复用单条查询的 `TicketResponse`，含**正确的 `version`**；
- `totalElements` 是满足条件的总条数（`long`）；`totalPages` 亦按 `long` 推导，不会溢出；
- **越界页返回 200 与空 `items`**，并照常给出 `totalElements`/`totalPages`，**不是 404**；
- **列表响应不返回集合 ETag**（集合没有单一版本号）；
- `sort` 回显**实际生效**的取值，客户端不必自行推断默认值。

**排序规则**

1. `createdAt`、`updatedAt` 按真实时间排序；
2. `priority` 按业务顺序 `P1 → P2 → P3 → P4`（用 `CASE` 映射，不依赖字典序）；
3. `status` 按生命周期顺序 `NEW → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED`（同上；
   注意字典序是 `ASSIGNED, CLOSED, IN_PROGRESS, NEW, RESOLVED`，与业务顺序无关）；
4. 所有排序都追加 **`id ASC`** 作为稳定的最终排序键，保证并列行的分页不重不漏；
5. 排序字段与方向接受的是**白名单取值**，服务端只按枚举映射固定列名，
   客户端文本永远不会进入 SQL。

**关键字与通配符转义**

`keyword` 只做「包含」匹配（不是相关性排序）。为避免用户输入的 `%`、`_` 被当成 LIKE 通配符，
服务端以 `!` 为转义符并在 SQL 中声明 `ESCAPE '!'`：

| 输入 | 绑定到 SQL 的模式 | 含义 |
| --- | --- | --- |
| `登录` | `%登录%` | 包含「登录」 |
| `100%` | `%100!%%` | 包含字面量 `100%`（**不是**「以 100 开头」） |
| `under_score` | `%under!_score%` | 包含字面量 `under_score`（`_` 不是任意单字符） |
| `!` | `%!!%` | 包含字面量 `!` |

匹配是**大小写不敏感**的：数据库侧 `LOWER(列)`，绑定值在 Java 侧 `toLowerCase(Locale.ROOT)`。
模式本身是**参数绑定值**，不参与 SQL 结构拼接。

**分页模型的取舍（本阶段）**

本阶段采用 **offset 分页**（`page`/`size` + `COUNT(*)`），因为它能直接表达「第几页」「共几条」；
代价是深翻页时 `OFFSET` 仍需跳过前 N 行，且跨越多次请求的翻页过程中并发写入可能造成重复/跳行
（并列行已由 `id ASC` 兜底，单次请求内始终是自洽的全序）。
**cursor/keyset 分页与 PostgreSQL 全文检索、`pg_trgm` 均属于后续优化，本任务不实现**；
包含搜索当前是可移植的 `LIKE`，无法使用 B 树索引，即全表扫描 + 过滤。
完整论证见 [`docs/adr/0004-ticket-search-pagination.md`](docs/adr/0004-ticket-search-pagination.md)。

**单次响应内部一定自洽**：`totalElements` 与 `items` 由**同一个独立（`REQUIRES_NEW`）的只读
`REPEATABLE_READ` 事务**中的两条语句取得，因此不可能出现「总数 5 却返回 6 行」这类矛盾 ——
默认的 `READ_COMMITTED` 下每条语句各取一个新快照，**不足以保证**这一点。
`REQUIRES_NEW` 也不可省：默认的 `REQUIRED` 会加入调用方已有的事务，那时只读与隔离级别都不会生效；
代价是在已有事务中调用列表查询会额外占用一条数据库连接。写路径的事务定义与隔离级别保持不变。

## 十二、代码约束

- 不使用 Lombok。
- 不创建空的 Controller、Service、Repository、Entity 占位类。
- 不提前实现业务功能；不引入 Redis、MQ、鉴权或前端依赖；RAG 与向量存储**只按阶段引入**
  （RAG 1/6~3/6 已交付：上传、解析切片、Embedding + pgvector 业务表；不使用通用向量库抽象）。
- 不使用通配符版本；子模块不重复声明受 BOM 管理的版本。
- 不隐藏编译警告，不跳过测试；全部文件使用 UTF-8。

## 十三、后续阶段简述

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| FD-0001 | Maven 多模块骨架与版本基线 | ✅ 已完成 |
| FD-0002 | DeepSeek 接入与本地 Tool Calling 冒烟闭环 | ✅ 已完成 |
| FD-0003 | 工单核心领域模型与生命周期状态机 | ✅ 已完成 |
| FD-0004 | 工单应用用例、输入输出端口与乐观并发契约 | ✅ 已完成 |
| FD-0005 | JDBC 持久化适配器、Flyway 迁移与 Spring 装配 | ✅ 已完成 |
| FD-0006 | 工单 REST API、ProblemDetail 与 ETag 并发协议 | ✅ 已完成 |
| FD-0007 | 工单列表、分页、排序与条件搜索 | ✅ 已完成 |
| FD-0008 | 知识文档领域模型与安全上传链路（RAG 1/6） | ✅ 已完成 |
| FD-0009 | 文档解析与确定性切片（RAG 2/6） | ✅ 已完成（含 R1/R2 两轮修复：OOXML 类型验证、OCR 关闭、提取上限语义、持久化端口防线、OPC 关系证明与 Unicode 流状态） |
| FD-0010 | 切片 Embedding 与 pgvector 原子存储（RAG 3/6） | ✅ 已完成 |
| FD-0010-R1 | 索引链路修订：Key fail-fast、按 index 归位、依赖库日志关闭、failureCode 贯通、真实 JDBC 批处理 | ✅ 已完成 |
| FD-0010-R2 | provider 血缘校验（只允许规范值 dashscope）、Key 优先级说明修正、失败码契约措辞修正 | ✅ 已完成 |
| FD-0010-R3 | 统一 Embedding 顺序契约（协议层归位 vs 持久化层拒绝错配）、清理过期阶段说明 | ✅ 已完成（仅文档与注释） |
| FD-0011 | Query Embedding、pgvector 相似度检索与可审计引用结果（RAG 4/6） | ✅ 已完成 |
| FD-0011-R1 | 检索硬上限回归契约、行映射异常分类、空请求体契约、过期文档清理 | ✅ 已完成 |
| FD-0011-R2 | 检索端口错误分类收口（只允许 `KNOWLEDGE_RETRIEVAL_FAILURE`，其它错误码收敛为内部失败） | ✅ 已完成 |
| 后续 | RAG 5/6：基于引用结果生成答案（引用只来自检索结果） | 未开始 |
| 后续 | Rerank：对检索结果重排（需要区分「召回分」与「重排分」） | 未开始 |
| 后续 | 游标/keyset 分页；PostgreSQL 全文检索与 `pg_trgm`（混合检索） | 未开始 |
| 后续 | 孤立文件清理任务 | 未开始 |
| 后续 | Tool 体系扩展：面向工单与知识的工具注册 | 未开始 |
| 后续 | MCP：资产 MCP 服务与监控 MCP 服务的能力实现 | 未开始 |
| 后续 | Agent Graph：基于 Spring AI Alibaba Agent Framework 的多节点编排 | 未开始 |

## 十四、真实验证状态（重要）

| 项 | 状态 | 含义 |
| --- | --- | --- |
| H2 集成 / HTTP 集成 | ✅ 已执行 | 真实 Spring 上下文、真实 JDBC、真实 Flyway 迁移（H2 执行 V1~V5；V6 为 PostgreSQL 专用 pgvector 迁移）、真实并发线程、真实文件系统、真实 multipart 上传，以及真实 PDF/DOCX 解析（夹具按规范现场生成） |
| 本地 smoke（默认 profile） | ✅ 已执行 | 真实进程 + 真实 HTTP：multipart 上传、存储目录落盘校验（FD-0008）、文档解析与状态推进、伪装 XLSX/普通 ZIP 被拒（FD-0009 / R1）；向量化在默认环境关闭，索引接口 503（FD-0010）；**检索接口在同一进程返回 503 且不需要任何 Key**（FD-0011） |
| 索引链路修订证据（FD-0010-R1 / R2） | ✅ 已执行 | 真实嵌套 Spring 上下文：缺失/空/纯空白 Key 均启动失败、`provider` 非规范值（含 `openai`/大小写变体/前后空格）在创建适配器之前启动失败、假 Key 与 `postgres,deepseek,dashscope-embedding` 组合可完成装配（不经真实数据库与模型）；真实 `DashScopeEmbeddingModel` 指向未监听的本机端口，证明关闭 logger 后切片正文不进入日志（并把 logger 临时打开做反证）；H2 影子表上观测到真实的 `addBatch`/`executeBatch`（无逐条 `executeUpdate`）与跨批次回滚；8 线程真实竞争下只有一个请求进入 `INDEXING` |
| 检索链路证据（FD-0011 / R1） | ✅ 已执行 | 真实 Spring 上下文 + 真实 HTTP：完整成功 JSON、空 citations、非法字段 400（固定 detail）、**空请求体 400（同一条检索契约，且零端口调用）**、坏 JSON 保留全局契约、上游失败 502、内部失败 500、415/406、响应不含 query/向量/SQL/异常；真实用例服务上验证「先模型后数据库」「非法输入零端口调用」「关闭状态零模型零数据库」「行映射领域异常收敛为 500 而非 400」；JDBC 替身上验证 SQL 原样下发与 11 个参数绑定顺序、以及四种映射期失败（非十六进制摘要 / 领域异常 / 结果集读取失败 / 数据库异常）全部归类为 `KNOWLEDGE_RETRIEVAL_FAILURE`；配置 `max-top-k=21`、`max-query-code-points=2001` 在真实上下文启动失败；`PgVectorLiteral` 在土耳其语/德语 Locale 下仍输出点号小数点 |
| PostgreSQL | ⚠️ **NOT_RUN** | 本机无 PostgreSQL 服务与 Docker，**PostgreSQL 尚未验证**；pgvector 集成测试（Testcontainers，镜像固定为带扩展版本的 `pgvector/pgvector:0.8.6-pg16`）在无 Docker 时跳过（当前跳过 26 条：15 条索引写入 + 11 条相似度检索），报告标注 POSTGRESQL_PGVECTOR_IT=NOT_RUN |
| 真实 DashScope Embedding | ⚠️ **DASHSCOPE_LIVE=NOT_RUN** | 无 DASHSCOPE_API_KEY，**未对真实向量服务发起过任何请求**；全部自动化测试都不访问真实上游 |
| 真实 DeepSeek | ⚠️ **LIVE_SMOKE=NOT_RUN** | 无 `DEEPSEEK_API_KEY`，**未对真实模型发起过任何请求** |

> **本文档不宣称 PostgreSQL 或真实 DeepSeek 已验证。** 相关代码按标准 SQL 与 OpenAI 兼容协议编写、
> 预期可用，但在真实环境跑通之前不应当作已验证。

> Spring AI Alibaba 的 BOM 已在根 pom 中导入并锁定版本，但其 Agent Framework 制品尚未使用；
> 后续阶段接入时直接复用现有 BOM，不需要改动版本基线。

## 十五、知识文档上传（RAG 1/6）

设计取舍见 [`docs/adr/0005-knowledge-document-upload-storage.md`](docs/adr/0005-knowledge-document-upload-storage.md)。

本节描述的是 **FD-0008 那一阶段的边界**：只做「收进来 + 查得到」。
当时的范围里**不包含**文档解析与切片 —— 它们已在 **FD-0009 实现**，见第十六章；
本节也**不包含** Embedding、向量库与 RAG 检索，并且没有文档列表、下载、删除或版本更新接口。
换句话说：上传阶段的文档状态停留在 `UPLOADED`，把它推进到 `PARSED` 的接口属于 FD-0009。

### 15.1 接口表

统一前缀 `/api/v1/knowledge/documents`。

| 方法 | 路径 | 请求 | 成功响应 |
| --- | --- | --- | --- |
| POST | `/api/v1/knowledge/documents` | `multipart/form-data`：`title`（必填）+ `file`（必填） | 201 Created + `Location` |
| GET | `/api/v1/knowledge/documents/{documentId}` | 无 | 200 OK |

响应字段：`id`、`title`、`originalFilename`、`format`、`mediaType`、`sizeBytes`、`sha256`、
`status`、`version`、`createdAt`、`updatedAt`。

**响应里绝不出现 `contentKey`、磁盘路径、临时文件路径或存储根目录** ——
视图类型里根本没有这些字段（有反射测试锁定），因此这条约束不依赖响应组装时的纪律。

```json
{
  "id": "4fac368c-64ca-41ab-9ab8-a27502e814f6",
  "title": "季度运维报告",
  "originalFilename": "report.pdf",
  "format": "PDF",
  "mediaType": "application/pdf",
  "sizeBytes": 1024,
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "status": "UPLOADED",
  "version": 0,
  "createdAt": "2026-09-13T14:12:04.873200Z",
  "updatedAt": "2026-09-13T14:12:04.873200Z"
}
```

### 15.2 支持的文件类型与内容校验

| 格式 | 扩展名 | 内容校验 |
| --- | --- | --- |
| PDF | `.pdf` | 文件头必须是 `%PDF-` |
| DOCX | `.docx` | 必须是 ZIP 容器（本地文件头 `PK\x03\x04`）；**不解压、不解析正文** |
| Markdown | `.md` | 必须是合法 UTF-8，且不含 NUL |
| Text | `.txt` | 同上 |

- 扩展名**大小写不敏感**；
- `Content-Type` 为空或 `application/octet-stream` → 视为未声明，按扩展名 + 内容识别；
- 明确声明了不兼容的 `Content-Type`（例如 `image/png` 配 `report.pdf`）→ 415；
- 原始文件名先按 `/` 与 `\` 取最后一段（兼容 `C:\fakepath\file.txt`），
  领域层再拒绝任何含路径分隔符或控制字符的值；
- **原始文件名只作为元数据，绝不参与磁盘路径拼接**。

### 15.3 大小限制与流式处理

上传涉及**两层限制、三个配置属性**（应用层一个、容器层两个）：

| 层 | 配置 | 默认值 | 作用 |
| --- | --- | --- | --- |
| 应用层 | `flowdesk.knowledge.upload.max-size` | `20MB` | **有效上限**：按实际读取到的字节数判断 |
| 容器层 | `spring.servlet.multipart.max-file-size` | `20MB` | 早期拒绝单个文件（在进入 Controller 之前） |
| 容器层 | `spring.servlet.multipart.max-request-size` | `22MB` | 早期拒绝整个 multipart 请求，必须能容纳文件 + 边框与其它字段 |

**三个属性不允许冲突：应用启动时会强制校验**（`KnowledgeUploadLimitValidator`）：

- `max-file-size >= upload.max-size`，否则应用上限不可达（典型漂移：应用改成 25MB 而容器仍是 20MB，
  21MB 的上传会被容器提前拒绝，配置看起来「改成功了」却永远传不上去）；
- `max-request-size >= max-file-size + 1KB`，否则连「恰好达到文件上限」的请求都会被容器拒绝；
- 三者都必须为正；余量比较使用安全减法，因此接近 `Long.MAX_VALUE` 的配置也不会因为加法回绕而误判通过；
- 冲突时**启动失败并给出明确文案**，而不是在生产流量里表现为「明明配了 25MB 却传不上去」。

容器上限**大于**应用上限是允许的（应用层仍会精确拦住），因此不要求两者相等 —— 只要求容器不是更小的那个。

- 应用层按**实际读取到的字节数**限流，每次批量读取最多只多读 **1** 个字节
  （`max + 1` 是区分「恰好等于上限」与「超限」的最小代价），因此伪造的 `Content-Length`
  或错误的声明大小都无法让服务端白读数据；`skip` 同样受限，且不会绕过文件头/UTF-8/NUL 校验；
- 禁止 `MultipartFile.getBytes()`，禁止把整个文件载入内存：固定缓冲区边复制边计算 SHA-256；
- 实际大小为 0 时在**发布之前**拒绝；超限或校验失败时立刻停止读取并清理临时文件；
  `InputStream` 与内容源总是被关闭（**所有**路径，包括前置校验失败）；
- 真实容器测试同时覆盖两条路径：容器侧超限（`MaxUploadSizeExceededException` → 413）
  与应用侧超限（容器放行、应用按实际字节数拒绝），并验证非默认上限**确实可达**
  （1023 字节成功、1025 字节被应用层拒绝）。

### 15.4 存储布局与一致性

```
<flowdesk.knowledge.storage.root>/
├─ documents/<contentKey>      # 最终对象；contentKey = kdoc-<文档标识>，由服务端生成
└─ tmp/upload-*.part           # 临时文件；发布成功即消失，失败即清理
```

- 内容键由**服务端根据文档标识**派生，客户端无法影响；解析路径时还要通过字符集白名单与
  「必须仍在存储根目录内」的包含性检查；
- **发布是原子且「目标存在即失败」的**：优先使用硬链接（POSIX `link(2)` / Windows
  `CreateHardLink`，目标已存在时原子失败、永不替换、内容一次性可见）；文件系统不支持硬链接时退化为
  `CREATE_NEW` 占位锁 + 原子移动，占位锁把「检查目标 + 移动」变成互斥临界区。
  **不使用 `REPLACE_EXISTING`**，也**不把「先 exists 再 move」当作唯一防线**；
- 同一文档标识的并发上传最多只有一个成功；失败方既不会覆盖、也不会删除成功方的对象
  （20 轮并发测试覆盖两条发布路径）；
- 上传顺序固定为：校验命令与文件元数据 → 生成文档标识 → 流式写临时文件并算摘要 →
  原子发布 → 插入元数据 → 返回。**文件复制期间不持有数据库事务**；
- 补偿分三个阶段：内容尚未发布（由适配器清理临时文件）→ 内容已发布但元数据尚未确认写入
  （任意运行时失败都补偿删除**一次**，补偿失败只作为 suppressed 保留、不覆盖原始异常）→
  元数据已写入（**后续失败绝不删除内容**，否则会留下指向不存在内容的数据库记录）；
- **已知边界**：进程在「内容已发布」与「元数据写入」之间崩溃仍可能留下孤立文件，
  本阶段不实现清理任务（见 ADR 0005）。

### 15.5 数据与错误契约

`knowledge_documents` 表由 Flyway **V3** 建立：`content_key` 唯一，`sha256` 只建**普通索引**
（相同内容允许作为不同逻辑文档上传），并对 `size_bytes > 0`、`version >= 0`、
枚举取值、摘要长度与小写、时间链设有 CHECK 约束。

| 场景 | HTTP | code |
| --- | --- | --- |
| 标题、文件名等非法输入（含领域字段校验失败） | 400 | `INVALID_REQUEST` |
| 空文件 | 400 | `INVALID_REQUEST` |
| 超过大小限制（应用侧或容器侧 multipart 上限） | 413 | `DOCUMENT_TOO_LARGE` |
| 不支持的格式、扩展名/Content-Type/文件头不一致、非法 UTF-8 或含 NUL | 415 | `UNSUPPORTED_DOCUMENT_TYPE` |
| 文档不存在 | 404 | `KNOWLEDGE_DOCUMENT_NOT_FOUND` |
| 内容存储 / 元数据存储失败、快照损坏 | 500 | `INTERNAL_SERVER_ERROR` |

错误 `detail` 一律是固定安全文案：不含原始文件名、标题原文、内容键、本地路径、SQL、异常类名或堆栈。

## 十六、文档解析与确定性切片（RAG 2/6）

设计取舍见 [`docs/adr/0006-document-parsing-and-deterministic-chunking.md`](docs/adr/0006-document-parsing-and-deterministic-chunking.md)。

本阶段把「已上传的原始文件」变成「可用的纯文本切片」：
读取原文 → 安全解析 PDF/DOCX/MD/TXT → 规范化 → 确定性切片 → 原子保存 → 状态更新。
**不做** Embedding、向量库、检索增强，也**没有**「按 documentId + chunkIndex 直接读取切片」的接口：
切片内容只在服务端索引/检索链路内流转（向量化见第十七章，检索见第十八章）——
检索接口会返回**命中片段**，但只返回受 `topK` 与 `minScore` 限制的那几条。

### 16.1 接口表

| 方法 | 路径 | 请求 | 成功响应 |
| --- | --- | --- | --- |
| POST | `/api/v1/knowledge/documents/{documentId}/parse` | 无请求体；**必须**带 `If-Match` | 200 OK + `ETag` |

- **没有请求体**：调用方无法影响解析器选择、切片参数或存储位置，这些全部由服务端配置决定；
- **解析是同步的**：返回时切片已经落库，因此是 200 而不是 202；
- 响应字段：`documentId`、`title`、`status`、`version`、`chunkCount`、`parsedAt`
  （成功时不含 `failureCode`；失败走错误契约）。

```powershell
# 1) 上传
curl.exe -s -X POST http://localhost:8080/api/v1/knowledge/documents `
  -F "title=季度运维报告" -F "file=@report.pdf"
# → 201, status=UPLOADED, version=0

# 2) 解析（If-Match 必须是当前版本）
curl.exe -s -i -X POST http://localhost:8080/api/v1/knowledge/documents/<id>/parse `
  -H "If-Match: `"0`""
# → 200 OK, ETag: "2"
# {"documentId":"<id>","title":"季度运维报告","status":"PARSED",
#  "version":2,"chunkCount":3,"parsedAt":"2026-09-14T05:12:31.482Z"}
```

```json
{
  "type": "urn:flowdesk:problem:document-parse-failed",
  "title": "文档无法解析",
  "status": 422,
  "detail": "文档内容已损坏或与声明的格式不符",
  "instance": "/api/v1/knowledge/documents/4fac368c-.../parse",
  "code": "DOCUMENT_PARSE_FAILED",
  "failureCode": "CORRUPTED_DOCUMENT"
}
```

### 16.2 状态机与 CAS 时序

```
UPLOADED ──claim──▶ PARSING ──complete──▶ PARSED
PARSE_FAILED ──claim──┘        └──fail────▶ PARSE_FAILED
```

| 步骤 | 动作 | 版本变化 |
| --- | --- | --- |
| ① 读取 | `findById` | — |
| ② 版本比对 | 与 `If-Match` 不一致 → **412**（无任何写入） | — |
| ③ 领取 | `SELECT ... FOR UPDATE` + `UPDATE ... WHERE id AND version` | **+1** |
| ④ 事务外 | 读原文 → 解析 → 规范化 → 切片 | — |
| ⑤ 完成 | 删除旧切片 + 插入新切片 + 置 `PARSED`（同一事务） | **+1** |
| ⑥ 失败 | 把文档 CAS 成 `PARSE_FAILED`（可重试） | **+1** |

- **领取是并发闸门**：CAS 保证同一文档只有一个请求能从 `UPLOADED`/`PARSE_FAILED` 进入 `PARSING`
  （8 线程并发测试断言「恰好一个成功」）；
- `PARSED` 不能被重复解析（409），`PARSING` 不能被二次领取（409）；
- `PARSE_FAILED → PARSING` 是有意放开的：修好原始文件后可以用最新版本重试，
  不必重新上传（重新上传会得到新的文档标识）；
- 状态与解析字段严格配对：`PARSED` 只带 `parsedAt`、`PARSE_FAILED` 只带 `parseFailedAt` + 失败码、
  `UPLOADED`/`PARSING` 两者都不带 —— 领域层与数据库 CHECK 双重强制。

### 16.3 事务边界

- **解析与切片不在任何数据库事务里**：事务只包住「领取」与「完成」两个短操作；
- **「完成解析」是一个原子端口方法**（`KnowledgeDocumentChunkStore.completeParsing`）：
  校验行仍是 `PARSING` 且版本匹配 → 删除旧切片 → 插入新切片 → 更新为 `PARSED` 并加版本 →
  在同一事务内重新读取。任一步失败**整体回滚**（测试用主键冲突与版本错配两条路径验证
  「文档仍是 `PARSING`、版本未变、切片为零」）；
- 事务边界属于适配器，应用层不认识事务；JDBC 异常在适配器内被映射为
  `METADATA_STORAGE_FAILURE`，不向应用层泄漏 Spring/JDBC 类型。

**完成解析的入参防线**（在开启事务与执行任何 SQL **之前**）：聚合必须是 `PARSED`、
切片列表非空且不含 `null`、每个切片的 `documentId` 必须等于目标文档、
`chunkIndex` 必须从 0 严格连续递增；非法输入以既有的稳定内部错误 `KNOWLEDGE_INTERNAL_ERROR`
拒绝，**不静默纠正、零写入**（测试用「语句计数数据源」断言一条 SQL 都没下发）。

**通用 `update` 的状态矩阵**（FD-0009-R1 收紧，避免绕过状态机）：

| 数据库中的当前状态 | 允许写入 | 说明 |
| --- | --- | --- |
| `UPLOADED` | `PARSING` | 领取解析 |
| `PARSE_FAILED` | `PARSING` | 修复后重试 |
| `PARSING` | `PARSE_FAILED` | 失败补偿 |
| `PARSED` | **无** | 只能由 `completeParsing` 原子端口落库 |

版本不匹配仍是 412 `KNOWLEDGE_DOCUMENT_VERSION_CONFLICT`；版本相同但转换非法用现有的
409 状态错误拒绝（未新增任何公开错误码）。

### 16.4 解析安全边界

| 边界 | 做法 |
| --- | --- |
| 路径逃逸 | 只接受内容键；字符集白名单 + 解析结果必须在存储根目录内；只读普通文件、**不跟随符号链接** |
| DOCX 类型混淆 | **ZIP 初筛 + OOXML 包类型验证**（R1 加入，R2 收紧到 OPC 级别）：`[Content_Types].xml` 的根必须是 OPC 内容类型命名空间下的 `Types`，其对 `/word/document.xml` **生效**的内容类型必须是 WordprocessingML；`_rels/.rels` 的根必须是 OPC 关系命名空间下的 `Relationships`，其中必须有**恰好一个** `officeDocument` 关系、且是**内部**关系（`TargetMode` 缺失或精确等于 `Internal`）、`Target` 区分大小写地精确等于 `word/document.xml`（容忍单个前导 `/`）。XLSX / PPTX / DOCM / XPS / 普通 ZIP / 命名空间或层级造假 / 大小写不符一律 `UNSUPPORTED_DOCUMENT_CONTENT` |
| 伪造格式 | PDF 必须 `%PDF-`、DOCX 必须 `PK\x03\x04`（初筛）；不符 → `UNSUPPORTED_DOCUMENT_CONTENT` |
| 损坏文档 | 容器读不出来、已声明的主文档部件缺失、正文无法解析 → `CORRUPTED_DOCUMENT`（按异常**类型**映射，不解析异常文本） |
| 加密文档 | → `ENCRYPTED_DOCUMENT`（测试用现场构造的加密 PDF 真实验证） |
| OCR | PDF 显式配置 **`OCR_STRATEGY.NO_OCR`**：不依赖机器上是否安装 Tesseract，解析结果与资源消耗不随部署环境变化；构造后不再修改这份共享配置 |
| XXE / 外部实体 | 只用本地解析器；包元数据用「禁用 DOCTYPE 与外部实体」的安全 XML 解析器；测试用「正文引用本地文件的 DOCX」验证本地文件内容绝不进入提取文本 |
| 资源耗尽 | 提取文本按 **code point** 限量（默认 1,000,000），超限**立即中断**；切片数量上限 5000；DOCX 类型验证最多读包元数据的前 1 MiB，落盘用固定 8 KiB 缓冲（**全程没有 `readAllBytes`**） |
| 非法 UTF-8 | 文本格式用 `CodingErrorAction.REPORT` 的严格解码器（默认的替换行为会静默产生 `U+FFFD`） |
| 信息泄漏 | 响应里没有原文、切片内容、内容键、路径、解析器名称或异常文本 |

- 解析器由**数据库里保存的格式**直接指定（PDF → PDFBox、DOCX → POI OOXML），不做二次探测；
- **类型判定只依据包内容**：不使用原始文件名、扩展名或客户端声明的 Content-Type，
  也不靠往 Tika `Metadata` 里写一个「它是 DOCX」的声明来充当验证；
- Markdown/Text 不经过解析框架：严格 UTF-8 解码 + 去掉 BOM；
- DOCX 路径会先把内容流式写入一份受控临时文件（固定缓冲区，`finally` 删除），
  这样才能在**正文提取之前**完成包类型验证；调用方的流始终由调用方关闭；
- 解析器**不关闭**调用方的流（`nonClosing` 包装），流的生命周期由应用服务负责。

### 16.5 规范化、切片算法与参数

规范化（切片之前统一执行）：`CRLF`/`CR` → `LF`、Unicode **NFC**、3 个以上连续换行折叠为 2 个、首尾 `strip()`。

切片按 **Unicode code point** 处理（绝不切断代理对），边界优先级：

1. 段落边界（换行）→ 2. 中英文句末标点（`。！？；.!?;`）→ 3. 普通空白 → 4. 硬切；

回溯窗口限制在后半段（`chunkSize/2` 起），避免因为开头附近的一个句号切出极短片；
下一片从 `end - overlap` 开始，并**显式保证严格前进**（因此 `overlap = chunkSize - 1` 也不会死循环）。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `flowdesk.knowledge.chunking.chunk-size` | `1000` | 单片最大 code point 数（硬上限 2000，由列容量反推） |
| `flowdesk.knowledge.chunking.overlap` | `150` | 相邻片重叠的 code point 数，必须 `< chunk-size` |
| `flowdesk.knowledge.chunking.max-chunks` | `5000` | 单文档切片数上限，超过即整体失败（**不写部分切片**） |
| `flowdesk.knowledge.chunking.max-extracted-code-points` | `1000000` | 提取文本上限，解析中一旦超过立即中断 |

四个属性在**启动期**校验（组合溢出用 `long` 计算），不合法就让应用启动失败。

**提取上限覆盖「最终输出」的每一个 code point**（FD-0009-R1 修正，R2 补齐状态语义）：
普通字符、`ignorableWhitespace`、块级元素之间自动补的结构换行、跨 SAX 回调与跨 8192 char 解码缓冲区的
代理对，全部经过同一个计数器；不变量是 `text().codePointCount(0, text().length()) <= 上限`。
另外：**只有输入流的第一个 code point 是 `U+FEFF` 时才被丢弃且不占配额**（第二个 `U+FEFF` 就是普通内容），
空回调不会消耗「首字符尚未判断」这一状态，结构换行会结束它；
写入顺序与输入完全一致（先落高代理、再写结构换行、最后是低代理），`text()` 是无副作用的观察；
超限时在追加越界字符**之前**抛出，不会先拼出完整文本再回头检查。

### 16.6 数据与错误契约

Flyway **V4** 在 `knowledge_documents` 上新增 `parsed_at`、`parse_failed_at`、`parse_failure_code`，
并重建状态 CHECK 为四个取值；同时新建 `knowledge_document_chunks`：

- 主键 `(document_id, chunk_index)`，外键指向 `knowledge_documents(id)` **ON DELETE CASCADE**；
- CHECK：序号非负、内容非空、code point 计数为正、摘要为 64 位小写十六进制；
- 索引 `idx_knowledge_document_chunks_document` 支持按文档分页读取。

| 场景 | HTTP | code |
| --- | --- | --- |
| 解析命令不合法（版本为负等） | 400 | `INVALID_REQUEST` |
| 缺少 `If-Match` | 428 | `PRECONDITION_REQUIRED` |
| `If-Match` 格式非法 | 400 | `INVALID_IF_MATCH` |
| 文档不存在 | 404 | `KNOWLEDGE_DOCUMENT_NOT_FOUND` |
| 当前状态不允许解析（已在解析/已解析完成） | 409 | `KNOWLEDGE_DOCUMENT_NOT_PARSABLE` |
| `If-Match` 已过期 / 领取 CAS 失败 | 412 | `KNOWLEDGE_DOCUMENT_VERSION_CONFLICT` |
| 文档损坏 / 加密 / 与声明类型不符 / 无可提取文本 | 422 | `DOCUMENT_PARSE_FAILED` + `failureCode` |
| 提取文本超限 | 413 | `DOCUMENT_TOO_LARGE` + `failureCode=EXTRACTED_TEXT_TOO_LARGE` |
| 切片数量超限 | 413 | `DOCUMENT_TOO_MANY_CHUNKS` + `failureCode=TOO_MANY_CHUNKS` |
| 解析器内部失败、原文不可读、结果写入失败 | 500 | `INTERNAL_SERVER_ERROR` |

`failureCode` 是 `KnowledgeParseFailureCode` 的枚举名（稳定契约，不是自由文本）：
`CORRUPTED_DOCUMENT`、`ENCRYPTED_DOCUMENT`、`UNSUPPORTED_DOCUMENT_CONTENT`、`EMPTY_EXTRACTED_TEXT`、
`EXTRACTED_TEXT_TOO_LARGE`、`TOO_MANY_CHUNKS`、`PARSER_FAILURE`。

### 16.7 已知边界

1. **`PARSING` 悬挂**：进程在解析期间崩溃会让文档停在 `PARSING`，既不会自动重试也不能被再次领取。
   本阶段**不实现**恢复扫描任务，需要人工把状态改回 `UPLOADED`/`PARSE_FAILED`，或由后续任务实现超时回收；
2. **同步解析**：请求会一直等到解析完成，极大文档可能触及客户端/代理超时（本阶段不引入异步任务与 MQ）；
3. **切片参数变更不会回填历史数据**：改配置后需要重新解析才会生效；
4. 解析产物的读取端口（`countChunks` / `findChunks`）已经就位，但**没有公开 HTTP 接口** ——
   切片内容仅供服务端索引/检索链路使用，不通过解析响应公开。

## 十七、切片 Embedding 与 pgvector 存储（RAG 3/6）

设计取舍见 [`docs/adr/0007-dashscope-embedding-pgvector-storage.md`](docs/adr/0007-dashscope-embedding-pgvector-storage.md)。

本阶段把「已解析的切片」变成「可检索的向量」：
**分批读取切片 → 调用 DashScope `text-embedding-v4` 生成 1024 维向量 → 逐批校验 →
单事务替换向量并把文档推进为 `INDEXED`**。
**FD-0010 当时不做**（阶段范围，其中多项已由后续任务交付）：向量相似度查询与 Query Embedding
（**已由 FD-0011 交付，见第十八章**）、Rerank 与 RAG 问答（**仍未实现**）。

### 17.1 完整链路与状态机

```
上传 ──▶ UPLOADED ──领取解析──▶ PARSING ──完成──▶ PARSED ──领取索引──▶ INDEXING ──完成──▶ INDEXED
                                   │                      │                      │
                                   └──失败──▶ PARSE_FAILED└──失败──▶ INDEX_FAILED┘
                    PARSE_FAILED ──重新领取解析──▶ PARSING
                    INDEX_FAILED ──重新领取索引──▶ INDEXING
```

| 步骤 | 动作 | 版本 | 事务 |
| --- | --- | --- | --- |
| ① 校验命令 | 非法输入在任何端口调用前拒绝 | — | 无 |
| ② 开关检查 | 未启用向量化 → **503**，且不读仓储 | — | 无 |
| ③ 读取 + 版本比对 | `If-Match` 过期 → 412 | — | 无 |
| ④ 状态检查 | 只允许 `PARSED`/`INDEX_FAILED`，否则 409 | — | 无 |
| ⑤ 领取 | `markIndexing` + CAS 更新为 `INDEXING` | **+1** | 短事务 |
| ⑥ 分批生成 | 每批 ≤10 条读切片 → 调模型 → 逐批校验 | — | **无事务** |
| ⑦ 完成 | 校验状态/版本/切片摘要 → 替换向量 → 置 `INDEXED` | **+1** | 短事务 |
| ⑧ 失败补偿 | 把文档 CAS 成 `INDEX_FAILED`（带稳定失败码） | **+1** | 短事务 |

- 只有把 ⑥ 放在事务外，才不会有「用上游延迟占用数据库连接」的问题；
- 只有把 ⑦ 做成一个原子端口，才不会出现「文档 INDEXED 但向量只写了一半」；
- 版本冲突与「状态不允许索引」**不写失败态**（当前请求无权给别人盖失败戳）。

### 17.2 模型、维度与批次

| 项 | 值 | 说明 |
| --- | --- | --- |
| 提供方 | `dashscope`（阿里云百炼） | DeepSeek 只负责 Chat/Agent，Embedding 走 DashScope |
| provider 取值 | **只允许逐字 `dashscope`** | 会被持久化的**血缘字段**；启用时其他取值（含大小写变体与前后空格）一律启动失败（FD-0010-R2，见 17.8） |
| 模型 | `text-embedding-v4` | 由 `flowdesk.knowledge.embedding.model` 显式指定 |
| 维度 | `1024` | 与 `vector(1024)` 列、领域不变量三处一致 |
| 语义 | `document` | 查询侧用 `query`（**已由 FD-0011 实现**，见 18.1） |
| 单批上限 | `10` | `flowdesk.knowledge.embedding.batch-size`，配置校验拒绝 >10 |
| 维度探测 | **禁止** | 适配器从不调用 `EmbeddingModel.dimensions()`（它可能发起远端请求） |
| 结果归位 | 按 `Embedding.getIndex()` | **不**信任响应列表顺序（FD-0010-R1，见 17.8） |
| 数据库写批次 | `100`（上限 1000） | `JdbcKnowledgeDocumentEmbeddingStore.DEFAULT_WRITE_BATCH_SIZE`，真正的 `executeBatch()` |

响应校验（任何一条不满足即 `INVALID_EMBEDDING_RESPONSE`，且**不写入任何向量**）：
结果数量与请求一致、`index` 存在且落在 `0..n-1`、无重复、无缺失（按 index 归位后逐条校验）、
每条恰好 1024 维、不含 `null`/`NaN`/`±Infinity`、不是全零。

### 17.3 Profile 与启动方式

| 环境 | 启动方式 | 结果 |
| --- | --- | --- |
| 默认（H2） | `.\mvnw.cmd -pl flowdesk-bootstrap spring-boot:run` | 可启动；**不创建 EmbeddingModel**、无网络请求、无需 Key；索引接口 503 |
| 仅 DeepSeek | `--spring.profiles.active=deepseek` | 只有 ChatModel，仍然没有 EmbeddingModel，**不要求 DashScope Key** |
| 完整生产组合 | `--spring.profiles.active=postgres,deepseek,dashscope-embedding` | Chat 走 DeepSeek、Embedding 走 DashScope、向量落 pgvector |
| 只启用向量化但没有 PostgreSQL | `--spring.profiles.active=dashscope-embedding` | **启动失败**并给出明确的配置错误（不会静默退回 H2 或内存向量库） |
| 只启用向量化但 Key 缺失/空/纯空白 | `--spring.profiles.active=postgres,dashscope-embedding` | **启动失败**，错误信息只提 `DASHSCOPE_API_KEY` 与配置名（FD-0010-R1） |
| provider 不是规范值 `dashscope`（如 `openai`） | 同上再加 `--flowdesk.knowledge.embedding.provider=openai` | **启动失败**，且在创建任何向量适配器之前就失败；错误信息只说明「当前版本只支持 dashscope」，不回显原值（FD-0010-R2） |

```powershell
$env:DASHSCOPE_API_KEY = '<key>'
$env:FLOWDESK_DB_URL = 'jdbc:postgresql://localhost:5432/flowdesk'
$env:FLOWDESK_DB_USERNAME = '<user>'
$env:FLOWDESK_DB_PASSWORD = '<password>'
java -jar flowdesk-bootstrap/target/flowdesk-bootstrap-0.1.0-SNAPSHOT.jar `
     --spring.profiles.active=postgres,deepseek,dashscope-embedding
```

仓库中**不保存**任何真实 API Key、数据库密码或 Token：`api-key` 只读取 `${DASHSCOPE_API_KEY:}`
（带空默认值是刻意的，见 17.8）。Key 的**真实值**由启动期校验读取并拒绝空值，
因此「清掉环境变量后应用仍然启动」不会再发生。

Key 的优先级与依赖 1.1.2.2 的真实行为一致 —— **模态级优先、通用兜底**：

1. `spring.ai.dashscope.embedding.api-key` 有文本 → 用它；
2. 否则回退 `spring.ai.dashscope.api-key`（profile 把它绑定到 `${DASHSCOPE_API_KEY:}`）；
3. 依赖库只在选出的配置值为 `null` 时才尝试 `AI_DASHSCOPE_API_KEY` 环境变量；
   **本项目不承认这条来源**（否则「Key 从哪来」无法被启动期校验确定性地看到），
   缺失、空串与纯空白一律由项目校验器用自己的稳定异常拒绝。

启用向量化时 `flowdesk.knowledge.embedding.provider` 必须**逐字**写成 `dashscope`：
`null`、空白、`openai`、`DashScope`、`" dashscope "` 都会启动失败 ——
它是会被持久化到文档与向量表的**血缘字段**，接受别的取值等于把 DashScope 生成的向量
标注成别的来源。见 17.8。

`dashscope-embedding` profile 同时把依赖库的日志关掉：

```yaml
logging:
  level:
    # DashScopeEmbeddingModel 会把切片正文（request.getInstructions()）写进日志，
    # 且发生在我们的适配器捕获异常之前，因此必须由配置关掉它（FD-0010-R1）
    com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel: "OFF"
```

### 17.4 接口与 curl 示例

| 方法 | 路径 | 请求 | 成功响应 |
| --- | --- | --- | --- |
| POST | `/api/v1/knowledge/documents/{documentId}/index` | 无请求体；**必须**带 `If-Match` | 200 OK + `ETag` |

```powershell
# 上传 → 解析 → 索引（同步，返回时向量已落库）
$doc = curl.exe -s -X POST http://localhost:8080/api/v1/knowledge/documents `
  -F "title=季度运维报告" -F "file=@report.docx" | ConvertFrom-Json

curl.exe -s -X POST "http://localhost:8080/api/v1/knowledge/documents/$($doc.id)/parse" `
  -H "If-Match: `"0`""
# → 200 OK, ETag: "2", status=PARSED

curl.exe -s -i -X POST "http://localhost:8080/api/v1/knowledge/documents/$($doc.id)/index" `
  -H "If-Match: `"2`""
# → 200 OK, ETag: "4"
# {"documentId":"...","title":"季度运维报告","status":"INDEXED","version":4,"chunkCount":3,
#  "embeddingProvider":"dashscope","embeddingModel":"text-embedding-v4",
#  "embeddingDimensions":1024,"indexedAt":"2026-09-14T10:12:31.482Z"}
```

响应里**没有**切片正文、向量数组、内容键、本地路径、API Key 或上游响应。
`GET /api/v1/knowledge/documents/{id}` 同步扩展了 `parsedAt`/`indexedAt`/`embeddingProvider`/
`embeddingModel`/`embeddingDimensions`，并使用 `NON_NULL`：不相关状态不输出空字段。

| 场景 | HTTP | code |
| --- | --- | --- |
| 索引命令不合法 | 400 | `INVALID_REQUEST` |
| 缺少 / 非法 `If-Match` | 428 / 400 | `PRECONDITION_REQUIRED` / `INVALID_IF_MATCH` |
| 文档不存在 | 404 | `KNOWLEDGE_DOCUMENT_NOT_FOUND` |
| 状态不允许索引 | 409 | `KNOWLEDGE_DOCUMENT_NOT_INDEXABLE` |
| 版本过期 / 领取 CAS 失败 | 412 | `KNOWLEDGE_DOCUMENT_VERSION_CONFLICT` |
| 默认环境未启用向量化 | 503 | `KNOWLEDGE_EMBEDDING_DISABLED` |
| 上游向量服务失败（超时/限流/5xx） | 502 | `EMBEDDING_PROVIDER_ERROR` + `failureCode=EMBEDDING_PROVIDER_FAILURE` |
| 模型响应非法（乱序/重复/越界/缺 index/数量不符） | 500 | `INTERNAL_SERVER_ERROR` + `failureCode=INVALID_EMBEDDING_RESPONSE` |
| 切片数量或摘要与库中不一致 | 500 | `INTERNAL_SERVER_ERROR` + `failureCode=CHUNK_DATA_INVALID` |
| 向量写入 / 批处理 / 事务提交失败 | 500 | `INTERNAL_SERVER_ERROR` + `failureCode=VECTOR_STORAGE_FAILURE` |

**失败码的准确契约**（FD-0010-R2 修正，不再宣称「永远一致」）：

- **成功领取索引且补偿 CAS 成功**时，数据库里的 `index_failure_code` 与抛出的
  `DocumentIndexingException.failureCode` 一致，HTTP 响应里的 `failureCode` 也是同一个值；
- **补偿 CAS 失败**时，根异常与 HTTP `failureCode` **保持不变**，补偿异常只作为 suppressed 附加；
  此时数据库可能仍停在 `INDEXING`，也可能已被并发请求改动 —— 这一条**不保证**数据库与响应一致；
- **领取之前**（读文档、领取 CAS）的读取/存储失败**不写失败态**：文档还没被领取，
  不存在可以安全持久化的 `INDEX_FAILED`，响应是项目自己的错误码（可能没有 `failureCode`）；
- 版本冲突 / 状态不允许索引 / 文档不存在：仍然**不得**写失败态（412 / 409 / 404）。

响应始终是固定安全文案：不含 SQL、连接串、上游响应体、切片正文或异常类名。

### 17.5 数据库结构（V5 + V6）

**V5（通用，H2 与 PostgreSQL 都执行）**：给 `knowledge_documents` 增加
`index_started_at`、`indexed_at`、`index_failed_at`、`index_failure_code`、
`embedding_provider`、`embedding_model`、`embedding_dimensions`，并把状态 CHECK 扩展到七个取值；
同时增加「状态与索引字段一致」「维度必须是 1024」「完整时间线」三类 CHECK。

**V6（PostgreSQL 专用，`db/postgresql-migration`）**：`CREATE EXTENSION IF NOT EXISTS vector`，
并创建业务向量表：

```sql
knowledge_document_chunk_embeddings (
    document_id UUID, chunk_index INTEGER, chunk_sha256 CHAR(64),
    embedding vector(1024), provider, model, embedding_dimensions, created_at,
    PRIMARY KEY (document_id, chunk_index),
    FOREIGN KEY (document_id, chunk_index)
        REFERENCES knowledge_document_chunks (document_id, chunk_index) ON DELETE CASCADE,
    CHECK (embedding_dimensions = 1024),
    CHECK (vector_dims(embedding) = embedding_dimensions)
);
CREATE INDEX … USING hnsw (embedding vector_cosine_ops);
```

- `application-postgres.yml` 的 Flyway locations = `classpath:db/migration` + `classpath:db/postgresql-migration`；
- 默认 H2 **只执行 V1~V5**，不会尝试 `CREATE EXTENSION` 或 `vector(1024)`；
- **不使用** Spring AI 的 `PgVectorStore` 自动建表，也不创建通用 `vector_store` 表：
  向量表必须是业务表，才能有「切片外键 + 摘要证明 + 状态 CAS + 同一事务完成」的语义。

### 17.6 原子性、并发与失败补偿

- **向量与状态同事务**：完成阶段在**一个短事务**里校验「文档仍是 `INDEXING` 且版本匹配」→
  校验向量与库中切片**数量/序号/摘要**完全一致 → 删除旧向量 → 分批写入新向量 →
  CAS 更新为 `INDEXED` 并版本 +1 → 同事务重新读取。任一步失败**整体回滚**，
  包括**已经执行过的前几个写批次**（集成测试用「第 2 个批次失败」「所有批次成功后 CAS 失败」
  与「摘要被改坏」三条路径验证）；
- **两层顺序语义（FD-0010-R3 澄清）**：
  - **协议层（`DashScopeKnowledgeEmbeddingAdapter`）按 `Embedding.getIndex()` 归位**：
    上游响应里每条向量都带着供应商声明的 `index`，适配器据此把它放回**原请求位置**，
    从而保证端口契约「返回列表与输入文本一一对应、且已恢复为请求顺序」。
    这属于**协议映射**（把上游字段翻译成本端顺序），**不是**猜测、也**不是**静默纠错；
    `index` 为 `null`、为负、越界、重复、缺失或数量不一致时一律拒绝
    （`INVALID_EMBEDDING_RESPONSE`），绝不按响应列表的先后顺序去猜。
  - **持久化层（`JdbcKnowledgeDocumentEmbeddingStore`）拒绝对业务记录重排**：它拿到的是
    已经绑定好的 `(documentId, chunkIndex, chunkSha256, vector)`，因此错序、断号、归属错误、
    摘要变化一律**拒绝并整体回滚**，绝不按位置重排或跳过不匹配的行 —— 到了这一层，
    顺序语义已经确定，任何偏差都是业务数据错配，只能失败；
- **并发**：领取是 CAS（行锁 + 版本条件更新），同一文档同时只有一个索引请求能成功；
  并发证据有两处：H2 上的 8 线程真实竞争（`KnowledgeDocumentIndexingClaimConcurrencyTest`）
  与 PostgreSQL 集成测试里的等价用例（无 Docker 时跳过）；
- **补偿**：失败后把文档 CAS 成 `INDEX_FAILED`（稳定失败码）；补偿失败只作为 suppressed 附加，
  绝不覆盖根因；版本冲突与状态冲突不写失败态；
- **切片分页读取**：每批最多 10 条，内存中只累积已校验的 1024 维向量，不会一次性加载整篇切片文本。

### 17.7 已知边界

1. **`INDEXING` 悬挂**：进程在索引期间崩溃会让文档停在 `INDEXING`，本阶段不实现超时回收；
2. **同步索引**：请求会一直等到全部批次完成并落库，极大文档可能触及客户端/代理超时；
3. **`INDEXED` 不可重建**：主动重建索引不在本阶段范围内（重新索引需人工退回 `INDEX_FAILED`，
   届时旧向量会被替换）；
4. **批次串行**：分批串行调用上游，未做并发化；
5. **依赖库日志被整体关闭**：`DashScopeEmbeddingModel` 的 logger 是 `OFF`，
   因此该类自己的诊断信息（含上游错误细节）不会出现在日志里；
   替代品是我们适配器里「只记失败码 + 异常类名」的一条 ERROR，原始异常仍作为 cause 保留在服务端；
6. **真实上游与真实数据库**：`DASHSCOPE_LIVE=NOT_RUN`、`POSTGRESQL_PGVECTOR_IT=NOT_RUN`
   （本机没有 Key、没有 PostgreSQL 与 Docker），自动化测试全部使用替身或 H2；
   pgvector 相关断言由 Testcontainers 测试覆盖，无 Docker 时跳过。

### 17.8 FD-0010-R1 与 FD-0010-R2：缺口的修复

**FD-0010-R1（五个缺口）**

| # | 缺口 | 修复位置 |
| --- | --- | --- |
| 1 | 清掉 `DASHSCOPE_API_KEY` 后应用仍能启动（只检查了 Bean 是否存在） | `application-dashscope-embedding.yml` 改为 `${DASHSCOPE_API_KEY:}`；`KnowledgeEmbeddingConfigurationValidator.requireDashScopeApiKey(...)` 读**真实配置值**并拒绝缺失/空/纯空白；`KnowledgeConfiguration.knowledgeEmbeddingPort` 在解析模型前再校验一次 |
| 2 | 假定 `response.getResults()` 的顺序就是请求顺序 | `DashScopeKnowledgeEmbeddingAdapter.orderByIndex(...)`：按 `Embedding.getIndex()` 归位；index 为 `null`、为负、越界、重复、缺失或数量不一致一律 `INVALID_EMBEDDING_RESPONSE` |
| 3 | 依赖库把切片正文写进日志 | profile 里 `com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel: "OFF"`；自有适配器改为「只记失败码 + 异常类名」 |
| 4 | 真实向量写入失败在响应里没有 `failureCode` | 适配器抛 `DocumentIndexingException(VECTOR_STORAGE_FAILURE)` / `(CHUNK_DATA_INVALID)`；用例层对 `DocumentIndexingException` 原样保留，不再包装成 `METADATA_STORAGE_FAILURE` |
| 5 | 循环 `executeUpdate()` 不是批处理 | `JdbcKnowledgeDocumentEmbeddingStore` 改用 `JdbcTemplate.batchUpdate(sql, collection, writeBatchSize, setter)`，批大小有界、与删除旧向量和状态 CAS 同事务 |

**FD-0010-R2（三个缺口）**

| # | 缺口 | 修复位置 |
| --- | --- | --- |
| 1 | `provider=openai` 也能启动 → 实际由 DashScope 生成向量却把来源标成 openai（**血缘错配**） | `KnowledgeEmbeddingConfigurationValidator.requireSupportedProvider(...)`：启用时要求 provider **逐字**等于 `dashscope`（不 trim、不改大小写）；由启动期校验 Bean 与 `knowledgeEmbeddingPort` 共同调用，两者都在创建适配器之前失败 |
| 2 | Key 优先级说明写反（写成了「通用优先、模态兜底」） | 代码改为**模态级优先、通用兜底**（与 `DashScopeConnectionUtils` 一致），并新增 `resolveApiKey(...)` 直接断言优先级方向；Javadoc / README / ADR 测试命名同步修正 |
| 3 | 「数据库 `index_failure_code` 与响应 `failureCode` 永远一致」的绝对表述不成立 | 用例层 Javadoc、README 17.4/17.6、ADR 改为按「补偿成功 / 补偿失败 / 领取之前 / 冲突类」四种情形分别陈述；补偿失败测试改为断根异常不变 + suppressed 存在，不再暗示数据库已写入同一失败码 |

**FD-0010-R3（顺序契约与过期阶段说明，仅文档/注释修订）**

| # | 问题 | 处理 |
| --- | --- | --- |
| 1 | `KnowledgeEmbeddingPort` Javadoc 写成「不做按索引字段重排，上游错序就应当失败」，与 R1 之后按 `Embedding.getIndex()` 归位的实现相反 | 端口契约改为「返回列表与输入文本一一对应、且**已恢复为请求顺序**」，并写明**基础设施适配器可以并且应该**按供应商声明的稳定 `index` 归位；这属于**协议映射**而非猜测；非法 index 与数量不符必须拒绝；业务合法性（数量/维度/NaN/Infinity/全零）仍由应用层统一校验 |
| 2 | README 17.6 与 ADR 0007 笼统写「不做任何纠错／不按位置重排」，与适配器行为模糊冲突 | 明确**两层语义**：协议层（适配器）按 `index` 把原始响应归位；持久化层（`JdbcKnowledgeDocumentEmbeddingStore`）对已绑定的 `(documentId, chunkIndex, chunkSha256, vector)` **不**重排、**不**补号、只拒绝错配。ADR 内不再有互相矛盾的结论（并记录了原文为什么矛盾） |
| 3 | 过期阶段说明：把已完成的向量化写成「下一阶段/将来」 | `flowdesk-domain/package-info.java`、`KnowledgeDocumentChunk`、`ParsedDocumentResponse`、`DocumentChunker`、`KnowledgeDocumentChunkStore`、解析接口断言说明与 README 对应段落全部更正；尚未交付的能力（截至当时含检索增强）保留表述并写明阶段名 —— 检索增强现已由 FD-0011 交付（RAG 4/6，见第十八章），**Rerank 与答案生成仍未实现** |

**为什么 Key 校验要读真实值**：DashScope 的自动配置条件是
`@ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "dashscope", matchIfMissing = true)`，
「什么都不配」也会命中 —— Bean 能创建出来，可用性却取决于连接属性里的 Key，
所以「Bean 存在」不构成有效防线。

**为什么 provider 必须逐字匹配**：它是会被持久化的血缘字段。静默 trim 或改大小写会让
「配置文件里写的」与「真正生效并被持久化的」不一致；而接受 `openai` 这类取值，
会让检索阶段在判断「库里的向量由谁生成」时得到错误结论，且不会有任何报错。

**为什么用 `OFF` 而不是只关 `ERROR`**：该依赖类有三个分支把
`request.getInstructions()`（切片正文）当日志参数：`Error embedding request: {}`、
`Error message returned for request: {}`（error）与 `No embeddings returned for request: {}`（warn）。

**为什么 `null`/空白 Key 也必须失败**：它们会让每一次索引都以 401/403 收场，
表现为「每次索引都 502」，比起动失败难排查得多。

## 十八、知识检索：Query Embedding 与 pgvector 相似度检索（RAG 4/6）

设计取舍见 [`docs/adr/0008-query-embedding-and-pgvector-similarity-search.md`](docs/adr/0008-query-embedding-and-pgvector-similarity-search.md)。

本阶段把「用户问题」变成「可审计的引用结果」：
**规范化问题 → 生成查询向量（textType=query）→ pgvector 余弦检索 → 结果契约校验 → 引用编号 K1、K2……**。
**不做**：调用 DeepSeek Chat、生成自然语言答案、Rerank、全文/混合检索、接入 Agent/MCP、
修改任何文档状态或向量。

### 18.1 完整链路与固定时序

```
POST /api/v1/knowledge/search
  └─ ① 校验并规范化 query（NFC → strip → 判空 → code point 上限 → 拒绝 ISO 控制字符）
      ② 解析 topK / minScore（套用配置默认值，校验范围）
      ③ 开关检查：未启用向量化 → 503（不调用模型、不访问向量表）
      ④ 查询向量：DashScopeQueryEmbeddingAdapter（一次请求一个 query，textType=query）
      ⑤ 领域校验：KnowledgeQueryEmbedding（1024 维、有限、非全零、防御性复制）
      ⑥ 相似度检索：JdbcKnowledgeVectorSearchAdapter（一条只读 SQL，只检索 INDEXED）
      ⑦ 结果校验：条数 ≤ topK、分数 ∈ [0,1] 且 ≥ minScore、顺序与去重（违反即 500，不修正）
      ⑧ 引用编号：按最终顺序生成 K1、K2……与 rank 1、2……
```

- ④ 发生在**任何 SQL 之前**，且不持有数据库连接；
- ⑥ 是**单条只读语句**：不开显式事务、不加 `FOR UPDATE`、不写任何表；
- ⑦ 只**校验**，不排序、不去重、不截断：违反契约一律按内部错误失败，
  避免把「适配器坏了」掩饰成「结果看起来正常」。

### 18.2 接口与示例

| 方法 | 路径 | 请求 | 成功响应 |
| --- | --- | --- | --- |
| POST | `/api/v1/knowledge/search` | `application/json`：`query`（必填）、`topK`、`minScore` | 200 OK + 检索结果 |

```powershell
curl.exe -s -X POST http://localhost:8080/api/v1/knowledge/search `
  -H "Content-Type: application/json" `
  -d '{\"query\":\"VPN 无法连接应该如何处理？\",\"topK\":5,\"minScore\":0.30}'
```

```json
{
  "provider": "dashscope",
  "model": "text-embedding-v4",
  "dimensions": 1024,
  "topK": 5,
  "minScore": 0.30,
  "citations": [
    {
      "citationId": "K1",
      "rank": 1,
      "documentId": "4fac368c-8d3f-4a4e-9b1f-6f0b1f2a77aa",
      "documentVersion": 4,
      "documentTitle": "VPN 故障处理手册",
      "chunkIndex": 2,
      "chunkSha256": "9f2c…（64 位小写十六进制）",
      "content": "第一步：检查隧道状态，确认预共享密钥未过期",
      "score": 0.873421
    }
  ]
}
```

请求参数规则：

| 字段 | 规则 |
| --- | --- |
| `query` | 必填；NFC 规范化后 `strip`；不能为空；最多 `max-query-code-points`（默认 2000）个 **code point**；拒绝 ISO 控制字符 |
| `topK` | 可选，默认 `default-top-k`（5）；必须在 `1..max-top-k`（默认 20）之间 |
| `minScore` | 可选，默认 `default-min-score`（0.30）；必须是 `0.0..1.0` 的**有限**数值（含边界） |

**公开硬上限是任务契约的一部分，配置只能收紧**（FD-0011-R1）：
query 上限写死在用例服务的常量 `MAX_QUERY_CODE_POINTS_LIMIT = 2000`、topK 上限写死在
`MAX_TOP_K_LIMIT = 20`；`flowdesk.knowledge.retrieval` 只能取更小的值
（`1 <= max-query-code-points <= 2000`、`1 <= default-top-k <= max-top-k <= 20`），
违反即在装配期启动失败；而且用例构造器<b>自身</b>也执行同一组校验，
因此任何绕过 Spring 配置的装配方式都不能放大公开契约。

响应约束：`citationId` 按最终顺序为 `K1`、`K2`……，`rank` 从 1 连续递增；
**不回显 query**、不含向量、不含 SQL；无命中时返回 200 与空 `citations`（不是 404）。

### 18.3 错误矩阵

| 场景 | HTTP | code |
| --- | --- | --- |
| query 缺失/空白/超长/含控制字符，topK 或 minScore 越界 | 400 | `INVALID_REQUEST`（detail 固定为「检索请求不合法」） |
| **空请求体**（`Content-Length: 0`） | 400 | `INVALID_REQUEST`（同一条检索契约；不调用模型、不访问向量表） |
| 请求体无法解析（坏 JSON 或只有空白） | 400 | `INVALID_REQUEST`（detail「请求体不是合法 JSON」，全局框架契约） |
| 默认环境未启用向量化 | 503 | `KNOWLEDGE_EMBEDDING_DISABLED` |
| 上游超时、限流、连接失败、5xx | 502 | `EMBEDDING_PROVIDER_ERROR` |
| 模型响应结构非法 / 查询向量非法 / 数据库检索失败 / 结果契约被破坏 | 500 | `INTERNAL_SERVER_ERROR` |
| `Content-Type` 不受支持 / `Accept` 无法满足 / 请求体无法解析 | 415 / 406 / 400 | `UNSUPPORTED_MEDIA_TYPE` / `NOT_ACCEPTABLE` / `INVALID_REQUEST` |

检索是**只读**的：不修改文档版本、状态、失败码或任何向量，因此响应里**没有** `failureCode`。

### 18.4 检索 SQL 的过滤、排序与绑定

`KnowledgeRetrievalSql.SELECT_MATCHES` 是一条只读 SQL，逐条满足：

1. `d.status = 'INDEXED'`：只检索已索引文档；
2. 描述符**精确匹配**：文档行与向量行的 `provider/model/dimensions` 都必须等于当前配置
   （换模型之后的历史向量不会被误用）；
3. `JOIN knowledge_document_chunks`：`document_id` 与 `chunk_index` 相等，
   且 `c.sha256 = e.chunk_sha256`（摘要不符说明切片在写入向量之后被换过，必须排除）；
4. 相似度 `1 - (e.embedding <=> ?::vector)`；
5. 阈值过滤 `>= ?`（含边界）；
6. 排序 `ORDER BY e.embedding <=> ?::vector ASC, e.document_id ASC, e.chunk_index ASC`：
   **距离是第一排序键**（这样 HNSW `vector_cosine_ops` 索引才可用），
   tie-break 保证同分时顺序确定；
7. `LIMIT ?` 绑定经过校验的 topK；
8. 11 个参数全部是占位符绑定：查询向量 3 次（相似度、阈值、排序）、
   描述符 3+3 次（文档行与向量行）、阈值 1 次、topK 1 次；**没有任何字符串拼接**；
9. 没有 `FOR UPDATE`、没有任何写语句、不开启跨模型调用的事务；
10. **不**写成 `ORDER BY (1 - distance) DESC` —— 那会让 pgvector 退化成全表扫描加排序。

向量字面量由写入与查询**共用**的 `PgVectorLiteral` 生成（`[0.1,0.2,…]`，Locale 无关，
只输出有限数值），保证同一个数值在两条语句里被解析成同一个向量。

### 18.5 配置

```yaml
flowdesk:
  knowledge:
    retrieval:
      max-query-code-points: 2000   # 单个 query 的 code point 上限
      default-top-k: 5              # topK 默认值
      max-top-k: 20                 # topK 上限（请求只能更小）
      default-min-score: 0.30       # 相似度阈值默认值
```

装配期校验：`1 <= max-query-code-points <= 2000`；`1 <= default-top-k <= max-top-k <= 20`；
`default-min-score` 必须是 `0..1` 的有限数值。**配置只能收紧，不能扩大公开契约**：
上限本身来自应用层常量（见 18.2），配置文件只能取更小的值。应用服务只接收纯 Java 数值，
并且它自己的构造器会再执行一次同样的硬上限检查。

默认 profile（H2、未启用向量化）仍然**零依赖启动**：检索用例与接口都装配好了，
有效请求得到 503；真实检索需要 `--spring.profiles.active=postgres,dashscope-embedding`。

### 18.6 已知边界

1. **无 Rerank**：只按向量相似度排序，没有重排兜底；
2. **无混合检索**：没有 BM25 / 全文检索 / `pg_trgm` 融合，纯向量召回；
3. **不生成答案**：只返回引用片段，不调用 Chat 模型；
4. **没有任意切片读取接口**：切片只能通过检索返回，且受 `topK` 与 `minScore` 限制；
5. **HNSW + 过滤是后过滤**：带过滤条件的索引扫描可能返回少于实际匹配数的行
   （`iterative scan` / 调高 `ef_search` 未启用，也未做规模压测）；
6. **没有分页**：单次最多 `max-top-k` 条，不提供游标翻页；
7. **真实上游与真实数据库**：`DASHSCOPE_LIVE=NOT_RUN`、`POSTGRESQL_PGVECTOR_IT=NOT_RUN`
   （本机没有 Key、没有 PostgreSQL 与 Docker），pgvector 断言由 Testcontainers 覆盖，无 Docker 时跳过。

### 18.7 FD-0011-R1：四项契约修订

| # | 问题 | 处理 |
| --- | --- | --- |
| 1 | 配置能把公开上限放大（`max-top-k` 允许到 50、`max-query-code-points` 允许到 100000） | 公开硬上限写进用例服务常量（`MAX_TOP_K_LIMIT=20`、`MAX_QUERY_CODE_POINTS_LIMIT=2000`），并**在用例构造器与配置校验两处**执行：`1 <= max-query-code-points <= 2000`、`1 <= default-top-k <= max-top-k <= 20`。配置只能收紧；`max-top-k=21` 或 `max-query-code-points=2001` 都让应用启动失败 |
| 2 | 行映射阶段的领域异常（例如库里 `chunk_sha256` 不是合法十六进制）会以领域异常外泄，被全局映射接成 **400** | 适配器把**数据库查询 / 结果集读取 / 行映射**三个阶段的异常统一映射为 `KNOWLEDGE_RETRIEVAL_FAILURE`（HTTP 500），原始异常只作为 cause；用例服务在端口边界再收口一次（见下一条），保证「服务端数据问题」永远不会被说成「调用方输入错误」 |
| 3 | 空请求体走「缺少请求体」的通用错误路径，与「缺 query」契约不一致 | 控制器把空 body 转成 `query=null` 交给用例 → 400 + `code=INVALID_REQUEST` + 固定 detail「检索请求不合法」，且不调用查询向量端口、不访问向量检索端口；**坏 JSON（含只有空白的 body）仍保留全局「请求体不是合法 JSON」契约** |
| 4 | 过期文档：任务表里仍有「RAG 4/6 未开始」「查询侧尚未实现」 | README 任务表与第十七章、ADR 0007（阶段范围改为「FD-0010 当时不做」并标注后续进展）、`DashScopeKnowledgeEmbeddingAdapter` 与 `flowdesk-agent` 包注释全部更正；Rerank 与答案生成仍如实标注未实现 |

### 18.8 FD-0011-R2：检索端口的错误分类收口

`KnowledgeVectorSearchPort` 的契约只允许一种失败表达：`KNOWLEDGE_RETRIEVAL_FAILURE`。
此前用例层把**所有** `KnowledgeApplicationException` 都原样上抛，于是端口的未来实现或错误装配
（例如抛出 `INVALID_RETRIEVAL_QUERY`、`KNOWLEDGE_DOCUMENT_NOT_FOUND`、`EMBEDDING_PROVIDER_ERROR`）
会穿透到 HTTP 层，表现为 400 / 404 / 502 / 503 —— 把内部故障说成调用方输入错误、文档不存在或上游不可用。

现在端口边界只有三种归宿：

| 端口抛出 | 处理 |
| --- | --- |
| `KNOWLEDGE_RETRIEVAL_FAILURE`（契约内） | **原样上抛**（同一实例，不二次包装） |
| 其它 `KnowledgeApplicationException`（契约违约） | 包装成 `KNOWLEDGE_RETRIEVAL_FAILURE`，原异常作为 cause；对外固定文案「向量检索失败」 |
| `KnowledgeDomainException` 或其它 `RuntimeException` | 同上 |

因此检索链路的对外失败只有两种形态：**400（调用方输入，在调用端口之前判定）**与 **500（服务端）**。
查询向量端口的 `EMBEDDING_PROVIDER_ERROR`（502）不受影响：它发生在 `embedQuery` 链路，
与本收口无关。回归测试用 `@EnumSource`（排除 `KNOWLEDGE_RETRIEVAL_FAILURE`）遍历**当前与将来**的
全部错误码，锁死新错误码的默认行为。
