# FlowDesk 本地启动与冒烟验证（FD-0019-A）

本页是面向使用者的操作顺序：**构建 → Basic 启动 → 检查 → 查看日志 → 停止**。
全部命令都在仓库根目录执行；脚本自身会从自己的位置推导仓库根路径，
所以从任何目录（包括仓库外）调用都不会走错路径。

---

## 0. 前提

| 项 | 要求 |
| --- | --- |
| JDK | **17**（必须是 JDK，不是 JRE：脚本会检查 `bin\javac.exe`） |
| Shell | Windows PowerShell 5.1（`powershell.exe`） |
| 网络 | **Basic 模式不需要任何网络与任何 Key** |
| 端口 | 8080 / 8091 / 8092 必须空闲（被占用时脚本会报出占用者 PID，不会去杀它） |

脚本**不修改**系统/用户的 `JAVA_HOME` 或 `PATH`，也**不修改**固定的服务端口配置。
三个子进程的端口、绑定地址与运行模式都是通过**明确的命令行参数**传入的，
因此运行环境里可能存在的 `server.port`（或 `SERVER_PORT`）之类的覆盖不会影响它们。

### 需要显式给出 JDK 目录

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\Users\<你>\.jdks\jdk-17.0.20.1+1'
```

- 不传 `-JdkHome` 时，脚本回退使用**当前进程**的环境变量 `JAVA_HOME`（并给出提示）。
- 两者都没有、或该目录不是 JDK 17 时，脚本在**启动任何服务之前**失败，并给出修复指引。

---

## 1. 构建

```powershell
# 方式一：让启动脚本顺带构建一次（含全部测试；退出码非零立即停止，不跳过测试）
powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\...\jdk-17' -Build

# 方式二：手动构建，之后启动时复用已有 JAR
mvnw.cmd clean package
powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\...\jdk-17'
```

三个目标 JAR：

```
flowdesk-bootstrap\target\flowdesk-bootstrap-0.1.0-SNAPSHOT.jar
flowdesk-mcp-asset\target\flowdesk-mcp-asset-0.1.0-SNAPSHOT.jar
flowdesk-mcp-monitoring\target\flowdesk-mcp-monitoring-0.1.0-SNAPSHOT.jar
```

JAR 缺失时脚本**明确提示**该跑哪条构建命令，然后以退出码 3 结束（不会起一个半套的服务）。

---

## 2. Basic 模式启动（默认，不需要任何 Key）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\...\jdk-17'
```

启动顺序固定：**先两个 MCP 服务**（各自健康检查通过）**再主服务**。

| 服务 | 地址 | 关键参数 |
| --- | --- | --- |
| 资产 MCP | `http://127.0.0.1:8091`（MCP 端点 `/mcp`） | `--server.address=127.0.0.1 --flowdesk.asset.directory.mode=demo` |
| 监控 MCP | `http://127.0.0.1:8092`（MCP 端点 `/mcp`） | `--server.address=127.0.0.1 --flowdesk.monitoring.source.mode=demo` |
| 主服务 | `http://127.0.0.1:8080` | `--flowdesk.ai.enabled=false --flowdesk.knowledge.embedding.enabled=false` |

`-McpClient` 可显式打开主服务的 MCP 客户端开关，并把它指向上面两个回环服务：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\...\jdk-17' -McpClient
```

### 重复启动是安全的

已经有一套本脚本启动的实例在运行时，脚本会**报告每个进程的 PID、端口与模式**，
然后以退出码 0 结束，**不会**启动第二套进程。想重启就先 `stop-local.ps1`。

---

## 3. 检查（不启动任何服务，只检查现有实例）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-local.ps1
```

检查项：

1. 三个服务的 `/actuator/health` 健康，且三个端口的**实际监听地址**都是 `127.0.0.1`；
2. **资产 MCP**：真实 HTTP + JSON-RPC —— `initialize`（拿到 `Mcp-Session-Id`）→ `initialized` 通知（202）
   → `tools/list`（恰好 1 个只读工具 `asset_get`，`required=[assetId]`、`additionalProperties=false`）
   → `tools/call` 演示命中（`AST-900001` → `SERVER`/`IN_SERVICE`/`source=DEMO`）
   → `tools/call` 演示未命中（`AST-000000` → `found=false`、`error=ASSET_NOT_FOUND`，**未命中不是工具失败**）
   → `finally` 里 `DELETE /mcp` 结束会话；
3. **监控 MCP**：同样流程调用 `monitoring_snapshot_get`，
   命中 `AST-900001`（`2026-01-01T00:00:00Z`/`DEGRADED`/`92`/`68`/`1`/`source=DEMO`）、
   未命中 `AST-900003`（`MONITORING_SNAPSHOT_NOT_FOUND`），`finally` 里 `DELETE /mcp`；
4. 响应**按 `Content-Type` 解析**（`application/json` 直接解析；`text/event-stream` 取 `data:` 帧），
   不靠搜索字符串「200」判断成功；
5. 请求体编码：**UTF-8 且无 BOM**；含中文的 JSON 请求体必须能进入业务层
   （用 `/api/v1/knowledge/search` 验证：Embedding 关闭时应得到 `503 KNOWLEDGE_EMBEDDING_DISABLED`，
   而不是「请求体不是合法 JSON」）；
6. **Basic 模式**下 `/api/v1/ai/asset-diagnosis` 与 `/api/v1/ai/incident-triage` 都是 **404**。

任一 `FAIL` → 脚本以退出码 1 结束；`SKIP` 不算失败。

---

## 4. 查看日志与运行信息

| 内容 | 位置 |
| --- | --- |
| 运行信息（PID、进程启动时间、目标 JAR、端口、模式） | `.local-run\state.json` |
| 每个服务的 stdout / stderr（**两路分开**） | `.local-run\logs\<服务名>-<时间戳>.out.log` / `.err.log` |
| `-Build` 的构建输出 | `.local-run\logs\build-<时间戳>.log` |

`.local-run\` 已被 `.gitignore` 忽略：运行信息与日志**不会**进入 Git。

> **小提示（实测行为）**：如果把 `start-local.ps1` 的输出**管道**给别的命令
> （例如 `... | Out-File log.txt` 或 `... | Tee-Object`），那个管道会一直保持打开，
> 直到服务停止 —— 原因是被启动的 java 子进程继承了父进程的标准输出句柄。
> 在终端里直接运行脚本不受影响（命令提示符会立即返回）；
> 需要在脚本退出后立刻拿到 shell 的场合，请让脚本直接输出到终端，或用
> `-Build` 之外的独立命令去构建并把日志写进文件。

---

## 5. 停止

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-local.ps1
```

- 只操作**本脚本记录在案并且身份核实通过**的进程。身份核对同时校验
  **PID + 进程名（java）+ 进程启动时间 + 命令行里的目标 JAR** ——
  旧 PID 被系统复用给别的程序时不会被误杀。
- 记录缺失 / 损坏 / 身份不符时**报告并跳过**，绝不扩大终止范围；
  脚本**不会**「按所有 `java.exe`」或「按端口占用者」杀进程。
- 先尝试**正常关闭**（`taskkill` 不带 `/F`）；控制台型 Java 进程通常拿不到正常关闭路径，
  此时才**强制终止**，并在输出里**明确记录**用了哪种方式。
- 结束后确认三个端口已释放；**重复停止是安全的**（第二次会报告「没有记录在案的运行实例」）。
- **保留全部日志**，不删除数据库、上传内容或任何用户文件。

---

## 6. DeepSeek 模式（可选，可能产生 API 费用）

```powershell
$env:DEEPSEEK_API_KEY = '<你的 Key>'
powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\...\jdk-17' -Mode deepseek
```

- Key **只从进程环境变量** `DEEPSEEK_API_KEY` 读取；脚本**不接受**命令行明文 Key 参数，
  也不会输出或记录 Key 的值（进程环境不会被打印）。
- 缺 Key 时在**启动任何服务之前**失败（固定提示、退出码 6），三个端口仍然空闲。
- 在 Basic 的服务组合上增加既有 `deepseek` profile：模型名与端点沿用项目既有配置
  （见 `flowdesk-bootstrap/src/main/resources/application-deepseek.yml`）。
- **Embedding 仍然关闭**：知识检索分支不可用（接口会明确回答 `DISABLED`，而不是假装没有数据），
  因此事件研判只能使用资产与监控的演示证据。
- **本次启动本身不会调用任何付费模型**；是否产生费用取决于你之后如何去调用这些接口。
- 冒烟脚本在 DeepSeek 模式下会跳过「AI 接口应为 404」这一项（它只适用于 Basic 模式），
  并且**不会**为了检查而调用 AI 接口生成答案。

---

## 7. 边界与未验证项（请勿误读）

1. **当前没有前端页面。** 浏览器打开 `http://127.0.0.1:8080/` **不会**打开产品界面，只会得到 404；
   本阶段的交互入口是 HTTP 接口本身。
2. **Basic 模式没有 AI 回答能力。** 资产诊断与事件研判接口都是 404，
   **不能**宣称可以生成诊断或研判答案；知识检索也因 Embedding 关闭而不可用。
3. **演示数据是虚构的。** 所有命中的 `source` 都是 `DEMO`，不是真实企业资产或监控系统。
4. **本阶段没有验证真实 DeepSeek**（`LIVE_SMOKE=NOT_RUN`）、
   **没有验证真实 DashScope**（`DASHSCOPE_LIVE=NOT_RUN`）、
   **没有验证真实 PostgreSQL/pgvector**（`POSTGRES_LIVE=NOT_RUN`）。
   冒烟脚本用的是**真实进程 + 真实 MCP 协议 + 演示数据**，这三点都不能替代上面的真实依赖验证。
5. 冒烟脚本**不调用 AI 接口生成答案**，也**不新建或修改**任何业务数据。

---

## 8. 脚本与退出码

| 文件 | 作用 |
| --- | --- |
| `scripts/flowdesk-local-common.ps1` | 共享函数：路径解析、JDK 校验、端口检查、HTTP/JSON-RPC 调用、进程身份校验、状态文件读写 |
| `scripts/start-local.ps1` | 启动三个服务（含可选 `-Build`） |
| `scripts/stop-local.ps1` | 按记录与身份核对停止 |
| `scripts/test-local.ps1` | 只检查现有实例（卫生检查 + 真实 MCP 协议 + 编码 + AI 端点装配） |

| 退出码 | 含义 | 出现在 |
| --- | --- | --- |
| 0 | 成功（含「已在运行，未启动第二套」与「没有记录可停」） | start / stop / test |
| 1 | 冒烟检查有 FAIL 项；或停止时有进程未能停止/端口未释放 | test / stop |
| 2 | 端口被占用（已提示占用者 PID，不杀未知进程） | start |
| 3 | 缺少打包产物（已提示构建命令） | start |
| 4 | JDK 目录无效或不是 JDK 17 | start |
| 5 | `-Build` 构建失败（保留准确退出码与构建日志） | start |
| 6 | 启动失败（已清理本次起来的所有进程）；或 DeepSeek 模式缺 Key（零服务启动） | start |

### 编码约定（维护者）

- `scripts/*.ps1` 必须保存为 **UTF-8 with BOM**：Windows PowerShell 5.1 在没有 BOM 时会按当前
  ANSI 代码页读取脚本，中文文案会变乱码、甚至解析失败。
- HTTP **请求体**反过来必须是 **UTF-8 without BOM**（脚本用 `UTF8Encoding($false)` 构造）：
  带 BOM 的 JSON 会让服务端判为「请求体不是合法 JSON」。
  这两条要求方向相反，`test-local.ps1` 对两者各有一项检查。
