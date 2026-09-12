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
| `flowdesk-agent` | 后续存放 Graph、ReactAgent、RAG 编排与工具注册 | 仅模块与 `package-info.java` |
| `flowdesk-infrastructure` | 后续存放数据库、Redis、向量库、DeepSeek 等适配器 | 仅模块与 `package-info.java` |
| `flowdesk-bootstrap` | FlowDesk 主服务启动模块（Web + Validation + Actuator） | 可启动，端口 8080 |
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

## 八、代码约束

- 不使用 Lombok。
- 不创建空的 Controller、Service、Repository、Entity 占位类。
- 不提前实现业务功能，不引入数据库、Redis、MQ、RAG、MCP、DeepSeek 依赖。
- 不使用通配符版本；子模块不重复声明受 BOM 管理的版本。
- 不隐藏编译警告，不跳过测试；全部文件使用 UTF-8。
