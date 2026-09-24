# FlowDesk 完整演示手册

面向**第一次接触本仓库的人**：从构建、启动、上传样例、解析、索引，一直到一次事件研判，
按两条路径走完。**本手册不引入任何新功能，只把已有接口与已验证过的链路写清楚。**

> ## 先读这一节（边界与前提）
>
> - **没有网页前端**：浏览器打开主服务根地址不会看到产品界面（只有 404）。所有交互都是 HTTP 接口。
> - **没有鉴权**：主服务与两个 MCP 服务都**没有身份与权限概念**，能访问端口就能调用。
>   因此三者都**只监听本机回环**（`127.0.0.1`），这是启动期强制的边界：把 `server.address`
>   覆盖成 `0.0.0.0`、`::`、局域网/公网地址或主机名会在**创建 Web 服务器之前**失败（FD-0021）。
>   **只供本机演示；将来若要远程访问，必须先单独设计鉴权与授权。**
> - **Rerank（重排）全程保持关闭**：本手册两条路径都不打开它。
> - **不要加任何 `sdk-log-level` 命令行绕过**：该启动缺陷**已修复**（交付配置里已是带引号的
>   `"OFF"`，并有不加载测试属性的启动回归测试守着），手工命令行不需要、也不应该再传它。
> - **不要清库、不要删卷**：数据库用**已有命名卷**；禁止 `docker compose … down -v` 或任何卷删除。
> - **不要批量结束 java 进程**：禁止 `taskkill /IM java.exe`、`Get-Process java | Stop-Process`
>   —— 那会误杀不属于本演示的进程。停止方式见「第六节」。
> - **Key 只从进程环境继承**：本手册只检查 Key **是否存在**，不打印、不保存、不写入任何报告；
>   也不要把 Key 写进命令行。

## 费用一览（每一步都在正文里再次标注）

| 步骤 | 是否可能产生供应商费用 |
| --- | --- |
| 构建、启动、健康检查、MCP 演示查询、上传、解析、停止 | **不产生**（这些步骤不调用任何模型；解析用本地 Tika） |
| 索引（index） | **可能产生**（DashScope Embedding：1 次文档批次） |
| 检索（search） | **可能产生**（DashScope Embedding：1 次查询） |
| 事件研判（incident-triage） | **可能产生**（DashScope Embedding 1 次查询 + DeepSeek 1 次对话） |

> **预检查、启动与健康检查绝不调用模型。** 付费动作（索引 / 检索 / 研判）必须由你
> **显式地、分别地**执行：本手册没有任何自动重试、重放或循环；失败就停下来看错误码，
> 不要用「换个提示词再试一次」的方式反复消耗额度。

---

## 一、准备（不产生费用）

| 项 | 说明 |
| --- | --- |
| JDK 17 | 需要**显式给出目录**，例如 `C:\Users\me\.jdks\jdk-17.0.20.1+1`。脚本只读取、不改你的 `JAVA_HOME`/`PATH` |
| PowerShell 5.1 | 本手册的命令在 Windows PowerShell 下可直接粘贴 |
| Docker Desktop | **只有路径 B 需要**（本地 PostgreSQL/pgvector 容器）。路径 A 完全不需要 |
| 可选环境变量 | 路径 B 需要：`DASHSCOPE_API_KEY`（向量化）、`DEEPSEEK_API_KEY`（对话）、`FLOWDESK_DB_PASSWORD`（数据库口令） |

**只检查 Key 是否存在（不显示、不保存值）**：对下面三个变量名各跑一次，只得到 `PRESENT`/`MISSING`。

```powershell
foreach ($name in @('DASHSCOPE_API_KEY', 'DEEPSEEK_API_KEY', 'FLOWDESK_DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
        Write-Host "$name = MISSING"
    }
    else {
        Write-Host "$name = PRESENT"
    }
}
```

---

## 路径 A：免费离线路径（不需要任何 Key，全程不产生费用）

用仓库自带的三个脚本走完「构建 → 启动 → 自测 → 停止」。**这条路不碰任何模型，也不碰数据库。**

### A1 构建（不产生费用）

```powershell
# 在仓库根目录执行
.\mvnw.cmd clean package          # Windows
# ./mvnw clean package            # macOS / Linux
```

### A2 启动三个服务（不产生费用）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\Users\me\.jdks\jdk-17.0.20.1+1'
```

- 固定顺序：资产 MCP（8091）→ 监控 MCP（8092）→ 主服务（8080），前两个健康后才启动主服务；
- 三个服务都显式绑定 `127.0.0.1`；
- `-Build` 可让脚本先跑一次 `mvnw clean package`（含全部测试）；
- `-McpClient` 会打开主服务的 MCP 客户端开关，并指向上面两个回环 MCP 服务。
- 运行信息（PID、启动时间、目标 JAR、端口、模式、日志路径）写在 `.local-run/state.json`（已被 Git 忽略）。

### A3 自测（不产生费用）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-local.ps1
```

`test-local.ps1` **不启动任何服务**，只检查正在运行的实例，逐项打印 PASS/FAIL：

1. 三个服务的 `/actuator/health` 健康，且三个端口的**实际监听地址都是 `127.0.0.1`**；
2. 资产 MCP：真实 HTTP + JSON-RPC 握手（`initialize` → `initialized` → `tools/list` → `tools/call`），
   验证固定工具 `asset_get` 的演示命中与未命中，最后 `DELETE` 会话；
3. 监控 MCP：同样调用 `monitoring_snapshot_get`，验证演示命中与未命中，最后 `DELETE` 会话；
4. 响应按 `Content-Type` 解析（`application/json` 直解、`text/event-stream` 取 `data:` 帧），
   不靠搜索字符串判断成功；
5. 请求体 UTF-8 **无 BOM**，含中文的 JSON 能进入业务层；
6. **Basic 模式下 AI 接口返回 404**（下面解释为什么）。

命中的 `source` 都是 `DEMO`（虚构数据）——这是「真实进程 + 真实 MCP 协议 + 演示数据」的验证，
**不是**真实企业数据源验证。

### A4 为什么 Basic 模式下的 AI 接口是 404（而不是 500/503）

AI 相关的控制器（例如事件研判 `POST /api/v1/ai/incident-triage`）带
`@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")`。
Basic 模式显式传 `--flowdesk.ai.enabled=false`，**这些控制器根本没有被注册**，
所以是 **404（接口不存在）**——不是 500（服务端错误）、不是 503（暂时不可用），
也不是「数据不存在」那种业务结论。需要 AI 能力时，走路径 B。

### A5 `-Mode deepseek` 的能力边界（重要）

`-Mode deepseek` 会在 Basic 的服务组合上启用既有的 `deepseek` profile：
AI 开关打开，**但 Embedding 仍然显式关闭**（`--flowdesk.knowledge.embedding.enabled=false`）。

因此它**当前不支持完整 RAG**：

- 对话/答案能力可用；
- 但**知识检索分支不可用** —— 检索会明确回答 `DISABLED`（而不是假装「没有相关数据」）；
- 所以它**不能**用来替代路径 B，也不能用它宣称「RAG 演示通过」。

### A6 停止（不产生费用）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-local.ps1
```

脚本只停止**它自己启动的**进程：先按运行记录核对 PID、启动时间与目标 JAR，核对通过才停止。
**不会**按进程名批量结束 java。

---

## 路径 B：完整三路证据路径（会产生供应商费用）

**三路证据**＝知识（pgvector 检索）+ 资产（demo MCP）+ 监控（demo MCP）。

| 环节 | 说明 |
| --- | --- |
| 数据库 | 本地 PostgreSQL 16 + pgvector 容器（复用**已有命名卷**，不清库） |
| 向量化 | DashScope Embedding（`text-embedding-v4`，1024 维） |
| 对话 | DeepSeek（OpenAI 兼容接口） |
| 资产/监控 | 本仓库的两个 **demo** MCP 服务（`source=DEMO`，虚构数据） |

> 下面的命令都假设你**在仓库根目录**执行（`docs\samples\...`、`flowdesk-*\target\...` 都是相对路径），
> 并且已经跑过一次 `.\mvnw.cmd clean package` 得到三个 JAR。

### B1 启动数据库容器（不产生费用、不清库）

```powershell
# 口令只从环境变量读取；缺失时 compose 会直接报错，不会用空口令起来
docker compose -p flowdesk -f compose.postgres.yml up -d
docker inspect --format '{{.State.Health.Status}}' flowdesk-postgres   # 期望 healthy
docker volume ls | Select-String 'flowdesk-pgdata'                     # 卷仍在
```

- 端口只绑 `127.0.0.1:5433`；Flyway 会在首次连接时自动跑迁移（V1–V6，含 `vector` 扩展与向量表）；
- **复用已有卷**：容器重启不会清数据；再次强调**禁止 `down -v`**；
- 不要用 `docker compose down`（不带 `-v` 也会移除容器，允许但不必要）；停止用 `stop`（见第六节）。

### B2 启动两个 demo MCP 服务（不产生费用）

各开一个 PowerShell 窗口执行（日志直接可见，停止就是 `Ctrl+C`）：

```powershell
# 窗口 1：资产 MCP（8091，demo 数据源）
& 'C:\Users\me\.jdks\jdk-17.0.20.1+1\bin\java.exe' -jar 'D:\FlowDesk\flowdesk-mcp-asset\target\flowdesk-mcp-asset-0.1.0-SNAPSHOT.jar' --server.port=8091 --server.address=127.0.0.1 --flowdesk.asset.directory.mode=demo
```

```powershell
# 窗口 2：监控 MCP（8092，demo 数据源）
& 'C:\Users\me\.jdks\jdk-17.0.20.1+1\bin\java.exe' -jar 'D:\FlowDesk\flowdesk-mcp-monitoring\target\flowdesk-mcp-monitoring-0.1.0-SNAPSHOT.jar' --server.port=8092 --server.address=127.0.0.1 --flowdesk.monitoring.source.mode=demo
```

### B3 启动主服务（不产生费用）

第三个窗口执行。**注意：命令行里不传任何 Key，也不传 `sdk-log-level`。**

```powershell
$env:FLOWDESK_DB_URL = 'jdbc:postgresql://127.0.0.1:5433/flowdesk'
$env:FLOWDESK_DB_USERNAME = 'flowdesk'
# 口令：先看**当前进程**里是否已经有值；只有为空时才回退到 User 级。
# 绝不用 User 级无条件覆盖进程级（进程级可能已经正确设置，而 User 级是空的），也绝不打印口令。
if ([string]::IsNullOrWhiteSpace($env:FLOWDESK_DB_PASSWORD)) {
    $fromUser = [Environment]::GetEnvironmentVariable('FLOWDESK_DB_PASSWORD', 'User')
    if ([string]::IsNullOrWhiteSpace($fromUser)) {
        Write-Host 'FLOWDESK_DB_PASSWORD 缺失：请先在当前会话里设置它，再重跑本步（不会显示口令）'
        return
    }
    $env:FLOWDESK_DB_PASSWORD = $fromUser
}
Write-Host 'FLOWDESK_DB_PASSWORD = PRESENT'   # 只报存在性；值不打印、不保存、不写进任何报告
& 'C:\Users\me\.jdks\jdk-17.0.20.1+1\bin\java.exe' -jar 'D:\FlowDesk\flowdesk-bootstrap\target\flowdesk-bootstrap-0.1.0-SNAPSHOT.jar' --spring.profiles.active=postgres,dashscope-embedding,deepseek --server.port=8080 --server.address=127.0.0.1 --flowdesk.ai.enabled=true --flowdesk.mcp.client.enabled=true --flowdesk.mcp.client.asset.base-url=http://127.0.0.1:8091 --flowdesk.mcp.client.monitoring.base-url=http://127.0.0.1:8092 --flowdesk.knowledge.rerank.enabled=false
```

- `postgres` → 连本地容器库；`dashscope-embedding` → 打开向量化；`deepseek` → 打开对话模型；
- `--flowdesk.ai.enabled=true` → AI 接口才会注册（否则 404）；
- MCP 客户端显式开启并指向两个回环 demo 服务；
- **Rerank 保持关闭**；
- `DASHSCOPE_API_KEY` / `DEEPSEEK_API_KEY` 从进程环境继承（不写进命令行）。

### B4 健康检查（不产生费用，**不调用模型**）

```powershell
# 三个健康端点（期望都是 UP）
foreach ($port in 8080, 8091, 8092) {
    $h = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health"
    Write-Host "$port = $($h.status)"
}
# 三个端口的实际监听地址（都应是 127.0.0.1）
foreach ($port in 8080, 8091, 8092) {
    $addr = (Get-NetTCPConnection -State Listen -LocalPort $port | Select-Object -First 1).LocalAddress
    Write-Host "$port listen = $addr"
}
```

> **不要在这一步运行 `scripts\test-local.ps1`。** 它最后一项检查会 `POST /api/v1/knowledge/search`，
> 并期望 Embedding **关闭**时的 `503 KNOWLEDGE_EMBEDDING_DISABLED`（那是 Basic 模式的前提）。
> 路径 B 已经打开了 Embedding：这一步**既可能触发一次计费**，又会因为拿到非 503 而**误报 FAIL**。
> 路径 B 的检查只用下面的**零模型调用**方式。

**想单独确认两个 demo MCP 的协议可用（依然不调用任何模型）**：下面的片段直接对 MCP 端点做
`initialize` → `initialized` → `tools/list` 之外的 `tools/call`，并打印工具返回的演示 JSON；
它**不经过主服务**，也不触发任何模型调用：

```powershell
foreach ($target in @(
        @{ url = 'http://127.0.0.1:8091/mcp'; tool = 'asset_get' },
        @{ url = 'http://127.0.0.1:8092/mcp'; tool = 'monitoring_snapshot_get' })) {

    $headers = @{ 'Content-Type' = 'application/json'; 'Accept' = 'application/json, text/event-stream' }
    $initBody = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"full-demo","version":"1.0.0"}}}'
    $init = Invoke-WebRequest -Uri $target.url -Method POST -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($initBody)) -UseBasicParsing
    $session = [string]$init.Headers['Mcp-Session-Id']
    $headers['Mcp-Session-Id'] = $session

    $initialized = '{"jsonrpc":"2.0","method":"notifications/initialized"}'
    Invoke-WebRequest -Uri $target.url -Method POST -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($initialized)) -UseBasicParsing | Out-Null

    $callBody = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"' + $target.tool + '","arguments":{"assetId":"AST-900001"}}}'
    $call = Invoke-WebRequest -Uri $target.url -Method POST -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($callBody)) -UseBasicParsing
    Write-Host ($target.tool + ' -> ' + $call.Content)

    Invoke-WebRequest -Uri $target.url -Method DELETE -Headers @{ 'Mcp-Session-Id' = $session } -UseBasicParsing | Out-Null
}
```

**预期**：两条 `tools/call` 都返回 200，打印出的 JSON 里带 `"source":"DEMO"`
（资产是 `SERVER`/`IN_SERVICE`，监控是 `DEGRADED` 等 demo 数值）——`source=DEMO` 就是「这是演示数据」的标记。

到这一步为止**没有产生任何供应商费用**。

### B5 上传样例文档（不产生费用）

仓库自带一份**虚构、无敏感信息**的样例：`docs/samples/fictional-kb-sample.md`
（唯一标记 `FLOWDESK-DEMO-KB-2200`，可安全上传）。

```powershell
$resp = curl.exe -s --noproxy '*' -i -X POST 'http://127.0.0.1:8080/api/v1/knowledge/documents' -F 'title=虚构样例：NB-2200 边缘路由器失联处置' -F 'file=@docs\samples\fictional-kb-sample.md'
$resp
```

**预期**：`201 Created` + **`Location` 响应头**。
**注意：上传响应不带 `ETag`** —— `ETag` 出现在**解析与索引**的响应里（那两步需要它配合 `If-Match`）。
响应体字段：`id`、`title`、`originalFilename`、`format=MARKDOWN`、`mediaType`、`sizeBytes`、
`sha256`（**整份文件**的摘要）、`status`（`UPLOADED`）、`version=0`、`createdAt`/`updatedAt`。

记下三样东西，后面每一步都要用：**`id`**、**`version`**（后续 `If-Match` 用 `"<version>"`）、**`sha256`**（整份文件摘要）。

### B6 解析（不产生费用；本地 Tika，不调用模型）

**这一步用 `Invoke-WebRequest`，并把 `If-Match` 写成带双引号的字面量**：
PowerShell 5.1 把内联参数交给原生 `curl.exe` 时会吃掉参数里的双引号，而 `If-Match` 的值
**必须**是带引号的版本号（`"0"`）；实测拼接出来的写法可能送不到服务端，所以这里用字面量。

```powershell
$id = '<B5 返回的 id>'
# 注意这里保留双引号：值就是 "0"（引号是 ETag 语法的一部分）
$resp = Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/v1/knowledge/documents/$id/parse" -Method POST -Headers @{ 'If-Match' = '"0"' } -UseBasicParsing
$resp.StatusCode
$resp.Content
```

**预期**：`200`；响应体字段 `documentId`、`title`、`status=PARSED`、`version`（已 +1）、
`chunkCount`（≥1）、`parsedAt`，并且**响应带 `ETag`**（内容是新版本号）。

`If-Match` 必须与当前版本一致：缺失会得到 `428`，陈旧会得到 `412` —— 这不是故障，是**乐观锁**在起作用。
（若你的文档版本已经不是 `0`，把上面字面量里的 `0` 换成当前版本号，**保留双引号**。）

### B7 索引（**可能产生供应商费用：DashScope Embedding，1 次文档批次**）

> 这是本手册第一个**付费动作**。只执行一次；失败就停下看错误码，不要循环重试。

```powershell
$id = '<B5 返回的 id>'
# 版本号换成 B6 响应里的 version，保留双引号
$resp = Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/v1/knowledge/documents/$id/index" -Method POST -Headers @{ 'If-Match' = '"1"' } -UseBasicParsing
$resp.StatusCode
$resp.Content
```

**预期**：`200`；字段 `documentId`、`title`、`status=INDEXED`、`version`、`chunkCount`、
`embeddingProvider=dashscope`、`embeddingModel=text-embedding-v4`、`embeddingDimensions=1024`、`indexedAt`；
响应同样带 `ETag`。

### B8 可选：一次检索（**可能产生供应商费用：1 次查询 Embedding**）

想确认「向量确实写进库并能被检索出来」时可以单独跑这一步；只跑一次。

> **请求体要用「UTF-8 无 BOM 文件 + `--data-binary`」传**：PowerShell 5.1 把内联参数交给原生
> `curl.exe` 时会**吃掉 JSON 里的双引号**（实测 `--data-raw '{"a":1}'` 送达后变成 `{a:1}`，是非法 JSON）。
> 脚本「语法解析通过」不等于 HTTP 请求体正确，所以这里统一用文件传。
> `--noproxy '*'` 是给设置了 `HTTP_PROXY`/`HTTPS_PROXY` 的环境用的：本地回环请求不应该走代理。
> `-o` 把响应**原样存成文件**再按 UTF-8 读：PowerShell 5.1 直接显示响应内容时会按 Latin-1 解码，
> 中文会变乱码（那是**显示**问题，不是数据问题）。

```powershell
$body = @'
{"query":"NB-2200 失联处置流程是什么","topK":1,"minScore":0.0}
'@
$bodyPath = Join-Path $env:TEMP 'flowdesk-search.json'
[System.IO.File]::WriteAllBytes($bodyPath, (New-Object System.Text.UTF8Encoding($false)).GetBytes($body))
$respPath = Join-Path $env:TEMP 'flowdesk-search-response.json'
curl.exe -s --noproxy '*' -o $respPath -X POST 'http://127.0.0.1:8080/api/v1/knowledge/search' -H 'Content-Type: application/json' --data-binary "@$bodyPath"
Get-Content -LiteralPath $respPath -Raw -Encoding UTF8
```

**预期**：`200 OK`；`provider=dashscope`、`model=text-embedding-v4`、`dimensions=1024`、`topK`、
`rankingMode=VECTOR_SIMILARITY`，以及 `citations[0]`：`citationId`、`rank`、`documentId`、`documentVersion`、
`chunkIndex`、`chunkSha256`、`content`、`score`。

**关于「样例是否命中」要有条件地看**：路径 B 用的是**已有数据卷**，库里可能已经有别的文档，
`topK=1` **不保证**本样例一定排第一。所以：

- 若 `citations[0].documentId` = B5 的 `id` → 命中，接着核对该切片的 `content` 是否含 `FLOWDESK-DEMO-KB-2200`；
- 若 `documentId` 是**别的文档** → 这不是故障，说明库里已有更相近的切片：**看返回的 `documentId` 是谁**，
  需要本样例时把 `topK` 调大（这会多花一次查询 Embedding，仍然只发一次）或换一个更贴近样例内容的查询词；
- **不要**为了「让它排第一」反复重发同样的付费请求，也**不要**清库/删卷。

### B9 一次事件研判（**可能产生供应商费用：1 次查询 Embedding + 1 次 DeepSeek 对话**）

> 一个请求同时触发三路证据：知识检索（Embedding）+ 资产 MCP + 监控 MCP，随后一次对话生成答案。
> **只发一次。** 请求体同样用「UTF-8 无 BOM 文件 + `--data-binary`」传。

```powershell
$body = @'
{"assetId":"AST-900001","question":"对照知识库 FLOWDESK-DEMO-KB-2200 中 NB-2200 的失联处置流程，结合 AST-900001 的资产类型和最新监控快照，说明哪些信息有证据支持、哪些流程不能直接套用？","topK":1,"minScore":0.0}
'@
$bodyPath = Join-Path $env:TEMP 'flowdesk-triage.json'
[System.IO.File]::WriteAllBytes($bodyPath, (New-Object System.Text.UTF8Encoding($false)).GetBytes($body))
$respPath = Join-Path $env:TEMP 'flowdesk-triage-response.json'
curl.exe -s --noproxy '*' -o $respPath -X POST 'http://127.0.0.1:8080/api/v1/ai/incident-triage' -H 'Content-Type: application/json' --data-binary "@$bodyPath"
Get-Content -LiteralPath $respPath -Raw -Encoding UTF8
```

**预期响应字段（这就是验收要看的）**：

| 字段 | 期望 | 含义 |
| --- | --- | --- |
| `requestId` | 有值 | 本次请求的标识 |
| `knowledge.status` | `FOUND` | 知识路确实检索到了切片（**不保证**检索到的就是本样例，见下） |
| `knowledge.retrieval.citations[0].documentId` | 期望等于 B5 的 `id` | 指向你上传的那份文档；若指向**别的** `documentId`，说明库里已有更相近的切片（见下面的条件说明） |
| 该引用的 `documentVersion` | 与 B7 返回的版本一致（通常 = B7 的 `version`） | 引用的是**当前版本** |
| 该引用的 `chunkSha256` | 一段**独立**的摘要 | 它是**切片内容**的摘要，与 B5 里的 `sha256`（**整份文件**摘要）**不是一回事**；B7 不返回切片摘要，所以不要拿它去和 B5/B7 对齐 |
| 该引用的 `content` | 命中本样例时应含 `FLOWDESK-DEMO-KB-2200` | 命中本样例的判据 |
| `asset.outcome` | `FOUND` | 资产路查询成功 |
| `asset.assetType` / `asset.status` / `asset.source` | `SERVER` / `IN_SERVICE` / **`DEMO`** | `source=DEMO` 表示这是**演示数据**，不是真实企业资产系统 |
| `monitoring.outcome` | `FOUND` | 监控路查询成功 |
| `monitoring.source` | **`DEMO`** | 同上；快照里的 `cpuUtilizationPercent` 等数值来自 demo 数据 |
| `grounded` | `true` | 答案受证据约束（没有引用不存在的编号） |
| `usedEvidenceIds` | 三类编号**都出现**，且**顺序与答案里首次出现的顺序一致** | 它按**首次出现顺序**去重，**不保证恒为 `["K1","A1","M1"]`**：答案先引用哪一类，哪一类就排在前面 |
| `executionPath` | 约九步，含 `validate_asset` → `retrieve_knowledge` → `query_asset` → `query_monitoring` → … → `generate_answer` → `validate_citations` → `finish` | 能看出确实**分别**走了知识、资产、监控三路 |
| `answer` | 带 `[K1]`/`[A1]`/`[M1]` 的中文答案 | 编号集合与 `usedEvidenceIds` 一致；顺序也应对得上 |

**「样例是否命中」同样要有条件地看**：复用已有卷时 `topK=1` 不保证本样例排第一。
若 `citations[0].documentId` 不是 B5 的 `id`，**不要**重发同样的付费请求，也**不要**清库/删卷：
先看返回的 `documentId` 是谁、`content` 讲了什么，再决定是否调整查询词或 `topK` 后**单独发一次**。

**还应该人工看一眼答案的合理性**：样例文档写的是**边缘路由器**的处置流程，而 `AST-900001` 是
**服务器**（`assetType=SERVER`）。一份「有证据且自洽」的答案应当**指出类型不匹配、不能直接套用**，
而不是把路由器流程无条件搬到服务器上。

### B10 引用正确 ≠ 事实必然正确

`grounded=true` 与 `usedEvidenceIds` 只保证**引用关系**成立：

- ✅ 它保证：`[K1]` 指向的那段话确实来自你上传的文档切片（`documentId`/`chunkIndex`/摘要可逐字核对）；
- ❌ 它**不**保证：文档内容本身是真的（本手册用的是**虚构样例**）、模型推理没有偏差、
  数值符合现实（资产/监控是 `DEMO` 数据）、结论可以直接用于生产决策。

要判断「事实是否成立」，必须回到**真实数据源**与**人工确认**。演示里所有资产/监控证据都标着
`source=DEMO`，就是为了不让人把演示结论当成真实结论。

---

## 六、安全停止（不产生费用，保留数据卷）

### 6.1 停主服务与两个 MCP

- **推荐**：它们各自运行在一个 PowerShell 窗口里 → 在该窗口按 **`Ctrl+C`**，等进程退出。
- 若是脚本启动的（路径 A）→ 用 `scripts\stop-local.ps1`（它按运行记录核对 PID、启动时间与目标 JAR）。

**禁止**：`taskkill /IM java.exe`、`Get-Process java | Stop-Process`（会误杀别的 Java 进程）。
如果必须按 PID 停止（例如用 `Start-Process -PassThru` 记录过 PID），**先核对身份再停**：

```powershell
$pid8080 = 12345   # 换成你记录的 PID
Get-CimInstance Win32_Process -Filter "ProcessId=$pid8080" | Select-Object ProcessId, Name, CommandLine
# 确认 CommandLine 里是本次启动的 flowdesk-*.jar 与 127.0.0.1 之后：
Stop-Process -Id $pid8080
```

### 6.2 停数据库容器（**保留数据卷**）

```powershell
docker compose -p flowdesk -f compose.postgres.yml stop
docker ps -a --filter 'name=flowdesk-postgres'          # 期望 Exited (0)
docker volume ls | Select-String 'flowdesk-pgdata'      # 卷必须仍然在
```

**禁止** `docker compose … down -v`（会删卷）、`docker volume rm`、`docker system prune`。
卷保留意味着下次演示不用重新上传与索引（上一步索引过的文档还在）。

### 6.3 收尾核对

```powershell
foreach ($port in 8080, 8091, 8092, 5433) {
    $busy = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue).Count -gt 0
    Write-Host "$port listening = $busy"   # 期望全部 False
}
```

---

## 七、本手册的证据口径（请照实引用）

- **路径 A**（构建 / 启动 / 自测 / 停止、404 的解释、`-Mode deepseek` 关闭 Embedding）：
  命令与预期都来自仓库现有脚本与交付配置，本手册编写时做了**静态核对**，并用仓库自带的
  **离线自测**验证了脚本与文档里的命令。
- **路径 B 的完整链路**：本手册**没有在编写时重跑**（重跑会产生供应商费用）。
  其中「预期字段」来自 **FD-0020-E** 验证过的单次事件研判冒烟（2026-09-23：`200`，
  `knowledge FOUND`、`K1` 归属、`asset`/`monitoring` `FOUND` 且 `source=DEMO`、`grounded=true`、
  `usedEvidenceIds=[K1,A1,M1]`、`executionPath` 九步、答案指出路由器流程与服务器类型不匹配），
  以及 FD-0020-C / FD-0020-D 的 PostgreSQL 与向量化联调记录。
  **引用它们时请写成历史证据，不要写成「本次实测」。**
- 相关文档：`docs/local-run.md`（脚本细节）、`docs/postgres-local.md`（数据库联调与重启验收）、
  `docs/ai-demo.md`（AI 演示脚本）、`README.md` 第十四章（真实验证状态表）。
