# ============================================================================
# MDT DO 服务器门户 - 服务器日志列表 / 下载 / 尾部预览（第二期）
#
# 安全要点：
#   - 文件名严格白名单（不递归、不接受路径分隔符），解析后的绝对路径必须位于 logs 目录内；
#   - 默认需要管理员登录（logs.requireLogin=true；日志含玩家 IP/聊天等敏感内容）；
#   - 每 IP 限频（logs.rateLimitPerWindow / rateWindowMinutes）；
#   - 同时下载数上限（logs.maxConcurrentDownloads），超限 429 BUSY + Retry-After；
#   - 单文件上限 logs.maxFileBytes（默认 50MB）；
#   - 分块输出（不整读进内存）：先发响应头，再由主循环的「下载泵」每次写一块；
#   - 下载开始/完成/中止都写 web-data/audit.log 审计。
# 兼容 Windows PowerShell 5.1 与 PowerShell 7+。
# ============================================================================

$script:PortalLogNameRules = @(
    @{ kind = "game-log"; pattern = '^log-(0|[1-9][0-9]{0,4})\.txt$' },
    @{ kind = "console"; pattern = '^console-\d{8}-\d{6}\.log$' },
    @{ kind = "last"; pattern = '^last\.log$' },
    @{ kind = "restart-supervisor"; pattern = '^restart-supervisor\.log$' }
)
$script:PortalDownloadChunkBytes = 262144   # 每次泵写 256KB（兼顾吞吐与主循环响应性）

function Get-PortalLogKind {
    # 严格白名单匹配；不匹配返回空串（调用方按 404/400 处理）。
    param([string]$Name)
    if ([string]::IsNullOrWhiteSpace($Name)) { return "" }
    if ($Name.Length -gt 128) { return "" }
    if ($Name.Contains("/") -or $Name.Contains("\") -or $Name.Contains("..") -or $Name.Contains("`0")) { return "" }
    foreach ($rule in $script:PortalLogNameRules) {
        if ([regex]::IsMatch($Name, $rule.pattern)) { return [string]$rule.kind }
    }
    return ""
}

function Resolve-PortalLogFile {
    # 返回 @{ok;name;kind;path;size}; 失败 @{ok=$false;code=...;message=...}
    param([System.Collections.IDictionary]$Config, [string]$Name)
    $kind = Get-PortalLogKind -Name $Name
    if ($kind -eq "") {
        return @{ ok = $false; code = "NOT_FOUND"; message = "日志文件不存在或不允许访问" }
    }
    $dir = Get-PortalString $Config.logsDirPath ""
    if ($dir -eq "" -or -not (Test-Path -LiteralPath $dir -PathType Container)) {
        return @{ ok = $false; code = "NOT_FOUND"; message = "日志目录不存在" }
    }
    $full = [System.IO.Path]::GetFullPath((Join-Path $dir $Name))
    if (-not (Test-PortalPathInside -ChildPath $full -ParentDir $dir)) {
        return @{ ok = $false; code = "NOT_FOUND"; message = "日志文件不存在或不允许访问" }
    }
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        return @{ ok = $false; code = "NOT_FOUND"; message = "日志文件不存在或不允许访问" }
    }
    $info = Get-Item -LiteralPath $full
    return @{ ok = $true; name = $Name; kind = $kind; path = $full; size = [long]$info.Length }
}

function Get-PortalLogList {
    param([System.Collections.IDictionary]$Config, [int]$Limit = 100)
    $result = [ordered]@{ available = $false; dir = (Get-PortalString $Config.logsDirPath ""); items = @(); count = 0 }
    if (-not (Get-PortalBool $Config.logs.enabled $true)) { return $result }
    $dir = Get-PortalString $Config.logsDirPath ""
    if ($dir -eq "" -or -not (Test-Path -LiteralPath $dir -PathType Container)) { return $result }
    $result.available = $true
    $safeLimit = [Math]::Max(1, [Math]::Min($Limit, 500))
    $items = New-Object System.Collections.ArrayList
    try {
        $files = Get-ChildItem -LiteralPath $dir -File -ErrorAction Stop
    } catch {
        Write-PortalLog -Config $Config -Level "WARN" -Message ("枚举日志目录失败：" + $_.Exception.Message)
        return $result
    }
    foreach ($f in $files) {
        $kind = Get-PortalLogKind -Name $f.Name
        if ($kind -eq "") { continue }
        [void]$items.Add([ordered]@{
            name       = $f.Name
            size       = [long]$f.Length
            modifiedAt = $f.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
            modifiedTs = [long]([System.DateTimeOffset]$f.LastWriteTimeUtc).ToUnixTimeMilliseconds()
            kind       = $kind
        })
    }
    $sorted = @($items | Sort-Object -Property @{ Expression = { [long]$_.modifiedTs } } -Descending)
    if ($sorted.Count -gt $safeLimit) { $sorted = @($sorted | Select-Object -First $safeLimit) }
    foreach ($item in $sorted) { $item.Remove("modifiedTs") }
    $result.items = @($sorted)
    $result.count = $sorted.Count
    return $result
}

function Get-PortalLogTail {
    # 从文件尾部按块反向读取最后 N 行（避免整读大文件）；返回 @{ok;lines;truncated;size}
    param([System.Collections.IDictionary]$Config, [string]$Path, [int]$Lines, [long]$MaxBytes)
    $maxLines = [Math]::Max(1, [Math]::Min($Lines, 5000))
    $maxBytesSafe = [Math]::Max(65536L, $MaxBytes)
    $result = @{ ok = $false; lines = @(); truncated = $false; size = 0L; message = "" }
    try {
        $fs = New-Object System.IO.FileStream($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
        try {
            $result.size = [long]$fs.Length
            $readBytes = [Math]::Min($fs.Length, $maxBytesSafe)
            $result.truncated = ($fs.Length -gt $readBytes)
            $fs.Seek([long](-$readBytes), [System.IO.SeekOrigin]::End) | Out-Null
            $buffer = New-Object byte[] ([int]$readBytes)
            $offset = 0
            while ($offset -lt $buffer.Length) {
                $n = $fs.Read($buffer, $offset, $buffer.Length - $offset)
                if ($n -le 0) { break }
                $offset += $n
            }
            $text = (Get-PortalUtf8NoBom).GetString($buffer, 0, $offset)
            # 处理从文件中间开始时可能的半行：截断第一行（若未从头读）
            if ($result.truncated) {
                $firstNl = $text.IndexOf("`n")
                if ($firstNl -ge 0) { $text = $text.Substring($firstNl + 1) }
            }
            $allLines = @($text -split "`n" | ForEach-Object { $_.TrimEnd("`r") })
            if ($allLines.Count -gt $maxLines) {
                $allLines = @($allLines | Select-Object -Last $maxLines)
                $result.truncated = $true
            }
            $result.lines = @($allLines)
            $result.ok = $true
        } finally {
            $fs.Dispose()
        }
    } catch {
        $result.message = $_.Exception.Message
    }
    return $result
}

function Get-PortalRangeSpec {
    # 解析单个 Range 请求（不支持的格式返回 unsat/格式错误，由调用方回 416）。
    param($Headers, [long]$Size)
    $result = @{ ok = $true; partial = $false; start = 0L; length = $Size; rangeHeader = ""; unsat = $false }
    $range = ""
    try { $range = [string]$Headers["Range"] } catch { }
    if ([string]::IsNullOrWhiteSpace($range)) { return $result }
    $m = [regex]::Match($range, '^\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*$', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $m.Success) { return @{ ok = $false; unsat = $true; partial = $false; start = 0L; length = 0L } }
    $startRaw = $m.Groups[1].Value
    $endRaw = $m.Groups[2].Value
    if ($startRaw -eq "" -and $endRaw -eq "") { return @{ ok = $false; unsat = $true } }
    $start = 0L
    $end = 0L
    if ($startRaw -eq "") {
        $suffix = 0L
        if (-not [long]::TryParse($endRaw, [ref]$suffix) -or $suffix -le 0) { return @{ ok = $false; unsat = $true } }
        $start = [Math]::Max(0L, $Size - $suffix)
        $end = $Size - 1
    } else {
        if (-not [long]::TryParse($startRaw, [ref]$start) -or $start -lt 0) { return @{ ok = $false; unsat = $true } }
        if ($endRaw -ne "") {
            if (-not [long]::TryParse($endRaw, [ref]$end) -or $end -lt $start) { return @{ ok = $false; unsat = $true } }
        } else {
            $end = $Size - 1
        }
    }
    if ($Size -le 0 -or $start -ge $Size) { return @{ ok = $false; unsat = $true } }
    if ($end -ge $Size) { $end = $Size - 1 }
    $length = $end - $start + 1
    return @{ ok = $true; partial = $true; start = $start; length = $length; rangeHeader = ("bytes {0}-{1}/{2}" -f $start, $end, $Size); unsat = $false }
}

function Send-PortalRangeNotSatisfiable {
    param($Context, [long]$Size)
    $body = (Get-PortalUtf8NoBom).GetBytes("range not satisfiable")
    Add-PortalSecurityHeaders -Context $Context
    try { $Context.Response.Headers.Add("Content-Range", "bytes */$Size") } catch { }
    $Context.Response.StatusCode = 416
    $Context.Response.ContentType = "text/plain; charset=utf-8"
    $Context.Response.ContentLength64 = $body.Length
    try { $Context.Response.OutputStream.Write($body, 0, $body.Length) } finally {
        try { $Context.Response.OutputStream.Close() } catch { }
        try { $Context.Response.Close() } catch { }
    }
}

function Start-PortalLogDownload {
    # 校验通过后开始「延迟分块」下载：发响应头 → 注册到下载泵 → 返回 $true（响应未关闭）。
    # 调用方在拿到 $true 后必须直接返回，让主循环继续处理其它请求 / 泵数据。
    param(
        $Context,
        [System.Collections.IDictionary]$Config,
        [string]$Name,
        [string]$ClientIp,
        [string]$Actor
    )
    $file = Resolve-PortalLogFile -Config $Config -Name $Name
    if (-not $file.ok) {
        Send-PortalApiError -Context $Context -StatusCode 404 -Code "NOT_FOUND" -Message $file.message
        return $false
    }
    $maxBytes = Get-PortalLong $Config.logs.maxFileBytes 52428800 1024 1073741824
    if ([long]$file.size -gt $maxBytes) {
        Send-PortalApiError -Context $Context -StatusCode 413 -Code "FILE_TOO_LARGE" -Message ("日志文件超过下载上限（{0} 字节）" -f $maxBytes)
        return $false
    }
    $maxConcurrent = Get-PortalInt $Config.logs.maxConcurrentDownloads 2 1 16
    if ($script:PortalDownloadActive.Count -ge $maxConcurrent) {
        Write-PortalAudit -Config $Config -Action "download_busy" -Actor $Actor -ClientIp $ClientIp -Detail ([ordered]@{ name = $file.name; active = $script:PortalDownloadActive.Count; limit = $maxConcurrent })
        Add-PortalSecurityHeaders -Context $Context
        try { $Context.Response.Headers.Add("Retry-After", "3") } catch { }
        Send-PortalApiError -Context $Context -StatusCode 429 -Code "BUSY" -Message ("同时下载数已达上限（{0}），请稍后再试" -f $maxConcurrent)
        return $false
    }
    $range = Get-PortalRangeSpec -Headers $Context.Request.Headers -Size ([long]$file.size)
    if (-not $range.ok) {
        Send-PortalRangeNotSatisfiable -Context $Context -Size ([long]$file.size)
        return $false
    }
    $stream = $null
    try {
        $stream = New-Object System.IO.FileStream($file.path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
        $stream.Seek([long]$range.start, [System.IO.SeekOrigin]::Begin) | Out-Null
    } catch {
        if ($null -ne $stream) { try { $stream.Dispose() } catch { } }
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "READ_FAILED" -Message "打开日志文件失败"
        return $false
    }
    $response = $Context.Response
    try {
        Add-PortalSecurityHeaders -Context $Context
        try { $response.Headers.Add("Content-Disposition", ('attachment; filename="{0}"' -f $file.name)) } catch { }
        try { $response.Headers.Add("Accept-Ranges", "bytes") } catch { }
        try { $response.Headers.Add("Cache-Control", "no-store") } catch { }
        if ($range.partial) {
            $response.StatusCode = 206
            try { $response.Headers.Add("Content-Range", [string]$range.rangeHeader) } catch { }
        } else {
            $response.StatusCode = 200
        }
        $response.ContentType = "text/plain; charset=utf-8"
        $response.ContentLength64 = [long]$range.length
    } catch {
        try { $stream.Dispose() } catch { }
        if (Test-PortalClientDisconnectError -Exception $_.Exception) {
            $script:PortalClientDisconnectCount++
            return $false
        }
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "READ_FAILED" -Message "开始下载响应失败"
        return $false
    }
    $entry = @{
        context   = $Context
        config    = $Config
        stream    = $stream
        path      = $file.path
        name      = $file.name
        kind      = $file.kind
        fileSize  = [long]$file.size
        offset    = [long]$range.start
        length    = [long]$range.length
        sent      = 0L
        partial   = [bool]$range.partial
        buffer    = (New-Object byte[] $script:PortalDownloadChunkBytes)
        outStream = $response.OutputStream
        pendingWrite = $null
        pendingLen   = 0
        startedAt = Get-PortalNowMillis
        clientIp  = $ClientIp
        actor     = $Actor
    }
    [void]$script:PortalDownloadActive.Add($entry)
    Write-PortalAudit -Config $Config -Action "download_start" -Actor $Actor -ClientIp $ClientIp -Detail ([ordered]@{
        name = $file.name; size = [long]$file.size; range = $(if ($range.partial) { [string]$range.rangeHeader } else { "full" })
    })
    Write-PortalLog -Config $Config -Level "INFO" -Message ("开始下载日志：{0}（{1} bytes{2}）ip={3} actor={4}" -f $file.name, $file.size, $(if ($range.partial) { "，Range " + $range.rangeHeader } else { "" }), $ClientIp, $Actor)
    $script:PortalResponseDeferred = $true
    return $true
}

function Complete-PortalDownloadEntry {
    param([System.Collections.IDictionary]$Config, $Entry, [string]$Status, [string]$ErrorMessage = "")
    try { $Entry.stream.Dispose() } catch { }
    try { $Entry.context.Response.OutputStream.Close() } catch { }
    try { $Entry.context.Response.Close() } catch { }
    try { [void]$script:PortalDownloadActive.Remove($Entry) } catch { }
    $durationMs = (Get-PortalNowMillis) - [long]$Entry.startedAt
    $detail = [ordered]@{
        name = [string]$Entry.name
        size = [long]$Entry.fileSize
        sent = [long]$Entry.sent
        partial = [bool]$Entry.partial
        durationMs = $durationMs
        status = $Status
    }
    if ($ErrorMessage -ne "") { $detail["error"] = $ErrorMessage }
    $auditAction = if ($Status -eq "ok") { "download_done" } elseif ($Status -eq "client_abort") { "download_client_abort" } else { "download_abort" }
    Write-PortalAudit -Config $Config -Action $auditAction -Actor ([string]$Entry.actor) -ClientIp ([string]$Entry.clientIp) -Detail $detail
    $logLevel = "WARN"
    if ($Status -eq "ok" -or $Status -eq "client_abort") { $logLevel = "INFO" }
    $statusText = $Status
    if ($Status -eq "client_abort") { $statusText = "客户端提前断开（正常）" }
    Write-PortalLog -Config $Config -Level $logLevel -Message ("日志下载结束：{0} status={1} sent={2}/{3} bytes 耗时={4}ms{5}" -f $Entry.name, $statusText, $Entry.sent, $Entry.length, $durationMs, $(if ($ErrorMessage) { " err=" + $ErrorMessage } else { "" }))
}

function Complete-PortalDownloadFailure {
    param([System.Collections.IDictionary]$Config, $Entry, $Exception)
    try { $Entry.context.Response.Abort() } catch { }
    if (Test-PortalClientDisconnectError -Exception $Exception) {
        # 客户端取消下载/提前断开：正常网络现象，不计 ERROR
        $script:PortalClientDisconnectCount++
        Complete-PortalDownloadEntry -Config $Config -Entry $Entry -Status "client_abort"
    } else {
        Complete-PortalDownloadEntry -Config $Config -Entry $Entry -Status "abort" -ErrorMessage ([string]$Exception.Message)
    }
}

function Invoke-PortalDownloadPump {
    # 主循环每轮调用：用异步写（BeginWrite/EndWrite 分离）向每个进行中的下载泵数据。
    # 不能用同步 Write：慢速客户端会把整个单线程门户主循环卡住（生产上会表现为所有 API 超时）。
    if ($script:PortalDownloadActive.Count -eq 0) { return }
    foreach ($entry in @($script:PortalDownloadActive)) {
        $entryConfig = $entry.config
        try {
            # 1) 处理上一次未完成的异步写
            if ($null -ne $entry.pendingWrite) {
                $iar = $entry.pendingWrite
                if (-not $iar.IsCompleted) { continue }
                try {
                    $entry.outStream.EndWrite($iar)
                } catch {
                    Complete-PortalDownloadFailure -Config $entryConfig -Entry $entry -Exception $_.Exception
                    continue
                }
                $entry.sent = [long]$entry.sent + [long]$entry.pendingLen
                $entry.pendingWrite = $null
                $entry.pendingLen = 0
                if ($entry.sent -ge $entry.length) {
                    Complete-PortalDownloadEntry -Config $entryConfig -Entry $entry -Status "ok"
                    continue
                }
            }
            # 2) 读下一块并挂起异步写
            $want = [int][Math]::Min([long]$entry.buffer.Length, ([long]$entry.length - [long]$entry.sent))
            $read = $entry.stream.Read($entry.buffer, 0, $want)
            if ($read -le 0) {
                Complete-PortalDownloadEntry -Config $entryConfig -Entry $entry -Status "abort" -ErrorMessage "unexpected EOF"
                continue
            }
            $entry.pendingWrite = $entry.outStream.BeginWrite($entry.buffer, 0, $read, $null, $null)
            $entry.pendingLen = $read
        } catch {
            Complete-PortalDownloadFailure -Config $entryConfig -Entry $entry -Exception $_.Exception
        }
    }
}

function Close-PortalDownloads {
    param([System.Collections.IDictionary]$Config)
    foreach ($entry in @($script:PortalDownloadActive)) {
        try { $entry.context.Response.Abort() } catch { }
        Complete-PortalDownloadEntry -Config $Config -Entry $entry -Status "abort" -ErrorMessage "server shutdown"
    }
}
