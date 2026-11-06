# ============================================================================
# MDT DO 服务器门户 - API 路由处理
# 统一响应：成功 {ok:true,data:...}；失败 {ok:false,error:{code,message}}
# 兼容 Windows PowerShell 5.1 与 PowerShell 7+。
# ============================================================================

function Get-PortalProp {
    param($Object, [string]$Name, $Default = $null)
    if ($null -eq $Object) { return $Default }
    try {
        $prop = $Object.PSObject.Properties[$Name]
        if ($null -eq $prop) { return $Default }
        if ($null -eq $prop.Value) { return $Default }
        return $prop.Value
    } catch { return $Default }
}

function Send-PortalStoreResult {
    param($Context, $Result)
    if ($null -eq $Result) {
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "INTERNAL" -Message "内部错误：空结果"
        return
    }
    if ([bool]$Result.ok) {
        Send-PortalApiOk -Context $Context -Data $Result.data
        return
    }
    $code = Get-PortalString $Result.code "INTERNAL"
    $status = 500
    switch ($code) {
        "NOT_FOUND" { $status = 404 }
        "ALREADY_REVIEWED" { $status = 409 }
        "CONFLICT" { $status = 409 }
        "NAME_CONFLICT" { $status = 409 }
        "BAD_REQUEST" { $status = 400 }
        "PAYLOAD_MISSING" { $status = 410 }
        "FORBIDDEN" { $status = 403 }
        "RATE_LIMITED" { $status = 429 }
        default { $status = 500 }
    }
    Send-PortalApiError -Context $Context -StatusCode $status -Code $code -Message (Get-PortalString $Result.message "操作失败")
}

function Get-PortalAuthContext {
    # 校验 Bearer 会话；失败时已发送 401 响应并返回 $null。
    param($Context, [System.Collections.IDictionary]$Config)
    $token = Get-PortalBearerToken -Context $Context
    if ([string]::IsNullOrWhiteSpace($token)) {
        Send-PortalApiError -Context $Context -StatusCode 401 -Code "UNAUTHORIZED" -Message "需要管理员登录"
        return $null
    }
    $session = Get-PortalSession -Config $Config -Token $token
    if ($null -eq $session) {
        Send-PortalApiError -Context $Context -StatusCode 401 -Code "SESSION_EXPIRED" -Message "登录已失效，请重新登录"
        return $null
    }
    return @{ token = $token; session = $session; username = (Get-PortalString $session.username "admin") }
}

# ---------------------------------------------------------------------------
# GET /api/status
# ---------------------------------------------------------------------------

function Handle-PortalApiStatus {
    param($Context, [System.Collections.IDictionary]$Config)
    $available = $false
    $stats = $null
    $statsPath = Get-PortalString $Config.statsJsonPath ""
    if ($statsPath -ne "" -and (Test-Path -LiteralPath $statsPath)) {
        try {
            $text = Read-PortalTextFile -Path $statsPath
            if (-not [string]::IsNullOrWhiteSpace($text)) {
                $stats = ConvertFrom-Json -InputObject $text -ErrorAction Stop
                if ($null -ne $stats) { $available = $true }
            }
        } catch {
            Write-PortalLog -Config $Config -Level "WARN" -Message "读取 server-status.json 失败：$($_.Exception.Message)"
        }
    }
    $serverInfo = $null
    if ($available) { $serverInfo = Get-PortalProp $stats "serverInfo" $null }
    $onlineByRank = Get-PortalProp $serverInfo "onlineByRank" $null
    # R1-D：带宽的「网卡名 + 30 点序列」属于主机指纹（interface=WLAN 能推断机器形态、
    # 序列能看出活跃时段），未认证只返回聚合值；完整快照只在认证分支返回。
    $netAuthed = $false
    try {
        $netTok = Get-PortalBearerToken -Context $Context
        if (-not [string]::IsNullOrWhiteSpace($netTok)) {
            if ($null -ne (Get-PortalSession -Config $Config -Token $netTok)) { $netAuthed = $true }
        }
    } catch { $netAuthed = $false }
    $netSnapshot = $null
    try { $netSnapshot = Get-PortalBandwidthSnapshot -Config $Config -IncludeSeries $true } catch {
        $netSnapshot = [ordered]@{ available = $false; avgUpBps = $null; windowMinutes = 0; sampledAt = $null; series = @(); error = $_.Exception.Message }
    }
    if (-not $netAuthed -and $null -ne $netSnapshot) {
        $publicNet = [ordered]@{}
        foreach ($k in @("available", "unit", "avgUpBps", "avgUpBytesPerSec", "windowMinutes", "sampledAt", "sampledAtText", "error")) {
            $v = $null
            try { $v = $netSnapshot.$k } catch { $v = $null }
            if ($null -ne $v) { $publicNet[$k] = $v }
        }
        $netSnapshot = $publicNet
    }
    $data = [ordered]@{
        available     = $available
        ok            = $true
        updatedAt     = Get-PortalProp $stats "updatedAt" $null
        enabled       = Get-PortalProp $stats "enabled" $null
        online        = Get-PortalProp $serverInfo "onlinePlayers" $null
        maxPlayers    = $null
        map           = Get-PortalProp $serverInfo "map" $null
        mode          = Get-PortalProp $serverInfo "mode" $null
        wave          = Get-PortalProp $serverInfo "wave" $null
        tps           = Get-PortalProp $serverInfo "tps" $null
        uptimeSeconds = Get-PortalProp $serverInfo "uptimeSeconds" $null
        onlineByRank  = $onlineByRank
        stats         = $stats
        serverTime    = Get-PortalNowText
        net           = $netSnapshot
    }
    Send-PortalApiOk -Context $Context -Data $data
}

# ---------------------------------------------------------------------------
# GET /api/events
# ---------------------------------------------------------------------------

function Get-PortalLiveEvents {
    # 读取 live-events.jsonl；坏行/半行/文件缺失全部容错。
    param([System.Collections.IDictionary]$Config, [long]$AfterTs, [int]$Limit)
    $result = @{ events = @(); lastTs = $AfterTs; available = $false; total = 0; mainFileAvailable = $false; historyAvailable = $false; hint = $null }
    $path = Get-PortalString $Config.eventsJsonlPath ""
    if ($path -eq "" -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
        # 主事件文件可能因轮转瞬间暂缺（rename→新建之间）或尚无新事件：
        # 给出中性提示，让前端知道历史文件仍可查询，而不是误判"事件流未启用"。
        try {
            $histFiles = Get-PortalEventHistoryFiles -Config $Config
            $result.historyAvailable = [bool]$histFiles.available
            if ($result.historyAvailable) {
                $result.hint = "当前事件文件因轮转暂缺（暂无新事件）；历史消息仍可通过 /api/events/history 查询"
            }
        } catch { }
        return $result
    }
    $result.mainFileAvailable = $true
    $result.historyAvailable = $true
    $text = $null
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        try { $text = Read-PortalTextFile -Path $path; break } catch { Start-Sleep -Milliseconds 40 }
    }
    if ($null -eq $text) { return $result }
    $result.available = $true
    $events = New-Object System.Collections.ArrayList
    foreach ($line in ($text -split "`n")) {
        $t = $line.Trim()
        if ($t -eq "") { continue }
        try { $obj = ConvertFrom-Json -InputObject $t -ErrorAction Stop } catch { continue }
        if ($null -eq $obj) { continue }
        $tsValue = Get-PortalProp $obj "ts" $null
        if ($null -eq $tsValue) { continue }
        $ts = 0L
        if (-not [long]::TryParse([string]$tsValue, [ref]$ts)) { continue }
        if ($ts -le 0) { continue }
        $eventType = Get-PortalString (Get-PortalProp $obj "type" "system") "system"
        $eventText = Get-PortalString (Get-PortalProp $obj "text" "") ""
        $player = Get-PortalString (Get-PortalProp $obj "player" "") ""
        if (Test-PortalPublicEventHidden -Type $eventType -Text $eventText -Object $obj) { continue }
        $item = [ordered]@{
            ts    = $ts
            type  = $eventType
            text  = $eventText
            player = $player
        }
        [void]$events.Add($item)
    }
    $result.total = $events.Count
    if ($events.Count -eq 0) { return $result }
    # 文件是 append-only 的，但为防止外部写入乱序，这里按 ts 升序稳定排序。
    $sorted = New-Object System.Collections.ArrayList
    foreach ($e in ($events | Sort-Object -Property @{ Expression = { [long]$_.ts } })) { [void]$sorted.Add($e) }
    $selected = New-Object System.Collections.ArrayList
    if ($AfterTs -gt 0) {
        foreach ($e in $sorted) {
            if ([long]$e.ts -gt $AfterTs) { [void]$selected.Add($e) }
        }
        if ($selected.Count -gt $Limit) {
            # 截断时把与第 limit 条同 ts 的事件一起返回，避免前端游标跳过同毫秒事件。
            $cutTs = [long]$selected[$Limit - 1].ts
            $trimmed = New-Object System.Collections.ArrayList
            foreach ($e in $selected) {
                if ($trimmed.Count -ge $Limit) {
                    if ([long]$e.ts -ne $cutTs) { break }
                }
                [void]$trimmed.Add($e)
            }
            $selected = $trimmed
        }
        if ($selected.Count -eq 0) { $result.lastTs = $AfterTs } else { $result.lastTs = [long]$selected[$selected.Count - 1].ts }
    } else {
        $start = 0
        if ($sorted.Count -gt $Limit) { $start = $sorted.Count - $Limit }
        for ($i = $start; $i -lt $sorted.Count; $i++) { [void]$selected.Add($sorted[$i]) }
        if ($selected.Count -eq 0) { $result.lastTs = 0 } else { $result.lastTs = [long]$selected[$selected.Count - 1].ts }
    }
    $result.events = @($selected)
    return $result
}

function Handle-PortalApiEvents {
    param($Context, [System.Collections.IDictionary]$Config)
    $afterTs = Get-PortalQueryLong -Context $Context -Name "afterTs" -Default 0 -Min 0 -Max ([long]::MaxValue)
    $limit = Get-PortalQueryInt -Context $Context -Name "limit" -Default 100 -Min 1 -Max 300
    $data = Get-PortalLiveEvents -Config $Config -AfterTs ([long]$afterTs) -Limit $limit
    Send-PortalApiOk -Context $Context -Data $data
}

# ---------------------------------------------------------------------------
# GET /api/events/history  （跨当前 + 旋转历史文件的倒序分页）
# ---------------------------------------------------------------------------

function Send-PortalApiErrorRetry {
    # 429 等需要 Retry-After 头的错误响应。
    param($Context, [int]$StatusCode, [string]$Code, [string]$Message, [int]$RetryAfterSeconds = 0, [System.Collections.IDictionary]$Extra = $null)
    $errorBody = [ordered]@{ code = $Code; message = $Message }
    if ($Extra) { foreach ($k in @($Extra.Keys)) { $errorBody[[string]$k] = $Extra[$k] } }
    $body = [ordered]@{ ok = $false; error = $errorBody }
    $json = ConvertTo-Json -InputObject $body -Depth 8 -Compress
    $bytes = (Get-PortalUtf8NoBom).GetBytes($json)
    $headers = @{ "Cache-Control" = "no-store" }
    if ($RetryAfterSeconds -gt 0) { $headers["Retry-After"] = [string]$RetryAfterSeconds }
    Send-PortalResponseBytes -Context $Context -StatusCode $StatusCode -ContentType "application/json; charset=utf-8" -Body $bytes -Headers $headers
}

function Handle-PortalApiEventsHistory {
    param($Context, [System.Collections.IDictionary]$Config, [string]$ClientIp)
    $rate = Test-PortalHistoryAllowed -Config $Config -ClientIp $ClientIp
    if (-not $rate.allowed) {
        Send-PortalApiErrorRetry -Context $Context -StatusCode 429 -Code "RATE_LIMITED" `
            -Message ("历史事件查询过于频繁（{0}/{1} 次/分钟），请稍后再试" -f $rate.used, $rate.limit) `
            -RetryAfterSeconds $rate.retryAfterSeconds
        return
    }
    $fromTs = Get-PortalQueryLong -Context $Context -Name "fromTs" -Default 0 -Min 0 -Max ([long]::MaxValue)
    $toTs = Get-PortalQueryLong -Context $Context -Name "toTs" -Default 0 -Min 0 -Max ([long]::MaxValue)
    $cursor = Get-PortalQueryLong -Context $Context -Name "cursor" -Default 0 -Min 0 -Max ([long]::MaxValue)
    $limit = Get-PortalQueryInt -Context $Context -Name "limit" -Default 100 -Min 1 -Max 200
    $type = (Get-PortalString (Get-PortalQueryValue -Context $Context -Name "type" -Default "") "").Trim().ToLowerInvariant()
    $keyword = (Get-PortalString (Get-PortalQueryValue -Context $Context -Name "keyword" -Default "") "").Trim()
    if ($type -ne "" -and ($script:PortalEventAllowedTypes -notcontains $type)) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message ("type 非法，允许：" + ($script:PortalEventAllowedTypes -join " / "))
        return
    }
    $maxKeyword = Get-PortalInt $Config.events.historyMaxKeywordChars 100 1 500
    if ($keyword.Length -gt $maxKeyword) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message ("keyword 过长（最多 {0} 字符）" -f $maxKeyword)
        return
    }
    if ($fromTs -gt 0 -and $toTs -gt 0 -and $fromTs -gt $toTs) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "fromTs 不能大于 toTs"
        return
    }
    $result = Get-PortalEventHistory -Config $Config -FromTs ([long]$fromTs) -ToTs ([long]$toTs) -Type $type `
        -Keyword $keyword -Limit $limit -Cursor ([long]$cursor)
    # R2-6：走到这里说明请求真的进入了业务（参数校验已过），才消耗每 IP 配额。
    Add-PortalHistoryHit -Config $Config -ClientIp $ClientIp
    Send-PortalStoreResult -Context $Context -Result $result
}

# ---------------------------------------------------------------------------
# 其它只读接口
# ---------------------------------------------------------------------------

function Handle-PortalApiCatalog {
    param($Context, [System.Collections.IDictionary]$Config)
    $catalog = Get-PortalCatalog -Config $Config
    Send-PortalApiOk -Context $Context -Data $catalog
}

function Handle-PortalApiHealth {
    # M-1：未认证只返回最小信息（ok / serverTime / version）；
    # 绝对路径、管理员用户名、控制台 host:port、网卡名、会话数/队列数等
    # 一律只在**已认证**分支返回，避免给未授权访问者做资产测绘。
    param($Context, [System.Collections.IDictionary]$Config)
    $authed = $false
    try {
        $tok = Get-PortalBearerToken -Context $Context
        if (-not [string]::IsNullOrWhiteSpace($tok)) {
            if ($null -ne (Get-PortalSession -Config $Config -Token $tok)) { $authed = $true }
        }
    } catch { $authed = $false }

    if (-not $authed) {
        Send-PortalApiOk -Context $Context -Data ([ordered]@{
            ok         = $true
            serverTime = Get-PortalNowText
            version    = "portal-1.1.0"
        })
        return
    }

    $statsAvailable = $false
    if ($Config.statsJsonPath -and (Test-Path -LiteralPath $Config.statsJsonPath)) { $statsAvailable = $true }
    $eventsAvailable = $false
    if ($Config.eventsJsonlPath -and (Test-Path -LiteralPath $Config.eventsJsonlPath)) { $eventsAvailable = $true }
    # 注意：Get-PortalDirectoryList 返回 ArrayList 本身（函数内用 ,$out 阻止展开），
    # 直接取 .Count；不要再套 @(...)（那会把 ArrayList 包成单元素数组）。
    $dpCount = (Get-PortalDirectoryList -Config $Config -Kind "dp").Count
    $mapCount = (Get-PortalDirectoryList -Config $Config -Kind "map").Count
    $store = Read-PortalStore -Config $Config
    $logsAvailable = $false
    if ($Config.logsDirPath -and (Test-Path -LiteralPath $Config.logsDirPath -PathType Container)) { $logsAvailable = $true }
    $historyFiles = Get-PortalEventHistoryFiles -Config $Config
    $data = [ordered]@{
        serverTime       = Get-PortalNowText
        # R2-4：认证后也不再回绝对路径（盘符/部署结构），只给目录名，便于区分环境。
        webRoot          = [System.IO.Path]::GetFileName(([string]$Config.webRoot).TrimEnd('\', '/'))
        dataDir          = [System.IO.Path]::GetFileName(([string]$Config.dataDirPath).TrimEnd('\', '/'))
        pathsRedacted    = $true
        statsAvailable   = $statsAvailable
        eventsAvailable  = $eventsAvailable
        eventHistoryFiles = $historyFiles.files.Count
        dpDir            = [System.IO.Path]::GetFileName(([string]$Config.dpDirPath).TrimEnd('\', '/'))
        mapsDir          = [System.IO.Path]::GetFileName(([string]$Config.mapsDirPath).TrimEnd('\', '/'))
        dpCount          = $dpCount
        mapCount         = $mapCount
        pendingDp        = (Get-PortalPendingCount -Store $store -Kind "dp")
        pendingMap       = (Get-PortalPendingCount -Store $store -Kind "map")
        queueLimit       = (Get-PortalInt $Config.upload.queueLimitPerKind 10 1 100)
        sessions         = $script:PortalSessions.Count
        pendingConsole   = $script:PortalConsolePending.Count
        consoleEnabled   = (Get-PortalBool $Config.console.enabled $true)
        logsEnabled      = (Get-PortalBool $Config.logs.enabled $true)
        logsAvailable    = $logsAvailable
        logsRequireLogin = (Get-PortalBool $Config.logs.requireLogin $true)
        logsDir          = [System.IO.Path]::GetFileName(([string]$Config.logsDirPath).TrimEnd('\', '/'))
        activeDownloads  = $script:PortalDownloadActive.Count
        maxConcurrentDownloads = (Get-PortalInt $Config.logs.maxConcurrentDownloads 2 1 16)
        bandwidthAvailable = [bool]$script:PortalBandwidth.available
        bandwidthInterface = (Get-PortalString $script:PortalBandwidth.ifaceName "")
        clientDisconnects = [long]$script:PortalClientDisconnectCount
        console          = (Get-PortalConsoleHealth -Config $Config)
        adminUser        = Get-PortalString $Config.admin.username "admin"
        version          = "portal-1.1.0"
    }
    Send-PortalApiOk -Context $Context -Data $data
}

# ---------------------------------------------------------------------------
# 服务器日志：列表 / 下载 / 尾部预览（第二期）
# ---------------------------------------------------------------------------

function Get-PortalLogsAccessContext {
    # 统一鉴权：logs.requireLogin=true 时必须是管理员会话；false 时公开（actor=anonymous）。
    # 通过时返回 @{ actor = 用户名或 anonymous; via = header|query }；失败返回 $null（已发送 401）。
    # 支持两种带 token 方式（浏览器直接导航下载无法带 Authorization 头）：
    #   1) Authorization: Bearer <sessionToken>（API/fetch）
    #   2) ?token=<sessionToken> 查询参数（<a download> 直接下载；同源、Referrer-Policy: no-referrer）
    # 注意：查询参数里的 token 会出现在浏览器历史/用户可见 URL 中，建议仅在下载链接使用、勿粘贴分享。
    param($Context, [System.Collections.IDictionary]$Config)
    if (-not (Get-PortalBool $Config.logs.requireLogin $true)) {
        return @{ actor = "anonymous"; username = ""; via = "public" }
    }
    $token = Get-PortalBearerToken -Context $Context
    $via = "header"
    if ([string]::IsNullOrWhiteSpace($token)) {
        $token = (Get-PortalString (Get-PortalQueryValue -Context $Context -Name "token" -Default "") "").Trim()
        $via = "query"
    }
    if ([string]::IsNullOrWhiteSpace($token)) {
        Send-PortalApiError -Context $Context -StatusCode 401 -Code "UNAUTHORIZED" -Message "需要管理员登录（Authorization Bearer 或 ?token=）"
        return $null
    }
    $session = Get-PortalSession -Config $Config -Token $token
    if ($null -eq $session) {
        Send-PortalApiError -Context $Context -StatusCode 401 -Code "SESSION_EXPIRED" -Message "登录已失效，请重新登录"
        return $null
    }
    $username = Get-PortalString $session.username "admin"
    return @{ actor = $username; username = $username; via = $via }
}

function Handle-PortalApiLogs {
    param($Context, [System.Collections.IDictionary]$Config)
    if (-not (Get-PortalBool $Config.logs.enabled $true)) {
        Send-PortalApiError -Context $Context -StatusCode 404 -Code "NOT_FOUND" -Message "日志下载功能未启用"
        return
    }
    $access = Get-PortalLogsAccessContext -Context $Context -Config $Config
    if ($null -eq $access) { return }
    $limit = Get-PortalQueryInt -Context $Context -Name "limit" -Default 100 -Min 1 -Max 500
    $list = Get-PortalLogList -Config $Config -Limit $limit
    $data = [ordered]@{
        available   = [bool]$list.available
        dir         = [string]$list.dir
        requireLogin = (Get-PortalBool $Config.logs.requireLogin $true)
        maxFileBytes = (Get-PortalLong $Config.logs.maxFileBytes 52428800 1024 1073741824)
        items       = @($list.items)
        count       = [int]$list.count
        serverTime  = Get-PortalNowText
    }
    Send-PortalApiOk -Context $Context -Data $data
}

function Handle-PortalApiLogsDownload {
    param($Context, [System.Collections.IDictionary]$Config, [string]$ClientIp)
    if (-not (Get-PortalBool $Config.logs.enabled $true)) {
        Send-PortalApiError -Context $Context -StatusCode 404 -Code "NOT_FOUND" -Message "日志下载功能未启用"
        return
    }
    $access = Get-PortalLogsAccessContext -Context $Context -Config $Config
    if ($null -eq $access) { return }
    $name = (Get-PortalString (Get-PortalQueryValue -Context $Context -Name "name" -Default "") "").Trim()
    if ($name -eq "") {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "缺少 name 参数"
        return
    }
    $rate = Test-PortalLogAccessAllowed -Config $Config -ClientIp $ClientIp
    if (-not $rate.allowed) {
        Write-PortalAudit -Config $Config -Action "download_rate_limited" -Actor $access.actor -ClientIp $ClientIp -Detail ([ordered]@{ name = $name; used = $rate.used; limit = $rate.limit })
        Send-PortalApiErrorRetry -Context $Context -StatusCode 429 -Code "RATE_LIMITED" `
            -Message ("日志下载过于频繁（{0}/{1} 次/{2} 分钟），请稍后再试" -f $rate.used, $rate.limit, (Get-PortalInt $Config.logs.rateWindowMinutes 5 1 1440)) `
            -RetryAfterSeconds $rate.retryAfterSeconds
        return
    }
    $started = [bool](Start-PortalLogDownload -Context $Context -Config $Config -Name $name -ClientIp $ClientIp -Actor $access.actor)
    # R2-6：只有真正开始下发才消耗配额；404/413 之类的失败不计数。
    if ($started) { Add-PortalLogHit -Config $Config -ClientIp $ClientIp }
}

function Handle-PortalApiLogsTail {
    param($Context, [System.Collections.IDictionary]$Config, [string]$ClientIp)
    if (-not (Get-PortalBool $Config.logs.enabled $true)) {
        Send-PortalApiError -Context $Context -StatusCode 404 -Code "NOT_FOUND" -Message "日志下载功能未启用"
        return
    }
    $access = Get-PortalLogsAccessContext -Context $Context -Config $Config
    if ($null -eq $access) { return }
    $name = (Get-PortalString (Get-PortalQueryValue -Context $Context -Name "name" -Default "") "").Trim()
    if ($name -eq "") {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "缺少 name 参数"
        return
    }
    $rate = Test-PortalLogAccessAllowed -Config $Config -ClientIp $ClientIp
    if (-not $rate.allowed) {
        Send-PortalApiErrorRetry -Context $Context -StatusCode 429 -Code "RATE_LIMITED" `
            -Message ("日志访问过于频繁（{0}/{1} 次/{2} 分钟），请稍后再试" -f $rate.used, $rate.limit, (Get-PortalInt $Config.logs.rateWindowMinutes 5 1 1440)) `
            -RetryAfterSeconds $rate.retryAfterSeconds
        return
    }
    $file = Resolve-PortalLogFile -Config $Config -Name $name
    if (-not $file.ok) {
        Send-PortalApiError -Context $Context -StatusCode 404 -Code "NOT_FOUND" -Message $file.message
        return
    }
    $maxFileBytes = Get-PortalLong $Config.logs.maxFileBytes 52428800 1024 1073741824
    if ([long]$file.size -gt $maxFileBytes) {
        Send-PortalApiError -Context $Context -StatusCode 413 -Code "FILE_TOO_LARGE" -Message ("日志文件超过大小上限（{0} 字节）" -f $maxFileBytes)
        return
    }
    $lines = Get-PortalQueryInt -Context $Context -Name "lines" -Default 200 -Min 1 -Max (Get-PortalInt $Config.logs.tailMaxLines 2000 10 5000)
    $tail = Get-PortalLogTail -Config $Config -Path $file.path -Lines $lines -MaxBytes ([long]$Config.logs.tailMaxBytes)
    if (-not $tail.ok) {
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "READ_FAILED" -Message ("读取日志尾部失败：" + $tail.message)
        return
    }
    # R2-6：成功读到日志内容才计数。
    Add-PortalLogHit -Config $Config -ClientIp $ClientIp
    $data = [ordered]@{
        name      = [string]$file.name
        kind      = [string]$file.kind
        size      = [long]$file.size
        lines     = @($tail.lines)
        truncated = [bool]$tail.truncated
        serverTime = Get-PortalNowText
    }
    Send-PortalApiOk -Context $Context -Data $data
}

# ---------------------------------------------------------------------------
# 认证
# ---------------------------------------------------------------------------

function Handle-PortalApiLogin {
    param($Context, [System.Collections.IDictionary]$Config, [string]$ClientIp)
    $now = Get-PortalNowMillis
    # R2-3：429 一律用同一句文案，不再通过 message/reason 暴露内部状态
    # （是全局慢速、账号退避还是 IP 锁定，攻击者都不该知道）。
    # 时间信息只放在 Retry-After 头 + body 的 retryAfter 字段里（前端需要它做倒计时）。
    $throttleMessage = "登录尝试过于频繁，请稍后重试"
    # 1) 全局节奏闸门（常态 120ms/次、慢速 5s/次；无阻塞，只比较时间戳）。
    $gate = Get-PortalGlobalLoginGate -Config $Config -Now $now
    if ([int]$gate.retryAfter -gt 0) {
        Send-PortalApiErrorRetry -Context $Context -StatusCode 429 -Code "RATE_LIMITED" `
            -Message $throttleMessage -RetryAfterSeconds ([int]$gate.retryAfter) `
            -Extra ([ordered]@{ retryAfter = [int]$gate.retryAfter })
        return
    }
    # 2) per-IP 硬锁（默认关闭：rateLimit.ipHardLockFailures=0，避免同 NAT 下攻击者锁死管理员）。
    if (Test-PortalLoginBlocked -Config $Config -ClientIp $ClientIp) {
        $ra = [Math]::Max(1, (Get-PortalInt $Config.rateLimit.loginWindowMinutes 5 1 1440) * 60)
        Send-PortalApiErrorRetry -Context $Context -StatusCode 429 -Code "RATE_LIMITED" `
            -Message $throttleMessage -RetryAfterSeconds $ra `
            -Extra ([ordered]@{ retryAfter = $ra })
        return
    }
    $body = Read-PortalRequestJson -Context $Context -MaxBytes 65536 -Config $Config
    if (-not $body.ok) {
        if ([bool]$body.timeout) {
            Send-PortalApiError -Context $Context -StatusCode 408 -Code "REQUEST_TIMEOUT" -Message "请求读取超时"
            return
        }
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "请求体格式错误"
        return
    }
    $username = Get-PortalString (Get-PortalProp $body.data "username" "") ""
    $password = Get-PortalString (Get-PortalProp $body.data "password" "") ""
    if ($username.Length -gt 128) { $username = $username.Substring(0, 128) }
    # 缺字段的 400 不计入任何失败计数（否则 `{}` 空请求可零成本打满全局预算）。
    if ($username -eq "" -or $password -eq "") {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "请输入账号与密码"
        return
    }
    $adminUser = Get-PortalString $Config.admin.username "admin"
    $userOk = Test-PortalStringEquals -A $adminUser -B $username
    $passOk = Test-PortalAdminPassword -Config $Config -Password $password
    $valid = ($userOk -and $passOk)
    # R2-2：**先验密码，再决定是否退避** —— 正确密码永远放行，退避只作用于"错密码"这条路径。
    # 否则攻击者只要持续错登，就能把 nextAllowedAt 永久推到未来，管理员自己也进不来。
    # PBKDF2 的 CPU 成本由上面的全局节奏闸门（120ms/次）兜底，而不是靠牺牲可登录性。
    if (-not $valid) {
        $backoff = Test-PortalLoginBackoff -Config $Config -UserName $username -Now $now
        if ([bool]$backoff.blocked) {
            # 退避中：不累加失败次数（否则延迟会被无限维持），只告诉客户端还要等多久。
            Send-PortalApiErrorRetry -Context $Context -StatusCode 429 -Code "RATE_LIMITED" `
                -Message $throttleMessage -RetryAfterSeconds ([int]$backoff.retryAfter) `
                -Extra ([ordered]@{ retryAfter = [int]$backoff.retryAfter })
            return
        }
        Add-PortalLoginBackoff -Config $Config -UserName $username -Now $now
        Add-PortalGlobalLoginFailure -Config $Config -Now $now
        Add-PortalLoginFailure -Config $Config -ClientIp $ClientIp   # per-IP 仅计数（默认不拦截）
        # 日志注入防护：去掉控制字符并截断，避免伪造日志行。
        $safeUser = ($username -replace '[\r\n\t]', ' ')
        if ($safeUser.Length -gt 64) { $safeUser = $safeUser.Substring(0, 64) }
        Write-PortalLog -Config $Config -Level "WARN" -Message "管理员登录失败：user=$safeUser ip=$ClientIp fails=$([int]$backoff.fails + 1)"
        Send-PortalApiError -Context $Context -StatusCode 401 -Code "BAD_CREDENTIALS" -Message "账号或密码错误"
        return
    }
    Reset-PortalLoginFailures -ClientIp $ClientIp
    Reset-PortalLoginBackoff -UserName $adminUser
    $session = New-PortalSession -Config $Config -Username $adminUser -ClientIp $ClientIp
    Write-PortalAudit -Config $Config -Action "login" -Actor $adminUser -ClientIp $ClientIp -Detail ([ordered]@{ tokenTail = ([string]$session.token).Substring([Math]::Max(0, ([string]$session.token).Length - 8)) })
    $data = [ordered]@{
        token     = $session.token
        expiresAt = $session.expiresAt
        username  = $adminUser
    }
    Send-PortalApiOk -Context $Context -Data $data
}

function Handle-PortalApiLogout {
    param($Context, [System.Collections.IDictionary]$Config, [string]$ClientIp)
    $auth = Get-PortalAuthContext -Context $Context -Config $Config
    if ($null -eq $auth) { return }
    [void](Remove-PortalSession -Token $auth.token)
    Write-PortalAudit -Config $Config -Action "logout" -Actor $auth.username -ClientIp $ClientIp -Detail $null
    Send-PortalApiOk -Context $Context -Data ([ordered]@{ loggedOut = $true })
}

# ---------------------------------------------------------------------------
# 审核
# ---------------------------------------------------------------------------

function Handle-PortalApiReviewList {
    param($Context, [System.Collections.IDictionary]$Config)
    $auth = Get-PortalAuthContext -Context $Context -Config $Config
    if ($null -eq $auth) { return }
    $kind = Get-PortalString (Get-PortalQueryValue -Context $Context -Name "kind" -Default "dp") "dp"
    $result = Get-PortalReviewList -Config $Config -Kind $kind
    Send-PortalStoreResult -Context $Context -Result $result
}

function Handle-PortalApiApprove {
    param($Context, [System.Collections.IDictionary]$Config, [string]$ClientIp)
    $auth = Get-PortalAuthContext -Context $Context -Config $Config
    if ($null -eq $auth) { return }
    $body = Read-PortalRequestJson -Context $Context -MaxBytes 65536
    if (-not $body.ok) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "请求体格式错误"
        return
    }
    $id = Get-PortalString (Get-PortalProp $body.data "id" "") ""
    if ($id -eq "") {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "缺少提交 id"
        return
    }
    $reloadRaw = Get-PortalProp $body.data "reload" $null
    $reloadMode = "default"
    if ($null -ne $reloadRaw) {
        if ($reloadRaw -is [bool]) { $reloadMode = if ($reloadRaw) { "true" } else { "false" } }
        else { $reloadMode = ([string]$reloadRaw).Trim().ToLowerInvariant() }
    }
    $result = Approve-PortalSubmission -Config $Config -Id $id -ReloadMode $reloadMode -Actor $auth.username -ClientIp $ClientIp
    Send-PortalStoreResult -Context $Context -Result $result
}

function Handle-PortalApiReject {
    param($Context, [System.Collections.IDictionary]$Config, [string]$ClientIp)
    $auth = Get-PortalAuthContext -Context $Context -Config $Config
    if ($null -eq $auth) { return }
    $body = Read-PortalRequestJson -Context $Context -MaxBytes 65536
    if (-not $body.ok) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "请求体格式错误"
        return
    }
    $id = Get-PortalString (Get-PortalProp $body.data "id" "") ""
    $reason = Get-PortalString (Get-PortalProp $body.data "reason" "") ""
    if ($id -eq "") {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "缺少提交 id"
        return
    }
    $result = Reject-PortalSubmission -Config $Config -Id $id -Reason $reason -Actor $auth.username -ClientIp $ClientIp
    Send-PortalStoreResult -Context $Context -Result $result
}

function Handle-PortalApiDeleteItem {
    param($Context, [System.Collections.IDictionary]$Config, [string]$ClientIp)
    $auth = Get-PortalAuthContext -Context $Context -Config $Config
    if ($null -eq $auth) { return }
    $body = Read-PortalRequestJson -Context $Context -MaxBytes 65536
    if (-not $body.ok) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "请求体格式错误"
        return
    }
    $kind = Get-PortalString (Get-PortalProp $body.data "kind" "") ""
    $name = Get-PortalString (Get-PortalProp $body.data "name" "") ""
    $unload = Get-PortalBool (Get-PortalProp $body.data "unload" $false) $false
    if ($kind -eq "" -or $name -eq "") {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "缺少 kind 或 name"
        return
    }
    $result = Remove-PortalItem -Config $Config -Kind $kind -Name $name -Unload $unload -Actor $auth.username -ClientIp $ClientIp
    Send-PortalStoreResult -Context $Context -Result $result
}

# ---------------------------------------------------------------------------
# 上传（公开，无需登录）
# ---------------------------------------------------------------------------

function Handle-PortalApiUpload {
    param($Context, [System.Collections.IDictionary]$Config, [string]$ClientIp)
    if (-not (Test-PortalUploadAllowed -Config $Config -ClientIp $ClientIp)) {
        Send-PortalApiError -Context $Context -StatusCode 429 -Code "RATE_LIMITED" -Message "上传过于频繁，请稍后再试"
        return
    }
    $multipart = Read-PortalMultipart -Context $Context -MaxRequestBytes ([long]$Config.upload.maxRequestBytes) -MaxFileBytes ([long]$Config.upload.maxFileBytes) -Config $Config
    if (-not $multipart.ok) {
        if ($multipart.error -eq "BUSY") {
            # R2-1：大 body 的停滞预算已被其它慢连接耗尽 —— 回 503 而不是 408，
            # 让客户端按 Retry-After 退让，而不是立刻重发撞同一堵墙。
            Send-PortalApiErrorRetry -Context $Context -StatusCode 503 -Code "SERVER_BUSY" `
                -Message "服务端暂时繁忙（正在处理其它上传），请稍后重试" -RetryAfterSeconds 1
            return
        }
        if ($multipart.error -eq "TIMEOUT") {
            # H-1：读超时（慢速/停滞连接）→ 408 并放弃该连接，不拖住后续请求。
            Send-PortalApiError -Context $Context -StatusCode 408 -Code "REQUEST_TIMEOUT" -Message "上传请求读取超时（连接过慢或已停滞）"
            try { $Context.Response.Abort() } catch { }
            return
        }
        if ($multipart.error -eq "FILE_TOO_LARGE" -or $multipart.error -eq "TOO_LARGE") {
            Send-PortalApiError -Context $Context -StatusCode 413 -Code "FILE_TOO_LARGE" -Message ("文件超过大小上限（{0} 字节）" -f $Config.upload.maxFileBytes)
        } else {
            Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message ("上传请求解析失败：" + (Get-PortalString $multipart.error "未知错误"))
        }
        return
    }
    $fields = $multipart.fields
    $kind = (Get-PortalString $fields["kind"] "").Trim().ToLowerInvariant()
    if ($null -eq (Get-PortalKindInfo -Kind $kind)) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message "kind 只支持 dp 或 map"
        return
    }
    $files = @($multipart.files)
    if ($files.Count -ne 1) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "必须且只能上传一个文件"
        return
    }
    $file = $files[0]
    if ($file.size -le 0) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "文件为空"
        return
    }
    if ([long]$file.size -gt [long]$Config.upload.maxFileBytes) {
        Send-PortalApiError -Context $Context -StatusCode 413 -Code "FILE_TOO_LARGE" -Message ("文件超过大小上限（{0} 字节）" -f $Config.upload.maxFileBytes)
        return
    }
    $leaf = Get-PortalSafeLeafName -RawName $file.fileName
    if ($leaf -eq "") {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_FILENAME" -Message "文件名非法（含路径分隔符、控制字符或 Windows 保留名）"
        return
    }
    $allowed = Get-PortalKindAllowedExtensions -Config $Config -Kind $kind
    if (-not (Test-PortalAllowedExtension -FileName $leaf -AllowedExtensions $allowed)) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message ("不支持的文件类型（允许：{0}）" -f ($allowed -join " / "))
        return
    }
    # M-4：文件内容以 (offset,count) 引用 body 缓冲区，不复制。
    $body = $multipart.body
    $fileOffset = [int]$file.offset
    $fileCount = [int]$file.count
    $ext = Get-PortalFileExtension -FileName $leaf
    if ($kind -eq "dp") {
        if ($ext -eq ".zip") {
            if (-not (Test-PortalZipMagic -Bytes $body -Offset $fileOffset -Count $fileCount)) {
                Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message "不是有效的 ZIP 包（缺少本地文件头或目录结束记录）"
                return
            }
        } elseif ($ext -eq ".json") {
            # M-2：.json 必须真的能解析；.hjson/.json5 不是合法 JSON，只能弱校验。
            if (-not (Test-PortalJsonStrict -Bytes $body -Offset $fileOffset -Count $fileCount)) {
                Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message "JSON 解析失败（不是合法的 JSON 对象/数组）"
                return
            }
        } else {
            if (-not (Test-PortalJsonLikeMagic -Bytes $body -Offset $fileOffset -Count $fileCount)) {
                Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message "HJSON/JSON5 文件内容看起来不是对象/数组"
                return
            }
        }
    } elseif ($kind -eq "map") {
        if (-not (Test-PortalMsavMagic -Bytes $body -Offset $fileOffset -Count $fileCount)) {
            Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message "不是有效的 Mindustry 地图文件（.msav 解压后缺少 MSAV 头或版本号异常）"
            return
        }
    }

    $title = (Get-PortalString $fields["title"] "").Trim()
    $author = (Get-PortalString $fields["author"] "").Trim()
    $desc = (Get-PortalString $fields["desc"] "").Trim()
    $replaceOfRaw = (Get-PortalString $fields["replaceOf"] "").Trim()
    if ($title -eq "") {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "请填写介绍/标题"
        return
    }
    if ($author -eq "") {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "请填写作者"
        return
    }
    if ($title.Length -gt (Get-PortalInt $Config.upload.titleMaxChars 200 1 1000)) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "标题过长"
        return
    }
    if ($author.Length -gt (Get-PortalInt $Config.upload.authorMaxChars 100 1 200)) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "作者名过长"
        return
    }
    if ($desc.Length -gt (Get-PortalInt $Config.upload.descMaxChars 4000 1 20000)) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "介绍过长"
        return
    }
    $replaceOf = ""
    if ($replaceOfRaw -ne "") {
        $replaceOf = Get-PortalSafeLeafName -RawName $replaceOfRaw
        if ($replaceOf -eq "") {
            Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "replaceOf 非法"
            return
        }
        $existing = Get-PortalExistingFileByName -Config $Config -Kind $kind -Name $replaceOf
        if ($null -ne $existing) { $replaceOf = $existing.Name }
    }

    # 队列上限（每种类型独立 10 个待审）：先校验再入队，避免脏文件占用队列。
    $queue = Get-PortalQueueState -Config $Config -Kind $kind
    if ($queue.pending -ge $queue.limit) {
        Write-PortalLog -Config $Config -Level "WARN" -Message ("上传被拒（待审队列已满）：kind=$kind ip=$ClientIp pending=" + $queue.pending + "/" + $queue.limit)
        $errBody = [ordered]@{
            ok    = $false
            error = [ordered]@{
                code    = "QUEUE_FULL"
                message = "待审核队列已满（$($queue.pending)/$($queue.limit)），请稍后再试"
                queue   = $queue
            }
        }
        Send-PortalJson -Context $Context -StatusCode 409 -Object $errBody
        return
    }

    $store = Read-PortalStore -Config $Config
    $id = New-PortalSubmissionId -Kind $kind
    $payload = $null
    try {
        $payload = Save-PortalUploadedPayload -Config $Config -Id $id -FileName $leaf -Body $body -Offset $fileOffset -Count $fileCount
    } catch {
        # 不把 .NET 异常消息回显给客户端（可能含方法名/路径）；细节只进服务端日志。
        Write-PortalLog -Config $Config -Level "ERROR" -Message ("保存上传文件失败 id=$id file=$leaf：" + $_.Exception.Message)
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "UPLOAD_SAVE_FAILED" -Message "保存上传文件失败（服务器内部错误）"
        return
    }
    $payload.fileName = $leaf
    $fieldsForStore = @{ title = $title; author = $author; desc = $desc; replaceOf = $replaceOf }
    try {
        $item = Add-PortalSubmission -Config $Config -Kind $kind -Fields $fieldsForStore -Payload $payload -ClientIp $ClientIp
    } catch {
        try { Remove-Item -LiteralPath ([System.IO.Path]::GetDirectoryName($payload.fullPath)) -Recurse -Force -ErrorAction SilentlyContinue } catch { }
        Write-PortalLog -Config $Config -Level "ERROR" -Message ("保存提交记录失败 id=$id：" + $_.Exception.Message)
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "UPLOAD_SAVE_FAILED" -Message "保存提交记录失败（服务器内部错误）"
        return
    }
    $newQueue = Get-PortalQueueState -Config $Config -Kind $kind
    # R2-6：只有真正受理（业务成功）才消耗每 IP 上传配额。
    Add-PortalUploadHit -Config $Config -ClientIp $ClientIp
    $data = [ordered]@{
        id     = [string]$item.id
        kind   = $kind
        status = "pending"
        title  = $title
        author = $author
        fileName = $leaf
        size   = [long]$file.size
        queue  = $newQueue
        message = "已受理，等待管理员审核"
    }
    Write-PortalLog -Config $Config -Level "INFO" -Message ("收到上传：kind=$kind id=$($item.id) file=$leaf size=$($file.size) ip=$ClientIp")
    Send-PortalApiOk -Context $Context -Data $data
}

# ---------------------------------------------------------------------------
# API 总入口（由 portal-server.ps1 路由调用）
# ---------------------------------------------------------------------------

function Handle-PortalApiRequest {
    param($Context, [System.Collections.IDictionary]$Config, [string]$Method, [string]$PathLower, [string]$ClientIp)
    # R1-F：API 不支持 HEAD。旧行为是照常 200 并写 body，HttpListener 会因 HEAD 不能有 body
    # 抛异常 → 每 60 秒一条 ERROR 日志。这里统一为 405，语义明确且不产生噪声。
    if ($Method -eq "HEAD") {
        Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "API 不支持 HEAD，请使用 GET"
        return
    }
    switch ($PathLower) {
        "/api/status" {
            if ($Method -ne "GET" -and $Method -ne "HEAD") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"; return }
            Handle-PortalApiStatus -Context $Context -Config $Config
            return
        }
        "/api/events" {
            if ($Method -ne "GET" -and $Method -ne "HEAD") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"; return }
            Handle-PortalApiEvents -Context $Context -Config $Config
            return
        }
        "/api/events/history" {
            if ($Method -ne "GET") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"; return }
            Handle-PortalApiEventsHistory -Context $Context -Config $Config -ClientIp $ClientIp
            return
        }
        "/api/logs" {
            if ($Method -ne "GET") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"; return }
            Handle-PortalApiLogs -Context $Context -Config $Config
            return
        }
        "/api/logs/download" {
            if ($Method -ne "GET") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"; return }
            Handle-PortalApiLogsDownload -Context $Context -Config $Config -ClientIp $ClientIp
            return
        }
        "/api/logs/tail" {
            if ($Method -ne "GET") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"; return }
            Handle-PortalApiLogsTail -Context $Context -Config $Config -ClientIp $ClientIp
            return
        }
        "/api/catalog" {
            if ($Method -ne "GET" -and $Method -ne "HEAD") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"; return }
            Handle-PortalApiCatalog -Context $Context -Config $Config
            return
        }
        "/api/health" {
            if ($Method -ne "GET" -and $Method -ne "HEAD") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"; return }
            Handle-PortalApiHealth -Context $Context -Config $Config
            return
        }
        "/api/login" {
            if ($Method -ne "POST") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 POST"; return }
            Handle-PortalApiLogin -Context $Context -Config $Config -ClientIp $ClientIp
            return
        }
        "/api/logout" {
            if ($Method -ne "POST") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 POST"; return }
            Handle-PortalApiLogout -Context $Context -Config $Config -ClientIp $ClientIp
            return
        }
        "/api/review/list" {
            if ($Method -ne "GET") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"; return }
            Handle-PortalApiReviewList -Context $Context -Config $Config
            return
        }
        "/api/upload" {
            if ($Method -ne "POST") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 POST"; return }
            Handle-PortalApiUpload -Context $Context -Config $Config -ClientIp $ClientIp
            return
        }
        "/api/review/approve" {
            if ($Method -ne "POST") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 POST"; return }
            Handle-PortalApiApprove -Context $Context -Config $Config -ClientIp $ClientIp
            return
        }
        "/api/review/reject" {
            if ($Method -ne "POST") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 POST"; return }
            Handle-PortalApiReject -Context $Context -Config $Config -ClientIp $ClientIp
            return
        }
        "/api/item" {
            if ($Method -ne "DELETE") { Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 DELETE"; return }
            Handle-PortalApiDeleteItem -Context $Context -Config $Config -ClientIp $ClientIp
            return
        }
        default {
            Send-PortalApiError -Context $Context -StatusCode 404 -Code "NOT_FOUND" -Message "接口不存在"
            return
        }
    }
}
