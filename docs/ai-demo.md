# AI 命令行演示入口（FD-0019-B）

`scripts/demo-ai.ps1` 调用主服务**已有**的两个 AI 接口，把答案、引用、三个证据来源的真实状态、
以及事件研判的实际执行路径展示出来。

它**不修改** Java 生产代码、**不新增**后端接口、**不做**前端，也**不参与**服务生命周期
（启动/检查/停止请用 `start-local.ps1` / `test-local.ps1` / `stop-local.ps1`）。

---

## 1. 操作顺序

1. **设置 Key（只在本地 shell）**

   ```powershell
   $env:DEEPSEEK_API_KEY = '<你的 Key>'
   ```

   不要把真实 Key 发进聊天或写进任何文件。演示脚本**不读取、不接收、不传递** Key：
   Key 由主服务从它自己的进程环境读取，脚本不需要知道它。

2. **以 DeepSeek 模式启动服务**（AI 端点只有在这个模式下才注册）

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -Mode deepseek -McpClient -JdkHome 'C:\...\jdk-17'
   ```

   `-McpClient` 必须加：它是资产与监控证据的来源。不加时主服务没有证据，
   两个接口会明确回答降级（`DISABLED`），而不是「数据不存在」。

3. **先预览，再由你显式调用**

   ```powershell
   # 预览：只展示用法、请求预览与费用提示，**一个请求都不发**（POST 次数 = 0）
   powershell -ExecutionPolicy Bypass -File scripts\demo-ai.ps1 -Scenario triage

   # 确认要产生一次真实调用时，由你显式加上 -InvokeModel
   powershell -ExecutionPolicy Bypass -File scripts\demo-ai.ps1 -Scenario triage -InvokeModel

   # 资产诊断
   powershell -ExecutionPolicy Bypass -File scripts\demo-ai.ps1 -Scenario diagnosis -AssetId AST-900001 -InvokeModel
   ```

4. **停止服务**

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\stop-local.ps1
   ```

---

## 2. 参数

| 参数 | 说明 |
| --- | --- |
| `-Scenario` | `diagnosis`（资产诊断）或 `triage`（事件研判）；默认 `triage` |
| `-AssetId` | 资产编号；默认 `AST-900001`（演示数据源里的编号） |
| `-Question` | 事件/问题原文，仅 `triage` 使用；默认一条固定的中文演示问题。它作为**数据**序列化进请求体，不会被求值或执行 |
| `-RequestTimeoutSec` | 单个请求的超时秒数，允许 **1..600**（默认 90）；越界在发送任何请求之前拒绝（退出码 7） |
| `-InvokeModel` | 明确同意本次提交**可能**调用付费模型的请求 |

访问地址固定为 `http://127.0.0.1:8080`，不接受任意远程 URL。

`topK` / `minScore` 本轮刻意不提供命令行参数：省略后使用服务端默认值。

## 3. 调用与费用边界

- 未传 `-InvokeModel`：只展示用法、请求预览与费用提示，**不发送任何请求**（POST 次数 = 0）。
  运行脚本、查看帮助或执行自测都**不会**调用付费模型。
- 传入 `-InvokeModel`：只对选定接口发送**一次** POST，不循环、不自动重试、不同时调用两个接口。
- 一次业务 POST **不保证**只有一次供应商请求：后端已有重试配置，本任务不改动它，也不虚报调用次数。
- 本脚本**不读取、不保存**任何 Key；本轮也**不**把请求、答案与证据正文写入磁盘（只在终端展示）。

## 4. 展示内容

两个场景都展示：

- `requestId`
- `answer`（普通文本，不执行、不解释为命令）
- `grounded`
- `usedEvidenceIds`
- `asset.outcome`、`monitoring.outcome`
- 失败来源的稳定 `failure` 分类
- 命中记录里的 `source`（`DEMO` / `REAL`）

事件研判额外展示：

- `knowledge.status` 及其失败分类
- `executionPath`（按服务端原顺序输出）

### 关键读法（脚本会按真实状态给出提示）

| 提示 | 何时出现 |
| --- | --- |
| HTTP 200 不代表所有来源都成功 | 总是：请以每一路的 `outcome`/`status` 与 `failure` 为准 |
| `grounded=false` 是**降级结果** | 没有证据支撑时；不能包装成模型诊断/研判成功 |
| `FAILED` / `DISABLED` 不得改写成 `NOT_FOUND` | 任一路失败时（它们是「这次查询没成功」，与「没有这条数据」不同） |
| `source=DEMO` 是**虚构的演示数据** | 命中的记录来自演示数据源时 |
| `grounded=true` 只表示答案引用了本次证据 | **不等于答案事实正确** |

## 5. 退出码

| 退出码 | 含义 |
| --- | --- |
| 0 | 预览已展示（未发送任何请求），或成功展示了一次调用的结果（含降级结果） |
| 1 | 响应不符合已公布的契约（非 JSON / 缺字段 / 类型不符） |
| 2 | HTTP 400 请求不合法（展示服务端安全字段，提示检查输入） |
| 3 | HTTP 404 端点不存在（可能未启用 AI，但不断言为唯一原因） |
| 4 | 连接失败或超时（不回显原始异常文本） |
| 5 | HTTP 502 AI 服务错误（展示稳定错误码与 requestId，**不自动重试**） |
| 6 | 其它未分类的 HTTP 状态码 |
| 7 | 参数不合法（在发送任何请求之前拒绝） |

## 6. 边界（请勿误读）

1. **当前 Embedding 关闭**：知识来源不可用（`knowledge.status=FAILED`、`failure=DISABLED`），
   事件研判只能使用 MCP 演示证据 —— **这还不是完整 RAG 的实机验收**。
2. **没有前端页面**：浏览器打开 `http://127.0.0.1:8080/` 仍然不是产品界面。
3. **真实 DeepSeek 尚未验证**：本任务**不授权**调用真实付费模型，`LIVE_SMOKE = NOT_RUN`；
   没有 Key 也不阻塞脚本交付。400 / 502 / 超时等路径由离线自测覆盖，
   实机上已验证的是 404（AI 未启用）与传输失败两条路径。
4. **演示数据是虚构的**（`source=DEMO`），不是真实企业数据源。

## 7. 自测（离线，无 Key、无模型调用、不依赖服务）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\self-test-demo-ai.ps1
```

覆盖：请求字段与中文/引号/换行的往返、完整证据、部分证据（知识 `FAILED` + 资产/监控 `FOUND`）、
无证据降级、`FAILED` 与 `NOT_FOUND` 的区别、400/404/502 与传输失败的映射、
非 JSON / 缺字段 / 类型错误、未传 `-InvokeModel` 时 POST 次数为 0、
命令文本不会被执行、参数越界在发送前拒绝。
