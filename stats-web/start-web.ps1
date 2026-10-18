[CmdletBinding()]
param(
    # 留空时读取 web-config.json；若不存在但发现旧版 web-config.ps1，会自动迁移生成 web-config.json
    # 注意：参数变量名不能和内部配置哈希表 $config 同名（PowerShell 变量名不区分大小写，
    # 且参数上的 [string] 类型约束会把它后续赋的值强制转字符串），因此这里用 ConfigFile + Alias。
    [Alias("Config")]
    [string]$ConfigFile = "",
    # 覆盖配置文件里的监听地址（0.0.0.0 需先 -InstallAcl）
    [string]$Bind = "",
    # 覆盖配置文件里的端口（0 = 用配置文件）
    [int]$Port = 0,
    # 覆盖配置文件里的统计 JSON 路径（旧参数，兼容保留）
    [string]$StatsJson = "",
    # 以管理员运行一次，为当前用户注册 URL 保留（之后无需管理员即可 0.0.0.0 监听）
    [switch]$InstallAcl,
    # 生成 PBKDF2 密码哈希（填入 web-config.json 的 admin.passwordHash），生成后退出
    [string]$GenPasswordHash = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ServerDir = Join-Path $Root "server"

# ---------- 加载后端模块（dot-source 共享同一作用域） ----------
$modules = @(
    "portal-core.ps1",
    "portal-http.ps1",
    "portal-store.ps1",
    "portal-console.ps1",
    "portal-logs.ps1",
    "portal-history.ps1",
    "portal-metrics.ps1",
    "portal-api.ps1",
    "portal-server.ps1"
)
foreach ($m in $modules) {
    $p = Join-Path $ServerDir $m
    if (-not (Test-Path -LiteralPath $p)) {
        throw "后端模块缺失：$p"
    }
    . $p
}

# ---------- 密码哈希生成模式 ----------
if ($PSBoundParameters.ContainsKey("GenPasswordHash")) {
    $hash = New-PortalPasswordHash -Password $GenPasswordHash
    Write-Host "PBKDF2 密码哈希（填入 web-config.json 的 admin.passwordHash，并清空 admin.password）：" -ForegroundColor Cyan
    Write-Host $hash -ForegroundColor Green
    exit 0
}

# ---------- 配置解析：-Config / web-config.json / 旧版 web-config.ps1 自动迁移 ----------
$configPath = ""
if (-not [string]::IsNullOrWhiteSpace($ConfigFile)) {
    if ([System.IO.Path]::IsPathRooted($ConfigFile)) { $configPath = [System.IO.Path]::GetFullPath($ConfigFile) }
    else { $configPath = [System.IO.Path]::GetFullPath((Join-Path $Root $ConfigFile)) }
} else {
    $configPath = Join-Path $Root "web-config.json"
}

# 旧部署升级：没有 web-config.json、但有旧版 web-config.ps1 时，自动迁移生成新 JSON
# （旧文件改名 .bak-<时间>；失败自动回退旧配置启动）。只在默认配置路径上迁移，
# 避免 -Config 指向其它文件时误改仓库里的旧配置。
$defaultConfigPath = Join-Path $Root "web-config.json"
$allowMigration = [string]::Equals($configPath, $defaultConfigPath, [System.StringComparison]::OrdinalIgnoreCase)
$migration = Invoke-PortalLegacyConfigMigration -Root $Root -ConfigPath $configPath -AllowMigration $allowMigration

$config = New-PortalConfig -WebRoot $Root -ConfigPath $configPath
$usingLegacyConfig = $false
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    if ($migration.usedLegacyFallback -and $null -ne $migration.values) {
        # 迁移失败（写文件异常等）：直接用旧配置里的值继续启动，不影响生产可用性。
        $legacyValues = $migration.values
        if ($legacyValues.Contains("bind") -and "" -ne [string]$legacyValues.bind) { $config.bind = [string]$legacyValues.bind }
        if ($legacyValues.Contains("port") -and [int]$legacyValues.port -gt 0) { $config.port = [int]$legacyValues.port }
        if ($legacyValues.Contains("statsJson") -and "" -ne [string]$legacyValues.statsJson) { $config.statsJson = [string]$legacyValues.statsJson }
        $usingLegacyConfig = $true
        Write-Host "[警告] 自动迁移未完成，已回退使用旧版 web-config.ps1 的值启动（旧文件保持原样，下次启动会重试迁移）。" -ForegroundColor Yellow
    } else {
        $legacyPath = Join-Path $Root "web-config.ps1"
        if (Test-Path -LiteralPath $legacyPath -PathType Leaf) {
            Write-Host "[提示] 未生成 web-config.json，回退读取旧版 web-config.ps1 启动（下次启动将重试自动迁移）。" -ForegroundColor Yellow
            try {
                . $legacyPath
                if ($WebBind) { $config.bind = [string]$WebBind }
                if ($WebPort -gt 0) { $config.port = [int]$WebPort }
                if ($WebStatsJson) { $config.statsJson = [string]$WebStatsJson }
                $usingLegacyConfig = $true
            } catch {
                Write-Host ("[错误] 旧版 web-config.ps1 无法加载：" + $_.Exception.Message) -ForegroundColor Red
                Write-Host "       请修复该文件，或手动创建 web-config.json（字段参考 docs\web-portal.md §3.3）。" -ForegroundColor Red
                throw
            }
        } else {
            Write-Host "[提示] 未找到配置文件，使用内置默认配置（仅本机 127.0.0.1:8090）。" -ForegroundColor Yellow
        }
    }
} else {
    Write-Host ("配置文件：{0}" -f $configPath) -ForegroundColor DarkGray
    if ($migration.migrated) {
        Write-Host "[升级] 本次启动已由旧版 web-config.ps1 自动迁移生成新配置（旧文件已备份，详见上方日志）。" -ForegroundColor Green
    }
}

# ---------- 命令行覆盖（优先级最高） ----------
if (-not [string]::IsNullOrWhiteSpace($Bind)) { $config.bind = $Bind.Trim() }
if ($Port -gt 0) { $config.port = $Port }
if (-not [string]::IsNullOrWhiteSpace($StatsJson)) { $config.statsJson = $StatsJson }
$config.configPath = if ($usingLegacyConfig) { Join-Path $Root "web-config.ps1 (legacy)" } else { $configPath }

# ---------- 配置初始化（路径解析 / 目录创建 / 数值规范化） ----------
$config = Initialize-PortalConfig -Config $config -WebRoot $Root

# 迁移事件写审计日志（配置文件迁移属于运维动作，便于事后追溯）
if ($migration.migrated) {
    try {
        $backupLeaf = ""
        if ($migration.backupPath) { $backupLeaf = Split-Path -Leaf $migration.backupPath }
        Write-PortalAudit -Config $config -Action "config_migrated" -Actor "start-web.ps1" -ClientIp "" -Detail ([ordered]@{
            from = "web-config.ps1"; backup = $backupLeaf; bind = [string]$config.bind; port = [int]$config.port; statsJson = [string]$config.statsJson
        })
    } catch { }
}

if ($InstallAcl) {
    Install-PortalUrlAcl -Bind $config.bind -Port $config.port
    exit 0
}

# 运行前环境检查提示（缺失不算致命错误，门户仍可启动）。
if (-not (Test-Path -LiteralPath $config.statsJsonPath)) {
    Write-Host ("[警告] 统计 JSON 当前不存在：{0}" -f $config.statsJsonPath) -ForegroundColor Yellow
    Write-Host "       请在游戏内执行 /serverstats status 确认脚本已输出该文件；/api/status 会返回 available=false。" -ForegroundColor Yellow
}
if (-not (Test-Path -LiteralPath $config.eventsJsonlPath)) {
    Write-Host ("[提示] 事件流 JSONL 当前不存在：{0}" -f $config.eventsJsonlPath) -ForegroundColor DarkYellow
    Write-Host "       加载 wayzer/ext/liveEventFeed.kts 后会自动生成；在此之前 /api/events 返回空列表。" -ForegroundColor DarkYellow
}
if (-not (Test-Path -LiteralPath $config.dpDirPath)) {
    Write-Host ("[警告] DP 目录不存在：{0}（审核通过的 DP 将无法落盘）" -f $config.dpDirPath) -ForegroundColor Yellow
}
if (-not (Test-Path -LiteralPath $config.mapsDirPath)) {
    Write-Host ("[警告] 地图目录不存在：{0}（审核通过的地图将无法落盘）" -f $config.mapsDirPath) -ForegroundColor Yellow
}

# ---------- 启动服务 ----------
Start-PortalServer -Config $config
