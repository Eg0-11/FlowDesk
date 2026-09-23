<#
.SYNOPSIS
    FlowDesk AI 演示脚本的共享函数（FD-0019-B）。

.DESCRIPTION
    本文件只放**纯函数与展示逻辑**：请求构造、响应契约校验、展示行生成、错误分类。
    它不启动服务、不停止进程、不读运行记录，也不发送任何 HTTP 请求 ——
    因此 scripts\self-test-demo-ai.ps1 可以在**不依赖服务运行、不需要任何 Key**的前提下
    对这些逻辑做正反例测试。

    真正的发送动作在 scripts\demo-ai.ps1 里，并且只有在显式传入 `-InvokeModel` 时才会发生。
#>

# 演示固定访问本机主服务：不接受任意 URL，也不读取或传递模型 Key
$global:FlowDeskDemoBaseUrl = 'http://127.0.0.1:8080'
$global:FlowDeskDemoDiagnosisPath = '/api/v1/ai/asset-diagnosis'
$global:FlowDeskDemoTriagePath = '/api/v1/ai/incident-triage'

# 稳定枚举：两侧查询与知识分支各自的状态
$global:FlowDeskDemoOutcomes = @('FOUND', 'NOT_FOUND', 'FAILED')

<#
    固定的中文演示问题（仅事件研判使用）。

    刻意做成函数而不是散落在命令行里的字面量：预览与实际发送必须用同一条问题，
    否则「预览看到的」和「真正发出去的」会不一致。
#>
function Get-FlowDeskDemoDefaultQuestion {
    return '服务器出现持续告警，应该如何排查？'
}

<#
    校验并归一化场景名。

    @param Scenario 用户传入的场景
    @return 归一化后的场景名；不合法时返回 $null
#>
function Get-FlowDeskDemoScenario([string]$Scenario) {
    if (-not $Scenario) { return $null }
    $normalized = $Scenario.Trim().ToLowerInvariant()
    if ($normalized -eq 'diagnosis') { return 'diagnosis' }
    if ($normalized -eq 'triage') { return 'triage' }
    return $null
}

function Get-FlowDeskDemoEndpoint([string]$Scenario) {
    if ($Scenario -eq 'diagnosis') { return $global:FlowDeskDemoDiagnosisPath }
    return $global:FlowDeskDemoTriagePath
}

<#
    用 **JSON 序列化**构造请求体（不做字符串拼接）。

    用户问题里的引号、换行、反斜杠与 `$()` 之类文本都由序列化器正确转义，
    返回的是一段合法 JSON 文本；随后由 New-FlowDeskJsonContent 编成**无 BOM** 的 UTF-8 字节。

    @param Scenario diagnosis / triage
    @param AssetId  资产编号
    @param Question 事件/问题原文（仅 triage 使用；作为数据原样序列化，不执行）
    @return JSON 文本
#>
function New-FlowDeskDemoRequestJson {
    param(
        [Parameter(Mandatory = $true)][string]$Scenario,
        [Parameter(Mandatory = $true)][string]$AssetId,
        [string]$Question
    )

    if ($Scenario -eq 'diagnosis') {
        return ([ordered]@{ assetId = $AssetId } | ConvertTo-Json -Depth 5 -Compress)
    }

    # topK / minScore 本轮刻意省略：让服务端用自己的默认值
    return ([ordered]@{ assetId = $AssetId; question = $Question } | ConvertTo-Json -Depth 5 -Compress)
}

<#
    JSON 文本 -> 无 BOM 的 UTF-8 字节（供自测检查字节前缀）。
#>
function Get-FlowDeskDemoRequestBytes([string]$Json) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    return @($encoding.GetBytes($Json))
}

<#
    本次演示的**执行计划**（纯函数，不发送任何东西）。

    这是「未传 -InvokeModel 时绝对不发送请求」的可测试依据：
    自测可以在不联网、不启动服务的前提下断言 WillSend 为 $false。

    @return [pscustomobject] @{ WillSend; Method; Url; Path; Json; Reason }
#>
function Get-FlowDeskDemoPlan {
    param(
        [Parameter(Mandatory = $true)][string]$Scenario,
        [Parameter(Mandatory = $true)][string]$AssetId,
        [string]$Question,
        [switch]$InvokeModel
    )

    $path = Get-FlowDeskDemoEndpoint -Scenario $Scenario
    $url = "$global:FlowDeskDemoBaseUrl$path"
    $json = New-FlowDeskDemoRequestJson -Scenario $Scenario -AssetId $AssetId -Question $Question

    if ($InvokeModel) {
        return [pscustomobject]@{
            WillSend = $true
            Method   = 'POST'
            Url      = $url
            Path     = $path
            Json     = $json
            Reason   = '已显式传入 -InvokeModel，将对选定接口发送一次 POST'
        }
    }

    return [pscustomobject]@{
        WillSend = $false
        Method   = 'POST'
        Url      = $url
        Path     = $path
        Json     = $json
        Reason   = '未传入 -InvokeModel：只展示用法、请求预览与费用提示，不发送任何请求'
    }
}

<#
    校验响应是否符合已公布的响应契约。

    刻意**严格**：缺少必要字段、字段类型不符、状态枚举越界，一律判为契约错误。
    绝不允许「字段缺失就静默打印空值、然后按成功退出」。

    @param Scenario diagnosis / triage
    @param Json     已解析的响应对象（未解析时为 $null）
    @return [pscustomobject] @{ Ok; Reason }
#>
function Test-FlowDeskDemoResponse {
    param(
        [Parameter(Mandatory = $true)][string]$Scenario,
        $Json
    )

    if (-not $Json) {
        return [pscustomobject]@{ Ok = $false; Reason = '响应体为空或不是合法 JSON' }
    }
    if ($Json -isnot [psobject]) {
        return [pscustomobject]@{ Ok = $false; Reason = '响应体不是 JSON 对象' }
    }

    # ---- 通用必要字段 ----
    if (-not $Json.PSObject.Properties['requestId'] -or -not $Json.requestId -or $Json.requestId -isnot [string]) {
        return [pscustomobject]@{ Ok = $false; Reason = '缺少必要的字符串字段 requestId' }
    }
    if (-not $Json.PSObject.Properties['answer'] -or $Json.answer -isnot [string]) {
        return [pscustomobject]@{ Ok = $false; Reason = '缺少必要的字符串字段 answer' }
    }
    if (-not $Json.PSObject.Properties['grounded'] -or $Json.grounded -isnot [bool]) {
        return [pscustomobject]@{ Ok = $false; Reason = '缺少必要的布尔字段 grounded' }
    }
    if (-not $Json.PSObject.Properties['usedEvidenceIds'] -or $Json.usedEvidenceIds -isnot [array]) {
        return [pscustomobject]@{ Ok = $false; Reason = '缺少必要的数组字段 usedEvidenceIds' }
    }

    # ---- 两侧查询 ----
    foreach ($side in @('asset', 'monitoring')) {
        if (-not $Json.PSObject.Properties[$side] -or $Json.$side -isnot [psobject]) {
            return [pscustomobject]@{ Ok = $false; Reason = "缺少必要的对象字段 $side" }
        }
        $verdict = Test-FlowDeskDemoSide -Name $side -Item $Json.$side -StateField 'outcome'
        if (-not $verdict.Ok) { return $verdict }
    }

    # ---- 事件研判额外字段 ----
    if ($Scenario -eq 'triage') {
        if (-not $Json.PSObject.Properties['knowledge'] -or $Json.knowledge -isnot [psobject]) {
            return [pscustomobject]@{ Ok = $false; Reason = '缺少必要的对象字段 knowledge' }
        }
        $verdict = Test-FlowDeskDemoSide -Name 'knowledge' -Item $Json.knowledge -StateField 'status'
        if (-not $verdict.Ok) { return $verdict }
        if (-not $Json.PSObject.Properties['executionPath'] -or $Json.executionPath -isnot [array]) {
            return [pscustomobject]@{ Ok = $false; Reason = '缺少必要的数组字段 executionPath' }
        }
    }

    return [pscustomobject]@{ Ok = $true; Reason = '响应形状符合契约' }
}

<#
    校验一个来源分支：状态字段必须是稳定枚举，FAILED 时必须带稳定失败分类。

    @param Name       分支名（asset / monitoring / knowledge）
    @param Item       分支对象
    @param StateField 状态字段名（outcome 或 status）
    @return [pscustomobject] @{ Ok; Reason }
#>
function Test-FlowDeskDemoSide {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Item,
        [Parameter(Mandatory = $true)][string]$StateField
    )

    if (-not $Item.PSObject.Properties[$StateField] -or $Item.$StateField -isnot [string]) {
        return [pscustomobject]@{ Ok = $false; Reason = "$Name 缺少必要的字符串字段 $StateField" }
    }
    if ($global:FlowDeskDemoOutcomes -notcontains $Item.$StateField) {
        return [pscustomobject]@{ Ok = $false; Reason = "$Name.$StateField 不是已知状态：$($Item.$StateField)" }
    }
    if ($Item.$StateField -eq 'FAILED') {
        if (-not $Item.PSObject.Properties['failure'] -or $Item.failure -isnot [string] -or -not $Item.failure) {
            return [pscustomobject]@{ Ok = $false; Reason = "$Name 状态为 FAILED 但没有稳定的 failure 分类" }
        }
    }

    return [pscustomobject]@{ Ok = $true; Reason = "$Name 形状合法" }
}

<#
    把命中/未命中/失败的一路来源压成一行展示文本。

    只输出服务端真的给出的字段：失败的一路没有编号与来源，就不编号不来源。
#>
function Format-FlowDeskDemoSide {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Item,
        [Parameter(Mandatory = $true)][string]$StateField
    )

    $state = $Item.$StateField

    if ($state -eq 'FAILED') {
        return "$Name.$StateField = FAILED（failure = $($Item.failure)）"
    }

    $parts = @("$Name.$StateField = $state")

    if ($Item.PSObject.Properties['assetId'] -and $Item.assetId) { $parts += "assetId=$($Item.assetId)" }
    if ($Item.PSObject.Properties['assetType'] -and $Item.assetType) { $parts += "assetType=$($Item.assetType)" }
    if ($Item.PSObject.Properties['status'] -and $Item.status) { $parts += "status=$($Item.status)" }
    if ($Item.PSObject.Properties['health'] -and $Item.health) { $parts += "health=$($Item.health)" }
    if ($Item.PSObject.Properties['observedAt'] -and $Item.observedAt) { $parts += "observedAt=$($Item.observedAt)" }
    if ($Item.PSObject.Properties['cpuUtilizationPercent'] -and $null -ne $Item.cpuUtilizationPercent) {
        $parts += "cpu=$($Item.cpuUtilizationPercent)%"
    }
    if ($Item.PSObject.Properties['memoryUtilizationPercent'] -and $null -ne $Item.memoryUtilizationPercent) {
        $parts += "mem=$($Item.memoryUtilizationPercent)%"
    }
    if ($Item.PSObject.Properties['activeAlertCount'] -and $null -ne $Item.activeAlertCount) {
        $parts += "alerts=$($Item.activeAlertCount)"
    }
    if ($Item.PSObject.Properties['source'] -and $Item.source) { $parts += "source=$($Item.source)" }

    return ($parts -join '，')
}

<#
    判断某一路来源是不是「虚构的演示数据」。

    FOUND / NOT_FOUND 都带 source；DEMO 一律如实标注，不伪装成真实数据源。
#>
function Test-FlowDeskDemoSourceIsDemo($Item) {
    if ($null -eq $Item) { return $false }
    if (-not $Item.PSObject.Properties['source']) { return $false }
    return ("$($Item.source)" -eq 'DEMO')
}

<#
    生成展示行（**纯函数**，不打印、不执行任何内容）。

    答案、问题与证据正文一律按普通文本处理：这里只把它们放进输出行，
    不做求值、不做解释、不把它当成命令。

    @return 展示行数组
#>
function Get-FlowDeskDemoDisplayLines {
    param(
        [Parameter(Mandatory = $true)][string]$Scenario,
        [Parameter(Mandatory = $true)]$Json
    )

    $lines = @()
    $lines += 'requestId: ' + $Json.requestId
    $lines += 'grounded : ' + ("$($Json.grounded)").ToLowerInvariant()

    $evidence = @($Json.usedEvidenceIds)
    if ($evidence.Count -eq 0) {
        $lines += 'usedEvidenceIds: （空）'
    }
    else {
        $lines += 'usedEvidenceIds: ' + ($evidence -join ', ') + "（共 $($evidence.Count) 条）"
    }

    $lines += ''
    $lines += 'answer（普通文本，不执行、不解释为命令）：'
    foreach ($textLine in ("$($Json.answer)" -split "`r?`n")) {
        $lines += '  ' + $textLine
    }

    $lines += ''
    $lines += '-- 证据来源的真实状态 --'
    if ($Scenario -eq 'triage') {
        $lines += Format-FlowDeskDemoSide -Name 'knowledge' -Item $Json.knowledge -StateField 'status'
        if ($Json.knowledge.status -ne 'FAILED' -and $Json.knowledge.PSObject.Properties['retrieval']) {
            $citations = @($Json.knowledge.retrieval.citations)
            $lines += "  knowledge.retrieval.citations: 共 $($citations.Count) 条"
        }
    }
    $lines += Format-FlowDeskDemoSide -Name 'asset' -Item $Json.asset -StateField 'outcome'
    $lines += Format-FlowDeskDemoSide -Name 'monitoring' -Item $Json.monitoring -StateField 'outcome'

    if ($Scenario -eq 'triage') {
        $lines += ''
        $lines += '-- executionPath（按服务端原顺序）--'
        $path = @($Json.executionPath)
        if ($path.Count -eq 0) {
            $lines += '  （空）'
        }
        else {
            $lines += '  ' + ($path -join ' -> ')
        }
    }

    # ---- 关键语义提示（按实际状态给出，不伪造）----
    $lines += ''
    $lines += '-- 读法（这些提示按上面真实状态给出）--'
    $lines += '  · HTTP 200 不代表所有来源都成功：请以上面每一路的状态与 failure 为准。'

    # 失败的一侧：事件研判要连 knowledge 一起看（知识 FAILED 同样属于「这次查询没成功」）。
    # 用显式列表构造：@( @('a','b') ) 这类写法会把内层数组展开成两个字符串，
    # 循环里再取 $pair[0] 就会拿到单个字符而不是字段名。
    $failedSides = @()
    $sideStates = New-Object System.Collections.ArrayList
    if ($Scenario -eq 'triage') { [void]$sideStates.Add(@('knowledge', 'status')) }
    [void]$sideStates.Add(@('asset', 'outcome'))
    [void]$sideStates.Add(@('monitoring', 'outcome'))
    foreach ($pair in $sideStates) {
        $item = $Json.($pair[0])
        if ($item.($pair[1]) -eq 'FAILED') { $failedSides += "$($pair[0])=FAILED($($item.failure))" }
    }
    if ($Json.grounded) {
        $lines += '  · grounded=true 只表示答案引用了本次证据，**不等于答案事实正确**。'
    }
    else {
        $lines += '  · grounded=false 是**降级结果**（没有证据支撑），不能包装成模型诊断/研判成功。'
    }
    if ($failedSides.Count -gt 0) {
        $lines += '  · ' + ($failedSides -join '，') + '：FAILED / DISABLED 是「这次查询没成功」，不得改写成 NOT_FOUND。'
    }

    $demoSides = @()
    foreach ($name in @('asset', 'monitoring')) {
        if (Test-FlowDeskDemoSourceIsDemo -Item $Json.$name) { $demoSides += $name }
    }
    if ($demoSides.Count -gt 0) {
        $lines += '  · ' + ($demoSides -join '，') + ' 的 source=DEMO：这是**虚构的演示数据**，不是真实企业数据源。'
    }

    return $lines
}

<#
    把展示行打到控制台（只做输出，不写文件、不落日志）。
#>
function Show-FlowDeskDemoResult {
    param(
        [Parameter(Mandatory = $true)][string]$Scenario,
        [Parameter(Mandatory = $true)]$Json
    )

    Write-FlowDeskTitle ("演示结果：{0}" -f $(if ($Scenario -eq 'diagnosis') { '资产诊断' } else { '事件研判' }))
    foreach ($line in (Get-FlowDeskDemoDisplayLines -Scenario $Scenario -Json $Json)) {
        Write-Host $line
    }
}

<#
    把一次 HTTP 结果映射成错误分类与展示行。

    分类：
      · Transport —— Status=0 或带 Error（连接被拒、超时、DNS 等）
      · Http400   —— 请求不合法（展示服务端给出的安全字段）
      · Http404   —— 端点不存在（可能未启用 AI，但**不**断言这是唯一原因）
      · Http502   —— AI 服务错误（展示稳定错误码与 requestId，**不自动重试**）
      · HttpOther —— 其它非 2xx
      · NonJson   —— 2xx 但响应体解析不出 JSON
      · Contract  —— 2xx 且是 JSON，但形状/类型不符契约

    @return [pscustomobject] @{ Kind; Lines }
#>
function Resolve-FlowDeskDemoFailure {
    param(
        [Parameter(Mandatory = $true)]$Result,
        [Parameter(Mandatory = $true)][string]$Scenario
    )

    $lines = @()

    if ($Result.Status -eq 0 -or $Result.Error) {
        # 只按关键字归类，**不**回显原始异常文本（那可能带出内部细节）。
        # 注意：Invoke-FlowDeskHttp 只透出最外层异常文本（通常是 "An error occurred while
        # sending the request."），内层 SocketException 的信息拿不到，因此这里按最接近的
        # 含义归类，并明确提示用 test-local 检查服务状态。
        $message = "$($Result.Error)".ToLowerInvariant()
        if ($message -match 'refused|actively|拒绝') {
            $kindText = '连接被拒（8080 上可能没有服务在监听）'
        }
        elseif ($message -match 'timed out|timeout|超时|canceled|已取消|取消') {
            $kindText = '请求超时（模型首次响应可能较慢）'
        }
        elseif ($message -match 'sending the request|发送请求时出错|无法连接|no such host|解析') {
            $kindText = '连接失败（8080 上可能没有服务在监听）'
        }
        else {
            $kindText = '其它传输错误'
        }

        $lines += "连接失败或超时：$kindText，没有拿到 HTTP 响应。"
        $lines += '  （这里不回显原始异常文本，避免把内部细节带出来。）'
        $lines += '  请先用 scripts\test-local.ps1 检查三个服务是否在跑（重点看 8080 主服务）。'
        $lines += '  若服务刚启动或模型较慢，可以适当调大 -RequestTimeoutSec 后**由你自己**再执行一次。'
        return [pscustomobject]@{ Kind = 'Transport'; Lines = $lines }
    }

    $status = [int]$Result.Status
    $problem = $null
    if ($Result.Json -and $Result.Json.PSObject.Properties['code']) { $problem = $Result.Json }

    switch ($status) {
        400 {
            $lines += 'HTTP 400：请求不合法，服务端已拒绝（本次没有产生任何模型调用）。'
            if ($problem) {
                $lines += "  code   = $($problem.code)"
                if ($problem.PSObject.Properties['detail'] -and $problem.detail) {
                    $lines += "  detail = $($problem.detail)"
                }
            }
            $lines += '  请检查 -AssetId 形如 AST-000001；事件研判还要确认 -Question 非空。'
            return [pscustomobject]@{ Kind = 'Http400'; Lines = $lines }
        }
        404 {
            $lines += 'HTTP 404：这个端点不存在。'
            $lines += '  一种常见原因是主服务没有启用 AI（需要以 -Mode deepseek 启动，端点才注册）；'
            $lines += '  但 404 也可能是路径或方法不对，因此这里**不**断言它是唯一原因。'
            if ($problem -and $problem.PSObject.Properties['code']) {
                $lines += "  服务端错误码：$($problem.code)"
            }
            $lines += '  请用 scripts\stop-local.ps1 停止后，再以 -Mode deepseek -McpClient 启动。'
            return [pscustomobject]@{ Kind = 'Http404'; Lines = $lines }
        }
        502 {
            $lines += 'HTTP 502：AI 服务错误（上游模型调用失败）。'
            if ($problem) {
                $lines += "  code = $($problem.code)"
                if ($problem.PSObject.Properties['requestId'] -and $problem.requestId) {
                    $lines += "  requestId = $($problem.requestId)"
                }
            }
            $lines += '  本脚本**不会**自动重试：要再试一次，请由你自己重新执行一次命令（一次一次地、由你决定）。'
            return [pscustomobject]@{ Kind = 'Http502'; Lines = $lines }
        }
    }

    if ($status -lt 200 -or $status -ge 300) {
        $lines += "HTTP $status：既不是成功响应，也不是上面几类已分类的错误。"
        if ($problem -and $problem.PSObject.Properties['code']) {
            $lines += "  服务端错误码：$($problem.code)"
        }
        return [pscustomobject]@{ Kind = 'HttpOther'; Lines = $lines }
    }

    if (-not $Result.Json) {
        $lines += "HTTP $status，但响应体不是本脚本能解析的 JSON。"
        $lines += '  按契约错误处理：不会静默打印一堆空字段然后按成功退出。'
        return [pscustomobject]@{ Kind = 'NonJson'; Lines = $lines }
    }

    $verdict = Test-FlowDeskDemoResponse -Scenario $Scenario -Json $Result.Json
    if (-not $verdict.Ok) {
        $lines += "HTTP $status，但响应形状不符合已公布的契约：$($verdict.Reason)"
        $lines += '  按契约错误处理：这是一个需要排查的不一致，不是一个可以展示的结果。'
        return [pscustomobject]@{ Kind = 'Contract'; Lines = $lines }
    }

    return [pscustomobject]@{ Kind = 'None'; Lines = $lines }
}
