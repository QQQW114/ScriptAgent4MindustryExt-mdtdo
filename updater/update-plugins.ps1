<#
.SYNOPSIS
    MDT 更新器：严格镜像更新服务器插件脚本（仓库 scripts/** → config.json 的 serverScriptsPath）。

.DESCRIPTION
    流程：版本检查（GitHub API，失败不阻塞）→ 下载仓库 zip → 解压校验 →
          严格镜像对比 → 备份 → 应用（新增/覆盖 + 删除仓库中已不存在的文件）→ 变更报告。
    要求：更新前服务器必须处于关闭状态（检测游戏端口与游戏 java 进程，运行中直接拒绝执行）。
    默认保护目录：data、external-cp、cache（不复制、不备份、不删除）；受保护文件可在 config.json 配置。
    其中 cache 是脚本编译缓存：普通更新不会碰它，需要清缓存请用 update-plugins-clear-cache。
    本脚本不会启动/重启服务器：更新完成后请自行启动服务器验证。

    退出码：0 = 成功/已是最新/预览完成；1 = 失败（目标未被破坏）；2 = 拒绝执行（未停服、配置缺失等）。

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
    离线/测试用：直接使用本地仓库 zip（结构与 GitHub zip 相同，根目录含 scripts/、stats-web/），
    跳过下载与远端版本检查。

.PARAMETER KeepTemp
    保留本次下载解压的临时目录（排障用）。

.EXAMPLE
    .\update-plugins.bat -DryRun
    预览严格镜像会新增/修改/删除哪些文件（服务器运行中也可以预览）。

.EXAMPLE
    .\update-plugins.bat -ProxyPort 7897
    走本机 7897 代理更新（要求服务器已关闭）。

.EXAMPLE
    .\update-plugins.bat -Rollback
    回滚到最近一次更新前的备份。
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

$exitCode = Invoke-MdtUpdater -Kind 'plugins' -UpdaterDir $UpdaterDir -Cli $cli
exit $exitCode
