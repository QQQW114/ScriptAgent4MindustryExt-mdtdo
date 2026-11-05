# ============================================================================
# MDT DO 服务器门户 - HTTP 基础模块
# 响应发送 / 请求体读取 / multipart 解析 / 上传魔数校验 / 静态文件映射
# 兼容 Windows PowerShell 5.1 与 PowerShell 7+。
# ============================================================================

function Add-PortalSecurityHeaders {
    param($Context)
    $headers = $Context.Response.Headers
    try { $headers.Add("X-Content-Type-Options", "nosniff") } catch { }
    try { $headers.Add("X-Frame-Options", "SAMEORIGIN") } catch { }
    try { $headers.Add("Referrer-Policy", "no-referrer") } catch { }
    # L-4：务实的最小 CSP。前端全部同源本地资源、无内联 <script>、无外链 CDN；
    # 样式有大量 element.style 与内联 style 属性，所以 style-src 需要 'unsafe-inline'。
    try {
        $headers.Add("Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'")
    } catch { }
    try { $headers.Add("X-Permitted-Cross-Domain-Policies", "none") } catch { }
}

# ---------------------------------------------------------------------------
# 客户端断开类异常识别（生产日志里 77/80 条 ERROR 都是这类「正常网络现象」）：
# 客户端提前关闭页面/取消下载时，写响应会抛 IOException / HttpListenerException /
# ObjectDisposedException，message 常见 "network name is no longer available"、
# "connection was aborted"、"Cannot access a disposed object"、"non-connected socket" 等。
# 这类错误静默忽略（仅累计计数，/api/health 可见），不打 ERROR 堆栈。
# ---------------------------------------------------------------------------

$script:PortalClientDisconnectCount = 0L
$script:PortalErrorLogTs = New-Object 'System.Collections.Hashtable'

function Get-PortalExceptionMessageChain {
    param($Exception)
    $messages = New-Object System.Collections.ArrayList
    $current = $Exception
    $depth = 0
    while ($null -ne $current -and $depth -lt 6) {
        try { if (-not [string]::IsNullOrWhiteSpace([string]$current.Message)) { [void]$messages.Add([string]$current.Message) } } catch { }
        try { $current = $current.InnerException } catch { $current = $null }
        $depth++
    }
    return ,$messages
}

function Test-PortalClientDisconnectError {
    param($Exception)
    if ($null -eq $Exception) { return $false }
    $patterns = @(
        "network name is no longer available",
        "connection was aborted",
        "connection was reset",
        "connection reset by peer",
        "an established connection was aborted",
        "broken pipe",
        "pipe is being closed",
        "non-connected socket",
        "cannot access a disposed object",
        "disposed object",
        "the specified network name",
        "指定的网络名不再可用",
        "网络名不再可用",
        "远程主机强迫关闭",
        "已中止",
        "连接被关闭",
        "连接已关闭",
        "对象已释放",
        "无法访问已释放",
        "非连接的套接字",
        "操作已被取消"
    )
    foreach ($msg in (Get-PortalExceptionMessageChain -Exception $Exception)) {
        foreach ($pattern in $patterns) {
            if ($msg.IndexOf($pattern, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
        }
    }
    # HttpListenerException / SocketException 的部分错误码也属于对端断开
    try {
        if ($Exception -is [System.Net.HttpListenerException]) {
            if ($Exception.ErrorCode -in @(64, 1236, 995, 10054, 10053)) { return $true }
        }
        if ($Exception -is [System.Net.Sockets.SocketException]) {
            if ($Exception.SocketErrorCode -in @([System.Net.Sockets.SocketError]::ConnectionReset, [System.Net.Sockets.SocketError]::ConnectionAborted)) { return $true }
        }
    } catch { }
    return $false
}

function Send-PortalResponseBytes {
    param(
        $Context,
        [int]$StatusCode,
        [string]$ContentType,
        [byte[]]$Body,
        [System.Collections.IDictionary]$Headers,
        [switch]$HeadOnly
    )
    if ($null -eq $Body) { $Body = New-Object byte[] 0 }
    $response = $Context.Response
    # 1) 响应头阶段：客户端可能已断开/上下文已销毁，这类异常忽略
    try {
        Add-PortalSecurityHeaders -Context $Context
        if ($Headers) {
            foreach ($k in @($Headers.Keys)) {
                try { $response.Headers.Add([string]$k, [string]$Headers[$k]) } catch { }
            }
        }
        $response.StatusCode = $StatusCode
        $response.ContentType = $ContentType
    } catch {
        if (Test-PortalClientDisconnectError -Exception $_.Exception) {
            $script:PortalClientDisconnectCount++
            return
        }
        throw
    }
    # 2) 写响应体阶段：客户端提前断开是正常现象 → 静默忽略并计数
    try {
        if ($HeadOnly) {
            $response.ContentLength64 = 0
        } else {
            $response.ContentLength64 = $Body.Length
            $response.OutputStream.Write($Body, 0, $Body.Length)
        }
    } catch {
        if (Test-PortalClientDisconnectError -Exception $_.Exception) {
            $script:PortalClientDisconnectCount++
        } else {
            # 非断开类错误：交给上层记录（ERROR 限频 + 堆栈前 3 帧）
            try { $response.OutputStream.Close() } catch { }
            try { $response.Close() } catch { }
            throw
        }
    } finally {
        try { $response.OutputStream.Close() } catch { }
        try { $response.Close() } catch { }
    }
}

function Send-PortalJson {
    param($Context, [int]$StatusCode, $Object, [System.Collections.IDictionary]$Headers, [switch]$HeadOnly)
    $json = ConvertTo-Json -InputObject $Object -Depth 16 -Compress
    $bytes = (Get-PortalUtf8NoBom).GetBytes($json)
    if (-not $Headers) { $Headers = @{} }
    if (-not $Headers.Contains("Cache-Control")) { $Headers["Cache-Control"] = "no-store" }
    Send-PortalResponseBytes -Context $Context -StatusCode $StatusCode -ContentType "application/json; charset=utf-8" `
        -Body $bytes -Headers $Headers -HeadOnly:$HeadOnly
}

function Send-PortalApiOk {
    param($Context, $Data)
    Send-PortalJson -Context $Context -StatusCode 200 -Object ([ordered]@{ ok = $true; data = $Data })
}

function Send-PortalApiError {
    param($Context, [int]$StatusCode, [string]$Code, [string]$Message)
    $body = [ordered]@{
        ok    = $false
        error = [ordered]@{ code = $Code; message = $Message }
    }
    Send-PortalJson -Context $Context -StatusCode $StatusCode -Object $body
}

function Read-PortalRequestBody {
    # 返回 @{ok;tooLarge;timeout;bytes;length;reason}
    # H-1 / R1-A：
    #   1) Content-Length 超过上限时**立即**拒绝（tooLarge），不进读取循环；
    #   2) 单次读停滞上限 200ms + 全局「每秒停滞预算」400ms：4 个慢速连接最多消耗 400ms 总等待，
    #      之后的 body 请求直接 408（0ms），保证后续正常请求 < 500ms 得到响应；
    #   3) 总超时 30s、最低平均速率 2KB/s 兜底「慢滴」；
    #   4) 按 Content-Length 一次分配精确缓冲区（不再 MemoryStream + ToArray 双份）。
    param($Context, [long]$MaxBytes, [System.Collections.IDictionary]$Config = $null)
    $request = $Context.Request
    $length = -1L
    try { $length = [long]$request.ContentLength64 } catch { $length = -1L }
    if ($length -gt $MaxBytes) {
        return @{ ok = $false; tooLarge = $true; timeout = $false; bytes = $null; length = $length; reason = "content-length" }
    }
    if ($length -le 0) {
        # 无 Content-Length（chunked）当前不支持：按空体处理，由调用方拒绝，而不是阻塞等待。
        return @{ ok = $true; tooLarge = $false; timeout = $false; bytes = (New-Object byte[] 0); length = 0; reason = "no-length" }
    }
    $timeoutMs = 30000L
    $minRate = 2048L
    $stallMs = 200L
    $budgetMs = 400L
    $budgetWindowMs = 1000L
    if ($null -ne $Config) {
        try {
            $timeoutMs = [long](Get-PortalInt $Config.security.requestReadTimeoutSeconds 30 5 300) * 1000L
            $minRate = [long](Get-PortalInt $Config.security.minReadBytesPerSecond 2048 256 1048576)
            $stallMs = [long](Get-PortalInt $Config.security.readStallTimeoutMs 200 50 5000)
            $budgetMs = [long](Get-PortalInt $Config.security.readStallBudgetMs 400 0 60000)
            $budgetWindowMs = [long](Get-PortalInt $Config.security.readStallWindowMs 1000 100 60000)
        } catch { }
    }
    # R2-1：全局停滞预算**只作用于大 body**（Content-Length > 64KB，实际只有上传）。
    # 登录/审核/驳回等小 JSON 请求走「每请求独立 200ms 停滞上限」这条路径，
    # 所以上传方向的慢连接再也无法把管理面拖成 408（第 2 轮的回归）。
    # 预算耗尽时对大 body 返回 busy（调用方回 503 + Retry-After），而不是 408 ——
    # 408 会让客户端立刻重发、撞同一堵墙；503 明确告诉它"服务端忙，稍后再来"。
    $isLargeBody = ($length -gt 65536)
    $budgetDisabled = ($budgetMs -le 0) -or (-not $isLargeBody)
    $nowStart = Get-PortalNowMillis
    if (-not $budgetDisabled -and ($nowStart - [long]$script:PortalReadWindowStart) -ge $budgetWindowMs) {
        $script:PortalReadWindowStart = $nowStart
        $script:PortalReadStallUsedMs = 0
    }
    $allowance = $stallMs
    if (-not $budgetDisabled) {
        $allowance = [long]$budgetMs - [long]$script:PortalReadStallUsedMs
        if ($allowance -lt 0) { $allowance = 0 }
        if ($allowance -gt $stallMs) { $allowance = $stallMs }
        if ($allowance -le 0) {
            return @{ ok = $false; tooLarge = $false; timeout = $false; busy = $true; bytes = $null; length = 0; reason = "budget" }
        }
    }
    $effectiveStall = $allowance
    $buffer = $null
    try { $buffer = New-Object byte[] ([int]$length) } catch {
        return @{ ok = $false; tooLarge = $true; timeout = $false; bytes = $null; length = $length; reason = "alloc" }
    }
    $stream = $request.InputStream
    $started = Get-PortalNowMillis
    $deadline = $started + $timeoutMs
    $offset = 0
    try {
        while ($offset -lt $buffer.Length) {
            if ((Get-PortalNowMillis) -ge $deadline) {
                return @{ ok = $false; tooLarge = $false; timeout = $true; bytes = $null; length = [long]$offset; reason = "deadline" }
            }
            $want = [int][Math]::Min(65536, $buffer.Length - $offset)
            $chargeBudget = (-not $budgetDisabled) -and ($offset -lt 65536)
            $readStart = Get-PortalNowMillis
            $task = $stream.ReadAsync($buffer, $offset, $want)
            while (-not $task.IsCompleted) {
                $now = Get-PortalNowMillis
                if ($now -ge $deadline) {
                    return @{ ok = $false; tooLarge = $false; timeout = $true; bytes = $null; length = [long]$offset; reason = "deadline" }
                }
                if (($now - $readStart) -ge $effectiveStall) {
                    if ($chargeBudget) { $script:PortalReadStallUsedMs = [long]$script:PortalReadStallUsedMs + ($now - $readStart) }
                    return @{ ok = $false; tooLarge = $false; timeout = $true; bytes = $null; length = [long]$offset; reason = "stall" }
                }
                try { [void]$task.Wait(50) } catch { }
            }
            if ($chargeBudget) {
                $waited = (Get-PortalNowMillis) - $readStart
                $script:PortalReadStallUsedMs = [long]$script:PortalReadStallUsedMs + $waited
            }
            $read = 0
            try { $read = $task.Result } catch { $read = -1 }
            if ($read -lt 0) {
                return @{ ok = $false; tooLarge = $false; timeout = $true; bytes = $null; length = [long]$offset; reason = "read-error" }
            }
            if ($read -eq 0) {
                # 对端已关闭但字节数不够：立刻失败，不等待
                return @{ ok = $false; tooLarge = $false; timeout = $true; bytes = $null; length = [long]$offset; reason = "eof" }
            }
            $offset += $read
            $elapsed = (Get-PortalNowMillis) - $started
            if ($elapsed -ge 1000L -and $offset -gt 0) {
                $rate = ([double]$offset * 1000.0) / ([double]$elapsed)
                if ($rate -lt [double]$minRate) {
                    return @{ ok = $false; tooLarge = $false; timeout = $true; bytes = $null; length = [long]$offset; reason = "slow" }
                }
            }
        }
        return @{ ok = $true; tooLarge = $false; timeout = $false; bytes = $buffer; length = [long]$offset; reason = "" }
    } catch {
        return @{ ok = $false; tooLarge = $false; timeout = $true; bytes = $null; length = [long]$offset; reason = "exception" }
    }
}
function Read-PortalRequestJson {
    # 返回 @{ok;tooLarge;timeout;data}；data 为 PSCustomObject（空体/坏 JSON 时为 $null）。
    param($Context, [long]$MaxBytes = 1048576, [System.Collections.IDictionary]$Config = $null)
    $raw = Read-PortalRequestBody -Context $Context -MaxBytes $MaxBytes -Config $Config
    if (-not $raw.ok) {
        if ($raw.timeout) { return @{ ok = $false; tooLarge = $false; timeout = $true; data = $null } }
        return @{ ok = $false; tooLarge = $true; timeout = $false; data = $null }
    }
    if ($raw.length -le 0) { return @{ ok = $true; tooLarge = $false; timeout = $false; data = $null } }
    $text = (Get-PortalUtf8NoBom).GetString($raw.bytes).Trim()
    if ($text -eq "") { return @{ ok = $true; tooLarge = $false; timeout = $false; data = $null } }
    try {
        $data = ConvertFrom-Json -InputObject $text -ErrorAction Stop
        return @{ ok = $true; tooLarge = $false; timeout = $false; data = $data }
    } catch {
        return @{ ok = $false; tooLarge = $false; timeout = $false; data = $null; badJson = $true }
    }
}

function Get-PortalBearerToken {
    param($Context)
    $header = $Context.Request.Headers["Authorization"]
    if ([string]::IsNullOrWhiteSpace($header)) { return "" }
    $m = [regex]::Match($header, '^\s*Bearer\s+(\S+)\s*$', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if ($m.Success) { return $m.Groups[1].Value }
    return ""
}

function Get-PortalClientIp {
    # L-2/B-02：X-Forwarded-For 只有在「直连对端本身是受信代理」时才采信，
    # 且取**最右侧**的非受信段（不是最左侧——最左侧完全由客户端伪造）。
    # 未配置 security.trustedProxies 时一律使用套接字地址，XFF 完全忽略。
    param($Context, [System.Collections.IDictionary]$Config)
    $remote = ""
    try { $remote = $Context.Request.RemoteEndPoint.Address.ToString() } catch { }
    if ($Config -and (Get-PortalBool $Config.security.trustProxyHeaders $false)) {
        $trusted = @()
        try { $trusted = @(Get-PortalStringList $Config.security.trustedProxies @()) } catch { $trusted = @() }
        $isTrustedPeer = $false
        foreach ($rule in $trusted) {
            if (Test-PortalIpInCidr -Ip $remote -Rule $rule) { $isTrustedPeer = $true; break }
        }
        if ($isTrustedPeer) {
            $xff = ""
            try { $xff = [string]$Context.Request.Headers["X-Forwarded-For"] } catch { }
            if (-not [string]::IsNullOrWhiteSpace($xff)) {
                $chain = @($xff.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
                for ($i = $chain.Count - 1; $i -ge 0; $i--) {
                    $cand = $chain[$i]
                    $candTrusted = $false
                    foreach ($rule in $trusted) {
                        if (Test-PortalIpInCidr -Ip $cand -Rule $rule) { $candTrusted = $true; break }
                    }
                    if (-not $candTrusted) { $remote = $cand; break }
                }
            }
        }
    }
    if ($remote.StartsWith("::ffff:")) { $remote = $remote.Substring(7) }
    if ($remote -eq "::1") { $remote = "127.0.0.1" }
    return $remote
}

function Get-PortalQueryValue {
    # 手动解析原始查询串（RawUrl）再按 UTF-8 做 percent-decode。
    # 原因：HttpListener.QueryString 会把 percent-encoded UTF-8 按系统 ANSI 解码，
    # 中文 keyword（如「奶龙」）会变成乱码；浏览器/前端用 encodeURIComponent 时
    # RawUrl 保留 %XX，自己 UnescapeDataString 可正确还原 UTF-8。
    param($Context, [string]$Name, [string]$Default = "")
    try {
        $rawUrl = [string]$Context.Request.RawUrl
        $qIndex = $rawUrl.IndexOf('?')
        if ($qIndex -ge 0) {
            $queryText = $rawUrl.Substring($qIndex + 1)
            $hashIndex = $queryText.IndexOf('#')
            if ($hashIndex -ge 0) { $queryText = $queryText.Substring(0, $hashIndex) }
            if ($queryText -ne "") {
                foreach ($pair in $queryText.Split('&')) {
                    if ($pair -eq "") { continue }
                    $eq = $pair.IndexOf('=')
                    if ($eq -lt 0) { $keyRaw = $pair; $valueRaw = "" } else { $keyRaw = $pair.Substring(0, $eq); $valueRaw = $pair.Substring($eq + 1) }
                    try { $keyText = [Uri]::UnescapeDataString($keyRaw) } catch { $keyText = $keyRaw }
                    if ($keyText -ne $Name) { continue }
                    try { return [Uri]::UnescapeDataString($valueRaw) } catch { return $valueRaw }
                }
            }
            return $Default
        }
        # 没有 "?" 时退回标准实现（纯 ASCII 场景）
        $v = $Context.Request.QueryString[$Name]
        if ($null -eq $v) { return $Default }
        return [string]$v
    } catch { return $Default }
}

function Get-PortalQueryInt {
    param($Context, [string]$Name, [int]$Default = 0, [int]$Min = [int]::MinValue, [int]$Max = [int]::MaxValue)
    $raw = Get-PortalQueryValue -Context $Context -Name $Name -Default ""
    $parsed = 0
    if ([int]::TryParse($raw, [ref]$parsed)) {
        if ($parsed -lt $Min) { return $Min }
        if ($parsed -gt $Max) { return $Max }
        return $parsed
    }
    return $Default
}

function Get-PortalQueryLong {
    # epoch 毫秒级时间戳（约 1.7e12）超出 Int32，必须用 long 解析。
    param($Context, [string]$Name, [long]$Default = 0, [long]$Min = [long]::MinValue, [long]$Max = [long]::MaxValue)
    $raw = Get-PortalQueryValue -Context $Context -Name $Name -Default ""
    $parsed = 0L
    if ([long]::TryParse($raw, [ref]$parsed)) {
        if ($parsed -lt $Min) { return $Min }
        if ($parsed -gt $Max) { return $Max }
        return $parsed
    }
    return $Default
}

function Get-PortalFileExtension {
    param([string]$FileName)
    if ([string]::IsNullOrWhiteSpace($FileName)) { return "" }
    return [System.IO.Path]::GetExtension($FileName).ToLowerInvariant()
}

function Test-PortalAllowedExtension {
    param([string]$FileName, $AllowedExtensions)
    $ext = Get-PortalFileExtension -FileName $FileName
    if ($ext -eq "") { return $false }
    foreach ($allowed in @($AllowedExtensions)) {
        if ($ext -eq ([string]$allowed).ToLowerInvariant()) { return $true }
    }
    return $false
}

function Test-PortalZipMagic {
    # M-2：不再只看 "PK" 两字节。要求：
    #   1) 开头是本地文件头 PK\x03\x04 或空档的 EOCD PK\x05\x06；
    #   2) 文件末尾 65557 字节内存在 EOCD 签名（zip 结构上成立）。
    param([byte[]]$Bytes, [int]$Offset = 0, [int]$Count = -1)
    if ($null -eq $Bytes) { return $false }
    $len = $Bytes.Length
    if ($Count -ge 0) { $len = [Math]::Min($len, $Offset + $Count) }
    if (($len - $Offset) -lt 22) { return $false }
    $p0 = [int]$Offset
    $local = ($Bytes[$p0] -eq 0x50 -and $Bytes[$p0 + 1] -eq 0x4B -and $Bytes[$p0 + 2] -eq 0x03 -and $Bytes[$p0 + 3] -eq 0x04)
    $empty = ($Bytes[$p0] -eq 0x50 -and $Bytes[$p0 + 1] -eq 0x4B -and $Bytes[$p0 + 2] -eq 0x05 -and $Bytes[$p0 + 3] -eq 0x06)
    if (-not ($local -or $empty)) { return $false }
    $scanStart = [Math]::Max($p0, $len - 65557)
    for ($i = $len - 22; $i -ge $scanStart; $i--) {
        if ($Bytes[$i] -eq 0x50 -and $Bytes[$i + 1] -eq 0x4B -and $Bytes[$i + 2] -eq 0x05 -and $Bytes[$i + 3] -eq 0x06) { return $true }
    }
    return $false
}

function Test-PortalJsonLikeMagic {
    # 仅用于 .hjson / .json5（不是合法 JSON，无法严格解析）：跳过 BOM/空白后必须是 { 或 [。
    param([byte[]]$Bytes, [int]$Offset = 0, [int]$Count = -1)
    if ($null -eq $Bytes) { return $false }
    $end = $Bytes.Length
    if ($Count -ge 0) { $end = [Math]::Min($end, $Offset + $Count) }
    if (($end - $Offset) -lt 2) { return $false }
    $i = [int]$Offset
    if (($end - $i) -ge 3 -and $Bytes[$i] -eq 0xEF -and $Bytes[$i + 1] -eq 0xBB -and $Bytes[$i + 2] -eq 0xBF) { $i = $i + 3 }
    while ($i -lt $end -and ($Bytes[$i] -eq 0x20 -or $Bytes[$i] -eq 0x09 -or $Bytes[$i] -eq 0x0A -or $Bytes[$i] -eq 0x0D)) { $i++ }
    if ($i -ge $end) { return $false }
    return ($Bytes[$i] -eq 0x7B -or $Bytes[$i] -eq 0x5B)   # '{' or '['
}

function Test-PortalJsonStrict {
    # M-2：对 .json 真正解析一遍（只判首字符等于没校验）。
    # 注意：.hjson / .json5 不是合法 JSON，调用方不要用这个函数。
    param([byte[]]$Bytes, [int]$Offset = 0, [int]$Count = -1)
    if ($null -eq $Bytes) { return $false }
    if ($Count -lt 0) { $Count = $Bytes.Length - $Offset }
    if ($Count -le 1 -or $Count -gt 20971520) { return $false }
    $text = ""
    try { $text = (Get-PortalUtf8NoBom).GetString($Bytes, $Offset, $Count).Trim() } catch { return $false }
    if ($text -eq "") { return $false }
    if (-not ($text.StartsWith("{") -or $text.StartsWith("["))) { return $false }
    try {
        $null = ConvertFrom-Json -InputObject $text -ErrorAction Stop
        return $true
    } catch { return $false }
}

function Test-PortalMsavMagic {
    # M-2：不只验头字节 —— 真正解压出前 16 字节，要求是 Mindustry 存档结构：
    #   明文 "MSAV" + big-endian int 版本号（1..1000）。
    # Mindustry 用 zlib（2 字节头 + raw deflate），部分工具产出 gzip，两者都接受。
    param([byte[]]$Bytes, [int]$Offset = 0, [int]$Count = -1)
    if ($null -eq $Bytes) { return $false }
    if ($Count -lt 0) { $Count = $Bytes.Length - $Offset }
    if ($Count -lt 12) { return $false }
    $isGzip = ($Bytes[$Offset] -eq 0x1F -and $Bytes[$Offset + 1] -eq 0x8B)
    if (-not $isGzip) {
        $cmf = [int]$Bytes[$Offset]
        $flg = [int]$Bytes[$Offset + 1]
        if (($cmf -band 0x0F) -ne 8) { return $false }
        if (((($cmf * 256) + $flg) % 31) -ne 0) { return $false }
        if (($flg -band 0x20) -ne 0) { return $false }   # 预置字典不受支持
    }
    $head = New-Object byte[] 16
    $n = 0
    try {
        $ms = New-Object System.IO.MemoryStream($Bytes, $Offset, $Count, $false)
        try {
            if ($isGzip) {
                $z = New-Object System.IO.Compression.GZipStream($ms, [System.IO.Compression.CompressionMode]::Decompress)
                try { $n = $z.Read($head, 0, 16) } finally { $z.Dispose() }
            } else {
                $ms.Seek(2, [System.IO.SeekOrigin]::Begin) | Out-Null
                $z = New-Object System.IO.Compression.DeflateStream($ms, [System.IO.Compression.CompressionMode]::Decompress)
                try { $n = $z.Read($head, 0, 16) } finally { $z.Dispose() }
            }
        } finally { $ms.Dispose() }
    } catch { return $false }
    if ($n -lt 8) { return $false }
    if (-not ($head[0] -eq 0x4D -and $head[1] -eq 0x53 -and $head[2] -eq 0x41 -and $head[3] -eq 0x56)) { return $false }
    $ver = ([int]$head[4] -shl 24) -bor ([int]$head[5] -shl 16) -bor ([int]$head[6] -shl 8) -bor [int]$head[7]
    if ($ver -lt 1 -or $ver -gt 1000) { return $false }
    return $true
}

function Get-PortalMimeType {
    param([string]$FileName)
    $ext = Get-PortalFileExtension -FileName $FileName
    if ($script:PortalMimeTypes.ContainsKey($ext)) { return $script:PortalMimeTypes[$ext] }
    return "application/octet-stream"
}

function Find-PortalBytes {
    # 在字节数组中查找子串。用 Array.IndexOf 原生定位首字节，再逐字节确认；
    # 带候选数预算，避免「全 0x2D」之类退化输入把主循环拖死。
    param([byte[]]$Haystack, [byte[]]$Needle, [int]$Start = 0, [int]$MaxCandidates = 2000000)
    if ($null -eq $Haystack -or $null -eq $Needle -or $Needle.Length -eq 0) { return -1 }
    $limit = $Haystack.Length - $Needle.Length
    if ($limit -lt 0) { return -1 }
    $i = [Math]::Max(0, $Start)
    $candidates = 0
    $first = [byte]$Needle[0]
    while ($i -le $limit) {
        $idx = [System.Array]::IndexOf($Haystack, $first, $i)
        if ($idx -lt 0 -or $idx -gt $limit) { return -1 }
        $candidates++
        if ($candidates -gt $MaxCandidates) { return -1 }
        $ok = $true
        for ($j = 1; $j -lt $Needle.Length; $j++) {
            if ($Haystack[$idx + $j] -ne $Needle[$j]) { $ok = $false; break }
        }
        if ($ok) { return $idx }
        $i = $idx + 1
    }
    return -1
}

function Read-PortalMultipart {
    # 解析 multipart/form-data。
    # M-4：
    #   - 先按 Content-Length 硬拒超限（不读进内存）；
    #   - 字节级扫描 boundary（Array.IndexOf 原生定位），不再把整个 body 转字符串再 Split；
    #   - 分片数上限在切分**之前**用扫描预算判断，避免 Split 产生百万级子串；
    #   - 文件内容用 (offset,count) 引用 body 缓冲区，零复制；落盘时再流式写出。
    # 返回 @{ok;error;fields(name=>string);files(@{name;fileName;offset;count;size});body}
    param($Context, [long]$MaxRequestBytes, [long]$MaxFileBytes, [System.Collections.IDictionary]$Config = $null)
    $result = @{ ok = $false; error = ""; fields = @{}; files = @(); body = $null }
    $contentType = ""
    try { $contentType = [string]$Context.Request.ContentType } catch { }
    if ([string]::IsNullOrWhiteSpace($contentType)) {
        $result.error = "缺少 Content-Type"
        return $result
    }
    $boundary = ""
    $m = [regex]::Match($contentType, 'boundary\s*=\s*(?:"([^"]+)"|([^;\s]+))', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if ($m.Success) {
        if ($m.Groups[1].Success) { $boundary = $m.Groups[1].Value } else { $boundary = $m.Groups[2].Value }
    }
    if ([string]::IsNullOrWhiteSpace($boundary) -or $boundary.Length -gt 200) {
        $result.error = "multipart boundary 无效"
        return $result
    }
    $declared = -1L
    try { $declared = [long]$Context.Request.ContentLength64 } catch { $declared = -1L }
    if ($declared -gt $MaxRequestBytes) {
        $result.error = "TOO_LARGE"
        return $result
    }
    $raw = Read-PortalRequestBody -Context $Context -MaxBytes $MaxRequestBytes -Config $Config
    if (-not $raw.ok) {
        if ($raw.busy) { $result.error = "BUSY" }
        elseif ($raw.timeout) { $result.error = "TIMEOUT" }
        elseif ($raw.tooLarge) { $result.error = "TOO_LARGE" }
        else { $result.error = "读取请求体失败" }
        return $result
    }
    if ($raw.length -le 0) {
        $result.error = "空请求体"
        return $result
    }
    $body = $raw.bytes
    $result.body = $body
    $delimiter = (Get-PortalUtf8NoBom).GetBytes("--" + $boundary)
    $headerSep = [byte[]]@(0x0D, 0x0A, 0x0D, 0x0A)
    # 先扫描全部「行首分隔符」位置：预算 = 前导 1 + 最多 40 个分片 + 终结 1
    $positions = New-Object System.Collections.ArrayList
    $scan = 0
    while ($positions.Count -le 42) {
        $idx = Find-PortalBytes -Haystack $body -Needle $delimiter -Start $scan
        if ($idx -lt 0) { break }
        $atLineStart = ($idx -eq 0) -or ($body[$idx - 1] -eq 0x0A)
        if ($atLineStart) { [void]$positions.Add($idx) }
        $scan = $idx + $delimiter.Length
    }
    if ($positions.Count -lt 2) {
        $result.error = "multipart 格式错误"
        return $result
    }
    if ($positions.Count -gt 41) {
        $result.error = "multipart 分片过多"
        return $result
    }
    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
    $partIndex = 0
    for ($k = 0; $k -lt $positions.Count - 1; $k++) {
        $segStart = [int]$positions[$k] + $delimiter.Length
        $segEnd = [int]$positions[$k + 1]
        if ($segStart + 1 -lt $body.Length -and $body[$segStart] -eq 0x2D -and $body[$segStart + 1] -eq 0x2D) { break }
        $partIndex++
        if ($partIndex -gt 40) {
            $result.error = "multipart 分片过多"
            return $result
        }
        if ($segStart + 1 -lt $body.Length -and $body[$segStart] -eq 0x0D -and $body[$segStart + 1] -eq 0x0A) { $segStart += 2 }
        $hdr = Find-PortalBytes -Haystack $body -Needle $headerSep -Start $segStart
        if ($hdr -lt 0 -or $hdr -ge $segEnd) { continue }
        $headerText = $latin1.GetString($body, $segStart, $hdr - $segStart)
        $contentStart = $hdr + 4
        $contentEnd = $segEnd
        if (($contentEnd - 2) -ge $contentStart -and $body[$contentEnd - 2] -eq 0x0D -and $body[$contentEnd - 1] -eq 0x0A) { $contentEnd -= 2 }
        $contentLen = $contentEnd - $contentStart
        if ($contentLen -lt 0) { $contentLen = 0 }
        $name = ""
        $fileName = ""
        foreach ($line in ($headerText -split "`r`n")) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $idx = $line.IndexOf(':')
            if ($idx -lt 0) { continue }
            $headerName = $line.Substring(0, $idx).Trim()
            $headerValue = $line.Substring($idx + 1).Trim()
            if ($headerName -ieq "Content-Disposition") {
                $nm = [regex]::Match($headerValue, 'name\s*=\s*"([^"]*)"', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
                if ($nm.Success) { $name = $nm.Groups[1].Value }
                $fm = [regex]::Match($headerValue, 'filename\s*=\s*"([^"]*)"', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
                if ($fm.Success) {
                    $rawName = $fm.Groups[1].Value
                    # 浏览器把 UTF-8 文件名按 UTF-8 字节写进 header，这里还原中文文件名。
                    $bytesOfName = $latin1.GetBytes($rawName)
                    $utf8Name = (Get-PortalUtf8NoBom).GetString($bytesOfName)
                    if (-not [string]::IsNullOrWhiteSpace($utf8Name)) { $fileName = $utf8Name } else { $fileName = $rawName }
                } else {
                    $fm2 = [regex]::Match($headerValue, "filename\*=UTF-8''([^;\s]+)", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
                    if ($fm2.Success) {
                        try { $fileName = [Uri]::UnescapeDataString($fm2.Groups[1].Value) } catch { }
                    }
                }
            }
        }
        if ($fileName -ne "") {
            if ($fileName.Length -gt 600) { $fileName = $fileName.Substring(0, 600) }
            if ([long]$contentLen -gt $MaxFileBytes) {
                $result.error = "FILE_TOO_LARGE"
                return $result
            }
            $files = @($result.files)
            $files += @{ name = $name; fileName = $fileName; offset = $contentStart; count = $contentLen; size = $contentLen }
            $result.files = $files
        } elseif ($name -ne "") {
            if ($contentLen -gt 65536) {
                $result.error = "字段过大: $name"
                return $result
            }
            $result.fields[$name] = (Get-PortalUtf8NoBom).GetString($body, $contentStart, $contentLen)
        }
    }
    if (($result.files.Count -eq 0) -and ($result.fields.Count -eq 0)) {
        $result.error = "multipart 格式错误"
        return $result
    }
    $result.ok = $true
    return $result
}

function Get-PortalStaticTarget {
    # 把 URL 相对路径安全映射到 stats-web 目录内文件；拒绝目录穿越与敏感文件。
    param([System.Collections.IDictionary]$Config, [string]$RelativePath)
    $webRoot = [System.IO.Path]::GetFullPath([string]$Config.webRoot).TrimEnd('\', '/')
    $rel = $RelativePath
    if ([string]::IsNullOrWhiteSpace($rel) -or $rel -eq "/") { $rel = "index.html" }
    $rel = $rel.TrimStart('/').TrimEnd('/')
    if ($rel -eq "") { $rel = "index.html" }
    if ($rel.Contains("`0")) { return @{ ok = $false; reason = "bad path" } }
    # R2-7：`/index.html/`、`/upload.html/` 这类「文件路径带尾斜杠」不应再返回 200
    # （同一内容多 URL = 缓存/指纹面）。除根路径外一律按不存在处理。
    if ($RelativePath.Length -gt 1 -and $RelativePath.EndsWith("/")) { return @{ ok = $false; reason = "not found" } }
    $segments = $rel.Split([char[]]@('/', '\'))
    if ($segments.Count -eq 0) { return @{ ok = $false; reason = "bad path" } }
    $first = $segments[0].ToLowerInvariant()
    if ($first -eq "server" -or $first -eq "web-data" -or $first -eq ".git" -or $first -eq "node_modules") {
        return @{ ok = $false; reason = "denied" }
    }
    foreach ($seg in $segments) {
        if ($seg -eq "" -or $seg -eq "." -or $seg -eq "..") { return @{ ok = $false; reason = "bad path" } }
        if ($seg.StartsWith(".")) { return @{ ok = $false; reason = "denied" } }
        # R1-E：`:` 是 NTFS 的 ADS（备用数据流）分隔符；且 `...%3a%3a%24DATA` 这类输入
        # 会让 GetFullPath 抛 ArgumentException（旧代码未捕获 → 500 + 堆栈）。
        if ($seg.Contains(":")) { return @{ ok = $false; reason = "bad path" } }
    }
    $leaf = $segments[$segments.Count - 1]
    $leafLower = $leaf.ToLowerInvariant()
    if ($leafLower -eq "web-config.json" -or $leafLower -eq "web-config.ps1" -or $leafLower -eq "start-web.ps1" -or $leafLower -eq "start-web.bat") {
        return @{ ok = $false; reason = "denied" }
    }
    $ext = Get-PortalFileExtension -FileName $leaf
    if (-not $script:PortalMimeTypes.ContainsKey($ext)) {
        return @{ ok = $false; reason = "type not allowed" }
    }
    $combined = Join-Path $webRoot ($rel.Replace('/', '\'))
    $full = ""
    try {
        # R1-E：非法路径（`::`、`$DATA`、超长路径等）会让 GetFullPath 抛异常。
        # 必须捕获并向调用方返回「不存在」，而不是 500 + 异常细节。
        $full = [System.IO.Path]::GetFullPath($combined)
    } catch {
        return @{ ok = $false; reason = "not found" }
    }
    try {
        if (-not (Test-PortalPathInside -ChildPath $full -ParentDir $webRoot)) {
            return @{ ok = $false; reason = "outside root" }
        }
    } catch {
        return @{ ok = $false; reason = "not found" }
    }
    # 数据目录与 server 目录：按「解析后的真实路径」拒绝，而不是只按目录名字符串。
    # dataDir 可配置（默认 web-data）；只比对名字时，部署者一旦改名（例如改成 data），
    # /data/submissions.json 就会被静态接口匿名读走。
    $sensitiveDirs = New-Object System.Collections.ArrayList
    try {
        $dataDirCfg = Get-PortalString $Config.dataDir "web-data"
        if (-not [string]::IsNullOrWhiteSpace($dataDirCfg)) {
            [void]$sensitiveDirs.Add([System.IO.Path]::GetFullPath((Join-Path $webRoot $dataDirCfg)))
        }
    } catch { }
    try { [void]$sensitiveDirs.Add([System.IO.Path]::GetFullPath((Join-Path $webRoot "server"))) } catch { }
    $fullNorm = ([string]$full).TrimEnd([char]92, [char]47)
    foreach ($dir in $sensitiveDirs) {
        $dirNorm = ([string]$dir).TrimEnd([char]92, [char]47)
        if ($dirNorm -eq "") { continue }
        if ($fullNorm.Equals($dirNorm, [System.StringComparison]::OrdinalIgnoreCase) -or
            $fullNorm.StartsWith($dirNorm + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
            return @{ ok = $false; reason = "denied" }
        }
    }
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        return @{ ok = $false; reason = "not found" }
    }
    return @{ ok = $true; fullPath = $full; contentType = (Get-PortalMimeType -FileName $full) }
}

function Send-PortalStaticFile {
    param($Context, [System.Collections.IDictionary]$Config, [string]$RelativePath, [switch]$HeadOnly)
    $target = Get-PortalStaticTarget -Config $Config -RelativePath $RelativePath
    if (-not $target.ok) {
        if ($target.reason -eq "not found" -or $target.reason -eq "type not allowed") {
            Send-PortalApiError -Context $Context -StatusCode 404 -Code "NOT_FOUND" -Message "页面不存在"
        } else {
            Send-PortalApiError -Context $Context -StatusCode 404 -Code "NOT_FOUND" -Message "页面不存在"
        }
        return
    }
    try {
        $bytes = [System.IO.File]::ReadAllBytes($target.fullPath)
    } catch {
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "READ_FAILED" -Message "读取静态文件失败"
        return
    }
    $headers = @{}
    $ext = Get-PortalFileExtension -FileName $target.fullPath
    if ($ext -eq ".html" -or $ext -eq ".htm") {
        $headers["Cache-Control"] = "no-cache"
    } else {
        $headers["Cache-Control"] = "public, max-age=300"
    }
    Send-PortalResponseBytes -Context $Context -StatusCode 200 -ContentType $target.contentType -Body $bytes -Headers $headers -HeadOnly:$HeadOnly
}
