# FlowDesk

面向企业 IT 场景的智能工单与知识管理项目。结合 RAG、MCP 和 Agent Graph，支持知识问答、资产诊断与事件研判。

## 核心功能

- **工单管理**：工单状态流转、条件查询及 ETag 乐观并发控制。
- **知识问答**：文档解析、切片、向量检索与重排；基于证据生成答案并校验引用。
- **Agent 编排**：整合知识库、资产和监控数据，输出带证据引用的诊断与研判结果。
- **MCP 工具**：两个独立的只读服务，分别提供资产查询和监控快照。

## 技术栈

Java 17 · Spring Boot · Spring AI · Spring AI Alibaba Graph · PostgreSQL/pgvector · DeepSeek · DashScope · MCP

## 本地体验

- [完整演示步骤](docs/full-demo.md)
- [启动与停止服务](docs/local-run.md)
- [AI 命令行演示](docs/ai-demo.md)

当前项目仅供本地演示，主服务只监听本机回环地址，尚未提供登录鉴权和远程访问。

详细架构、接口、验证记录和开发阶段说明见 [技术文档](README.detailed.md)。
