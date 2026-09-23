<#
.SYNOPSIS
    FlowDesk 本地脚本库的自测（FD-0019-A-R1）。

.DESCRIPTION
    对 scripts/flowdesk-local-common.ps1 里「只读的判定函数」做反例测试：
    目标 JAR 路径校验、命令行切分（Windows 引号/转义规则）、
    Java 启动目标识别的**最小白名单**（只认 `java.exe -jar <JAR> <应用参数…>`）、
    运行记录形状校验、进程身份核验，以及「无法确认」与「确认进程不存在」的分类。

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
# B. Java 启动目标识别（最小白名单：只认 java.exe -jar <JAR> <应用参数…>）
# =====================================================================================
Write-FlowDeskStep 'B0. 命令行切分本身（Windows 引号/转义规则）'

# 这些直接测切分器：它必须按 CommandLineToArgvW 的语义工作，否则连白名单都判不准。
$split = ConvertTo-FlowDeskArgumentList -CommandLine '"C:\jdk\bin\java.exe" -jar "D:\Flow Desk\a.jar" --server.port=8091'
Assert-FlowDesk 'B0a 路径里的反斜杠必须原样保留（反斜杠后面不是引号时不减半）' `
    (($split.Arguments[2] -eq 'D:\Flow Desk\a.jar')) ("切分结果：" + ($split.Arguments -join ' | '))

$split = ConvertTo-FlowDeskArgumentList -CommandLine '"C:\a\b\\" x'
Assert-FlowDesk 'B0b 2 个反斜杠 + 引号 → 1 个反斜杠并把引号当定界符' `
    ($split.Arguments[0] -eq 'C:\a\b\') ("切分结果：" + ($split.Arguments -join ' | '))

$split = ConvertTo-FlowDeskArgumentList -CommandLine 'x "a""b" y'
Assert-FlowDesk 'B0c 引号内的 "" 表示一个字面量引号' `
    ($split.Arguments[1] -eq 'a"b') ("切分结果：" + ($split.Arguments -join ' | '))

$split = ConvertTo-FlowDeskArgumentList -CommandLine 'x "未闭合'
Assert-FlowDesk 'B0d 引号不成对时切分器明确失败' (-not $split.Ok) $split.Reason

Write-FlowDeskStep 'B. 启动目标白名单：只认 java.exe -jar <JAR> 这一种形式'

# ---- 正向：本脚本实际生成的命令 ----
$cmd = '"C:\jdk\bin\java.exe" -jar "' + $assetJar + '" --server.port=8091 --server.address=127.0.0.1'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B1 正向：本脚本实际生成的命令被识别，且后面应用参数里的参数不影响判定' `
    ($target.Ok -and $target.Jar -eq $assetJar) "Ok=$($target.Ok) Jar='$($target.Jar)'"

$cmd = '"C:\Program Files\Java\jdk-17\bin\java.exe" -jar "D:\Flow Desk\my app.jar" --server.port=8091'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B2 正向：含空格路径被完整识别（引号切分正确）' `
    ($target.Ok -and $target.Jar -eq 'D:\Flow Desk\my app.jar') "Ok=$($target.Ok) Jar='$($target.Jar)'"

$cmd = '"C:\jdk\bin\java.exe" -jar "' + $assetJar + '"'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B3 正向：只有三个参数（无应用参数）时也被识别' `
    ($target.Ok -and $target.Jar -eq $assetJar) "Ok=$($target.Ok) Jar='$($target.Jar)'"

# ---- 反向：白名单之外的启动形式，一律无法确认（拒绝） ----
$cmd = '"C:\jdk\bin\java.exe" --module=mymod/com.example.Main -jar "' + $assetJar + '"'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B4 拒绝：--module=<模块/主类> 后附带 -jar' (-not $target.Ok) $target.Reason

$cmd = '"C:\jdk\bin\java.exe" -m mymod/com.example.Main -jar "' + $assetJar + '"'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B5 拒绝：-m <模块/主类> 模块启动' (-not $target.Ok) $target.Reason

$cmd = '"C:\jdk\bin\java.exe" --module mymod/com.example.Main'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B6 拒绝：--module <模块/主类> 模块启动' (-not $target.Ok) $target.Reason

$cmd = '"C:\jdk\bin\java.exe" -cp app.jar com.example.Main -jar "' + $assetJar + '"'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B7 拒绝：主类启动后附带 -jar（不得把后面的 JAR 当成启动目标）' `
    ((-not $target.Ok) -and ($target.Jar -eq $null)) $target.Reason

$cmd = '"C:\jdk\bin\java.exe" com.example.Main -jar "' + $assetJar + '"'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B8 拒绝：裸主类启动后附带 -jar' (-not $target.Ok) $target.Reason

$cmd = '"C:\jdk\bin\java.exe" "' + $assetJar + '" --server.port=8091'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B9 拒绝：裸 JAR 路径（不再视为等价 -jar）' (-not $target.Ok) $target.Reason

$cmd = '"C:\jdk\bin\java.exe" -Dflowdesk.note="-jar C:\fake\fake.jar" -jar "' + $assetJar + '"'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B10 拒绝：引号内伪造的 -jar（第一个参数不是 -jar 即无法确认）' `
    ((-not $target.Ok) -and ($target.Jar -eq $null)) $target.Reason

$cmd = '"C:\jdk\bin\java.exe" -JAR "' + $assetJar + '"'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B11 拒绝：-JAR（大小写变体，必须区分大小写）' (-not $target.Ok) $target.Reason

$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine '"C:\jdk\bin\java.exe" -jar'
Assert-FlowDesk 'B12 拒绝：-jar 后面缺路径' (-not $target.Ok) $target.Reason

$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine '"C:\jdk\bin\java.exe" -jar ""'
Assert-FlowDesk 'B13 拒绝：-jar 后面是空路径' (-not $target.Ok) $target.Reason

$cmd = '"C:\jdk\bin\java.exe" -Xmx256m -jar "' + $assetJar + '"'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B14 拒绝：-jar 前面有任何 JVM 选项（不再跳过选项去找启动目标）' `
    (-not $target.Ok) $target.Reason

$cmd = '"C:\jdk\bin\java.exe" -jar "D:\dir\" --server.port=8091'
$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $cmd
Assert-FlowDesk 'B15 拒绝：引号不成对（路径以反斜杠吞掉闭合引号）' (-not $target.Ok) $target.Reason

$target = Resolve-FlowDeskJavaLaunchTarget -CommandLine '"C:\jdk\bin\java.exe" --version'
Assert-FlowDesk 'B16 拒绝：根本没有启动目标' (-not $target.Ok) $target.Reason

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
Assert-FlowDesk 'D1 PID 被别的进程占用时拒绝终止，且**不得**判成「已经不存在」' `
    ((-not $verdict.Killable) -and (-not $verdict.PidAbsent)) $verdict.Reason

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
Assert-FlowDesk 'D3 实际确认 PID 不存在时才判成「已经不存在」' `
    ((-not $verdict.Killable) -and $verdict.PidAbsent) $verdict.Reason

$shapeBrokenRecord = [pscustomobject]@{ name = 'asset-mcp'; port = 8091 }
$verdict = Test-FlowDeskRecordedProcess -Record $shapeBrokenRecord
Assert-FlowDesk 'D3b 记录缺字段时是「无法判定」，**不得**判成「已经不存在」' `
    ((-not $verdict.Killable) -and (-not $verdict.PidAbsent)) $verdict.Reason

$badJarRecord = [pscustomobject]@{
    name             = 'asset-mcp'
    pid              = $PID
    processStartTime = $selfProcess.StartTime.ToUniversalTime().ToString('o')
    jar              = 'C:\Windows\System32\java.exe'
    port             = 8091
}
$verdict = Test-FlowDeskRecordedProcess -Record $badJarRecord
Assert-FlowDesk 'D4 记录里的 JAR 在仓库外时被拒绝（连进程都不看），且不算「已不存在」' `
    ((-not $verdict.Killable) -and (-not $verdict.PidAbsent)) $verdict.Reason

$crossServiceRecord = [pscustomobject]@{
    name             = 'asset-mcp'
    pid              = $PID
    processStartTime = $selfProcess.StartTime.ToUniversalTime().ToString('o')
    jar              = $monitoringJar
    port              = 8091
}
$verdict = Test-FlowDeskRecordedProcess -Record $crossServiceRecord
Assert-FlowDesk 'D5 记录的服务与 JAR 不匹配时被拒绝，且不算「已不存在」' `
    ((-not $verdict.Killable) -and (-not $verdict.PidAbsent)) $verdict.Reason

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

    $target = Resolve-FlowDeskJavaLaunchTarget -CommandLine $commandLine
    if (-not $target.Ok) { continue }
    $jarArgument = $target.Jar

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
# F. 分类：区分「无法确认」与「确认进程不存在」（只读：整段不调用任何终止）
# =====================================================================================
Write-FlowDeskStep 'F. 无法确认 vs 确认不存在（分类结果）'

$absentRecord = [pscustomobject]@{
    name = 'asset-mcp'; pid = 999998
    processStartTime = (Get-Date).ToUniversalTime().ToString('o')
    jar = $assetJar; port = 8091
}
$corruptRecord = [pscustomobject]@{ name = 'asset-mcp'; port = 8091 }
$badPortRecord = [pscustomobject]@{
    name = 'asset-mcp'; pid = $PID
    processStartTime = $selfProcess.StartTime.ToUniversalTime().ToString('o')
    jar = $assetJar; port = '不是数字'
}
$unprovableRecord = [pscustomobject]@{
    name = 'asset-mcp'; pid = $PID
    processStartTime = $selfProcess.StartTime.ToUniversalTime().ToString('o')
    jar = $assetJar; port = 8091
}

# F1 仅含损坏记录：一条都不能算「已经不存在」，也不能宣称全部清理
$onlyCorrupt = Clear-FlowDeskStartedProcesses -Records @($corruptRecord, $badPortRecord)
Assert-FlowDesk 'F1a 仅含损坏记录时不算「已不存在」' ($onlyCorrupt.AlreadyGone.Count -eq 0) `
    "AlreadyGone=$($onlyCorrupt.AlreadyGone.Count)，Unresolved=$($onlyCorrupt.Unresolved.Count)"
Assert-FlowDesk 'F1b 仅含损坏记录时不得宣称 AllCleared' (-not $onlyCorrupt.AllCleared) `
    "AllCleared=$($onlyCorrupt.AllCleared)，Cleared=$($onlyCorrupt.Cleared.Count)"
Assert-FlowDesk 'F1c 仅含损坏记录时全部归入未处理' ($onlyCorrupt.Unresolved.Count -eq 2) `
    ("未处理：" + (($onlyCorrupt.Unresolved | ForEach-Object { $_.Reason }) -join ' | '))

# F2 混合记录：只有「实际确认 PID 不存在」的那条进 AlreadyGone
$mixed = Clear-FlowDeskStartedProcesses -Records @($absentRecord, $corruptRecord, $unprovableRecord)
Assert-FlowDesk 'F2a 混合记录里只有确认不存在的那条进 AlreadyGone' `
    ($mixed.AlreadyGone.Count -eq 1) "AlreadyGone=$($mixed.AlreadyGone.Count)"
Assert-FlowDesk 'F2b 混合记录里损坏与无法证明的都进未处理' `
    ($mixed.Unresolved.Count -eq 2) "Unresolved=$($mixed.Unresolved.Count)"
Assert-FlowDesk 'F2c 混合记录时不宣称 AllCleared、也没有终止任何东西' `
    ((-not $mixed.AllCleared) -and ($mixed.Cleared.Count -eq 0)) `
    "AllCleared=$($mixed.AllCleared)，Cleared=$($mixed.Cleared.Count)"

# F3 正向对照：全部都是确认不存在的记录 → 可以全部丢掉
$allAbsent = Clear-FlowDeskStartedProcesses -Records @($absentRecord)
Assert-FlowDesk 'F3 全部记录都确认 PID 不存在时，才允许 AllCleared' `
    ($allAbsent.AllCleared -and $allAbsent.AlreadyGone.Count -eq 1) `
    "AllCleared=$($allAbsent.AllCleared)，AlreadyGone=$($allAbsent.AlreadyGone.Count)"

# F4 损坏的 port 字段不得在分类/后续处理里抛异常
$threw = $false
try { $null = Clear-FlowDeskStartedProcesses -Records @($badPortRecord) }
catch { $threw = $true }
Assert-FlowDesk 'F4 损坏的 port 字段不会引发未处理异常' (-not $threw) '（整段分类过程无异常抛出）'

# F5 反向分类器：三类互斥且不重复计数
$state = [pscustomobject]@{ services = @($absentRecord, $corruptRecord, $unprovableRecord) }
$split = Split-FlowDeskRecordsByVerdict -State $state
Assert-FlowDesk 'F5 分类器把三类分开（Live / Stale / Unjudgeable）' `
    (($split.Live.Count -eq 0) -and ($split.Stale.Count -eq 1) -and ($split.Unjudgeable.Count -eq 2)) `
    "Live=$($split.Live.Count) Stale=$($split.Stale.Count) Unjudgeable=$($split.Unjudgeable.Count)"

# F6 整段 F 没有终止任何进程
Assert-FlowDesk 'F6 F 段没有终止任何进程（本进程与 java 计数不变）' `
    (([bool](Get-Process -Id $PID -ErrorAction SilentlyContinue)) -and
     (@(Get-Process -Name java -ErrorAction SilentlyContinue).Count -eq $javaProcesses.Count)) `
    "PID=$PID 仍在；java 计数 " + @(Get-Process -Name java -ErrorAction SilentlyContinue).Count

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
