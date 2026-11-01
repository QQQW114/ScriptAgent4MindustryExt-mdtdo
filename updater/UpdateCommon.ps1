# ============================================================================
# MDT 更新器公共模块（由三个入口脚本 dot-source 使用）
# 要求：Windows PowerShell 5.1+；本文件需保存为 UTF-8 with BOM。
#
# 入口脚本（update-plugins.ps1 / update-web.ps1 /
# update-plugins-clear-cache.ps1）只负责解析参数，然后调用：
#   Invoke-MdtUpdater -Kind <plugins|web|plugins-clear-cache> -UpdaterDir <本目录> -Cli <参数表>
# 返回值即进程退出码（0 成功 / 1 失败 / 2 拒绝执行 / 3 文件已更新但运行中热重载失败或超时）。
#
# 运行中热更新：update-plugins 在服务器运行中且 console socket（server.properties 的
# socketInput 配置）可用时：镜像前先逐个 sa unload 将被删除的脚本（文件还在时），
# 再严格镜像，镜像后先 sa scan 再逐个 sa load changed/added，并尾随
# <serverScriptsPath>\..\logs\script-load\current.log 确认每个脚本（含级联依赖）完成；
# 失败/超时返回退出码 3；镜像前 unload 失败返回退出码 2（目标零改动）。
# ============================================================================

$script:UpdaterVersion = '1.1.0'
$script:LogWriter = $null
$script:LogPath = ''
$script:TempDir = ''
$script:LockPath = ''

# ----------------------------------------------------------------------------
# 控制台 / 日志
# ----------------------------------------------------------------------------

function Set-ConsoleUtf8 {
    try {
        [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
        [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
        $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    } catch {
        # 老旧控制台不支持时忽略
    }
}

# 从 PowerShell 7 的会话里启动 Windows PowerShell 5.1 时，子进程会继承 pwsh 的 PSModulePath
# （其中包含 PowerShell 7 的模块目录）。Windows PowerShell 5.1 会优先从这些目录加载不兼容的模块，
# 表现为 Get-FileHash 不存在、Get-CimInstance 报 "Argument types do not match" 等。这里修正成
# Windows PowerShell 的默认模块路径，正常从 cmd/资源管理器双击启动时不会触发。
function Repair-PSModulePath {
    if ($PSVersionTable.PSVersion.Major -gt 5) { return }
    $current = [string]$env:PSModulePath
    if ([string]::IsNullOrWhiteSpace($current)) { return }
    if ($current -notmatch '(?i)PowerShell\\7\\|WindowsApps\\|\\PowerShell\\Modules') { return }
    $paths = New-Object System.Collections.Generic.List[string]
    $defaults = @(
        (Join-Path $env:USERPROFILE 'Documents\WindowsPowerShell\Modules'),
        'C:\Program Files\WindowsPowerShell\Modules',
        'C:\WINDOWS\system32\WindowsPowerShell\v1.0\Modules'
    )
    foreach ($candidate in $defaults) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        if ((Test-Path -LiteralPath $candidate) -and -not $paths.Contains($candidate)) { $paths.Add($candidate) | Out-Null }
    }
    foreach ($part in ($current -split ';')) {
        if ([string]::IsNullOrWhiteSpace($part)) { continue }
        if ($part -match '(?i)PowerShell\\7\\|WindowsApps\\|\\PowerShell\\Modules') { continue }
        if (-not $paths.Contains($part)) { $paths.Add($part) | Out-Null }
    }
    $env:PSModulePath = ($paths -join ';')
    Write-Host '已修正 PSModulePath：检测到从 PowerShell 7 会话继承的模块路径（会破坏 Windows PowerShell 5.1 的模块加载）。' -ForegroundColor Yellow
}

function New-Directory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Force -Path $Path | Out-Null
    }
}

function Open-Logger {
    param([Parameter(Mandatory = $true)][string]$Path)
    New-Directory -Path (Split-Path -Parent $Path)
    # UTF-8 with BOM：Windows 记事本与 PowerShell 5.1 的 Get-Content 都能正确识别中文
    $encoding = New-Object System.Text.UTF8Encoding($true)
    $script:LogWriter = New-Object System.IO.StreamWriter($Path, $false, $encoding)
    $script:LogWriter.AutoFlush = $true
    $script:LogPath = $Path
}

function Close-Logger {
    if ($null -ne $script:LogWriter) {
        try { $script:LogWriter.Flush() } catch { }
        try { $script:LogWriter.Dispose() } catch { }
        $script:LogWriter = $null
    }
}

function Get-LogTimestamp {
    return (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
}

function Write-Log {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Message,
        [ValidateSet('INFO', 'STEP', 'OK', 'WARN', 'ERROR')]
        [string]$Level = 'INFO'
    )
    $line = '{0} [{1}] {2}' -f (Get-LogTimestamp), $Level, $Message
    switch ($Level) {
        'STEP' { Write-Host $line -ForegroundColor Cyan }
        'OK' { Write-Host $line -ForegroundColor Green }
        'WARN' { Write-Host $line -ForegroundColor Yellow }
        'ERROR' { Write-Host $line -ForegroundColor Red }
        default { Write-Host $line }
    }
    if ($null -ne $script:LogWriter) {
        $script:LogWriter.WriteLine($line)
    }
}

function Write-Detail {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Message)
    if ($null -ne $script:LogWriter) {
        $script:LogWriter.WriteLine(('{0} [DETAIL] {1}' -f (Get-LogTimestamp), $Message))
    }
}

function Write-Step {
    param([Parameter(Mandatory = $true)][string]$Title)
    Write-Log ''
    Write-Log ('==================== ' + $Title + ' ====================') 'STEP'
}

function New-UpdateResult {
    param([int]$ExitCode, [string]$Summary)
    return [pscustomobject]@{ ExitCode = $ExitCode; Summary = $Summary }
}

function Format-Bytes {
    param([long]$Bytes)
    if ($Bytes -ge 1GB) { return ('{0:N2} GB' -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ('{0:N2} MB' -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ('{0:N1} KB' -f ($Bytes / 1KB)) }
    return ('{0} B' -f $Bytes)
}

function Format-CommandLineForLog {
    param([AllowEmptyString()][string]$CommandLine, [int]$MaxLength = 500)
    if ([string]::IsNullOrEmpty($CommandLine)) { return '（不可读取）' }
    if ($CommandLine.Length -le $MaxLength) { return $CommandLine }
    return $CommandLine.Substring(0, $MaxLength) + '...(截断)'
}

# ----------------------------------------------------------------------------
# JSON / 文件 / 路径
# ----------------------------------------------------------------------------

function Get-JsonProp {
    param(
        [AllowNull()][object]$Object,
        [Parameter(Mandatory = $true)][string[]]$Names
    )
    if ($null -eq $Object) { return $null }
    foreach ($prop in $Object.PSObject.Properties) {
        foreach ($name in $Names) {
            if ($prop.Name -ieq $name) { return $prop.Value }
        }
    }
    return $null
}

function Read-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$What = 'JSON 文件'
    )
    try {
        $raw = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
        if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
        return ($raw | ConvertFrom-Json)
    } catch {
        throw ('{0}解析失败：{1}（{2}）' -f $What, $Path, $_.Exception.Message)
    }
}

function Save-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][object]$Data
    )
    New-Directory -Path (Split-Path -Parent $Path)
    $json = $Data | ConvertTo-Json -Depth 8
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, $encoding)
}

function Resolve-UserPath {
    param(
        [AllowEmptyString()][string]$Path,
        [Parameter(Mandatory = $true)][string]$BaseDir
    )
    if ([string]::IsNullOrWhiteSpace($Path)) { return '' }
    $expanded = [Environment]::ExpandEnvironmentVariables($Path.Trim().Trim('"'))
    if ([System.IO.Path]::IsPathRooted($expanded)) {
        return [System.IO.Path]::GetFullPath($expanded)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BaseDir $expanded))
}

function Copy-FileForce {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    $destDir = Split-Path -Parent $Destination
    if (-not [string]::IsNullOrEmpty($destDir)) { New-Directory -Path $destDir }
    if (Test-Path -LiteralPath $Destination) {
        $item = Get-Item -LiteralPath $Destination -Force
        if ($item.IsReadOnly) { $item.IsReadOnly = $false }
    }
    [System.IO.File]::Copy($Source, $Destination, $true)
}

# 列出目录下所有文件（返回相对路径，按名称排序）
function Get-RelativeFiles {
    param([Parameter(Mandatory = $true)][string]$Root)
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return @() }
    $rootFull = (Get-Item -LiteralPath $Root).FullName.TrimEnd('\')
    $prefixLength = $rootFull.Length + 1
    $result = New-Object System.Collections.Generic.List[string]
    foreach ($file in (Get-ChildItem -LiteralPath $rootFull -Recurse -File -ErrorAction Stop)) {
        if ($file.FullName.Length -le $prefixLength) { continue }
        $result.Add($file.FullName.Substring($prefixLength)) | Out-Null
    }
    return @($result | Sort-Object)
}

function Get-FileHashMap {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [AllowEmptyCollection()][string[]]$RelPaths = @()
    )
    $map = New-Object -TypeName 'System.Collections.Hashtable' -ArgumentList ([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($rel in $RelPaths) {
        $full = Join-Path $Root $rel
        try {
            $map[$rel] = (Get-FileHash -LiteralPath $full -Algorithm SHA256 -ErrorAction Stop).Hash
        } catch {
            throw ('计算文件哈希失败：{0} — {1}' -f $full, $_.Exception.Message)
        }
    }
    return $map
}

# ----------------------------------------------------------------------------
# 配置
# ----------------------------------------------------------------------------

function Get-DefaultConfig {
    return [ordered]@{
        Repo                 = 'QQQW114/ScriptAgent4MindustryExt-mdtdo'
        Branch               = 'main'
        Proxy                = ''
        ProxyPort            = 0
        ProxyFallbackDirect  = $true
        ServerScriptsPath    = ''
        StatsWebPath         = ''
        BackupPath           = ''
        KeepBackups          = 5
        ProtectedServerDirs  = @('data', 'external-cp', 'cache')
        ProtectedServerFiles = @()
        ProtectedWebDirs     = @('web-data')
        ProtectedWebFiles    = @('web-config.json', 'web-config.ps1')
        CachePath            = ''
        # 运行中热更新（console socket + 脚本加载日志）；缺字段时使用这些内置默认值，老配置无需修改
        HotReloadMode               = 'socket'
        ConsoleSocketHost           = '127.0.0.1'
        ConsoleSocketPort           = 6859
        ReloadLogPath               = ''
        PerScriptTimeoutSeconds     = 120
        ReloadSettleMillis          = 3000
        SocketConnectTimeoutSeconds = 5
        RequireServerStopped = $true
        GamePort             = 6567
        WebPort              = 0
        LogDir               = ''
    }
}

function Get-StringArray {
    param([AllowNull()][object]$Value)
    $result = New-Object System.Collections.Generic.List[string]
    foreach ($item in @($Value)) {
        if ($null -eq $item) { continue }
        $text = ([string]$item).Trim()
        if ([string]::IsNullOrWhiteSpace($text)) { continue }
        $result.Add($text.Trim('\')) | Out-Null
    }
    return $result.ToArray()
}

function Get-EffectiveConfig {
    param(
        [Parameter(Mandatory = $true)][string]$UpdaterDir,
        [Parameter(Mandatory = $true)][hashtable]$Cli
    )

    $configPath = ''
    if ($Cli.ContainsKey('Config') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.Config)) {
        $configPath = Resolve-UserPath -Path ([string]$Cli.Config) -BaseDir $UpdaterDir
        if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
            throw ('REFUSED: 指定的配置文件不存在：{0}' -f $configPath)
        }
    } else {
        $defaultConfig = Join-Path $UpdaterDir 'config.json'
        if (Test-Path -LiteralPath $defaultConfig -PathType Leaf) { $configPath = $defaultConfig }
    }

    $fileConfig = $null
    if (-not [string]::IsNullOrWhiteSpace($configPath)) {
        $fileConfig = Read-JsonFile -Path $configPath -What '配置文件'
    }

    $config = Get-DefaultConfig

    $map = @(
        @{ Json = 'repo'; Key = 'Repo'; Kind = 'string' },
        @{ Json = 'branch'; Key = 'Branch'; Kind = 'string' },
        @{ Json = 'proxy'; Key = 'Proxy'; Kind = 'string' },
        @{ Json = 'proxyPort'; Key = 'ProxyPort'; Kind = 'int' },
        @{ Json = 'proxyFallbackDirect'; Key = 'ProxyFallbackDirect'; Kind = 'bool' },
        @{ Json = 'serverScriptsPath'; Key = 'ServerScriptsPath'; Kind = 'string' },
        @{ Json = 'statsWebPath'; Key = 'StatsWebPath'; Kind = 'string' },
        @{ Json = 'backupPath'; Key = 'BackupPath'; Kind = 'string' },
        @{ Json = 'keepBackups'; Key = 'KeepBackups'; Kind = 'int' },
        @{ Json = 'protectedServerDirs'; Key = 'ProtectedServerDirs'; Kind = 'stringArray' },
        @{ Json = 'protectedServerFiles'; Key = 'ProtectedServerFiles'; Kind = 'stringArray' },
        @{ Json = 'protectedWebDirs'; Key = 'ProtectedWebDirs'; Kind = 'stringArray' },
        @{ Json = 'protectedWebFiles'; Key = 'ProtectedWebFiles'; Kind = 'stringArray' },
        @{ Json = 'cachePath'; Key = 'CachePath'; Kind = 'string' },
        @{ Json = 'hotReloadMode'; Key = 'HotReloadMode'; Kind = 'string' },
        @{ Json = 'consoleSocketHost'; Key = 'ConsoleSocketHost'; Kind = 'string' },
        @{ Json = 'consoleSocketPort'; Key = 'ConsoleSocketPort'; Kind = 'int' },
        @{ Json = 'reloadLogPath'; Key = 'ReloadLogPath'; Kind = 'string' },
        @{ Json = 'perScriptTimeoutSeconds'; Key = 'PerScriptTimeoutSeconds'; Kind = 'int' },
        @{ Json = 'reloadSettleMillis'; Key = 'ReloadSettleMillis'; Kind = 'int' },
        @{ Json = 'socketConnectTimeoutSeconds'; Key = 'SocketConnectTimeoutSeconds'; Kind = 'int' },
        @{ Json = 'requireServerStopped'; Key = 'RequireServerStopped'; Kind = 'bool' },
        @{ Json = 'gamePort'; Key = 'GamePort'; Kind = 'int' },
        @{ Json = 'webPort'; Key = 'WebPort'; Kind = 'int' },
        @{ Json = 'logDir'; Key = 'LogDir'; Kind = 'string' }
    )
    if ($null -ne $fileConfig) {
        foreach ($entry in $map) {
            $value = Get-JsonProp -Object $fileConfig -Names @($entry.Json)
            if ($null -eq $value) { continue }
            switch ($entry.Kind) {
                'string' { $config[$entry.Key] = [string]$value }
                'int' { $config[$entry.Key] = [int]$value }
                'bool' { $config[$entry.Key] = [bool]$value }
                'stringArray' { $config[$entry.Key] = Get-StringArray -Value $value }
            }
        }
    }

    # 命令行覆盖（显式传入优先）
    if ($Cli.ContainsKey('Repo') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.Repo)) { $config.Repo = [string]$Cli.Repo }
    if ($Cli.ContainsKey('Branch') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.Branch)) { $config.Branch = [string]$Cli.Branch }
    if ($Cli.ContainsKey('Proxy') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.Proxy)) { $config.Proxy = [string]$Cli.Proxy }
    if ($Cli.ContainsKey('ProxyPort') -and [int]$Cli.ProxyPort -gt 0) { $config.ProxyPort = [int]$Cli.ProxyPort }
    if ($Cli.ContainsKey('KeepBackups') -and [int]$Cli.KeepBackups -gt 0) { $config.KeepBackups = [int]$Cli.KeepBackups }

    # 运行中热更新：模式与参数（缺字段用默认值；异常值回退/钳制并提示，老配置无需修改）
    $hotReloadMode = ([string]$config.HotReloadMode).Trim().ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($hotReloadMode)) { $hotReloadMode = 'socket' }
    if (@('socket', 'auto', 'off') -notcontains $hotReloadMode) {
        Write-Host ('提示：config.json 的 hotReloadMode 值无效（{0}），已回退为 socket（可选 socket|auto|off）。' -f $config.HotReloadMode) -ForegroundColor Yellow
        $hotReloadMode = 'socket'
    }
    if ($Cli.ContainsKey('HotReload')) {
        $cliHotReload = ([string]$Cli.HotReload).Trim().ToLowerInvariant()
        if (-not [string]::IsNullOrWhiteSpace($cliHotReload)) {
            if (@('socket', 'auto', 'off') -notcontains $cliHotReload) {
                throw ('REFUSED: -HotReload 参数无效：{0}（可选 socket|auto|off）' -f $Cli.HotReload)
            }
            $hotReloadMode = $cliHotReload
        }
    }
    if ($Cli.ContainsKey('NoHotReload') -and [bool]$Cli.NoHotReload) {
        if ($hotReloadMode -ne 'off') { Write-Host '提示：-NoHotReload 已生效（等同 -HotReload off）。' -ForegroundColor Yellow }
        $hotReloadMode = 'off'
    }
    $config.HotReloadMode = $hotReloadMode
    $perScriptTimeout = [int]$config.PerScriptTimeoutSeconds
    if ($perScriptTimeout -lt 10) {
        Write-Host ('提示：perScriptTimeoutSeconds={0} 过小，已钳制为最小值 10 秒（允许 10-600）。' -f $perScriptTimeout) -ForegroundColor Yellow
        $perScriptTimeout = 10
    } elseif ($perScriptTimeout -gt 600) {
        Write-Host ('提示：perScriptTimeoutSeconds={0} 过大，已钳制为最大值 600 秒（允许 10-600）。' -f $perScriptTimeout) -ForegroundColor Yellow
        $perScriptTimeout = 600
    }
    $config.PerScriptTimeoutSeconds = $perScriptTimeout
    $reloadSettleMillis = [int]$config.ReloadSettleMillis
    if ($reloadSettleMillis -lt 500) {
        Write-Host ('提示：reloadSettleMillis={0} 过小，已钳制为最小值 500 毫秒（允许 500-15000）。' -f $reloadSettleMillis) -ForegroundColor Yellow
        $reloadSettleMillis = 500
    } elseif ($reloadSettleMillis -gt 15000) {
        Write-Host ('提示：reloadSettleMillis={0} 过大，已钳制为最大值 15000 毫秒（允许 500-15000）。' -f $reloadSettleMillis) -ForegroundColor Yellow
        $reloadSettleMillis = 15000
    }
    $config.ReloadSettleMillis = $reloadSettleMillis
    $connectTimeout = [int]$config.SocketConnectTimeoutSeconds
    if ($connectTimeout -lt 2) { $connectTimeout = 2 } elseif ($connectTimeout -gt 60) { $connectTimeout = 60 }
    $config.SocketConnectTimeoutSeconds = $connectTimeout

    # 代理解析
    if (-not [string]::IsNullOrWhiteSpace([string]$config.Proxy) -and [int]$config.ProxyPort -gt 0) {
        Write-Host '提示：同时指定了 proxy 与 proxyPort，以 proxy 为准（忽略 proxyPort）。' -ForegroundColor Yellow
        $config.ProxyPort = 0
    }
    if ([string]::IsNullOrWhiteSpace([string]$config.Proxy) -and [int]$config.ProxyPort -gt 0) {
        $config.Proxy = 'http://127.0.0.1:{0}' -f [int]$config.ProxyPort
        $config.ProxyPort = 0
    }
    # 环境变量 HTTPS_PROXY 兜底
    if ([string]::IsNullOrWhiteSpace([string]$config.Proxy)) {
        foreach ($envName in @('HTTPS_PROXY', 'https_proxy', 'HTTP_PROXY', 'http_proxy')) {
            $envValue = [Environment]::GetEnvironmentVariable($envName)
            if (-not [string]::IsNullOrWhiteSpace($envValue)) {
                $config.Proxy = $envValue
                $config['ProxyFromEnv'] = $true
                $config['ProxyEnvName'] = $envName
                break
            }
        }
    }

    # 路径解析（相对路径按 updater 目录解析；推荐绝对路径）
    $config.ServerScriptsPath = Resolve-UserPath -Path ([string]$config.ServerScriptsPath) -BaseDir $UpdaterDir
    $config.StatsWebPath = Resolve-UserPath -Path ([string]$config.StatsWebPath) -BaseDir $UpdaterDir
    $config.BackupPath = Resolve-UserPath -Path ([string]$config.BackupPath) -BaseDir $UpdaterDir
    $config.CachePath = Resolve-UserPath -Path ([string]$config.CachePath) -BaseDir $UpdaterDir
    # 脚本加载日志（完成判定依据）：留空 = <serverScriptsPath>\..\logs\script-load\current.log
    # 即 mdtserver\logs\script-load\current.log，由 SA 在每次编译/加载脚本时追加写入。
    if ([string]::IsNullOrWhiteSpace([string]$config.ReloadLogPath)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$config.ServerScriptsPath)) {
            $serverRoot = Split-Path -Parent ([string]$config.ServerScriptsPath).TrimEnd('\')
            if (-not [string]::IsNullOrWhiteSpace($serverRoot)) {
                $config.ReloadLogPath = Join-Path $serverRoot 'logs\script-load\current.log'
            }
        }
    } else {
        $config.ReloadLogPath = Resolve-UserPath -Path ([string]$config.ReloadLogPath) -BaseDir $UpdaterDir
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$config.LogDir)) {
        $config.LogDir = Resolve-UserPath -Path ([string]$config.LogDir) -BaseDir $UpdaterDir
    }
    $config['UpdaterDir'] = $UpdaterDir
    $config['ConfigPath'] = $configPath

    # 校验
    if ([string]$config.Repo -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') {
        throw ('仓库格式无效：{0}（应为 owner/name）' -f $config.Repo)
    }
    if ([string]$config.Branch -notmatch '^[A-Za-z0-9._/-]+$') {
        throw ('分支名无效：{0}' -f $config.Branch)
    }
    if ([int]$config.KeepBackups -lt 1 -or [int]$config.KeepBackups -gt 50) {
        throw ('keepBackups 无效：{0}（应为 1-50）' -f $config.KeepBackups)
    }
    if ([int]$config.GamePort -lt 1 -or [int]$config.GamePort -gt 65535) {
        throw ('gamePort 无效：{0}' -f $config.GamePort)
    }
    if ([int]$config.WebPort -lt 0 -or [int]$config.WebPort -gt 65535) {
        throw ('webPort 无效：{0}' -f $config.WebPort)
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$config.Proxy) -and [string]$config.Proxy -notmatch '^https?://') {
        throw ('代理格式无效：{0}（应形如 http://127.0.0.1:7897）' -f $config.Proxy)
    }
    if ([string]::IsNullOrWhiteSpace([string]$config.BackupPath)) {
        throw 'REFUSED: 尚未配置 backupPath（备份根目录），请先编辑 config.json。'
    }
    return $config
}

function Get-KindSettings {
    param(
        [Parameter(Mandatory = $true)][string]$Kind,
        [Parameter(Mandatory = $true)]$Config
    )
    $isWeb = ($Kind -eq 'web')
    if ($isWeb) {
        $backupRoot = Join-Path ([string]$Config.BackupPath) 'web'
        return [pscustomobject]@{
            Kind             = $Kind
            IsWeb            = $true
            SourceSubdir     = 'stats-web'
            KindLabel        = 'Web 门户（stats-web）'
            TargetPath       = [string]$Config.StatsWebPath
            ProtectedDirs    = @($Config.ProtectedWebDirs)
            ProtectedFiles   = @($Config.ProtectedWebFiles)
            BackupKind       = 'web'
            BackupRoot       = $backupRoot
            MarkerPath       = (Join-Path $backupRoot '.last-version')
            NeedsServerStopped = $false
            RequireServerStopped = $false
            SupportsHotReload = $false
            ClearCache       = $false
        }
    }
    $backupRoot = Join-Path ([string]$Config.BackupPath) 'plugins'
    return [pscustomobject]@{
        Kind             = $Kind
        IsWeb            = $false
        SourceSubdir     = 'scripts'
        KindLabel        = '服务器插件脚本（scripts）'
        TargetPath       = [string]$Config.ServerScriptsPath
        ProtectedDirs    = @($Config.ProtectedServerDirs)
        ProtectedFiles   = @($Config.ProtectedServerFiles)
        BackupKind       = 'plugins'
        BackupRoot       = $backupRoot
        MarkerPath       = (Join-Path $backupRoot '.last-version')
        NeedsServerStopped = [bool]$Config.RequireServerStopped
        RequireServerStopped = [bool]$Config.RequireServerStopped
        # 运行中热更新只对普通 update-plugins 开放；清缓存版要求停服强制重编译
        SupportsHotReload = ($Kind -eq 'plugins')
        ClearCache       = ($Kind -eq 'plugins-clear-cache')
    }
}

# ----------------------------------------------------------------------------
# 网络：代理 / 下载 / GitHub API
# ----------------------------------------------------------------------------

function Get-ProxyCandidates {
    param([Parameter(Mandatory = $true)]$Config)
    $candidates = New-Object System.Collections.Generic.List[object]
    $explicitProxy = [string]$Config.Proxy
    if (-not [string]::IsNullOrWhiteSpace($explicitProxy)) {
        $label = '代理 ' + $explicitProxy
        if ($Config.Contains('ProxyFromEnv') -and $Config.ProxyFromEnv) {
            $label = ('环境变量 {0} = {1}' -f $Config.ProxyEnvName, $explicitProxy)
        }
        $candidates.Add([pscustomobject]@{ Label = $label; Proxy = $explicitProxy }) | Out-Null
        if ([bool]$Config.ProxyFallbackDirect) {
            $candidates.Add([pscustomobject]@{ Label = '直连（代理失败后回退）'; Proxy = '' }) | Out-Null
        }
    } else {
        $candidates.Add([pscustomobject]@{ Label = '直连'; Proxy = '' }) | Out-Null
    }
    return $candidates.ToArray()
}

function Invoke-DownloadFile {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$OutFile,
        [Parameter(Mandatory = $true)]$Config
    )
    if (Test-Path -LiteralPath $OutFile) { Remove-Item -LiteralPath $OutFile -Force -ErrorAction SilentlyContinue }
    New-Directory -Path (Split-Path -Parent $OutFile)
    $errors = New-Object System.Collections.Generic.List[string]
    foreach ($candidate in (Get-ProxyCandidates -Config $Config)) {
        try {
            Write-Log ('下载（{0}）：{1}' -f $candidate.Label, $Url)
            $params = @{
                Uri             = $Url
                OutFile         = $OutFile
                UseBasicParsing = $true
                TimeoutSec      = 300
            }
            if (-not [string]::IsNullOrWhiteSpace([string]$candidate.Proxy)) { $params['Proxy'] = [string]$candidate.Proxy }
            $watch = [System.Diagnostics.Stopwatch]::StartNew()
            Invoke-WebRequest @params
            $watch.Stop()
            if (-not (Test-Path -LiteralPath $OutFile -PathType Leaf)) { throw '下载完成后没有生成文件' }
            $size = (Get-Item -LiteralPath $OutFile).Length
            if ($size -lt 10KB) { throw ('下载文件过小（{0} 字节），可能不是有效 zip' -f $size) }
            Write-Log ('下载完成：{0}（{1}，用时 {2:N1}s）' -f $OutFile, (Format-Bytes -Bytes $size), $watch.Elapsed.TotalSeconds) 'OK'
            return
        } catch {
            $errors.Add(('[{0}] {1}' -f $candidate.Label, $_.Exception.Message)) | Out-Null
            Write-Log ('下载失败（{0}）：{1}' -f $candidate.Label, $_.Exception.Message) 'WARN'
            Remove-Item -LiteralPath $OutFile -Force -ErrorAction SilentlyContinue
        }
    }
    throw ('下载失败，已中止（目标目录没有任何改动）。' + [Environment]::NewLine + ($errors -join [Environment]::NewLine))
}

function Invoke-GitHubApi {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)]$Config
    )
    $errors = New-Object System.Collections.Generic.List[string]
    foreach ($candidate in (Get-ProxyCandidates -Config $Config)) {
        try {
            $headers = @{
                'User-Agent' = ('mdt-updater/' + $script:UpdaterVersion)
                'Accept'     = 'application/vnd.github+json'
            }
            $params = @{
                Uri             = $Url
                UseBasicParsing = $true
                TimeoutSec      = 20
                Headers         = $headers
            }
            if (-not [string]::IsNullOrWhiteSpace([string]$candidate.Proxy)) { $params['Proxy'] = [string]$candidate.Proxy }
            $response = Invoke-WebRequest @params
            return ($response.Content | ConvertFrom-Json)
        } catch {
            $errors.Add(('[{0}] {1}' -f $candidate.Label, $_.Exception.Message)) | Out-Null
        }
    }
    Write-Detail ('GitHub API 全部尝试失败：' + ($errors -join ' | '))
    return $null
}

function Get-RemoteCommitInfo {
    param([Parameter(Mandatory = $true)]$Config)
    $url = 'https://api.github.com/repos/{0}/commits/{1}' -f $Config.Repo, $Config.Branch
    $response = Invoke-GitHubApi -Url $url -Config $Config
    if ($null -eq $response) { return $null }
    $sha = [string]$response.sha
    if ([string]::IsNullOrWhiteSpace($sha)) { return $null }
    $date = ''
    $message = ''
    try { $date = [string]$response.commit.committer.date } catch { }
    try { $message = (([string]$response.commit.message) -split "`n")[0].Trim() } catch { }
    return [pscustomobject]@{ Sha = $sha.ToLowerInvariant(); Date = $date; Message = $message }
}

# ----------------------------------------------------------------------------
# 版本标记
# ----------------------------------------------------------------------------

function Read-VersionMarker {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try {
        return (Read-JsonFile -Path $Path -What '本地版本标记')
    } catch {
        Write-Log ('本地版本标记损坏，将忽略：{0}' -f $_.Exception.Message) 'WARN'
        return $null
    }
}

function Write-VersionMarker {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Marker
    )
    Save-JsonFile -Path $Path -Data $Marker
}

# ----------------------------------------------------------------------------
# zip / 源目录定位与校验
# ----------------------------------------------------------------------------

function Expand-RepoZip {
    param(
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    if (Test-Path -LiteralPath $Destination) {
        Remove-Item -LiteralPath $Destination -Recurse -Force -ErrorAction SilentlyContinue
    }
    New-Directory -Path $Destination
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
        [System.IO.Compression.ZipFile]::ExtractToDirectory($ZipPath, $Destination)
        Write-Log ('解压完成：{0}' -f $Destination) 'OK'
        return
    } catch {
        Write-Log ('ZipFile 解压失败，改用 Expand-Archive 重试：{0}' -f $_.Exception.Message) 'WARN'
    }
    if (Test-Path -LiteralPath $Destination) {
        Remove-Item -LiteralPath $Destination -Recurse -Force -ErrorAction SilentlyContinue
    }
    New-Directory -Path $Destination
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $Destination -Force
    Write-Log ('解压完成（Expand-Archive）：{0}' -f $Destination) 'OK'
}

function Get-RepoSourceDir {
    param(
        [Parameter(Mandatory = $true)][string]$ExtractDir,
        [Parameter(Mandatory = $true)][string]$SourceSubdir
    )
    $candidates = New-Object System.Collections.Generic.List[string]
    $direct = Join-Path $ExtractDir $SourceSubdir
    if (Test-Path -LiteralPath $direct -PathType Container) { $candidates.Add($direct) | Out-Null }
    foreach ($top in (Get-ChildItem -LiteralPath $ExtractDir -Directory -ErrorAction SilentlyContinue)) {
        $child = Join-Path $top.FullName $SourceSubdir
        if (Test-Path -LiteralPath $child -PathType Container) { $candidates.Add($child) | Out-Null }
    }
    if ($candidates.Count -eq 0) {
        throw ('zip 中没有找到 {0}\ 目录（解压目录：{1}）。已中止，目标目录没有任何改动。可能仓库/分支选错了。' -f $SourceSubdir, $ExtractDir)
    }
    if ($candidates.Count -gt 1) {
        throw ('zip 中找到了多个 {0}\ 目录，无法确定使用哪个：{1}' -f $SourceSubdir, ($candidates -join '; '))
    }
    return $candidates[0]
}

function Assert-SourceContent {
    param([Parameter(Mandatory = $true)]$Settings, [Parameter(Mandatory = $true)][string]$SourceDir)
    if ($Settings.IsWeb) {
        $required = @('index.html', 'start-web.ps1')
        $missing = New-Object System.Collections.Generic.List[string]
        foreach ($rel in $required) {
            if (-not (Test-Path -LiteralPath (Join-Path $SourceDir $rel) -PathType Leaf)) { $missing.Add($rel) | Out-Null }
        }
        if (-not (Test-Path -LiteralPath (Join-Path $SourceDir 'server') -PathType Container)) { $missing.Add('server\') | Out-Null }
        if ($missing.Count -gt 0) {
            throw ('下载内容校验失败：stats-web 缺少关键项 {0}。已中止，目标目录没有任何改动。' -f ($missing -join ', '))
        }
        $files = @(Get-RelativeFiles -Root $SourceDir)
        if ($files.Count -lt 10) {
            throw ('下载内容校验失败：stats-web 只有 {0} 个文件，不像完整仓库。已中止。' -f $files.Count)
        }
        $size = 0
        foreach ($rel in $files) { $size += (Get-Item -LiteralPath (Join-Path $SourceDir $rel)).Length }
        Write-Log ('校验通过：stats-web\ 共 {0} 个文件（{1}）。' -f $files.Count, (Format-Bytes -Bytes $size)) 'OK'
        return
    }

    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($rel in @('wayzer\vote.lib.kt', 'coreLibrary\lib\CommandApi.kt')) {
        if (-not (Test-Path -LiteralPath (Join-Path $SourceDir $rel) -PathType Leaf)) { $missing.Add($rel) | Out-Null }
    }
    if ($missing.Count -gt 0) {
        throw ('下载内容校验失败：scripts 缺少关键文件 {0}。已中止，目标目录没有任何改动。' -f ($missing -join ', '))
    }
    $scriptCount = 0
    $fileCount = 0
    $size = 0
    foreach ($file in (Get-ChildItem -LiteralPath $SourceDir -Recurse -File -ErrorAction Stop)) {
        $fileCount++
        $size += $file.Length
        if (@('.kt', '.kts') -contains $file.Extension.ToLowerInvariant()) { $scriptCount++ }
    }
    if ($scriptCount -lt 100) {
        throw ('下载内容校验失败：scripts 中 .kt/.kts 脚本只有 {0} 个（要求 > 100），不像完整仓库。已中止。' -f $scriptCount)
    }
    Write-Log ('校验通过：scripts\ 共 {0} 个文件（其中 .kt/.kts 脚本 {1} 个，{2}）。' -f $fileCount, $scriptCount, (Format-Bytes -Bytes $size)) 'OK'
}

# ----------------------------------------------------------------------------
# 保护规则 / 严格镜像分析
# ----------------------------------------------------------------------------

function Test-IsProtectedPath {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [AllowEmptyCollection()][string[]]$ProtectedDirs = @(),
        [AllowEmptyCollection()][string[]]$ProtectedFiles = @()
    )
    $rel = $RelativePath.TrimStart('\')
    foreach ($file in @($ProtectedFiles)) {
        if ([string]::IsNullOrWhiteSpace([string]$file)) { continue }
        if ($rel -ieq ([string]$file).Trim('\')) { return $true }
    }
    foreach ($dir in @($ProtectedDirs)) {
        if ([string]::IsNullOrWhiteSpace([string]$dir)) { continue }
        $d = ([string]$dir).Trim('\')
        if ($rel -ieq $d) { return $true }
        if ($rel.Length -gt $d.Length) {
            if ($rel.Substring(0, $d.Length + 1) -ieq ($d + '\')) { return $true }
        }
    }
    return $false
}

function Get-MirrorAnalysis {
    param(
        [Parameter(Mandatory = $true)][string]$SourceRoot,
        [Parameter(Mandatory = $true)][string]$TargetRoot,
        [AllowEmptyCollection()][string[]]$ProtectedDirs = @(),
        [AllowEmptyCollection()][string[]]$ProtectedFiles = @()
    )
    $sourceAll = @(Get-RelativeFiles -Root $SourceRoot)
    $targetAll = @(Get-RelativeFiles -Root $TargetRoot)

    $sourceMirror = New-Object System.Collections.Generic.List[string]
    $sourceProtected = New-Object System.Collections.Generic.List[string]
    foreach ($rel in $sourceAll) {
        if (Test-IsProtectedPath -RelativePath $rel -ProtectedDirs $ProtectedDirs -ProtectedFiles $ProtectedFiles) {
            $sourceProtected.Add($rel) | Out-Null
        } else {
            $sourceMirror.Add($rel) | Out-Null
        }
    }
    $targetMirror = New-Object System.Collections.Generic.List[string]
    $targetProtected = New-Object System.Collections.Generic.List[string]
    foreach ($rel in $targetAll) {
        if (Test-IsProtectedPath -RelativePath $rel -ProtectedDirs $ProtectedDirs -ProtectedFiles $ProtectedFiles) {
            $targetProtected.Add($rel) | Out-Null
        } else {
            $targetMirror.Add($rel) | Out-Null
        }
    }

    $sourceHash = Get-FileHashMap -Root $SourceRoot -RelPaths $sourceMirror.ToArray()
    $targetHash = Get-FileHashMap -Root $TargetRoot -RelPaths $targetMirror.ToArray()

    $added = New-Object System.Collections.Generic.List[string]
    $modified = New-Object System.Collections.Generic.List[string]
    $unchanged = New-Object System.Collections.Generic.List[string]
    foreach ($rel in $sourceMirror) {
        if (-not $targetHash.ContainsKey($rel)) {
            $added.Add($rel) | Out-Null
        } elseif ([string]$sourceHash[$rel] -ne [string]$targetHash[$rel]) {
            $modified.Add($rel) | Out-Null
        } else {
            $unchanged.Add($rel) | Out-Null
        }
    }
    $deleted = New-Object System.Collections.Generic.List[string]
    foreach ($rel in $targetMirror) {
        if (-not $sourceHash.ContainsKey($rel)) { $deleted.Add($rel) | Out-Null }
    }

    return [pscustomobject]@{
        SourceRoot            = (Get-Item -LiteralPath $SourceRoot).FullName
        TargetRoot            = (Get-Item -LiteralPath $TargetRoot).FullName
        SourceMirrorCount     = $sourceMirror.Count
        TargetMirrorCount     = $targetMirror.Count
        SourceHash            = $sourceHash
        Added                 = @($added | Sort-Object)
        Modified              = @($modified | Sort-Object)
        Deleted               = @($deleted | Sort-Object)
        UnchangedCount        = $unchanged.Count
        SourceProtected       = @($sourceProtected | Sort-Object)
        TargetProtectedCount  = $targetProtected.Count
        TargetProtectedList   = @($targetProtected | Sort-Object)
    }
}

function Write-FileList {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [AllowEmptyCollection()][string[]]$Files = @(),
        [int]$ConsoleLimit = 40
    )
    $Files = @($Files)
    if ($Files.Count -eq 0) { return }
    Write-Log ('{0}（{1} 个）：' -f $Title, $Files.Count)
    for ($i = 0; $i -lt $Files.Count; $i++) {
        Write-Detail ('  ' + $Files[$i])
        if ($i -lt $ConsoleLimit) { Write-Host ('  ' + $Files[$i]) }
    }
    if ($Files.Count -gt $ConsoleLimit) {
        Write-Host ('  ...（其余 {0} 个见日志文件）' -f ($Files.Count - $ConsoleLimit))
    }
}

function Write-MirrorReport {
    param([Parameter(Mandatory = $true)]$Analysis, [Parameter(Mandatory = $true)]$Settings)
    Write-Log ('目标目录：{0}（严格镜像：{1}）' -f $Analysis.TargetRoot, $Settings.KindLabel)
    Write-Log ('扫描：仓库受管 {0} 个文件；目标受管 {1} 个文件（受保护保留 {2} 个；仓库受保护跳过 {3} 个）' -f $Analysis.SourceMirrorCount, $Analysis.TargetMirrorCount, $Analysis.TargetProtectedCount, $Analysis.SourceProtected.Count)
    Write-Log ('严格镜像统计：新增 {0} / 修改 {1} / 删除 {2} / 未变 {3}' -f $Analysis.Added.Count, $Analysis.Modified.Count, $Analysis.Deleted.Count, $Analysis.UnchangedCount) 'OK'
    Write-FileList -Title '新增文件' -Files $Analysis.Added
    Write-FileList -Title '修改文件' -Files $Analysis.Modified
    Write-FileList -Title '删除文件（仓库中已不存在，按严格镜像删除）' -Files $Analysis.Deleted
    if ($Analysis.SourceProtected.Count -gt 0) {
        Write-Log ('仓库中受保护、不会复制到目标的文件（{0} 个，例如 data\config.conf、external-cp\.gitkeep、web-config.json）：' -f $Analysis.SourceProtected.Count)
        foreach ($rel in $Analysis.SourceProtected) { Write-Detail ('  - ' + $rel) }
    }
}

# ----------------------------------------------------------------------------
# 服务器/游戏进程检查（要求停服）
# ----------------------------------------------------------------------------

function Get-ListeningOwnerPids {
    param([Parameter(Mandatory = $true)][int]$Port)
    $pids = New-Object System.Collections.Generic.List[int]
    try {
        foreach ($conn in (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop)) {
            $ownerPid = [int]$conn.OwningProcess
            if ($ownerPid -gt 0 -and -not $pids.Contains($ownerPid)) { $pids.Add($ownerPid) | Out-Null }
        }
        return $pids.ToArray()
    } catch {
        Write-Detail ('Get-NetTCPConnection 不可用，改用 netstat：{0}' -f $_.Exception.Message)
    }
    try {
        $lines = & netstat -ano -p tcp 2>$null
        foreach ($line in $lines) {
            if ($line -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$') {
                if ([int]$Matches[1] -eq $Port) {
                    $ownerPid = [int]$Matches[2]
                    if ($ownerPid -gt 0 -and -not $pids.Contains($ownerPid)) { $pids.Add($ownerPid) | Out-Null }
                }
            }
        }
    } catch {
        Write-Log ('无法枚举端口监听进程：{0}' -f $_.Exception.Message) 'WARN'
    }
    return $pids.ToArray()
}

function Get-JavaProcesses {
    try {
        return @(Get-CimInstance -ClassName Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -ErrorAction Stop)
    } catch {
        Write-Log ('无法通过 CIM 枚举 java 进程：{0}' -f $_.Exception.Message) 'WARN'
        return @()
    }
}

function Get-GameJavaProcesses {
    param([Parameter(Mandatory = $true)]$Config)
    $gamePort = [int]$Config.GamePort
    $listeningPids = @(Get-ListeningOwnerPids -Port $gamePort)
    $result = New-Object System.Collections.Generic.List[object]
    foreach ($process in (Get-JavaProcesses)) {
        $commandLine = ''
        try { $commandLine = [string]$process.CommandLine } catch { }
        if ([string]::IsNullOrWhiteSpace($commandLine)) { continue }
        $jarMatch = $commandLine -match '(?i)server-[^\\/\s"]+\.jar'
        $mdtMatch = $commandLine -match '(?i)mdtserver'
        $portMatch = $listeningPids -contains [int]$process.ProcessId
        if (-not ($jarMatch -or $mdtMatch -or $portMatch)) { continue }
        $result.Add([pscustomobject]@{
            ProcessId   = [int]$process.ProcessId
            Name        = [string]$process.Name
            CommandLine = $commandLine
            JarMatch    = $jarMatch
            MdtMatch    = $mdtMatch
            PortMatch   = $portMatch
        }) | Out-Null
    }
    return $result.ToArray()
}

# 检测服务器是否在运行（gamePort 监听 + 游戏 java 进程），并收集证据用于日志/提示。
function Get-ServerRunState {
    param([Parameter(Mandatory = $true)]$Config)
    $gamePort = [int]$Config.GamePort
    Write-Log ('检查服务器是否已关闭（端口 {0} / 游戏 java 进程）...' -f $gamePort)
    $listeningPids = @(Get-ListeningOwnerPids -Port $gamePort)
    $gameJavas = @(Get-GameJavaProcesses -Config $Config)
    $evidence = New-Object System.Collections.Generic.List[string]
    foreach ($ownerPid in $listeningPids) {
        $detail = ''
        try {
            $proc = Get-CimInstance -ClassName Win32_Process -Filter ("ProcessId = {0}" -f $ownerPid) -ErrorAction Stop
            if ($null -ne $proc) {
                $detail = '{0}，命令行：{1}' -f ([string]$proc.Name), (Format-CommandLineForLog -CommandLine ([string]$proc.CommandLine))
            }
        } catch { }
        $evidence.Add(('  端口 {0} 正在监听：PID={1}（{2}）' -f $gamePort, $ownerPid, $detail)) | Out-Null
    }
    foreach ($process in $gameJavas) {
        $evidence.Add(('  疑似游戏服 java 进程：PID={0}，命令行：{1}' -f $process.ProcessId, (Format-CommandLineForLog -CommandLine $process.CommandLine))) | Out-Null
    }
    return [pscustomobject]@{
        Running       = (($listeningPids.Count -gt 0) -or ($gameJavas.Count -gt 0))
        GamePort      = $gamePort
        ListeningPids = @($listeningPids)
        GameJavas     = @($gameJavas)
        Evidence      = @($evidence)
    }
}

function Get-ServerRunningMessage {
    param(
        [Parameter(Mandatory = $true)]$State,
        [AllowEmptyCollection()][string[]]$Hints = @()
    )
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('检测到服务器仍在运行。') | Out-Null
    foreach ($line in @($State.Evidence)) {
        if ($null -eq $line) { continue }
        $lines.Add([string]$line) | Out-Null
    }
    foreach ($hint in @($Hints)) {
        if ([string]::IsNullOrWhiteSpace([string]$hint)) { continue }
        $lines.Add([string]$hint) | Out-Null
    }
    return $lines.ToArray()
}

function Assert-ServerStopped {
    param([Parameter(Mandatory = $true)]$Config)
    $state = Get-ServerRunState -Config $Config
    if (-not $state.Running) {
        Write-Log '未检测到运行中的服务器，可以安全更新。' 'OK'
        return
    }
    $hints = @(
        '请先关闭服务器（关掉「启动服务器.bat」窗口，或结束对应 java 进程）后重新运行本脚本。',
        '如果确认服务器已关闭但仍有误报：检查 config.json 的 gamePort 是否与 server.properties 的 port 一致。'
    )
    throw ('REFUSED: ' + ((Get-ServerRunningMessage -State $state -Hints $hints) -join [Environment]::NewLine))
}

# ----------------------------------------------------------------------------
# 运行中热重载（console socket + 脚本加载日志）
#
# 原理：
#   服务器运行中不能覆盖脚本文件，但可以打开 Mindustry/SA 的 console socket
#   （server.properties: socketInput=true / socketInputPort=6859 / socketInputAddress=localhost），
#   更新器在严格镜像完成后通过 socket 依次发送 sa 命令，让运行中的服务器自己重载脚本。
#   sa 命令在 socket 上通常没有回显，因此完成判定改为尾随 SA 的脚本加载日志：
#     <serverScriptsPath>\..\logs\script-load\current.log
#   日志每步写一行（[ScriptAgent] 编译脚本 <id> / 编译脚本完成 <id> / 停止脚本 <id> /
#   卸载脚本 <id> / 加载脚本 <id> / 启用脚本 <id>），级联重启的依赖方也会继续写。
#   逐个脚本的节奏：发命令 → 等日志出现该脚本的「启用脚本」（load）或「卸载脚本」（unload）
#   → 再等 reloadSettleMillis 内没有新的 [ScriptAgent] 行 → 才发下一个命令（防止一口气发
#   多条命令导致级联重启互相踩踏）。
#
# 命令顺序（added 非空时先发 sa scan，然后逐个执行）：
#   removed: sa unload <id> → changed: sa load <id> → added: sa load <id>
#   id = scripts\ 下相对路径去掉 .kts、用 / 分隔（例如 wayzer/ext/idleBreakGuard）
#   全部完成后发 sa listFailed 读取 2~3 秒回显，若有脚本失败行则汇总为失败（退出码 3）。
#
# 安全：socket 只应绑定 localhost（不要暴露公网）；只发 sa load/unload/scan/listFailed
#       这几类命令；socket/日志探测失败时在改动文件前中止；热重载失败/超时时文件已更新，
#       返回退出码 3 并提示 sa load 或重启服务器。
# ----------------------------------------------------------------------------

# 命令发出后完全没有 [ScriptAgent] 日志行时的兜底等待（秒）：超过后按 no-op 完成处理。
$script:NoOpGraceSeconds = 8

function Get-ReloadLogLength {
    param([Parameter(Mandatory = $true)][string]$Path)
    try {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return [long]-1 }
        return [long](Get-Item -LiteralPath $Path -Force).Length
    } catch {
        return [long]-1
    }
}

# 从指定偏移读取脚本加载日志的新增内容；文件被轮转/截断时从头读并标记 Truncated。
function Read-ReloadLogNewText {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][long]$Offset
    )
    $result = [pscustomobject]@{
        Lines      = @()
        NewOffset  = $Offset
        Truncated  = $false
        Error      = ''
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $result.Error = '日志文件不存在'
        return $result
    }
    $stream = $null
    $reader = $null
    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
        if ($Offset -lt 0 -or $Offset -gt $stream.Length) {
            $Offset = [long]0
            $result.Truncated = $true
        }
        [void]$stream.Seek($Offset, [System.IO.SeekOrigin]::Begin)
        $reader = New-Object System.IO.StreamReader($stream, (New-Object System.Text.UTF8Encoding($false)), $true)
        $text = $reader.ReadToEnd()
        $result.NewOffset = [long]$stream.Position
        if (-not [string]::IsNullOrEmpty($text)) {
            $result.Lines = @($text -split "`r?`n" | Where-Object { -not [string]::IsNullOrEmpty($_) })
        }
        return $result
    } catch {
        $result.Error = $_.Exception.Message
        return $result
    } finally {
        if ($null -ne $reader) {
            try { $reader.Dispose() } catch { }
        } elseif ($null -ne $stream) {
            try { $stream.Dispose() } catch { }
        }
    }
}

# 把 scripts\ 下的相对路径（如 wayzer\ext\x.kts）转成 sa 脚本 id（wayzer/ext/x）：
# 只要 .kts；保留顺序、去掉 `.kts` 后缀、反斜杠转 /、去重。
function Get-HotReloadScriptIds {
    param([AllowEmptyCollection()][string[]]$RelPaths = @())
    $ids = New-Object System.Collections.Generic.List[string]
    $seen = @{}
    foreach ($rel in @($RelPaths)) {
        $text = [string]$rel
        if ([string]::IsNullOrWhiteSpace($text)) { continue }
        $text = $text.Trim().TrimStart('\').Replace('/', '\')
        if ([System.IO.Path]::GetExtension($text) -ine '.kts') { continue }
        $id = $text.Substring(0, $text.Length - 4).Replace('\', '/')
        if ($seen.ContainsKey($id)) { continue }
        $seen[$id] = $true
        $ids.Add($id) | Out-Null
    }
    return $ids.ToArray()
}

# 找出变更中的非 .kts 文件（.kt 库 / 模块等）——运行中无法热重载，需停服或重启。
function Get-NonScriptRelPaths {
    param([AllowEmptyCollection()][string[]]$RelPaths = @())
    $result = New-Object System.Collections.Generic.List[string]
    $seen = @{}
    foreach ($rel in @($RelPaths)) {
        $text = [string]$rel
        if ([string]::IsNullOrWhiteSpace($text)) { continue }
        $text = $text.Trim().TrimStart('\').Replace('/', '\')
        if ([System.IO.Path]::GetExtension($text) -ieq '.kts') { continue }
        if ($seen.ContainsKey($text)) { continue }
        $seen[$text] = $true
        $result.Add($text) | Out-Null
    }
    return $result.ToArray()
}

# SA 脚本加载日志的完成标记：
#   load   → 启用脚本 <id>（在「加载脚本 <id>」之后）
#   unload → 卸载脚本 <id>
# 注意：真实日志里 unload 行形如「... [ScriptAgent] 卸载脚本 wayzer/ext/e2eProbe: 」（id 后面是
# ASCII 冒号），load 行「... [ScriptAgent] 启用脚本 wayzer/ext/x」行尾无冒号，所以尾随字符类
# 必须同时接受空白/行尾/中英文标点（含 : 与 ：），否则 unload 永远等不到完成标记而超时。
function Test-ReloadCompletionLine {
    param([string]$Line, [string]$Id, [string]$ActionKind)
    if ([string]::IsNullOrEmpty($Line) -or [string]::IsNullOrEmpty($Id)) { return $false }
    if ($Line -notmatch '\[ScriptAgent\]') { return $false }
    $escaped = [regex]::Escape($Id)
    $trailing = '(\s|$|,|:|;|，|；|：|（|\(|)'
    if ($ActionKind -eq 'unload') {
        return ($Line -match ('卸载脚本\s+' + $escaped + $trailing))
    }
    return ($Line -match ('启用脚本\s+' + $escaped + $trailing))
}

# 失败标记：日志里出现当前脚本 id 的失败行（编译脚本失败 / 出错 / 异常 / Exception 等）。
function Test-ReloadFailureLine {
    param([string]$Line, [string]$Id)
    if ([string]::IsNullOrEmpty($Line)) { return $false }
    if ($Line -notmatch '\[ScriptAgent\]') { return $false }
    if ($Line -notmatch '(?i)(失败|出错|异常|错误|ERROR|Exception|failReason)') { return $false }
    if ([string]::IsNullOrWhiteSpace($Id)) { return $true }
    return ($Line -match [regex]::Escape($Id))
}

# 打开 console socket 会话（等价于 telnet 127.0.0.1 6859 后逐行输入 sa 命令）。
function Open-ConsoleSocketSession {
    param([Parameter(Mandatory = $true)]$Config)
    $hostName = [string]$Config.ConsoleSocketHost
    $port = [int]$Config.ConsoleSocketPort
    $timeoutMs = [Math]::Max(1000, [int]$Config.SocketConnectTimeoutSeconds * 1000)
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.NoDelay = $true } catch { }
    $iar = $client.BeginConnect($hostName, $port, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne($timeoutMs, $false)) {
        try { $client.Close() } catch { }
        throw ('连接 console socket 超时（{0}:{1}，{2} 毫秒）：请确认 server.properties 的 socketInput/socketInputPort/socketInputAddress 配置与服务器状态。' -f $hostName, $port, $timeoutMs)
    }
    try {
        $client.EndConnect($iar)
    } catch {
        try { $client.Close() } catch { }
        throw ('连接 console socket 失败（{0}:{1}）：{2}' -f $hostName, $port, $_.Exception.Message)
    }
    $stream = $client.GetStream()
    try { $stream.ReadTimeout = 1000 } catch { }
    try { $stream.WriteTimeout = 5000 } catch { }
    $writer = New-Object System.IO.StreamWriter($stream, (New-Object System.Text.UTF8Encoding($false)))
    $writer.AutoFlush = $true
    return [pscustomobject]@{
        Client       = $client
        Stream       = $stream
        Writer       = $writer
        Host         = $hostName
        Port         = $port
        SentCommands = New-Object System.Collections.Generic.List[string]
    }
}

function Send-ConsoleCommand {
    param(
        [Parameter(Mandatory = $true)]$Session,
        [Parameter(Mandatory = $true)][string]$Command
    )
    try {
        $Session.Writer.WriteLine($Command)
        $Session.Writer.Flush()
    } catch {
        throw ('发送 sa 命令失败：{0}（{1}）' -f $Command, $_.Exception.Message)
    }
    $Session.SentCommands.Add([string]$Command) | Out-Null
    Write-Log ('  → 发送控制台命令：{0}' -f $Command)
}

function Read-ConsoleEcho {
    param(
        [Parameter(Mandatory = $true)]$Session,
        [int]$Milliseconds = 2500
    )
    $lines = New-Object System.Collections.Generic.List[string]
    $deadline = (Get-Date).AddMilliseconds($Milliseconds)
    $buffer = New-Object byte[] 4096
    while ((Get-Date) -lt $deadline) {
        try {
            if ($Session.Client.Available -gt 0) {
                $read = $Session.Stream.Read($buffer, 0, $buffer.Length)
                if ($read -gt 0) {
                    $text = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $read)
                    foreach ($line in ($text -split "`r?`n")) {
                        if (-not [string]::IsNullOrWhiteSpace($line)) { $lines.Add($line) | Out-Null }
                    }
                } else {
                    Start-Sleep -Milliseconds 100
                }
            } else {
                Start-Sleep -Milliseconds 100
            }
        } catch {
            break
        }
    }
    return $lines.ToArray()
}

function Close-ConsoleSocketSession {
    param([AllowNull()]$Session)
    if ($null -eq $Session) { return }
    try { $Session.Writer.Dispose() } catch { }
    try { $Session.Stream.Dispose() } catch { }
    try { $Session.Client.Close() } catch { }
}

# 只读探测 console socket 能否连接（连接后立刻关闭，不发任何命令）。DryRun 也用这个。
function Test-ConsoleSocketAvailable {
    param([Parameter(Mandatory = $true)]$Config)
    $info = [pscustomobject]@{
        Available = $false
        Host      = [string]$Config.ConsoleSocketHost
        Port      = [int]$Config.ConsoleSocketPort
        Reason    = ''
    }
    $session = $null
    try {
        $session = Open-ConsoleSocketSession -Config $Config
        $info.Available = $true
    } catch {
        $info.Reason = $_.Exception.Message
    } finally {
        if ($null -ne $session) { Close-ConsoleSocketSession -Session $session }
    }
    return $info
}

# 把 planned 变更转换成「逐个执行」的命令列表：
#   先 removed 的 sa unload，再 changed 的 sa load，最后 added 的 sa load（各自保持顺序、去重）。
function Get-HotReloadActionPlan {
    param(
        [AllowEmptyCollection()][string[]]$AddedPaths = @(),
        [AllowEmptyCollection()][string[]]$ChangedPaths = @(),
        [AllowEmptyCollection()][string[]]$RemovedPaths = @()
    )
    $actions = New-Object System.Collections.Generic.List[object]
    foreach ($id in @(Get-HotReloadScriptIds -RelPaths $RemovedPaths)) {
        $actions.Add([pscustomobject]@{ Id = [string]$id; Kind = 'removed'; ActionKind = 'unload'; Command = ('sa unload ' + $id) }) | Out-Null
    }
    foreach ($id in @(Get-HotReloadScriptIds -RelPaths $ChangedPaths)) {
        $actions.Add([pscustomobject]@{ Id = [string]$id; Kind = 'changed'; ActionKind = 'load'; Command = ('sa load ' + $id) }) | Out-Null
    }
    foreach ($id in @(Get-HotReloadScriptIds -RelPaths $AddedPaths)) {
        $actions.Add([pscustomobject]@{ Id = [string]$id; Kind = 'added'; ActionKind = 'load'; Command = ('sa load ' + $id) }) | Out-Null
    }
    return $actions.ToArray()
}

# 等待单个脚本的重载完成：
#   - 日志出现该 id 的完成标记（load → 启用脚本；unload → 卸载脚本）；
#   - 之后连续 reloadSettleMillis 内没有新的 [ScriptAgent] 行（等依赖方级联重启写完）；
#   - 超时 perScriptTimeoutSeconds → 失败（TimedOut）；
#   - 命令发出后 noOpGraceSeconds 内完全没有 [ScriptAgent] 行 → 按 no-op 完成处理（NoOp）。
function Wait-ScriptReloadComplete {
    param(
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)][long]$Offset,
        [Parameter(Mandatory = $true)][string]$ScriptId,
        [Parameter(Mandatory = $true)][ValidateSet('load', 'unload')][string]$ActionKind,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][int]$SettleMillis,
        [int]$NoOpGraceSeconds = $script:NoOpGraceSeconds
    )
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $noOpDeadline = (Get-Date).AddSeconds($NoOpGraceSeconds)
    $completionSeen = $false
    $completionAt = $null
    $lastAgentAt = $null
    $sawAgentLine = $false
    $failureLine = ''
    $agentLines = New-Object System.Collections.Generic.List[string]

    while ((Get-Date) -lt $deadline) {
        $chunk = Read-ReloadLogNewText -Path $LogPath -Offset $Offset
        $Offset = [long]$chunk.NewOffset
        if ([bool]$chunk.Truncated) {
            Write-Log ('脚本加载日志被截断/轮转（{0}），已从文件头重新读取。' -f $LogPath) 'WARN'
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$chunk.Error)) {
            Write-Detail ('读取脚本加载日志失败（继续重试）：{0}' -f $chunk.Error)
        }
        foreach ($line in @($chunk.Lines)) {
            if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
            if ($line -notmatch '\[ScriptAgent\]') { continue }
            $sawAgentLine = $true
            $lastAgentAt = Get-Date
            $agentLines.Add([string]$line) | Out-Null
            Write-Detail ('  [脚本日志] ' + $line)
            if (Test-ReloadFailureLine -Line $line -Id $ScriptId) {
                $failureLine = [string]$line
                break
            }
            if (-not $completionSeen -and (Test-ReloadCompletionLine -Line $line -Id $ScriptId -ActionKind $ActionKind)) {
                $completionSeen = $true
                $completionAt = Get-Date
            }
        }
        if (-not [string]::IsNullOrEmpty($failureLine)) { break }

        $now = Get-Date
        if ($completionSeen) {
            $lastActivity = $completionAt
            if ($null -ne $lastAgentAt -and $lastAgentAt -gt $lastActivity) { $lastActivity = $lastAgentAt }
            if (($now - $lastActivity).TotalMilliseconds -ge $SettleMillis) {
                $watch.Stop()
                return [pscustomobject]@{
                    Ok = $true; NoOp = $false; TimedOut = $false; FailureLine = ''
                    ElapsedMs = [long]$watch.Elapsed.TotalMilliseconds
                    AgentLines = @($agentLines.ToArray()); Offset = [long]$Offset
                }
            }
        } elseif (-not $sawAgentLine -and $now -gt $noOpDeadline) {
            $watch.Stop()
            return [pscustomobject]@{
                Ok = $true; NoOp = $true; TimedOut = $false; FailureLine = ''
                ElapsedMs = [long]$watch.Elapsed.TotalMilliseconds
                AgentLines = @(); Offset = [long]$Offset
            }
        }
        Start-Sleep -Milliseconds 200
    }

    $watch.Stop()
    if (-not [string]::IsNullOrEmpty($failureLine)) {
        return [pscustomobject]@{
            Ok = $false; NoOp = $false; TimedOut = $false; FailureLine = [string]$failureLine
            ElapsedMs = [long]$watch.Elapsed.TotalMilliseconds
            AgentLines = @($agentLines.ToArray()); Offset = [long]$Offset
        }
    }
    return [pscustomobject]@{
        Ok = $false; NoOp = $false; TimedOut = $true; FailureLine = ''
        ElapsedMs = [long]$watch.Elapsed.TotalMilliseconds
        AgentLines = @($agentLines.ToArray()); Offset = [long]$Offset
    }
}

# 收尾：发 sa listFailed 并读取 2~3 秒回显，解析脚本失败行（形如 `[Failed] wayzer/ext/x`）。
function Invoke-HotReloadListFailedProbe {
    param(
        [Parameter(Mandatory = $true)]$Session,
        [int]$ReadMilliseconds = 2500,
        [AllowEmptyCollection()][string[]]$KnownIds = @()
    )
    Send-ConsoleCommand -Session $Session -Command 'sa listFailed'
    Write-Log ('已发送 sa listFailed，读取回显（最多 {0} 毫秒；无失败时通常只有 autosave 等无关行）...' -f $ReadMilliseconds)
    $echo = @(Read-ConsoleEcho -Session $Session -Milliseconds $ReadMilliseconds)
    $failedIds = New-Object System.Collections.Generic.List[string]
    $failureLines = New-Object System.Collections.Generic.List[string]
    foreach ($line in $echo) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        Write-Detail ('  [listFailed 回显] ' + $line)
        $state = ''
        $scriptId = ''
        if ($line -match '^\s*\[([^\]]+)\]\s+(.+?)\s*$') {
            $state = [string]$Matches[1]
            $scriptId = ([string]$Matches[2]).Split(' ')[0]
        } else {
            continue
        }
        $isFailureState = ($state -match '(?i)(fail|error|失败|异常|错误)')
        $isKnownId = (@($KnownIds) -contains $scriptId)
        $isOkState = ($state -match '(?i)(enabled|ok|success|成功)')
        if ($isFailureState -or ($isKnownId -and -not $isOkState)) {
            if (-not $failedIds.Contains($scriptId)) { $failedIds.Add($scriptId) | Out-Null }
            $failureLines.Add([string]$line) | Out-Null
        }
    }
    return [pscustomobject]@{
        Echo         = @($echo)
        FailedIds    = @($failedIds.ToArray())
        FailureLines = @($failureLines.ToArray())
    }
}

# ----------------------------------------------------------------------------
# 热重载执行（内部辅助 + 两阶段）
#
# 重要顺序（实测教训）：
#   运行中删除脚本必须「先 sa unload（此时源文件还在）→ 再严格镜像删文件」。
#   如果先镜像删了 .kts 文件再 sa unload，SA 事务会卡住 console socket（日志写了停止/卸载，
#   但 console 线程不再处理后续命令，游戏线程仍活着）。所以拆成两阶段：
#     阶段一（镜像前）：removed → 逐个 sa unload；
#     阶段二（镜像后）：added 先 sa scan，再 changed → added 逐个 sa load，最后 sa listFailed。
#   两阶段都逐脚本等待脚本加载日志确认完成（含级联依赖重启静默）。
# ----------------------------------------------------------------------------

# 在已打开的 socket 会话里按顺序逐个执行 actions（不包含 sa scan / sa listFailed）。
# 每发一条：记录日志 offset → 发送命令 → 等待完成标记 + 静默；失败/超时立即停止后续。
# 返回 { Completed, FailedAction, FailedReason, FailedTimedOut, Commands, ElapsedMs }
function Invoke-ReloadActionsInSession {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)]$Session,
        [AllowEmptyCollection()][object[]]$Actions = @(),
        [Parameter(Mandatory = $true)][int]$PerScriptTimeoutSeconds,
        [Parameter(Mandatory = $true)][int]$SettleMillis
    )
    $completed = New-Object System.Collections.Generic.List[object]
    $sentCommands = New-Object System.Collections.Generic.List[string]
    $failedAction = $null
    $failedReason = ''
    $failedTimedOut = $false
    $overall = [System.Diagnostics.Stopwatch]::StartNew()
    foreach ($action in @($Actions)) {
        $offset = Get-ReloadLogLength -Path $LogPath
        if ($offset -lt 0) {
            Write-Log ('脚本加载日志不存在或不可读：{0}，将从头开始读取。' -f $LogPath) 'WARN'
            $offset = [long]0
        }
        $scriptWatch = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            Send-ConsoleCommand -Session $Session -Command $action.Command
        } catch {
            $failedAction = $action
            $failedReason = $_.Exception.Message
            $failedTimedOut = $false
            break
        }
        $sentCommands.Add([string]$action.Command) | Out-Null
        $wait = Wait-ScriptReloadComplete -LogPath $LogPath -Offset $offset -ScriptId $action.Id -ActionKind $action.ActionKind `
            -TimeoutSeconds $PerScriptTimeoutSeconds -SettleMillis $SettleMillis
        $scriptWatch.Stop()
        $elapsedSeconds = [math]::Round($scriptWatch.Elapsed.TotalSeconds, 1)
        if ([bool]$wait.NoOp) {
            Write-Log ('  [NO-OP] {0}：命令发出后 {1} 秒内没有新的 [ScriptAgent] 日志行，按已完成处理（可能是内容未变，无需重载）。' -f $action.Command, $script:NoOpGraceSeconds) 'WARN'
            $completed.Add([pscustomobject]@{ Id = $action.Id; Kind = $action.Kind; Command = $action.Command; Seconds = $elapsedSeconds; Result = 'no-op' }) | Out-Null
            continue
        }
        if (-not [bool]$wait.Ok) {
            $failedAction = $action
            $failedTimedOut = [bool]$wait.TimedOut
            $failedReason = [string]$wait.FailureLine
            if ($failedTimedOut) {
                Write-Log ('  [TIMEOUT] {0}：等待 {1} 秒仍未出现完成标记（已读取 {2} 行脚本日志）。' -f $action.Command, $PerScriptTimeoutSeconds, @($wait.AgentLines).Count) 'ERROR'
            } else {
                Write-Log ('  [FAIL] {0}：{1}' -f $action.Command, $failedReason) 'ERROR'
            }
            break
        }
        Write-Log ('  [OK] {0}：完成（{1:N1} 秒，读取 {2} 行脚本日志）' -f $action.Command, $elapsedSeconds, @($wait.AgentLines).Count) 'OK'
        $completed.Add([pscustomobject]@{ Id = $action.Id; Kind = $action.Kind; Command = $action.Command; Seconds = $elapsedSeconds; Result = 'ok' }) | Out-Null
    }
    $overall.Stop()
    return [pscustomobject]@{
        Completed      = @($completed.ToArray())
        FailedAction   = $failedAction
        FailedReason   = [string]$failedReason
        FailedTimedOut = [bool]$failedTimedOut
        Commands       = @($sentCommands.ToArray())
        ElapsedMs      = [long]$overall.Elapsed.TotalMilliseconds
    }
}

# 阶段一（严格镜像之前）：对「将被严格镜像删除」的 removed 脚本先逐个 sa unload。
# 必须在文件还存在时 unload：实测对已删除源文件的脚本 unload 会让 SA 事务卡住 console socket。
# 失败/超时 → 中止更新，退出码 2；此时还没开始镜像，目标目录零改动。
# 返回 { Done, Attempted, ExitCode, Summary, Stage, Actions, Completed, FailedAction, Commands, ElapsedSeconds }
function Invoke-HotReloadPreUnload {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)]$Context,
        [AllowEmptyCollection()][string[]]$RemovedPaths = @(),
        [string]$Stage = '更新'
    )
    $actions = @(Get-HotReloadActionPlan -AddedPaths @() -ChangedPaths @() -RemovedPaths $RemovedPaths)
    $info = [pscustomobject]@{
        Done = $false; Attempted = $false; ExitCode = 0; Summary = ''; Stage = $Stage
        Actions = $actions; Completed = @(); FailedAction = $null; Commands = @(); ElapsedSeconds = 0.0
    }
    if ($actions.Count -eq 0) {
        Write-Log '没有需要先卸载的脚本（本次没有删除 .kts 脚本），跳过镜像前卸载阶段。'
        return $info
    }
    $perScriptTimeout = [int]$Config.PerScriptTimeoutSeconds
    $settleMillis = [int]$Config.ReloadSettleMillis
    $logPath = [string]$Config.ReloadLogPath
    Write-Step ('运行中热重载 · 阶段一：镜像前卸载将删除的脚本（{0}）' -f $Stage)
    Write-Log ('console socket：{0}:{1}；脚本加载日志：{2}' -f $Config.ConsoleSocketHost, $Config.ConsoleSocketPort, $logPath)
    Write-Log ('先逐个 sa unload（此时文件还存在）：{0}' -f (($actions | ForEach-Object { $_.Command }) -join ' → '))
    Write-Log ('节奏：每个脚本等日志出现完成标记后，再等 {0} 毫秒静默（等依赖级联重启写完）才发下一条；单脚本超时 {1} 秒。' -f $settleMillis, $perScriptTimeout)
    $session = $null
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        try {
            $session = Open-ConsoleSocketSession -Config $Config
        } catch {
            $message = $_.Exception.Message
            Write-Log ('打开 console socket 会话失败：{0}' -f $message) 'ERROR'
            Write-Log '已在改动任何文件之前中止（目标目录零改动）。' 'WARN'
            Write-Log '处理建议：检查 server.properties 的 socketInput/socketInputPort/socketInputAddress；或先停服更新。' 'WARN'
            $info.Done = $true
            $info.ExitCode = 2
            $info.Summary = ('镜像前卸载阶段无法连接 console socket：{0}；已中止，目标目录零改动。' -f $message)
            return $info
        }
        $info.Attempted = $true
        $result = Invoke-ReloadActionsInSession -Config $Config -LogPath $logPath -Session $session -Actions $actions `
            -PerScriptTimeoutSeconds $perScriptTimeout -SettleMillis $settleMillis
        $info.Commands = @($result.Commands)
        $info.Completed = @($result.Completed)
        $info.FailedAction = $result.FailedAction
        if ($null -ne $result.FailedAction) {
            Write-Log ''
            if (@($result.Completed).Count -gt 0) {
                Write-Log ('已成功卸载（{0} 个）：{1}' -f @($result.Completed).Count, ((@($result.Completed) | ForEach-Object { $_.Id }) -join ', '))
            }
            $why = $(if ([bool]$result.FailedTimedOut) { '等待完成标记超时' } else { $result.FailedReason })
            Write-Log ('卸载失败/未完成（1 个）：{0}（{1}）' -f $result.FailedAction.Command, $why) 'ERROR'
            Write-Log '已停止后续操作，且目标目录零改动（还没开始严格镜像）。' 'WARN'
            Write-Log '处理建议：已执行的 unload 可能已生效；请重跑本更新器（会重新比对），或重启服务器后再更新。' 'WARN'
            $info.Done = $true
            $info.ExitCode = 2
            $info.Summary = ('镜像前卸载失败：{0}（{1}）；已中止，目标目录零改动；请重跑或重启服务器。' -f $result.FailedAction.Id, $why)
            return $info
        }
        $watch.Stop()
        $info.ElapsedSeconds = [math]::Round($watch.Elapsed.TotalSeconds, 1)
        Write-Log ('镜像前卸载完成：{0} 个将删除的脚本已 unload（{1:N1} 秒），接下来执行严格镜像。' -f $actions.Count, $info.ElapsedSeconds) 'OK'
        $info.Done = $true
        $info.ExitCode = 0
        $info.Summary = ('镜像前卸载完成（{0} 个脚本）' -f $actions.Count)
        return $info
    } finally {
        $watch.Stop()
        if ($null -ne $session) { Close-ConsoleSocketSession -Session $session }
    }
}

# 阶段二（严格镜像之后）：若本次有 added 或 removed，先发一次 sa scan（发现新脚本 + 清理已删除
# 脚本的注册表；added 与 removed 同时存在也只发一次，等 1.5 秒）；再逐个 sa load（changed → added），
# 最后 sa listFailed 读取 2~3 秒回显。失败/超时/listFailed 非空 → 退出码 3（文件已更新）。
# CheckListFailed：即使本次没有新增/修改脚本（例如只删除了脚本），也执行 sa listFailed 收尾检查。
# 返回 { Done, Attempted, ExitCode, Summary, Stage, Actions, Completed, FailedAction, FailedIds, Echo, Commands, ElapsedSeconds }
function Invoke-HotReloadPostLoad {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)]$Context,
        [AllowEmptyCollection()][string[]]$AddedPaths = @(),
        [AllowEmptyCollection()][string[]]$ChangedPaths = @(),
        [int]$RemovedScriptCount = 0,
        [bool]$CheckListFailed = $false,
        [string]$Stage = '更新'
    )
    $actions = @(Get-HotReloadActionPlan -AddedPaths $AddedPaths -ChangedPaths $ChangedPaths -RemovedPaths @())
    $addedIds = @(Get-HotReloadScriptIds -RelPaths $AddedPaths)
    $scanNeeded = ($addedIds.Count -gt 0 -or $RemovedScriptCount -gt 0)
    $info = [pscustomobject]@{
        Done = $false; Attempted = $false; ExitCode = 0; Summary = ''; Stage = $Stage
        Actions = $actions; Completed = @(); FailedAction = $null; FailedIds = @(); Echo = @()
        Commands = @(); ElapsedSeconds = 0.0
    }
    if ($actions.Count -eq 0 -and -not $CheckListFailed) {
        Write-Log '没有需要热加载的 .kts 脚本（本次没有新增/修改脚本），跳过镜像后加载阶段。' 'OK'
        return $info
    }
    $perScriptTimeout = [int]$Config.PerScriptTimeoutSeconds
    $settleMillis = [int]$Config.ReloadSettleMillis
    $logPath = [string]$Config.ReloadLogPath
    Write-Step ('运行中热重载 · 阶段二：镜像后加载变更脚本（{0}）' -f $Stage)
    Write-Log ('console socket：{0}:{1}；脚本加载日志：{2}' -f $Config.ConsoleSocketHost, $Config.ConsoleSocketPort, $logPath)
    if ($actions.Count -gt 0) {
        Write-Log ('镜像已完成，接下来逐个 sa load（changed → added）：{0}' -f (($actions | ForEach-Object { $_.Command }) -join ' → '))
        Write-Log ('节奏：每个脚本等日志出现完成标记后，再等 {0} 毫秒静默（等依赖级联重启写完）才发下一条；单脚本超时 {1} 秒。' -f $settleMillis, $perScriptTimeout)
    }
    $session = $null
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        try {
            $session = Open-ConsoleSocketSession -Config $Config
        } catch {
            $message = $_.Exception.Message
            Write-Log ('打开 console socket 会话失败：{0}' -f $message) 'ERROR'
            Write-Log '脚本文件已更新，但运行中的服务器没有收到热加载命令。' 'ERROR'
            Write-Log '处理建议：检查 socket 配置；或在服务器控制台执行 sa load，或重启服务器。' 'WARN'
            $info.Done = $true
            $info.ExitCode = 3
            $info.Summary = ('连接 console socket 失败：{0}；文件已更新，请 sa load 或重启服务器。' -f $message)
            return $info
        }
        $info.Attempted = $true
        $sentCommands = New-Object System.Collections.Generic.List[string]
        if ($scanNeeded) {
            Send-ConsoleCommand -Session $session -Command 'sa scan'
            $sentCommands.Add('sa scan') | Out-Null
            Write-Log '  存在新增/删除脚本：已发 sa scan（发现新脚本 + 清理已删除脚本的注册表），等待 1.5 秒。'
            Start-Sleep -Milliseconds 1500
        }
        $result = Invoke-ReloadActionsInSession -Config $Config -LogPath $logPath -Session $session -Actions $actions `
            -PerScriptTimeoutSeconds $perScriptTimeout -SettleMillis $settleMillis
        foreach ($command in @($result.Commands)) { $sentCommands.Add([string]$command) | Out-Null }
        $info.Commands = @($sentCommands.ToArray())
        $info.Completed = @($result.Completed)
        $info.FailedAction = $result.FailedAction
        if ($null -ne $result.FailedAction) {
            Write-Log ''
            if (@($result.Completed).Count -gt 0) {
                Write-Log ('已完成（{0} 个）：{1}' -f @($result.Completed).Count, ((@($result.Completed) | ForEach-Object { $_.Id }) -join ', '))
            }
            $why = $(if ([bool]$result.FailedTimedOut) { '等待完成标记超时' } else { $result.FailedReason })
            Write-Log ('失败/未完成（1 个）：{0}（{1}）' -f $result.FailedAction.Command, $why) 'ERROR'
            Write-Log '已停止后续脚本命令（避免级联重启互相踩踏）；脚本文件已更新。' 'WARN'
            Write-Log '处理建议：1) 查看 script-load\current.log 的失败原因；2) 服务器控制台执行 sa listFailed / sa load；3) 修复后仍不行就重启服务器。' 'WARN'
            $info.Done = $true
            $info.ExitCode = 3
            $info.FailedIds = @($result.FailedAction.Id)
            if ([bool]$result.FailedTimedOut) {
                $info.Summary = ('运行中热重载超时：{0} 在 {1} 秒内未完成；已完成 {2} 个；文件已更新，请 sa load 或重启服务器。' -f $result.FailedAction.Id, $perScriptTimeout, @($result.Completed).Count)
            } else {
                $info.Summary = ('运行中热重载失败：{0}（{1}）；已完成 {2} 个；文件已更新，请 sa load 或重启服务器。' -f $result.FailedAction.Id, $why, @($result.Completed).Count)
            }
            return $info
        }
        $knownIds = @($actions | ForEach-Object { $_.Id })
        $probe = Invoke-HotReloadListFailedProbe -Session $session -KnownIds $knownIds
        $info.Echo = @($probe.Echo)
        if (@($probe.FailedIds).Count -gt 0) {
            Write-Log ('sa listFailed 显示仍有失败脚本（{0} 个）：{1}' -f @($probe.FailedIds).Count, (@($probe.FailedIds) -join ', ')) 'ERROR'
            Write-Log '脚本文件已更新，但运行中的服务器仍有脚本加载失败。' 'ERROR'
            Write-Log '处理建议：查看 script-load\current.log 与上面的回显；修复后在服务器控制台执行 sa load，或重启服务器。' 'WARN'
            $info.Done = $true
            $info.ExitCode = 3
            $info.FailedIds = @($probe.FailedIds)
            $info.Summary = ('运行中热重载后 sa listFailed 仍有失败脚本：{0}；文件已更新，请修复后 sa load 或重启服务器。' -f (@($probe.FailedIds) -join ', '))
            return $info
        }
        $watch.Stop()
        $info.ElapsedSeconds = [math]::Round($watch.Elapsed.TotalSeconds, 1)
        Write-Log ('镜像后加载完成：changed {0} / added {1}，用时 {2:N1} 秒；sa listFailed 未发现失败脚本。' -f `
            (@(Get-HotReloadScriptIds -RelPaths $ChangedPaths)).Count, $addedIds.Count, $info.ElapsedSeconds) 'OK'
        $info.Done = $true
        $info.ExitCode = 0
        $info.Summary = ('运行中热加载完成（changed {0} / added {1}，用时 {2:N1} 秒）' -f (@(Get-HotReloadScriptIds -RelPaths $ChangedPaths)).Count, $addedIds.Count, $info.ElapsedSeconds)
        return $info
    } finally {
        $watch.Stop()
        if ($null -ne $session) { Close-ConsoleSocketSession -Session $session }
    }
}

# 服务器运行中 / 热重载可用性预检。
# 语义（优先级从高到低）：
#   1) 服务器未运行 → 普通停服流程（退出码 0），不连 socket、不做热重载检查；
#   2) 服务器运行中 + 操作支持热重载 + hotReloadMode=socket/auto
#      + console socket 可连接 + reloadLogPath 存在 → 镜像后走 socket 逐个热重载（0 或 3）；
#   3) 不可用/关闭 → 回退 requireServerStopped：true=拒绝（退出码 2），
#      false=按旧行为在线更新（不推荐，需重启生效）；
#   4) hotReloadMode=socket 是强制模式：socket/日志不可用时一律拒绝，即使 requireServerStopped=false。
function Initialize-HotReloadContext {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)]$Settings,
        [Parameter(Mandatory = $true)][hashtable]$Cli,
        [bool]$IsRollback = $false
    )
    $context = [pscustomobject]@{
        Applicable             = (-not $Settings.IsWeb)
        Supported              = [bool]$Settings.SupportsHotReload
        Mode                   = [string]$Config.HotReloadMode
        ServerRunning          = $false
        ServerState            = $null
        Socket                 = $null
        SocketAvailable        = $false
        LogPath                = [string]$Config.ReloadLogPath
        LogAvailable           = $false
        UseHotReload           = $false
        DryRun                 = [bool]$Cli.DryRun
        IsRollback             = $IsRollback
        ForcedWhileRunning     = $false
        RefusalReason          = ''
        PerScriptTimeoutSeconds = [int]$Config.PerScriptTimeoutSeconds
        ReloadSettleMillis     = [int]$Config.ReloadSettleMillis
        NoOpGraceSeconds       = $script:NoOpGraceSeconds
    }
    if ($Settings.IsWeb) { return $context }
    $state = Get-ServerRunState -Config $Config
    $context.ServerState = $state
    $context.ServerRunning = [bool]$state.Running
    if (-not $state.Running) {
        Write-Log '服务器未运行：按停服更新流程执行，脚本在下次启动服务器时加载（不需要热重载）。' 'OK'
        return $context
    }
    Write-Log '服务器正在运行：检查运行中热重载（console socket + 脚本加载日志）可用性。'
    foreach ($line in @($state.Evidence)) {
        if ($null -eq $line) { continue }
        Write-Log ([string]$line) 'WARN'
    }
    $dryRunNote = ''
    if ($context.DryRun) { $dryRunNote = '（-DryRun 只预览，正式执行时才会真正处理）' }

    if (-not $context.Supported) {
        $reason = '当前操作不支持运行中热重载（清缓存版会清空编译缓存，必须停服强制重新编译）'
        Write-Log ('{0}。{1}' -f $reason, $dryRunNote) 'WARN'
        if (-not [bool]$Settings.RequireServerStopped) {
            Write-Log '配置 requireServerStopped=false：继续执行（不推荐在服务器运行中更新文件！）' 'WARN'
            $context.ForcedWhileRunning = $true
            return $context
        }
        if ($context.DryRun) {
            $context.RefusalReason = $reason
            Write-Log 'DryRun：正式执行将拒绝执行（退出码 2）。' 'WARN'
            return $context
        }
        $hints = @(
            ('{0}，且 config.json 的 requireServerStopped=true。' -f $reason),
            '请先关闭服务器后重试；如果确实需要在运行中更新（不推荐），可临时设置 requireServerStopped=false。'
        )
        throw ('REFUSED: ' + ((Get-ServerRunningMessage -State $state -Hints $hints) -join [Environment]::NewLine))
    }

    if ($context.Mode -eq 'off') {
        $reason = '运行中热重载已关闭（hotReloadMode=off / -NoHotReload）'
        Write-Log ('{0}。{1}' -f $reason, $dryRunNote) 'WARN'
        if (-not [bool]$Settings.RequireServerStopped) {
            Write-Log '配置 requireServerStopped=false：继续执行（不推荐在服务器运行中更新文件；更新后需重启服务器生效）。' 'WARN'
            $context.ForcedWhileRunning = $true
            return $context
        }
        if ($context.DryRun) {
            $context.RefusalReason = $reason
            Write-Log 'DryRun：正式执行将拒绝执行（退出码 2）。' 'WARN'
            return $context
        }
        $hints = @(
            ('{0}，且 config.json 的 requireServerStopped=true。' -f $reason),
            '请先关闭服务器后重试；或删除 -NoHotReload 使用默认的 socket 热重载。'
        )
        throw ('REFUSED: ' + ((Get-ServerRunningMessage -State $state -Hints $hints) -join [Environment]::NewLine))
    }

    # socket / auto：只读探测 console socket + 检查脚本加载日志是否存在
    $socketInfo = Test-ConsoleSocketAvailable -Config $Config
    $context.Socket = $socketInfo
    $context.SocketAvailable = [bool]$socketInfo.Available
    $context.LogAvailable = Test-Path -LiteralPath ([string]$Config.ReloadLogPath) -PathType Leaf
    $problems = New-Object System.Collections.Generic.List[string]
    if (-not $context.SocketAvailable) {
        $problems.Add(('console socket 不可达（{0}）' -f $socketInfo.Reason)) | Out-Null
    }
    if (-not $context.LogAvailable) {
        $problems.Add(('脚本加载日志不存在（{0}）' -f [string]$Config.ReloadLogPath)) | Out-Null
    }
    if ($problems.Count -eq 0) {
        $context.UseHotReload = $true
        Write-Log ('console socket 可连接：{0}:{1}' -f $socketInfo.Host, $socketInfo.Port) 'OK'
        $logLength = Get-ReloadLogLength -Path ([string]$Config.ReloadLogPath)
        Write-Log ('脚本加载日志可用：{0}（当前 {1} 字节）' -f [string]$Config.ReloadLogPath, $logLength) 'OK'
        if ($context.DryRun) { Write-Log 'DryRun：正式执行时会在严格镜像后逐个发送 sa 命令，并尾随脚本加载日志确认完成（本次不发命令、不改文件）。' }
        return $context
    }

    $reason = ($problems -join '；')
    Write-Log ('运行中热重载不可用：{0}。{1}' -f $reason, $dryRunNote) 'WARN'
    if ($context.DryRun) {
        $context.RefusalReason = $reason
        Write-Log 'DryRun：正式执行将拒绝执行（退出码 2）。' 'WARN'
        return $context
    }
    $refuseWhileRunning = ($context.Mode -eq 'socket' -or [bool]$Settings.RequireServerStopped)
    if (-not $refuseWhileRunning) {
        Write-Log '运行中热重载不可用，但 config.json 的 requireServerStopped=false：将按旧行为在线更新文件（不推荐；更新后需重启服务器才会生效）。' 'WARN'
        $context.ForcedWhileRunning = $true
        return $context
    }
    $hints = @(
        ('console socket：{0}:{1}；脚本加载日志：{2}' -f [string]$Config.ConsoleSocketHost, [int]$Config.ConsoleSocketPort, [string]$Config.ReloadLogPath),
        '请确认 server.properties 已配置：socketInput=true、socketInputPort=<端口>、socketInputAddress=localhost，并重启服务器使配置生效。',
        '首次使用需先停服更新一次（把 updater 换新、确认脚本加载日志路径），之后即可运行中更新；或加 -NoHotReload 关闭热重载、停服更新。'
    )
    if ($context.Mode -eq 'socket') {
        $hints += '当前 hotReloadMode=socket（强制 socket 模式）：socket/日志不可用时一律拒绝运行中更新。'
    }
    throw ('REFUSED: ' + ((Get-ServerRunningMessage -State $state -Hints $hints) -join [Environment]::NewLine))
}

# -DryRun 预览：说明正式执行时会「逐个发送哪些 sa 命令」还是「拒绝执行」。
function Show-HotReloadPreview {
    param(
        [Parameter(Mandatory = $true)]$Analysis,
        [AllowNull()]$Context
    )
    Write-Step '运行中热重载评估（-DryRun 预览：不发 sa 命令、不改文件）'
    if ($null -eq $Context -or -not [bool]$Context.Applicable) {
        Write-Log '当前操作不涉及运行中热重载。'
        return
    }
    if (-not [bool]$Context.ServerRunning) {
        Write-Log '服务器未运行：正式执行将按停服流程备份并严格镜像（退出码 0），脚本在下次启动服务器时加载。'
        return
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$Context.RefusalReason)) {
        Write-Log ('服务器运行中且当前配置将拒绝执行（正式执行退出码 2）：{0}' -f $Context.RefusalReason) 'WARN'
        Write-Log '如需运行中更新：按 README 配置 server.properties 的 socketInput/socketInputPort/socketInputAddress；否则请先关闭服务器，或用 -NoHotReload。' 'WARN'
        return
    }
    if ([bool]$Context.ForcedWhileRunning) {
        Write-Log '服务器运行中、热重载不可用/关闭，但 config.json 的 requireServerStopped=false：正式执行将按旧行为在线更新文件（不推荐；更新后需重启服务器才会生效）。' 'WARN'
        return
    }
    if (-not [bool]$Context.UseHotReload) {
        Write-Log '未启用运行中热重载，正式执行按停服流程处理。'
        return
    }
    $allChanges = @()
    $allChanges += @($Analysis.Added)
    $allChanges += @($Analysis.Modified)
    $allChanges += @($Analysis.Deleted)
    $nonScript = @(Get-NonScriptRelPaths -RelPaths $allChanges)
    if ($nonScript.Count -gt 0) {
        Write-Log ('正式执行将拒绝（退出码 2）：本次变更包含 {0} 个非 .kts 文件（.kt 模块库等），运行中无法热重载，必须停服更新（会在改动文件前中止）。' -f $nonScript.Count) 'WARN'
        Write-FileList -Title '非 .kts 变更（需要停服）' -Files $nonScript
        return
    }
    $removedIds = @(Get-HotReloadScriptIds -RelPaths @($Analysis.Deleted))
    $changedIds = @(Get-HotReloadScriptIds -RelPaths @($Analysis.Modified))
    $addedIds = @(Get-HotReloadScriptIds -RelPaths @($Analysis.Added))
    $scanNeeded = ($removedIds.Count -gt 0 -or $addedIds.Count -gt 0)
    $unloadCommands = @($removedIds | ForEach-Object { 'sa unload ' + $_ })
    $loadCommands = @()
    $loadCommands += @($changedIds | ForEach-Object { 'sa load ' + $_ })
    $loadCommands += @($addedIds | ForEach-Object { 'sa load ' + $_ })
    Write-Log '正式执行将按以下顺序（DryRun 不发任何 sa 命令、不改文件）：'
    Write-Log ('  ① 镜像前：先逐个 sa unload 将被删除的脚本（removed {0} 个，此时文件还在）：{1}' -f $removedIds.Count, $(if ($unloadCommands.Count -gt 0) { $unloadCommands -join ' → ' } else { '（无）' }))
    Write-Log '  ② 严格镜像（备份 → 写入/覆盖 → 删除仓库中已不存在的文件）'
    Write-Log ('  ③ 镜像后：{0}逐个 sa load（changed {1} 个 → added {2} 个）：{3}' -f $(if ($scanNeeded) { '先 sa scan（等 1.5 秒，清理已删脚本注册表/发现新脚本），然后' } else { '' }), $changedIds.Count, $addedIds.Count, $(if ($loadCommands.Count -gt 0) { $loadCommands -join ' → ' } else { '（无）' }))
    Write-Log '  ④ sa listFailed 收尾（阶段二失败/超时退出码 3；阶段一 unload 失败退出码 2 且目标零改动）'
    Write-Log ('  每个脚本：等日志出现完成标记后，再等 {0} 毫秒静默才发下一条；单脚本超时 {1} 秒；完全没有日志行时 {2} 秒后按 no-op 继续。' -f $Context.ReloadSettleMillis, $Context.PerScriptTimeoutSeconds, $Context.NoOpGraceSeconds)
    Write-Log ('  console socket：{0}:{1}；脚本加载日志：{2}' -f [string]$Context.Socket.Host, [int]$Context.Socket.Port, [string]$Context.LogPath)
    Write-Log '  DryRun 只做只读探测：不发任何 sa 命令、不改目标文件。'
}

function Get-WebRunningHint {
    param([Parameter(Mandatory = $true)]$Config)
    $lines = New-Object System.Collections.Generic.List[string]
    $webPort = [int]$Config.WebPort
    if ($webPort -gt 0) {
        $pids = @(Get-ListeningOwnerPids -Port $webPort)
        if ($pids.Count -gt 0) {
            $lines.Add(('检测到 Web 正在运行（端口 {0}，PID={1}）。更新后需要重启 Web 进程才会生效。' -f $webPort, ($pids -join ','))) | Out-Null
        } else {
            $lines.Add(('未检测到 Web 监听端口 {0}；更新后启动 Web 即生效。' -f $webPort)) | Out-Null
        }
    } else {
        $lines.Add('更新后需要重启 Web 进程才会生效（关闭 start-web 窗口后重新运行 start-web.bat）。') | Out-Null
    }
    try {
        $webProcs = @(Get-CimInstance -ClassName Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'pwsh.exe'" -ErrorAction Stop |
            Where-Object { [string]$_.CommandLine -match '(?i)start-web\.ps1' })
        foreach ($process in $webProcs) {
            $lines.Add(('找到正在运行的 Web 进程：PID={0}，命令行：{1}' -f [int]$process.ProcessId, (Format-CommandLineForLog -CommandLine ([string]$process.CommandLine)))) | Out-Null
        }
    } catch { }
    return $lines.ToArray()
}

# ----------------------------------------------------------------------------
# 缓存清理
# ----------------------------------------------------------------------------

function Resolve-CachePath {
    param([Parameter(Mandatory = $true)]$Config, [Parameter(Mandatory = $true)]$Settings)
    if ($Settings.IsWeb) { return '' }
    $cachePath = [string]$Config.CachePath
    if ([string]::IsNullOrWhiteSpace($cachePath)) {
        $cachePath = Join-Path $Settings.TargetPath 'cache'
    }
    return [System.IO.Path]::GetFullPath($cachePath).TrimEnd('\')
}

function Assert-CachePathSafe {
    param(
        [Parameter(Mandatory = $true)][string]$CachePath,
        [Parameter(Mandatory = $true)][string]$TargetPath,
        [Parameter(Mandatory = $true)][string]$BackupPath
    )
    if ([string]::IsNullOrWhiteSpace($CachePath)) { throw '缓存路径为空。' }
    $cacheFull = [System.IO.Path]::GetFullPath($CachePath).TrimEnd('\')
    $root = [System.IO.Path]::GetPathRoot($cacheFull)
    if ($cacheFull -ieq $root.TrimEnd('\')) {
        throw ('REFUSED: 缓存路径不能是磁盘根目录：{0}' -f $cacheFull)
    }
    # 注意：缓存目录默认就位于 <serverScriptsPath>\cache（目标目录内部），这是正常配置，不能拒绝；
    # 只需拒绝：等于目标/备份目录、是它们的上级目录（会误删脚本或备份）。
    foreach ($danger in @($TargetPath, $BackupPath)) {
        if ([string]::IsNullOrWhiteSpace($danger)) { continue }
        $dangerFull = [System.IO.Path]::GetFullPath($danger).TrimEnd('\')
        if ($cacheFull -ieq $dangerFull) {
            throw ('REFUSED: 缓存路径与目标/备份目录相同，拒绝清理：{0}' -f $cacheFull)
        }
        if ($dangerFull.StartsWith($cacheFull + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
            throw ('REFUSED: 缓存路径是目标/备份目录的上级目录（会误删），拒绝清理：{0}' -f $cacheFull)
        }
    }
}

function Get-PathContentStats {
    param([Parameter(Mandatory = $true)][string]$Path)
    $files = 0
    $bytes = 0
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        return [pscustomobject]@{ Exists = $false; Files = 0; Bytes = 0 }
    }
    foreach ($item in (Get-ChildItem -LiteralPath $Path -Force -ErrorAction SilentlyContinue)) {
        if ($item.PSIsContainer) {
            foreach ($file in (Get-ChildItem -LiteralPath $item.FullName -Recurse -File -Force -ErrorAction SilentlyContinue)) {
                $files++
                $bytes += $file.Length
            }
        } else {
            $files++
            $bytes += $item.Length
        }
    }
    return [pscustomobject]@{ Exists = $true; Files = $files; Bytes = $bytes }
}

function Clear-PathContents {
    param([Parameter(Mandatory = $true)][string]$Path)
    $stats = Get-PathContentStats -Path $Path
    if (-not $stats.Exists) { return $stats }
    foreach ($item in (Get-ChildItem -LiteralPath $Path -Force -ErrorAction Stop)) {
        if ($item.PSIsContainer) {
            Remove-Item -LiteralPath $item.FullName -Recurse -Force -ErrorAction Stop
        } else {
            if ($item.IsReadOnly) { $item.IsReadOnly = $false }
            Remove-Item -LiteralPath $item.FullName -Force -ErrorAction Stop
        }
    }
    return $stats
}

# ----------------------------------------------------------------------------
# 备份 / 恢复 / 保留份数
# ----------------------------------------------------------------------------

function New-TargetBackup {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)]$Settings,
        [AllowNull()]$PreviousMarker
    )
    $backupRoot = $Settings.BackupRoot
    New-Directory -Path $backupRoot
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupDir = Join-Path $backupRoot $timestamp
    while (Test-Path -LiteralPath $backupDir) {
        Start-Sleep -Milliseconds 1100
        $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $backupDir = Join-Path $backupRoot $timestamp
    }
    New-Directory -Path $backupDir

    $all = @(Get-RelativeFiles -Root $Settings.TargetPath)
    $toBackup = New-Object System.Collections.Generic.List[string]
    $protectedCount = 0
    foreach ($rel in $all) {
        if (Test-IsProtectedPath -RelativePath $rel -ProtectedDirs $Settings.ProtectedDirs -ProtectedFiles $Settings.ProtectedFiles) {
            $protectedCount++
        } else {
            $toBackup.Add($rel) | Out-Null
        }
    }

    $copied = 0
    $totalBytes = 0
    $failures = New-Object System.Collections.Generic.List[string]
    foreach ($rel in $toBackup) {
        try {
            $source = Join-Path $Settings.TargetPath $rel
            $destination = Join-Path $backupDir $rel
            Copy-FileForce -Source $source -Destination $destination
            $copied++
            $totalBytes += (Get-Item -LiteralPath $source).Length
        } catch {
            $failures.Add(('{0} — {1}' -f $rel, $_.Exception.Message)) | Out-Null
        }
    }
    if ($failures.Count -gt 0) {
        Remove-Item -LiteralPath $backupDir -Recurse -Force -ErrorAction SilentlyContinue
        throw ('备份失败（{0}/{1} 个文件无法复制），已中止更新，目标目录没有做任何修改。' + [Environment]::NewLine + '第一个失败：' + $failures[0])
    }

    Write-Log ('备份完成：{0}（{1} 个文件，{2}）' -f $backupDir, $copied, (Format-Bytes -Bytes $totalBytes)) 'OK'
    Write-Detail ('备份范围：目标目录中参与镜像的文件（受保护目录/文件不备份，因为更新不会改动它们；受保护 {0} 个）。' -f $protectedCount)

    $metaPath = Join-Path $backupRoot ('.meta-' + $timestamp + '.json')
    $meta = [ordered]@{
        schema            = 1
        timestamp         = $timestamp
        kind              = $Settings.Kind
        createdAt         = (Get-LogTimestamp)
        repo              = [string]$Config.Repo
        branch            = [string]$Config.Branch
        targetPath        = $Settings.TargetPath
        backupFileCount   = $copied
        backupTotalBytes  = $totalBytes
        protectedCount    = $protectedCount
        previousVersion   = $PreviousMarker
        plannedAdded      = @()
        plannedModified   = @()
        plannedDeleted    = @()
        plannedUnchanged  = 0
        cacheClearedFiles = 0
        deployedCommitSha = ''
        commitDate        = ''
        status            = 'backup-done'
    }
    Save-JsonFile -Path $metaPath -Data $meta
    return [pscustomobject]@{
        Timestamp  = $timestamp
        Dir        = $backupDir
        MetaPath   = $metaPath
        FileCount  = $copied
        TotalBytes = $totalBytes
        Meta       = $meta
    }
}

function Read-BackupMeta {
    param([Parameter(Mandatory = $true)][string]$MetaPath)
    if (-not (Test-Path -LiteralPath $MetaPath -PathType Leaf)) { return $null }
    try {
        return (Read-JsonFile -Path $MetaPath -What '备份元数据')
    } catch {
        Write-Log ('备份元数据损坏（将按无元数据处理）：{0}' -f $_.Exception.Message) 'WARN'
        return $null
    }
}

function Remove-OldBackups {
    param(
        [Parameter(Mandatory = $true)][string]$BackupRoot,
        [Parameter(Mandatory = $true)][int]$Keep,
        [Parameter(Mandatory = $true)][string]$CurrentTimestamp
    )
    if (-not (Test-Path -LiteralPath $BackupRoot -PathType Container)) { return }
    $backups = @(Get-ChildItem -LiteralPath $BackupRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d{8}-\d{6}$' } |
        Sort-Object Name -Descending)
    if ($backups.Count -le $Keep) {
        Write-Log ('备份保留检查：现有 {0} 份，未超过上限 {1} 份。' -f $backups.Count, $Keep)
        return
    }
    foreach ($dir in @($backups | Select-Object -Skip $Keep)) {
        try {
            Remove-Item -LiteralPath $dir.FullName -Recurse -Force -ErrorAction Stop
            Remove-Item -LiteralPath (Join-Path $BackupRoot ('.meta-' + $dir.Name + '.json')) -Force -ErrorAction SilentlyContinue
            Write-Log ('已删除旧备份（超出保留份数）：{0}' -f $dir.Name)
        } catch {
            Write-Log ('删除旧备份失败（忽略）：{0} — {1}' -f $dir.Name, $_.Exception.Message) 'WARN'
        }
    }
    Write-Log ('备份保留：最近 {0} 份（本次 {1}）。' -f $Keep, $CurrentTimestamp)
}

function Restore-BackupFiles {
    param(
        [Parameter(Mandatory = $true)][string]$BackupDir,
        [Parameter(Mandatory = $true)][string]$TargetRoot,
        [AllowEmptyCollection()][string[]]$AddedRelPaths = @()
    )
    $restored = 0
    $backupFull = (Get-Item -LiteralPath $BackupDir).FullName.TrimEnd('\')
    $prefixLength = $backupFull.Length + 1
    foreach ($file in (Get-ChildItem -LiteralPath $backupFull -Recurse -File -ErrorAction Stop)) {
        $rel = $file.FullName.Substring($prefixLength)
        $destination = Join-Path $TargetRoot $rel
        Copy-FileForce -Source $file.FullName -Destination $destination
        $restored++
    }
    $deleted = 0
    foreach ($rel in @($AddedRelPaths)) {
        if ([string]::IsNullOrWhiteSpace([string]$rel)) { continue }
        $destination = Join-Path $TargetRoot ([string]$rel)
        if (Test-Path -LiteralPath $destination -PathType Leaf) {
            Remove-Item -LiteralPath $destination -Force
            $deleted++
        }
    }
    return [pscustomobject]@{ Restored = $restored; Deleted = $deleted }
}

# ----------------------------------------------------------------------------
# 严格镜像应用 / 校验
# ----------------------------------------------------------------------------

function Apply-Mirror {
    param(
        [Parameter(Mandatory = $true)]$Analysis,
        [Parameter(Mandatory = $true)]$Settings
    )
    $targetRoot = $Settings.TargetPath
    $copied = 0
    $all = @()
    $all += @($Analysis.Added)
    $all += @($Analysis.Modified)
    foreach ($rel in $all) {
        Copy-FileForce -Source (Join-Path $Analysis.SourceRoot $rel) -Destination (Join-Path $targetRoot $rel)
        $copied++
        if ($copied % 50 -eq 0) { Write-Detail ('已复制 {0}/{1} 个文件...' -f $copied, $all.Count) }
    }

    # 复制后校验（防止复制损坏/被占用导致静默失败）
    $bad = New-Object System.Collections.Generic.List[string]
    foreach ($rel in $all) {
        $destination = Join-Path $targetRoot $rel
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
            $bad.Add(($rel + '（复制后不存在）')) | Out-Null
            continue
        }
        $expected = [string]$Analysis.SourceHash[$rel]
        $actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA256 -ErrorAction Stop).Hash
        if ($actual -ne $expected) { $bad.Add(($rel + '（哈希不一致）')) | Out-Null }
    }
    if ($bad.Count -gt 0) {
        throw ('复制后校验失败（{0} 个文件）：{1}' -f $bad.Count, ($bad -join '; '))
    }

    # 严格镜像：删除仓库中已不存在的文件
    $targetFull = (Get-Item -LiteralPath $targetRoot).FullName.TrimEnd('\')
    $deleted = 0
    foreach ($rel in @($Analysis.Deleted)) {
        $full = [System.IO.Path]::GetFullPath((Join-Path $targetFull ([string]$rel)))
        if (-not $full.StartsWith($targetFull + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
            throw ('拒绝删除目标目录之外的路径：{0}' -f $full)
        }
        if (Test-Path -LiteralPath $full -PathType Leaf) {
            Remove-Item -LiteralPath $full -Force
            $deleted++
            Write-Detail ('  - 删除 ' + $rel)
        }
    }

    $removedDirs = Remove-EmptyTargetDirectories -TargetRoot $targetFull -Settings $Settings
    return [pscustomobject]@{ Copied = $copied; Deleted = $deleted; RemovedEmptyDirs = $removedDirs }
}

function Remove-EmptyTargetDirectories {
    param(
        [Parameter(Mandatory = $true)][string]$TargetRoot,
        [Parameter(Mandatory = $true)]$Settings
    )
    $rootFull = (Get-Item -LiteralPath $TargetRoot).FullName.TrimEnd('\')
    $removed = 0
    $dirs = @(Get-ChildItem -LiteralPath $rootFull -Recurse -Directory -ErrorAction SilentlyContinue | Sort-Object -Property FullName -Descending)
    foreach ($dir in $dirs) {
        $rel = $dir.FullName.Substring($rootFull.Length + 1)
        if (Test-IsProtectedPath -RelativePath $rel -ProtectedDirs $Settings.ProtectedDirs -ProtectedFiles $Settings.ProtectedFiles) { continue }
        $children = @(Get-ChildItem -LiteralPath $dir.FullName -Force -ErrorAction SilentlyContinue)
        if ($children.Count -eq 0) {
            try {
                Remove-Item -LiteralPath $dir.FullName -Force -ErrorAction Stop
                $removed++
                Write-Detail ('  - 删除空目录 ' + $rel)
            } catch { }
        }
    }
    return $removed
}

# ----------------------------------------------------------------------------
# 更新主流程
# ----------------------------------------------------------------------------

function Show-Preview {
    param(
        [Parameter(Mandatory = $true)]$Analysis,
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)]$Settings,
        [Parameter(Mandatory = $true)][hashtable]$Cli,
        [AllowNull()]$Remote
    )
    Write-Step '预览（-DryRun：不备份、不改目标文件、不清缓存、不写版本标记）'
    Write-Log ('严格镜像计划：新增 {0} / 修改 {1} / 删除 {2} / 未变 {3}' -f $Analysis.Added.Count, $Analysis.Modified.Count, $Analysis.Deleted.Count, $Analysis.UnchangedCount)
    if ($Cli.NoBackup) {
        Write-Log '本次指定了 -NoBackup：正式执行时不会创建备份。'
    } else {
        Write-Log ('正式执行时会先备份到：{0}\<时间戳>\（保留 {1} 份）' -f $Settings.BackupRoot, $Config.KeepBackups)
    }
    if ($Settings.ClearCache) {
        $cachePath = Resolve-CachePath -Config $Config -Settings $Settings
        Assert-CachePathSafe -CachePath $cachePath -TargetPath $Settings.TargetPath -BackupPath ([string]$Config.BackupPath)
        $stats = Get-PathContentStats -Path $cachePath
        Write-Log ('缓存清理计划：将清空 {0}（当前 {1} 个文件，{2}）' -f $cachePath, $stats.Files, (Format-Bytes -Bytes $stats.Bytes))
    }
    if ($Settings.IsWeb) {
        foreach ($line in (Get-WebRunningHint -Config $Config)) { Write-Log $line }
    } elseif ($null -ne $script:HotReloadContext -and [bool]$script:HotReloadContext.UseHotReload) {
        Write-Log '正式执行时不会重启服务器：脚本会在严格镜像后通过 console socket 逐个热重载（详见下方评估）。'
    } else {
        Write-Log '正式执行时不会自动启动/重启服务器；更新完成后请自行启动服务器验证。'
    }
    if (-not $Settings.IsWeb) {
        Show-HotReloadPreview -Analysis $Analysis -Context $script:HotReloadContext
    }
    $shaText = '（未知）'
    if ($null -ne $Remote) { $shaText = $Remote.Sha }
    Write-Log ('版本标记将写入：{0}（SHA {1}）' -f $Settings.MarkerPath, $shaText)
    if ($null -ne $Remote) { Write-Log ('远端最新提交：{0}（{1}）' -f $Remote.Sha, $Remote.Date) }
    Write-Log 'DryRun 完成：未做任何修改（下载/解压只在系统临时目录进行，结束后已清理）。'
}

function Invoke-UpdateFlow {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)]$Settings,
        [Parameter(Mandatory = $true)][hashtable]$Cli,
        [Parameter(Mandatory = $true)][string]$UpdaterDir
    )

    $remote = $null
    $localMarker = Read-VersionMarker -Path $Settings.MarkerPath
    $useLocalZip = ($Cli.ContainsKey('SourceZip') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.SourceZip))
    $force = [bool]$Cli.Force

    # ---------- 1. 版本检查 ----------
    Write-Step ('1/6 版本检查 / 确定更新来源' + $(if ($useLocalZip) { '（使用本地 zip）' } else { '（GitHub API，失败不阻塞）' }))
    if (-not $useLocalZip) {
        $remote = Get-RemoteCommitInfo -Config $Config
    }
    if ($null -ne $remote) {
        Write-Log ('远端最新提交：{0}' -f $remote.Sha)
        if (-not [string]::IsNullOrWhiteSpace($remote.Date)) { Write-Log ('提交时间：{0}' -f $remote.Date) }
        if (-not [string]::IsNullOrWhiteSpace($remote.Message)) { Write-Log ('提交说明：{0}' -f $remote.Message) }
    } elseif (-not $useLocalZip) {
        Write-Log '版本检查不可用（网络 / 代理 / GitHub API 限流）。将继续下载 zip 做内容比对，不阻塞更新。' 'WARN'
    }
    if ($null -ne $localMarker) {
        Write-Log ('本地已部署版本：{0}（{1} 部署，分支 {2}）' -f $localMarker.sha, $localMarker.deployedAt, $localMarker.branch)
    } else {
        Write-Log '本地没有版本标记（首次运行，或备份目录被清理过）。'
    }

    $upToDate = $false
    if ($null -ne $remote -and $null -ne $localMarker) {
        $upToDate = (([string]$remote.Sha -ieq [string]$localMarker.sha) -and
                     ([string]$localMarker.repo -ieq [string]$Config.Repo) -and
                     ([string]$localMarker.branch -ieq [string]$Config.Branch))
    }
    if ($upToDate -and -not $force -and -not $Settings.ClearCache) {
        Write-Log '远端提交与本地已部署版本一致，已是最新，跳过下载与更新。' 'OK'
        Write-Log '如需强制重新镜像（例如目标目录被手改过），请加 -Force。'
        return New-UpdateResult -ExitCode 0 -Summary ('已是最新版本（{0}），未做任何修改。' -f $remote.Sha)
    }
    if ($upToDate -and $Settings.ClearCache) {
        Write-Log '版本没有变化，但本脚本仍会执行严格镜像并清理编译缓存（这正是 clear-cache 版的用途）。' 'INFO'
    }

    # ---------- 2. 取得 zip ----------
    Write-Step '2/6 获取仓库 zip'
    New-Directory -Path $script:TempDir
    $zipPath = ''
    if ($useLocalZip) {
        $zipPath = Resolve-UserPath -Path ([string]$Cli.SourceZip) -BaseDir $UpdaterDir
        if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) {
            throw ('指定的本地 zip 不存在：{0}' -f $zipPath)
        }
        Write-Log ('使用本地 zip：{0}（{1}）' -f $zipPath, (Format-Bytes -Bytes (Get-Item -LiteralPath $zipPath).Length))
    } else {
        $zipPath = Join-Path $script:TempDir ('repo-{0}.zip' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
        $downloadUrl = 'https://codeload.github.com/{0}/zip/refs/heads/{1}' -f $Config.Repo, $Config.Branch
        Write-Log ('仓库：{0}  分支：{1}' -f $Config.Repo, $Config.Branch)
        Invoke-DownloadFile -Url $downloadUrl -OutFile $zipPath -Config $Config
    }

    # ---------- 3. 解压 + 校验 ----------
    Write-Step '3/6 解压并校验仓库内容'
    $extractDir = Join-Path $script:TempDir 'extract'
    Expand-RepoZip -ZipPath $zipPath -Destination $extractDir
    $sourceDir = Get-RepoSourceDir -ExtractDir $extractDir -SourceSubdir $Settings.SourceSubdir
    Write-Log ('zip 内 {0}\ 目录：{1}' -f $Settings.SourceSubdir, $sourceDir)
    Assert-SourceContent -Settings $Settings -SourceDir $sourceDir
    foreach ($ignored in @('docs', 'README.md', 'LICENSE.md', '.gitignore')) {
        Write-Detail ('忽略 zip 中与本次更新无关的内容：' + $ignored)
    }

    # ---------- 4. 严格镜像分析 ----------
    Write-Step '4/6 严格镜像分析（对比仓库与目标目录）'
    $analysis = Get-MirrorAnalysis -SourceRoot $sourceDir -TargetRoot $Settings.TargetPath -ProtectedDirs $Settings.ProtectedDirs -ProtectedFiles $Settings.ProtectedFiles
    Write-MirrorReport -Analysis $analysis -Settings $Settings

    if ($Cli.DryRun) {
        Show-Preview -Analysis $analysis -Config $Config -Settings $Settings -Cli $Cli -Remote $remote
        return New-UpdateResult -ExitCode 0 -Summary ('DryRun 预览完成：新增 {0} / 修改 {1} / 删除 {2} / 未变 {3}，未做任何修改。' -f $analysis.Added.Count, $analysis.Modified.Count, $analysis.Deleted.Count, $analysis.UnchangedCount)
    }

    # ---------- 4.1 运行中热重载：变更类型预检（必须在改动任何文件之前） ----------
    $hotReload = $script:HotReloadContext
    $hotReloadActive = ($null -ne $hotReload -and [bool]$hotReload.UseHotReload -and -not $Settings.IsWeb)
    if ($hotReloadActive) {
        $allChanges = @()
        $allChanges += @($analysis.Added)
        $allChanges += @($analysis.Modified)
        $allChanges += @($analysis.Deleted)
        $nonScriptChanges = @(Get-NonScriptRelPaths -RelPaths $allChanges)
        if ($nonScriptChanges.Count -gt 0) {
            $detailLines = New-Object System.Collections.Generic.List[string]
            foreach ($rel in $nonScriptChanges) { $detailLines.Add('  - ' + [string]$rel) | Out-Null }
            $message = @(
                ('REFUSED: 本次更新包含 {0} 个非 .kts 文件（.kt 模块库/依赖等）变更，运行中热重载无法安全处理，已在改动文件前中止。' -f $nonScriptChanges.Count),
                ($detailLines -join [Environment]::NewLine),
                '请关闭服务器后重新运行本脚本（可加 -DryRun 先预览）。',
                '热重载只适合纯 .kts 脚本变更；.kt 库/依赖改动必须停服更新并重启服务器。'
            ) -join [Environment]::NewLine
            throw $message
        }
        Write-Log ('运行中热更新预检通过：本次变更全部为 .kts 脚本（{0} 个）；将先逐个 sa unload 将被删除的脚本（文件还在时）→ 严格镜像 → 再逐个 sa load 变更/新增脚本。' -f (@(Get-HotReloadScriptIds -RelPaths $allChanges)).Count) 'OK'
    }

    # ---------- 4.2 运行中热重载 · 阶段一：镜像前卸载将被删除的脚本 ----------
    # 实测：必须先 unload（文件还在）再严格镜像删文件；先删文件再 unload 会让 SA 事务卡住 console socket。
    $preUnloadInfo = $null
    if ($hotReloadActive) {
        $removedScriptIds = @(Get-HotReloadScriptIds -RelPaths @($analysis.Deleted))
        if ($removedScriptIds.Count -gt 0) {
            $preUnloadInfo = Invoke-HotReloadPreUnload -Config $Config -Context $hotReload -RemovedPaths @($analysis.Deleted) -Stage '更新'
            if ([int]$preUnloadInfo.ExitCode -ne 0) {
                Write-Log ''
                Write-Log ('运行中热重载 · 阶段一失败：{0}' -f $preUnloadInfo.Summary) 'ERROR'
                return New-UpdateResult -ExitCode 2 -Summary $preUnloadInfo.Summary
            }
        }
    }

    # ---------- 5. 备份（+ 可选清缓存） ----------
    # 从这里到镜像应用完成属于「改动文件」阶段；若失败会尝试把阶段一已 unload 的脚本重新加载回去。
    try {
        Write-Step '5/6 备份目标目录'
        $backup = $null
        if ($Cli.NoBackup) {
            Write-Log '按要求跳过备份（-NoBackup，不建议）：如果更新失败将无法自动恢复。' 'WARN'
        } else {
            $backup = New-TargetBackup -Config $Config -Settings $Settings -PreviousMarker $localMarker
            $meta = $backup.Meta
            $meta.plannedAdded = @($analysis.Added)
            $meta.plannedModified = @($analysis.Modified)
            $meta.plannedDeleted = @($analysis.Deleted)
            $meta.plannedUnchanged = $analysis.UnchangedCount
            $meta.deployedCommitSha = $(if ($null -ne $remote) { [string]$remote.Sha } else { '' })
            $meta.commitDate = $(if ($null -ne $remote) { [string]$remote.Date } else { '' })
            $meta.status = 'applying'
            Save-JsonFile -Path $backup.MetaPath -Data $meta
        }

        $cacheStats = $null
        if ($Settings.ClearCache) {
            Write-Step '5.1/6 清空脚本编译缓存'
            $cachePath = Resolve-CachePath -Config $Config -Settings $Settings
            Assert-CachePathSafe -CachePath $cachePath -TargetPath $Settings.TargetPath -BackupPath ([string]$Config.BackupPath)
            Write-Log ('缓存目录：{0}' -f $cachePath)
            $cacheStats = Clear-PathContents -Path $cachePath
            if (-not $cacheStats.Exists) {
                Write-Log '缓存目录不存在（无需清理）。' 'OK'
            } else {
                Write-Log ('缓存已清空：删除 {0} 个文件（{1}）。' -f $cacheStats.Files, (Format-Bytes -Bytes $cacheStats.Bytes)) 'OK'
            }
        }

        # ---------- 6. 应用严格镜像 ----------
        Write-Step '6/6 应用严格镜像（新增/覆盖 + 删除仓库中不存在的文件）'
        $apply = $null
        try {
            $apply = Apply-Mirror -Analysis $analysis -Settings $Settings
        } catch {
            $applyError = $_.Exception.Message
            Write-Log ('应用更新失败：{0}' -f $applyError) 'ERROR'
            if ($null -ne $backup) {
                Write-Log '正在用刚创建的备份自动恢复被改动/删除的文件...' 'WARN'
                try {
                    $restore = Restore-BackupFiles -BackupDir $backup.Dir -TargetRoot $Settings.TargetPath -AddedRelPaths @($analysis.Added)
                    Write-Log ('已自动恢复：恢复 {0} 个文件，删除新增文件 {1} 个。' -f $restore.Restored, $restore.Deleted) 'OK'
                    $meta.status = 'apply-failed-restored'
                    Save-JsonFile -Path $backup.MetaPath -Data $meta
                } catch {
                    Write-Log ('自动恢复失败：{0}' -f $_.Exception.Message) 'ERROR'
                    Write-Log ('请手动用该备份恢复：{0}' -f $backup.Dir) 'ERROR'
                    $meta.status = 'apply-failed'
                    try { Save-JsonFile -Path $backup.MetaPath -Data $meta } catch { }
                }
            } else {
                Write-Log '本次没有备份（-NoBackup），无法自动恢复，请人工检查目标目录。' 'ERROR'
            }
            throw ('更新失败，已中止：{0}' -f $applyError)
        }

        if ($null -ne $backup) {
            $meta.status = 'success'
            $meta.appliedCopied = $apply.Copied
            $meta.appliedDeleted = $apply.Deleted
            $meta.appliedRemovedEmptyDirs = $apply.RemovedEmptyDirs
            if ($null -ne $cacheStats) { $meta.cacheClearedFiles = $cacheStats.Files }
            $meta.finishedAt = (Get-LogTimestamp)
            Save-JsonFile -Path $backup.MetaPath -Data $meta
        }
    } catch {
        # 镜像阶段失败：若阶段一已 unload 过脚本（文件此时仍然存在/已被自动恢复），尝试重新加载，避免留下「文件在但脚本未加载」状态。
        $mirrorError = $_
        if ($null -ne $preUnloadInfo -and [bool]$preUnloadInfo.Attempted -and @($preUnloadInfo.Completed).Count -gt 0) {
            Write-Log ('镜像阶段失败：正在尝试把阶段一已卸载的 {0} 个脚本重新加载回来（best-effort）...' -f @($preUnloadInfo.Completed).Count) 'WARN'
            try {
                $recovery = Invoke-HotReloadPostLoad -Config $Config -Context $hotReload -AddedPaths @() -ChangedPaths @($analysis.Deleted) -CheckListFailed $false -Stage '更新失败恢复'
                Write-Log ('失败恢复（重新加载已卸载脚本）结果：{0}' -f $recovery.Summary) $(if ([int]$recovery.ExitCode -eq 0) { 'OK' } else { 'WARN' })
            } catch {
                Write-Log ('失败恢复时重新加载脚本出错：{0}（请手动 sa load 或重启服务器）' -f $_.Exception.Message) 'ERROR'
            }
        }
        throw $mirrorError
    }
    # ---------- 写版本标记 / 保留份数 ----------
    $deployedSha = ''
    if ($null -ne $remote) { $deployedSha = [string]$remote.Sha }
    $marker = [ordered]@{
        schema       = 1
        kind         = $Settings.Kind
        sha          = $deployedSha
        commitDate   = $(if ($null -ne $remote) { [string]$remote.Date } else { '' })
        deployedAt   = (Get-LogTimestamp)
        repo         = [string]$Config.Repo
        branch       = [string]$Config.Branch
        source       = $(if ($useLocalZip) { 'local-zip' } else { 'github' })
        added        = $analysis.Added.Count
        modified     = $analysis.Modified.Count
        deleted      = $analysis.Deleted.Count
        unchanged    = $analysis.UnchangedCount
        backup       = $(if ($null -ne $backup) { $backup.Timestamp } else { '' })
        cacheCleared = $(if ($null -ne $cacheStats) { $cacheStats.Files } else { 0 })
    }
    if ([string]::IsNullOrWhiteSpace($deployedSha) -and -not $useLocalZip) {
        Write-Log '注意：版本检查不可用，本地版本标记的 SHA 为空；下次运行会重新下载比对（内容未变时不会改文件）。' 'WARN'
    }
    Write-VersionMarker -Path $Settings.MarkerPath -Marker $marker
    Write-Log ('已写入本地版本标记：{0}' -f $Settings.MarkerPath)

    if ($null -ne $backup) {
        Remove-OldBackups -BackupRoot $Settings.BackupRoot -Keep ([int]$Config.KeepBackups) -CurrentTimestamp $backup.Timestamp
    }

    # ---------- 运行中热重载 · 阶段二：镜像后加载 changed/added（文件已更新；失败/超时退出码 3） ----------
    $hotReloadInfo = $null
    if ($hotReloadActive) {
        $checkListFailed = (@($analysis.Added).Count -gt 0 -or @($analysis.Modified).Count -gt 0 -or ($null -ne $preUnloadInfo -and [bool]$preUnloadInfo.Attempted))
        $removedScriptCount = @(Get-HotReloadScriptIds -RelPaths @($analysis.Deleted)).Count
        $hotReloadInfo = Invoke-HotReloadPostLoad -Config $Config -Context $hotReload `
            -AddedPaths @($analysis.Added) -ChangedPaths @($analysis.Modified) -RemovedScriptCount $removedScriptCount -CheckListFailed $checkListFailed -Stage '更新'
        if ($hotReloadInfo.Done -and [int]$hotReloadInfo.ExitCode -ne 0) {
            Write-Log ''
            Write-Log ('严格镜像已完成（文件已更新），但运行中热重载未成功：{0}' -f $hotReloadInfo.Summary) 'ERROR'
            Write-Log '文件状态说明：目标目录已是仓库最新版本，只是运行中的服务器可能没有成功加载新脚本。' 'WARN'
            Write-Log '恢复建议：1) 在服务器控制台执行 sa listFailed / sa load；2) 修复失败脚本后再次运行本更新器（会重新比对，内容未变时不会重复下载/改动）；3) 仍不行就重启服务器。' 'WARN'
            $summary = ('更新完成（文件已更新：新增 {0} / 修改 {1} / 删除 {2}），但 {3}' -f $analysis.Added.Count, $analysis.Modified.Count, $analysis.Deleted.Count, $hotReloadInfo.Summary)
            return New-UpdateResult -ExitCode 3 -Summary $summary
        }
    }

    # ---------- 收尾提示 ----------
    Write-Log ''
    Write-Log ('严格镜像完成：新增 {0} / 修改 {1} / 删除 {2} / 未变 {3}；复制与哈希校验通过。' -f $analysis.Added.Count, $analysis.Modified.Count, $analysis.Deleted.Count, $analysis.UnchangedCount) 'OK'
    if ($Settings.IsWeb) {
        foreach ($line in (Get-WebRunningHint -Config $Config)) { Write-Log $line 'WARN' }
    } elseif ($null -ne $hotReloadInfo -and [bool]$hotReloadInfo.Attempted) {
        Write-Log '运行中热重载已完成：被删除脚本已先在镜像前 unload（文件随镜像删除），变更/新增脚本已在镜像后逐个 load；本次没有重启服务器。'
        Write-Log '请在服务器控制台/日志确认没有脚本报错（config\logs\script-load\last-error.log 为空、脚本加载数量正常）。'
    } elseif ($null -ne $script:HotReloadContext -and [bool]$script:HotReloadContext.ServerRunning -and -not $Settings.ClearCache) {
        Write-Log '服务器保持运行中：本次没有需要热重载的 .kts 脚本变更，无需重启服务器。'
    } elseif ($Settings.ClearCache) {
        Write-Log '缓存已清空：启动服务器时会重新编译全部脚本（首次启动会慢一些），这是预期行为。'
        Write-Log '请启动服务器（运行 mdtserver\启动服务器.bat）后检查 config\logs\script-load\last-error.log 为空、脚本加载数量正常。'
    } else {
        Write-Log '更新器不会自动启动服务器。请自行启动服务器（运行 mdtserver\启动服务器.bat）验证脚本加载正常。'
    }

    $summaryExtra = ''
    if ($null -ne $backup) { $summaryExtra = '；备份 ' + $backup.Timestamp } else { $summaryExtra = '；未备份' }
    if ($Settings.ClearCache -and $null -ne $cacheStats) { $summaryExtra += ('；缓存清理 ' + $cacheStats.Files + ' 个文件') }
    if ($null -ne $preUnloadInfo -and [bool]$preUnloadInfo.Attempted) { $summaryExtra += ('；' + $preUnloadInfo.Summary) }
    if ($null -ne $hotReloadInfo -and [bool]$hotReloadInfo.Attempted) { $summaryExtra += ('；' + $hotReloadInfo.Summary) }
    $summary = ('更新完成：新增 {0} / 修改 {1} / 删除 {2} / 未变 {3}{4}。' -f $analysis.Added.Count, $analysis.Modified.Count, $analysis.Deleted.Count, $analysis.UnchangedCount, $summaryExtra)
    return New-UpdateResult -ExitCode 0 -Summary $summary
}

# ----------------------------------------------------------------------------
# 回滚流程
# ----------------------------------------------------------------------------

function Resolve-RollbackBackup {
    param(
        [Parameter(Mandatory = $true)][string]$BackupRoot,
        [AllowEmptyString()][string]$RollbackTo
    )
    if (-not (Test-Path -LiteralPath $BackupRoot -PathType Container)) {
        throw ('REFUSED: 备份目录不存在：{0}（还没有可回滚的备份）' -f $BackupRoot)
    }
    if (-not [string]::IsNullOrWhiteSpace($RollbackTo)) {
        $candidate = Resolve-UserPath -Path $RollbackTo -BaseDir $BackupRoot
        if (Test-Path -LiteralPath $candidate -PathType Container) { return (Get-Item -LiteralPath $candidate) }
        $direct = Join-Path $BackupRoot $RollbackTo
        if (Test-Path -LiteralPath $direct -PathType Container) { return (Get-Item -LiteralPath $direct) }
        $available = @(Get-ChildItem -LiteralPath $BackupRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^\d{8}-\d{6}$' } | Sort-Object Name -Descending | ForEach-Object { $_.Name })
        throw ('REFUSED: 找不到指定备份：{0}。可用备份：{1}' -f $RollbackTo, $(if ($available.Count -gt 0) { $available -join ', ' } else { '（无）' }))
    }
    $latest = @(Get-ChildItem -LiteralPath $BackupRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d{8}-\d{6}$' } | Sort-Object Name -Descending | Select-Object -First 1)
    if ($latest.Count -eq 0) {
        throw ('REFUSED: 备份目录中没有可回滚的备份：{0}' -f $BackupRoot)
    }
    return $latest[0]
}

# 回滚前计算：备份中与当前目标内容不一致（或目标缺失）的文件，用于热重载 changed 列表。
# （备份里包含目标目录全部参与镜像的文件，不能全部 reload，只请求真正被回滚改动的脚本。）
function Get-RollbackChangedRelPaths {
    param(
        [Parameter(Mandatory = $true)][string]$BackupDir,
        [Parameter(Mandatory = $true)][string]$TargetRoot
    )
    $result = New-Object System.Collections.Generic.List[string]
    $backupFull = (Get-Item -LiteralPath $BackupDir).FullName.TrimEnd('\')
    $prefixLength = $backupFull.Length + 1
    foreach ($file in (Get-ChildItem -LiteralPath $backupFull -Recurse -File -ErrorAction SilentlyContinue)) {
        if ($file.FullName.Length -le $prefixLength) { continue }
        $rel = $file.FullName.Substring($prefixLength)
        $targetFile = Join-Path $TargetRoot $rel
        if (-not (Test-Path -LiteralPath $targetFile -PathType Leaf)) {
            $result.Add($rel) | Out-Null
            continue
        }
        try {
            $backupHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256 -ErrorAction Stop).Hash
            $targetHash = (Get-FileHash -LiteralPath $targetFile -Algorithm SHA256 -ErrorAction Stop).Hash
            if ($backupHash -ne $targetHash) { $result.Add($rel) | Out-Null }
        } catch {
            $result.Add($rel) | Out-Null
        }
    }
    return $result.ToArray()
}

function Invoke-RollbackFlow {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)]$Settings,
        [Parameter(Mandatory = $true)][hashtable]$Cli
    )
    Write-Step '回滚：定位备份'
    $backupRoot = $Settings.BackupRoot
    $rollbackTo = ''
    if ($Cli.ContainsKey('RollbackTo')) { $rollbackTo = [string]$Cli.RollbackTo }
    $backup = Resolve-RollbackBackup -BackupRoot $backupRoot -RollbackTo $rollbackTo
    $metaPath = Join-Path $backupRoot ('.meta-' + $backup.Name + '.json')
    $meta = Read-BackupMeta -MetaPath $metaPath
    $backupFiles = @(Get-ChildItem -LiteralPath $backup.FullName -Recurse -File -ErrorAction SilentlyContinue)
    Write-Log ('目标备份：{0}' -f $backup.FullName)
    Write-Log ('备份时间：{0}，备份文件数：{1}' -f $backup.Name, $backupFiles.Count)
    if ($null -ne $meta) {
        Write-Log ('备份元数据：状态={0}，部署版本={1}，更新前版本={2}，计划新增={3}，计划修改={4}，计划删除={5}' -f
            (Get-JsonProp -Object $meta -Names @('status')),
            (Get-JsonProp -Object $meta -Names @('deployedCommitSha')),
            (Get-JsonProp -Object $meta -Names @('previousVersion')),
            (@((Get-JsonProp -Object $meta -Names @('plannedAdded'))).Count),
            (@((Get-JsonProp -Object $meta -Names @('plannedModified'))).Count),
            (@((Get-JsonProp -Object $meta -Names @('plannedDeleted'))).Count))
    } else {
        Write-Log '该备份没有元数据（.meta-*.json），将只做目录文件恢复，不删除更新时新增的文件。' 'WARN'
    }

    $addedRelPaths = @()
    if ($null -ne $meta) {
        $plannedAdded = Get-JsonProp -Object $meta -Names @('plannedAdded')
        if ($null -ne $plannedAdded) { $addedRelPaths = @($plannedAdded) }
    }
    # 回滚实际会被删除的「更新新增」文件（当前目标里存在的），用于热重载 removed 列表
    $rollbackRemovedRelPaths = New-Object System.Collections.Generic.List[string]
    foreach ($rel in @($addedRelPaths)) {
        if ([string]::IsNullOrWhiteSpace([string]$rel)) { continue }
        if (Test-Path -LiteralPath (Join-Path $Settings.TargetPath ([string]$rel)) -PathType Leaf) {
            $rollbackRemovedRelPaths.Add([string]$rel) | Out-Null
        }
    }

    if ($backupFiles.Count -eq 0 -and -not [bool]$Cli.Force) {
        throw ('REFUSED: 备份是空的（{0}）：回滚会把目标目录恢复到空状态，风险过高已拒绝。确认要清空请加 -Force。' -f $backup.FullName)
    }

    if ($Cli.DryRun) {
        Write-Step '回滚预览（-DryRun：不修改任何文件）'
        Write-Log ('将恢复文件：{0} 个（备份目录 → {1}）' -f $backupFiles.Count, $Settings.TargetPath)
        Write-Log ('将删除「该次更新新增、备份中不存在」的文件：{0} 个' -f @($addedRelPaths).Count)
        Write-FileList -Title '将删除的文件' -Files $addedRelPaths
        $rbContext = $script:HotReloadContext
        if ($null -ne $rbContext -and [bool]$rbContext.ServerRunning -and -not $Settings.IsWeb) {
            if ([bool]$rbContext.UseHotReload) {
                $rollbackChangedPreview = @(Get-RollbackChangedRelPaths -BackupDir $backup.FullName -TargetRoot $Settings.TargetPath)
                $changedIds = @(Get-HotReloadScriptIds -RelPaths $rollbackChangedPreview)
                $removedIds = @(Get-HotReloadScriptIds -RelPaths $rollbackRemovedRelPaths.ToArray())
                $nonScriptPreview = @(Get-NonScriptRelPaths -RelPaths (@($rollbackChangedPreview) + @($rollbackRemovedRelPaths.ToArray())))
                Write-Log '正式回滚将按以下顺序执行（DryRun 不发命令、不改文件）：'
                Write-Log ('  ① 恢复文件前：先逐个 sa unload 将被删除的新增脚本（文件还在时）：{0}' -f $(if ($removedIds.Count -gt 0) { ($removedIds | ForEach-Object { 'sa unload ' + $_ }) -join ' → ' } else { '（无）' }))
                Write-Log ('  ② 恢复备份文件（{0} 个）并删除该次更新新增的文件' -f $backupFiles.Count)
                Write-Log ('  ③ 恢复后：{0}逐个 sa load 被恢复的脚本（changed {1} 个）：{2}' -f $(if ($removedIds.Count -gt 0) { '先 sa scan（等 1.5 秒，清理已删脚本注册表），然后' } else { '' }), $changedIds.Count, $(if ($changedIds.Count -gt 0) { ($changedIds | ForEach-Object { 'sa load ' + $_ }) -join ' → ' } else { '（无）' }))
                Write-Log '  ④ sa listFailed 收尾（失败/超时退出码 3；unload 失败退出码 2 且目标零改动）'
                if ($nonScriptPreview.Count -gt 0) {
                    Write-Log ('注意：回滚涉及 {0} 个非 .kts 文件（.kt 库等），热重载无法处理，正式回滚后需要重启服务器才生效。' -f $nonScriptPreview.Count) 'WARN'
                    Write-FileList -Title '需要重启才生效的非 .kts 文件' -Files $nonScriptPreview
                }
            } else {
                Write-Log '服务器运行中但热重载不可用/关闭：正式回滚后需重启服务器（或 sa load）才会生效。' 'WARN'
            }
        } elseif ($null -ne $rbContext -and -not $Settings.IsWeb) {
            Write-Log '服务器未运行：正式回滚按停服流程恢复文件，下次启动服务器时加载。'
        }
        Write-Log 'DryRun：未修改任何文件。'
        return New-UpdateResult -ExitCode 0 -Summary ('DryRun 回滚预览完成（备份 {0}：将恢复 {1} 个文件，删除 {2} 个新增文件）。' -f $backup.Name, $backupFiles.Count, @($addedRelPaths).Count)
    }

    $rollbackChangedRelPaths = @()
    if ($null -ne $script:HotReloadContext -and [bool]$script:HotReloadContext.UseHotReload -and -not $Settings.IsWeb) {
        $rollbackChangedRelPaths = @(Get-RollbackChangedRelPaths -BackupDir $backup.FullName -TargetRoot $Settings.TargetPath)
    }
    # ---------- 运行中热重载 · 阶段一（回滚）：恢复前先卸载将被删除的新增脚本 ----------
    # 同更新流程：必须先 unload（文件还在）再删文件，避免 SA 事务卡住 console socket。
    $hotReloadCtx = $script:HotReloadContext
    $hotReloadActive = ($null -ne $hotReloadCtx -and [bool]$hotReloadCtx.UseHotReload -and -not $Settings.IsWeb)
    $preUnloadInfo = $null
    if ($hotReloadActive) {
        $rollbackRemovedIds = @(Get-HotReloadScriptIds -RelPaths @($rollbackRemovedRelPaths.ToArray()))
        if ($rollbackRemovedIds.Count -gt 0) {
            $preUnloadInfo = Invoke-HotReloadPreUnload -Config $Config -Context $hotReloadCtx -RemovedPaths @($rollbackRemovedRelPaths.ToArray()) -Stage '回滚'
            if ([int]$preUnloadInfo.ExitCode -ne 0) {
                Write-Log ''
                Write-Log ('运行中热重载 · 阶段一失败：{0}' -f $preUnloadInfo.Summary) 'ERROR'
                return New-UpdateResult -ExitCode 2 -Summary $preUnloadInfo.Summary
            }
        }
    }

    Write-Step '回滚：恢复文件'
    $restore = Restore-BackupFiles -BackupDir $backup.FullName -TargetRoot $Settings.TargetPath -AddedRelPaths $addedRelPaths
    Write-Log ('已恢复 {0} 个文件，删除更新新增文件 {1} 个。' -f $restore.Restored, $restore.Deleted) 'OK'

    # 版本标记回滚到「更新前版本」，避免下次版本检查误判
    $previous = $null
    if ($null -ne $meta) { $previous = Get-JsonProp -Object $meta -Names @('previousVersion') }
    $marker = [ordered]@{
        schema        = 1
        kind          = $Settings.Kind
        sha           = $(if ($null -ne $previous) { ([string](Get-JsonProp -Object $previous -Names @('sha'))).ToLowerInvariant() } else { '' })
        commitDate    = $(if ($null -ne $previous) { [string](Get-JsonProp -Object $previous -Names @('commitDate')) } else { '' })
        deployedAt    = (Get-LogTimestamp)
        repo          = [string]$Config.Repo
        branch        = [string]$Config.Branch
        source        = 'rollback'
        rolledBackTo  = $backup.Name
    }
    Write-VersionMarker -Path $Settings.MarkerPath -Marker $marker
    Write-Log ('已更新本地版本标记（回滚后）：{0}' -f $Settings.MarkerPath)

    # ---------- 运行中热重载 · 阶段二（回滚）：恢复后先 scan（若删了脚本）再逐个 load 被恢复的脚本 ----------
    $hotReloadInfo = $null
    if ($hotReloadActive) {
        $checkListFailed = (@($rollbackChangedRelPaths).Count -gt 0 -or ($null -ne $preUnloadInfo -and [bool]$preUnloadInfo.Attempted))
        $rollbackRemovedScriptCount = @(Get-HotReloadScriptIds -RelPaths @($rollbackRemovedRelPaths.ToArray())).Count
        $hotReloadInfo = Invoke-HotReloadPostLoad -Config $Config -Context $hotReloadCtx `
            -AddedPaths @() -ChangedPaths @($rollbackChangedRelPaths) -RemovedScriptCount $rollbackRemovedScriptCount -CheckListFailed $checkListFailed -Stage '回滚'
        if ($hotReloadInfo.Done -and [int]$hotReloadInfo.ExitCode -ne 0) {
            Write-Log ''
            Write-Log ('回滚已恢复文件，但运行中热重载未成功：{0}' -f $hotReloadInfo.Summary) 'ERROR'
            Write-Log '恢复建议：在服务器控制台执行 sa load / sa listFailed 排查失败脚本，或重启服务器。' 'WARN'
            $summary = ('回滚完成（文件已恢复：{0} 个；删除更新新增 {1} 个），但 {2}' -f $restore.Restored, $restore.Deleted, $hotReloadInfo.Summary)
            return New-UpdateResult -ExitCode 3 -Summary $summary
        }
    }

    if ($Settings.IsWeb) {
        foreach ($line in (Get-WebRunningHint -Config $Config)) { Write-Log $line 'WARN' }
    } elseif ($null -ne $hotReloadInfo -and [bool]$hotReloadInfo.Attempted) {
        Write-Log '运行中回滚已按「先 unload 将被删脚本 → 恢复文件 → 再 load 恢复的脚本」逐个热重载完成，本次没有重启服务器。'
        Write-Log '请在服务器控制台/日志确认没有脚本报错（config\logs\script-load\last-error.log 为空、脚本加载数量正常）。'
    } elseif ($null -ne $script:HotReloadContext -and [bool]$script:HotReloadContext.ServerRunning -and -not $Settings.IsWeb) {
        Write-Log '服务器保持运行中：本次回滚没有需要热重载的 .kts 脚本变更，无需重启服务器。'
    } else {
        Write-Log '回滚完成。请启动服务器验证；如需确认版本，可查看本日志与备份元数据。'
    }
    $summaryExtra = ''
    if ($null -ne $preUnloadInfo -and [bool]$preUnloadInfo.Attempted) { $summaryExtra += '；' + $preUnloadInfo.Summary }
    if ($null -ne $hotReloadInfo -and [bool]$hotReloadInfo.Attempted) { $summaryExtra += '；' + $hotReloadInfo.Summary }
    return New-UpdateResult -ExitCode 0 -Summary ('回滚完成（备份 {0}）：恢复 {1} 个文件，删除更新新增文件 {2} 个{3}。' -f $backup.Name, $restore.Restored, $restore.Deleted, $summaryExtra)
}

# ----------------------------------------------------------------------------
# 横幅 / 参数摘要
# ----------------------------------------------------------------------------

function Show-Banner {
    param([Parameter(Mandatory = $true)][string]$UpdaterDir)
    Write-Log ('=' * 78) 'STEP'
    Write-Log ('MDT 更新器 v{0}' -f $script:UpdaterVersion) 'STEP'
    Write-Log ('=' * 78) 'STEP'
    Write-Log ('开始时间：{0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    Write-Log ('主机 / 用户：{0} / {1}' -f $env:COMPUTERNAME, $env:USERNAME)
    Write-Log ('PowerShell：{0}' -f $PSVersionTable.PSVersion)
    Write-Log ('更新器目录：{0}' -f $UpdaterDir)
    Write-Log ('日志文件：{0}' -f $script:LogPath)
}

function Show-OptionsSummary {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)]$Settings,
        [Parameter(Mandatory = $true)][hashtable]$Cli
    )
    Write-Step '配置与参数'
    if (-not [string]::IsNullOrWhiteSpace([string]$Config.ConfigPath)) {
        Write-Log ('配置文件：{0}' -f $Config.ConfigPath)
    } else {
        Write-Log '配置文件：未找到 config.json，使用内置默认值（目标路径为空时会报错）。'
    }
    Write-Log ('操作类型：{0}' -f $Settings.KindLabel)
    Write-Log ('仓库 / 分支：{0} / {1}' -f $Config.Repo, $Config.Branch)
    if (-not [string]::IsNullOrWhiteSpace([string]$Config.Proxy)) {
        $label = [string]$Config.Proxy
        if ($Config.Contains('ProxyFromEnv') -and $Config.ProxyFromEnv) { $label = ('{0}（来自环境变量 {1}）' -f $label, $Config.ProxyEnvName) }
        Write-Log ('代理：{0}' -f $label)
    } else {
        Write-Log '代理：无（直连）'
    }
    if ($Settings.IsWeb) {
        Write-Log ('统计 Web 目录（statsWebPath）：{0}' -f $Settings.TargetPath)
        Write-Log ('保护：目录 [{0}]；文件 [{1}]' -f (($Settings.ProtectedDirs) -join ', '), (($Settings.ProtectedFiles) -join ', '))
    } else {
        Write-Log ('服务器脚本目录（serverScriptsPath）：{0}' -f $Settings.TargetPath)
        Write-Log ('保护：目录 [{0}]；文件 [{1}]' -f (($Settings.ProtectedDirs) -join ', '), (($Settings.ProtectedFiles) -join ', '))
        Write-Log ('要求停服（requireServerStopped）：{0}（服务器运行中且热重载不可用/关闭时才生效）' -f $Config.RequireServerStopped)
        Write-Log ('游戏端口（gamePort）：{0}' -f $Config.GamePort)
        if ([bool]$Settings.SupportsHotReload) {
            Write-Log ('console socket（consoleSocketHost:consoleSocketPort）：{0}:{1}' -f $Config.ConsoleSocketHost, $Config.ConsoleSocketPort)
            Write-Log ('脚本加载日志（reloadLogPath）：{0}' -f $Config.ReloadLogPath)
            Write-Log ('单脚本超时（perScriptTimeoutSeconds）：{0} 秒；级联静默（reloadSettleMillis）：{1} 毫秒；连接超时（socketConnectTimeoutSeconds）：{2} 秒' -f $Config.PerScriptTimeoutSeconds, $Config.ReloadSettleMillis, $Config.SocketConnectTimeoutSeconds)
        } else {
            Write-Log '运行中热更新：本脚本不支持（清缓存版需要停服）。'
        }
    }
    Write-Log ('备份根目录：{0}（本类备份：{1}，保留 {2} 份）' -f $Config.BackupPath, $Settings.BackupRoot, $Config.KeepBackups)
    if (-not [string]::IsNullOrWhiteSpace([string]$Config.LogDir)) { Write-Log ('日志目录：{0}' -f $Config.LogDir) }
    if ($Settings.ClearCache) {
        Write-Log ('缓存目录（cachePath）：{0}' -f (Resolve-CachePath -Config $Config -Settings $Settings))
    }
    if (-not $Settings.IsWeb -and [int]$Config.WebPort -gt 0) { Write-Log ('Web 端口（webPort）：{0}' -f $Config.WebPort) }
    $flags = New-Object System.Collections.Generic.List[string]
    foreach ($name in @('DryRun', 'Force', 'NoBackup', 'Rollback', 'KeepTemp', 'NoHotReload')) {
        if ($Cli.ContainsKey($name) -and [bool]$Cli[$name]) { $flags.Add($name) | Out-Null }
    }
    if ($Cli.ContainsKey('HotReload') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.HotReload)) { $flags.Add('HotReload=' + [string]$Cli.HotReload) | Out-Null }
    if ($Cli.ContainsKey('RollbackTo') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.RollbackTo)) { $flags.Add('RollbackTo=' + [string]$Cli.RollbackTo) | Out-Null }
    if ($Cli.ContainsKey('SourceZip') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.SourceZip)) { $flags.Add('SourceZip') | Out-Null }
    if ($flags.Count -gt 0) { Write-Log ('命令行开关：{0}' -f ($flags -join ', ')) } else { Write-Log '命令行开关：无' }
}

# ----------------------------------------------------------------------------
# 互斥锁 / 临时目录
# ----------------------------------------------------------------------------

function Acquire-UpdateLock {
    param([Parameter(Mandatory = $true)][string]$Path)
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        try {
            $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            try {
                $writer = New-Object System.IO.StreamWriter($stream, (New-Object System.Text.UTF8Encoding($false)))
                $writer.WriteLine('PID={0}' -f $PID)
                $writer.WriteLine('started={0}' -f (Get-LogTimestamp))
                $writer.Flush()
                $writer.Dispose()
            } finally {
                try { $stream.Dispose() } catch { }
            }
            $script:LockPath = $Path
            return
        } catch [System.IO.IOException] {
            $content = ''
            try { $content = [System.IO.File]::ReadAllText($Path) } catch { }
            $stalePid = 0
            if ($content -match 'PID=(\d+)') { $stalePid = [int]$Matches[1] }
            $alive = $false
            if ($stalePid -gt 0) { $alive = $null -ne (Get-Process -Id $stalePid -ErrorAction SilentlyContinue) }
            if (-not $alive) {
                Write-Log ('发现过期的更新锁（PID {0} 已不存在），自动清理后继续。' -f $stalePid) 'WARN'
                Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
                continue
            }
            throw ('REFUSED: 另一个更新进程正在运行（PID {0}）。如确认没有进程在跑，请删除锁文件：{1}' -f $stalePid, $Path)
        } catch {
            Write-Log ('无法创建更新锁（{0}），继续执行但不防止并发。' -f $_.Exception.Message) 'WARN'
            return
        }
    }
    throw 'REFUSED: 无法获取更新锁（可能有多份更新在同时运行）。'
}

function Release-UpdateLock {
    if (-not [string]::IsNullOrEmpty($script:LockPath)) {
        Remove-Item -LiteralPath $script:LockPath -Force -ErrorAction SilentlyContinue
        $script:LockPath = ''
    }
}

function Remove-CurrentTempDir {
    param([Parameter(Mandatory = $true)][bool]$KeepTemp)
    if ([string]::IsNullOrEmpty($script:TempDir)) { return }
    if ($KeepTemp) {
        Write-Log ('按要求保留临时目录：{0}' -f $script:TempDir) 'WARN'
        return
    }
    if (Test-Path -LiteralPath $script:TempDir) {
        try {
            Remove-Item -LiteralPath $script:TempDir -Recurse -Force -ErrorAction Stop
        } catch {
            Write-Log ('清理临时目录失败：{0} — {1}' -f $script:TempDir, $_.Exception.Message) 'WARN'
        }
    }
    $parent = Split-Path -Parent $script:TempDir
    if (-not [string]::IsNullOrEmpty($parent) -and (Test-Path -LiteralPath $parent)) {
        try {
            if (@(Get-ChildItem -LiteralPath $parent -Force -ErrorAction SilentlyContinue).Count -eq 0) {
                Remove-Item -LiteralPath $parent -Force -ErrorAction SilentlyContinue
            }
        } catch { }
    }
}

# ============================================================================
# 主入口
# ============================================================================

function Invoke-MdtUpdater {
    param(
        [Parameter(Mandatory = $true)][string]$Kind,
        [Parameter(Mandatory = $true)][string]$UpdaterDir,
        [hashtable]$Cli = @{}
    )

    $exitCode = 1
    $summary = '未执行'
    $script:LogWriter = $null
    $script:LogPath = ''
    $script:TempDir = ''
    $script:LockPath = ''
    $script:HotReloadContext = $null
    $keepTemp = ($Cli.ContainsKey('KeepTemp') -and [bool]$Cli.KeepTemp)
    $settings = $null
    $config = $null

    try {
        Set-ConsoleUtf8
        Repair-PSModulePath
        $sourceZipInput = ''
        if ($Cli.ContainsKey('SourceZip')) { $sourceZipInput = [string]$Cli.SourceZip }
        if (-not [string]::IsNullOrWhiteSpace($sourceZipInput) -and [bool]$Cli.Rollback) {
            throw '-Rollback 与 -SourceZip 不能同时使用。'
        }

        $config = Get-EffectiveConfig -UpdaterDir $UpdaterDir -Cli $Cli
        $settings = Get-KindSettings -Kind $Kind -Config $config

        $logDir = [string]$config.LogDir
        if ([string]::IsNullOrWhiteSpace($logDir)) { $logDir = Join-Path $UpdaterDir 'logs' }
        New-Directory -Path $logDir
        $config.LogDir = $logDir
        $logFileName = 'update-{0}-{1}.log' -f $settings.BackupKind, (Get-Date -Format 'yyyyMMdd-HHmmss')
        Open-Logger -Path (Join-Path $logDir $logFileName)

        Show-Banner -UpdaterDir $UpdaterDir
        Show-OptionsSummary -Config $config -Settings $settings -Cli $Cli

        # 临时目录 + 互斥锁
        $tempBase = Join-Path ([System.IO.Path]::GetTempPath()) 'mdt-updater'
        New-Directory -Path $tempBase
        Acquire-UpdateLock -Path (Join-Path $tempBase ('.lock-' + $settings.BackupKind))
        $script:TempDir = Join-Path $tempBase ('{0}-{1}' -f $settings.BackupKind, (Get-Date -Format 'yyyyMMdd-HHmmss'))
        New-Directory -Path $script:TempDir

        # 预检：目标目录
        Write-Step '预检'
        if ([string]::IsNullOrWhiteSpace([string]$settings.TargetPath)) {
            $hint = 'serverScriptsPath'
            if ($settings.IsWeb) { $hint = 'statsWebPath' }
            throw ('REFUSED: 尚未配置 {0}，请编辑 config.json 填写绝对路径。' -f $hint)
        }
        if (-not (Test-Path -LiteralPath $settings.TargetPath -PathType Container)) {
            throw ('REFUSED: 目标目录不存在：{0}。请检查 config.json（本工具不会自动创建生产目录）。' -f $settings.TargetPath)
        }
        Write-Log ('目标目录存在：{0}' -f $settings.TargetPath) 'OK'
        if (-not (Test-Path -LiteralPath ([string]$config.BackupPath))) {
            New-Directory -Path ([string]$config.BackupPath)
        }

        # 预检：服务器运行状态 / 运行中热重载（console socket + 脚本加载日志）可用性
        # 优先级：服务器未运行 → 普通停服流程；运行中且热重载可用 → 镜像后热重载；
        #         热重载不可用/关闭 → 回退 requireServerStopped（true=拒绝2 / false=旧行为强制更新）。
        $isRollback = ([bool]$Cli.Rollback -or ($Cli.ContainsKey('RollbackTo') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.RollbackTo)))
        if (-not $settings.IsWeb) {
            $script:HotReloadContext = Initialize-HotReloadContext -Config $config -Settings $settings -Cli $Cli -IsRollback $isRollback
        } else {
            $script:HotReloadContext = $null
        }

        # 回滚 / 更新
        if ($isRollback) {
            $result = Invoke-RollbackFlow -Config $config -Settings $settings -Cli $Cli
        } else {
            $result = Invoke-UpdateFlow -Config $config -Settings $settings -Cli $Cli -UpdaterDir $UpdaterDir
        }
        $exitCode = [int]$result.ExitCode
        $summary = [string]$result.Summary
    } catch {
        $message = $_.Exception.Message
        if ($message.StartsWith('REFUSED: ')) {
            $exitCode = 2
            $message = $message.Substring(9)
        } else {
            $exitCode = 1
        }
        $summary = $message
        if ($null -ne $script:LogWriter) {
            Write-Log ('错误：{0}' -f $message) 'ERROR'
            try {
                if (-not [string]::IsNullOrWhiteSpace([string]$_.ScriptStackTrace)) {
                    Write-Detail ('调用栈：' + $_.ScriptStackTrace)
                }
            } catch { }
        } else {
            Write-Host ('错误：{0}' -f $message) -ForegroundColor Red
        }
    } finally {
        try {
            if ($null -ne $script:LogWriter) {
                Write-Log ''
                Write-Log ('=' * 78) 'STEP'
                $level = 'OK'
                if ($exitCode -ne 0) { $level = 'WARN' }
                Write-Log ('执行结果（退出码 {0}）：{1}' -f $exitCode, $summary) $level
                if (-not [string]::IsNullOrWhiteSpace($script:LogPath)) {
                    Write-Log ('完整日志：{0}' -f $script:LogPath)
                }
                Write-Log ('=' * 78) 'STEP'
            } else {
                Write-Host ('执行结果（退出码 {0}）：{1}' -f $exitCode, $summary) -ForegroundColor Yellow
            }
        } catch { }
        Release-UpdateLock
        Remove-CurrentTempDir -KeepTemp $keepTemp
        Close-Logger
    }
    return [int]$exitCode
}
