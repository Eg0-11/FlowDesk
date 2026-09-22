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

- 只操作**本脚本记录在案并且身份核实通过**的进程。身份核对依次校验：
  记录本身是否自洽（必要字段齐全、服务名已知、**目标 JAR 属于该服务**、端口在 1..65535）、
  PID 是否存在、进程名是否为 `java`、进程启动时间是否与记录一致（2 秒容差），
  以及命令行里那个真正的 **`-jar` 参数的规范化完整路径**是否与记录**完全相同** ——
  这里不做子串匹配，因此「别的参数里恰好含同一个路径」不会造成误判。
- 目标 JAR 必须是**该服务模块自己 `target` 目录下**、且文件名匹配该模块打包产物模式的绝对路径：
  空路径、通配符（`*`、`?`、`[]`）、裸文件名（如 `java.exe`）、仓库外路径、
  另一个服务的 JAR、`.jar.original` 都会被拒绝。
- 记录缺失 / 损坏 / 身份不符时**报告并跳过**，绝不扩大终止范围；
  脚本**不会**「按所有 `java.exe`」或「按端口占用者」杀进程。
  这类记录只要进程还在就会被**保留**在运行记录里，方便人工确认后重试。
- 先尝试**正常关闭**（`taskkill` 不带 `/F`）；控制台型 Java 进程通常拿不到正常关闭路径，
  此时才**强制终止**，并在输出里**明确记录**用了哪种方式。
- 结束后按**去重后的端口**确认已释放；**重复停止是安全的**
  （第二次会报告「没有记录在案的运行实例」，或继续报告那条未处理的记录）。
- **保留全部日志**，不删除数据库、上传内容或任何用户文件。

### 自测（只读，不终止任何进程）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\self-test-local.ps1
```

对脚本库的判定函数做正反例测试：目标 JAR 路径校验（空/通配符/仓库外/跨服务）、
`-jar` 参数提取（含「路径只作为别的参数的一部分出现」的反例）、记录形状校验、
以及「身份无法证明时拒绝终止」。它**不会**启动服务、也**不会**终止任何进程 ——
其中一条反例刻意用「PID 指向脚本自己的 PowerShell 进程」来验证「拒绝终止」，
并在检查之后断言那个进程仍然活着。有 java 服务在运行时，它还会用真实进程做
正反对照（同一个进程、启动时间错位 1 小时 → 必须因为**启动时间**而不是别的检查被拒绝）。

---

## 6. DeepSeek 模式（可选，可能产生 API 费用）

```powershell
$env:DEEPSEEK_API_KEY = '<你的 Key>'
powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\...\jdk-17' -Mode deepseek -McpClient
```

- Key **只从进程环境变量** `DEEPSEEK_API_KEY` 读取；脚本**不接受**命令行明文 Key 参数，
  也不会输出或记录 Key 的值（进程环境不会被打印）。
- 缺 Key 时在**启动任何服务之前**失败（固定提示、退出码 6），三个端口仍然空闲。
- 在 Basic 的服务组合上增加既有 `deepseek` profile：模型名与端点沿用项目既有配置
  （见 `flowdesk-bootstrap/src/main/resources/application-deepseek.yml`）。
- **Embedding 仍然关闭**：知识检索分支不可用（接口会明确回答 `DISABLED`，而不是假装没有数据），
  因此事件研判只能使用资产与监控的演示证据。
- **`-McpClient` 建议加上**：它把主服务的 MCP 客户端显式打开并指向本机 8091/8092，
  这样资产与监控的演示证据才真的可用。
  **不加 `-McpClient` 时主服务没有资产与监控证据** —— 诊断/研判里的两个查询会明确回答
  `DISABLED`（不是「数据不存在」），研判通常只能走降级路径。脚本在两种情况下都会说明这一点。
  未指定时脚本会**显式**传入 `--flowdesk.mcp.client.enabled=false`，
  因此「关着」是这次启动确定的事实，而不是继承来的某个开关。
- **本次启动本身不会调用任何付费模型**；是否产生费用取决于你之后如何去调用这些接口。
- 冒烟脚本在 DeepSeek 模式下会跳过「AI 接口应为 404」这一项（它只适用于 Basic 模式），
  并且**不会**为了检查而调用 AI 接口生成答案。

---

## 6.1 环境注入的 `SERVER__*` 变量（实测记录）

某些宿主环境（例如把终端会话托管起来的编辑器/IDE 宿主）会往每个子进程注入
`SERVER__HOST` 与 `SERVER__PORT`。Spring Boot 对**环境变量**做宽松绑定，会把这些名字
映射成 `server.host` / `server.port`，**优先级高于打包里的 `application.yml`** ——
实测行为：清掉 `SERVER__PORT` 后监控服务回到配置的 8092，把它设成 8083 则真的监听 8083。

对本脚本**没有影响**：三个服务的端口与绑定地址都是通过**命令行参数**传入的，
而命令行参数的优先级高于环境变量。脚本检测到这两个变量时会打印一条提示，
方便排障时对号入座。

**但如果要手动跑 Maven 测试**（例如 `mvnw.cmd clean package`），这个注入会真的生效：
`flowdesk-mcp-monitoring` 里那条「随包交付的启动契约」用例断言 `server.port == 8092`，
被注入覆盖时会如实地失败 —— 那是**环境**在覆盖配置，不是代码或断言有问题。
处理办法是在同一个 shell 里先移除宿主注入的变量，让测试看到**真正的交付配置**：

```powershell
Remove-Item Env:SERVER__PORT -ErrorAction SilentlyContinue
Remove-Item Env:SERVER__HOST -ErrorAction SilentlyContinue
mvnw.cmd clean package
```

不要用 `-DskipTests`、`-Dmaven.test.failure.ignore` 或改断言来绕过它。

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
| `scripts/flowdesk-local-common.ps1` | 共享函数：路径解析、JDK 校验、端口检查、HTTP/JSON-RPC 调用、记录形状与进程身份核验、失败清理、状态文件读写 |
| `scripts/start-local.ps1` | 启动三个服务（含可选 `-Build`、`-McpClient`） |
| `scripts/stop-local.ps1` | 按记录与身份核对停止；未处理的记录会保留 |
| `scripts/test-local.ps1` | 只检查现有实例（健康 + 监听地址 + 真实 MCP 协议 + 编码 + AI 端点装配） |
| `scripts/self-test-local.ps1` | 脚本库自测（只读反例测试，**不终止任何进程**） |

| 退出码 | 含义 | 出现在 |
| --- | --- | --- |
| 0 | 成功（含「已在运行，未启动第二套」与「没有记录可处理」） | start / stop / test / self-test |
| 1 | 冒烟检查有 FAIL 项；或停止/自测有未处理项 | test / stop / self-test |
| 2 | 端口被占用（已提示占用者 PID，不杀未知进程） | start |
| 3 | 缺少打包产物（已提示构建命令） | start |
| 4 | JDK 目录无效或不是 JDK 17 | start |
| 5 | `-Build` 构建失败（保留准确退出码与构建日志） | start |
| 6 | 启动失败（已清理本次创建的进程；清理未完成时保留可重试的运行记录） | start |
| 7 | 时间参数超出允许范围（什么都没做） | start / stop / test |

时间参数的允许范围（越界一律退出码 7，且不做任何事）：

| 参数 | 脚本 | 范围 | 默认 |
| --- | --- | --- | --- |
| `-HealthTimeoutSec` | start | 1..600 | 90 |
| `-GracefulWaitSec` | stop | 1..120 | 8 |
| `-RequestTimeoutSec` | test | 1..600 | 20 |

### 启动事务与可重试的运行记录

`start-local.ps1` 的顺序是**先登记、再等健康**：每创建一个 JVM 就立刻把
「PID + 进程启动时间 + 目标 JAR + 端口 + 日志路径」写进 `.local-run/state.json`，
**不等健康检查通过**。因此下面这些情况都不会留下没人认领的进程：

- 健康检查超时（即使那个 JVM 还活着、只是还没起来）；
- 后续服务启动失败；
- 写运行记录本身失败（内存里的清单仍然能驱动清理）。

失败时脚本只终止**本次创建、且身份核实通过**的进程。如果有一个进程没能清理掉
（身份无法证明，或终止失败），脚本会：**保留这些记录**以便用 `stop-local.ps1` 重试，
并明确输出「清理**未完成**」—— **不会**在此时输出「全部清理」。
只有真的全部清理成功，才会删除运行记录。

`stop-local.ps1` 同理：身份无法证明但进程仍存在的记录会被**保留**，
下次运行还会再次报告它，而不是悄悄丢掉线索。运行记录文件本身损坏（非法 JSON）时，
脚本拒绝执行任何终止动作并给出人工处理指引。

### 编码约定（维护者）

- `scripts/*.ps1` 必须保存为 **UTF-8 with BOM**：Windows PowerShell 5.1 在没有 BOM 时会按当前
  ANSI 代码页读取脚本，中文文案会变乱码、甚至解析失败。
- HTTP **请求体**反过来必须是 **UTF-8 without BOM**（脚本用 `UTF8Encoding($false)` 构造）：
  带 BOM 的 JSON 会让服务端判为「请求体不是合法 JSON」。
  这两条要求方向相反，`test-local.ps1` 对两者各有一项检查。
