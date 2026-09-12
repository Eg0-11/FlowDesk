# FlowDesk 企业智能工单与知识运营平台

> **当前阶段：FD-0002 —— DeepSeek 接入与本地 Tool Calling 冒烟闭环（已完成）**
> 已完成：Maven 多模块骨架与版本基线（FD-0001）、DeepSeek 的 OpenAI 兼容传输适配、
> 普通聊天接口、单次本地只读工具调用闭环、配置隔离、异常映射与自动化测试。
> 尚未实现：工单业务、RAG、MCP 能力、Agent Graph、数据库/Redis/MQ、会话记忆、流式输出、鉴权与前端。

## 一、项目简介

FlowDesk 面向企业 IT 服务与运营场景，规划能力包括：智能化工单流转、知识库运营、RAG 检索增强、
Tool 调用、MCP 资产/监控服务以及基于 Agent Graph 的自动化编排。

当前仓库已经打通第一条 AI 垂直链路：**HTTP → 用例 → Agent 编排 → Spring AI ChatClient →
DeepSeek（OpenAI 兼容传输）→ 本地只读工具 → 模型汇总 → HTTP 响应**，
并保留了清晰的模块边界、单向依赖方向与统一的版本基线。

## 二、模块职责

| 模块 | 职责 | 当前状态 |
| --- | --- | --- |
| `flowdesk-shared` | 通用异常、基础类型、工具类 | 仅模块与 `package-info.java` |
| `flowdesk-domain` | 领域实体、值对象、领域规则（**不依赖 Spring**） | 仅模块与 `package-info.java` |
| `flowdesk-application` | 用例服务、输入输出端口 | 已定义 AI 用例接口与命令/结果对象（`com.flowdesk.application.ai`，框架无关） |
| `flowdesk-agent` | AI 编排：实现 application 的 AI 用例，用 ChatClient 编排提示词与本地工具 | 已实现普通聊天与工具冒烟 |
| `flowdesk-infrastructure` | 数据库、Redis、向量库、模型等适配器 | 已提供 DeepSeek 的 OpenAI 兼容传输适配 |
| `flowdesk-bootstrap` | FlowDesk 主服务启动模块（Web + Validation + Actuator + AI 接口） | 可启动，端口 8080 |
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

## 八、代码约束

- 不使用 Lombok。
- 不创建空的 Controller、Service、Repository、Entity 占位类。
- 不提前实现业务功能；当前阶段不引入数据库、Redis、MQ、RAG、MCP 能力的依赖。
- 不使用通配符版本；子模块不重复声明受 BOM 管理的版本。
- 不隐藏编译警告，不跳过测试；全部文件使用 UTF-8。

## 九、后续阶段简述

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| FD-0001 | Maven 多模块骨架与版本基线 | ✅ 已完成 |
| FD-0002 | DeepSeek 接入与本地 Tool Calling 冒烟闭环 | ✅ 已完成 |
| 后续 | 工单业务（领域模型、用例、持久化、接口） | 未开始 |
| 后续 | RAG：文档解析、切分、向量化、检索增强 | 未开始 |
| 后续 | Tool 体系扩展：面向工单与知识的工具注册 | 未开始 |
| 后续 | MCP：资产 MCP 服务与监控 MCP 服务的能力实现 | 未开始 |
| 后续 | Agent Graph：基于 Spring AI Alibaba Agent Framework 的多节点编排 | 未开始 |

> Spring AI Alibaba 的 BOM 已在根 pom 中导入并锁定版本，但其 Agent Framework 制品尚未使用；
> 后续阶段接入时直接复用现有 BOM，不需要改动版本基线。
