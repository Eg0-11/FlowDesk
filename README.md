# FlowDesk 企业智能工单与知识运营平台

> **当前阶段：FD-0007 —— 工单列表、分页、排序与条件搜索（已完成）**
> 已完成：Maven 多模块骨架与版本基线（FD-0001）、DeepSeek 接入与工具调用闭环（FD-0002）、
> 工单领域状态机（FD-0003）、工单应用用例与乐观并发契约（FD-0004）、
> JDBC 持久化适配器 + Flyway 迁移 + Spring 装配（FD-0005）、
> 工单 REST 接口 + ETag 并发协议 + 统一错误契约（FD-0006）、
> 列表接口 + offset 分页 + 排序白名单 + 条件搜索（FD-0007）。
> 尚未实现：游标/keyset 分页、PostgreSQL 全文检索与 pg_trgm、RAG、MCP 能力、Agent Graph、
> 鉴权与前端。

## 一、项目简介

FlowDesk 面向企业 IT 服务与运营场景，规划能力包括：智能化工单流转、知识库运营、RAG 检索增强、
Tool 调用、MCP 资产/监控服务以及基于 Agent Graph 的自动化编排。

当前仓库已经完成两件事：一是打通的 AI 垂直链路
（**HTTP → 用例 → Agent 编排 → Spring AI ChatClient → DeepSeek（OpenAI 兼容传输）→
本地只读工具 → 模型汇总 → HTTP 响应**），二是纯 Java 的工单领域核心
（工单聚合与生命周期状态机），并保留了清晰的模块边界、单向依赖方向与统一的版本基线。

## 二、模块职责

| 模块 | 职责 | 当前状态 |
| --- | --- | --- |
| `flowdesk-shared` | 通用异常、基础类型、工具类 | 仅模块与 `package-info.java` |
| `flowdesk-domain` | 领域实体、值对象、领域规则（**不依赖 Spring**） | 已实现工单聚合与生命周期状态机（`com.flowdesk.domain.ticket`） |
| `flowdesk-application` | 用例服务、输入输出端口 | 已实现 AI 用例（`…application.ai`）与工单用例 + 乐观并发契约（`…application.ticket`） |
| `flowdesk-agent` | AI 编排：实现 application 的 AI 用例，用 ChatClient 编排提示词与本地工具 | 已实现普通聊天与工具冒烟 |
| `flowdesk-infrastructure` | 持久化与模型适配器 | 已提供 JDBC 工单存储（`…ticket.persistence.jdbc`）与 DeepSeek 传输适配 |
| `flowdesk-bootstrap` | FlowDesk 主服务启动模块（Web + Validation + Actuator + AI 接口 + 工单 REST 接口） | 可启动，端口 8080 |
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
| Spring AI Alibaba | 1.1.2.2（**仅导入 BOM**，其 Agent Framework 留待后续阶段） |
| Spring AI Alibaba Extensions | 1.1.2.2（**仅导入 BOM**） |
| DeepSeek 传输 | OpenAI 兼容 Chat Completions（`spring-ai-starter-model-openai`，见 [ADR 0001](docs/adr/0001-deepseek-openai-compatible-transport.md)） |
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
| `TicketRepository.search` | 分页 / 条件搜索：返回「当前页 + 总数」；只发两条语句（一条 `COUNT`、一条分页查询），**无 N+1**；筛选值全部参数绑定，排序按白名单映射固定列名，每行仍经 `Ticket.restore` 恢复成独立聚合 |

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
| 路径不存在 | 404 | `ENDPOINT_NOT_FOUND` |
| 路径存在但方法不支持 | 405 | `METHOD_NOT_ALLOWED`（保留标准 `Allow` 头） |
| `Accept` 无法被满足 | 406 | `NOT_ACCEPTABLE` |
| `Content-Type` 不受支持 | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 未预期异常 | 500 | `INTERNAL_SERVER_ERROR` |

上表覆盖 FlowDesk 自行处理（以及兜底处理）的全部错误来源：工单业务错误、应用层错误、
请求解析与 Bean Validation 失败，以及五类框架错误（404 / 405 / 406 / 415 / 500）。
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

## 十二、代码约束

- 不使用 Lombok。
- 不创建空的 Controller、Service、Repository、Entity 占位类。
- 不提前实现业务功能；当前阶段不引入数据库、Redis、MQ、RAG、MCP 能力的依赖。
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
| 后续 | 游标/keyset 分页；PostgreSQL 全文检索与 `pg_trgm` | 未开始 |
| 后续 | RAG：文档解析、切分、向量化、检索增强 | 未开始 |
| 后续 | Tool 体系扩展：面向工单与知识的工具注册 | 未开始 |
| 后续 | MCP：资产 MCP 服务与监控 MCP 服务的能力实现 | 未开始 |
| 后续 | Agent Graph：基于 Spring AI Alibaba Agent Framework 的多节点编排 | 未开始 |

## 十四、真实验证状态（重要）

| 项 | 状态 | 含义 |
| --- | --- | --- |
| H2 集成 / HTTP 集成 | ✅ 已执行 | 真实 Spring 上下文、真实 JDBC、真实 Flyway 迁移（V1+V2）、真实并发线程 |
| PostgreSQL | ⚠️ **NOT_RUN** | 本机无 PostgreSQL 服务与 Docker，**PostgreSQL 尚未验证** |
| 真实 DeepSeek | ⚠️ **LIVE_SMOKE=NOT_RUN** | 无 `DEEPSEEK_API_KEY`，**未对真实模型发起过任何请求** |

> **本文档不宣称 PostgreSQL 或真实 DeepSeek 已验证。** 相关代码按标准 SQL 与 OpenAI 兼容协议编写、
> 预期可用，但在真实环境跑通之前不应当作已验证。

> Spring AI Alibaba 的 BOM 已在根 pom 中导入并锁定版本，但其 Agent Framework 制品尚未使用；
> 后续阶段接入时直接复用现有 BOM，不需要改动版本基线。
