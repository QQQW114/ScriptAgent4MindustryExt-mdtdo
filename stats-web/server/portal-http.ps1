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
    # 先看 Content-Length 快速拒绝超限请求，再按上限读取（避免把超大请求读进内存）。
    param($Context, [long]$MaxBytes)
    $request = $Context.Request
    $length = [long]$request.ContentLength64
    if ($length -gt $MaxBytes) {
        return @{ ok = $false; tooLarge = $true; bytes = $null; length = $length }
    }
    $ms = New-Object System.IO.MemoryStream
    try {
        if ($length -gt 0) {
            $buffer = New-Object byte[] 65536
            $remaining = $length
            while ($remaining -gt 0) {
                $want = [int][Math]::Min([long]$buffer.Length, $remaining)
                $read = $request.InputStream.Read($buffer, 0, $want)
                if ($read -le 0) { break }
                $ms.Write($buffer, 0, $read)
                $remaining -= $read
                if ($ms.Length -gt $MaxBytes) {
                    return @{ ok = $false; tooLarge = $true; bytes = $null; length = $ms.Length }
                }
            }
        }
        return @{ ok = $true; tooLarge = $false; bytes = $ms.ToArray(); length = $ms.Length }
    } finally {
        $ms.Dispose()
    }
}

function Read-PortalRequestJson {
    # 返回 @{ok;tooLarge;data}；data 为 PSCustomObject（空体/坏 JSON 时为 $null）。
    param($Context, [long]$MaxBytes = 1048576)
    $raw = Read-PortalRequestBody -Context $Context -MaxBytes $MaxBytes
    if (-not $raw.ok) { return @{ ok = $false; tooLarge = $true; data = $null } }
    if ($raw.length -le 0) { return @{ ok = $true; tooLarge = $false; data = $null } }
    $text = (Get-PortalUtf8NoBom).GetString($raw.bytes).Trim()
    if ($text -eq "") { return @{ ok = $true; tooLarge = $false; data = $null } }
    try {
        $data = ConvertFrom-Json -InputObject $text -ErrorAction Stop
        return @{ ok = $true; tooLarge = $false; data = $data }
    } catch {
        return @{ ok = $false; tooLarge = $false; data = $null; badJson = $true }
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
    param($Context, [System.Collections.IDictionary]$Config)
    $ip = ""
    try { $ip = $Context.Request.RemoteEndPoint.Address.ToString() } catch { }
    if ($Config -and (Get-PortalBool $Config.security.trustProxyHeaders $false)) {
        try {
            $xff = $Context.Request.Headers["X-Forwarded-For"]
            if (-not [string]::IsNullOrWhiteSpace($xff)) {
                $ip = ($xff -split ',')[0].Trim()
            }
        } catch { }
    }
    if ($ip.StartsWith("::ffff:")) { $ip = $ip.Substring(7) }
    if ($ip -eq "::1") { $ip = "127.0.0.1" }
    return $ip
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
    param([byte[]]$Bytes)
    if ($null -eq $Bytes -or $Bytes.Length -lt 4) { return $false }
    return ($Bytes[0] -eq 0x50 -and $Bytes[1] -eq 0x4B)
}

function Test-PortalJsonLikeMagic {
    param([byte[]]$Bytes)
    if ($null -eq $Bytes -or $Bytes.Length -lt 2) { return $false }
    $i = 0
    if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) { $i = 3 }
    while ($i -lt $Bytes.Length -and ($Bytes[$i] -eq 0x20 -or $Bytes[$i] -eq 0x09 -or $Bytes[$i] -eq 0x0A -or $Bytes[$i] -eq 0x0D)) { $i++ }
    if ($i -ge $Bytes.Length) { return $false }
    return ($Bytes[$i] -eq 0x7B -or $Bytes[$i] -eq 0x5B)   # '{' or '['
}

function Test-PortalMsavMagic {
    # Mindustry 存档 .msav 为 zlib(DeflaterOutputStream/InflaterInputStream)；
    # 也接受 gzip 头，兼容部分工具产出的地图文件。
    param([byte[]]$Bytes)
    if ($null -eq $Bytes -or $Bytes.Length -lt 8) { return $false }
    if ($Bytes[0] -eq 0x1F -and $Bytes[1] -eq 0x8B) { return $true }
    $cmf = [int]$Bytes[0]
    $flg = [int]$Bytes[1]
    if (($cmf -band 0x0F) -ne 8) { return $false }
    if ((($cmf * 256) + $flg) % 31 -ne 0) { return $false }
    return $true
}

function Get-PortalMimeType {
    param([string]$FileName)
    $ext = Get-PortalFileExtension -FileName $FileName
    if ($script:PortalMimeTypes.ContainsKey($ext)) { return $script:PortalMimeTypes[$ext] }
    return "application/octet-stream"
}

function Read-PortalMultipart {
    # 解析 multipart/form-data。文件内容按 ISO-8859-1 与字节 1:1 映射后切片，避免额外编码损耗。
    # 返回 @{ok;error;fields(hashtable name=>string);files(@(name,fileName,bytes,size))}
    param($Context, [long]$MaxRequestBytes, [long]$MaxFileBytes)
    $result = @{ ok = $false; error = ""; fields = @{}; files = @() }
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
    $raw = Read-PortalRequestBody -Context $Context -MaxBytes $MaxRequestBytes
    if (-not $raw.ok) {
        $result.error = if ($raw.tooLarge) { "TOO_LARGE" } else { "读取请求体失败" }
        return $result
    }
    if ($raw.length -le 0) {
        $result.error = "空请求体"
        return $result
    }
    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
    $bodyText = $latin1.GetString($raw.bytes)
    $delimiter = "--" + $boundary
    $parts = $bodyText.Split([string[]]@($delimiter), [System.StringSplitOptions]::None)
    $partCount = 0
    foreach ($part in $parts) {
        $partCount++
        if ($partCount -eq 1) { continue }          # 前导内容
        if ($part.StartsWith("--", [System.StringComparison]::Ordinal)) { break }  # 结束标记
        $p = $part
        if ($p.StartsWith("`r`n", [System.StringComparison]::Ordinal)) { $p = $p.Substring(2) }
        $headerEnd = $p.IndexOf("`r`n`r`n", [System.StringComparison]::Ordinal)
        if ($headerEnd -lt 0) { continue }
        $headerText = $p.Substring(0, $headerEnd)
        $contentText = $p.Substring($headerEnd + 4)
        if ($contentText.EndsWith("`r`n", [System.StringComparison]::Ordinal)) {
            $contentText = $contentText.Substring(0, $contentText.Length - 2)
        }
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
            $contentBytes = $latin1.GetBytes($contentText)
            if ($contentBytes.Length -gt $MaxFileBytes) {
                $result.error = "FILE_TOO_LARGE"
                return $result
            }
            $files = @($result.files)
            $files += @{ name = $name; fileName = $fileName; bytes = $contentBytes; size = $contentBytes.Length }
            $result.files = $files
        } elseif ($name -ne "") {
            $valueBytes = $latin1.GetBytes($contentText)
            if ($valueBytes.Length -gt 65536) {
                $result.error = "字段过大: $name"
                return $result
            }
            $result.fields[$name] = (Get-PortalUtf8NoBom).GetString($valueBytes)
        }
        if ($partCount -gt 40) {
            $result.error = "multipart 分片过多"
            return $result
        }
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
    $segments = $rel.Split([char[]]@('/', '\'))
    if ($segments.Count -eq 0) { return @{ ok = $false; reason = "bad path" } }
    $first = $segments[0].ToLowerInvariant()
    if ($first -eq "server" -or $first -eq "web-data" -or $first -eq ".git" -or $first -eq "node_modules") {
        return @{ ok = $false; reason = "denied" }
    }
    foreach ($seg in $segments) {
        if ($seg -eq "" -or $seg -eq "." -or $seg -eq "..") { return @{ ok = $false; reason = "bad path" } }
        if ($seg.StartsWith(".")) { return @{ ok = $false; reason = "denied" } }
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
    $full = [System.IO.Path]::GetFullPath($combined)
    if (-not (Test-PortalPathInside -ChildPath $full -ParentDir $webRoot)) {
        return @{ ok = $false; reason = "outside root" }
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
