<#
.SYNOPSIS
    MDT 更新器：严格镜像更新服务器门户 Web（仓库 stats-web/** → config.json 的 statsWebPath）。

.DESCRIPTION
    流程：版本检查（GitHub API，失败不阻塞）→ 下载仓库 zip → 解压校验 →
          严格镜像对比 → 备份 → 应用（新增/覆盖 + 删除仓库中已不存在的文件）→ 变更报告。
    默认保护目录：web-data（不复制、不备份、不删除）；默认保护文件：web-config.json、web-config.ps1
    （仓库里的是默认配置，绝不能覆盖生产配置）。仓库里没有 web-data，因此不会带入运行数据。
    本脚本不会自动重启 Web：更新完成后必须重启 Web 进程（关闭 start-web 窗口后重新运行 start-web.bat）。
    更新 Web 不需要关闭游戏服务器。

    退出码：0 = 成功/已是最新/预览完成；1 = 失败（目标未被破坏）；2 = 拒绝执行（配置缺失等）。

.PARAMETER DryRun
    只下载、校验并显示将「新增/修改/删除」的文件清单，不备份、不改目标、不写版本标记。

.PARAMETER Force
    忽略「已是最新」判断，强制重新下载并执行严格镜像。

.PARAMETER Proxy
    下载代理，如 http://127.0.0.1:7897。为空时若设置了环境变量 HTTPS_PROXY 会自动使用。

.PARAMETER ProxyPort
    本机代理端口快捷方式，等价于 -Proxy http://127.0.0.1:<port>。

.PARAMETER Config
    配置文件路径，默认使用本目录下的 config.json。

.PARAMETER NoBackup
    跳过备份（不建议）：更新失败时无法自动恢复。

.PARAMETER Rollback
    回滚到最近一次更新前的备份。

.PARAMETER RollbackTo
    回滚到指定备份（备份目录名，如 20261018-173000；也可传备份目录完整路径）。

.PARAMETER KeepBackups
    覆盖配置中的备份保留份数（默认 5）。

.PARAMETER Branch
    覆盖仓库分支（默认 main）。

.PARAMETER Repo
    覆盖仓库（默认 QQQW114/ScriptAgent4MindustryExt-mdtdo）。

.PARAMETER SourceZip
    离线/测试用：直接使用本地仓库 zip（根目录含 stats-web/），跳过下载与远端版本检查。

.PARAMETER KeepTemp
    保留本次下载解压的临时目录（排障用）。

.EXAMPLE
    .\update-web.bat -DryRun
    预览 Web 严格镜像会新增/修改/删除哪些文件（不影响运行中的 Web）。

.EXAMPLE
    .\update-web.bat -ProxyPort 7897
    走本机 7897 代理更新 Web（更新后请重启 Web 进程）。

.EXAMPLE
    .\update-web.bat -Rollback
    回滚 Web 到最近一次更新前的备份（回滚后请重启 Web 进程）。
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
    [switch]$KeepTemp
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
}

$exitCode = Invoke-MdtUpdater -Kind 'web' -UpdaterDir $UpdaterDir -Cli $cli
exit $exitCode
