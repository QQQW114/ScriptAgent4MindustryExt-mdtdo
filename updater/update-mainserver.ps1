<#
.SYNOPSIS
    MDT 主服（MDT DO）快速同步：一次运行 = 插件 scripts（主服分支，严格镜像 + 可选运行中热重载）
    + email-service（只新增/更新，保护生产 config.json 与 logs）。

.DESCRIPTION
    两个服的分支约定（由 Lead 统一维护分支，本脚本只按分支名下载 zip，不切/不改任何 git 分支）：
      * 主服 MDT DO：仓库分支 main（含账号/邮箱验证系统；本脚本默认分支）。
      * 分服 MDC DO：仓库分支 mdc-do（保持原基线、不含账号系统），用 update-subserver.bat 同步。

    阶段 1/2：插件 scripts（仓库 scripts/** → config.json 的 serverScriptsPath）
      - 严格镜像：新增/覆盖 + 删除仓库中已不存在的文件；
      - 默认保护 data\、external-cp\、cache\（不复制、不备份、不删除）；
      - 服务器运行中且 socket 可用时逐个 sa unload/load 热重载（复用 updater 现有机制，
        见 README §10）；服务器未运行则按停服流程更新，下次启动生效；
      - 含 .kt 库/模块改动时会在改动文件前拒绝（退出码 2），必须停服更新。
    阶段 2/2：email-service（仓库 email-service/** → config.json 的 emailServicePath）
      - 默认只新增/更新服务文件，不删除目标目录里的本地文件（可在 config.json 设
        emailServicePruneDeleted=true 改成严格镜像）；
      - 默认保护 config.json（生产密钥/发件配置）与 logs\、dist\：不复制、不备份、不删除；
      - 更新器不会重启邮件服务：更新完请重启 email-service 进程（见输出提示）。
    两阶段各自备份（<backupPath>\plugins 与 <backupPath>\email）并各自写版本标记；
    任何一个阶段失败都会以非 0 退出码结束（见下面「退出码」），不会静默忽略。

    退出码（两阶段聚合）：
      0 = 全部成功 / 已是最新 / DryRun 预览完成；
      1 = 有阶段失败（下载/校验/备份/复制等）；2 = 有阶段拒绝执行（未改动或目标未变，含配置缺失、
          服务器运行中且热重载不可用、含 .kt 变更等）；3 = 文件已更新但运行中热重载失败/超时。
      聚合优先级 1 > 2 > 3；阶段 1 失败（1/2）时会跳过阶段 2（避免脚本没更新好就继续改生产）。
      说明：-Rollback 只回滚阶段 1 的插件 scripts；email-service 回滚请用 update-email-service.bat -Rollback。

.PARAMETER DryRun
    只下载、校验并显示两个阶段将「新增/修改/删除」的文件清单与热重载计划，不备份、不改目标、
    不发任何 sa 命令、不写版本标记。建议每次正式更新前先跑一遍。

.PARAMETER NoEmailService
    只同步插件 scripts，跳过 email-service 阶段（分服/不需要邮件服务时用）。

.PARAMETER NoScripts
    只同步 email-service，跳过插件 scripts 阶段。

.PARAMETER NoHotReload
    关闭运行中热重载（等同 -HotReload off）：服务器运行中时按 requireServerStopped 处理
    （默认拒绝，退出码 2）；只想停服更新时用。

.PARAMETER HotReload
    覆盖 config.json 的 hotReloadMode：socket（默认）| auto | off。

.PARAMETER Branch
    覆盖同步使用的仓库分支（默认 main = 主服分支；一般不要改）。

.PARAMETER Force
    忽略「已是最新」判断，强制重新下载并执行镜像。

.PARAMETER Proxy
    下载代理，如 http://127.0.0.1:7897。为空时若设置了环境变量 HTTPS_PROXY 会自动使用。

.PARAMETER ProxyPort
    本机代理端口快捷方式，等价于 -Proxy http://127.0.0.1:<port>。

.PARAMETER Config
    配置文件路径，默认使用本目录下的 config.json。

.PARAMETER NoBackup
    跳过备份（不建议）：更新失败时无法自动恢复。

.PARAMETER Rollback
    回滚插件 scripts 到最近一次更新前的备份（email-service 不回滚）。

.PARAMETER RollbackTo
    回滚插件 scripts 到指定备份（备份目录名，如 20261018-173000；也可传备份目录完整路径）。

.PARAMETER KeepBackups
    覆盖配置中的备份保留份数（默认 5）。

.PARAMETER Repo
    覆盖仓库（默认 QQQW114/ScriptAgent4MindustryExt-mdtdo）。

.PARAMETER SourceZip
    离线/测试用：直接使用本地仓库 zip（根目录含 scripts/、email-service/），跳过下载与远端版本检查。

.PARAMETER KeepTemp
    保留本次下载解压的临时目录（排障用）。

.EXAMPLE
    .\update-mainserver.bat -DryRun
    预览主服两个阶段会新增/修改/删除哪些文件（运行中也可以安全预览）。

.EXAMPLE
    .\update-mainserver.bat
    正式同步：插件 scripts（main 分支，运行中经 socket 逐个热重载）+ email-service（保护 config.json/logs）。

.EXAMPLE
    .\update-mainserver.bat -NoEmailService
    只同步插件 scripts（不同步邮件服务）。

.EXAMPLE
    .\update-mainserver.bat -NoScripts
    只同步 email-service（不同步插件，不需要停游戏服）。

.EXAMPLE
    .\update-mainserver.bat -ProxyPort 7897
    走本机 7897 代理更新两个阶段。
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
    [string]$HotReload = '',
    [switch]$NoEmailService,
    [switch]$NoScripts
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$UpdaterDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $UpdaterDir 'UpdateCommon.ps1')

# 主服分支：默认 main（可用 -Branch 覆盖）
$mainBranch = $Branch
if ([string]::IsNullOrWhiteSpace($mainBranch)) { $mainBranch = 'main' }

$isRollback = ([bool]$Rollback -or -not [string]::IsNullOrWhiteSpace($RollbackTo))
$runScripts = -not [bool]$NoScripts
$runEmail = -not [bool]$NoEmailService

if ($isRollback) {
    # 回滚只针对插件 scripts：email-service 的回滚用独立入口，避免「还没有邮件备份」把整次回滚判失败。
    if (-not $runScripts) {
        Write-Host '错误：-NoScripts 与 -Rollback 不能组合；回滚邮件服务请运行 update-email-service.bat -Rollback。' -ForegroundColor Red
        exit 2
    }
    if ($runEmail) {
        $runEmail = $false
        Write-Host '提示：本次是回滚模式：只回滚插件 scripts；email-service 不回滚（如需要请单独运行 update-email-service.bat -Rollback -RollbackTo <备份>）。' -ForegroundColor Yellow
    }
}
if (-not $runScripts -and -not $runEmail) {
    Write-Host '错误：-NoScripts 与 -NoEmailService 同时指定，没有可执行的操作。' -ForegroundColor Red
    exit 2
}

Write-Host ('=' * 78) -ForegroundColor Cyan
Write-Host 'MDT 主服（MDT DO）快速同步' -ForegroundColor Cyan
Write-Host ('  分支：{0}    插件 scripts：{1}    email-service：{2}{3}' -f
    $mainBranch,
    $(if ($runScripts) { '同步' } else { '跳过' }),
    $(if ($runEmail) { '同步' } else { '跳过' }),
    $(if ($DryRun) { '    [DryRun 预览]' } else { '' }))
Write-Host '  阶段 1 = 插件 scripts（严格镜像，保护 data/external-cp/cache，可运行中热重载）' -ForegroundColor DarkGray
Write-Host '  阶段 2 = email-service（只新增/更新，保护 config.json 与 logs/dist）' -ForegroundColor DarkGray
Write-Host ('=' * 78) -ForegroundColor Cyan

function New-PhaseCli {
    param([Parameter(Mandatory = $true)][string]$PhaseBranch)
    return @{
        DryRun      = [bool]$DryRun
        Force       = [bool]$Force
        Proxy       = $Proxy
        ProxyPort   = $ProxyPort
        Config      = $Config
        NoBackup    = [bool]$NoBackup
        Rollback    = [bool]$Rollback
        RollbackTo  = $RollbackTo
        KeepBackups = $KeepBackups
        Branch      = $PhaseBranch
        Repo        = $Repo
        SourceZip   = $SourceZip
        KeepTemp    = [bool]$KeepTemp
        NoHotReload = [bool]$NoHotReload
        HotReload   = $HotReload
    }
}

$exitCodes = New-Object System.Collections.Generic.List[int]
$scriptExit = 0

# 预检：email-service 阶段已启用时，先确认 config.json 配置了 emailServicePath 且目录存在，
# 避免「插件已经更新完、email 阶段才发现没配置」这种半更新状态。
if ($runEmail -and -not $isRollback) {
    try {
        $preflightConfig = Get-EffectiveConfig -UpdaterDir $UpdaterDir -Cli (New-PhaseCli -PhaseBranch $mainBranch)
        $emailPath = [string]$preflightConfig.EmailServicePath
        if ([string]::IsNullOrWhiteSpace($emailPath)) {
            Write-Host '错误：config.json 未配置 emailServicePath（邮件服务目录），已在改动任何文件前中止。' -ForegroundColor Red
            Write-Host '      如果本次只想更新插件，请加 -NoEmailService；否则请编辑 config.json 填写 emailServicePath。' -ForegroundColor Red
            exit 2
        }
        if (-not (Test-Path -LiteralPath $emailPath -PathType Container)) {
            Write-Host ('错误：emailServicePath 指向的目录不存在：{0}（已在改动任何文件前中止）' -f $emailPath) -ForegroundColor Red
            Write-Host '      请检查 config.json 的 emailServicePath，或加 -NoEmailService 只更新插件。' -ForegroundColor Red
            exit 2
        }
    } catch {
        Write-Host ('提示：email-service 预检未能完成（正式阶段会再校验）：{0}' -f $_.Exception.Message) -ForegroundColor Yellow
    }
}

if ($runScripts) {
    Write-Host ''
    Write-Host '---------- 阶段 1/2：插件 scripts（严格镜像 + 可选运行中热重载）----------' -ForegroundColor Cyan
    $cli = New-PhaseCli -PhaseBranch $mainBranch
    $scriptExit = [int](Invoke-MdtUpdater -Kind 'plugins' -UpdaterDir $UpdaterDir -Cli $cli)
    $exitCodes.Add($scriptExit)
    Write-Host ''
    Write-Host ('阶段 1/2 结果：插件 scripts 退出码 {0}' -f $scriptExit) -ForegroundColor $(if ($scriptExit -eq 0) { 'Green' } else { 'Yellow' })
}

if ($runEmail) {
    if ($runScripts -and $scriptExit -ne 0 -and $scriptExit -ne 3) {
        Write-Host ''
        Write-Host ('阶段 1/2 未成功（退出码 {0}），为安全起见跳过 email-service 阶段（脚本未更新成功时不要继续改生产）。' -f $scriptExit) -ForegroundColor Yellow
    } else {
        Write-Host ''
        Write-Host '---------- 阶段 2/2：email-service（只新增/更新，保护 config.json 与 logs/dist）----------' -ForegroundColor Cyan
        $cli = New-PhaseCli -PhaseBranch $mainBranch
        $emailExit = [int](Invoke-MdtUpdater -Kind 'email' -UpdaterDir $UpdaterDir -Cli $cli)
        $exitCodes.Add($emailExit)
        Write-Host ''
        Write-Host ('阶段 2/2 结果：email-service 退出码 {0}' -f $emailExit) -ForegroundColor $(if ($emailExit -eq 0) { 'Green' } else { 'Yellow' })
    }
}

# 聚合退出码：1（失败）> 2（拒绝执行）> 3（文件已更新但热重载失败）> 0
$finalExit = 0
foreach ($code in $exitCodes) { if ($code -eq 1) { $finalExit = 1; break } }
if ($finalExit -eq 0) { foreach ($code in $exitCodes) { if ($code -eq 2) { $finalExit = 2; break } } }
if ($finalExit -eq 0) { foreach ($code in $exitCodes) { if ($code -eq 3) { $finalExit = 3; break } } }
$verdict = @{ 0 = '全部成功'; 1 = '有阶段失败'; 2 = '有阶段拒绝执行/未改动'; 3 = '文件已更新但热重载失败' }[$finalExit]

Write-Host ''
Write-Host ('主服快速同步结束：退出码 {0}（{1}）。' -f $finalExit, $verdict) -ForegroundColor $(if ($finalExit -eq 0) { 'Green' } else { 'Yellow' })
if ($DryRun) { Write-Host 'DryRun 未做任何修改。' }
exit $finalExit
