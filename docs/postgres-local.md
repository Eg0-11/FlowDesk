# 本地 PostgreSQL + pgvector 联调（FD-0020-C）

用 `compose.postgres.yml` 在本机跑一个**只绑回环地址**的 PostgreSQL 16 + pgvector 0.8.6，
让主服务以 `postgres` profile 连真实数据库做联调。镜像与两个 Testcontainers 集成测试
使用的一致（`pgvector/pgvector:0.8.6-pg16`），因此容器上的行为与集成测试的验证对象相同。

> 这里只解决「有一台属于本项目的本地数据库」。**真实 DeepSeek / DashScope 仍未验证**
> （见文末边界），向量化开关在本流程中保持关闭。

---

## 1. 前置

| 项 | 要求 |
| --- | --- |
| Docker | Docker Desktop 已安装且**引擎已启动**（`docker info` 能返回 `OSTYPE=linux`） |
| JDK | 17（示例用 `C:\Users\ll189\.jdks\jdk-17.0.20.1+1`） |
| 主服务 JAR | 已构建：`flowdesk-bootstrap/target/flowdesk-bootstrap-0.1.0-SNAPSHOT.jar` |
| 端口 | 只用 `127.0.0.1:5433`（避开默认 5432，不占用、不冲突已有数据库） |

## 2. 数据库密码：只放本机环境变量

密码**不写入仓库、不写入文档、不出现在任何报告里**。compose 文件用
`${FLOWDESK_DB_PASSWORD:?...}` 读取它：变量缺失时 compose **直接报错退出**，
不会用空密码或默认密码把库跑起来。

```powershell
# PowerShell：设置用户级环境变量（一次即可，之后每个新 shell 都能读到）
$env:FLOWDESK_DB_PASSWORD = '<你的密码>'
[Environment]::SetEnvironmentVariable('FLOWDESK_DB_PASSWORD', $env:FLOWDESK_DB_PASSWORD, 'User')

# 或者：bash
# export FLOWDESK_DB_PASSWORD='<你的密码>'
```

> **注意**：compose 对所有子命令（包括 `restart`、`stop`）都会先插值整个文件，
> 所以**每条 compose 命令都必须在设置了该变量的 shell 里执行**，否则报
> `required variable FLOWDESK_DB_PASSWORD is missing a value`。
> 新开的终端若读不到该变量，先执行
> `$env:FLOWDESK_DB_PASSWORD = [Environment]::GetEnvironmentVariable('FLOWDESK_DB_PASSWORD','User')`。

首次执行本流程时，密码由操作者在本机生成并写入上述环境变量；**换了密码又复用旧数据卷会导致连不上**
（PostgreSQL 只在数据目录为空时用环境变量初始化密码），此时要么用回原密码，要么新建卷。

## 3. 启停（全部限定在 `flowdesk` 项目范围内）

```powershell
# 启动（后台）
docker compose -p flowdesk -f D:\FlowDesk\compose.postgres.yml up -d

# 看状态与本机端口绑定（应显示 127.0.0.1:5433->5432/tcp）
docker compose -p flowdesk -f D:\FlowDesk\compose.postgres.yml ps

# 等健康
docker inspect --format '{{.State.Health.Status}}' flowdesk-postgres    # -> healthy

# 日志
docker compose -p flowdesk -f D:\FlowDesk\compose.postgres.yml logs --tail 50

# 重启（保留数据卷）—— 用于验证持久化
docker compose -p flowdesk -f D:\FlowDesk\compose.postgres.yml restart

# 停止（保留容器与数据卷）
docker compose -p flowdesk -f D:\FlowDesk\compose.postgres.yml stop
# 或移除容器但**保留命名卷**
docker compose -p flowdesk -f D:\FlowDesk\compose.postgres.yml down
```

### 三条硬约束（请勿违反）

1. **不要用 `down -v`**：`-v` 会连命名卷 `flowdesk-pgdata` 一起删掉，等于删库。要清数据请显式
   `docker volume rm flowdesk-pgdata`（并清楚自己在做什么）。
2. **只操作本项目**：所有命令都带 `-p flowdesk`（文件里也写了 `name: flowdesk`），
   compose 只会碰本项目自己的容器/网络/卷；**不要**执行 `docker system prune`、
   `docker volume prune`、`docker rm -f $(docker ps -aq)` 之类的全局清理。
3. **端口只绑回环**：`127.0.0.1:5433:5432`，不对局域网暴露；如果 5433 也被占用，
   改 compose 里的宿主端口并同步改 `FLOWDESK_DB_URL`。

## 4. 用主服务连它（现有 JAR + postgres profile）

主服务用 `postgres` profile 读取三个环境变量；本流程**显式关闭** AI、Embedding 与 MCP 客户端
（不需要任何模型 Key，也不去连 MCP 服务）。

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Users\ll189\.jdks\jdk-17.0.20.1+1'
$env:FLOWDESK_DB_URL      = 'jdbc:postgresql://127.0.0.1:5433/flowdesk'
$env:FLOWDESK_DB_USERNAME = 'flowdesk'
$env:FLOWDESK_DB_PASSWORD = [Environment]::GetEnvironmentVariable('FLOWDESK_DB_PASSWORD','User')

# 本机进程环境里存在 Path/PATH、HTTP_PROXY/http_proxy 等**仅大小写不同**的重复变量，
# PowerShell 5.1 的 Start-Process 会因此报「字典中的关键字」而失败 —— 先折叠成单一拼写
# （只影响本 shell 与它启动的子进程，不改用户/系统环境变量）。
$vars = [System.Environment]::GetEnvironmentVariables()
foreach ($g in ($vars.Keys | Group-Object { $_.ToString().ToLowerInvariant() } | Where-Object { $_.Count -gt 1 })) {
    $names = @($g.Group | ForEach-Object { $_.ToString() })
    $canonical = ($names | Where-Object { $_ -ceq $_.ToUpperInvariant() } | Select-Object -First 1)
    if (-not $canonical) { $canonical = $names[0] }
    $value = $vars[$canonical]
    foreach ($n in $names) { [System.Environment]::SetEnvironmentVariable($n, $null) }
    [System.Environment]::SetEnvironmentVariable($canonical, $value)
}

$jar = 'D:\FlowDesk\flowdesk-bootstrap\target\flowdesk-bootstrap-0.1.0-SNAPSHOT.jar'
$proc = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$env:TEMP\flowdesk-main.out.log" `
    -RedirectStandardError  "$env:TEMP\flowdesk-main.err.log" `
    -ArgumentList @('-jar', $jar,
        '--spring.profiles.active=postgres',
        '--server.port=8080', '--server.address=127.0.0.1',
        '--flowdesk.ai.enabled=false',
        '--flowdesk.knowledge.embedding.enabled=false',
        '--flowdesk.mcp.client.enabled=false')

# 记录**本次启动的身份**（PID + 启动时间 + JAR）。§5.1 只会终止与这份记录一致、
# 且命令行确实是本服务的进程；记录缺失或不一致时该步骤**停止并报告**，不猜、不盲杀。
@{ pid = $proc.Id; startTime = $proc.StartTime.ToString('o'); jar = $jar } |
    ConvertTo-Json | Set-Content -LiteralPath "$env:TEMP\flowdesk-main.identity.json" -Encoding UTF8
Write-Host "已启动主服务：PID=$($proc.Id)，身份记录写入 $env:TEMP\flowdesk-main.identity.json"
```

- `--server.port` / `--server.address` 显式给出：命令行参数优先级最高，宿主机注入的
  `SERVER__PORT` / `SERVER__HOST` 覆盖不了它。
- 启动时会自动执行 Flyway：`db/migration`（V1–V5）+ `db/postgresql-migration`（V6，建 pgvector 扩展与向量表）。
- 健康检查：`Invoke-RestMethod http://127.0.0.1:8080/actuator/health`（应为 `UP`）。
- 日志在 `%TEMP%\flowdesk-main.out.log`（`Start-Process` 重定向，不再往终端刷屏）。

## 5. 验收脚本（可复制执行）

```powershell
# 1) 迁移历史（V1..V6，success=true）
docker exec flowdesk-postgres psql -U flowdesk -d flowdesk -tAc `
  "SELECT installed_rank||'|'||version||'|'||description||'|'||success FROM flyway_schema_history ORDER BY installed_rank;"

# 2) pgvector 扩展
docker exec flowdesk-postgres psql -U flowdesk -d flowdesk -tAc `
  "SELECT extname||'|'||extversion FROM pg_extension WHERE extname='vector';"      # -> vector|0.8.6

# 3) 向量表与 HNSW 索引
docker exec flowdesk-postgres psql -U flowdesk -d flowdesk -tAc `
  "SELECT indexname||'|'||indexdef FROM pg_indexes WHERE tablename='knowledge_document_chunk_embeddings';"
#  期望包含：USING hnsw (embedding vector_cosine_ops)

# 4) 创建工单（201 + Location + ETag）
$body = '{"title":"联调验收","description":"验证持久化","category":"SOFTWARE","priority":"P3","requesterId":"u-verify-1"}'
$bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($body)
$r = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/v1/tickets' -Method POST `
     -ContentType 'application/json' -Body $bytes -UseBasicParsing
$id = ([string]$r.Headers['Location']).Split('/')[-1]
"create=$([int]$r.StatusCode) id=$id"

# 5) 读回（200）
(Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/v1/tickets/$id" -UseBasicParsing).StatusCode

# 6) 重启主服务后再读、重启数据库容器后再读 —— 见下面 5.1/5.2 的完整步骤
```

> `psql` 走容器内**本地 socket**（镜像的 `pg_hba.conf` 对 local 是 trust），
> 因此这些命令**不需要、也不会打印**数据库密码；不要在命令行里传 `PGPASSWORD`。

### 5.1 重启主服务后复读（可执行步骤）

**安全前提**：这一步会终止一个进程，因此**只终止能完整核实的那个进程**。五条判据必须**同时**成立：

① PID 与 §4 写下的身份记录一致；② 进程名是 `java.exe`/`javaw.exe`；
③ 命令行指向本仓库的 `flowdesk-bootstrap-*.jar`；④ 命令行带 `spring.profiles.active=…postgres`；
⑤ **进程的真实启动时间**与身份记录里的 `startTime` 一致 —— 这条不可省：PID 会被系统回收复用，
只有启动时间能排除「同 PID 的另一个进程」。

第 ⑤ 条**取不到**（身份记录缺 `startTime`、时间不可解析、进程已退出或 `CreationDate` 不可读）或**与记录不一致**时，
一律**拒绝终止**。任一条不满足即失败退出（`exit 1`），并且拒绝分支**只输出安全的判定结果（布尔）与人工指引**，
**不回显完整命令行**（命令行可能带路径与参数，本步骤没有理由把它打印出来）。

```powershell
$ErrorActionPreference = 'Stop'
$id = '<第 4 步返回的工单 id>'
$identityFile = "$env:TEMP\flowdesk-main.identity.json"

# ---------- 1) 核实「8080 的持有者」确实是 §4 启动的那个主服务 ----------
if (-not (Test-Path -LiteralPath $identityFile)) {
    Write-Host "[停止] 找不到身份记录 $identityFile —— 无法证明 8080 的持有者是不是本流程启动的主服务。" -ForegroundColor Red
    Write-Host '        请用 §4 的命令启动（它会写入身份记录），或人工确认该进程后再处理；本步骤不做任何终止。'
    exit 1
}
$record = Get-Content -LiteralPath $identityFile -Raw | ConvertFrom-Json

$conn = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $conn) {
    Write-Host '8080 上没有监听进程：主服务已停止，跳过第 1 步。'
} else {
    $ownerPid = [int]$conn.OwningProcess
    $proc     = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerPid"
    $cmd      = [string]$proc.CommandLine

    $isJavaProcess = ($proc.Name -ieq 'java.exe') -or ($proc.Name -ieq 'javaw.exe')
    $isOurJar      = [bool]($cmd -match 'flowdesk-bootstrap-[^"\s]*\.jar')
    $isPgProfile   = [bool]($cmd -match 'spring\.profiles\.active=[^"\s]*postgres')
    $matchesRecord = ([int]$record.pid -eq $ownerPid) -and ($cmd -like "*$($record.jar)*")

    # 启动时间核对：PID 会被系统回收复用，只有启动时间能排除「同 PID 的另一个进程」
    $recordedStart = $null
    if ($record.PSObject.Properties['startTime'] -and $record.startTime) {
        $parsed = [datetimeoffset]::MinValue
        if ([datetimeoffset]::TryParse([string]$record.startTime, [ref]$parsed)) { $recordedStart = $parsed }
    }
    $actualStart = $null
    if ($proc.CreationDate) { $actualStart = [datetimeoffset]$proc.CreationDate }

    $startTimeAvailable = ($null -ne $recordedStart) -and ($null -ne $actualStart)
    $startTimeMatches = $false
    if ($startTimeAvailable) {
        # 允许 2 秒偏差：记录时刻与内核创建时刻之间本来就有正常的微小间隔
        $startTimeMatches = [math]::Abs(($actualStart - $recordedStart).TotalSeconds) -le 2
    }

    if (-not ($isJavaProcess -and $isOurJar -and $isPgProfile -and $matchesRecord -and $startTimeAvailable -and $startTimeMatches)) {
        Write-Host '[停止] 无法证明 8080 的持有者是本次启动的 FlowDesk 主服务，因此**不做任何终止**。' -ForegroundColor Red
        Write-Host '        安全判定结果（只列布尔值，不回显命令行内容）：'
        Write-Host ("          进程名是 java/javaw           : " + $isJavaProcess)
        Write-Host ("          命令行指向本仓库 JAR          : " + $isOurJar)
        Write-Host ("          命令行带 postgres profile     : " + $isPgProfile)
        Write-Host ("          PID / JAR 与身份记录一致      : " + $matchesRecord)
        Write-Host ("          启动时间可取到                : " + $startTimeAvailable)
        Write-Host ("          启动时间与身份记录一致        : " + $startTimeMatches)
        Write-Host '        请人工确认该进程是什么、由谁启动；本步骤不会结束无法核实的进程。'
        exit 1
    }

    # 五条判据全部成立才终止（-Force 只在核实之后使用）
    Stop-Process -Id $ownerPid -Force
    Write-Host "已核实并停止本次启动的主服务：PID=$ownerPid（启动时间与身份记录一致）"
}

# ---------- 2) 等端口释放：最多 30 秒，超时即失败退出 ----------
$deadline = (Get-Date).AddSeconds(30)
while (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) {
    if ((Get-Date) -gt $deadline) {
        Write-Host '[失败] 等待 8080 释放超时（30 秒）：主服务可能没停下来，请查日志后重试。' -ForegroundColor Red
        exit 1
    }
    Start-Sleep -Milliseconds 500
}
Write-Host '8080 已释放。'

# ---------- 3) 重新启动主服务（与 §4 相同的命令） ----------
#     若 Start-Process 报「字典中的关键字」冲突，先执行 §4 代码块里的折叠片段再跑这一段。
$jar = 'D:\FlowDesk\flowdesk-bootstrap\target\flowdesk-bootstrap-0.1.0-SNAPSHOT.jar'
$env:FLOWDESK_DB_PASSWORD = [Environment]::GetEnvironmentVariable('FLOWDESK_DB_PASSWORD','User')
$proc = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$env:TEMP\flowdesk-main.out.log" `
    -RedirectStandardError  "$env:TEMP\flowdesk-main.err.log" `
    -ArgumentList @('-jar', $jar, '--spring.profiles.active=postgres',
        '--server.port=8080', '--server.address=127.0.0.1',
        '--flowdesk.ai.enabled=false', '--flowdesk.knowledge.embedding.enabled=false',
        '--flowdesk.mcp.client.enabled=false')
@{ pid = $proc.Id; startTime = $proc.StartTime.ToString('o'); jar = $jar } |
    ConvertTo-Json | Set-Content -LiteralPath $identityFile -Encoding UTF8
Write-Host "已重启主服务：PID=$($proc.Id)"

# ---------- 4) 等主服务 UP：最多 120 秒，超时即失败退出 ----------
$deadline = (Get-Date).AddSeconds(120)
$h = ''
while ($h -ne 'UP') {
    if ((Get-Date) -gt $deadline) {
        Write-Host '[失败] 等待主服务健康（UP）超时（120 秒）；看 %TEMP%\flowdesk-main.out.log 定位原因。' -ForegroundColor Red
        exit 1
    }
    try { $h = (Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 3).status } catch { $h = '' }
    if ($h -ne 'UP') { Start-Sleep -Seconds 2 }
}
Write-Host '主服务已 UP。'

# ---------- 5) 复读同一张工单：期望 200 ----------
$r = Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/v1/tickets/$id" -UseBasicParsing -TimeoutSec 15
Write-Host ("复读状态码=" + [int]$r.StatusCode + "（期望 200）")

# ---------- 6) 确认迁移没有重跑（第二次启动的日志里应出现） ----------
Select-String -LiteralPath "$env:TEMP\flowdesk-main.out.log" -Pattern 'Current version of schema|No migration necessary' |
    ForEach-Object { $_.Line.Trim() }
```

> 不要按进程名批量结束 java（会误伤其它服务），也不要跳过第 1 步的核实直接 `Stop-Process`。

### 5.2 重启数据库容器后复读（先等 healthy，再读）

```powershell
$ErrorActionPreference = 'Stop'
$env:FLOWDESK_DB_PASSWORD = [Environment]::GetEnvironmentVariable('FLOWDESK_DB_PASSWORD','User')
$id = '<第 4 步返回的工单 id>'

# ---------- 1) 重启数据库容器（保留命名卷；**不要**用 down -v） ----------
docker compose -p flowdesk -f D:\FlowDesk\compose.postgres.yml restart
if ($LASTEXITCODE -ne 0) { Write-Host '[失败] compose restart 退出码非 0' -ForegroundColor Red; exit 1 }

# ---------- 2) 等数据库 healthy：最多 120 秒，超时即失败退出 ----------
#     关键：容器进程起来 ≠ 数据库可接受连接；必须等 healthcheck 通过再读。
$deadline = (Get-Date).AddSeconds(120)
$health = ''
while ($health -ne 'healthy') {
    if ((Get-Date) -gt $deadline) {
        Write-Host "[失败] 等待数据库 healthy 超时（120 秒），当前状态=$health；用 compose logs 定位原因。" -ForegroundColor Red
        exit 1
    }
    $health = (docker inspect --format '{{.State.Health.Status}}' flowdesk-postgres).Trim()
    if ($health -ne 'healthy') { Start-Sleep -Seconds 2 }
}
Write-Host '数据库已 healthy。'

# ---------- 3) 只读确认数据仍在（走容器内本地 socket，不需要密码） ----------
$count = (docker exec flowdesk-postgres psql -U flowdesk -d flowdesk -tAc "SELECT COUNT(*) FROM tickets WHERE id = '$id';").Trim()
Write-Host "库内行数=$count（期望 1）"
if ($count -ne '1') { Write-Host '[失败] 重启后库内数据与预期不一致' -ForegroundColor Red; exit 1 }

# ---------- 4) 再经 HTTP 读同一张工单：期望 200 ----------
#     连接池需要重连：第一次若遇到连接类错误，等 2 秒重试**一次**（最多一次，不做无限重试）。
$status = 0
foreach ($attempt in 1..2) {
    try {
        $status = [int](Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/v1/tickets/$id" -UseBasicParsing -TimeoutSec 15).StatusCode
        break
    } catch {
        Write-Host "第 $attempt 次请求失败：$($_.Exception.Message)"
        if ($attempt -lt 2) { Start-Sleep -Seconds 2 }
    }
}
Write-Host "复读状态码=$status（期望 200）"
if ($status -ne 200) { Write-Host '[失败] 重启数据库容器后读不到该工单' -ForegroundColor Red; exit 1 }
```

> 顺序很重要：**先等到 healthy 再读**。容器刚 `restart` 时进程已存在但 `pg_isready` 还没通过，
> 这时请求会失败——那不是数据丢失，也不该被记成「重启后读不到」。
> 真实验收中该顺序的实测结果为：容器 `StartedAt` 变化（真实重启）→ healthy → `COUNT(*)=1` → HTTP **200**。
> 两个等待循环都带**明确超时**（端口释放 30 秒、主服务 UP 120 秒、数据库 healthy 120 秒），
> 超时即以非零退出码失败并提示看哪个日志，不会无限等下去。

## 6. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `required variable FLOWDESK_DB_PASSWORD is missing a value` | 当前 shell 没有该变量（见第 2 节） |
| `docker compose` 报连不上 docker API | Docker Desktop 引擎没启动；先 `docker desktop start` 或打开 Docker Desktop |
| 应用启动失败 `Connection refused` | 容器没起来或端口不是 5433；`compose ps` 看绑定，`docker inspect ... Health` 看健康 |
| 应用启动失败 `password authentication failed` | 复用了旧数据卷但换了密码（见第 2 节末尾） |
| Flyway 报 V6 失败 `extension "vector" is not available` | 用的不是 pgvector 镜像；必须用 `pgvector/pgvector:0.8.6-pg16` |
| 想彻底重来 | `docker compose -p flowdesk -f D:\FlowDesk\compose.postgres.yml down` 然后 `docker volume rm flowdesk-pgdata`（**会删数据**，确认后再执行） |

## 7. 边界（本流程**不**验证什么）

- **真实 DeepSeek / DashScope 仍未验证**：本流程把 `flowdesk.ai.enabled` 与
  `flowdesk.knowledge.embedding.enabled` 都设为 `false`，没有任何模型/向量服务调用。
- 因此事件研判/资产诊断的 AI 端点不注册（404），知识向量化链路没有跑通 ——
  这里验证的是「真实 PostgreSQL + pgvector 上的迁移、表结构、索引与工单持久化」。
- 本流程与 `scripts/start-local.ps1`（Basic/DeepSeek 模式，内存 H2）**互不影响**：
  那条路径继续用 H2，不读这些环境变量。
