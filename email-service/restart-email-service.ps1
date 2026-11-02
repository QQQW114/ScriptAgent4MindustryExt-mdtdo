# 重启 MDT 邮箱服务（计划任务方式）
# 用法（管理员 PowerShell）：
#   .\restart-email-service.ps1              # 直接重启任务
#   .\restart-email-service.ps1 -IfUnhealthy # 仅 /health 不通过时才重启
# 更新器在同步 email-service 文件后也会调用本脚本，让新代码立即生效。
param(
    [string]$TaskName = 'MDT Email Service',
    [switch]$IfUnhealthy,
    [int]$WaitSeconds = 25
)

$ErrorActionPreference = 'Stop'
$healthUrl = 'http://127.0.0.1:40150/health'

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

if ($IfUnhealthy) {
    $health = Get-EmailHealth
    if ($health) {
        Write-Host ('邮箱服务当前健康，无需重启：' + ($health | ConvertTo-Json -Compress))
        exit 0
    }
}

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if (-not $task) {
    Write-Host ('未安装计划任务：{0}。请先在 email-service 目录以管理员运行 install-scheduled-task.ps1。' -f $TaskName) -ForegroundColor Yellow
    exit 2
}
if (-not (Test-IsAdmin)) {
    Write-Host '重启计划任务需要管理员权限。' -ForegroundColor Yellow
    exit 2
}

Write-Host ('停止任务：{0}' -f $TaskName)
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Write-Host ('启动任务：{0}' -f $TaskName)
Start-ScheduledTask -TaskName $TaskName
$health = Wait-EmailHealth -Seconds $WaitSeconds
if ($health) {
    Write-Host ('邮箱服务已重启，健康检查通过：' + ($health | ConvertTo-Json -Compress)) -ForegroundColor Green
    exit 0
}
Write-Host ('邮箱服务已启动，但 {0} 秒内 /health 未通过；请查看 email-service\logs。' -f $WaitSeconds) -ForegroundColor Yellow
exit 2
