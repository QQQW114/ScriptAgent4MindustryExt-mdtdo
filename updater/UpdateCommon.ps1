# ============================================================================
# MDT 更新器公共模块（由三个入口脚本 dot-source 使用）
# 要求：Windows PowerShell 5.1+；本文件需保存为 UTF-8 with BOM。
#
# 入口脚本（update-plugins.ps1 / update-web.ps1 /
# update-plugins-clear-cache.ps1）只负责解析参数，然后调用：
#   Invoke-MdtUpdater -Kind <plugins|web|plugins-clear-cache> -UpdaterDir <本目录> -Cli <参数表>
# 返回值即进程退出码（0 成功 / 1 失败 / 2 拒绝执行）。
# ============================================================================

$script:UpdaterVersion = '1.0.0'
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
        [Parameter(Mandatory = $true)][string[]]$RelPaths
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

function Assert-ServerStopped {
    param([Parameter(Mandatory = $true)]$Config)
    $gamePort = [int]$Config.GamePort
    Write-Log ('检查服务器是否已关闭（端口 {0} / 游戏 java 进程）...' -f $gamePort)
    $listeningPids = @(Get-ListeningOwnerPids -Port $gamePort)
    $gameJavas = @(Get-GameJavaProcesses -Config $Config)

    if ($listeningPids.Count -eq 0 -and $gameJavas.Count -eq 0) {
        Write-Log '未检测到运行中的服务器，可以安全更新。' 'OK'
        return
    }

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add(('检测到服务器仍在运行，已中止更新（更新器要求先关闭服务器）。')) | Out-Null
    foreach ($ownerPid in $listeningPids) {
        $detail = ''
        try {
            $proc = Get-CimInstance -ClassName Win32_Process -Filter ("ProcessId = {0}" -f $ownerPid) -ErrorAction Stop
            if ($null -ne $proc) {
                $detail = '{0}，命令行：{1}' -f ([string]$proc.Name), (Format-CommandLineForLog -CommandLine ([string]$proc.CommandLine))
            }
        } catch { }
        $lines.Add(('  端口 {0} 正在监听：PID={1}（{2}）' -f $gamePort, $ownerPid, $detail)) | Out-Null
    }
    foreach ($process in $gameJavas) {
        $lines.Add(('  疑似游戏服 java 进程：PID={0}，命令行：{1}' -f $process.ProcessId, (Format-CommandLineForLog -CommandLine $process.CommandLine))) | Out-Null
    }
    $lines.Add('请先关闭服务器（关掉「启动服务器.bat」窗口，或结束对应 java 进程）后重新运行本脚本。') | Out-Null
    $lines.Add('如果确认服务器已关闭但仍有误报：检查 config.json 的 gamePort 是否与 server.properties 的 port 一致。') | Out-Null
    throw ('REFUSED: ' + ($lines -join [Environment]::NewLine))
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
    } else {
        Write-Log '正式执行时不会自动启动/重启服务器；更新完成后请自行启动服务器验证。'
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

    # ---------- 5. 备份（+ 可选清缓存） ----------
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

    # ---------- 收尾提示 ----------
    Write-Log ''
    Write-Log ('严格镜像完成：新增 {0} / 修改 {1} / 删除 {2} / 未变 {3}；复制与哈希校验通过。' -f $analysis.Added.Count, $analysis.Modified.Count, $analysis.Deleted.Count, $analysis.UnchangedCount) 'OK'
    if ($Settings.IsWeb) {
        foreach ($line in (Get-WebRunningHint -Config $Config)) { Write-Log $line 'WARN' }
    } elseif ($Settings.ClearCache) {
        Write-Log '缓存已清空：启动服务器时会重新编译全部脚本（首次启动会慢一些），这是预期行为。'
        Write-Log '请启动服务器（运行 mdtserver\启动服务器.bat）后检查 config\logs\script-load\last-error.log 为空、脚本加载数量正常。'
    } else {
        Write-Log '更新器不会自动启动服务器。请自行启动服务器（运行 mdtserver\启动服务器.bat）验证脚本加载正常。'
    }

    $summaryExtra = ''
    if ($null -ne $backup) { $summaryExtra = '；备份 ' + $backup.Timestamp } else { $summaryExtra = '；未备份' }
    if ($Settings.ClearCache -and $null -ne $cacheStats) { $summaryExtra += ('；缓存清理 ' + $cacheStats.Files + ' 个文件') }
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

    if ($backupFiles.Count -eq 0 -and -not [bool]$Cli.Force) {
        throw ('REFUSED: 备份是空的（{0}）：回滚会把目标目录恢复到空状态，风险过高已拒绝。确认要清空请加 -Force。' -f $backup.FullName)
    }

    if ($Cli.DryRun) {
        Write-Step '回滚预览（-DryRun：不修改任何文件）'
        Write-Log ('将恢复文件：{0} 个（备份目录 → {1}）' -f $backupFiles.Count, $Settings.TargetPath)
        Write-Log ('将删除「该次更新新增、备份中不存在」的文件：{0} 个' -f @($addedRelPaths).Count)
        Write-FileList -Title '将删除的文件' -Files $addedRelPaths
        Write-Log 'DryRun：未修改任何文件。'
        return New-UpdateResult -ExitCode 0 -Summary ('DryRun 回滚预览完成（备份 {0}：将恢复 {1} 个文件，删除 {2} 个新增文件）。' -f $backup.Name, $backupFiles.Count, @($addedRelPaths).Count)
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

    if ($Settings.IsWeb) {
        foreach ($line in (Get-WebRunningHint -Config $Config)) { Write-Log $line 'WARN' }
    } else {
        Write-Log '回滚完成。请启动服务器验证；如需确认版本，可查看本日志与备份元数据。'
    }
    return New-UpdateResult -ExitCode 0 -Summary ('回滚完成（备份 {0}）：恢复 {1} 个文件，删除更新新增文件 {2} 个。' -f $backup.Name, $restore.Restored, $restore.Deleted)
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
        Write-Log ('要求停服（requireServerStopped）：{0}' -f $Config.RequireServerStopped)
        Write-Log ('游戏端口（gamePort）：{0}' -f $Config.GamePort)
    }
    Write-Log ('备份根目录：{0}（本类备份：{1}，保留 {2} 份）' -f $Config.BackupPath, $Settings.BackupRoot, $Config.KeepBackups)
    if (-not [string]::IsNullOrWhiteSpace([string]$Config.LogDir)) { Write-Log ('日志目录：{0}' -f $Config.LogDir) }
    if ($Settings.ClearCache) {
        Write-Log ('缓存目录（cachePath）：{0}' -f (Resolve-CachePath -Config $Config -Settings $Settings))
    }
    if (-not $Settings.IsWeb -and [int]$Config.WebPort -gt 0) { Write-Log ('Web 端口（webPort）：{0}' -f $Config.WebPort) }
    $flags = New-Object System.Collections.Generic.List[string]
    foreach ($name in @('DryRun', 'Force', 'NoBackup', 'Rollback', 'KeepTemp')) {
        if ($Cli.ContainsKey($name) -and [bool]$Cli[$name]) { $flags.Add($name) | Out-Null }
    }
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

        # 预检：必须停服（插件/清缓存类；Web 更新不需要停游戏服；DryRun 不改文件故不检查）
        if ($settings.NeedsServerStopped) {
            if ([bool]$Cli.DryRun) {
                Write-Log 'DryRun：跳过停服检查（预览不会改动任何文件；正式执行前必须关闭服务器）。' 'WARN'
            } else {
                Assert-ServerStopped -Config $config
            }
        } elseif (-not $settings.IsWeb -and -not [bool]$Cli.DryRun) {
            Write-Log '配置 requireServerStopped=false：本次跳过停服检查（不推荐在服务器运行时更新文件！）' 'WARN'
        }

        # 回滚 / 更新
        $isRollback = ([bool]$Cli.Rollback -or ($Cli.ContainsKey('RollbackTo') -and -not [string]::IsNullOrWhiteSpace([string]$Cli.RollbackTo)))
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
