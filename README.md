# FlowDesk 企业智能工单与知识运营平台

> **当前阶段：项目骨架（FD-0001）**
> 本阶段只建立可编译、可测试的 Maven 多模块结构并锁定版本基线。
> 尚未实现任何业务功能，未接入数据库、Redis、MQ、RAG、MCP 与 DeepSeek 模型。

## 一、项目简介

FlowDesk 面向企业 IT 服务与运营场景，规划能力包括：智能化工单流转、知识库运营、RAG 检索增强、
Tool 调用、MCP 资产/监控服务以及基于 Agent Graph 的自动化编排。

当前仓库提供的是这一切能力的地基：清晰的模块边界、单向依赖方向、统一的版本与编码基线。

## 二、模块职责

| 模块 | 职责 | 当前状态 |
| --- | --- | --- |
| `flowdesk-shared` | 通用异常、基础类型、工具类 | 仅模块与 `package-info.java` |
| `flowdesk-domain` | 领域实体、值对象、领域规则（**不依赖 Spring**） | 仅模块与 `package-info.java` |
| `flowdesk-application` | 用例服务、输入输出端口 | 仅模块与 `package-info.java` |
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
| Spring AI | 1.1.2（仅导入 BOM） |
| Spring AI Alibaba | 1.1.2.2（仅导入 BOM） |
| Spring AI Alibaba Extensions | 1.1.2.2（仅导入 BOM） |
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
不写入任何密码、Token、API Key，也不包含 DeepSeek 与数据库配置。

## 七、后续阶段简述

1. **工单业务**：领域模型、用例服务、持久化适配器与工单接口。
2. **DeepSeek 接入**：模型客户端与配置装配，接入 Spring AI。
3. **RAG**：知识库文档解析、切分、向量化与检索增强。
4. **Tool**：面向工单与知识的工具定义与注册。
5. **MCP**：资产 MCP 服务与监控 MCP 服务的能力实现。
6. **Agent Graph**：基于 Spring AI Alibaba 的多节点编排与自动化流转。

## 八、AI 链路（FD-0002）

本阶段打通了 FlowDesk 的第一条 AI 垂直链路：

```
HTTP → Bootstrap Controller → Application 用例接口 → Agent 编排 → Spring AI ChatClient
     → DeepSeek（OpenAI 兼容传输）→ lookup_support_policy 本地工具
     → DeepSeek 汇总工具结果 → HTTP 响应
```

### 8.1 模型接入方式

DeepSeek 通过 **OpenAI 兼容的 Chat Completions 接口**访问，使用
`spring-ai-starter-model-openai` 而非原生 DeepSeek 适配器。
决策背景、理由与重新评估条件见
[`docs/adr/0001-deepseek-openai-compatible-transport.md`](docs/adr/0001-deepseek-openai-compatible-transport.md)。

### 8.2 HTTP 接口

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

### 8.3 配置与启用方式

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
`flowdesk-bootstrap/src/main/resources/application-deepseek.yml`）：

```bash
# macOS / Linux
export DEEPSEEK_API_KEY=sk-...
./mvnw -pl flowdesk-bootstrap spring-boot:run -Dspring-boot.run.profiles=deepseek

# Windows PowerShell
$env:DEEPSEEK_API_KEY = 'sk-...'
.\mvnw.cmd -pl flowdesk-bootstrap spring-boot:run "-Dspring-boot.run.profiles=deepseek"
```

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

### 8.4 已知的 Spring AI 1.1.2 传输层缺陷与绕行

`OpenAiChatModel.createRequest` 在**注册了工具**时会执行第二次 `ModelOptionsUtils.merge`
（把 `tools` 数组并进请求体），而这次合并会把 `extraBody` 清空：

```
第一次 merge 后: {"messages":[],"model":"deepseek-flash","thinking":{"type":"disabled"}}
第二次 merge 后: {"messages":[],"model":"deepseek-flash"}     ← thinking 被抹掉
```

也就是说配置里的 `extra-body` 在普通聊天路径有效，一旦携带工具就失效，
而携带工具恰恰是必须关闭 thinking 的场景。FlowDesk 因此在传输层加入了
`DeepSeekThinkingDisabledInterceptor`：在 `/chat/completions` 请求真正发出前补齐该字段。
该拦截器由 `ToolCallingLoopTests` 断言其真实生效；Spring AI 修复该缺陷后可整体删除。

### 8.5 上游故障的失败时效

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

## 九、代码约束

- 不使用 Lombok。
- 不创建空的 Controller、Service、Repository、Entity 占位类。
- 不提前实现业务功能，不引入数据库、Redis、MQ、RAG、MCP、DeepSeek 依赖。
- 不使用通配符版本；子模块不重复声明受 BOM 管理的版本。
- 不隐藏编译警告，不跳过测试；全部文件使用 UTF-8。
