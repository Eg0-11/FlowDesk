<#
.SYNOPSIS
    FlowDesk AI 演示脚本的离线自测（FD-0019-B / B-R1）。

.DESCRIPTION
    对 scripts\flowdesk-demo-common.ps1 里的纯函数做正反例测试：
    请求构造（含中文、引号、换行的往返）、**按现有 HTTP DTO 逐字段**的响应契约校验、
    展示行生成、错误分类映射、真实发送计数（离线替身），以及「未传 -InvokeModel 时 POST 次数为 0」。

    **本自测不需要任何 Key、不调用模型、不依赖服务运行、不发任何网络请求**，
    也不需要新的工具链或依赖：响应一律用内存对象或固定 JSON 文本构造，
    真实发送路径用**离线替身传输**计数（生产脚本不开放任意远程地址）。

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

<#
    一条合法的 asset / monitoring FOUND 片段（与真实 DTO 逐字段对应）。
#>
function New-FlowDeskDemoAssetFound([string]$Source = 'DEMO') {
    return (@{ outcome = 'FOUND'; assetId = 'AST-900001'; assetType = 'SERVER'; status = 'IN_SERVICE'; source = $Source } |
        ConvertTo-Json -Compress | ConvertFrom-Json)
}

function New-FlowDeskDemoMonitoringFound([string]$Source = 'DEMO') {
    return (@{
        outcome = 'FOUND'; assetId = 'AST-900001'; observedAt = '2026-01-01T00:00:00Z'; health = 'DEGRADED'
        cpuUtilizationPercent = 92; memoryUtilizationPercent = 68; activeAlertCount = 1; source = $Source
    } | ConvertTo-Json -Compress | ConvertFrom-Json)
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
# B. 响应契约：完整 / 部分 / 降级 三种真实形状
# =====================================================================================
Write-FlowDeskStep 'B. 响应契约：完整 / 部分 / 降级'

$fullTriage = New-FlowDeskDemoFixture @'
{
  "requestId": "9c1b7d20-1111-2222-3333-444455556666",
  "answer": "采样周期配置偏短 [M1]，资产在保 [A1]，建议按手册调整 [K1]。",
  "grounded": true,
  "usedEvidenceIds": ["M1", "A1", "K1"],
  "executionPath": ["validate_asset", "retrieve_knowledge", "query_asset", "query_monitoring",
                    "verify_contracts", "evidence_gate", "generate_answer", "validate_citations", "finish"],
  "knowledge": { "status": "FOUND",
                 "retrieval": { "provider": "dashscope", "model": "text-embedding-v3", "dimensions": 1024,
                                "topK": 5, "minScore": 0.3, "rankingMode": "VECTOR_SIMILARITY",
                                "citations": [ { "citationId": "K1", "rank": 1, "documentTitle": "告警排查手册" } ] } },
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
Assert-FlowDeskDemo 'B2 展示包含 requestId / grounded / usedEvidenceIds / 三路状态 / citations / executionPath' `
    (($joined -match 'requestId: ') -and ($joined -match 'grounded : true') -and
     ($joined -match 'M1, A1, K1') -and ($joined -match 'knowledge.status = FOUND') -and
     ($joined -match 'asset.outcome = FOUND') -and ($joined -match 'monitoring.outcome = FOUND') -and
     ($joined -match 'citations: K1') -and ($joined -match 'validate_asset -> retrieve_knowledge')) '见展示行'

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
    ($demoLines -match '虚构的演示数据') 'DEMO 标注'

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
Assert-FlowDeskDemo 'B5 部分证据响应（知识 FAILED + 资产/监控 FOUND）通过契约校验' $verdict.Ok $verdict.Reason

$partialLines = @(Get-FlowDeskDemoDisplayLines -Scenario 'triage' -Json $partialTriage) -join "`n"
Assert-FlowDeskDemo 'B6 知识侧如实显示 FAILED + failure=DISABLED，且语义提示覆盖知识侧' `
    (($partialLines -match 'knowledge.status = FAILED（failure = DISABLED）') -and
     ($partialLines -match 'knowledge=FAILED\(DISABLED\)') -and
     ($partialLines -notmatch 'knowledge.status = NOT_FOUND')) 'FAILED 原样展示'

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
Assert-FlowDeskDemo 'B7 降级响应通过契约校验（NOT_FOUND 带 assetId/source；空数组合法）' $verdict.Ok $verdict.Reason

$degradedLines = @(Get-FlowDeskDemoDisplayLines -Scenario 'triage' -Json $degraded) -join "`n"
Assert-FlowDeskDemo 'B8 grounded=false 被明确说成降级结果，引用为空如实展示' `
    (($degradedLines -match 'grounded : false') -and ($degradedLines -match '降级结果') -and
     ($degradedLines -match 'usedEvidenceIds: （空）')) '降级提示'

# =====================================================================================
# C. 按来源与状态逐字段校验（以现有 HTTP DTO 为依据）
# =====================================================================================
Write-FlowDeskStep 'C. 逐字段契约（按来源与状态）'

$assetFound = '{"outcome":"FOUND","assetId":"AST-900001","assetType":"SERVER","status":"IN_SERVICE","source":"DEMO"}'
$monitoringFound = '{"outcome":"FOUND","assetId":"AST-900001","observedAt":"2026-01-01T00:00:00Z","health":"DEGRADED","cpuUtilizationPercent":92,"memoryUtilizationPercent":68,"activeAlertCount":1,"source":"DEMO"}'

function New-FlowDeskDemoDiagnosis([string]$AssetJson, [string]$MonitoringJson,
        [string]$RequestId = 'x', [string]$Answer = 'a',
        [bool]$Grounded = $true, [string]$Evidence = '["A1"]') {
    $text = ('{"requestId":"' + $RequestId + '","answer":"' + $Answer + '","grounded":' +
        $Grounded.ToString().ToLowerInvariant() + ',"usedEvidenceIds":' + $Evidence +
        ',"asset":' + $AssetJson + ',"monitoring":' + $MonitoringJson + '}')
    return ($text | ConvertFrom-Json)
}

$contractCases = @(
    @{ n = 'C1 asset FOUND 缺 assetType 被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis '{"outcome":"FOUND","assetId":"AST-900001","status":"IN_SERVICE","source":"DEMO"}' $monitoringFound) },
    @{ n = 'C2 asset FOUND 缺 status 被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis '{"outcome":"FOUND","assetId":"AST-900001","assetType":"SERVER","source":"DEMO"}' $monitoringFound) },
    @{ n = 'C3 asset FOUND 缺 source 被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis '{"outcome":"FOUND","assetId":"AST-900001","assetType":"SERVER","status":"IN_SERVICE"}' $monitoringFound) },
    @{ n = 'C4 asset FOUND 的 source=demo（小写）被拒绝（区分大小写）'; ok = $false
       j = (New-FlowDeskDemoDiagnosis '{"outcome":"FOUND","assetId":"AST-900001","assetType":"SERVER","status":"IN_SERVICE","source":"demo"}' $monitoringFound) },
    @{ n = 'C5 monitoring FOUND 缺 observedAt 被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound '{"outcome":"FOUND","assetId":"AST-900001","health":"DEGRADED","cpuUtilizationPercent":92,"memoryUtilizationPercent":68,"activeAlertCount":1,"source":"DEMO"}') },
    @{ n = 'C6 monitoring.observedAt 不是可解析时间被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound '{"outcome":"FOUND","assetId":"AST-900001","observedAt":"昨天下午","health":"DEGRADED","cpuUtilizationPercent":92,"memoryUtilizationPercent":68,"activeAlertCount":1,"source":"DEMO"}') },
    @{ n = 'C7 monitoring.health 不在枚举内被拒绝（区分大小写）'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound '{"outcome":"FOUND","assetId":"AST-900001","observedAt":"2026-01-01T00:00:00Z","health":"degraded","cpuUtilizationPercent":92,"memoryUtilizationPercent":68,"activeAlertCount":1,"source":"DEMO"}') },
    @{ n = 'C8 monitoring CPU 超出 0..100 被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound '{"outcome":"FOUND","assetId":"AST-900001","observedAt":"2026-01-01T00:00:00Z","health":"DEGRADED","cpuUtilizationPercent":150,"memoryUtilizationPercent":68,"activeAlertCount":1,"source":"DEMO"}') },
    @{ n = 'C9 monitoring CPU 是字符串被拒绝（类型不符）'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound '{"outcome":"FOUND","assetId":"AST-900001","observedAt":"2026-01-01T00:00:00Z","health":"DEGRADED","cpuUtilizationPercent":"92","memoryUtilizationPercent":68,"activeAlertCount":1,"source":"DEMO"}') },
    @{ n = 'C10 monitoring 告警数为负被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound '{"outcome":"FOUND","assetId":"AST-900001","observedAt":"2026-01-01T00:00:00Z","health":"DEGRADED","cpuUtilizationPercent":92,"memoryUtilizationPercent":68,"activeAlertCount":-1,"source":"DEMO"}') },
    @{ n = 'C11 monitoring 缺告警数被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound '{"outcome":"FOUND","assetId":"AST-900001","observedAt":"2026-01-01T00:00:00Z","health":"DEGRADED","cpuUtilizationPercent":92,"memoryUtilizationPercent":68,"source":"DEMO"}') },
    @{ n = 'C12 asset NOT_FOUND 缺 source 被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis '{"outcome":"NOT_FOUND","assetId":"AST-900001"}' $monitoringFound) },
    @{ n = 'C13 asset NOT_FOUND 缺 assetId 被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis '{"outcome":"NOT_FOUND","source":"DEMO"}' $monitoringFound) },
    @{ n = 'C14 asset FAILED failure 不属于 QueryFailure 被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis '{"outcome":"FAILED","failure":"KNOWLEDGE_DOWN"}' $monitoringFound) },
    @{ n = 'C15 monitoring FAILED failure 属于 QueryFailure 被接受'; ok = $true
       j = (New-FlowDeskDemoDiagnosis $assetFound '{"outcome":"FAILED","failure":"TIMEOUT"}') },
    @{ n = 'C16 requestId 纯空白被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound $monitoringFound -RequestId '   ') },
    @{ n = 'C17 answer 纯空白被拒绝'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound $monitoringFound -Answer '  ') },
    @{ n = 'C18 grounded=true 但引用为空被拒绝（明显矛盾）'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound $monitoringFound -Evidence '[]') },
    @{ n = 'C19 grounded=false 但引用非空被拒绝（明显矛盾）'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound $monitoringFound -Grounded $false) },
    @{ n = 'C20 usedEvidenceIds 含空白项被拒绝（逐项检查）'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound $monitoringFound -Evidence '["A1","  "]') },
    @{ n = 'C21 usedEvidenceIds 含非字符串被拒绝（逐项检查）'; ok = $false
       j = (New-FlowDeskDemoDiagnosis $assetFound $monitoringFound -Evidence '["A1",5]') },
    @{ n = 'C22 source=REAL 被接受（正向对照）'; ok = $true
       j = (New-FlowDeskDemoDiagnosis (New-FlowDeskDemoAssetFound 'REAL' | ConvertTo-Json -Compress) (New-FlowDeskDemoMonitoringFound 'REAL' | ConvertTo-Json -Compress)) }
)

$passCount = 0
foreach ($case in $contractCases) {
    $verdict = Test-FlowDeskDemoResponse -Scenario 'diagnosis' -Json $case.j
    if ($case.ok -eq $verdict.Ok) { $passCount++ }
    else {
        Write-Host ("   [FAIL] {0}" -f $case.n) -ForegroundColor Red
        Write-Host ("          期望 $($case.ok)，实际 $($verdict.Ok)：$($verdict.Reason)") -ForegroundColor DarkGray
        $script:failed++
    }
}
if ($passCount -eq $contractCases.Count) {
    Write-Host ("   [PASS] 上述 $($contractCases.Count) 条 asset/monitoring 契约用例全部符合预期") -ForegroundColor Green
    $script:passed += $passCount
}
else {
    Write-Host ("   [FAIL] asset/monitoring 契约用例通过 $passCount / $($contractCases.Count)") -ForegroundColor Red
    $script:failed += ($contractCases.Count - $passCount)
}

# ---- 研判的知识分支与 executionPath ----
$assetNotFound = '{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}'
$monitoringNotFound = '{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}'
$knowledgeFoundK1 = '{"status":"FOUND","retrieval":{"citations":[{"citationId":"K1","rank":1}]}}'
$knowledgeFoundNoRetrieval = '{"status":"FOUND"}'
$knowledgeFoundNoCitations = '{"status":"FOUND","retrieval":{}}'
$knowledgeFoundBadCitation = '{"status":"FOUND","retrieval":{"citations":[{"rank":1}]}}'
$knowledgeNotFoundEmpty = '{"status":"NOT_FOUND","retrieval":{"citations":[]}}'
$knowledgeFailedBadFailure = '{"status":"FAILED","failure":"TIMEOUT"}'
$knowledgeFailedDisabled = '{"status":"FAILED","failure":"DISABLED"}'

function New-FlowDeskDemoTriage([string]$KnowledgeJson,
        [string]$Path = '"validate_asset","finish"',
        [string]$Grounded = 'false', [string]$Evidence = '[]',
        [bool]$IncludeKnowledge = $true, [bool]$IncludePath = $true) {

    # 用显式 Add 构造：多行数组字面量里「"x" + $var, …」的 + 会被逗号拆开，
    # 拼出 "grounded":,false 这种非法 JSON（本轮自测抓到的坑）。
    $parts = New-Object System.Collections.Generic.List[string]
    $parts.Add('"requestId":"x"')
    $parts.Add('"answer":"a"')
    $parts.Add('"grounded":' + $Grounded)
    $parts.Add('"usedEvidenceIds":' + $Evidence)
    if ($IncludePath) { $parts.Add('"executionPath":[' + $Path + ']') }
    if ($IncludeKnowledge) { $parts.Add('"knowledge":' + $KnowledgeJson) }
    $parts.Add('"asset":' + $assetNotFound)
    $parts.Add('"monitoring":' + $monitoringNotFound)
    return ('{' + ($parts -join ',') + '}' | ConvertFrom-Json)
}

$knowledgeCases = @(
    @{ n = 'C23 研判缺 executionPath 被拒绝'; ok = $false
       j = (New-FlowDeskDemoTriage $knowledgeFoundK1 -IncludePath $false) },
    @{ n = 'C24 研判缺 knowledge 被拒绝'; ok = $false
       j = (New-FlowDeskDemoTriage $knowledgeFoundK1 -IncludeKnowledge $false) },
    @{ n = 'C25 knowledge.status 未知被拒绝（区分大小写）'; ok = $false
       j = (New-FlowDeskDemoTriage '{"status":"empty"}') },
    @{ n = 'C26 knowledge FOUND 缺 retrieval 被拒绝'; ok = $false
       j = (New-FlowDeskDemoTriage $knowledgeFoundNoRetrieval) },
    @{ n = 'C27 knowledge FOUND 缺 citations 被拒绝'; ok = $false
       j = (New-FlowDeskDemoTriage $knowledgeFoundNoCitations) },
    @{ n = 'C28 citations 条目缺 citationId 被拒绝'; ok = $false
       j = (New-FlowDeskDemoTriage $knowledgeFoundBadCitation) },
    @{ n = 'C29 knowledge FAILED failure 不属于 KnowledgeFailure 被拒绝'; ok = $false
       j = (New-FlowDeskDemoTriage $knowledgeFailedBadFailure) },
    @{ n = 'C30 knowledge NOT_FOUND（citations 为空列表）被接受'; ok = $true
       j = (New-FlowDeskDemoTriage $knowledgeNotFoundEmpty) },
    @{ n = 'C31 executionPath 含空白项被拒绝（逐项检查）'; ok = $false
       j = (New-FlowDeskDemoTriage $knowledgeFailedDisabled -Path '"validate_asset"," "') }
)

$passCount = 0
foreach ($case in $knowledgeCases) {
    if ($null -eq $case.j) {
        Write-Host ("   [FAIL] {0}：夹具构造失败（生成了空对象）" -f $case.n) -ForegroundColor Red
        $script:failed++
        continue
    }
    try {
        $verdict = Test-FlowDeskDemoResponse -Scenario 'triage' -Json $case.j
    }
    catch {
        Write-Host ("   [FAIL] {0}：校验时抛出异常：{1}" -f $case.n, $_.Exception.Message) -ForegroundColor Red
        $script:failed++
        continue
    }
    if ($case.ok -eq $verdict.Ok) { $passCount++ }
    else {
        Write-Host ("   [FAIL] {0}" -f $case.n) -ForegroundColor Red
        Write-Host ("          期望 $($case.ok)，实际 $($verdict.Ok)：$($verdict.Reason)") -ForegroundColor DarkGray
        $script:failed++
    }
}
if ($passCount -eq $knowledgeCases.Count) {
    Write-Host ("   [PASS] 上述 $($knowledgeCases.Count) 条 knowledge/executionPath 契约用例全部符合预期") -ForegroundColor Green
    $script:passed += $passCount
}
else {
    Write-Host ("   [FAIL] knowledge 契约用例通过 $passCount / $($knowledgeCases.Count)") -ForegroundColor Red
    $script:failed += ($knowledgeCases.Count - $passCount)
}

# =====================================================================================
# D. 错误映射：400 / 404 / 502 / 传输失败 / 其它状态码
# =====================================================================================
Write-FlowDeskStep 'D. HTTP 与传输层的映射'

function New-FlowDeskDemoResult([int]$Status, $Json, [string]$Error) {
    return [pscustomobject]@{ Status = $Status; ContentType = 'application/json'; Body = '';
        SessionId = $null; Json = $Json; Error = $Error }
}

$badRequest = New-FlowDeskDemoFixture '{"type":"urn:flowdesk:problem:invalid-request","title":"请求不合法","status":400,"detail":"assetId 必须形如 AST-000001","code":"INVALID_REQUEST"}'
$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 400 $badRequest $null) -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'D1 400 → Http400，并展示服务端的安全字段（code/detail）' `
    (($failure.Kind -eq 'Http400') -and ($failureText -match 'INVALID_REQUEST') -and
     ($failureText -match 'assetId 必须形如 AST-000001')) $failure.Kind

$notFound = New-FlowDeskDemoFixture '{"title":"端点不存在","status":404,"detail":"没有这个端点","code":"ENDPOINT_NOT_FOUND"}'
$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 404 $notFound $null) -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'D2 404 → Http404，提示可能未启用 AI 但**不**断言唯一原因' `
    (($failure.Kind -eq 'Http404') -and ($failureText -match '不\*\*断言它是唯一原因') -and
     ($failureText -match 'ENDPOINT_NOT_FOUND')) $failure.Kind

$providerError = New-FlowDeskDemoFixture '{"title":"AI 服务错误","status":502,"detail":"上游 AI 服务暂时不可用，请稍后重试","code":"AI_PROVIDER_ERROR","requestId":"eeee1111-2222-3333-4444-555566667777"}'
$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 502 $providerError $null) -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'D3 502 → 中性文案「AI 诊断/研判处理失败」，不断言是供应商调用失败' `
    (($failure.Kind -eq 'Http502') -and ($failureText -match 'AI 诊断/研判处理失败') -and
     ($failureText -notmatch '上游模型调用失败')) $failureText

Assert-FlowDeskDemo 'D4 502 仍保留稳定错误码与 requestId，且明确不自动重试' `
    (($failureText -match 'AI_PROVIDER_ERROR') -and
     ($failureText -match 'eeee1111-2222-3333-4444-555566667777') -and
     ($failureText -match '不会\*\*自动重试')) $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 0 $null 'No connection could be made because the target machine actively refused it.') -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'D5 连接被拒 → Transport（且**不**回显原始异常文本）' `
    (($failure.Kind -eq 'Transport') -and ($failureText -match '连接被拒') -and
     ($failureText -notmatch 'actively refused')) $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 0 $null 'A task was canceled because the request timed out.') -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'D6 超时 → Transport（不回显原始异常文本）' `
    (($failure.Kind -eq 'Transport') -and ($failureText -match '请求超时') -and
     ($failureText -notmatch 'timed out')) $failure.Kind

# 本机 .NET 的异常文本是**本地化中文**的：Invoke-FlowDeskHttp 只透出最外层消息
$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 0 $null '使用“0”个参数调用“GetResult”时发生异常:“发送请求时出错。”') -Scenario 'diagnosis'
$failureText = $failure.Lines -join "`n"
Assert-FlowDeskDemo 'D7 本地化中文异常文本 → 归为连接失败（服务可能没在监听），且不回显原文' `
    (($failure.Kind -eq 'Transport') -and ($failureText -match '连接失败') -and
     ($failureText -notmatch '发送请求时出错')) $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 500 $null $null) -Scenario 'diagnosis'
Assert-FlowDeskDemo 'D8 500 → HttpOther（归入未分类，不静默成功）' ($failure.Kind -eq 'HttpOther') $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 200 $null $null) -Scenario 'diagnosis'
Assert-FlowDeskDemo 'D9 200 但响应体不是 JSON → NonJson（按契约错误处理）' ($failure.Kind -eq 'NonJson') $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 200 (New-FlowDeskDemoFixture '{"requestId":"x"}') $null) -Scenario 'diagnosis'
Assert-FlowDeskDemo 'D10 200 但字段缺失 → Contract' ($failure.Kind -eq 'Contract') $failure.Kind

$failure = Resolve-FlowDeskDemoFailure -Result (New-FlowDeskDemoResult 200 $fullDiagnosis $null) -Scenario 'diagnosis'
Assert-FlowDeskDemo 'D11 合格响应 → 无失败（Kind=None）' ($failure.Kind -eq 'None') $failure.Kind

# =====================================================================================
# E. 真实发送计数（离线替身）：预览 0 次，显式调用 1 次，失败不重试
# =====================================================================================
Write-FlowDeskStep 'E. 真实发送计数（离线替身传输）'

Reset-FlowDeskDemoSendCount

$previewPlan = Get-FlowDeskDemoPlan -Scenario 'triage' -AssetId 'AST-900001' -Question $triageQuestion
Assert-FlowDeskDemo 'E1 未传 -InvokeModel → WillSend=$false（计划不发送）' `
    (-not $previewPlan.WillSend) $previewPlan.Reason

Assert-FlowDeskDemo 'E2 计划阶段本身不计入发送次数（构造请求不是发送）' `
    ((Get-FlowDeskDemoSendCount) -eq 0) ("当前计数：" + (Get-FlowDeskDemoSendCount))

# 离线替身传输：只回放固定响应，不发任何网络请求
$script:stubResponses = @()
$script:stubCalls = 0
$stubTransport = {
    param([string]$Url, [string]$Body)
    $script:stubCalls++
    if ($script:stubCalls -le $script:stubResponses.Count) {
        return $script:stubResponses[$script:stubCalls - 1]
    }
    return $script:stubResponses[$script:stubResponses.Count - 1]
}

# 一次成功调用：计数 +1
$script:stubCalls = 0
$script:stubResponses = @((New-FlowDeskDemoResult 200 $fullDiagnosis $null))
$result = Invoke-FlowDeskDemoSend -Client $null -Url $previewPlan.Url -Body $previewPlan.Json -Transport $stubTransport
Assert-FlowDeskDemo 'E3 显式调用一次 → 计数正好 1（替身被调用一次）' `
    (((Get-FlowDeskDemoSendCount) -eq 1) -and ($script:stubCalls -eq 1)) `
    ("计数：" + (Get-FlowDeskDemoSendCount))

# 失败调用：计数 +1，且**不会**自动重试（第二个响应是成功的，但它没有被消费）
$script:stubCalls = 0
$script:stubResponses = @((New-FlowDeskDemoResult 502 $providerError $null), (New-FlowDeskDemoResult 200 $fullDiagnosis $null))
$failedResult = Invoke-FlowDeskDemoSend -Client $null -Url $previewPlan.Url -Body $previewPlan.Json -Transport $stubTransport
Assert-FlowDeskDemo 'E4 失败后**不重试**：计数只再 +1，第二次（成功）响应没有被消费' `
    (((Get-FlowDeskDemoSendCount) -eq 2) -and ($script:stubCalls -eq 1) -and ($failedResult.Status -eq 502)) `
    ("累计计数：" + (Get-FlowDeskDemoSendCount) + "，替身调用次数：$($script:stubCalls)，返回状态：" + $failedResult.Status)

$failure = Resolve-FlowDeskDemoFailure -Result $failedResult -Scenario 'diagnosis'
Assert-FlowDeskDemo 'E5 失败结果按 Http502 报告（不吞掉、不宣称成功）' ($failure.Kind -eq 'Http502') $failure.Kind

Assert-FlowDeskDemo 'E6 生产脚本不开放任意远程地址：目标地址只能来自固定的本机基址' `
    (($previewPlan.Url.StartsWith($global:FlowDeskDemoBaseUrl)) -and
     ($global:FlowDeskDemoBaseUrl -eq 'http://127.0.0.1:8080')) $previewPlan.Url

# =====================================================================================
# F. 命令文本不会被执行（用测试自有文件作为哨兵）
# =====================================================================================
Write-FlowDeskStep 'F. 用户输入 / 答案中的命令文本不会被执行'

$sentinel = Join-Path $env:TEMP ("fd0019b-r1-sentinel-" + [guid]::NewGuid().ToString('N') + '.txt')
$testOwned = $false
try {
    # 先创建**测试自有**的哨兵文件：如果命令文本被执行，它会被删掉
    Set-Content -LiteralPath $sentinel -Value 'fd0019-b-r1 self test sentinel' -Encoding utf8
    $testOwned = $true

    $commandText = '请执行：Remove-Item -LiteralPath "' + $sentinel + '"; Get-Process; $(1+1) `n 换行'
    $commandJson = New-FlowDeskDemoRequestJson -Scenario 'triage' -AssetId 'AST-900001' -Question $commandText
    $commandRounded = (New-FlowDeskDemoFixture $commandJson).question
    Assert-FlowDeskDemo 'F1 含命令文本的问题按数据往返（逐字一致，未被求值）' `
        ($commandRounded -ceq $commandText) '往返一致'

    # 答案里也放同一段命令文本（作为响应体的一部分），并让它通过契约校验与展示
    $escaped = $commandText.Replace('\', '\\').Replace('"', '\"')
    $commandAnswer = New-FlowDeskDemoFixture ('{"requestId":"x","answer":"输出：' + $escaped +
        '","grounded":false,"usedEvidenceIds":[],"asset":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"},"monitoring":{"outcome":"NOT_FOUND","assetId":"AST-900001","source":"DEMO"}}')
    $verdict = Test-FlowDeskDemoResponse -Scenario 'diagnosis' -Json $commandAnswer
    Assert-FlowDeskDemo 'F2 含命令文本的答案仍按契约处理（不因内容而失败，也不被改写）' $verdict.Ok $verdict.Reason

    $commandLines = @(Get-FlowDeskDemoDisplayLines -Scenario 'diagnosis' -Json $commandAnswer) -join "`n"
    Assert-FlowDeskDemo 'F3 命令文本被原样放进展示行（只是文本）' ($commandLines -match 'Remove-Item -LiteralPath') '原样展示'

    Assert-FlowDeskDemo 'F4 哨兵文件**仍然存在**：请求构造、契约校验与展示都没有执行其中的命令' `
        (Test-Path -LiteralPath $sentinel) $sentinel
}
finally {
    # 清理只针对**测试自有**的资源
    if ($testOwned -and (Test-Path -LiteralPath $sentinel)) {
        Remove-Item -LiteralPath $sentinel -Force
    }
}

# =====================================================================================
# G. 参数校验（在发送任何请求之前）
# =====================================================================================
Write-FlowDeskStep 'G. 参数校验（在发送任何请求之前）'

Assert-FlowDeskDemo 'G1 -RequestTimeoutSec 0 被拒绝' `
    ($null -ne (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 0 -Min 1 -Max 600)) `
    (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 0 -Min 1 -Max 600)

Assert-FlowDeskDemo 'G2 -RequestTimeoutSec 601 被拒绝' `
    ($null -ne (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 601 -Min 1 -Max 600)) `
    (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 601 -Min 1 -Max 600)

Assert-FlowDeskDemo 'G3 -RequestTimeoutSec 90 被接受（正向对照）' `
    ($null -eq (Test-FlowDeskTimeRange -Name '-RequestTimeoutSec' -Value 90 -Min 1 -Max 600)) '合法'

Assert-FlowDeskDemo 'G4 场景名不合法时 Get-FlowDeskDemoScenario 返回空（不会去发请求）' `
    ($null -eq (Get-FlowDeskDemoScenario -Scenario 'chat')) '非法场景返回 $null'

Assert-FlowDeskDemo 'G5 场景名大小写不敏感地归一化' `
    ((Get-FlowDeskDemoScenario -Scenario 'Triage') -eq 'triage') 'Triage -> triage'

# =====================================================================================
# H. 预览与参数错误路径（真实子进程，不发送任何请求）
# =====================================================================================
Write-FlowDeskStep 'H. 预览与参数错误路径（子进程实测，POST 次数 0）'

$customAssetId = 'AST-123456'
# 注意：PowerShell 5.1 调用原生命令（含 powershell.exe -File）时，参数里的英文双引号
# 会在**进入脚本之前**被命令行解析吃掉 —— 这是宿主限制，不是脚本逻辑。
# 因此这里用不含英文双引号的问题做「自定义参数生效」的回归；
# 引号本身的转义正确性已由 A4（请求体往返）覆盖。
$customQuestion = '自定义问题：数据库连接池打满，如何排查？请给出处理顺序。'
$customTimeout = 42
$previewOut = Join-Path $env:TEMP 'fd0019b-r1-preview.log'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptsDir 'demo-ai.ps1') `
    -Scenario triage -AssetId $customAssetId -Question $customQuestion -RequestTimeoutSec $customTimeout *>&1 |
    Out-File -FilePath $previewOut -Encoding utf8
$previewExit = $LASTEXITCODE
$previewText = (Get-Content -LiteralPath $previewOut -Encoding UTF8 -Raw)

Assert-FlowDeskDemo 'H1 预览（自定义 AssetId/Question/超时）退出码 0' ($previewExit -eq 0) "退出码：$previewExit"

Assert-FlowDeskDemo 'H2 预览输出自定义的 AssetId、Question 与超时值（不被默认值替换）' `
    (($previewText -match [regex]::Escape($customAssetId)) -and
     ($previewText -match [regex]::Escape($customQuestion)) -and
     ($previewText -match [regex]::Escape("$customTimeout 秒"))) '自定义参数逐字出现在请求预览里'

Assert-FlowDeskDemo 'H3 预览输出「已发送 POST 次数：0」' ($previewText -match '已发送 POST 次数：0') '零调用'

Assert-FlowDeskDemo 'H4 预览提示改为「保留所有参数，在原命令末尾追加 -InvokeModel」（不再自动拼命令）' `
    (($previewText -match '保留刚才命令里的所有参数') -and
     ($previewText -notmatch '-Scenario triage -InvokeModel')) '提示文本'

$paramOut = Join-Path $env:TEMP 'fd0019b-r1-param.log'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptsDir 'demo-ai.ps1') `
    -Scenario triage -RequestTimeoutSec 0 *>&1 | Out-File -FilePath $paramOut -Encoding utf8
$paramExit = $LASTEXITCODE
$paramText = (Get-Content -LiteralPath $paramOut -Encoding UTF8 -Raw)

Assert-FlowDeskDemo 'H5 参数越界（-RequestTimeoutSec 0）退出码 7，且未发送任何请求' `
    (($paramExit -eq 7) -and ($paramText -match '未发送任何请求')) "退出码：$paramExit"

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
