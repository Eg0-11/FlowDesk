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
    从命令行里取出真正的 {@code -jar} 参数。

    <p>刻意不做子串匹配：只有「以独立 token 出现的 {@code -jar}」后面跟的那个参数才算数。
    这样「别的参数里恰好含有同一个路径」不会被误判成身份一致，而
    {@code --foo=-jar ...} 这类写法也不会被当成 {@code -jar}。</p>

    @param CommandLine 进程命令行
    @return JAR 路径；命令行里没有 -jar 参数时返回 $null
#>
function Get-FlowDeskJarArgument([string]$CommandLine) {
    if (-not $CommandLine) { return $null }
    if ($CommandLine -match '(?:^|\s)-jar\s+(?:"(?<p>[^"]+)"|(?<p>[^"\s]+))') {
        return $Matches['p']
    }
    return $null
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

    核验顺序（任何一步失败都返回 Killable=$false，且**禁止终止**）：
      1. 记录的形状合法（必要字段、服务名、目标 JAR 属于该服务，见 Test-FlowDeskRecordShape）；
      2. PID 存在、进程名是 java、启动时间与记录一致（2 秒容差）；
      3. 命令行里真的有一个 {@code -jar} 参数，且它的**规范化完整路径**与记录的目标 JAR 完全相同
         （不是子串包含）。

    @param Record 运行记录
    @return [pscustomobject] @{ Killable; Exists; Reason }
#>
function Test-FlowDeskRecordedProcess {
    param([Parameter(Mandatory = $true)]$Record)

    # ---- 1. 记录形状 ----
    $shape = Test-FlowDeskRecordShape -Record $Record
    if (-not $shape.Ok) {
        return [pscustomobject]@{ Killable = $false; Exists = $false; Reason = $shape.Reason }
    }
    $service = $shape.Service
    $processId = $shape.Pid
    $recordedStart = $shape.StartTime

    # ---- 2. 进程本身 ----
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $process) {
        return [pscustomobject]@{ Killable = $false; Exists = $false; Reason = '进程已不存在' }
    }
    if ($process.ProcessName -ne 'java') {
        return [pscustomobject]@{ Killable = $false; Exists = $true; Reason = "PID 现在的进程名是 $($process.ProcessName)，不是 java" }
    }

    $startTime = $null
    try { $startTime = $process.StartTime } catch { $startTime = $null }
    if (-not $startTime) {
        return [pscustomobject]@{ Killable = $false; Exists = $true; Reason = '读不到进程启动时间，无法核对身份' }
    }
    $delta = [math]::Abs(($startTime.ToUniversalTime() - $recordedStart.ToUniversalTime()).TotalSeconds)
    if ($delta -gt 2) {
        return [pscustomobject]@{ Killable = $false; Exists = $true; Reason = "启动时间不符（差 $([math]::Round($delta,1)) 秒），PID 可能已被系统复用" }
    }

    # ---- 3. 命令行里的 -jar 参数（完整路径，不做子串匹配）----
    $commandLine = $null
    try {
        $cim = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId=$processId" -ErrorAction Stop
        if ($cim) { $commandLine = $cim.CommandLine }
    }
    catch { $commandLine = $null }
    if (-not $commandLine) {
        return [pscustomobject]@{ Killable = $false; Exists = $true; Reason = '读不到进程命令行，无法确认目标 JAR' }
    }

    $jarArgument = Get-FlowDeskJarArgument -CommandLine $commandLine
    if (-not $jarArgument) {
        return [pscustomobject]@{ Killable = $false; Exists = $true; Reason = '进程命令行里没有 -jar 参数，无法确认目标 JAR' }
    }

    $argumentNormalized = $null
    try { $argumentNormalized = [System.IO.Path]::GetFullPath($jarArgument) }
    catch { return [pscustomobject]@{ Killable = $false; Exists = $true; Reason = "命令行里的 -jar 路径无法规范化：$jarArgument" } }

    $argumentVerdict = Test-FlowDeskServiceJarPath -Service $service -JarPath $argumentNormalized
    if (-not $argumentVerdict.Ok) {
        return [pscustomobject]@{ Killable = $false; Exists = $true; Reason = "命令行里的 -jar 不属于该服务：$($argumentVerdict.Reason)" }
    }

    $recordedNormalized = [System.IO.Path]::GetFullPath("$($Record.jar)")
    if ($argumentNormalized -ine $recordedNormalized) {
        return [pscustomobject]@{
            Killable = $false
            Exists   = $true
            Reason   = "命令行里的 -jar 与记录的目标 JAR 不一致（实际 $argumentNormalized，记录 $recordedNormalized）"
        }
    }

    return [pscustomobject]@{ Killable = $true; Exists = $true; Reason = '身份已核实（PID + 进程名 + 启动时间 + -jar 完整路径）' }
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

        if (-not $verdict.Killable -and -not $verdict.Exists) {
            $alreadyGone += [pscustomobject]@{ Record = $record; Reason = $verdict.Reason }
            continue
        }
        if (-not $verdict.Killable) {
            $unresolved += [pscustomobject]@{ Record = $record; Reason = $verdict.Reason }
            continue
        }

        $result = Stop-FlowDeskVerifiedProcess -Record $record -GracefulWaitSec $GracefulWaitSec
        if ($result.Stopped) {
            $cleared += [pscustomobject]@{ Record = $record; Method = $result.Method }
        }
        else {
            $unresolved += [pscustomobject]@{ Record = $record; Reason = $result.Note }
        }
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
    只返回仍然存活（或记录不自洽但进程仍在）的记录，用于「重复启动」检测。
#>
function Get-FlowDeskLiveRecords($State) {
    $live = @()
    if (-not $State -or -not $State.services) { return $live }

    foreach ($record in @($State.services)) {
        $verdict = Test-FlowDeskRecordedProcess -Record $record
        if ($verdict.Exists) {
            $live += [pscustomobject]@{ Record = $record; Verdict = $verdict }
        }
    }
    return $live
}
