<#
.SYNOPSIS
    FlowDesk 本地脚本库的自测（FD-0019-A-R1）。

.DESCRIPTION
    对 scripts/flowdesk-local-common.ps1 里「只读的判定函数」做反例测试：
    目标 JAR 路径校验、-jar 参数提取、运行记录形状校验、进程身份核验。

    **本脚本是只读的**：
      · 它不启动任何服务；
      · 它**不终止任何进程** —— 全部断言都只调用判定函数并检查返回值；
      · 它用一个「PID 指向本脚本自己的 PowerShell 进程」的记录来证明
        「身份无法证明时拒绝终止」，并在检查之后断言那个进程**仍然活着**。

    退出码：0 = 全部通过；1 = 有失败项。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\self-test-local.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$scriptsDir = Split-Path -Parent $PSCommandPath
. (Join-Path $scriptsDir 'flowdesk-local-common.ps1')

Write-FlowDeskTitle 'FlowDesk 本地脚本库自测（只读，不终止任何进程）'

$script:passed = 0
$script:failed = 0
$script:skipped = 0

function Assert-FlowDesk([string]$Name, [bool]$Condition, [string]$Note) {
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

$assetService = Get-FlowDeskServiceByName 'asset-mcp'
$monitoringService = Get-FlowDeskServiceByName 'monitoring-mcp'
$repoRoot = $global:FlowDeskRepoRoot
$assetJar = Join-Path $repoRoot 'flowdesk-mcp-asset\target\flowdesk-mcp-asset-0.1.0-SNAPSHOT.jar'
$monitoringJar = Join-Path $repoRoot 'flowdesk-mcp-monitoring\target\flowdesk-mcp-monitoring-0.1.0-SNAPSHOT.jar'

Assert-FlowDesk '服务契约可查（asset-mcp / monitoring-mcp）' `
    ($null -ne $assetService -and $null -ne $monitoringService) `
    "仓库根：$repoRoot"

# =====================================================================================
# A. 目标 JAR 路径校验
# =====================================================================================
Write-FlowDeskStep 'A. 目标 JAR 必须属于该服务'

$ok = Test-FlowDeskServiceJarPath -Service $assetService -JarPath $assetJar
Assert-FlowDesk 'A1 正向对照：该服务自己的 target 下的打包产物被接受' $ok.Ok $ok.Reason

$cases = @(
    @{ n = 'A2 空串被拒绝'; v = '' },
    @{ n = 'A3 纯空白被拒绝'; v = '   ' },
    @{ n = 'A4 通配符 * 被拒绝'; v = (Join-Path $repoRoot 'flowdesk-mcp-asset\target\flowdesk-mcp-asset-*.jar') },
    @{ n = 'A5 通配符 ? 与 [] 被拒绝'; v = (Join-Path $repoRoot 'flowdesk-mcp-asset\target\flowdesk-mcp-asset-?[0-9].jar') },
    @{ n = 'A6 裸文件名（java.exe）被拒绝'; v = 'java.exe' },
    @{ n = 'A7 仓库外绝对路径被拒绝'; v = 'C:\Windows\System32\java.exe' },
    @{ n = 'A8 另一个服务的 JAR 被拒绝'; v = $monitoringJar },
    @{ n = 'A9 该模块目录但不是 target 被拒绝'; v = (Join-Path $repoRoot 'flowdesk-mcp-asset\pom.xml') },
    @{ n = 'A10 target 下但文件名不属于该模块被拒绝'; v = (Join-Path $repoRoot 'flowdesk-mcp-asset\target\flowdesk-bootstrap-0.1.0-SNAPSHOT.jar') },
    @{ n = 'A11 备份产物 .jar.original 被拒绝'; v = "$assetJar.original" }
)
foreach ($case in $cases) {
    $verdict = Test-FlowDeskServiceJarPath -Service $assetService -JarPath $case.v
    Assert-FlowDesk $case.n (-not $verdict.Ok) $verdict.Reason
}

$shapeOkPath = Join-Path $repoRoot 'flowdesk-mcp-asset\target\flowdesk-mcp-asset-9.9.9-SNAPSHOT.jar'
$verdict = Test-FlowDeskServiceJarPath -Service $assetService -JarPath $shapeOkPath
Assert-FlowDesk 'A12 形状合法但当前不存在的路径仍被接受（不要求文件存在）' $verdict.Ok $verdict.Reason

# =====================================================================================
# B. -jar 参数提取（不做子串匹配）
# =====================================================================================
Write-FlowDeskStep 'B. 只认真正的 -jar 参数，不做子串匹配'

$quoted = '"C:\jdk\bin\java.exe" -jar "' + $assetJar + '" --server.port=8091'
$extracted = Get-FlowDeskJarArgument -CommandLine $quoted
Assert-FlowDesk 'B1 带引号的 -jar 参数被完整提取' ($extracted -eq $assetJar) "提取结果：$extracted"

$bare = 'C:\jdk\bin\java.exe -jar ' + $assetJar + ' --server.port=8091'
$extracted = Get-FlowDeskJarArgument -CommandLine $bare
Assert-FlowDesk 'B2 不带引号的 -jar 参数被完整提取' ($extracted -eq $assetJar) "提取结果：$extracted"

$substringTrap = 'C:\jdk\bin\java.exe -Dflowdesk.note=' + $assetJar + '.suffix --server.port=8091'
$extracted = Get-FlowDeskJarArgument -CommandLine $substringTrap
Assert-FlowDesk 'B3 路径只作为别的参数的一部分出现时不匹配（无子串匹配）' `
    ($null -eq $extracted) "提取结果：'$extracted'（应为空）"

$trap2 = 'C:\jdk\bin\java.exe -jar NotAJar --note=x-jar nope'
$extracted = Get-FlowDeskJarArgument -CommandLine $trap2
Assert-FlowDesk 'B4 取的是真正的 -jar 参数，而不是后面那个像 -jar 的片段' `
    ($extracted -eq 'NotAJar') "提取结果：'$extracted'"

$trap3 = 'C:\jdk\bin\java.exe --note=-jar NotAJar'
$extracted = Get-FlowDeskJarArgument -CommandLine $trap3
Assert-FlowDesk 'B5 --note=-jar 这种写法不被当成 -jar（前面必须是空白或行首）' `
    ($null -eq $extracted) "提取结果：'$extracted'（应为空）"

$extracted = Get-FlowDeskJarArgument -CommandLine 'C:\jdk\bin\java.exe --server.port=8091'
Assert-FlowDesk 'B6 命令行里没有 -jar 时返回空' ($null -eq $extracted) "提取结果：'$extracted'（应为空）"

# =====================================================================================
# C. 运行记录形状
# =====================================================================================
Write-FlowDeskStep 'C. 运行记录的必要字段与服务/JAR 对应关系'

$goodRecord = [pscustomobject]@{
    name = 'asset-mcp'; pid = 4242; processStartTime = (Get-Date).ToUniversalTime().ToString('o')
    jar = $assetJar; port = 8091
}
$shape = Test-FlowDeskRecordShape -Record $goodRecord
Assert-FlowDesk 'C1 正向对照：一条合法记录被接受' $shape.Ok $shape.Reason

$shapeCases = @(
    @{ n = 'C2 缺少 pid 被拒绝'; r = [pscustomobject]@{ name = 'asset-mcp'; processStartTime = '2026-09-22T00:00:00Z'; jar = $assetJar; port = 8091 } },
    @{ n = 'C3 缺少进程启动时间被拒绝'; r = [pscustomobject]@{ name = 'asset-mcp'; pid = 4242; jar = $assetJar; port = 8091 } },
    @{ n = 'C4 端口 0 被拒绝'; r = [pscustomobject]@{ name = 'asset-mcp'; pid = 4242; processStartTime = '2026-09-22T00:00:00Z'; jar = $assetJar; port = 0 } },
    @{ n = 'C5 端口 70000 被拒绝'; r = [pscustomobject]@{ name = 'asset-mcp'; pid = 4242; processStartTime = '2026-09-22T00:00:00Z'; jar = $assetJar; port = 70000 } },
    @{ n = 'C6 启动时间无法解析被拒绝'; r = [pscustomobject]@{ name = 'asset-mcp'; pid = 4242; processStartTime = '不是时间'; jar = $assetJar; port = 8091 } },
    @{ n = 'C7 未知服务名被拒绝'; r = [pscustomobject]@{ name = 'unknown-service'; pid = 4242; processStartTime = '2026-09-22T00:00:00Z'; jar = $assetJar; port = 8091 } },
    @{ n = 'C8 记录里 JAR 属于另一个服务被拒绝'; r = [pscustomobject]@{ name = 'asset-mcp'; pid = 4242; processStartTime = '2026-09-22T00:00:00Z'; jar = $monitoringJar; port = 8091 } },
    @{ n = 'C9 PID 为 0 被拒绝'; r = [pscustomobject]@{ name = 'asset-mcp'; pid = 0; processStartTime = '2026-09-22T00:00:00Z'; jar = $assetJar; port = 8091 } }
)
foreach ($case in $shapeCases) {
    $verdict = Test-FlowDeskRecordShape -Record $case.r
    Assert-FlowDesk $case.n (-not $verdict.Ok) $verdict.Reason
}

# =====================================================================================
# D. 进程身份核验：无法证明身份就拒绝，并且绝不终止任何进程
# =====================================================================================
Write-FlowDeskStep 'D. 身份无法证明时禁止终止（只读断言）'

$selfProcess = Get-Process -Id $PID
$selfRecord = [pscustomobject]@{
    name             = 'asset-mcp'
    pid              = $PID
    processStartTime = $selfProcess.StartTime.ToUniversalTime().ToString('o')
    jar              = $assetJar
    port             = 8091
}
$verdict = Test-FlowDeskRecordedProcess -Record $selfRecord
Assert-FlowDesk 'D1 进程名不是 java 时拒绝终止（拒绝理由清晰）' `
    ((-not $verdict.Killable) -and $verdict.Exists) $verdict.Reason

$stillAlive = [bool](Get-Process -Id $PID -ErrorAction SilentlyContinue)
Assert-FlowDesk 'D2 上一步之后本进程仍然存活（核验是只读的，没有终止任何东西）' `
    $stillAlive "PID=$PID"

$missingPidRecord = [pscustomobject]@{
    name             = 'asset-mcp'
    pid              = 999999
    processStartTime = (Get-Date).ToUniversalTime().ToString('o')
    jar              = $assetJar
    port             = 8091
}
$verdict = Test-FlowDeskRecordedProcess -Record $missingPidRecord
Assert-FlowDesk 'D3 不存在的 PID 被拒绝且标记为不存在' `
    ((-not $verdict.Killable) -and (-not $verdict.Exists)) $verdict.Reason

$badJarRecord = [pscustomobject]@{
    name             = 'asset-mcp'
    pid              = $PID
    processStartTime = $selfProcess.StartTime.ToUniversalTime().ToString('o')
    jar              = 'C:\Windows\System32\java.exe'
    port             = 8091
}
$verdict = Test-FlowDeskRecordedProcess -Record $badJarRecord
Assert-FlowDesk 'D4 记录里的 JAR 在仓库外时被拒绝（连进程都不看）' `
    (-not $verdict.Killable) $verdict.Reason

$crossServiceRecord = [pscustomobject]@{
    name             = 'asset-mcp'
    pid              = $PID
    processStartTime = $selfProcess.StartTime.ToUniversalTime().ToString('o')
    jar              = $monitoringJar
    port              = 8091
}
$verdict = Test-FlowDeskRecordedProcess -Record $crossServiceRecord
Assert-FlowDesk 'D5 记录的服务与 JAR 不匹配时被拒绝' (-not $verdict.Killable) $verdict.Reason

$wrongStartRecord = [pscustomobject]@{
    name             = 'asset-mcp'
    pid              = $PID
    processStartTime = (Get-Date).AddYears(-1).ToUniversalTime().ToString('o')
    jar              = $assetJar
    port             = 8091
}
$verdict = Test-FlowDeskRecordedProcess -Record $wrongStartRecord
Assert-FlowDesk 'D6 启动时间与记录不符的记录不会通过核验' (-not $verdict.Killable) $verdict.Reason

Assert-FlowDesk 'D7 全部反例跑完后本进程仍然存活' `
    ([bool](Get-Process -Id $PID -ErrorAction SilentlyContinue)) "PID=$PID"

# =====================================================================================
# E. 用真实 java 服务进程做身份核验（仍然只读：只判定，不终止）
# =====================================================================================
Write-FlowDeskStep 'E. 真实 java 进程上的正反对照（只读）'

$javaProcesses = @(Get-Process -Name java -ErrorAction SilentlyContinue)
$checked = 0

foreach ($process in $javaProcesses) {
    if ($checked -ge 2) { break }

    $commandLine = $null
    try {
        $cim = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId=$($process.Id)" -ErrorAction Stop
        if ($cim) { $commandLine = $cim.CommandLine }
    }
    catch { $commandLine = $null }
    if (-not $commandLine) { continue }

    $jarArgument = Get-FlowDeskJarArgument -CommandLine $commandLine
    if (-not $jarArgument) { continue }

    # 用「哪个服务的 JAR 校验接受它」来定服务，而不是猜
    $service = $null
    foreach ($candidate in $global:FlowDeskServices) {
        if ((Test-FlowDeskServiceJarPath -Service $candidate -JarPath $jarArgument).Ok) { $service = $candidate; break }
    }
    if (-not $service) { continue }

    $checked++
    $startTime = $process.StartTime.ToUniversalTime().ToString('o')

    $correct = [pscustomobject]@{
        name = $service.Name; pid = $process.Id; processStartTime = $startTime
        jar = $jarArgument; port = $service.Port
    }
    $verdict = Test-FlowDeskRecordedProcess -Record $correct
    Assert-FlowDesk "E$($checked)a 正向对照：真实 $($service.Name) 进程（PID=$($process.Id)）身份核实通过" `
        $verdict.Killable $verdict.Reason

    $shifted = [pscustomobject]@{
        name = $service.Name; pid = $process.Id
        processStartTime = $process.StartTime.AddHours(-1).ToUniversalTime().ToString('o')
        jar = $jarArgument; port = $service.Port
    }
    $verdict = Test-FlowDeskRecordedProcess -Record $shifted
    Assert-FlowDesk "E$($checked)b 反向对照：同一个进程但启动时间错位 1 小时被拒绝（理由是启动时间，而不是别的检查）" `
        ((-not $verdict.Killable) -and ($verdict.Reason -like '*启动时间不符*')) $verdict.Reason

    $otherService = $global:FlowDeskServices | Where-Object { $_.Name -ne $service.Name } | Select-Object -First 1
    $otherJar = Get-FlowDeskServiceJar -Service $otherService
    if ($otherJar) {
        $crossed = [pscustomobject]@{
            name = $service.Name; pid = $process.Id; processStartTime = $startTime
            jar = $otherJar; port = $service.Port
        }
        $verdict = Test-FlowDeskRecordedProcess -Record $crossed
        Assert-FlowDesk "E$($checked)c 反向对照：记录被换成另一个服务的 JAR 后拒绝" `
            (-not $verdict.Killable) $verdict.Reason
    }
}

if ($checked -eq 0) {
    $script:skipped++
    Write-Host '   [SKIP] E 段：当前没有本仓库的 java 服务进程在运行（有服务时重跑本脚本即可覆盖）' -ForegroundColor Yellow
}

Assert-FlowDesk 'E9 以上核验过程没有终止任何 java 进程' `
    (@(Get-Process -Name java -ErrorAction SilentlyContinue).Count -eq $javaProcesses.Count) `
    ("核验前 $($javaProcesses.Count) 个 / 核验后 " + @(Get-Process -Name java -ErrorAction SilentlyContinue).Count + ' 个')

# =====================================================================================
# 汇总
# =====================================================================================
Write-FlowDeskTitle '自测结果'
Write-FlowDeskInfo ("PASS {0} 项，FAIL {1} 项，SKIP {2} 项。" -f $script:passed, $script:failed, $script:skipped)
Write-FlowDeskInfo '本脚本没有启动任何服务，也没有终止任何进程。'

if ($script:failed -gt 0) {
    exit 1
}
exit 0
