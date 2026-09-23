<#
.SYNOPSIS
    FlowDesk AI 演示脚本的离线自测（FD-0019-B）。

.DESCRIPTION
    对 scripts\flowdesk-demo-common.ps1 里的纯函数做正反例测试：
    请求构造（含中文、引号、换行的往返）、响应契约校验、展示行生成、错误分类映射，
    以及「未传 -InvokeModel 时 POST 次数为 0」。

    **本自测不需要任何 Key、不调用模型、不依赖服务运行、不发任何网络请求**，
    也不需要新的工具链或依赖：响应一律用内存对象或固定 JSON 文本构造。

    .OUTPUTS
    退出码：0 = 全部通过；1 = 有失败项。
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$scriptsDir = Split-Path -Parent $PSCommandPath
. (Join-Path $scriptsDir 'flowdesk-local-common.ps1')
. (Join-Path $scriptsDir 'flowdesk-demo-common.ps1')

Write-FlowDeskTitle 'FlowDesk AI 演示脚本自测（离线：无 Key、无模型调用、无网络请求）'

$script:passed = 0
$script:failed = 0

function Assert-FlowDeskDemo([string]$Name, [bool]$Condition, [string]$Note) {
    if ($Condition) {
        $script:passed++
        Write-Host ("   [PASS] {0}" -f $Name) -ForegroundColor Green
    }
    else {
        $script:failed++
        Write-Host ("   [FAIL] {0}" -f $Name) -ForegroundColor Red
    }
    if ($Note) { Write-Host ("          $Note") -ForegroundColor DarkGray }
}

<#
    固定 JSON 文本 -> 内存对象（不联网、不读文件）。
#>
function New-FlowDeskDemoFixture([string]$JsonText) {
    return ($JsonText | ConvertFrom-Json)
}

# =====================================================================================
# A. 请求构造（JSON 序列化 + UTF-8 无 BOM）
# =====================================================================================
Write-FlowDeskStep 'A. 请求构造'

$diagnosisJson = New-FlowDeskDemoRequestJson -Scenario 'diagnosis' -AssetId 'AST-900001'
$diagnosisObject = New-FlowDeskDemoFixture $diagnosisJson
Assert-FlowDeskDemo 'A1 资产诊断请求：assetId 正确，且不带 question' `
    (($diagnosisObject.assetId -eq 'AST-900001') -and (-not $diagnosisObject.PSObject.Properties['question'])) $diagnosisJson

$triageQuestion = '服务器出现持续告警，应该如何排查？'
$triageJson = New-FlowDeskDemoRequestJson -Scenario 'triage' -AssetId 'AST-900001' -Question $triageQuestion
$triageObject = New-FlowDeskDemoFixture $triageJson
Assert-FlowDeskDemo 'A2 事件研判请求：assetId 与 question 都正确（topK/minScore 省略）' `
    (($triageObject.assetId -eq 'AST-900001') -and ($triageObject.question -ceq $triageQuestion) -and
     (-not $triageObject.PSObject.Properties['topK']) -and (-not $triageObject.PSObject.Properties['minScore'])) $triageJson

$rounded = (New-FlowDeskDemoRequestJson -Scenario 'triage' -AssetId 'AST-900001' -Question $triageQuestion |
    ConvertFrom-Json).question
Assert-FlowDeskDemo 'A3 中文问题往返一致（序列化后解析回来逐字相同）' ($rounded -ceq $triageQuestion) $rounded

$tricky = '第一行 "带引号" 与 \反斜杠\' + "`n" + '第二行：制表	符与 $(Get-Process) 文本'
$trickyRounded = (New-FlowDeskDemoRequestJson -Scenario 'triage' -AssetId 'AST-900001' -Question $tricky |
    ConvertFrom-Json).question
Assert-FlowDeskDemo 'A4 引号/换行/反斜杠/制表符往返一致（按数据转义，不拼接）' ($trickyRounded -ceq $tricky) `
    "往返长度：原文 $($tricky.Length) / 往返 $($trickyRounded.Length)"

$bytes = Get-FlowDeskDemoRequestBytes -Json $triageJson
Assert-FlowDeskDemo 'A5 请求体是 UTF-8 且**无 BOM**（字节前缀不是 EF BB BF）' `
    (-not ($bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) `
    ("首字节：$($bytes[0])")

$defaultQuestion = Get-FlowDeskDemoDefaultQuestion
Assert-FlowDeskDemo 'A6 默认演示问题是固定的中文问题且非空' `
    (-not [string]::IsNullOrWhiteSpace($defaultQuestion)) $defaultQuestion

# =====================================================================================
# B. 完整证据结果
# =====================================================================================
Write-FlowDeskStep 'B. 完整证据结果（知识 + 资产 + 监控全部命中）'

$fullTriage = New-FlowDeskDemoFixture @'
{
  "requestId": "9c1b7d20-1111-2222-3333-444455556666",
  "answer": "采样周期配置偏短 [M1]，资产在保 [A1]，建议按手册调整 [K1]。",
  "grounded": true,
  "usedEvidenceIds": ["M1", "A1", "K1"],
  "executionPath": ["validate_asset", "retrieve_knowledge", "query_asset", "query_monitoring",
                    "verify_contracts", "evidence_gate", "generate_answer", "validate_citations", "finish"],
  "knowledge": { "status": "FOUND",
                 "retrieval": { "citations": [ { "citationId": "K1", "documentTitle": "告警排查手册" } ] } },
  "asset": { "outcome": "FOUND", "assetId": "AST-900001", "assetType": "SERVER",
             "status": "IN_SERVICE", "source": "DEMO" },
  "monitoring": { "outcome": "FOUND", "assetId": "AST-900001", "observedAt": "2026-01-01T00:00:00Z",
                  "health": "DEGRADED", "cpuUtilizationPercent": 92, "memoryUtilizationPercent": 68,
                  "activeAlertCount": 1, "source": "DEMO" }
}
'@

$verdict = Test-FlowDeskDemoResponse -Scenario 'triage' -Json $fullTriage
Assert-FlowDeskDemo 'B1 完整研判响应通过契约校验' $verdict.Ok $verdict.Reason

$lines = @(Get-FlowDeskDemoDisplayLines -Scenario 'triage' -Json $fullTriage)
$joined = $lines -join "`n"
Assert-FlowDeskDemo 'B2 展示包含 requestId / grounded / usedEvidenceIds / 三路状态 / executionPath' `
    (($joined -match 'requestId: ') -and ($joined -match 'grounded : true') -and
     ($joined -match 'M1, A1, K1') -and ($joined -match 'knowledge.status = FOUND') -and
     ($joined -match 'asset.outcome = FOUND') -and ($joined -match 'monitoring.outcome = FOUND') -and
     ($joined -match 'validate_asset -> retrieve_knowledge')) '见下方展示行节选'

$fullDiagnosis = New-FlowDeskDemoFixture @'
{
  "requestId": "aaaa1111-2222-3333-4444-555566667777",
  "answer": "资产在保 [A1]，监控显示降级 [M1]。",
  "grounded": true,
  "usedEvidenceIds": ["A1", "M1"],
  "asset": { "outcome": "FOUND", "assetId": "AST-900001", "assetType": "SERVER",
             "status": "IN_SERVICE", "source": "DEMO" },
  "monitoring": { "outcome": "FOUND", "assetId": "AST-900001", "observedAt": "2026-01-01T00:00:00Z",
                  "health": "DEGRADED", "cpuUtilizationPercent": 92, "memoryUtilizationPercent": 68,
                  "activeAlertCount": 1, "source": "DEMO" }
}
'@
$verdict = Test-FlowDeskDemoResponse -Scenario 'diagnosis' -Json $fullDiagnosis
Assert-FlowDeskDemo 'B3 完整诊断响应通过契约校验（不需要 knowledge/executionPath）' $verdict.Ok $verdict.Reason

$demoLines = @(Get-FlowDeskDemoDisplayLines -Scenario 'diagnosis' -Json $fullDiagnosis) -join "`n"
Assert-FlowDeskDemo 'B4 source=DEMO 被明确标为虚构演示数据' `
    ($demoLines -match 'source=DEMO：这是\*\*虚构的演示数据\*\*|虚构的演示数据') 'DEMO 标注'

# =====================================================================================
# C. 部分证据：知识 FAILED，但资产/监控 FOUND
# =====================================================================================
Write-FlowDeskStep 'C. 部分证据（知识 FAILED + 资产/监控 FOUND）'

$partialTriage = New-FlowDeskDemoFixture @'
{
  "requestId": "bbbb1111-2222-3333-4444-555566667777",
  "answer": "资产在保 [A1]，监控降级 [M1]；知识库本次不可用。",
  "grounded": true,
  "usedEvidenceIds": ["A1", "M1"],
  "executionPath": ["validate_asset", "retrieve_knowledge", "query_asset", "query_monitoring",
                    "verify_contracts", "evidence_gate", "generate_answer", "finish"],
  "knowledge": { "status": "FAILED", "failure": "DISABLED" },
  "asset": { "outcome": "FOUND", "assetId": "AST-900001", "assetType": "SERVER", "status": "IN_SERVICE", "source": "DEMO" },
  "monitoring": { "outcome": "FOUND", "assetId": "AST-900001", "observedAt": "2026-01-01T00:00:00Z",
                  "health": "DEGRADED", "cpuUtilizationPercent": 92, "memoryUtilizationPercent": 68,
                  "activeAlertCount": 1, "source": "DEMO" }
}
'@

$verdict = Test-FlowDeskDemoResponse -Scenario 'triage' -Json $partialTriage
Assert-FlowDeskDemo 'C1 部分证据响应通过契约校验' $verdict.Ok $verdict.Reason

$partialLines = @(Get-FlowDeskDemoDisplayLines -Scenario 'triage' -Json $partialTriage) -join "`n"
Assert-FlowDeskDemo 'C2 知识侧如实显示 FAILED + failure=DISABLED（没有 retrieval，也不塌缩成 NOT_FOUND）' `
    (($partialLines -match 'knowledge.status = FAILED（failure = DISABLED）') -and
     ($partialLines -notmatch 'knowledge.status = NOT_FOUND')) 'FAILED 原样展示'

Assert-FlowDeskDemo 'C3 提示 FAILED / DISABLED 不得改写成 NOT_FOUND' `
    ($partialLines -match 'FAILED / DISABLED 是「这次查询没成功」') '语义提示'

# =====================================================================================
# D. 无证据降级
# =====================================================================================
Write-FlowDeskStep 'D. 无证据降级（grounded=false，引用为空）'

$degraded = New-FlowDeskDemoFixture @'
{
  "requestId": "cccc1111-2222-3333-4444-555566667777",
  "answer": "未查询到该资产或可用的监控快照。",
  "grounded": false,
  "usedEvidenceIds": [],
  "executionPath": ["validate_asset", "retrieve_knowledge", "query_asset", "query_monitoring",
                    "verify_contracts", "evidence_gate", "fallback_answer", "finish"],
  "knowledge": { "status": "FAILED", "failure": "DISABLED" },
  "asset": { "outcome": "NOT_FOUND", "assetId": "AST-900001", "source": "DEMO" },
  "monitoring": { "outcome": "NOT_FOUND", "assetId": "AST-900001", "source": "DEMO" }
}
'@

$verdict = Test-FlowDeskDemoResponse -Scenario 'triage' -Json $degraded
Assert-FlowDeskDemo 'D1 降级响应通过契约校验（空数组也是合法形状）' $verdict.Ok $verdict.Reason

$degradedLines = @(Get-FlowDeskDemoDisplayLines -Scenario 'triage' -Json $degraded) -join "`n"
Assert-FlowDeskDemo 'D2 grounded=false 被明确说成降级结果，而不是诊断成功' `
    (($degradedLines -match 'grounded : false') -and ($degradedLines -match '降级结果')) '降级提示'

Assert-FlowDeskDemo 'D3 引用为空如实展示（不伪造引用）' ($degradedLines -match 'usedEvidenceIds: （空）') '空引用'

# =====================================================================================
# E. FAILED 与 NOT_FOUND 保持区别
# =====================================================================================
Write-FlowDeskStep 'E. FAILED 与 NOT_FOUND 的区别'

$failedSide = New-FlowDeskDemoFixture @'
{
  "requestId": "dddd1111-2222-3333-4444-555566667777",
  "answer": "本次没有可引用的证据。",
  "grounded": false,
  "usedEvidenceIds": [],
  "asset": { "outcome": "FAILED", "failure": "TIMEOUT" },
  "monitoring": { "outcome": "NOT_FOUND", "assetId": "AST-900001", "source": "DEMO" }
}
'@
$verdict = Test-FlowDeskDemoResponse -Scenario 'diagnosis' -Json $failedSide
Assert-FlowDeskDemo 'E1 资产 FAILED（带 failure=TIMEOUT）通过契约校验' $verdict.Ok $verdict.Reason

$failedLines = @(Get-FlowDeskDemoDisplayLines -Scenario 'diagnosis' -Json $failedSide) -join "`n"
Assert-FlowDeskDemo 'E2 FAILED 一侧只显示 outcome 与 failure（不伪造编号与来源）' `
    (($failedLines -match 'asset.outcome = FAILED（failure = TIMEOUT）') -and
     ($failedLines -match 'monitoring.outcome = NOT_FOUND') -and
     ($failedLines -match 'FAILED / DISABLED 是「这次查询没成功」')) '两侧区分保留'

$noFailure = New-FlowDeskDemoFixture '{"requestId":"x","answer":"a","grounded":false,"usedEvidenceIds":[],"asset":{"outcome":"FAILED"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}'
$verdict = Test-FlowDeskDemoResponse -Scenario 'diagnosis' -Json $noFailure
Assert-FlowDeskDemo 'E3 FAILED 但没有 failure 分类 → 判为契约错误（不静默通过）' `
    ((-not $verdict.Ok) -and ($verdict.Reason -like '*failure*')) $verdict.Reason

# =====================================================================================
# F. 契约错误：非 JSON / 缺字段 / 类型不符
# =====================================================================================
Write-FlowDeskStep 'F. 契约错误'

$contractCases = @(
    @{ n = 'F1 响应对象为 $null（非 JSON）'; j = $null },
    @{ n = 'F2 缺少 requestId'; j = (New-FlowDeskDemoFixture '{"answer":"a","grounded":false,"usedEvidenceIds":[],"asset":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}') },
    @{ n = 'F3 grounded 是字符串而不是布尔'; j = (New-FlowDeskDemoFixture '{"requestId":"x","answer":"a","grounded":"true","usedEvidenceIds":[],"asset":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}') },
    @{ n = 'F4 usedEvidenceIds 不是数组'; j = (New-FlowDeskDemoFixture '{"requestId":"x","answer":"a","grounded":false,"usedEvidenceIds":"A1","asset":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}') },
    @{ n = 'F5 缺少 asset'; j = (New-FlowDeskDemoFixture '{"requestId":"x","answer":"a","grounded":false,"usedEvidenceIds":[],"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}') },
    @{ n = 'F6 缺少 monitoring'; j = (New-FlowDeskDemoFixture '{"requestId":"x","answer":"a","grounded":false,"usedEvidenceIds":[],"asset":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}') },
    @{ n = 'F7 asset.outcome 不是已知枚举'; j = (New-FlowDeskDemoFixture '{"requestId":"x","answer":"a","grounded":false,"usedEvidenceIds":[],"asset":{"outcome":"MAYBE","assetId":"AST-900001","source":"DEMO"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}') }
)
foreach ($case in $contractCases) {
    $verdict = Test-FlowDeskDemoResponse -Scenario 'diagnosis' -Json $case.j
    Assert-FlowDeskDemo $case.n (-not $verdict.Ok) $verdict.Reason
}

$triageContractCases = @(
    @{ n = 'F8 研判缺少 executionPath'; j = (New-FlowDeskDemoFixture '{"requestId":"x","answer":"a","grounded":false,"usedEvidenceIds":[],"knowledge":{"status":"FAILED","failure":"DISABLED"},"asset":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}') },
    @{ n = 'F9 研判缺少 knowledge'; j = (New-FlowDeskDemoFixture '{"requestId":"x","answer":"a","grounded":false,"usedEvidenceIds":[],"executionPath":["validate_asset","finish"],"asset":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}') },
    @{ n = 'F10 研判 knowledge.status 未知'; j = (New-FlowDeskDemoFixture '{"requestId":"x","answer":"a","grounded":false,"usedEvidenceIds":[],"executionPath":["finish"],"knowledge":{"status":"EMPTY"},"asset":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}') }
)
foreach ($case in $triageContractCases) {
    $verdict = Test-FlowDeskDemoResponse -Scenario 'triage' -Json $case.j
    Assert-FlowDeskDemo $case.n (-not $verdict.Ok) $verdict.Reason
}

# =====================================================================================
# G. 错误映射：400 / 404 / 502 / 传输失败 / 其它状态码
# =====================================================================================
Write-FlowDeskStep 'G. HTTP 与传输层的映射'

function New-FlowDeskDemoResult([int]$Status, $Json, [string]$Error) {
    return [pscustomobject]@{ Status = $Status; ContentType = 'application/json'; Body = '';
        SessionId = $null; Json = $Json; Error = $Error }
}

$badRequest = New-FlowDeskDemoFixture '{"type":"urn:flowdesk:problem:invalid-request","title":"请求不合法","status":400,"detail":"assetId 必须形如 AST-000001","code":"INVALID_REQUEST"}'
$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 400 $badRequest $null) -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'G1 400 → Http400，并展示服务端的安全字段（code/detail）' `
    (($failure.Kind -eq 'Http400') -and ($failureText -match 'INVALID_REQUEST') -and
     ($failureText -match 'assetId 必须形如 AST-000001')) $failure.Kind

$notFound = New-FlowDeskDemoFixture '{"title":"端点不存在","status":404,"detail":"没有这个端点","code":"ENDPOINT_NOT_FOUND"}'
$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 404 $notFound $null) -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'G2 404 → Http404，提示可能未启用 AI 但**不**断言唯一原因' `
    (($failure.Kind -eq 'Http404') -and ($failureText -match '不\*\*断言它是唯一原因') -and
     ($failureText -match 'ENDPOINT_NOT_FOUND')) $failure.Kind

$providerError = New-FlowDeskDemoFixture '{"title":"AI 服务错误","status":502,"detail":"上游 AI 服务暂时不可用，请稍后重试","code":"AI_PROVIDER_ERROR","requestId":"eeee1111-2222-3333-4444-555566667777"}'
$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 502 $providerError $null) -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'G3 502 → Http502，展示稳定错误码与 requestId，且明确不自动重试' `
    (($failure.Kind -eq 'Http502') -and ($failureText -match 'AI_PROVIDER_ERROR') -and
     ($failureText -match 'eeee1111-2222-3333-4444-555566667777') -and
     ($failureText -match '不会\*\*自动重试')) $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 0 $null 'No connection could be made because the target machine actively refused it.') -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'G4 连接被拒 → Transport（且**不**回显原始异常文本）' `
    (($failure.Kind -eq 'Transport') -and ($failureText -match '连接被拒') -and
     ($failureText -notmatch 'actively refused')) $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 0 $null 'A task was canceled because the request timed out.') -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'G5 超时 → Transport（不回显原始异常文本）' `
    (($failure.Kind -eq 'Transport') -and ($failureText -match '请求超时') -and
     ($failureText -notmatch 'timed out')) $failure.Kind

# 本机 .NET 的异常文本是**本地化中文**的：Invoke-FlowDeskHttp 只透出最外层消息
$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 0 $null '使用“0”个参数调用“GetResult”时发生异常:“发送请求时出错。”') -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'G6 本地化中文异常文本 → 归为连接失败（服务可能没在监听），且不回显原文' `
    (($failure.Kind -eq 'Transport') -and ($failureText -match '连接失败') -and
     ($failureText -notmatch '发送请求时出错')) $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 500 $null $null) -Scenario 'diagnosis'
Assert-FlowDeskDemo 'G6 500 → HttpOther（归入未分类，不静默成功）' ($failure.Kind -eq 'HttpOther') $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 200 $null $null) -Scenario 'diagnosis'
Assert-FlowDeskDemo 'G7 200 但响应体不是 JSON → NonJson（按契约错误处理）' ($failure.Kind -eq 'NonJson') $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 200 (New-FlowDeskDemoFixture '{"requestId":"x"}') $null) -Scenario 'diagnosis'
Assert-FlowDeskDemo 'G8 200 但字段缺失 → Contract' ($failure.Kind -eq 'Contract') $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 200 $fullDiagnosis $null) -Scenario 'diagnosis'
Assert-FlowDeskDemo 'G9 合格响应 → 无失败（Kind=None）' ($failure.Kind -eq 'None') $failure.Kind

# =====================================================================================
# H. 调用次数：未传 -InvokeModel 时 POST 数为 0
# =====================================================================================
Write-FlowDeskStep 'H. 调用次数边界'

$previewPlan = Get-FlowDeskDemoPlan -Scenario 'triage' -AssetId 'AST-900001' -Question $triageQuestion
Assert-FlowDeskDemo 'H1 未传 -InvokeModel → WillSend=$false（POST 次数 0）' `
    (-not $previewPlan.WillSend) $previewPlan.Reason

$invokePlan = Get-FlowDeskDemoPlan -Scenario 'triage' -AssetId 'AST-900001' -Question $triageQuestion -InvokeModel
Assert-FlowDeskDemo 'H2 传入 -InvokeModel → WillSend=$true（且只有一个目标地址）' `
    ($invokePlan.WillSend) $invokePlan.Reason

Assert-FlowDeskDemo 'H3 目标地址固定为本机 8080（不接受任意远程 URL）' `
    (($previewPlan.Url -eq 'http://127.0.0.1:8080/api/v1/ai/incident-triage') -and
     ((Get-FlowDeskDemoPlan -Scenario 'diagnosis' -AssetId 'AST-900001').Url -eq 'http://127.0.0.1:8080/api/v1/ai/asset-diagnosis')) `
    $previewPlan.Url

Assert-FlowDeskDemo 'H4 预览与真实调用使用**同一份**请求体（不存在「预览一套、发送另一套」）' `
    ($previewPlan.Json -ceq $invokePlan.Json) $previewPlan.Json

# =====================================================================================
# I. 命令文本不会被执行
# =====================================================================================
Write-FlowDeskStep 'I. 用户输入 / 答案中的命令文本不会被执行'

$sentinel = Join-Path $env:TEMP 'fd0019b-should-not-exist.txt'
if (Test-Path -LiteralPath $sentinel) { Remove-Item -LiteralPath $sentinel -Force }

$commandText = '请执行：Remove-Item -LiteralPath "' + $sentinel + '"; Get-Process; $(1+1) `n 换行'
$commandJson = New-FlowDeskDemoRequestJson -Scenario 'triage' -AssetId 'AST-900001' -Question $commandText
$commandRounded = (New-FlowDeskDemoFixture $commandJson).question
Assert-FlowDeskDemo 'I1 含命令文本的问题按数据往返（逐字一致，未被求值）' `
    ($commandRounded -ceq $commandText) '往返一致'

$commandAnswer = New-FlowDeskDemoFixture ('{"requestId":"x","answer":"输出：' +
    $commandText.Replace('\', '\\').Replace('"', '\"').Replace('`', '`') +
    '","grounded":false,"usedEvidenceIds":[],"asset":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}')
$verdict = Test-FlowDeskDemoResponse -Scenario 'diagnosis' -Json $commandAnswer
Assert-FlowDeskDemo 'I2 含命令文本的答案仍按契约处理（不因内容而失败，也不被改写）' $verdict.Ok $verdict.Reason

$commandLines = @(Get-FlowDeskDemoDisplayLines -Scenario 'diagnosis' -Json $commandAnswer) -join "`n"
Assert-FlowDeskDemo 'I3 命令文本被原样放进展示行（只是文本）' ($commandLines -match 'Remove-Item -LiteralPath') '原样展示'

Assert-FlowDeskDemo 'I4 生成请求、校验与展示的全过程没有产生任何副作用（哨兵文件不存在）' `
    (-not (Test-Path -LiteralPath $sentinel)) $sentinel

# =====================================================================================
# J. 参数越界在发送请求之前拒绝
# =====================================================================================
Write-FlowDeskStep 'J. 参数校验（在发送任何请求之前）'

Assert-FlowDeskDemo 'J1 -RequestTimeoutSec 0 被拒绝' `
    ($null -ne (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 0 -Min 1 -Max 600)) `
    (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 0 -Min 1 -Max 600)

Assert-FlowDeskDemo 'J2 -RequestTimeoutSec 601 被拒绝' `
    ($null -ne (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 601 -Min 1 -Max 600)) `
    (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 601 -Min 1 -Max 600)

Assert-FlowDeskDemo 'J3 -RequestTimeoutSec 90 被接受（正向对照）' `
    ($null -eq (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 90 -Min 1 -Max 600)) '合法'

Assert-FlowDeskDemo 'J4 场景名不合法时 Get-FlowDeskDemoScenario 返回空（不会去发请求）' `
    ($null -eq (Get-FlowDeskDemoScenario -Scenario 'chat')) '非法场景返回 $null'

Assert-FlowDeskDemo 'J5 场景名大小写不敏感地归一化' `
    ((Get-FlowDeskDemoScenario -Scenario 'Triage') -eq 'triage') 'Triage -> triage'

# =====================================================================================
# 汇总
# =====================================================================================
Write-FlowDeskTitle '自测结果'
Write-FlowDeskInfo ("PASS {0} 项，FAIL {1} 项。" -f $script:passed, $script:failed)
Write-FlowDeskInfo '本自测没有调用任何模型、没有发送任何网络请求，也没有读取任何 Key。'

if ($script:failed -gt 0) {
    exit 1
}
exit 0
