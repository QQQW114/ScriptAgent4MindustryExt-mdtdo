# MDT 邮箱服务 —— 在服务器本体安装开机自启（Windows 任务计划程序）
# 用法（管理员 PowerShell）：
#   .\install-scheduled-task.ps1            # 安装并启动
#   .\install-scheduled-task.ps1 -Remove    # 停止并删除任务
param(
    [string]$TaskName = 'MDT Email Service',
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$dir = $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $dir 'server.ps1'))) { throw "server.ps1 不存在：$dir" }
if (-not (Test-Path -LiteralPath (Join-Path $dir 'config.json'))) { throw "config.json 不存在：$dir（请先填写配置）" }

if ($Remove) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    schtasks.exe /Delete /TN $TaskName /F | Out-Null
    Write-Host "已停止并删除任务：$TaskName"
    return
}

$exe = 'powershell.exe'
$argsString = '-NoProfile -ExecutionPolicy Bypass -File "{0}\server.ps1" -Config "{0}\config.json"' -f $dir

$create = schtasks.exe /Create /TN $TaskName /TR ('{0} {1}' -f $exe, $argsString) /SC ONSTART /RU SYSTEM /RL HIGHEST /F 2>&1
if ($LASTEXITCODE -ne 0) { throw ("创建任务失败：" + ($create -join ' ')) }
Write-Host ("已创建任务：{0}" -f $TaskName)

Start-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) { Write-Host ("任务状态：{0}" -f $task.State) }

try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:40150/health' -TimeoutSec 5
    Write-Host ("服务健康检查通过：{0}" -f ($health | ConvertTo-Json -Compress))
} catch {
    Write-Host ("服务尚未就绪，请稍后手动执行：Invoke-RestMethod http://127.0.0.1:40150/health ；若失败看 logs 目录") -ForegroundColor Yellow
}
Write-Host '提示：若提示 HttpListener 拒绝访问，管理员执行一次：netsh http add urlacl url=http://127.0.0.1:40150/ user=Everyone'
