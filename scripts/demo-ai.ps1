<#
.SYNOPSIS
    FlowDesk 资产诊断 / 事件研判的命令行演示入口（FD-0019-B）。

.DESCRIPTION
    调用主服务**已有**的 HTTP 接口，把答案、引用、三个证据来源的真实状态、
    以及事件研判的实际执行路径清楚地展示出来。

    本脚本不修改 Java 生产代码、不新增后端接口、不实现前端，
    也不参与服务生命周期（那是 start-local / test-local / stop-local 的事）。

    **费用与调用边界（重要）**：
      · 未传入 -InvokeModel：只展示用法、请求预览与费用提示，**一个请求都不发**（POST 次数 = 0）；
      · 传入 -InvokeModel：只对选定接口发送 **一次** POST，不循环、不自动重试、不同时调用两个接口；
      · 一次业务 POST 不保证只有一次供应商请求：后端已有重试配置，本任务不改动它，也不虚报次数；
      · 本脚本**不读取、不接收、不传递任何模型 API Key** —— Key 由主服务从它自己的进程环境读取。

    **固定访问** http://127.0.0.1:8080：不接受任意远程 URL。

    本轮默认**不**把请求、答案与证据正文写入磁盘（只在终端展示）。

.PARAMETER Scenario
    diagnosis（资产诊断）或 triage（事件研判）；默认 triage。

.PARAMETER AssetId
    资产编号；默认 AST-900001（演示数据源里的编号）。

.PARAMETER Question
    事件/问题原文，仅 triage 使用；默认是一条固定的中文演示问题。
    它作为**数据**序列化进请求体，不会被求值或执行。

.PARAMETER RequestTimeoutSec
    单个请求的超时秒数，允许 1..600（默认 90）。越界在发送任何请求之前拒绝。

.PARAMETER InvokeModel
    明确同意本次提交**可能**调用付费模型的请求。没有它，本脚本不会发送任何生成请求。

.EXAMPLE
    # 1) 预览（不发请求、零调用）
    powershell -ExecutionPolicy Bypass -File scripts\demo-ai.ps1 -Scenario triage

.EXAMPLE
    # 2) 真正调用一次（会产生费用；由你显式决定）
    powershell -ExecutionPolicy Bypass -File scripts\demo-ai.ps1 -Scenario triage -InvokeModel

.EXAMPLE
    # 3) 资产诊断
    powershell -ExecutionPolicy Bypass -File scripts\demo-ai.ps1 -Scenario diagnosis -AssetId AST-900001 -InvokeModel

.OUTPUTS
    退出码：
      0  预览已展示（未发送任何请求），或成功展示了一次调用的结果（含降级结果）
      1  响应不符合已公布的契约（非 JSON / 缺字段 / 类型不符）
      2  HTTP 400 请求不合法
      3  HTTP 404 端点不存在（可能未启用 AI，但不断言为唯一原因）
      4  连接失败或超时
      5  HTTP 502 AI 服务错误（不自动重试）
      6  其它未分类的 HTTP 状态码
      7  参数不合法（在发送任何请求之前拒绝）
#>
[CmdletBinding()]
param(
    [ValidateSet('diagnosis', 'triage')]
    [string]$Scenario = 'triage',
    [string]$AssetId = 'AST-900001',
    [string]$Question,
    [int]$RequestTimeoutSec = 90,
    [switch]$InvokeModel
)

$ErrorActionPreference = 'Stop'

$scriptsDir = Split-Path -Parent $PSCommandPath
. (Join-Path $scriptsDir 'flowdesk-local-common.ps1')
. (Join-Path $scriptsDir 'flowdesk-demo-common.ps1')

Write-FlowDeskTitle 'FlowDesk AI 演示入口（FD-0019-B）'

# ---------- 参数校验（在发送任何请求之前）----------
$scenarioName = Get-FlowDeskDemoScenario -Scenario $Scenario
if (-not $scenarioName) {
    Write-FlowDeskFail "参数不合法：-Scenario 只能是 diagnosis 或 triage（当前值：$Scenario）"
    Write-FlowDeskInfo '未发送任何请求。'
    exit 7
}
if (-not $AssetId -or -not $AssetId.Trim()) {
    Write-FlowDeskFail '参数不合法：-AssetId 不能为空（形如 AST-000001）。'
    Write-FlowDeskInfo '未发送任何请求。'
    exit 7
}

$rangeError = Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value $RequestTimeoutSec -Min 1 -Max 600
if ($rangeError) {
    Write-FlowDeskFail "参数不合法：$rangeError"
    Write-FlowDeskInfo '未发送任何请求。'
    exit 7
}

$effectiveQuestion = if ($Question) { $Question } else { Get-FlowDeskDemoDefaultQuestion }
if ($scenarioName -eq 'triage' -and -not $effectiveQuestion.Trim()) {
    Write-FlowDeskFail '参数不合法：事件研判需要非空的 -Question。'
    Write-FlowDeskInfo '未发送任何请求。'
    exit 7
}

# ---------- 请求预览（纯展示，不发送）----------
$plan = Get-FlowDeskDemoPlan -Scenario $scenarioName -AssetId $AssetId.Trim() -Question $effectiveQuestion -InvokeModel:$InvokeModel

Write-FlowDeskStep '1/3 本次请求预览'
Write-FlowDeskInfo ("场景    ：" + $(if ($scenarioName -eq 'diagnosis') { '资产诊断 diagnosis' } else { '事件研判 triage' }))
Write-FlowDeskInfo "目标地址：$($plan.Url)（固定访问本机主服务，不接受任意远程 URL）"
Write-FlowDeskInfo "方法    ：$($plan.Method)"
Write-FlowDeskInfo "超时    ：$RequestTimeoutSec 秒（允许 1..600）"
Write-FlowDeskInfo '请求体  ：'
Write-Host "  $($plan.Json)"
Write-FlowDeskInfo '请求体由 JSON 序列化生成、以无 BOM 的 UTF-8 发送（中文/引号/换行都按数据转义，不执行）。'
if ($scenarioName -eq 'triage') {
    Write-FlowDeskInfo 'topK / minScore 本轮省略，使用服务端默认值。'
}

$sentCount = 0

if (-not $InvokeModel) {
    Write-FlowDeskStep '2/3 未调用（预览模式）'
    Write-FlowDeskWarn '这次**不会**调用任何付费模型：未传入 -InvokeModel，因此没有发出任何请求。'
    Write-FlowDeskInfo '已发送 POST 次数：0'
    Write-FlowDeskInfo '要产生一次真实调用：**保留刚才命令里的所有参数**，在原命令末尾追加 -InvokeModel 再执行。'
    Write-FlowDeskInfo '（本脚本不会替你重拼一条命令 —— 自动拼接会丢掉或改写你输入的参数。）'
    Write-FlowDeskStep '3/3 费用与前置条件提醒'
    Write-FlowDeskWarn '一次真实调用可能向模型供应商计费；本脚本不读取、不保存任何 Key。'
    Write-FlowDeskInfo '前置条件：主服务需要以 -Mode deepseek 启动（AI 端点才注册），并且 DEEPSEEK_API_KEY 由主服务的进程环境提供。'
    Write-FlowDeskInfo '当前 Embedding 关闭：知识来源不可用，事件研判只能使用 MCP 演示证据（尚不是完整 RAG 实机验收）。'
    Write-FlowDeskInfo '本脚本不把请求、答案与证据正文写入磁盘。'
    exit 0
}

# ---------- 真实调用（一次，不重试）----------
Write-FlowDeskStep '2/3 调用一次（已显式同意可能产生费用）'
Write-FlowDeskWarn '本次将对选定接口发送 **一次** POST；一次业务请求在后端可能触发多次供应商重试（本脚本不改动、也不虚报次数）。'
Write-FlowDeskInfo '本脚本不自动重试：502 或其它失败会原样报告，是否再试由你决定。'

$client = New-FlowDeskHttpClient
$client.Timeout = [TimeSpan]::FromSeconds($RequestTimeoutSec)
try {
    Reset-FlowDeskDemoSendCount
    $result = Invoke-FlowDeskDemoSend -Client $client -Url $plan.Url -Body $plan.Json
    $sentCount = Get-FlowDeskDemoSendCount
}
finally {
    $client.Dispose()
}

Write-FlowDeskInfo "已发送 POST 次数：$sentCount"

$failure = Resolve-FlowDeskDemoFailure -Result $result -Scenario $scenarioName
if ($failure.Kind -ne 'None') {
    Write-FlowDeskStep '3/3 结果（失败）'
    foreach ($line in $failure.Lines) { Write-Host "  $line" }
    Write-Host ''
    Write-FlowDeskFail "演示未完成（分类：$($failure.Kind)）。"

    switch ($failure.Kind) {
        'Contract' { exit 1 }
        'NonJson' { exit 1 }
        'Http400' { exit 2 }
        'Http404' { exit 3 }
        'Transport' { exit 4 }
        'Http502' { exit 5 }
        default { exit 6 }
    }
}

Write-FlowDeskStep '3/3 结果'
Show-FlowDeskDemoResult -Scenario $scenarioName -Json $result.Json
Write-Host ''
Write-FlowDeskOk '演示完成（结果已原样展示：来源状态、失败分类与执行路径都取自服务端响应，未做任何改写）。'
exit 0
