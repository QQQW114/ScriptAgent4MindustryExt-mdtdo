<#
.SYNOPSIS
    MDT 分服（MDC DO）快速同步：只同步插件 scripts（默认分服分支 mdc-do），不部署 email-service/账号系统。

.DESCRIPTION
    两个服的分支约定（由 Lead 统一维护分支，本脚本只按分支名下载 zip，不切/不改任何 git 分支）：
      * 主服 MDT DO：仓库分支 main（含账号/邮箱验证系统），用 update-mainserver.bat 同步。
      * 分服 MDC DO：仓库分支 mdc-do（当前 main 基线，保持原有、不含账号系统）——本脚本默认分支。
        如 Lead 后续调整分服分支名，可在这里改默认值，或每次运行时用 -Branch <分支名> 覆盖。

    行为与 update-plugins 相同：仓库 scripts/** → serverScriptsPath 严格镜像（新增/覆盖 +
    删除仓库中已不存在的文件），默认保护 data\、external-cp\、cache\（不复制、不备份、不删除）；
    服务器运行中且 console socket 可用时逐个 sa unload/load 热重载，否则按停服流程更新。
    本脚本**不同步** email-service、不同步 stats-web、不部署账号/邮箱系统相关服务。

    停服要求：含 .kt 库/模块改动时必须停服（工具会在改动文件前拒绝并退出码 2）。
    退出码：0 = 成功/已是最新/预览完成；1 = 失败（目标未被破坏）；2 = 拒绝执行（目标未改动）；
    3 = 文件已更新但运行中热重载失败/超时。

.PARAMETER DryRun
    只下载、校验并显示将「新增/修改/删除」的文件清单与热重载计划，不备份、不改目标、不发 sa 命令。

.PARAMETER Branch
    覆盖同步使用的仓库分支（默认 mdc-do = 分服分支）。一般不要改成 main（会带入账号/邮箱系统）。

.PARAMETER NoHotReload
    关闭运行中热重载（等同 -HotReload off）：服务器运行中时按 requireServerStopped 处理（默认拒绝，退出码 2）。

.PARAMETER HotReload
    覆盖 config.json 的 hotReloadMode：socket（默认）| auto | off。

.PARAMETER Force
    忽略「已是最新」判断，强制重新下载并执行严格镜像。

.PARAMETER Proxy
    下载代理，如 http://127.0.0.1:7897。为空时若设置了环境变量 HTTPS_PROXY 会自动使用。

.PARAMETER ProxyPort
    本机代理端口快捷方式，等价于 -Proxy http://127.0.0.1:<port>。

.PARAMETER Config
    配置文件路径，默认使用本目录下的 config.json（分服机器上同样要填 serverScriptsPath/backupPath）。

.PARAMETER NoBackup
    跳过备份（不建议）：更新失败时无法自动恢复。

.PARAMETER Rollback
    回滚到最近一次更新前的备份。

.PARAMETER RollbackTo
    回滚到指定备份（备份目录名，如 20261018-173000；也可传备份目录完整路径）。

.PARAMETER KeepBackups
    覆盖配置中的备份保留份数（默认 5）。

.PARAMETER Repo
    覆盖仓库（默认 QQQW114/ScriptAgent4MindustryExt-mdtdo）。

.PARAMETER SourceZip
    离线/测试用：直接使用本地仓库 zip（根目录含 scripts/），跳过下载与远端版本检查。

.PARAMETER KeepTemp
    保留本次下载解压的临时目录（排障用）。

.EXAMPLE
    .\update-subserver.bat -DryRun
    预览分服插件 scripts 会新增/修改/删除哪些文件（运行中也可以安全预览）。

.EXAMPLE
    .\update-subserver.bat
    从 mdc-do 分支正式同步分服插件 scripts（运行中经 socket 逐个热重载）。

.EXAMPLE
    .\update-subserver.bat -Branch mdc-do -ProxyPort 7897
    显式指定分服分支并走本机 7897 代理。

.EXAMPLE
    .\update-subserver.bat -Rollback
    回滚分服插件 scripts 到最近一次更新前的备份。
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
    [switch]$NoHotReload,
    [string]$HotReload = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$UpdaterDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $UpdaterDir 'UpdateCommon.ps1')

# 分服分支：默认 mdc-do（可用 -Branch 覆盖）
$subBranch = $Branch
if ([string]::IsNullOrWhiteSpace($subBranch)) { $subBranch = 'mdc-do' }

Write-Host ('=' * 78) -ForegroundColor Cyan
Write-Host 'MDT 分服（MDC DO）快速同步：只同步插件 scripts' -ForegroundColor Cyan
Write-Host ('  分支：{0}（分服应使用不含账号系统的分支）{1}' -f $subBranch, $(if ($DryRun) { '    [DryRun 预览]' } else { '' }))
Write-Host '  不部署 email-service / 账号系统；严格镜像，保护 data/external-cp/cache' -ForegroundColor DarkGray
Write-Host ('=' * 78) -ForegroundColor Cyan

if ($subBranch -eq 'main' -or $subBranch -like 'release/*' -or $subBranch -like 'feat/account*') {
    Write-Host ''
    Write-Host ('警告：分服通常应使用 mdc-do 分支（保持原基线、不含账号/邮箱系统），当前指定的是 {0}。' -f $subBranch) -ForegroundColor Yellow
    Write-Host '      如果这是有意为之（例如临时演练），请继续；否则请去掉 -Branch 参数（默认 mdc-do）。' -ForegroundColor Yellow
}

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
    Branch      = $subBranch
    Repo        = $Repo
    SourceZip   = $SourceZip
    KeepTemp    = [bool]$KeepTemp
    NoHotReload = [bool]$NoHotReload
    HotReload   = $HotReload
}

$exitCode = [int](Invoke-MdtUpdater -Kind 'plugins' -UpdaterDir $UpdaterDir -Cli $cli)
Write-Host ''
Write-Host ('分服快速同步结束：退出码 {0}。' -f $exitCode) -ForegroundColor $(if ($exitCode -eq 0) { 'Green' } else { 'Yellow' })
exit $exitCode
