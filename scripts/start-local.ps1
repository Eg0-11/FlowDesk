<#
.SYNOPSIS
    启动 FlowDesk 本地三个真实打包服务（FD-0019-A）。

.DESCRIPTION
    按固定顺序启动：资产 MCP（8091）→ 监控 MCP（8092）→ 主服务（8080），
    前两个健康检查通过后才启动主服务。三个服务都显式绑定 127.0.0.1，
    端口、绑定地址与运行模式一律通过命令行参数传给子进程。

    两种模式：
      basic     默认。不需要任何 Key。主服务 AI 关闭、Embedding 关闭；
                两个 MCP 服务显式使用 demo 数据源。
                **此模式没有 AI 回答能力**：AI 接口应当返回 404。
      deepseek  在 basic 的服务组合上启用既有 deepseek profile。
                Key 从进程环境 DEEPSEEK_API_KEY 读取，脚本不接受命令行明文 Key。

    运行信息（PID、启动时间、目标 JAR、端口、模式、日志路径）写入仓库内的
    被 Git 忽略的 .local-run/state.json；停止请用 scripts/stop-local.ps1。

.PARAMETER JdkHome
    JDK 17 的目录。必须显式给出（或显式传 $env:JAVA_HOME）。
    脚本只读取、不修改系统/用户的 JAVA_HOME 或 PATH。

.PARAMETER Mode
    basic（默认）或 deepseek。

.PARAMETER Build
    先执行一次 mvnw clean package（含全部测试）。退出码非零立即停止，不跳过测试。

.PARAMETER McpClient
    显式打开主服务的 MCP 客户端开关，并把它指向本脚本启动的两个回环 MCP 服务。

.PARAMETER HealthTimeoutSec
    单个服务健康检查的等待上限（默认 90 秒）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\Users\me\.jdks\jdk-17.0.20.1+1'
    powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 -JdkHome 'C:\...\jdk-17' -Mode deepseek -McpClient
#>
[CmdletBinding()]
param(
    [string]$JdkHome = '',
    [ValidateSet('basic', 'deepseek')]
    [string]$Mode = 'basic',
    [switch]$Build,
    [switch]$McpClient,
    [int]$HealthTimeoutSec = 90
)

$ErrorActionPreference = 'Stop'

$scriptsDir = Split-Path -Parent $PSCommandPath
. (Join-Path $scriptsDir 'flowdesk-local-common.ps1')

# ---------- 参数范围校验（不合法就什么都不做）----------
# 时间参数必须有界：0/负数会让等待立刻放弃，过大的值会让脚本在故障时长时间不返回。
$rangeError = Test-FlowDeskTimeRange -Name '-HealthTimeoutSec' -Value $HealthTimeoutSec -Min 1 -Max 600
if ($rangeError) {
    Write-FlowDeskTitle 'FlowDesk 本地启动（FD-0019-A）'
    Write-FlowDeskFail "参数不合法：$rangeError"
    Write-FlowDeskInfo '未做任何事（没有启动服务、没有写运行记录、没有改环境）。'
    exit 7
}

Write-FlowDeskTitle 'FlowDesk 本地启动（FD-0019-A）'
Write-FlowDeskInfo "仓库根目录：$global:FlowDeskRepoRoot"
Write-FlowDeskInfo "启动模式  ：$Mode"
Write-FlowDeskInfo "健康检查超时：$HealthTimeoutSec 秒（允许 1..600）"

# 宿主注入的 SERVER__* 变量会被 Spring 的宽松绑定映射成 server.*（例如 SERVER__PORT -> server.port），
# 从而覆盖打包里的 application.yml。本脚本对端口/绑定地址传的是**命令行参数**，优先级更高，
# 所以不受影响；这里只如实提示，方便排障时对上号。
$hostInjected = @()
foreach ($candidate in @('SERVER__PORT', 'SERVER__HOST')) {
    $value = [System.Environment]::GetEnvironmentVariable($candidate)
    if ($value) { $hostInjected += $candidate }
}
if ($hostInjected.Count -gt 0) {
    Write-FlowDeskWarn "当前进程环境里有宿主注入的 $($hostInjected -join '、')；它们会被 Spring 映射成 server.* 并覆盖 application.yml。"
    Write-FlowDeskInfo '  本脚本用命令行参数显式指定端口与绑定地址（优先级更高），因此不受影响。'
}

# ---------- 0. 环境规整（只作用于本脚本进程）----------
# PowerShell 5.1 的 Start-Process 遇到「仅大小写不同」的重复环境变量会直接失败（见共享函数注释）。
# 这里先折叠掉，并在输出里如实说明改了哪些**变量名**（从不输出取值）。
$collapsedNames = @(Remove-FlowDeskDuplicateEnvNames)
if ($collapsedNames.Count -gt 0) {
    Write-FlowDeskWarn "环境里有 $($collapsedNames.Count) 个仅大小写不同的重复变量名，已在本脚本进程内折叠为单一拼写："
    Write-FlowDeskInfo ("  " + ($collapsedNames -join ', '))
    Write-FlowDeskInfo '  （只影响本脚本启动的子进程；未修改系统/用户环境变量，也不涉及任何取值）'
}

# ---------- 1. JDK ----------
Write-FlowDeskStep '1/6 校验 JDK'

if (-not $JdkHome -and $env:JAVA_HOME) {
    $JdkHome = $env:JAVA_HOME
    Write-FlowDeskWarn "未传 -JdkHome，回退使用当前进程的 JAVA_HOME：$JdkHome"
}

try {
    $javaExe = Resolve-FlowDeskJdk -JdkHome $JdkHome
}
catch {
    Write-FlowDeskFail $_.Exception.Message
    exit 4
}
Write-FlowDeskOk "java.exe：$javaExe"
Write-FlowDeskInfo '（未修改系统/用户 JAVA_HOME 或 PATH；子进程用绝对路径启动）'

# ---------- 2. 重复启动与旧记录检查 ----------
Write-FlowDeskStep '2/6 检查是否已经有一套运行实例（并检查旧记录是否可判定）'

$existing = Read-FlowDeskState
$classification = Split-FlowDeskRecordsByVerdict -State $existing

if ($classification.Live.Count -gt 0) {
    Write-FlowDeskWarn "检测到已有 $($classification.Live.Count) 个由本脚本启动且仍在运行的进程，不会启动第二套："
    foreach ($item in $classification.Live) {
        $record = $item.Record
        Write-FlowDeskInfo "  $($record.name)  PID=$($record.pid)  端口=$($record.port)  模式=$($existing.mode)  $($item.Verdict.Reason)"
    }
    Write-FlowDeskInfo '如需重启：先执行 scripts\stop-local.ps1，再运行本脚本。'
    exit 0
}

if ($classification.Unjudgeable.Count -gt 0) {
    # 无法判定的旧记录必须先由人处理：既不能终止（身份不明），也不能当成「已经不存在」丢掉。
    # 因此这里**不启动任何服务**，也**不覆盖**运行记录 —— 否则这些线索就没了。
    Write-FlowDeskFail "运行记录里有 $($classification.Unjudgeable.Count) 条**无法判定**的条目，无法确认它们对应的进程是否还在："
    foreach ($item in $classification.Unjudgeable) {
        $record = $item.Record
        Write-FlowDeskInfo "  name=$($record.name)  pid=$($record.pid)  jar=$($record.jar)  reason=$($item.Verdict.Reason)"
    }
    Write-FlowDeskInfo '为避免覆盖这些线索，本次**不会**启动任何服务，也**不会**改写运行记录。'
    Write-FlowDeskInfo "运行记录：$global:FlowDeskStatePath"
    Write-FlowDeskInfo '人工确认后处理：确认这些进程确实不在，就删除该记录文件；否则请先手动处理这些进程。'
    exit 8
}

if ($classification.Stale.Count -gt 0) {
    Write-FlowDeskInfo "运行记录里有 $($classification.Stale.Count) 条陈旧条目（已实际确认对应 PID 不存在），将随本次启动被覆盖。"
}

# ---------- 3. 端口 ----------
Write-FlowDeskStep '3/6 检查三个端口是否空闲'

$portConflict = $false
foreach ($service in $global:FlowDeskServices) {
    $occupant = Format-FlowDeskPortOccupant -Port $service.Port
    if ($occupant) {
        Write-FlowDeskFail $occupant
        $portConflict = $true
    }
    else {
        Write-FlowDeskOk "端口 $($service.Port) 空闲（$($service.DisplayName)）"
    }
}
if ($portConflict) {
    Write-FlowDeskInfo '本脚本不会终止未知进程：请先确认占用者，或用 scripts\stop-local.ps1 停止上一套实例。'
    exit 2
}

# ---------- 4. 构建（可选）----------
if ($Build) {
    Write-FlowDeskStep '4/6 构建（mvnw clean package，含全部测试）'

    Initialize-FlowDeskLogDir
    $buildLog = Join-Path $global:FlowDeskLogDir ("build-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
    Write-FlowDeskInfo "构建日志：$buildLog"

    $mvnw = Join-Path $global:FlowDeskRepoRoot 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mvnw -PathType Leaf)) {
        Write-FlowDeskFail "找不到 Maven Wrapper：$mvnw"
        exit 5
    }
    if (Test-Path -LiteralPath $buildLog -PathType Leaf) { Remove-Item -LiteralPath $buildLog -Force }

    # JAVA_HOME 只对本进程与其子进程有效，不改动系统/用户环境变量
    $env:JAVA_HOME = (Split-Path -Parent (Split-Path -Parent $javaExe))

    # 必须显式指定 POM：Maven 默认用**进程当前目录**找 POM，
    # 而本脚本允许从任意目录调用（例如仓库外），那样会以
    # 「no POM in this directory」失败 —— 与仓库内容无关的假失败。
    $pomPath = Join-Path $global:FlowDeskRepoRoot 'pom.xml'

    # 本脚本整体是 $ErrorActionPreference='Stop'，但**原生命令写到 stderr 的内容**
    # （Maven 与 JVM 的正常告警，例如 "Sharing is only supported ..."）在 Stop 下会被当成
    # 终止性错误，构建因此会在跑到一半时被打断。这里只在构建调用周围局部放宽。
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $mvnw -B -f $pomPath clean package 2>&1 | Tee-Object -FilePath $buildLog
        $buildExit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($buildExit -ne 0) {
        Write-FlowDeskFail "构建失败，退出码 $buildExit。已停止，不会启动任何服务。"
        Write-FlowDeskInfo "完整输出见：$buildLog"
        exit 5
    }
    Write-FlowDeskOk "构建成功（退出码 0）"
}
else {
    Write-FlowDeskStep '4/6 构建：跳过（未指定 -Build，复用已有 JAR）'
    Write-FlowDeskInfo '如果 JAR 缺失，请先执行：mvnw.cmd clean package'
}

# ---------- 5. 目标 JAR ----------
Write-FlowDeskStep '5/6 定位三个目标 JAR'

$jarPaths = @{}
$missing = @()
foreach ($service in $global:FlowDeskServices) {
    $jar = Get-FlowDeskServiceJar -Service $service
    if ($jar) {
        $jarPaths[$service.Name] = $jar
        Write-FlowDeskOk "$($service.DisplayName)：$jar"
    }
    else {
        $missing += $service
        Write-FlowDeskFail "$($service.DisplayName) 缺少打包产物（$($service.Module)\target\$($service.JarName)）"
    }
}
if ($missing.Count -gt 0) {
    Write-FlowDeskInfo '请先构建：'
    Write-FlowDeskInfo '  mvnw.cmd clean package          （或重新运行本脚本并加上 -Build）'
    exit 3
}

# ---------- 6. DeepSeek 模式：先查 Key，再启动任何服务 ----------
Write-FlowDeskStep '6/6 启动服务'

$mainServiceArgs = @(
    '--server.port=8080'
    '--server.address=127.0.0.1'
)

if ($Mode -eq 'deepseek') {
    # 缺 Key 时在启动任何服务之前失败：固定提示，绝不输出 Key 的值
    if (-not $env:DEEPSEEK_API_KEY -or -not $env:DEEPSEEK_API_KEY.Trim()) {
        Write-FlowDeskFail 'DeepSeek 模式需要进程环境变量 DEEPSEEK_API_KEY，但当前环境里没有取到。'
        Write-FlowDeskInfo '请先在同一个 shell 里设置它，然后重新运行本脚本：'
        Write-FlowDeskInfo "  `$env:DEEPSEEK_API_KEY = '<你的 Key>'"
        Write-FlowDeskInfo '本脚本不接受命令行明文 Key 参数，也不会回显 Key 的值。'
        Write-FlowDeskInfo '未启动任何服务（三个端口仍然空闲）。'
        exit 6
    }
    Write-FlowDeskOk '已从进程环境读到 DEEPSEEK_API_KEY（只检查是否存在，不输出其值）'

    $mainServiceArgs += @(
        '--spring.profiles.active=deepseek'
        '--flowdesk.ai.enabled=true'
        # Embedding 仍然关闭：知识分支不可用（检索会明确回答 DISABLED，而不是假装没有数据）
        '--flowdesk.knowledge.embedding.enabled=false'
    )
}
else {
    $mainServiceArgs += @(
        '--flowdesk.ai.enabled=false'
        '--flowdesk.knowledge.embedding.enabled=false'
    )
}

if ($McpClient) {
    $mainServiceArgs += @(
        '--flowdesk.mcp.client.enabled=true'
        '--flowdesk.mcp.client.asset.base-url=http://127.0.0.1:8091'
        '--flowdesk.mcp.client.monitoring.base-url=http://127.0.0.1:8092'
    )
    Write-FlowDeskInfo '主服务 MCP 客户端：显式开启，指向本机 8091/8092'
}
else {
    # 未指定也要**显式**传 false：这样「关着」是本次启动确定的事实，
    # 而不是继承环境里的某个开关（默认值或外部注入都可能改变它）。
    $mainServiceArgs += @('--flowdesk.mcp.client.enabled=false')
    Write-FlowDeskInfo '主服务 MCP 客户端：显式关闭（--flowdesk.mcp.client.enabled=false）'
    Write-FlowDeskInfo '  因此主服务拿不到资产与监控证据：诊断/研判里的两个查询会明确回答 DISABLED，'
    Write-FlowDeskInfo '  而不会伪装成「数据不存在」。需要证据就用 -McpClient 重新启动。'
}

Initialize-FlowDeskLogDir
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$client = New-FlowDeskHttpClient
$records = @()

<#
    组装一条运行记录（身份 = PID + 进程启动时间 + 目标 JAR + 端口）。
#>
function New-FlowDeskServiceRecord([psobject]$Service, $Process, [string]$JarPath, [string]$OutLog,
        [string]$ErrLog) {

    return [pscustomobject]@{
        name             = $Service.Name
        displayName      = $Service.DisplayName
        pid              = $Process.Id
        processStartTime = $Process.StartTime.ToUniversalTime().ToString('o')
        jar              = $JarPath
        port             = $Service.Port
        address          = '127.0.0.1'
        url              = "http://127.0.0.1:$($Service.Port)"
        stdoutLog        = $OutLog
        stderrLog        = $ErrLog
    }
}

<#
    启动一个服务：**创建 JVM 成功后立刻登记身份并落盘**，然后才做健康检查。

    顺序刻意如此 —— 健康检查可能超时、后续服务可能起不来、写记录本身也可能失败，
    而这些情况下当前这个 JVM 都已经真实存在了。只有「先登记、再等健康」才能保证
    失败清理找得到它，而不是留下一个没人认领的进程。

    失败返回 $null（调用方负责清理本次已创建的全部进程）。
#>
function Start-FlowDeskOne {
    param(
        [Parameter(Mandatory = $true)][psobject]$Service,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $stdOutLog = Join-Path $global:FlowDeskLogDir "$($Service.Name)-$stamp.out.log"
    $stdErrLog = Join-Path $global:FlowDeskLogDir "$($Service.Name)-$stamp.err.log"

    Write-FlowDeskInfo "启动 $($Service.DisplayName)（端口 $($Service.Port)）…"
    Write-FlowDeskInfo "  stdout：$stdOutLog"
    Write-FlowDeskInfo "  stderr：$stdErrLog"

    $process = Start-FlowDeskServiceProcess -JavaExe $javaExe -JarPath $jarPaths[$Service.Name] `
        -Arguments $Arguments -StdOutLog $stdOutLog -StdErrLog $stdErrLog

    # ---- 立即登记（不等健康检查）----
    # 先放进本次清单，再落盘：即使落盘自己失败，内存清单也能让失败清理找到这个进程。
    $record = New-FlowDeskServiceRecord -Service $Service -Process $process `
        -JarPath $jarPaths[$Service.Name] -OutLog $stdOutLog -ErrLog $stdErrLog
    $script:records += $record
    Save-FlowDeskRunState -Mode $Mode -McpClient ([bool]$McpClient) -JavaExe $javaExe -Services $script:records
    Write-FlowDeskInfo "  已登记身份并写入运行记录：PID=$($process.Id)（不等健康检查通过）"

    $health = Wait-FlowDeskHealth -Client $client -BaseUrl "http://127.0.0.1:$($Service.Port)" `
        -TimeoutSec $HealthTimeoutSec

    if (-not $health.Ok) {
        Write-FlowDeskFail "$($Service.DisplayName) 未在 $HealthTimeoutSec 秒内健康（最后状态：$($health.Note)）"
        Write-FlowDeskInfo "请查看日志：$stdOutLog"
        if (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) {
            Write-FlowDeskInfo "  该进程**仍在运行**（PID=$($process.Id)）：它已登记，会在失败清理里被终止。"
        }
        else {
            Write-FlowDeskInfo '  该进程已经退出（启动失败），日志里有具体原因。'
        }
        return $null
    }

    Write-FlowDeskOk "$($Service.DisplayName) 健康（$($health.Note)）PID=$($process.Id)"
    return $record
}

<#
    启动失败后的收尾：清理本次已创建的全部进程，并按结果决定是否保留运行记录。

    只有**全部**清理成功才删除运行记录；只要有一个进程没能清理掉（身份无法证明或终止失败），
    就保留它以便重试，并且**不会**输出「全部清理」。
#>
function Complete-FlowDeskStartupFailure([string]$Reason) {
    $started = @($script:records)

    if ($started.Count -eq 0) {
        Write-FlowDeskInfo '本次没有创建任何进程，无需清理。'
        Remove-FlowDeskState
        return $true
    }

    Write-FlowDeskWarn "清理本次已创建的 $($started.Count) 个进程（原因：$Reason）…"
    $result = Clear-FlowDeskStartedProcesses -Records $started

    foreach ($item in $result.Cleared) {
        Write-FlowDeskOk "  $($item.Record.name)（PID=$($item.Record.pid)）已停止[$($item.Method)]"
    }
    foreach ($item in $result.AlreadyGone) {
        Write-FlowDeskInfo "  $($item.Record.name)（PID=$($item.Record.pid)）已自行退出，无需清理"
    }
    foreach ($item in $result.Unresolved) {
        Write-FlowDeskFail "  $($item.Record.name)（PID=$($item.Record.pid)）未能清理：$($item.Reason)"
    }

    if ($result.AllCleared) {
        Remove-FlowDeskState
        Write-FlowDeskOk "本次启动的 $($started.Count) 个进程已全部清理，运行记录已删除。"
        return $true
    }

    $remaining = @($result.Unresolved | ForEach-Object { $_.Record })
    try {
        Save-FlowDeskRunState -Mode $Mode -McpClient ([bool]$McpClient) -JavaExe $javaExe -Services $remaining
        Write-FlowDeskInfo "  运行记录已保留 $($remaining.Count) 条未清理的记录，可直接重试。"
    }
    catch {
        Write-FlowDeskFail "  保留运行记录也失败了：$($_.Exception.Message)"
        Write-FlowDeskInfo "  未清理的进程（请人工确认）："
        foreach ($record in $remaining) {
            Write-FlowDeskInfo "    $($record.name) PID=$($record.pid) JAR=$($record.jar)"
        }
    }
    Write-FlowDeskFail "清理**未完成**：$($result.Unresolved.Count) 个进程仍然存在（未输出「全部清理」）。"
    Write-FlowDeskInfo "  重试：powershell -ExecutionPolicy Bypass -File `"$($global:FlowDeskScriptsDir)\stop-local.ps1`""
    return $false
}

# 先起两个 MCP 服务（各带显式的 demo 数据源与回环绑定）
$mcpServices = @($global:FlowDeskServices | Where-Object { $_.Name -ne 'main-service' })
$mcpArguments = @{
    'asset-mcp'      = @('--server.port=8091', '--server.address=127.0.0.1', '--flowdesk.asset.directory.mode=demo')
    'monitoring-mcp' = @('--server.port=8092', '--server.address=127.0.0.1', '--flowdesk.monitoring.source.mode=demo')
}
# 两个 MCP 服务都健康之后才启动主服务
$mainService = $global:FlowDeskServices | Where-Object { $_.Name -eq 'main-service' }

# 整个启动序列包在 try/catch 里：任何未预料的异常（含写运行记录失败）都要走同一条清理路径
$startupFailure = $null
try {
    foreach ($service in $mcpServices) {
        if (-not (Start-FlowDeskOne -Service $service -Arguments $mcpArguments[$service.Name])) {
            $startupFailure = "$($service.DisplayName) 启动失败或健康检查超时"
            break
        }
    }
    if (-not $startupFailure) {
        if (-not (Start-FlowDeskOne -Service $mainService -Arguments $mainServiceArgs)) {
            $startupFailure = 'FlowDesk 主服务启动失败或健康检查超时'
        }
    }
}
catch {
    $startupFailure = "启动过程中出现异常：$($_.Exception.Message)"
}

if ($startupFailure) {
    Write-Host ''
    Write-FlowDeskFail "启动失败：$startupFailure"
    $null = Complete-FlowDeskStartupFailure -Reason $startupFailure
    exit 6
}

Write-FlowDeskOk "三个服务已启动并写入运行记录（$($script:records.Count) 条）。"

# ---------- 汇总 ----------
Write-FlowDeskTitle '启动完成'

Write-FlowDeskInfo "模式：$Mode"
foreach ($record in $records) {
    Write-FlowDeskInfo ("  {0,-16} {1}  PID={2}" -f $record.name, $record.url, $record.pid)
}

Write-Host ''
Write-FlowDeskInfo '健康检查：'
foreach ($record in $records) {
    Write-FlowDeskInfo "  $($record.url)/actuator/health"
}
Write-FlowDeskInfo '资产 MCP 端点：http://127.0.0.1:8091/mcp    工具：asset_get'
Write-FlowDeskInfo '监控 MCP 端点：http://127.0.0.1:8092/mcp    工具：monitoring_snapshot_get'

Write-Host ''
Write-FlowDeskInfo "运行记录：$global:FlowDeskStatePath"
Write-FlowDeskInfo "日志目录：$global:FlowDeskLogDir"
Write-Host ''
Write-FlowDeskInfo '停止：'
Write-FlowDeskInfo "  powershell -ExecutionPolicy Bypass -File `"$($global:FlowDeskScriptsDir)\stop-local.ps1`""
Write-FlowDeskInfo '冒烟检查（不启动服务，只检查现有实例）：'
Write-FlowDeskInfo "  powershell -ExecutionPolicy Bypass -File `"$($global:FlowDeskScriptsDir)\test-local.ps1`""

# ---------- 模式边界（避免把「能启动」误解成「能回答」）----------
Write-Host ''
if ($Mode -eq 'deepseek') {
    Write-FlowDeskTitle '模式边界（DeepSeek 模式）'
    Write-FlowDeskInfo '本模式启用既有 deepseek profile，因此资产诊断与事件研判接口是**注册的**。'
    Write-FlowDeskInfo '本次启动**没有**调用任何付费模型：是否产生费用取决于你之后如何去调用它。'
    Write-FlowDeskWarn 'Embedding 仍关闭：知识检索分支不可用（明确回答 DISABLED，而不是假装没有数据），'
    Write-FlowDeskWarn '因此事件研判只能使用资产与监控的演示证据。'
    if ($McpClient) {
        Write-FlowDeskInfo 'MCP 客户端已开启：资产与监控证据可用（指向本机 8091/8092 的演示数据源）。'
    }
    else {
        Write-FlowDeskWarn 'MCP 客户端未开启（未指定 -McpClient）：主服务**没有资产与监控证据** ——'
        Write-FlowDeskWarn '  诊断/研判里的两个查询会明确回答 DISABLED（不是「数据不存在」），'
        Write-FlowDeskWarn '  研判通常只能走降级路径。要演示真实证据链路，请加 -McpClient 重新启动。'
    }
    Write-FlowDeskInfo '演示数据是虚构的（source=DEMO），不是真实企业数据源。'
}
else {
    Write-FlowDeskTitle '模式边界（Basic 模式）'
    Write-FlowDeskInfo '此模式可验证：三个服务能否启动、工单接口、两个 MCP 服务的真实协议与演示数据。'
    Write-FlowDeskWarn '此模式**没有 AI 回答能力**：资产诊断与事件研判接口都是 404，'
    Write-FlowDeskWarn '不能宣称可以生成诊断或研判答案（知识检索也因 Embedding 关闭而不可用）。'
    Write-FlowDeskInfo '演示数据是虚构的（source=DEMO），不是真实企业数据源。'
    Write-FlowDeskInfo '当前没有前端页面：浏览器打开 http://127.0.0.1:8080/ 不会打开产品界面（只会看到 404）。'
}

exit 0
