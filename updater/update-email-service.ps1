<#
.SYNOPSIS
    MDT 更新器：同步邮件服务 email-service（仓库 email-service/** → config.json 的 emailServicePath）。

.DESCRIPTION
    只用于主服 MDT DO 的邮件服务（分服不部署 email-service）。
    流程：版本检查（GitHub API，失败不阻塞）→ 下载仓库 zip → 解压校验 → 对比 →
          备份 → 新增/更新服务文件 → 报告。
    安全默认值（与插件 scripts 的严格镜像不同）：
      * **只新增/更新，不删除**目标目录里仓库没有的本地文件（可设 emailServicePruneDeleted=true 改成严格镜像）；
      * 默认保护 `config.json`（生产密钥/发件配置，绝不能被仓库默认配置覆盖）、`logs\`（运行日志）、
        `dist\`（本地打包产物）：不复制、不备份、不删除；
      * 仓库里没有 config.json（已在 email-service/.gitignore 排除），若出现只会警告、不会复制到生产。
    更新器**不会重启邮件服务**：更新完成后请重启 email-service 进程（关闭原窗口/停止计划任务后重新运行
    email-service\start-email-service.bat），新文件才会生效。

    退出码：0 = 成功/已是最新/预览完成；1 = 失败（目标未被破坏）；2 = 拒绝执行（配置缺失等，目标未改动）。

.PARAMETER DryRun
    只下载、校验并显示将「新增/修改」的文件清单（以及会被保留、不删除的本地文件），不改目标。

.PARAMETER Force
    忽略「已是最新」判断，强制重新下载并同步。

.PARAMETER Proxy
    下载代理，如 http://127.0.0.1:7897。为空时若设置了环境变量 HTTPS_PROXY 会自动使用。

.PARAMETER ProxyPort
    本机代理端口快捷方式，等价于 -Proxy http://127.0.0.1:<port>。

.PARAMETER Config
    配置文件路径，默认使用本目录下的 config.json。

.PARAMETER NoBackup
    跳过备份（不建议）：更新失败时无法自动恢复。

.PARAMETER Rollback
    回滚到最近一次更新前的备份（回滚后请重启邮件服务）。

.PARAMETER RollbackTo
    回滚到指定备份（备份目录名，如 20261018-173000；也可传备份目录完整路径）。

.PARAMETER KeepBackups
    覆盖配置中的备份保留份数（默认 5）。

.PARAMETER Branch
    覆盖仓库分支（默认用 config.json 的 branch，即主服 main）。

.PARAMETER Repo
    覆盖仓库（默认 QQQW114/ScriptAgent4MindustryExt-mdtdo）。

.PARAMETER SourceZip
    离线/测试用：直接使用本地仓库 zip（根目录含 email-service/），跳过下载与远端版本检查。

.PARAMETER KeepTemp
    保留本次下载解压的临时目录（排障用）。

.EXAMPLE
    .\update-email-service.bat -DryRun
    预览 email-service 会新增/修改哪些文件。

.EXAMPLE
    .\update-email-service.bat
    正式更新邮件服务文件（不会动 config.json/logs；更新完重启邮件服务）。

.EXAMPLE
    .\update-email-service.bat -Rollback
    回滚邮件服务到最近一次更新前的备份（回滚后重启邮件服务）。
#>

#Requires -Version 5.1

[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$Force,
    [string]$Proxy = '',
    [int]$ProxyPort = 0,
    [string]$Config = '',
    [switch]$NoBackup,
    [switch]$Rollback,
    [string]$RollbackTo = '',
    [int]$KeepBackups = 0,
    [string]$Branch = '',
    [string]$Repo = '',
    [string]$SourceZip = '',
    [switch]$KeepTemp,
    [switch]$NoRestartEmailService
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$UpdaterDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $UpdaterDir 'UpdateCommon.ps1')

$cli = @{
    DryRun      = [bool]$DryRun
    Force       = [bool]$Force
    Proxy       = $Proxy
    ProxyPort   = $ProxyPort
    Config      = $Config
    NoBackup    = [bool]$NoBackup
    Rollback    = [bool]$Rollback
    RollbackTo  = $RollbackTo
    KeepBackups = $KeepBackups
    Branch      = $Branch
    Repo        = $Repo
    SourceZip   = $SourceZip
    KeepTemp    = [bool]$KeepTemp
    NoHotReload = $true
    HotReload   = ''
}

$exitCode = Invoke-MdtUpdater -Kind 'email' -UpdaterDir $UpdaterDir -Cli $cli
if ($exitCode -eq 0 -and -not $DryRun -and -not $NoRestartEmailService) {
    try {
        $restartConfig = Get-EffectiveConfig -UpdaterDir $UpdaterDir -Cli $cli
        $taskName = [string]$restartConfig.EmailServiceTaskName
        if ([string]::IsNullOrWhiteSpace($taskName)) { $taskName = 'MDT Email Service' }
        $restartEnabled = $restartConfig.RestartEmailServiceAfterUpdate
        if ($null -eq $restartEnabled) { $restartEnabled = $true }
        if ([bool]$restartEnabled) {
            Write-Host ('重启邮箱服务计划任务：{0}' -f $taskName) -ForegroundColor Cyan
            $restart = Restart-MdtEmailService -TaskName $taskName
            Write-Host ('  ' + $restart.Message) -ForegroundColor $(if ($restart.Ok) { 'Green' } else { 'Yellow' })
        } else {
            Write-Host '配置 restartEmailServiceAfterUpdate=false，跳过重启；新文件将在下次服务重启后生效。' -ForegroundColor Yellow
        }
    } catch {
        Write-Host ('重启邮箱服务时提示：{0}；新文件已落盘，请手动重启 email-service。' -f $_.Exception.Message) -ForegroundColor Yellow
    }
}
exit $exitCode
