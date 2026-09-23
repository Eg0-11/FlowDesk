<#
.SYNOPSIS
    检查已经运行的 FlowDesk 三个服务（FD-0019-A）。

.DESCRIPTION
    本脚本**不负责启动服务**：它只检查当前正在运行的实例。

    检查内容：
      1. 三个服务的 /actuator/health 健康，且三个端口的**实际监听地址**都是 127.0.0.1；
      2. 资产 MCP：真实 HTTP + JSON-RPC 握手（initialize → initialized 通知 → tools/list
         → tools/call），验证固定工具 asset_get、演示命中与未命中，最后在 finally 里 DELETE 会话；
      3. 监控 MCP：同样通过真实协议调用 monitoring_snapshot_get，验证演示命中与未命中，finally 里 DELETE 会话；
      4. 响应按 Content-Type 解析（application/json 直接解析，text/event-stream 取 data: 帧），
         不靠搜索字符串「200」判断成功；
      5. 请求体编码：UTF-8 **无 BOM**，且含中文的 JSON 能进入业务层；
      6. Basic 模式下资产诊断与事件研判接口都是 404（此模式没有 AI 回答能力）。

    每项输出 PASS/FAIL；任一项 FAIL 则脚本以非零退出码结束。

    这是「真实进程 + 真实 MCP 协议 + 演示数据」的验证，
    **不是**真实企业数据源验证：所有命中的 source 都是 DEMO（虚构数据）。
    本脚本不调用 AI 接口生成答案，也不新建或修改任何业务数据。

.PARAMETER Mode
    覆盖从运行记录里读到的模式（basic / deepseek）。不传时以 start-local.ps1 的记录为准。

.PARAMETER RequestTimeoutSec
    单个 HTTP 请求的超时（默认 20 秒）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\test-local.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('basic', 'deepseek', '')]
    [string]$Mode = '',
    [int]$RequestTimeoutSec = 20
)

$ErrorActionPreference = 'Stop'

$scriptsDir = Split-Path -Parent $PSCommandPath
. (Join-Path $scriptsDir 'flowdesk-local-common.ps1')

# ---------- 参数范围校验 ----------
$rangeError = Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value $RequestTimeoutSec -Min 1 -Max 600
if ($rangeError) {
    Write-FlowDeskTitle 'FlowDesk 本地冒烟检查（FD-0019-A）'
    Write-FlowDeskFail "参数不合法：$rangeError"
    Write-FlowDeskInfo '未做任何检查（没有发出任何请求）。'
    exit 7
}

Write-FlowDeskTitle 'FlowDesk 本地冒烟检查（FD-0019-A）'

# =====================================================================================
# 结果收集
# =====================================================================================

$script:results = New-Object System.Collections.ArrayList

function Add-FlowDeskResult([string]$Name, [string]$State, [string]$Note) {
    $null = $script:results.Add([pscustomobject]@{ Name = $Name; State = $State; Note = $Note })
    switch ($State) {
        'PASS' { Write-Host ("   [PASS] {0}" -f $Name) -ForegroundColor Green }
        'FAIL' { Write-Host ("   [FAIL] {0}" -f $Name) -ForegroundColor Red }
        'SKIP' { Write-Host ("   [SKIP] {0}" -f $Name) -ForegroundColor Yellow }
        default { Write-Host ("   [{0}] {1}" -f $State, $Name) -ForegroundColor Gray }
    }
    if ($Note) { Write-Host ("          $Note") -ForegroundColor DarkGray }
}

# =====================================================================================
# 工具函数
# =====================================================================================

<#
    读取某个端口的实际监听地址（用于验证「真的只绑在 127.0.0.1」）。
#>
function Get-FlowDeskListenAddress([int]$Port) {
    try {
        $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop
        return @($connections | ForEach-Object { "$($_.LocalAddress)" } | Sort-Object -Unique)
    }
    catch {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $lines = & netstat -ano -p TCP 2>$null }
        finally { $ErrorActionPreference = $previous }

        $pattern = 'TCP\s+(\S+):' + $Port + '\s+\S+\s+LISTENING'
        $addresses = @()
        foreach ($line in @($lines)) {
            if ("$line" -match $pattern) { $addresses += $Matches[1] }
        }
        return @($addresses | Sort-Object -Unique)
    }
}

<#
    从 tools/call 的 JSON-RPC 响应里取出工具返回的 JSON 载体。
    MCP 的返回形状是 result.content[0].text（一段 JSON 文本），这里把它解析出来。
#>
function Get-FlowDeskToolPayload($JsonRpcResponse) {
    if (-not $JsonRpcResponse -or -not $JsonRpcResponse.result) { return $null }
    $content = @($JsonRpcResponse.result.content)
    if ($content.Count -eq 0) { return $null }
    $text = $content[0].text
    if (-not $text) { return $null }
    try { return ($text | ConvertFrom-Json) } catch { return $null }
}

function New-FlowDeskJsonRpcBody([int]$Id, [string]$Method, [string]$ParamsJson) {
    if ($ParamsJson) {
        return '{"jsonrpc":"2.0","id":' + $Id + ',"method":"' + $Method + '","params":' + $ParamsJson + '}'
    }
    return '{"jsonrpc":"2.0","id":' + $Id + ',"method":"' + $Method + '"}'
}

# =====================================================================================
# 目标地址与模式
# =====================================================================================

$state = $null
try { $state = Read-FlowDeskState } catch { $state = $null }

$urls = @{
    'main-service'   = 'http://127.0.0.1:8080'
    'asset-mcp'      = 'http://127.0.0.1:8091'
    'monitoring-mcp' = 'http://127.0.0.1:8092'
}
$ports = @{ 'main-service' = 8080; 'asset-mcp' = 8091; 'monitoring-mcp' = 8092 }

if ($state -and $state.services) {
    Write-FlowDeskInfo "使用运行记录里的地址与端口：$global:FlowDeskStatePath"

    foreach ($record in @($state.services)) {
        # 先看这条记录本身是否可信：字段齐全、服务名已知、目标 JAR 属于该服务、端口合法。
        # 不可信的记录不会被拿来构造请求地址（否则会拿着一个编出来的端口去"检查"）。
        $shape = Test-FlowDeskRecordShape -Record $record
        if (-not $shape.Ok) {
            Add-FlowDeskResult "运行记录可信（$($record.name)）" 'FAIL' $shape.Reason
            continue
        }
        if ($urls.ContainsKey($record.name)) {
            # 端口同样先 TryParse 再使用：坏字段不允许在这里抛异常
            $recordPort = 0
            if ([int]::TryParse("$($record.port)", [ref]$recordPort) -and $recordPort -ge 1 -and $recordPort -le 65535) {
                $urls[$record.name] = $record.url
                $ports[$record.name] = $recordPort
            }
            else {
                Add-FlowDeskResult "运行记录端口合法（$($record.name)）" 'FAIL' "运行记录里的端口不合法：$($record.port)"
            }
        }
    }
}
else {
    Write-FlowDeskWarn "没有找到运行记录（$global:FlowDeskStatePath），按默认端口 8080/8091/8092 检查。"
}

$effectiveMode = $Mode
if (-not $effectiveMode) {
    if ($state -and $state.mode) { $effectiveMode = "$($state.mode)" } else { $effectiveMode = 'unknown' }
}
Write-FlowDeskInfo "模式：$effectiveMode"

$client = New-FlowDeskHttpClient
$client.Timeout = [TimeSpan]::FromSeconds($RequestTimeoutSec)

# =====================================================================================
# 1. 健康检查与实际监听地址
# =====================================================================================

Write-FlowDeskStep '1/4 三个服务的健康检查与监听地址'

foreach ($name in @('asset-mcp', 'monitoring-mcp', 'main-service')) {
    $baseUrl = $urls[$name]
    $port = $ports[$name]

    $probe = Invoke-FlowDeskHttp -Client $client -Method 'GET' -Url "$baseUrl/actuator/health"
    if ($probe.Status -eq 200 -and $probe.Json -and "$($probe.Json.status)" -eq 'UP') {
        Add-FlowDeskResult "$name /actuator/health 健康" 'PASS' "$baseUrl/actuator/health → 200 status=UP"
    }
    else {
        $note = if ($probe.Error) { $probe.Error } else { "HTTP $($probe.Status)" }
        Add-FlowDeskResult "$name /actuator/health 健康" 'FAIL' "$baseUrl/actuator/health → $note"
    }

    # 注意：函数返回单个元素时 PowerShell 会把数组拆成标量，这里必须显式包成数组
    $addresses = @(Get-FlowDeskListenAddress -Port $port)
    if ($addresses.Count -eq 1 -and $addresses[0] -eq '127.0.0.1') {
        Add-FlowDeskResult "$name 只监听 127.0.0.1（端口 $port）" 'PASS' "实际监听地址：127.0.0.1"
    }
    else {
        Add-FlowDeskResult "$name 只监听 127.0.0.1（端口 $port）" 'FAIL' ("实际监听地址：" + (($addresses -join ', ')))
    }
}

# =====================================================================================
# 2. 两个 MCP 服务的真实协议检查
# =====================================================================================

<#
    跑一遍完整的 MCP 会话：initialize → initialized → tools/list → tools/call（命中 + 未命中）
    → finally 里 DELETE 会话。每一步都按 Content-Type 解析响应体。
#>
function Test-FlowDeskMcpService {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$ToolName,
        [Parameter(Mandatory = $true)][string]$HitAssetId,
        [Parameter(Mandatory = $true)][hashtable]$HitFields,
        [Parameter(Mandatory = $true)][string]$MissAssetId,
        [Parameter(Mandatory = $true)][string]$MissErrorCode
    )

    $mcpUrl = "$BaseUrl/mcp"
    $sessionId = $null

    try {
        # ---- initialize ----
        $initializeBody = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":' +
            '{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"flowdesk-smoke","version":"1.0.0"}}}'
        $init = Invoke-FlowDeskHttp -Client $client -Method 'POST' -Url $mcpUrl -Body $initializeBody

        if ($init.Status -ne 200) {
            $note = if ($init.Error) { $init.Error } else { "HTTP $($init.Status)" }
            Add-FlowDeskResult "$Name initialize 握手" 'FAIL' "POST $mcpUrl → $note"
            return
        }
        $sessionId = $init.SessionId
        if (-not $sessionId) {
            Add-FlowDeskResult "$Name initialize 握手" 'FAIL' 'initialize 返回 200，但没有下发 Mcp-Session-Id'
            return
        }
        $serverName = $null
        if ($init.Json -and $init.Json.result -and $init.Json.result.serverInfo) {
            $serverName = $init.Json.result.serverInfo.name
        }
        Add-FlowDeskResult "$Name initialize 握手" 'PASS' "200 + Mcp-Session-Id；serverInfo.name=$serverName"

        # ---- initialized 通知 ----
        $initializedBody = '{"jsonrpc":"2.0","method":"notifications/initialized"}'
        $notified = Invoke-FlowDeskHttp -Client $client -Method 'POST' -Url $mcpUrl -Body $initializedBody -SessionId $sessionId
        if ($notified.Status -eq 202) {
            Add-FlowDeskResult "$Name initialized 通知" 'PASS' '202 Accepted'
        }
        else {
            Add-FlowDeskResult "$Name initialized 通知" 'FAIL' "期望 202，实际 HTTP $($notified.Status)"
        }

        # ---- tools/list ----
        $listBody = New-FlowDeskJsonRpcBody -Id 2 -Method 'tools/list' -ParamsJson $null
        $list = Invoke-FlowDeskHttp -Client $client -Method 'POST' -Url $mcpUrl -Body $listBody -SessionId $sessionId

        if ($list.Status -ne 200 -or -not $list.Json -or -not $list.Json.result) {
            Add-FlowDeskResult "$Name tools/list" 'FAIL' "HTTP $($list.Status)，响应里没有 JSON-RPC result"
        }
        else {
            $tools = @($list.Json.result.tools)
            $names = @($tools | ForEach-Object { $_.name })
            $schema = $null
            if ($tools.Count -gt 0) { $schema = $tools[0].inputSchema }
            $requiredOk = $false
            $additionalOk = $false
            if ($schema) {
                $requiredOk = (@($schema.required) -contains 'assetId')
                $additionalOk = ($schema.additionalProperties -eq $false)
            }

            if ($tools.Count -eq 1 -and $names[0] -eq $ToolName -and $requiredOk -and $additionalOk) {
                Add-FlowDeskResult "$Name tools/list 只暴露固定只读工具" 'PASS' `
                    "恰好 1 个工具：$ToolName；required=[assetId]；additionalProperties=false"
            }
            else {
                Add-FlowDeskResult "$Name tools/list 只暴露固定只读工具" 'FAIL' `
                    "工具数=$($tools.Count)，名字=[$($names -join ', ')]，required=[$(@($schema.required) -join ', ')]，additionalProperties=$($schema.additionalProperties)"
            }
        }

        # ---- tools/call：演示命中 ----
        $hitCallBody = New-FlowDeskJsonRpcBody -Id 3 -Method 'tools/call' `
            -ParamsJson ('{"name":"' + $ToolName + '","arguments":{"assetId":"' + $HitAssetId + '"}}')
        $hit = Invoke-FlowDeskHttp -Client $client -Method 'POST' -Url $mcpUrl -Body $hitCallBody -SessionId $sessionId
        $hitPayload = Get-FlowDeskToolPayload $hit.Json

        if ($hit.Status -ne 200 -or -not $hitPayload) {
            Add-FlowDeskResult "$Name tools/call 演示命中（$HitAssetId）" 'FAIL' `
                "HTTP $($hit.Status)；无法取得工具返回的 JSON 载荷"
        }
        elseif ($hit.Json.result.isError -ne $false) {
            Add-FlowDeskResult "$Name tools/call 演示命中（$HitAssetId）" 'FAIL' 'isError 不为 false'
        }
        else {
            $mismatches = @()
            foreach ($key in $HitFields.Keys) {
                $expected = $HitFields[$key]
                $actual = $hitPayload.$key
                if ("$actual" -ne "$expected") { $mismatches += "$key 期望=$expected 实际=$actual" }
            }
            if ($mismatches.Count -eq 0) {
                Add-FlowDeskResult "$Name tools/call 演示命中（$HitAssetId）" 'PASS' `
                    ("isError=false；" + (($HitFields.Keys | ForEach-Object { "$_=$($HitFields[$_])" }) -join '，'))
            }
            else {
                Add-FlowDeskResult "$Name tools/call 演示命中（$HitAssetId）" 'FAIL' ($mismatches -join '；')
            }
        }

        # ---- tools/call：演示未命中（未命中不是工具失败）----
        $missCallBody = New-FlowDeskJsonRpcBody -Id 4 -Method 'tools/call' `
            -ParamsJson ('{"name":"' + $ToolName + '","arguments":{"assetId":"' + $MissAssetId + '"}}')
        $miss = Invoke-FlowDeskHttp -Client $client -Method 'POST' -Url $mcpUrl -Body $missCallBody -SessionId $sessionId
        $missPayload = Get-FlowDeskToolPayload $miss.Json

        if ($miss.Status -ne 200 -or -not $missPayload) {
            Add-FlowDeskResult "$Name tools/call 演示未命中（$MissAssetId）" 'FAIL' `
                "HTTP $($miss.Status)；无法取得工具返回的 JSON 载荷"
        }
        elseif ($miss.Json.result.isError -ne $false) {
            Add-FlowDeskResult "$Name tools/call 演示未命中（$MissAssetId）" 'FAIL' `
                '未命中被当成了工具失败（isError 应为 false）'
        }
        elseif ($missPayload.found -ne $false -or "$($missPayload.error)" -ne $MissErrorCode) {
            Add-FlowDeskResult "$Name tools/call 演示未命中（$MissAssetId）" 'FAIL' `
                "期望 found=false + error=$MissErrorCode，实际 found=$($missPayload.found) + error=$($missPayload.error)"
        }
        else {
            Add-FlowDeskResult "$Name tools/call 演示未命中（$MissAssetId）" 'PASS' `
                "isError=false；found=false；error=$MissErrorCode；source=$($missPayload.source)"
        }
    }
    finally {
        # 无论前面成功还是失败，都要结束自己的会话
        if ($sessionId) {
            $deleted = Invoke-FlowDeskHttp -Client $client -Method 'DELETE' -Url $mcpUrl -SessionId $sessionId
            if ($deleted.Status -eq 200) {
                Add-FlowDeskResult "$Name 会话终止（DELETE /mcp）" 'PASS' '200'
            }
            else {
                Add-FlowDeskResult "$Name 会话终止（DELETE /mcp）" 'FAIL' "期望 200，实际 HTTP $($deleted.Status)"
            }
        }
        else {
            Add-FlowDeskResult "$Name 会话终止（DELETE /mcp）" 'FAIL' '没有会话标识，无法终止会话'
        }
    }
}

Write-FlowDeskStep '2/4 资产 MCP 的真实协议调用'

Test-FlowDeskMcpService -Name 'asset-mcp' -BaseUrl $urls['asset-mcp'] -ToolName 'asset_get' `
    -HitAssetId 'AST-900001' `
    -HitFields @{ assetId = 'AST-900001'; assetType = 'SERVER'; status = 'IN_SERVICE'; source = 'DEMO' } `
    -MissAssetId 'AST-000000' -MissErrorCode 'ASSET_NOT_FOUND'

Write-FlowDeskStep '3/4 监控 MCP 的真实协议调用'

Test-FlowDeskMcpService -Name 'monitoring-mcp' -BaseUrl $urls['monitoring-mcp'] -ToolName 'monitoring_snapshot_get' `
    -HitAssetId 'AST-900001' `
    -HitFields @{ assetId = 'AST-900001'; observedAt = '2026-01-01T00:00:00Z'; health = 'DEGRADED';
                  cpuUtilizationPercent = 92; memoryUtilizationPercent = 68; activeAlertCount = 1; source = 'DEMO' } `
    -MissAssetId 'AST-900003' -MissErrorCode 'MONITORING_SNAPSHOT_NOT_FOUND'

# =====================================================================================
# 4. 请求体编码与 AI 端点装配
# =====================================================================================

Write-FlowDeskStep '4/4 请求体编码与 AI 端点装配'

# 4a. 请求体必须是 UTF-8 无 BOM：直接检查我们发出去的字节前缀
$probeContent = New-FlowDeskJsonContent -Body '{"query":"中文编码探针"}'
$probeBytes = $probeContent.ReadAsByteArrayAsync().GetAwaiter().GetResult()
$hasBom = ($probeBytes.Length -ge 3 -and $probeBytes[0] -eq 0xEF -and $probeBytes[1] -eq 0xBB -and $probeBytes[2] -eq 0xBF)
if ($hasBom) {
    Add-FlowDeskResult '请求体构造：UTF-8 且无 BOM' 'FAIL' '请求体以 EF BB BF 开头，服务端会判为非法 JSON'
}
else {
    Add-FlowDeskResult '请求体构造：UTF-8 且无 BOM' 'PASS' '字节前缀不是 EF BB BF'
}

# 4b. 脚本文件是 UTF-8（中文文案必须逐字解析正确）
# 预期码点与下面的字面量必须逐字对应：中 文 文 案 探 针 ： 告 警 、 监 控 、 资 产
$chineseProbe = '中文文案探针：告警、监控、资产'
$expectedCodePoints = @(0x4E2D, 0x6587, 0x6587, 0x6848, 0x63A2, 0x9488, 0xFF1A, 0x544A, 0x8B66, 0x3001,
    0x76D1, 0x63A7, 0x3001, 0x8D44, 0x4EA7)
$actualCodePoints = @()
foreach ($character in $chineseProbe.ToCharArray()) { $actualCodePoints += [int]$character }
$codePointDiff = Compare-Object -ReferenceObject $expectedCodePoints -DifferenceObject $actualCodePoints

if (-not $codePointDiff) {
    Add-FlowDeskResult '脚本中文文案：UTF-8 源文件解析正确' 'PASS' '中文字面量的码点与预期逐字一致（文件带 BOM，未被按 ANSI 误读）'
}
else {
    Add-FlowDeskResult '脚本中文文案：UTF-8 源文件解析正确' 'FAIL' `
        "中文字面量解析结果与预期不符：$chineseProbe（实际码点数 $($actualCodePoints.Count)，预期 $($expectedCodePoints.Count)）"
}

# 4c. 含中文的 JSON 请求体必须能进入业务层（而不是在 JSON 解析阶段就失败）
$searchBody = '{"query":"VPN 无法连接，应该如何排查？","topK":5,"minScore":0.3}'
$search = Invoke-FlowDeskHttp -Client $client -Method 'POST' `
    -Url "$($urls['main-service'])/api/v1/knowledge/search" -Body $searchBody

if ($search.Status -eq 503 -and $search.Json -and "$($search.Json.code)" -eq 'KNOWLEDGE_EMBEDDING_DISABLED') {
    Add-FlowDeskResult '含中文的 JSON 请求体被正确解析（无 BOM/编码问题）' 'PASS' `
        'POST /api/v1/knowledge/search → 503 KNOWLEDGE_EMBEDDING_DISABLED（已进入业务层，而非 JSON 解析错误）'
}
elseif ($search.Json -and "$($search.Json.detail)" -eq '请求体不是合法 JSON') {
    Add-FlowDeskResult '含中文的 JSON 请求体被正确解析（无 BOM/编码问题）' 'FAIL' `
        '服务端判定「请求体不是合法 JSON」——请求体编码有问题'
}
else {
    Add-FlowDeskResult '含中文的 JSON 请求体被正确解析（无 BOM/编码问题）' 'FAIL' `
        "期望 503 KNOWLEDGE_EMBEDDING_DISABLED，实际 HTTP $($search.Status)：$($search.Body)"
}

# 4d. AI 端点装配：Basic 模式必须没有 AI 接口
if ($effectiveMode -eq 'basic') {
    foreach ($path in @('/api/v1/ai/asset-diagnosis', '/api/v1/ai/incident-triage')) {
        $probe = Invoke-FlowDeskHttp -Client $client -Method 'POST' `
            -Url "$($urls['main-service'])$path" -Body '{}'
        $code = $null
        if ($probe.Json) { $code = "$($probe.Json.code)" }

        if ($probe.Status -eq 404 -and $code -eq 'ENDPOINT_NOT_FOUND') {
            Add-FlowDeskResult "Basic 模式：$path 是 404（无 AI 能力）" 'PASS' `
                '404 ENDPOINT_NOT_FOUND：控制器未注册，不可能生成研判答案'
        }
        else {
            Add-FlowDeskResult "Basic 模式：$path 是 404（无 AI 能力）" 'FAIL' `
                "期望 404 ENDPOINT_NOT_FOUND，实际 HTTP $($probe.Status)，code=$code"
        }
    }
}
else {
    Add-FlowDeskResult 'Basic 模式：AI 接口应为 404' 'SKIP' `
        "当前模式是 $effectiveMode，不适用（本脚本不会为了检查而去调用 AI 接口生成答案）"
}

# =====================================================================================
# 汇总
# =====================================================================================

$client.Dispose()

$passCount = @($script:results | Where-Object { $_.State -eq 'PASS' }).Count
$failCount = @($script:results | Where-Object { $_.State -eq 'FAIL' }).Count
$skipCount = @($script:results | Where-Object { $_.State -eq 'SKIP' }).Count

Write-FlowDeskTitle '冒烟检查结果'
Write-FlowDeskInfo ("PASS {0} 项，FAIL {1} 项，SKIP {2} 项。" -f $passCount, $failCount, $skipCount)

if ($failCount -gt 0) {
    Write-Host ''
    Write-FlowDeskWarn '失败项：'
    foreach ($item in @($script:results | Where-Object { $_.State -eq 'FAIL' })) {
        Write-FlowDeskInfo "  $($item.Name) —— $($item.Note)"
    }
    Write-Host ''
    Write-FlowDeskInfo "日志目录：$global:FlowDeskLogDir"
    exit 1
}

Write-Host ''
Write-FlowDeskInfo '这是「真实进程 + 真实 MCP 协议 + 演示数据」的验证，不是真实企业数据源验证：'
Write-FlowDeskInfo '所有命中的 source 都是 DEMO（虚构数据）。'
Write-FlowDeskInfo '本脚本没有调用 AI 接口生成答案，也没有新建或修改任何业务数据。'
if ($effectiveMode -eq 'basic') {
    Write-FlowDeskInfo 'Basic 模式没有 AI 回答能力（AI 接口为 404），也没有验证真实 DeepSeek/DashScope/PostgreSQL。'
}
exit 0
