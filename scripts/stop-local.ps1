<#
.SYNOPSIS
    停止 scripts/start-local.ps1 启动的 FlowDesk 本地服务（FD-0019-A）。

.DESCRIPTION
    只操作**本项目脚本记录在 .local-run/state.json 里、并且身份核实通过**的进程。
    身份核对同时校验四项：PID、进程名（java）、进程启动时间、命令行里的目标 JAR ——
    这样即使旧 PID 已被系统复用给别的程序，也不会误杀。

    记录缺失/损坏/身份不符时如实报告并跳过，不扩大终止范围；
    绝不使用「按所有 java.exe」或「按端口占用者直接杀进程」的方式。

    日志全部保留；不删除数据库、上传内容或任何用户文件。

.PARAMETER GracefulWaitSec
    单个进程等待正常关闭的秒数（默认 8 秒）；超时后再强制终止，并明确记录用了哪种方式。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\stop-local.ps1
#>
[CmdletBinding()]
param(
    [int]$GracefulWaitSec = 8
)

$ErrorActionPreference = 'Stop'

$scriptsDir = Split-Path -Parent $PSCommandPath
. (Join-Path $scriptsDir 'flowdesk-local-common.ps1')

Write-FlowDeskTitle 'FlowDesk 本地停止（FD-0019-A）'

# ---------- 读取运行记录 ----------
try {
    $state = Read-FlowDeskState
}
catch {
    Write-FlowDeskFail $_.Exception.Message
    Write-FlowDeskInfo '未终止任何进程。请人工确认后删除该文件，或直接重新启动（start-local.ps1）。'
    exit 1
}

if (-not $state -or -not $state.services) {
    Write-FlowDeskInfo "没有记录在案的运行实例（$global:FlowDeskStatePath 不存在或为空）。"
    Write-FlowDeskInfo '重复停止是安全的：本次没有做任何事。'
    exit 0
}

$records = @($state.services)
Write-FlowDeskInfo "运行记录：$global:FlowDeskStatePath"
Write-FlowDeskInfo "记录中的模式：$($state.mode)；服务数：$($records.Count)"

# 先停主服务（它在最前面时最后停止），避免它继续持有与 MCP 服务的会话
[array]::Reverse($records)

# ---------- 逐个核对身份并停止 ----------
Write-FlowDeskStep '核对身份并停止'

$stopped = @()
$skipped = @()
$failed = @()

foreach ($record in $records) {
    $verdict = Test-FlowDeskRecordedProcess -Record $record

    if (-not $verdict.Alive) {
        Write-FlowDeskInfo "$($record.name)（PID=$($record.pid)）：$($verdict.Reason)，跳过。"
        $skipped += "PID=$($record.pid)"
        continue
    }

    if (-not $verdict.Ok) {
        Write-FlowDeskWarn "$($record.name)（PID=$($record.pid)）：$($verdict.Reason) —— 跳过，不终止。"
        Write-FlowDeskWarn '  （记录里的目标 JAR：' + $record.jar + '）'
        $skipped += "PID=$($record.pid)"
        continue
    }

    Write-FlowDeskInfo "$($record.name)（PID=$($record.pid)）身份已核实，正在停止…"
    $result = Stop-FlowDeskVerifiedProcess -Record $record -GracefulWaitSec $GracefulWaitSec

    if ($result.Stopped) {
        if ($result.Method -eq '正常关闭') {
            Write-FlowDeskOk "$($record.name)（PID=$($record.pid)）已正常关闭"
        }
        else {
            Write-FlowDeskWarn "$($record.name)（PID=$($record.pid)）已**强制终止**（$($result.Note)）"
            Write-FlowDeskInfo '  说明：控制台型 Java 进程没有窗口，通常拿不到正常关闭路径，因此这里记录了强制终止。'
        }
        $stopped += $record
    }
    else {
        Write-FlowDeskFail "$($record.name)（PID=$($record.pid)）停止失败：$($result.Note)"
        $failed += $record
    }
}

# ---------- 确认端口释放 ----------
Write-FlowDeskStep '确认端口释放'

$stillBusy = @()
foreach ($record in $records) {
    $released = $false
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        if ((Get-FlowDeskPortListenerPid -Port $record.port).Count -eq 0) {
            $released = $true
            break
        }
        Start-Sleep -Milliseconds 300
    }

    if ($released) {
        Write-FlowDeskOk "端口 $($record.port) 已释放（$($record.name)）"
    }
    else {
        $occupant = Format-FlowDeskPortOccupant -Port $record.port
        Write-FlowDeskWarn "端口 $($record.port) 仍被占用：$occupant"
        $stillBusy += $record.port
    }
}

# ---------- 收尾 ----------
Write-FlowDeskStep '收尾'

if ($failed.Count -eq 0) {
    Remove-FlowDeskState
    Write-FlowDeskOk "已删除运行记录（$global:FlowDeskStatePath）"
}
else {
    Write-FlowDeskWarn "有 $($failed.Count) 个进程未能停止，保留运行记录以便下次重试。"
}

Write-Host ''
Write-FlowDeskInfo "日志保留在：$global:FlowDeskLogDir（不会被删除）"
Write-FlowDeskInfo '没有删除数据库、上传内容或任何用户文件。'

Write-Host ''
Write-FlowDeskInfo ("停止 {0} 个，跳过 {1} 个，失败 {2} 个。" -f $stopped.Count, $skipped.Count, $failed.Count)

if ($failed.Count -gt 0 -or $stillBusy.Count -gt 0) {
    exit 1
}
exit 0
