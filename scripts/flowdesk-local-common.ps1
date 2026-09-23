<#
    FlowDesk 本地运行脚本的共享函数（FD-0019-A）。

    被 scripts/start-local.ps1 / stop-local.ps1 / test-local.ps1 以 dot-source 方式加载。
    本文件自身不执行任何动作：只提供路径解析、JDK 校验、端口检查、HTTP/JSON-RPC 调用、
    进程身份校验、状态文件读写与输出格式化。

    ── 编码约定（重要）─────────────────────────────────────────────────────────
    · 本仓库的 .ps1 使用 **UTF-8 with BOM**。Windows PowerShell 5.1 在没有 BOM 时会按当前
      ANSI 代码页读取脚本文件，中文文案会变成乱码、甚至把注释解析成代码而报错。
    · HTTP 请求体反过来必须是 **UTF-8 without BOM**（见 New-FlowDeskJsonContent）：
      带 BOM 的 JSON 会被服务端的 Jackson 判成「请求体不是合法 JSON」，得到 400。
      两者是相反的要求，不要把它们混为一谈。

    ── 设计约束（来自任务单）──────────────────────────────────────────────────
    · 仓库根路径从脚本自身位置推导，不依赖调用方所在的当前目录；
    · 只通过「PID + 进程名 + 启动时间 + 目标 JAR」四项同时校验来认定进程身份，
      绝不按 java.exe 通杀、也不按端口占用者杀进程；
    · 只读取环境变量，不写入系统/用户环境变量，不修改 PATH。
#>

# ---- 仓库根路径：以本文件所在目录（scripts/）的父目录为准 ----
if (-not $global:FlowDeskScriptsDir) {
    $commonScriptsDir = $PSScriptRoot
    if (-not $commonScriptsDir) {
        $commonScriptsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    }
    $global:FlowDeskScriptsDir = $commonScriptsDir
}
if (-not $global:FlowDeskRepoRoot) {
    $global:FlowDeskRepoRoot = Split-Path -Parent $global:FlowDeskScriptsDir
}

# ---- 运行信息目录（被 .gitignore 忽略）----
$global:FlowDeskLocalRunDir = Join-Path $global:FlowDeskRepoRoot '.local-run'
$global:FlowDeskLogDir = Join-Path $global:FlowDeskLocalRunDir 'logs'
$global:FlowDeskStatePath = Join-Path $global:FlowDeskLocalRunDir 'state.json'

# ---- 三个服务与端口的固定契约（与三个 application.yml / README 第六节一致）----
$global:FlowDeskServices = @(
    [pscustomobject]@{
        Name       = 'asset-mcp'
        DisplayName = '资产 MCP 服务'
        Module     = 'flowdesk-mcp-asset'
        JarPattern = 'flowdesk-mcp-asset-*.jar'
        JarName    = 'flowdesk-mcp-asset-0.1.0-SNAPSHOT.jar'
        Port       = 8091
        McpTool    = 'asset_get'
    },
    [pscustomobject]@{
        Name       = 'monitoring-mcp'
        DisplayName = '监控 MCP 服务'
        Module     = 'flowdesk-mcp-monitoring'
        JarPattern = 'flowdesk-mcp-monitoring-*.jar'
        JarName    = 'flowdesk-mcp-monitoring-0.1.0-SNAPSHOT.jar'
        Port       = 8092
        McpTool    = 'monitoring_snapshot_get'
    },
    [pscustomobject]@{
        Name       = 'main-service'
        DisplayName = 'FlowDesk 主服务'
        Module     = 'flowdesk-bootstrap'
        JarPattern = 'flowdesk-bootstrap-*.jar'
        JarName    = 'flowdesk-bootstrap-0.1.0-SNAPSHOT.jar'
        Port       = 8080
        McpTool    = $null
    }
)

# =====================================================================================
# 输出
# =====================================================================================

function Write-FlowDeskTitle([string]$Text) {
    Write-Host ''
    Write-Host ('=' * 74) -ForegroundColor DarkCyan
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host ('=' * 74) -ForegroundColor DarkCyan
}

function Write-FlowDeskStep([string]$Text) {
    Write-Host ''
    Write-Host "── $Text" -ForegroundColor Cyan
}

function Write-FlowDeskInfo([string]$Text) {
    Write-Host "   $Text" -ForegroundColor Gray
}

function Write-FlowDeskOk([string]$Text) {
    Write-Host "   [ OK ] $Text" -ForegroundColor Green
}

function Write-FlowDeskWarn([string]$Text) {
    Write-Host "   [WARN] $Text" -ForegroundColor Yellow
}

function Write-FlowDeskFail([string]$Text) {
    Write-Host "   [FAIL] $Text" -ForegroundColor Red
}

# =====================================================================================
# JDK
# =====================================================================================

<#
    解析并验证 JDK 目录：bin\java.exe 必须存在、bin\javac.exe 必须存在（证明是 JDK 而不是 JRE），
    且 java -version 的主版本必须是 17。

    返回 java.exe 的绝对路径；任何一项不满足就抛出带修复指引的异常。
#>
function Resolve-FlowDeskJdk([string]$JdkHome) {
    if (-not $JdkHome) {
        throw @"
没有拿到 JDK 目录。
  请用 -JdkHome 显式指定（本任务不使用、也不修改系统的 JAVA_HOME）：
      -JdkHome 'C:\Users\<你>\.jdks\jdk-17.0.20.1+1'
  如果确实想用当前进程的 JAVA_HOME，可显式传 -JdkHome `$env:JAVA_HOME。
"@
    }

    $jdkHome = $JdkHome.Trim().TrimEnd('\', '/')
    if (-not (Test-Path -LiteralPath $jdkHome -PathType Container)) {
        throw "JDK 目录不存在：$jdkHome"
    }

    $javaExe = Join-Path $jdkHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
        throw "JDK 目录下找不到 bin\java.exe：$jdkHome"
    }
    $javacExe = Join-Path $jdkHome 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $javacExe -PathType Leaf)) {
        throw "该目录看起来是 JRE 而不是 JDK（没有 bin\javac.exe）：$jdkHome"
    }

    # java -version 把版本写到 stderr；这里只取文本，不改动任何全局设置
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $versionText = (& $javaExe -version 2>&1 | Out-String)
    }
    finally {
        $ErrorActionPreference = $previous
    }

    if ($versionText -notmatch 'version "17[\.\"]') {
        $firstLine = ($versionText -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1)
        throw "该 JDK 不是 17：$jdkHome`n   java -version 首行：$firstLine"
    }

    return $javaExe
}

# =====================================================================================
# 端口
# =====================================================================================

<#
    返回监听指定端口的进程 PID 列表（取不到时返回空数组）。
    只做「谁在占用」的读取，不终止任何进程。
#>
function Get-FlowDeskPortListenerPid([int]$Port) {
    $pids = @()

    try {
        $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop
        $pids = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
    }
    catch {
        # 回退到 netstat：老系统或模块不可用时仍然能给出占用者
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $lines = & netstat -ano -p TCP 2>$null
        }
        finally {
            $ErrorActionPreference = $previous
        }
        $pattern = 'TCP\s+\S+:' + $Port + '\s+\S+\s+LISTENING\s+(\d+)'
        foreach ($line in @($lines)) {
            if ("$line" -match $pattern) {
                $pids += [int]$Matches[1]
            }
        }
        $pids = @($pids | Sort-Object -Unique)
    }

    return @($pids | Where-Object { $_ -and $_ -gt 0 })
}

<#
    组装「端口被占用」的可读说明，包含占用者 PID 与可取得的进程名/路径。
#>
function Format-FlowDeskPortOccupant([int]$Port) {
    $pids = Get-FlowDeskPortListenerPid -Port $Port
    if ($pids.Count -eq 0) {
        return $null
    }

    $parts = @()
    foreach ($processId in $pids) {
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($process) {
            $path = $null
            try { $path = $process.Path } catch { $path = $null }
            if ($path) {
                $parts += "PID=$processId ($($process.ProcessName), $path)"
            }
            else {
                $parts += "PID=$processId ($($process.ProcessName))"
            }
        }
        else {
            $parts += "PID=$processId (进程信息不可读)"
        }
    }
    return ("端口 $Port 已被占用：" + ($parts -join '；') + '。本脚本不会终止未知进程，请自行确认后处理。')
}

# =====================================================================================
# 目标 JAR
# =====================================================================================

<#
    在模块的 target 目录里找打包产物。取不到时返回 $null（由调用方给出构建指引）。
    排除 .jar.original（那是 spring-boot repackage 留下的原始包，不是可执行 fat jar）。
#>
function Get-FlowDeskServiceJar([psobject]$Service) {
    $targetDir = Join-Path (Join-Path $global:FlowDeskRepoRoot $Service.Module) 'target'
    if (-not (Test-Path -LiteralPath $targetDir -PathType Container)) {
        return $null
    }

    $candidates = @(
        Get-ChildItem -LiteralPath $targetDir -Filter $Service.JarPattern -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*.jar.original' -and $_.Name -notlike '*-sources.jar' }
    )
    if ($candidates.Count -eq 0) {
        return $null
    }

    # 优先精确名字，其次取最近写入的一个
    $exact = $candidates | Where-Object { $_.Name -eq $Service.JarName } | Select-Object -First 1
    if ($exact) {
        return $exact.FullName
    }
    return ($candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

# =====================================================================================
# HTTP / JSON-RPC
# =====================================================================================

function New-FlowDeskHttpClient {
    Add-Type -AssemblyName System.Net.Http | Out-Null

    $handler = New-Object System.Net.Http.HttpClientHandler
    # 端点不做跳转，跳转一律视为异常，避免把「被重定向」误判成成功
    $handler.AllowAutoRedirect = $false

    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    return $client
}

<#
    把字符串编码成 **无 BOM** 的 UTF-8 请求体。
    这里刻意不用 [System.Text.Encoding]::UTF8（它在某些路径上会带上 BOM），
    而是显式取 UTF8Encoding($false) 的字节：JSON 前多出 EF BB BF 会被服务端判成非法 JSON。
#>
function New-FlowDeskJsonContent([string]$Body) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    $bytes = $encoding.GetBytes($Body)
    $content = New-Object System.Net.Http.ByteArrayContent(, $bytes)
    $content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue('application/json')
    return $content
}

<#
    发一个有界的 HTTP 请求并返回结构化结果。

    返回：[pscustomobject] @{ Status; ContentType; Body; SessionId; Json; Error }
      · Status    HTTP 状态码（非 2xx 不抛异常，作为数据返回）
      · SessionId 响应头 Mcp-Session-Id（没有则为 $null）
      · Json      解析后的 JSON（application/json 直接解析；text/event-stream 取 data: 帧）
      · Error     传输层失败时的说明（此时 Status 为 0）
#>
function Invoke-FlowDeskHttp {
    param(
        [Parameter(Mandatory = $true)] $Client,
        [string]$Method = 'GET',
        [Parameter(Mandatory = $true)][string]$Url,
        [string]$Body,
        [string]$SessionId,
        [string]$ProtocolVersion,
        [string]$Accept = 'application/json, text/event-stream'
    )

    $request = $null
    try {
        $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($Method)), $Url)
        [void]$request.Headers.TryAddWithoutValidation('Accept', $Accept)
        if ($SessionId) {
            [void]$request.Headers.TryAddWithoutValidation('Mcp-Session-Id', $SessionId)
        }
        if ($ProtocolVersion) {
            [void]$request.Headers.TryAddWithoutValidation('MCP-Protocol-Version', $ProtocolVersion)
        }
        if ($PSBoundParameters.ContainsKey('Body') -and $null -ne $Body) {
            $request.Content = New-FlowDeskJsonContent -Body $Body
        }

        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $status = [int]$response.StatusCode
            $contentType = $null
            if ($response.Content -and $response.Content.Headers -and $response.Content.Headers.ContentType) {
                $contentType = $response.Content.Headers.ContentType.MediaType
            }
            $text = ''
            if ($response.Content) {
                $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            }
            $sessionId = $null
            try { $sessionId = @($response.Headers.GetValues('Mcp-Session-Id'))[0] } catch { $sessionId = $null }

            return [pscustomobject]@{
                Status      = $status
                ContentType = $contentType
                Body        = $text
                SessionId   = $sessionId
                Json        = ConvertFrom-FlowDeskJsonBody -ContentType $contentType -Body $text
                Error       = $null
            }
        }
        finally {
            $response.Dispose()
        }
    }
    catch {
        return [pscustomobject]@{
            Status      = 0
            ContentType = $null
            Body        = ''
            SessionId   = $null
            Json        = $null
            Error       = $_.Exception.Message
        }
    }
    finally {
        if ($request) { $request.Dispose() }
    }
}

<#
    解析响应体：SSE 取 data: 帧拼接后再解析，其它按普通 JSON 解析。
    解析不出来就返回 $null —— 由调用方按「形状不符」处理，绝不靠字符串搜索冒充成功。
#>
function ConvertFrom-FlowDeskJsonBody {
    param([string]$ContentType, [string]$Body)

    if (-not $Body) { return $null }

    $isEventStream = $false
    if ($ContentType) {
        $isEventStream = $ContentType.ToLowerInvariant().Contains('text/event-stream')
    }

    if ($isEventStream) {
        $payload = ''
        foreach ($line in ($Body -split "`r?`n")) {
            if ($line.StartsWith('data:')) {
                $payload += $line.Substring(5).Trim()
            }
        }
        if (-not $payload) { return $null }
        try { return ($payload | ConvertFrom-Json) } catch { return $null }
    }

    try { return ($Body | ConvertFrom-Json) } catch { return $null }
}

<#
    轮询健康检查：在超时时间内等待 /actuator/health 返回 200 + status=UP。
#>
function Wait-FlowDeskHealth {
    param(
        [Parameter(Mandatory = $true)] $Client,
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [int]$TimeoutSec = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $lastNote = '尚未收到响应'

    while ((Get-Date) -lt $deadline) {
        $probe = Invoke-FlowDeskHttp -Client $Client -Method 'GET' -Url "$BaseUrl/actuator/health"
        if ($probe.Error) {
            $lastNote = $probe.Error
        }
        elseif ($probe.Status -eq 200 -and $probe.Json -and "$($probe.Json.status)" -eq 'UP') {
            return [pscustomobject]@{ Ok = $true; Note = 'status=UP' }
        }
        else {
            $lastNote = "HTTP $($probe.Status)"
        }
        Start-Sleep -Milliseconds 500
    }

    return [pscustomobject]@{ Ok = $false; Note = $lastNote }
}

# =====================================================================================
# 进程启动与身份校验
# =====================================================================================

<#
    折叠「仅大小写不同」的重复环境变量。

    背景：Windows PowerShell 5.1 的 Start-Process 在需要重定向输出时会以 UseShellExecute=false 启动子进程，
    并把当前进程的环境块塞进一个**不区分大小写**的字典。当环境块里同时存在 HTTP_PROXY 与 http_proxy
    这类条目时，它会以「已添加项。字典中的关键字…」直接失败，子进程根本起不来
    （本机环境的实测：Path/PATH、HTTP_PROXY/http_proxy、HTTPS_PROXY/https_proxy 三组）。

    处置：把每一组折叠成单一拼写（保留原值，规范名优先取全大写形式）。
    只作用于**本脚本进程**，因此只影响本脚本启动的子进程 —— 不写系统/用户环境变量，
    返回值只包含变量名，从不包含任何取值。
#>
function Remove-FlowDeskDuplicateEnvNames {
    $removed = @()

    for ($round = 0; $round -lt 6; $round++) {
        $variables = [System.Environment]::GetEnvironmentVariables()
        $groups = @(@($variables.Keys) |
            Group-Object { $_.ToString().ToLowerInvariant() } |
            Where-Object { $_.Count -gt 1 })
        if ($groups.Count -eq 0) { break }

        foreach ($group in $groups) {
            $names = @($group.Group | ForEach-Object { $_.ToString() })

            $canonical = ''
            foreach ($name in $names) {
                if ($name -ceq $name.ToUpperInvariant()) { $canonical = $name; break }
            }
            if (-not $canonical) { $canonical = $names[0] }

            $value = $variables[$canonical]
            foreach ($name in $names) {
                [System.Environment]::SetEnvironmentVariable($name, $null)
                if ($name -cne $canonical) { $removed += $name }
            }
            [System.Environment]::SetEnvironmentVariable($canonical, $value)
        }
    }

    return @($removed | Sort-Object -Unique)
}

<#
    启动一个服务进程：隐藏窗口、stdout 与 stderr 分别写入独立日志、工作目录固定为仓库根。

    端口、绑定地址与模式一律通过**明确的命令行参数**传给子进程 —— 命令行参数的优先级高于
    环境变量与系统属性，因此运行环境里可能存在的 server.port（或 SERVER_PORT）覆盖不了它们。
#>
function Start-FlowDeskServiceProcess {
    param(
        [Parameter(Mandatory = $true)][string]$JavaExe,
        [Parameter(Mandatory = $true)][string]$JarPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$StdOutLog,
        [Parameter(Mandatory = $true)][string]$StdErrLog
    )

    $argumentList = @('-jar', "`"$JarPath`"") + $Arguments

    $started = Start-Process -FilePath $JavaExe `
        -ArgumentList $argumentList `
        -WorkingDirectory $global:FlowDeskRepoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $StdOutLog `
        -RedirectStandardError $StdErrLog `
        -PassThru

    # Start-Process 返回时进程可能还没有真正完成启动，等一小会儿让 StartTime 可读
    $deadline = (Get-Date).AddSeconds(5)
    while ((Get-Date) -lt $deadline) {
        try {
            $null = $started.StartTime
            break
        }
        catch {
            Start-Sleep -Milliseconds 100
        }
    }

    return $started
}

<#
    按名字取服务契约（$global:FlowDeskServices 里的那一项）。

    @param Name 服务名（asset-mcp / monitoring-mcp / main-service）
    @return 服务描述对象；名字未知时返回 $null
#>
function Get-FlowDeskServiceByName([string]$Name) {
    if (-not $Name) { return $null }
    foreach ($service in $global:FlowDeskServices) {
        if ($service.Name -eq $Name) { return $service }
    }
    return $null
}

<#
    校验一个「秒」级时间参数的合法范围。

    时间参数必须有界：无限等待会让脚本在故障时永远不返回，而 0 或负数会让等待循环
    立刻退出、把「还没起来」当成「起不来」。

    @param Name  参数名（用于文案）
    @param Value 取值
    @param Min   允许的最小值（含）
    @param Max   允许的最大值（含）
    @return 合法时返回 $null，否则返回错误说明
#>
function Test-FlowDeskTimeRange([string]$Name, $Value, [int]$Min, [int]$Max) {
    $parsed = 0
    if (-not [int]::TryParse("$Value", [ref]$parsed)) {
        return "$Name 必须是整数秒（当前值：$Value）"
    }
    if ($parsed -lt $Min -or $parsed -gt $Max) {
        return "$Name 必须在 $Min 到 $Max 秒之间（当前值：$parsed）"
    }
    return $null
}

<#
    校验「这条记录里的目标 JAR 路径是否真的属于这个服务」。

    规则（任务单要求逐条覆盖）：不能为空、不能含通配符、必须是绝对路径、
    规范化后必须落在**该服务模块自己的 target 目录**内、文件名必须匹配该模块的打包产物模式。
    空路径、通配符（`*`、`?`、`[`、`]`）、仓库外路径、`java.exe` 这种裸文件名、
    以及「资产服务的记录指向监控服务的 JAR」都会被拒绝。

    刻意**不**要求文件当前存在：重新构建会让 target 下的旧 JAR 消失，
    而那个进程可能仍在运行 —— 身份核验只判断「这条记录是否自洽且属于该服务」。

    @param Service 服务描述对象
    @param JarPath 记录里的 JAR 路径
    @return [pscustomobject] @{ Ok; Reason }
#>
function Test-FlowDeskServiceJarPath([psobject]$Service, [string]$JarPath) {
    if (-not $JarPath -or -not $JarPath.Trim()) {
        return [pscustomobject]@{ Ok = $false; Reason = '目标 JAR 路径为空' }
    }
    if ($JarPath -match '[\*\?\[\]]') {
        return [pscustomobject]@{ Ok = $false; Reason = "目标 JAR 路径含通配符：$JarPath" }
    }
    if (-not [System.IO.Path]::IsPathRooted($JarPath)) {
        return [pscustomobject]@{ Ok = $false; Reason = "目标 JAR 不是绝对路径：$JarPath" }
    }

    $normalized = $null
    try { $normalized = [System.IO.Path]::GetFullPath($JarPath) }
    catch { return [pscustomobject]@{ Ok = $false; Reason = "目标 JAR 路径无法规范化：$JarPath" } }

    $expectedDir = [System.IO.Path]::GetFullPath((Join-Path $global:FlowDeskRepoRoot "$($Service.Module)\target"))
    $actualDir = [System.IO.Path]::GetDirectoryName($normalized)
    if (-not $actualDir -or $actualDir.TrimEnd('\') -ine $expectedDir.TrimEnd('\')) {
        return [pscustomobject]@{ Ok = $false; Reason = "目标 JAR 不在该服务的 target 目录内（期望 $expectedDir）：$normalized" }
    }

    # 由打包产物模式推出文件名正则：flowdesk-x-*.jar -> ^flowdesk\-x\-[^\\/]*\.jar$
    $fileName = [System.IO.Path]::GetFileName($normalized)
    $pattern = '^' + [regex]::Escape($Service.JarPattern).Replace('\*', '[^\\/]*') + '$'
    if ($fileName -notmatch $pattern) {
        return [pscustomobject]@{ Ok = $false; Reason = "目标 JAR 文件名不匹配该服务的打包产物模式（$($Service.JarPattern)）：$fileName" }
    }

    return [pscustomobject]@{ Ok = $true; Reason = '目标 JAR 属于该服务' }
}

<#
    按 Windows 的命令行引号/转义规则把整条命令行切成参数表。

    规则与 {@code CommandLineToArgvW} 一致（这是 JVM 实际拿到的切分方式）：
      · 空白分隔参数；
      · 连续 2n 个反斜杠后跟一个引号时，输出 n 个反斜杠并把该引号当**引号定界符**
        （奇数个反斜杠时，输出 n 个反斜杠 + 一个字面量引号，定界状态不变）；
      · 引号内的 {@code ""} 表示一个字面量引号。

    为什么要自己切：直接对整条命令行做「空白 + 引号」的正则搜索会把
    引号内、或被引号包住的属性值里的 {@code -jar} 也当成真的启动参数。

    引号不成对（例如路径以反斜杠结尾把闭合引号吞掉了）时**拒绝**，而不是猜一个结果。

    @param CommandLine 进程命令行
    @return [pscustomobject] @{ Ok; Arguments; Reason }
#>
function ConvertTo-FlowDeskArgumentList([string]$CommandLine) {
    $arguments = @()
    if (-not $CommandLine) {
        return [pscustomobject]@{ Ok = $false; Arguments = $arguments; Reason = '命令行为空' }
    }

    $buffer = New-Object System.Text.StringBuilder
    $inQuotes = $false
    $hasToken = $false
    $index = 0
    $length = $CommandLine.Length

    while ($index -lt $length) {
        $character = $CommandLine[$index]

        if ($character -eq '\') {
            $slashes = 0
            while ($index -lt $length -and $CommandLine[$index] -eq '\') {
                $slashes++
                $index++
            }

            if ($index -lt $length -and $CommandLine[$index] -eq '"') {
                # 反斜杠后面跟引号：2n 个 → n 个字面反斜杠 + 引号定界；
                # 2n+1 个 → n 个字面反斜杠 + 一个字面量引号（定界状态不变）。
                for ($k = 0; $k -lt [math]::Floor($slashes / 2); $k++) { [void]$buffer.Append('\') }
                if ($slashes % 2 -eq 1) {
                    [void]$buffer.Append('"')
                }
                else {
                    $inQuotes = -not $inQuotes
                }
                $index++
            }
            else {
                # 后面不是引号：这些反斜杠全是字面量，**一个都不能少**
                for ($k = 0; $k -lt $slashes; $k++) { [void]$buffer.Append('\') }
            }

            $hasToken = $true
            continue
        }

        if ($character -eq '"') {
            if ($inQuotes -and ($index + 1) -lt $length -and $CommandLine[$index + 1] -eq '"') {
                [void]$buffer.Append('"')
                $index += 2
                $hasToken = $true
                continue
            }
            $inQuotes = -not $inQuotes
            $hasToken = $true
            $index++
            continue
        }

        if (-not $inQuotes -and ($character -eq ' ' -or $character -eq "`t")) {
            if ($hasToken) {
                $arguments += $buffer.ToString()
                [void]$buffer.Clear()
                $hasToken = $false
            }
            $index++
            continue
        }

        [void]$buffer.Append($character)
        $hasToken = $true
        $index++
    }

    if ($inQuotes) {
        return [pscustomobject]@{
            Ok        = $false
            Arguments = $arguments
            Reason    = '命令行里的引号不成对，无法可靠切分（拒绝核验，不猜）'
        }
    }

    if ($hasToken) { $arguments += $buffer.ToString() }
    return [pscustomobject]@{ Ok = $true; Arguments = $arguments; Reason = '命令行切分成功' }
}

<#
    识别 Java 的启动目标 —— **最小白名单**。

    <p>本脚本只会生成一种启动形式（见 {@code Start-FlowDeskServiceProcess}）：</p>

    <pre>java.exe -jar "&lt;本服务 JAR 完整路径&gt;" &lt;应用参数…&gt;</pre>

    <p>因此这里只接受这一种形式：
      · 按 Windows 的引号/转义规则切分成功；
      · 可执行程序后面的**第一个**参数**精确**等于 {@code -jar}（区分大小写）；
      · 紧随其后有非空的 JAR 路径。</p>

    <p>其余一切启动形式（前面带任何 JVM 选项、{@code -m}/{@code --module} 模块启动、
    主类启动、裸 JAR 路径、{@code -JAR} 之类的大小写变体）一律**无法确认**，
    由调用方拒绝终止 ——
    不做「跳过若干 JVM 选项再去找启动目标」这种通用解析，也不猜。</p>

    <p>把识别范围钉死在「我们自己会生成的那一种」，比实现一个通用 Java launcher 安全得多：
    任何我们没生成过的形式只会被**拒绝**（终止不了），而不会被误认成我们的进程。
    以后确需 JVM 参数时，再另行明确支持范围。</p>

    @param CommandLine 进程命令行
    @return [pscustomobject] @{ Ok; Jar; Reason }；Ok=$false 表示无法确认启动目标
#>
function Resolve-FlowDeskJavaLaunchTarget([string]$CommandLine) {
    if (-not $CommandLine) {
        return [pscustomobject]@{ Ok = $false; Jar = $null; Reason = '命令行为空，无法识别启动目标' }
    }

    $split = ConvertTo-FlowDeskArgumentList -CommandLine $CommandLine
    if (-not $split.Ok) {
        return [pscustomobject]@{ Ok = $false; Jar = $null; Reason = $split.Reason }
    }

    $arguments = @($split.Arguments)
    if ($arguments.Count -lt 3) {
        return [pscustomobject]@{
            Ok        = $false
            Jar       = $null
            Reason    = "参数数量不足，不是 `java -jar <JAR>` 形式：$CommandLine"
        }
    }

    if ($arguments[1] -cne '-jar') {
        return [pscustomobject]@{
            Ok     = $false
            Jar    = $null
            Reason = "可执行程序后面的第一个参数不是 -jar（实际为 '" + $arguments[1] + "'），不属于本脚本会生成的启动形式"
        }
    }

    $jar = $arguments[2]
    if (-not $jar -or -not $jar.Trim()) {
        return [pscustomobject]@{ Ok = $false; Jar = $null; Reason = '-jar 后面的 JAR 路径为空' }
    }

    return [pscustomobject]@{ Ok = $true; Jar = $jar; Reason = '启动形式是 java -jar <JAR>，在白名单内' }
}

<#
    校验一条运行记录的**形状**（不涉及进程本身）：必要字段齐全且合法、
    服务名是本脚本已知的服务、目标 JAR 属于该服务。

    单独抽出来有两个用途：一是被身份核验复用，二是让只做只读检查的场景
    （例如冒烟脚本）能在不碰任何进程的前提下判断「这条记录是否可信」。

    @param Record 运行记录
    @return [pscustomobject] @{ Ok; Reason; Service; Pid; StartTime }
#>
function Test-FlowDeskRecordShape {
    param([Parameter(Mandatory = $true)]$Record)

    if (-not $Record) {
        return [pscustomobject]@{ Ok = $false; Reason = '运行记录为空'; Service = $null; Pid = 0; StartTime = $null }
    }

    $missing = @()
    foreach ($field in @('name', 'pid', 'processStartTime', 'jar', 'port')) {
        $value = $Record.$field
        if ($null -eq $value -or "$value".Trim() -eq '') { $missing += $field }
    }
    if ($missing.Count -gt 0) {
        return [pscustomobject]@{
            Ok = $false; Reason = "运行记录缺少必要字段：" + ($missing -join ', ')
            Service = $null; Pid = 0; StartTime = $null
        }
    }

    $processId = 0
    if (-not [int]::TryParse("$($Record.pid)", [ref]$processId) -or $processId -le 0) {
        return [pscustomobject]@{
            Ok = $false; Reason = "运行记录里的 PID 不合法：$($Record.pid)"
            Service = $null; Pid = 0; StartTime = $null
        }
    }

    $port = 0
    if (-not [int]::TryParse("$($Record.port)", [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        return [pscustomobject]@{
            Ok = $false; Reason = "运行记录里的端口不合法：$($Record.port)"
            Service = $null; Pid = 0; StartTime = $null
        }
    }

    $recordedStart = $null
    try {
        $recordedStart = [datetime]::Parse("$($Record.processStartTime)",
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::RoundtripKind)
    }
    catch { $recordedStart = $null }
    if (-not $recordedStart) {
        return [pscustomobject]@{
            Ok = $false; Reason = "运行记录里的启动时间无法解析：$($Record.processStartTime)"
            Service = $null; Pid = 0; StartTime = $null
        }
    }

    $service = Get-FlowDeskServiceByName "$($Record.name)"
    if (-not $service) {
        return [pscustomobject]@{
            Ok = $false; Reason = "运行记录里的服务名不是本脚本已知的服务：$($Record.name)"
            Service = $null; Pid = 0; StartTime = $null
        }
    }

    $jarVerdict = Test-FlowDeskServiceJarPath -Service $service -JarPath "$($Record.jar)"
    if (-not $jarVerdict.Ok) {
        return [pscustomobject]@{ Ok = $false; Reason = $jarVerdict.Reason; Service = $service; Pid = 0; StartTime = $null }
    }

    return [pscustomobject]@{ Ok = $true; Reason = '记录形状合法'; Service = $service; Pid = $processId; StartTime = $recordedStart }
}

<#
    校验一条运行记录现在还是不是「我们启动的那一个进程」。

    返回值刻意把两种「否定」分开，因为它们的处置完全不同：
      · {@code Killable}  = 身份已核实，**可以终止**；
      · {@code PidAbsent} = **实际确认该 PID 不存在** —— 这是唯一可以说
        「进程已经没了、记录可以丢掉」的情形；
    两者都为 {@code $false} 表示**无法判定**（记录缺字段 / 字段非法 / JAR 不属于该服务 /
    PID 被别的进程占用 / 启动时间读不到 / 命令行读不到 / 启动目标识别不出来）。
    无法判定的记录既不能终止，也**不能**当成「已经不存在」，只能作为未处理项保留下来。

    @param Record 运行记录
    @return [pscustomobject] @{ Killable; PidAbsent; Reason }
#>
function Test-FlowDeskRecordedProcess {
    param([Parameter(Mandatory = $true)]$Record)

    # ---- 1. 记录形状（缺字段 / 字段非法 / JAR 不属于该服务）----
    $shape = Test-FlowDeskRecordShape -Record $Record
    if (-not $shape.Ok) {
        return [pscustomobject]@{ Killable = $false; PidAbsent = $false; Reason = $shape.Reason }
    }
    $service = $shape.Service
    $processId = $shape.Pid
    $recordedStart = $shape.StartTime

    # ---- 2. PID 是否存在 ----
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $process) {
        return [pscustomobject]@{ Killable = $false; PidAbsent = $true; Reason = '实际确认该 PID 不存在' }
    }
    if ($process.ProcessName -ne 'java') {
        return [pscustomobject]@{
            Killable  = $false
            PidAbsent = $false
            Reason    = "该 PID 现在被另一个进程占用（进程名 $($process.ProcessName)），无法确认原进程是否已退出"
        }
    }

    $startTime = $null
    try { $startTime = $process.StartTime } catch { $startTime = $null }
    if (-not $startTime) {
        return [pscustomobject]@{ Killable = $false; PidAbsent = $false; Reason = '读不到进程启动时间，无法核对身份' }
    }
    $delta = [math]::Abs(($startTime.ToUniversalTime() - $recordedStart.ToUniversalTime()).TotalSeconds)
    if ($delta -gt 2) {
        return [pscustomobject]@{ Killable = $false; PidAbsent = $false; Reason = "启动时间不符（差 $([math]::Round($delta,1)) 秒），PID 可能已被系统复用" }
    }

    # ---- 3. 启动目标：按 Windows 引号规则切分命令行后识别，并比对完整路径 ----
    $commandLine = $null
    try {
        $cim = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId=$processId" -ErrorAction Stop
        if ($cim) { $commandLine = $cim.CommandLine }
    }
    catch { $commandLine = $null }
    if (-not $commandLine) {
        return [pscustomobject]@{ Killable = $false; PidAbsent = $false; Reason = '读不到进程命令行，无法确认目标 JAR' }
    }

    $target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $commandLine
    if (-not $target.Ok) {
        return [pscustomobject]@{ Killable = $false; PidAbsent = $false; Reason = "无法可靠识别 Java 启动目标：$($target.Reason)" }
    }

    $argumentNormalized = $null
    try { $argumentNormalized = [System.IO.Path]::GetFullPath($target.Jar) }
    catch {
        return [pscustomobject]@{ Killable = $false; PidAbsent = $false; Reason = "启动目标路径无法规范化：$($target.Jar)" }
    }

    $argumentVerdict = Test-FlowDeskServiceJarPath -Service $service -JarPath $argumentNormalized
    if (-not $argumentVerdict.Ok) {
        return [pscustomobject]@{ Killable = $false; PidAbsent = $false; Reason = "启动目标不属于该服务：$($argumentVerdict.Reason)" }
    }

    $recordedNormalized = [System.IO.Path]::GetFullPath("$($Record.jar)")
    if ($argumentNormalized -ine $recordedNormalized) {
        return [pscustomobject]@{
            Killable  = $false
            PidAbsent = $false
            Reason    = "启动目标与记录的目标 JAR 不一致（实际 $argumentNormalized，记录 $recordedNormalized）"
        }
    }

    return [pscustomobject]@{ Killable = $true; PidAbsent = $false; Reason = '身份已核实（PID + 进程名 + 启动时间 + 启动目标完整路径）' }
}

<#
    清理一批「本次启动、已经登记身份」的进程。

    只终止身份核实通过的进程；身份无法证明、或终止失败的记录会原样回传，
    由调用方决定是否保留运行记录以便重试。**不会**报告「全部清理」除非真的全部清理掉了。

    @param Records         运行记录数组
    @param GracefulWaitSec 单个进程等待正常关闭的秒数
    @return [pscustomobject] @{ AllCleared; Cleared; AlreadyGone; Unresolved }
#>
function Clear-FlowDeskStartedProcesses {
    param(
        [psobject[]]$Records,
        [int]$GracefulWaitSec = 8
    )

    $cleared = @()
    $alreadyGone = @()
    $unresolved = @()

    foreach ($record in @($Records)) {
        $verdict = Test-FlowDeskRecordedProcess -Record $record

        if ($verdict.Killable) {
            $result = Stop-FlowDeskVerifiedProcess -Record $record -GracefulWaitSec $GracefulWaitSec
            if ($result.Stopped) {
                $cleared += [pscustomobject]@{ Record = $record; Method = $result.Method }
            }
            else {
                $unresolved += [pscustomobject]@{ Record = $record; Reason = $result.Note }
            }
            continue
        }

        # 只有**实际确认该 PID 不存在**，才允许归入「已经没了、可以丢记录」。
        # 记录缺字段 / 字段非法 / 身份无法证明 / PID 被别的进程占用，一律算未处理。
        if ($verdict.PidAbsent) {
            $alreadyGone += [pscustomobject]@{ Record = $record; Reason = $verdict.Reason }
            continue
        }

        $unresolved += [pscustomobject]@{ Record = $record; Reason = $verdict.Reason }
    }

    return [pscustomobject]@{
        AllCleared  = ($unresolved.Count -eq 0)
        Cleared     = $cleared
        AlreadyGone = $alreadyGone
        Unresolved  = $unresolved
    }
}


<#
    停止一个已核实身份的进程：先尝试正常关闭，超时后再强制终止，并如实报告用了哪种方式。
    返回：[pscustomobject] @{ Stopped; Method; Note }
#>
function Stop-FlowDeskVerifiedProcess {
    param(
        [Parameter(Mandatory = $true)][psobject]$Record,
        [int]$GracefulWaitSec = 8
    )

    # 1) 先尝试正常关闭：taskkill 不带 /F 只会发送关闭请求，不会强杀
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $null = & taskkill.exe /PID $Record.pid 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
    }

    $deadline = (Get-Date).AddSeconds($GracefulWaitSec)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $Record.pid -ErrorAction SilentlyContinue)) {
            return [pscustomobject]@{ Stopped = $true; Method = '正常关闭'; Note = 'taskkill 关闭请求后进程已退出' }
        }
        Start-Sleep -Milliseconds 250
    }

    # 2) 正常关闭无效（控制台进程通常如此）：强制终止，并明确记录
    try {
        Stop-Process -Id $Record.pid -Force -ErrorAction Stop
    }
    catch {
        return [pscustomobject]@{ Stopped = $false; Method = '强制终止'; Note = "强制终止失败：$($_.Exception.Message)" }
    }

    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $Record.pid -ErrorAction SilentlyContinue)) {
            return [pscustomobject]@{ Stopped = $true; Method = '强制终止'; Note = '正常关闭请求无效，已强制终止' }
        }
        Start-Sleep -Milliseconds 250
    }

    return [pscustomobject]@{ Stopped = $false; Method = '强制终止'; Note = '强制终止后进程仍然存在' }
}

# =====================================================================================
# 状态文件
# =====================================================================================

function Read-FlowDeskState {
    if (-not (Test-Path -LiteralPath $global:FlowDeskStatePath -PathType Leaf)) {
        return $null
    }

    try {
        $text = [System.IO.File]::ReadAllText($global:FlowDeskStatePath, [System.Text.Encoding]::UTF8)
        if (-not $text.Trim()) { return $null }
        return ($text | ConvertFrom-Json)
    }
    catch {
        throw "运行记录文件已损坏，无法解析：$global:FlowDeskStatePath`n   $($_.Exception.Message)"
    }
}

function Write-FlowDeskState($State) {
    if (-not (Test-Path -LiteralPath $global:FlowDeskLocalRunDir -PathType Container)) {
        $null = New-Item -ItemType Directory -Path $global:FlowDeskLocalRunDir -Force
    }
    $json = $State | ConvertTo-Json -Depth 8
    # 无 BOM：状态文件由本脚本自己读回，不需要 BOM，也避免被当成脏文件
    [System.IO.File]::WriteAllText($global:FlowDeskStatePath, $json, (New-Object System.Text.UTF8Encoding($false)))
}

function Remove-FlowDeskState {
    if (Test-Path -LiteralPath $global:FlowDeskStatePath -PathType Leaf) {
        Remove-Item -LiteralPath $global:FlowDeskStatePath -Force
    }
}

function Initialize-FlowDeskLogDir {
    if (-not (Test-Path -LiteralPath $global:FlowDeskLogDir -PathType Container)) {
        $null = New-Item -ItemType Directory -Path $global:FlowDeskLogDir -Force
    }
}

<#
    落盘运行记录。start-local 每创建一个 JVM 就立刻调用一次：这样任何后续失败
    （健康超时、后续服务启动异常、甚至写记录本身失败）都能按记录找到本次已创建的进程。

    @param Mode      运行模式
    @param McpClient 是否显式开启了主服务 MCP 客户端
    @param JavaExe   本脚本使用的 java.exe 绝对路径
    @param Services  已登记的服务记录数组
#>
function Save-FlowDeskRunState {
    param(
        [string]$Mode,
        [bool]$McpClient,
        [string]$JavaExe,
        $Services
    )

    $state = [pscustomobject]@{
        version   = 1
        mode      = $Mode
        startedAt = (Get-Date).ToUniversalTime().ToString('o')
        repoRoot  = $global:FlowDeskRepoRoot
        javaExe   = $JavaExe
        mcpClient = $McpClient
        services  = @($Services)
    }
    Write-FlowDeskState -State $state
}

<#
    把一份运行记录按核验结果分成三类，供「重复启动」与「旧记录检查」使用：

      · Live        —— 身份已核实、进程仍在（占用着那套端口）
      · Stale       —— **实际确认 PID 不存在**（陈旧记录，可以安全丢弃）
      · Unjudgeable —— 无法判定（记录缺字段 / 字段非法 / 身份无法证明 / PID 被别的进程占用）

    任何记录都不会被「猜」成 Stale：只有 PidAbsent 才算。

    @param State 已解析的运行记录
    @return [pscustomobject] @{ Live; Stale; Unjudgeable }
#>
function Split-FlowDeskRecordsByVerdict($State) {
    $live = @()
    $stale = @()
    $unjudgeable = @()

    if (-not $State -or -not $State.services) {
        return [pscustomobject]@{ Live = $live; Stale = $stale; Unjudgeable = $unjudgeable }
    }

    foreach ($record in @($State.services)) {
        $verdict = Test-FlowDeskRecordedProcess -Record $record
        if ($verdict.Killable) {
            $live += [pscustomobject]@{ Record = $record; Verdict = $verdict }
        }
        elseif ($verdict.PidAbsent) {
            $stale += [pscustomobject]@{ Record = $record; Verdict = $verdict }
        }
        else {
            $unjudgeable += [pscustomobject]@{ Record = $record; Verdict = $verdict }
        }
    }

    return [pscustomobject]@{ Live = $live; Stale = $stale; Unjudgeable = $unjudgeable }
}
