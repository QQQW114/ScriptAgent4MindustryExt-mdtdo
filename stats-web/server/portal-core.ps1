# ============================================================================
# MDT DO 服务器门户 - 核心模块（配置 / 日志 / 时间 / 密码哈希 / 会话 / 限频）
# 兼容 Windows PowerShell 5.1 与 PowerShell 7+；无任何外部依赖。
# 本文件由 start-web.ps1 dot-source 加载，函数与脚本级状态共享同一作用域。
# ============================================================================

$script:PortalDefaultConfigJson = @'
{
  "_comment": "MDT DO 服务器门户配置。相对路径以 stats-web 目录为基准；修改后重启 start-web.ps1 生效。",
  "_securityWarning": "默认管理员账号/密码与 session.secret 仅供首次启动！对外提供服务前必须修改 admin.password（或 admin.passwordHash）与 session.secret。",
  "bind": "127.0.0.1",
  "port": 8090,
  "statsJson": "../mdtserver/config/stats/server-status.json",
  "eventsJsonl": "../mdtserver/config/stats/live-events.jsonl",
  "dataDir": "web-data",
  "admin": {
    "username": "admin",
    "password": "ChangeMe_123456",
    "passwordHash": "",
    "sessionHours": 12
  },
  "session": {
    "secret": "CHANGE_ME_portal_session_secret_please_edit"
  },
  "upload": {
    "maxFileBytes": 10485760,
    "maxRequestBytes": 12582912,
    "queueLimitPerKind": 10,
    "historyLimit": 200,
    "allowedDpExtensions": [".zip", ".json", ".hjson", ".json5"],
    "allowedMapExtensions": [".msav"],
    "titleMaxChars": 200,
    "authorMaxChars": 100,
    "descMaxChars": 4000,
    "deleteRejectedPayloads": true
  },
  "rateLimit": {
    "loginFailuresPerWindow": 5,
    "loginWindowMinutes": 5,
    "uploadsPerWindow": 5,
    "uploadWindowMinutes": 10
  },
  "logs": {
    "enabled": true,
    "requireLogin": true,
    "dir": "../mdtserver/config/logs",
    "maxConcurrentDownloads": 2,
    "rateLimitPerWindow": 5,
    "rateWindowMinutes": 5,
    "maxFileBytes": 52428800,
    "tailMaxLines": 2000,
    "tailMaxBytes": 2097152
  },
  "bandwidth": {
    "enabled": true,
    "interface": "auto",
    "intervalSeconds": 10,
    "windowMinutes": 5,
    "seriesPoints": 30
  },
  "events": {
    "historyMaxFiles": 13,
    "historyCacheMaxBytes": 8388608,
    "historyRateLimitPerMinute": 60,
    "historyMaxKeywordChars": 100
  },
  "dirs": {
    "dp": "../mdtserver/config/scripts/external-cp",
    "maps": "../mdtserver/config/maps"
  },
  "console": {
    "enabled": true,
    "host": "127.0.0.1",
    "port": 6859,
    "waitSeconds": 30
  },
  "reload": {
    "dpOnApprove": false,
    "mapListOnApprove": true,
    "mapListOnRemove": true,
    "unloadOnRemove": false
  },
  "security": {
    "trustProxyHeaders": false,
    "maxPendingConsoleCommands": 8
  }
}
'@

$script:PortalDefaultAdminPassword = "ChangeMe_123456"
$script:PortalDefaultSessionSecret = "CHANGE_ME_portal_session_secret_please_edit"
$script:PortalSessions = New-Object 'System.Collections.Hashtable'
$script:PortalLoginFailures = New-Object 'System.Collections.Hashtable'
$script:PortalUploadRates = New-Object 'System.Collections.Hashtable'
$script:PortalLogRates = New-Object 'System.Collections.Hashtable'
$script:PortalHistoryRates = New-Object 'System.Collections.Hashtable'
# 下载是「先发响应头、后分块输出」的延迟完成模式；该标记表示当前请求的响应已由分块泵接管，
# 请求异常处理时不要再去写 JSON 错误体（否则会破坏已开始的流）。
$script:PortalResponseDeferred = $false
$script:PortalDownloadActive = New-Object System.Collections.ArrayList
$script:PortalMimeTypes = @{
    ".html" = "text/html; charset=utf-8"
    ".htm"  = "text/html; charset=utf-8"
    ".css"  = "text/css; charset=utf-8"
    ".js"   = "application/javascript; charset=utf-8"
    ".mjs"  = "application/javascript; charset=utf-8"
    ".json" = "application/json; charset=utf-8"
    ".txt"  = "text/plain; charset=utf-8"
    ".svg"  = "image/svg+xml"
    ".png"  = "image/png"
    ".jpg"  = "image/jpeg"
    ".jpeg" = "image/jpeg"
    ".webp" = "image/webp"
    ".gif"  = "image/gif"
    ".ico"  = "image/x-icon"
    ".bmp"  = "image/bmp"
    ".woff" = "font/woff"
    ".woff2" = "font/woff2"
    ".ttf"  = "font/ttf"
    ".otf"  = "font/otf"
    ".mp3"  = "audio/mpeg"
    ".ogg"  = "audio/ogg"
    ".wav"  = "audio/wav"
}

# ---------------------------------------------------------------------------
# 通用工具
# ---------------------------------------------------------------------------

function Get-PortalNowMillis {
    # .NET 4.6+ 提供 ToUnixTimeMilliseconds；旧环境回退到 UnixTimeSeconds 组合。
    try {
        return [long][System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    } catch {
        $utc = [System.DateTimeOffset]::UtcNow
        return [long]($utc.ToUnixTimeSeconds() * 1000L + $utc.Millisecond)
    }
}

function Get-PortalNowText {
    return (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
}

function Format-PortalMillis {
    param([long]$Millis)
    if ($Millis -le 0) { return "" }
    return [System.DateTimeOffset]::FromUnixTimeMilliseconds($Millis).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss")
}

function New-PortalRandomHex {
    param([int]$ByteCount = 16)
    $bytes = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return (($bytes | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Test-PortalStringEquals {
    # 长度不同直接失败；相同长度逐字符 XOR 累积，避免明显的提前返回时序差异。
    param([string]$A, [string]$B)
    if ($null -eq $A) { $A = "" }
    if ($null -eq $B) { $B = "" }
    if ($A.Length -ne $B.Length) { return $false }
    $diff = 0
    for ($i = 0; $i -lt $A.Length; $i++) {
        $diff = $diff -bor ([int]$A[$i] -bxor [int]$B[$i])
    }
    return ($diff -eq 0)
}

function Get-PortalUtf8NoBom {
    return (New-Object System.Text.UTF8Encoding($false))
}

function Write-PortalFileAtomic {
    # 先写同目录 .tmp，再替换目标文件；目标已存在时用 File.Replace（同卷原子替换）。
    # 注意：目标路径若被同名目录占位必须快速失败并抛错（否则 File.Replace 处理目录可能长时间阻塞）。
    param([string]$Path, [string]$Text, [string]$EncodingName = "utf8")
    if (Test-Path -LiteralPath $Path -PathType Container) {
        throw "目标路径是一个目录，无法写入文件：$Path（请先删除/改名该目录）"
    }
    $dir = [System.IO.Path]::GetDirectoryName($Path)
    if ($dir -and -not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    $tmp = "$Path.tmp-$(New-PortalRandomHex 4)"
    $enc = Get-PortalUtf8NoBom
    try {
        [System.IO.File]::WriteAllText($tmp, $Text, $enc)
        if (Test-Path -LiteralPath $Path) {
            $bak = "$Path.bak-$(New-PortalRandomHex 4)"
            try {
                [System.IO.File]::Replace($tmp, $Path, $bak)
                if (Test-Path -LiteralPath $bak) { Remove-Item -LiteralPath $bak -Force -ErrorAction SilentlyContinue }
            } catch {
                Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
                [System.IO.File]::Move($tmp, $Path)
            }
        } else {
            [System.IO.File]::Move($tmp, $Path)
        }
    } finally {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    }
}

function Read-PortalTextFile {
    # 允许其他进程（游戏服写入 live-events.jsonl）同时读写：FileShare.ReadWrite|Delete。
    param([string]$Path)
    $share = [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete
    $fs = New-Object System.IO.FileStream($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, $share)
    try {
        $sr = New-Object System.IO.StreamReader($fs, (Get-PortalUtf8NoBom), $true)
        try { return $sr.ReadToEnd() } finally { $sr.Dispose() }
    } finally {
        $fs.Dispose()
    }
}

function ConvertTo-PortalData {
    # PSCustomObject / IDictionary / IEnumerable 递归转换为 ordered hashtable / ArrayList，
    # 便于合并配置、按属性访问与 ConvertTo-Json 序列化。
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [string]) { return $Value }
    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        $h = [ordered]@{}
        foreach ($p in $Value.PSObject.Properties) {
            $h[$p.Name] = ConvertTo-PortalData $p.Value
        }
        return $h
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $h = [ordered]@{}
        foreach ($k in $Value.Keys) {
            $h[[string]$k] = ConvertTo-PortalData $Value[$k]
        }
        return $h
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        $list = New-Object System.Collections.ArrayList
        foreach ($item in $Value) { [void]$list.Add((ConvertTo-PortalData $item)) }
        return ,$list
    }
    return $Value
}

function Merge-PortalConfig {
    param([System.Collections.IDictionary]$Base, [System.Collections.IDictionary]$Override)
    foreach ($k in @($Override.Keys)) {
        $v = $Override[$k]
        if (($v -is [System.Collections.IDictionary]) -and ($Base[$k] -is [System.Collections.IDictionary])) {
            Merge-PortalConfig -Base $Base[$k] -Override $v
        } else {
            $Base[$k] = $v
        }
    }
    return $Base
}

function Get-PortalString {
    param($Value, [string]$Default = "")
    if ($null -eq $Value) { return $Default }
    return [string]$Value
}

function Get-PortalInt {
    param($Value, [int]$Default = 0, [int]$Min = [int]::MinValue, [int]$Max = [int]::MaxValue)
    $n = $Default
    if ($null -ne $Value) {
        $parsed = 0
        if ([int]::TryParse([string]$Value, [ref]$parsed)) { $n = $parsed }
    }
    if ($n -lt $Min) { $n = $Min }
    if ($n -gt $Max) { $n = $Max }
    return $n
}

function Get-PortalLong {
    param($Value, [long]$Default = 0, [long]$Min = [long]::MinValue, [long]$Max = [long]::MaxValue)
    $n = $Default
    if ($null -ne $Value) {
        $parsed = 0L
        if ([long]::TryParse([string]$Value, [ref]$parsed)) { $n = $parsed }
    }
    if ($n -lt $Min) { $n = $Min }
    if ($n -gt $Max) { $n = $Max }
    return $n
}

function Get-PortalBool {
    param($Value, [bool]$Default = $false)
    if ($null -eq $Value) { return $Default }
    if ($Value -is [bool]) { return $Value }
    $s = ([string]$Value).Trim().ToLowerInvariant()
    if ($s -eq "1" -or $s -eq "true" -or $s -eq "yes" -or $s -eq "on") { return $true }
    if ($s -eq "0" -or $s -eq "false" -or $s -eq "no" -or $s -eq "off") { return $false }
    return $Default
}

function Get-PortalStringList {
    param($Value, [string[]]$Default = @())
    if ($null -eq $Value) { return @($Default) }
    if ($Value -is [string]) {
        $parts = $Value.Split(",", [System.StringSplitOptions]::RemoveEmptyEntries)
        $out = @()
        foreach ($p in $parts) { $t = $p.Trim(); if ($t) { $out += $t } }
        if ($out.Count -eq 0) { return @($Default) }
        return $out
    }
    $out = @()
    foreach ($item in $Value) { $t = ([string]$item).Trim(); if ($t) { $out += $t } }
    if ($out.Count -eq 0) { return @($Default) }
    return $out
}

function Resolve-PortalPath {
    param([string]$BaseDir, [string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $BaseDir }
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BaseDir $Path))
}

function Test-PortalPathInside {
    param([string]$ChildPath, [string]$ParentDir)
    if ([string]::IsNullOrWhiteSpace($ChildPath) -or [string]::IsNullOrWhiteSpace($ParentDir)) { return $false }
    $parent = [System.IO.Path]::GetFullPath($ParentDir).TrimEnd('\', '/')
    $child = [System.IO.Path]::GetFullPath($ChildPath)
    if ($child.Equals($parent, [System.StringComparison]::OrdinalIgnoreCase)) { return $false }
    $prefix = $parent + [System.IO.Path]::DirectorySeparatorChar
    return $child.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
}

function Get-PortalSafeLeafName {
    # 仅取文件名末段并清洗控制字符；任何路径分隔符/.. 都会被拒绝（返回空串）。
    param([string]$RawName)
    if ([string]::IsNullOrWhiteSpace($RawName)) { return "" }
    $name = $RawName.Replace('\', '/')
    $leaf = ($name -split '/')[-1]
    $leaf = $leaf.Trim()
    if ([string]::IsNullOrWhiteSpace($leaf)) { return "" }
    if ($leaf -eq "." -or $leaf -eq "..") { return "" }
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $leaf.ToCharArray()) {
        $code = [int][char]$ch
        if ($code -lt 32 -or $code -eq 127) { continue }
        [void]$sb.Append($ch)
    }
    $leaf = $sb.ToString().Trim().TrimEnd('.').Trim()
    if ([string]::IsNullOrWhiteSpace($leaf)) { return "" }
    if ($leaf.Length -gt 150) {
        $ext = [System.IO.Path]::GetExtension($leaf)
        $base = [System.IO.Path]::GetFileNameWithoutExtension($leaf)
        if ($ext.Length -gt 20) { $ext = $ext.Substring(0, 20) }
        $keep = 150 - $ext.Length
        if ($keep -lt 1) { $keep = 1 }
        if ($base.Length -gt $keep) { $base = $base.Substring(0, $keep) }
        $leaf = $base + $ext
    }
    return $leaf
}

# ---------------------------------------------------------------------------
# 密码哈希（PBKDF2）
# ---------------------------------------------------------------------------

function New-PortalPasswordHash {
    param([string]$Password)
    if ($null -eq $Password) { $Password = "" }
    $salt = New-Object byte[] 16
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($salt) } finally { $rng.Dispose() }
    $iterations = 150000
    $algorithm = "sha256"
    $hashBytes = $null
    try {
        $derive = New-Object System.Security.Cryptography.Rfc2898DeriveBytes(
            $Password, $salt, $iterations, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
        try { $hashBytes = $derive.GetBytes(32) } finally { $derive.Dispose() }
    } catch {
        # .NET Framework 4.7.2 以下没有 SHA256 重载，回退 SHA1（格式里标明算法）。
        $algorithm = "sha1"
        $iterations = 200000
        $derive = New-Object System.Security.Cryptography.Rfc2898DeriveBytes($Password, $salt, $iterations)
        try { $hashBytes = $derive.GetBytes(32) } finally { $derive.Dispose() }
    }
    $saltB64 = [System.Convert]::ToBase64String($salt)
    $hashB64 = [System.Convert]::ToBase64String($hashBytes)
    return "pbkdf2`$$algorithm`$$iterations`$$saltB64`$$hashB64"
}

function Test-PortalPasswordHash {
    param([string]$Password, [string]$Encoded)
    if ([string]::IsNullOrWhiteSpace($Encoded)) { return $false }
    $parts = $Encoded.Split("`$")
    if ($parts.Count -ne 5) { return $false }
    if ($parts[0] -ne "pbkdf2") { return $false }
    $algorithm = $parts[1].ToLowerInvariant()
    $iterations = 0
    if (-not [int]::TryParse($parts[2], [ref]$iterations)) { return $false }
    if ($iterations -lt 1000 -or $iterations -gt 5000000) { return $false }
    try {
        $salt = [System.Convert]::FromBase64String($parts[3])
        $expected = [System.Convert]::FromBase64String($parts[4])
    } catch { return $false }
    $actual = $null
    try {
        if ($algorithm -eq "sha256") {
            $derive = New-Object System.Security.Cryptography.Rfc2898DeriveBytes(
                $Password, $salt, $iterations, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
            try { $actual = $derive.GetBytes($expected.Length) } finally { $derive.Dispose() }
        } elseif ($algorithm -eq "sha1") {
            $derive = New-Object System.Security.Cryptography.Rfc2898DeriveBytes($Password, $salt, $iterations)
            try { $actual = $derive.GetBytes($expected.Length) } finally { $derive.Dispose() }
        } else {
            return $false
        }
    } catch { return $false }
    if ($actual.Length -ne $expected.Length) { return $false }
    $diff = 0
    for ($i = 0; $i -lt $actual.Length; $i++) { $diff = $diff -bor ([int]$actual[$i] -bxor [int]$expected[$i]) }
    return ($diff -eq 0)
}

function Test-PortalAdminPassword {
    param([System.Collections.IDictionary]$Config, [string]$Password)
    $admin = $Config.admin
    if ($null -eq $admin) { return $false }
    $hash = Get-PortalString $admin.passwordHash ""
    if ($hash -ne "") {
        return (Test-PortalPasswordHash -Password $Password -Encoded $hash)
    }
    $plain = Get-PortalString $admin.password ""
    if ($plain -eq "") { return $false }
    return (Test-PortalStringEquals -A $plain -B $Password)
}

# ---------------------------------------------------------------------------
# 会话（内存态；允许同一账号多 IP 多会话）
# ---------------------------------------------------------------------------

function New-PortalSession {
    param([System.Collections.IDictionary]$Config, [string]$Username, [string]$ClientIp)
    $now = Get-PortalNowMillis
    $hours = Get-PortalInt $Config.admin.sessionHours 12 1 720
    $token = New-PortalRandomHex 32
    $session = @{
        token     = $token
        username  = $Username
        createdAt = $now
        expiresAt = $now + ([long]$hours * 3600000L)
        createdIp = $ClientIp
    }
    # 顺手清理过期会话，避免长期运行内存增长。
    $expired = @()
    foreach ($k in @($script:PortalSessions.Keys)) {
        $s = $script:PortalSessions[$k]
        if ($null -eq $s -or [long]$s.expiresAt -le $now) { $expired += $k }
    }
    foreach ($k in $expired) { $script:PortalSessions.Remove($k) }
    $script:PortalSessions[$token] = $session
    return $session
}

function Get-PortalSession {
    param([System.Collections.IDictionary]$Config, [string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) { return $null }
    if (-not $script:PortalSessions.ContainsKey($Token)) { return $null }
    $session = $script:PortalSessions[$Token]
    if ($null -eq $session) { return $null }
    if ([long]$session.expiresAt -le (Get-PortalNowMillis)) {
        $script:PortalSessions.Remove($Token)
        return $null
    }
    return $session
}

function Remove-PortalSession {
    param([string]$Token)
    if (-not [string]::IsNullOrWhiteSpace($Token) -and $script:PortalSessions.ContainsKey($Token)) {
        $script:PortalSessions.Remove($Token)
        return $true
    }
    return $false
}

# ---------------------------------------------------------------------------
# 限频（内存态：登录失败按 IP、上传按 IP）
# ---------------------------------------------------------------------------

function Get-PortalRatePruned {
    param([System.Collections.Hashtable]$Table, [string]$Key, [int]$WindowMinutes)
    $cutoff = (Get-PortalNowMillis) - ([long][Math]::Max(1, $WindowMinutes) * 60000L)
    $list = $null
    if ($Table.ContainsKey($Key)) { $list = $Table[$Key] }
    $kept = New-Object System.Collections.ArrayList
    if ($null -ne $list) {
        foreach ($t in $list) { if ([long]$t -gt $cutoff) { [void]$kept.Add([long]$t) } }
    }
    $Table[$Key] = $kept
    # 注意：PowerShell 会展开函数返回的集合，空 ArrayList 会变成 $null；
    # 用前置逗号包一层，保证调用方拿到 ArrayList 对象本身。
    return ,$kept
}

function Test-PortalLoginBlocked {
    param([System.Collections.IDictionary]$Config, [string]$ClientIp)
    $limit = Get-PortalInt $Config.rateLimit.loginFailuresPerWindow 5 1 1000
    $window = Get-PortalInt $Config.rateLimit.loginWindowMinutes 5 1 1440
    $list = Get-PortalRatePruned -Table $script:PortalLoginFailures -Key $ClientIp -WindowMinutes $window
    return ($list.Count -ge $limit)
}

function Add-PortalLoginFailure {
    param([System.Collections.IDictionary]$Config, [string]$ClientIp)
    $window = Get-PortalInt $Config.rateLimit.loginWindowMinutes 5 1 1440
    $list = Get-PortalRatePruned -Table $script:PortalLoginFailures -Key $ClientIp -WindowMinutes $window
    [void]$list.Add((Get-PortalNowMillis))
    $script:PortalLoginFailures[$ClientIp] = $list
}

function Reset-PortalLoginFailures {
    param([string]$ClientIp)
    if ($script:PortalLoginFailures.ContainsKey($ClientIp)) { $script:PortalLoginFailures.Remove($ClientIp) }
}

function Test-PortalUploadAllowed {
    param([System.Collections.IDictionary]$Config, [string]$ClientIp)
    $limit = Get-PortalInt $Config.rateLimit.uploadsPerWindow 5 1 1000
    $window = Get-PortalInt $Config.rateLimit.uploadWindowMinutes 10 1 1440
    $list = Get-PortalRatePruned -Table $script:PortalUploadRates -Key $ClientIp -WindowMinutes $window
    if ($list.Count -ge $limit) { return $false }
    [void]$list.Add((Get-PortalNowMillis))
    $script:PortalUploadRates[$ClientIp] = $list
    return $true
}

function Test-PortalRateAllowed {
    # 通用「每 IP N 次 / M 分钟」限频：允许时记一次并返回 true；超限返回 false（不记录）。
    # 返回 @{ allowed; used; limit; retryAfterSeconds }，便于调用方设置 Retry-After。
    param([System.Collections.Hashtable]$Table, [string]$Key, [int]$Limit, [int]$WindowMinutes)
    $limitSafe = [Math]::Max(1, $Limit)
    $windowSafe = [Math]::Max(1, $WindowMinutes)
    $list = Get-PortalRatePruned -Table $Table -Key $Key -WindowMinutes $windowSafe
    if ($list.Count -ge $limitSafe) {
        $oldest = [long]$list[0]
        $retryAfter = [int][Math]::Ceiling((($oldest + ([long]$windowSafe * 60000L)) - (Get-PortalNowMillis)) / 1000.0)
        if ($retryAfter -lt 1) { $retryAfter = 1 }
        return @{ allowed = $false; used = $list.Count; limit = $limitSafe; retryAfterSeconds = $retryAfter }
    }
    [void]$list.Add((Get-PortalNowMillis))
    $Table[$Key] = $list
    return @{ allowed = $true; used = $list.Count; limit = $limitSafe; retryAfterSeconds = 0 }
}

function Test-PortalLogAccessAllowed {
    param([System.Collections.IDictionary]$Config, [string]$ClientIp)
    $limit = Get-PortalInt $Config.logs.rateLimitPerWindow 5 1 10000
    $window = Get-PortalInt $Config.logs.rateWindowMinutes 5 1 1440
    return (Test-PortalRateAllowed -Table $script:PortalLogRates -Key $ClientIp -Limit $limit -WindowMinutes $window)
}

function Test-PortalHistoryAllowed {
    param([System.Collections.IDictionary]$Config, [string]$ClientIp)
    $limit = Get-PortalInt $Config.events.historyRateLimitPerMinute 60 1 100000
    return (Test-PortalRateAllowed -Table $script:PortalHistoryRates -Key $ClientIp -Limit $limit -WindowMinutes 1)
}

# ---------------------------------------------------------------------------
# 日志
# ---------------------------------------------------------------------------

function Write-PortalLog {
    param([System.Collections.IDictionary]$Config, [string]$Level, [string]$Message)
    $line = "[$(Get-PortalNowText)] [$Level] $Message"
    $color = "Gray"
    if ($Level -eq "WARN") { $color = "Yellow" } elseif ($Level -eq "ERROR") { $color = "Red" } elseif ($Level -eq "INFO") { $color = "Cyan" }
    try { Write-Host $line -ForegroundColor $color } catch { Write-Host $line }
    try {
        if ($Config -and $Config.dataDirPath) {
            $logFile = Join-Path $Config.dataDirPath "portal.log"
            if (Test-Path -LiteralPath $logFile) {
                $size = (Get-Item -LiteralPath $logFile).Length
                if ($size -gt 5242880) {
                    $rot = Join-Path $Config.dataDirPath "portal.1.log"
                    if (Test-Path -LiteralPath $rot) { Remove-Item -LiteralPath $rot -Force -ErrorAction SilentlyContinue }
                    Move-Item -LiteralPath $logFile -Destination $rot -Force -ErrorAction SilentlyContinue
                }
            }
            $enc = Get-PortalUtf8NoBom
            [System.IO.File]::AppendAllText($logFile, $line + "`r`n", $enc)
        }
    } catch { }
}

function Write-PortalAudit {
    # 审计日志：动作 / 操作者 / IP / 明细；一行一个 JSON，便于 grep 与机器处理。
    param([System.Collections.IDictionary]$Config, [string]$Action, [string]$Actor, [string]$ClientIp, $Detail)
    if ($null -eq $Config -or -not $Config.dataDirPath) { return }
    try {
        $record = [ordered]@{
            ts     = Get-PortalNowMillis
            time   = Get-PortalNowText
            action = $Action
            actor  = $Actor
            ip     = $ClientIp
            detail = $Detail
        }
        $json = ConvertTo-Json -InputObject $record -Depth 8 -Compress
        $file = Join-Path $Config.dataDirPath "audit.log"
        $enc = Get-PortalUtf8NoBom
        [System.IO.File]::AppendAllText($file, $json + "`n", $enc)
    } catch { }
}

function Write-PortalConsoleLog {
    param([System.Collections.IDictionary]$Config, [string]$Command, [string]$Output, [string]$Status)
    if ($null -eq $Config -or -not $Config.dataDirPath) { return }
    try {
        $record = [ordered]@{
            ts      = Get-PortalNowMillis
            time    = Get-PortalNowText
            command = $Command
            status  = $Status
            output  = $Output
        }
        $json = ConvertTo-Json -InputObject $record -Depth 6 -Compress
        $file = Join-Path $Config.dataDirPath "console-actions.log"
        $enc = Get-PortalUtf8NoBom
        [System.IO.File]::AppendAllText($file, $json + "`n", $enc)
    } catch { }
}

# ---------------------------------------------------------------------------
# 配置加载与初始化
# ---------------------------------------------------------------------------

function Initialize-PortalConfig {
    param([System.Collections.IDictionary]$Config, [string]$WebRoot)
    # 路径解析：配置内相对路径统一以 stats-web 目录（WebRoot）为基准。
    $Config["webRoot"] = [System.IO.Path]::GetFullPath($WebRoot)
    $Config["statsJsonPath"] = Resolve-PortalPath -BaseDir $Config.webRoot -Path (Get-PortalString $Config.statsJson "../mdtserver/config/stats/server-status.json")
    $Config["eventsJsonlPath"] = Resolve-PortalPath -BaseDir $Config.webRoot -Path (Get-PortalString $Config.eventsJsonl "../mdtserver/config/stats/live-events.jsonl")
    $Config["dataDirPath"] = Resolve-PortalPath -BaseDir $Config.webRoot -Path (Get-PortalString $Config.dataDir "web-data")
    $Config["dpDirPath"] = Resolve-PortalPath -BaseDir $Config.webRoot -Path (Get-PortalString $Config.dirs.dp "../mdtserver/config/scripts/external-cp")
    $Config["mapsDirPath"] = Resolve-PortalPath -BaseDir $Config.webRoot -Path (Get-PortalString $Config.dirs.maps "../mdtserver/config/maps")
    $Config["uploadsDirPath"] = Join-Path $Config.dataDirPath "uploads"
    $Config["submissionsPath"] = Join-Path $Config.dataDirPath "submissions.json"
    $Config["catalogMetaPath"] = Join-Path $Config.dataDirPath "catalog-meta.json"

    if (-not (Test-Path -LiteralPath $Config.dataDirPath)) {
        New-Item -ItemType Directory -Force -Path $Config.dataDirPath | Out-Null
    }
    if (-not (Test-Path -LiteralPath $Config.uploadsDirPath)) {
        New-Item -ItemType Directory -Force -Path $Config.uploadsDirPath | Out-Null
    }

    # 数值/字符串规范化（防止配置文件写错导致运行期异常）。
    if ($null -eq $Config.admin) { $Config.admin = [ordered]@{} }
    $Config.admin.username = Get-PortalString $Config.admin.username "admin"
    $Config.admin.password = Get-PortalString $Config.admin.password ""
    $Config.admin.passwordHash = Get-PortalString $Config.admin.passwordHash ""
    $Config.admin.sessionHours = Get-PortalInt $Config.admin.sessionHours 12 1 720
    if (-not $Config.admin) { }
    if (($Config.admin.password -eq "") -and ($Config.admin.passwordHash -eq "")) {
        $Config.admin.password = $script:PortalDefaultAdminPassword
    }

    if ($null -eq $Config.upload) { $Config.upload = [ordered]@{} }
    $Config.upload.maxFileBytes = Get-PortalLong $Config.upload.maxFileBytes 10485760 1024 104857600
    $Config.upload.maxRequestBytes = Get-PortalLong $Config.upload.maxRequestBytes ($Config.upload.maxFileBytes + 2097152) ([long]$Config.upload.maxFileBytes + 65536) 209715200
    $Config.upload.queueLimitPerKind = Get-PortalInt $Config.upload.queueLimitPerKind 10 1 100
    $Config.upload.historyLimit = Get-PortalInt $Config.upload.historyLimit 200 20 5000
    $Config.upload.allowedDpExtensions = Get-PortalStringList $Config.upload.allowedDpExtensions @(".zip", ".json", ".hjson", ".json5")
    $Config.upload.allowedMapExtensions = Get-PortalStringList $Config.upload.allowedMapExtensions @(".msav")
    $Config.upload.titleMaxChars = Get-PortalInt $Config.upload.titleMaxChars 200 1 1000
    $Config.upload.authorMaxChars = Get-PortalInt $Config.upload.authorMaxChars 100 1 200
    $Config.upload.descMaxChars = Get-PortalInt $Config.upload.descMaxChars 4000 1 20000
    $Config.upload.deleteRejectedPayloads = Get-PortalBool $Config.upload.deleteRejectedPayloads $true

    if ($null -eq $Config.rateLimit) { $Config.rateLimit = [ordered]@{} }
    $Config.rateLimit.loginFailuresPerWindow = Get-PortalInt $Config.rateLimit.loginFailuresPerWindow 5 1 1000
    $Config.rateLimit.loginWindowMinutes = Get-PortalInt $Config.rateLimit.loginWindowMinutes 5 1 1440
    $Config.rateLimit.uploadsPerWindow = Get-PortalInt $Config.rateLimit.uploadsPerWindow 5 1 1000
    $Config.rateLimit.uploadWindowMinutes = Get-PortalInt $Config.rateLimit.uploadWindowMinutes 10 1 1440

    # ---- 日志下载（第二期） ----
    if ($null -eq $Config.logs) { $Config.logs = [ordered]@{} }
    $Config.logs.enabled = Get-PortalBool $Config.logs.enabled $true
    $Config.logs.requireLogin = Get-PortalBool $Config.logs.requireLogin $true
    $Config.logs.dir = Get-PortalString $Config.logs.dir "../mdtserver/config/logs"
    $Config.logs.maxConcurrentDownloads = Get-PortalInt $Config.logs.maxConcurrentDownloads 2 1 16
    $Config.logs.rateLimitPerWindow = Get-PortalInt $Config.logs.rateLimitPerWindow 5 1 10000
    $Config.logs.rateWindowMinutes = Get-PortalInt $Config.logs.rateWindowMinutes 5 1 1440
    $Config.logs.maxFileBytes = Get-PortalLong $Config.logs.maxFileBytes 52428800 1024 1073741824
    $Config.logs.tailMaxLines = Get-PortalInt $Config.logs.tailMaxLines 2000 10 5000
    $Config.logs.tailMaxBytes = Get-PortalLong $Config.logs.tailMaxBytes 2097152 65536 67108864
    $Config["logsDirPath"] = Resolve-PortalPath -BaseDir $Config.webRoot -Path (Get-PortalString $Config.logs.dir "../mdtserver/config/logs")

    # ---- 上行带宽采样（第二期） ----
    if ($null -eq $Config.bandwidth) { $Config.bandwidth = [ordered]@{} }
    $Config.bandwidth.enabled = Get-PortalBool $Config.bandwidth.enabled $true
    $Config.bandwidth.interface = Get-PortalString $Config.bandwidth.interface "auto"
    $Config.bandwidth.intervalSeconds = Get-PortalInt $Config.bandwidth.intervalSeconds 10 2 300
    $Config.bandwidth.windowMinutes = Get-PortalInt $Config.bandwidth.windowMinutes 5 1 60
    $Config.bandwidth.seriesPoints = Get-PortalInt $Config.bandwidth.seriesPoints 30 0 240

    # ---- 历史事件（第二期） ----
    if ($null -eq $Config.events) { $Config.events = [ordered]@{} }
    $Config.events.historyMaxFiles = Get-PortalInt $Config.events.historyMaxFiles 13 1 100
    $Config.events.historyCacheMaxBytes = Get-PortalLong $Config.events.historyCacheMaxBytes 8388608 1048576 134217728
    $Config.events.historyRateLimitPerMinute = Get-PortalInt $Config.events.historyRateLimitPerMinute 60 1 100000
    $Config.events.historyMaxKeywordChars = Get-PortalInt $Config.events.historyMaxKeywordChars 100 1 500

    if ($null -eq $Config.console) { $Config.console = [ordered]@{} }
    $Config.console.enabled = Get-PortalBool $Config.console.enabled $true
    $Config.console.host = Get-PortalString $Config.console.host "127.0.0.1"
    $Config.console.port = Get-PortalInt $Config.console.port 6859 1 65535
    $Config.console.waitSeconds = Get-PortalInt $Config.console.waitSeconds 30 2 120

    if ($null -eq $Config.reload) { $Config.reload = [ordered]@{} }
    $Config.reload.dpOnApprove = Get-PortalBool $Config.reload.dpOnApprove $false
    $Config.reload.mapListOnApprove = Get-PortalBool $Config.reload.mapListOnApprove $true
    $Config.reload.mapListOnRemove = Get-PortalBool $Config.reload.mapListOnRemove $true
    $Config.reload.unloadOnRemove = Get-PortalBool $Config.reload.unloadOnRemove $false

    if ($null -eq $Config.security) { $Config.security = [ordered]@{} }
    $Config.security.maxPendingConsoleCommands = Get-PortalInt $Config.security.maxPendingConsoleCommands 8 1 64

    if ($null -eq $Config.session) { $Config.session = [ordered]@{} }
    $Config.session.secret = Get-PortalString $Config.session.secret $script:PortalDefaultSessionSecret

    $Config.bind = Get-PortalString $Config.bind "127.0.0.1"
    $Config.port = Get-PortalInt $Config.port 8090 1 65535

    return $Config
}

function Read-PortalConfigFile {
    param([string]$Path)
    $text = Read-PortalTextFile -Path $Path
    if ([string]::IsNullOrWhiteSpace($text)) { return [ordered]@{} }
    $obj = ConvertFrom-Json -InputObject $text
    $data = ConvertTo-PortalData $obj
    if ($data -isnot [System.Collections.IDictionary]) { $data = [ordered]@{} }
    return $data
}

function New-PortalConfig {
    param([string]$WebRoot, [string]$ConfigPath)
    $defaults = ConvertTo-PortalData (ConvertFrom-Json -InputObject $script:PortalDefaultConfigJson)
    $config = $defaults
    $config["configPath"] = $ConfigPath
    # 只读取「文件」形式的配置；路径被目录占位时按缺失处理（由调用方决定迁移/回退/默认值），避免抛错中断启动。
    if ($ConfigPath -and (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        $user = Read-PortalConfigFile -Path $ConfigPath
        [void](Merge-PortalConfig -Base $config -Override $user)
    }
    return $config
}

# ---------------------------------------------------------------------------
# 旧版配置自动迁移（web-config.ps1 → web-config.json）
#
# 背景：旧部署只有一个 web-config.ps1（$WebBind/$WebPort/$WebStatsJson），生产上往往
# 已经改成 0.0.0.0 + 自定义端口、公网可访问。新版门户主配置是 web-config.json，
# 为了让旧部署「替换文件即升级、无需手工改配置」，启动时自动把旧配置迁移成新 JSON，
# 并把旧文件改名备份（幂等：迁移后旧文件不存在，后续启动不会重复迁移）。
# ---------------------------------------------------------------------------

function Read-PortalLegacyConfigValues {
    # 读取旧版 web-config.ps1 的 $WebBind/$WebPort/$WebStatsJson。
    # 返回 @{ ok; values; error }。ok=false 表示解析与文本提取都失败。
    param([string]$LegacyPath)
    $result = @{ ok = $false; values = [ordered]@{}; error = "" }
    $text = ""
    try { $text = Read-PortalTextFile -Path $LegacyPath } catch {
        $result.error = "读取旧配置文件失败：" + $_.Exception.Message
        return $result
    }
    # 优先在隔离作用域内 dot-source：能按真实 PowerShell 语义处理注释/变量引用。
    try {
        $captured = @(& {
            param($p)
            . $p
            $out = [ordered]@{}
            if ($null -ne $WebBind) { $out.bind = [string]$WebBind }
            if ($null -ne $WebPort) { $out.port = [int]$WebPort }
            if ($null -ne $WebStatsJson) { $out.statsJson = [string]$WebStatsJson }
            return $out
        } $LegacyPath)
        $parsed = $null
        foreach ($item in $captured) {
            if ($item -is [System.Collections.IDictionary]) { $parsed = $item }
        }
        if ($null -ne $parsed) {
            $result.values = $parsed
            $result.ok = $true
            return $result
        }
        $result.error = "旧配置文件执行后未读到配置变量"
    } catch {
        $result.error = "旧配置解析异常：" + $_.Exception.Message
    }
    # 兜底：解析失败时按文本正则提取关键项，尽量保住生产 bind/port 不丢。
    $heuristic = [ordered]@{}
    try {
        $m = [regex]::Match($text, '(?m)^\s*\$WebBind\s*=\s*[''"]([^''"]*)[''"]')
        if ($m.Success) { $heuristic.bind = $m.Groups[1].Value }
        $m = [regex]::Match($text, '(?m)^\s*\$WebPort\s*=\s*(\d+)')
        if ($m.Success) { $heuristic.port = [int]$m.Groups[1].Value }
        $m = [regex]::Match($text, '(?m)^\s*\$WebStatsJson\s*=\s*[''"]([^''"]*)[''"]')
        if ($m.Success) { $heuristic.statsJson = $m.Groups[1].Value }
    } catch { }
    if ($heuristic.Contains("bind") -or $heuristic.Contains("port") -or $heuristic.Contains("statsJson")) {
        $result.values = $heuristic
        $result.ok = $true
        $result.error = $result.error + "（已按文本提取关键项继续迁移）"
        return $result
    }
    return $result
}

function Invoke-PortalLegacyConfigMigration {
    # 返回 @{ migrated; usedLegacyFallback; backupPath; warning; values }
    # -AllowMigration=false 时不迁移（例如 -Config 指定了自定义路径），避免误改仓库旧配置。
    param([string]$Root, [string]$ConfigPath, [bool]$AllowMigration)
    $result = @{ migrated = $false; usedLegacyFallback = $false; backupPath = ""; warning = ""; values = $null }
    $legacyPath = Join-Path $Root "web-config.ps1"
    $configExists = Test-Path -LiteralPath $ConfigPath -PathType Leaf
    $legacyExists = Test-Path -LiteralPath $legacyPath -PathType Leaf
    if ($configExists) {
        if ($legacyExists) {
            Write-Host "[提示] 检测到旧版 web-config.ps1，但已存在 web-config.json（新版配置优先）；旧文件不生效，确认无用后可删除。" -ForegroundColor DarkYellow
        }
        return $result
    }
    if (-not $legacyExists) { return $result }
    if (-not $AllowMigration) {
        Write-Host "[提示] 使用 -Config 指定了自定义配置路径，跳过旧版 web-config.ps1 自动迁移（不生成 web-config.json）。" -ForegroundColor Yellow
        return $result
    }
    Write-Host "[升级] 检测到旧版 web-config.ps1 且没有 web-config.json，正在自动迁移配置……" -ForegroundColor Cyan
    $legacy = Read-PortalLegacyConfigValues -LegacyPath $legacyPath
    if ("" -ne [string]$legacy.error) {
        Write-Host ("[警告] " + [string]$legacy.error) -ForegroundColor Yellow
        $result.warning = [string]$legacy.error
    }
    if (-not $legacy.ok) {
        $result.warning = ($result.warning + "；旧配置无法解析，将回退旧启动方式").Trim("；")
        return $result
    }
    $values = $legacy.values
    # 缺省用旧版默认值（历史 start-web.ps1 默认为 127.0.0.1:8081），不要臆造新默认。
    if (-not $values.Contains("bind") -or "" -eq [string]$values.bind) { $values.bind = "127.0.0.1" }
    if (-not $values.Contains("port") -or [int]$values.port -le 0) { $values.port = 8081 }
    $userConfig = [ordered]@{
        "_comment"         = "本文件由旧版 web-config.ps1 自动迁移生成。相对路径以 stats-web 目录为基准；修改后重启 start-web.ps1 生效。"
        "_migratedFrom"    = "web-config.ps1"
        "_migratedAt"      = Get-PortalNowText
        "_securityWarning" = "自动迁移生成：管理员账号/密码仍是默认值（admin / ChangeMe_123456），请立即修改 admin.password（或改用 admin.passwordHash），并修改 session.secret 后再对外提供服务。"
        "bind"             = [string]$values.bind
        "port"             = [int]$values.port
    }
    if ($values.Contains("statsJson") -and "" -ne [string]$values.statsJson) {
        $userConfig["statsJson"] = [string]$values.statsJson
    }
    try {
        $json = ConvertTo-Json -InputObject $userConfig -Depth 8
        Write-PortalFileAtomic -Path $ConfigPath -Text $json
    } catch {
        $err = "生成 web-config.json 失败：" + $_.Exception.Message
        Write-Host ("[警告] " + $err + "；将回退使用旧版 web-config.ps1 的值启动。") -ForegroundColor Yellow
        $result.usedLegacyFallback = $true
        $result.values = $values
        $result.warning = ($result.warning + "；" + $err).Trim("；")
        return $result
    }
    $result.migrated = $true
    $statsShown = "（默认）"
    if ($values.Contains("statsJson") -and "" -ne [string]$values.statsJson) { $statsShown = [string]$values.statsJson }
    Write-Host ("[升级] 已把旧配置迁移到 web-config.json：bind={0} port={1} statsJson={2}" -f `
        [string]$values.bind, [int]$values.port, $statsShown) -ForegroundColor Green
    # 改名备份旧文件：之后启动看到的是 web-config.json，天然幂等、不会重复迁移。
    try {
        $backupName = "web-config.ps1.bak-" + (Get-Date).ToString("yyyyMMdd-HHmmss")
        $backupPath = Join-Path $Root $backupName
        Move-Item -LiteralPath $legacyPath -Destination $backupPath -Force
        $result.backupPath = $backupPath
        Write-Host ("[升级] 旧配置已备份为：{0}" -f $backupName) -ForegroundColor DarkGray
    } catch {
        $err = "旧配置改名备份失败（不影响启动，下次启动仍以 web-config.json 为准）：" + $_.Exception.Message
        Write-Host ("[警告] " + $err) -ForegroundColor Yellow
        $result.warning = ($result.warning + "；" + $err).Trim("；")
    }
    Write-Host "[升级][安全] 迁移后的管理员账号/密码为默认值（admin / ChangeMe_123456），请立即修改！" -ForegroundColor Red
    return $result
}
