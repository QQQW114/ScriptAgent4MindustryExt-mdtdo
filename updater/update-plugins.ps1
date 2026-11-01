<#
.SYNOPSIS
    MDT 更新器：严格镜像更新服务器插件脚本（仓库 scripts/** → config.json 的 serverScriptsPath）。

.DESCRIPTION
    流程：版本检查（GitHub API，失败不阻塞）→ 下载仓库 zip → 解压校验 →
          严格镜像对比 → 备份 → 应用（新增/覆盖 + 删除仓库中已不存在的文件）→ 变更报告。
    停服要求与运行中热更新（console socket + 脚本加载日志）：
      * 服务器未运行：按停服流程更新，退出码 0，脚本在下次启动服务器时加载；
      * 服务器运行中且热重载可用（console socket 可连接 + 脚本加载日志存在）：
        分两阶段逐个热重载，并尾随 <serverScriptsPath>\..\logs\script-load\current.log 确认
        每个脚本（含级联依赖）完成：
          ① 镜像前：先对被删除（removed）的脚本逐个发 sa unload（此时源文件还在）；
             任一失败/超时 → 中止，退出码 2，目标目录零改动；
          ② 严格镜像（备份 → 写入/覆盖 → 删除文件）；
          ③ 镜像后：若有 added 或 removed，先发一次 sa scan（发现新脚本 + 清理已删除脚本的注册表，等 1.5 秒；两者都有也只发一次），再对 changed → added 逐个发 sa load；
             最后 sa listFailed 收尾；失败/超时/listFailed 非空 → 退出码 3（文件已更新，需 sa load 或重启服务器）；
        注意：运行中删除脚本必须「先 unload 再删文件」；反过来（文件已删再 unload）会让 SA 事务卡住 console socket。
      * 服务器运行中但热重载不可用/关闭：按 config.json 的 requireServerStopped 回退
        （true=拒绝执行退出码 2；false=旧行为在线更新，不推荐）。
    默认保护目录：data、external-cp、cache（不复制、不备份、不删除）；受保护文件可在 config.json 配置。
    其中 cache 是脚本编译缓存：普通更新不会碰它，需要清缓存请用 update-plugins-clear-cache。
    本脚本不会启动/重启服务器。

    退出码：0 = 成功/已是最新/预览完成；1 = 失败（目标未被破坏）；2 = 拒绝执行（未停服、含 .kt/非 .kts 变更、
    console socket 或脚本加载日志不可用、镜像前 unload 失败等，目标未改动）；3 = 文件已更新但运行中热重载
    失败/超时（可 sa load 或重启恢复）。

.PARAMETER DryRun
    只下载、校验并显示将「新增/修改/删除」的文件清单与运行中热重载计划（含「将逐个执行哪些 sa 命令 / 将拒绝」），
    不备份、不改目标、不发任何 sa 命令、不写版本标记。

.PARAMETER NoHotReload
    关闭运行中热重载（等同 -HotReload off）：服务器运行中时按 requireServerStopped 处理（默认拒绝，退出码 2）。
    首次部署 console socket 或只想停服更新时使用。

.PARAMETER HotReload
    覆盖 config.json 的 hotReloadMode：socket（默认，socket 可用则逐个热重载）| auto（schema 同 socket，
    但不可用时按 requireServerStopped 回退）| off（等同 -NoHotReload）。

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
    .\update-plugins.bat -NoHotReload
    关闭运行中热重载（首次部署 console socket、或服务器运行中想按停服规则拒绝时使用；服务器运行中会退出码 2）。

.EXAMPLE
    .\update-plugins.bat -HotReload off
    同上（-HotReload 支持 socket|auto|off）。

.EXAMPLE
    .\update-plugins.bat
    服务器运行中时：自动严格镜像 + 通过 console socket 逐个热重载变更脚本（需 server.properties 已开 socketInput）。

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
    [switch]$KeepTemp,
    [switch]$NoHotReload,
    [string]$HotReload = ''
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
    NoHotReload = [bool]$NoHotReload
    HotReload   = $HotReload
}

$exitCode = Invoke-MdtUpdater -Kind 'plugins' -UpdaterDir $UpdaterDir -Cli $cli
exit $exitCode
