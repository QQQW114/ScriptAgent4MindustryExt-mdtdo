# MDT 邮箱服务 —— 开机自启 / 自动重启（Windows 任务计划程序）
# 用法（管理员 PowerShell）：
#   .\install-scheduled-task.ps1            # 安装并启动（开机自启 + 崩溃自动重启）
#   .\install-scheduled-task.ps1 -Status    # 查看任务状态和 /health
#   .\install-scheduled-task.ps1 -Restart   # 停止再启动任务（应用新 server.ps1 改动）
#   .\install-scheduled-task.ps1 -Remove    # 停止并删除任务
# 说明：
#   - 任务以 SYSTEM 身份在开机时启动，执行 server.ps1；服务崩溃后 Task Scheduler 每分钟重试。
#   - 如果 40150 已有手动启动的窗口，请先关闭手动窗口，再执行 -Restart。
param(
    [string]$TaskName = 'MDT Email Service',
    [switch]$Remove,
    [switch]$Restart,
    [switch]$Status,
    [switch]$NoUrlAcl
)

$ErrorActionPreference = 'Stop'
$dir = $PSScriptRoot
$healthUrl = 'http://127.0.0.1:40150/health'
$serverPath = Join-Path $dir 'server.ps1'
$configPath = Join-Path $dir 'config.json'

function Test-IsAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-EmailHealth {
    try { return (Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3) } catch { return $null }
}

function Wait-EmailHealth {
    param([int]$Seconds = 25)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $health = Get-EmailHealth
        if ($health -and $health.ok) { return $health }
        Start-Sleep -Seconds 1
    }
    return $null
}

function Show-Health {
    $health = Get-EmailHealth
    if ($health) {
        Write-Host ('健康检查：' + ($health | ConvertTo-Json -Compress)) -ForegroundColor Green
    } else {
        Write-Host '健康检查失败：40150 没有响应。' -ForegroundColor Yellow
    }
}

function Show-TaskStatus {
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($task) {
        $info = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
        Write-Host ('任务名：{0}' -f $TaskName)
        Write-Host ('状态：{0}' -f $task.State)
        if ($info) {
            Write-Host ('上次运行：{0}，结果：0x{1:X8}，下次运行：{2}' -f $info.LastRunTime, $info.LastTaskResult, $info.NextRunTime)
        }
    } else {
        Write-Host ('未安装计划任务：{0}' -f $TaskName) -ForegroundColor Yellow
    }
    Show-Health
}

function Test-RequiredFiles {
    if (-not (Test-Path -LiteralPath $serverPath -PathType Leaf)) { throw "server.ps1 不存在：$serverPath" }
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw "config.json 不存在：$configPath（请先填写配置）" }
}

function Ensure-UrlAcl {
    param([string]$Url = 'http://127.0.0.1:40150/')
    if ($NoUrlAcl) { return }
    try {
        $null = netsh.exe http show urlacl url=$Url 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host ('URLACL 已存在：{0}' -f $Url)
            return
        }
        Write-Host ('添加 URLACL：{0}' -f $Url)
        $out = netsh.exe http add urlacl url=$Url user=Everyone 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host ('添加 URLACL 失败（任务可能仍能由 SYSTEM 绑定）：{0}' -f ($out -join ' ')) -ForegroundColor Yellow
        }
    } catch {
        Write-Host ('URLACL 检查失败：{0}' -f $_.Exception.Message) -ForegroundColor Yellow
    }
}

function Get-ServerLogTail {
    param([int]$Lines = 30)
    $logDir = Join-Path $dir 'logs'
    if (-not (Test-Path -LiteralPath $logDir)) { return @() }
    $log = Get-ChildItem -LiteralPath $logDir -Filter 'email-service-*.log' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $log) { return @() }
    return @(Get-Content -LiteralPath $log.FullName -Encoding UTF8 -Tail $Lines)
}

if ($Remove) {
    if (-not (Test-IsAdmin)) { throw '请用管理员 PowerShell 运行 -Remove。' }
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host ('已停止并删除任务：{0}' -f $TaskName)
    return
}

if ($Status) {
    Show-TaskStatus
    return
}

if (-not (Test-IsAdmin)) {
    throw '请用管理员 PowerShell 运行本脚本（安装/重启计划任务需要管理员权限）。'
}
if ($Restart) {
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if (-not $task) { throw ('未安装计划任务：{0}，请先运行不带参数的本脚本安装。' -f $TaskName) }
    Write-Host ('停止任务：{0}' -f $TaskName)
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    $health = Get-EmailHealth
    if ($health) {
        Write-Host '注意：停任务后 40150 仍有进程在响应，可能是手动启动的窗口；请先关闭它。' -ForegroundColor Yellow
    }
    Write-Host ('启动任务：{0}' -f $TaskName)
    Start-ScheduledTask -TaskName $TaskName
    $health = Wait-EmailHealth -Seconds 25
    if ($health) {
        Write-Host ('任务已重启，健康检查通过：' + ($health | ConvertTo-Json -Compress)) -ForegroundColor Green
        return
    }
    Write-Host '任务已启动，但 25 秒内 /health 未通过。最近日志：' -ForegroundColor Yellow
    Get-ServerLogTail -Lines 30 | ForEach-Object { Write-Host $_ }
    exit 2
}

Test-RequiredFiles
Ensure-UrlAcl
$existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existingTask) {
    Write-Host ('更新已存在任务：{0}' -f $TaskName)
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}
$manualHealth = Get-EmailHealth
if ($manualHealth -and $manualHealth.ok) {
    Write-Host '检测到 40150 已有服务在运行（可能是手动窗口），本次只注册任务，不强制启动。' -ForegroundColor Yellow
    Write-Host '关闭手动窗口后执行 .\install-scheduled-task.ps1 -Restart 让任务接管。' -ForegroundColor Yellow
}

$powershellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Config "{1}"' -f $serverPath, $configPath
$action = New-ScheduledTaskAction -Execute $powershellExe -Argument $arguments -WorkingDirectory $dir
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Principal $principal `
    -Settings $settings `
    -Description 'MDT 邮箱验证码服务（自动启动/崩溃重启）' `
    -Force | Out-Null
Write-Host ('已注册计划任务：{0}（SYSTEM/开机自启/失败每分钟重启）' -f $TaskName)

if (-not $manualHealth) {
    Start-ScheduledTask -TaskName $TaskName
    $health = Wait-EmailHealth -Seconds 30
    if ($health) {
        Write-Host ('服务已启动，健康检查通过：' + ($health | ConvertTo-Json -Compress)) -ForegroundColor Green
    } else {
        Write-Host '任务已启动，但 30 秒内 /health 未通过。最近日志：' -ForegroundColor Yellow
        Get-ServerLogTail -Lines 30 | ForEach-Object { Write-Host $_ }
        exit 2
    }
} else {
    Write-Host '任务已注册；关闭手动窗口后运行 -Restart 即可由任务接管。' -ForegroundColor Yellow
}

Show-TaskStatus
