<#
.SYNOPSIS
    MDT 更新器：先清空服务器脚本编译缓存，再严格镜像更新插件脚本。

.DESCRIPTION
    本脚本 = 清空脚本编译缓存（默认 <serverScriptsPath>\cache，可在 config.json 用 cachePath 覆盖）
            + 严格镜像更新插件（同 update-plugins.ps1）。
    要求：更新前服务器必须处于关闭状态（运行中直接拒绝执行）。
    缓存清空后，服务器下次启动会重新编译全部脚本（首次启动会慢一些），这是预期行为。

    与 update-plugins 的区别：
      * 普通 update-plugins 默认保护 cache\（cache 在 protectedServerDirs 里），不会碰编译缓存；
      * 本脚本会「先」清空缓存再镜像，并且不会因为「版本已是最新」而跳过——即使没有新提交，
        也会强制清理缓存并重新严格镜像一次，适合缓存损坏/需要强制重编译的场景。

    本脚本不会启动/重启服务器：更新完成后请自行启动服务器验证。
    退出码：0 = 成功/预览完成；1 = 失败；2 = 拒绝执行（未停服、配置缺失等）。

.PARAMETER DryRun
    显示将清空的缓存内容与严格镜像会新增/修改/删除的文件清单，不备份、不清缓存、不改目标。

.PARAMETER Force
    忽略「已是最新」判断（本脚本本来就会强制镜像，保留该参数用于兼容与强制重下）。

.PARAMETER Proxy
    下载代理，如 http://127.0.0.1:7897。为空时若设置了环境变量 HTTPS_PROXY 会自动使用。

.PARAMETER ProxyPort
    本机代理端口快捷方式，等价于 -Proxy http://127.0.0.1:<port>。

.PARAMETER Config
    配置文件路径，默认使用本目录下的 config.json。

.PARAMETER NoBackup
    跳过备份（不建议）：更新失败时无法自动恢复（cache 受保护、不参与备份）。

.PARAMETER Rollback
    回滚到最近一次更新前的备份（恢复脚本本身；cache 受保护不参与备份，回滚后由服务器重新编译）。

.PARAMETER RollbackTo
    回滚到指定备份（备份目录名，如 20261018-173000；也可传备份目录完整路径）。

.PARAMETER KeepBackups
    覆盖配置中的备份保留份数（默认 5）。

.PARAMETER Branch
    覆盖仓库分支（默认 main）。

.PARAMETER Repo
    覆盖仓库（默认 QQQW114/ScriptAgent4MindustryExt-mdtdo）。

.PARAMETER SourceZip
    离线/测试用：直接使用本地仓库 zip，跳过下载与远端版本检查。

.PARAMETER KeepTemp
    保留本次下载解压的临时目录（排障用）。

.EXAMPLE
    .\update-plugins-clear-cache.bat
    关服状态下：清空编译缓存 + 严格镜像更新插件脚本。

.EXAMPLE
    .\update-plugins-clear-cache.bat -DryRun
    预览将清空的缓存与将变更的脚本清单（不改任何文件）。

.EXAMPLE
    .\update-plugins-clear-cache.bat -Rollback
    回滚到最近一次更新前（恢复脚本；被清掉的缓存不在备份里，服务器启动时会重新编译）。
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

$exitCode = Invoke-MdtUpdater -Kind 'plugins-clear-cache' -UpdaterDir $UpdaterDir -Cli $cli
exit $exitCode
