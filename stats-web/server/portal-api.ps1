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
    $netSnapshot = $null
    try { $netSnapshot = Get-PortalBandwidthSnapshot -Config $Config -IncludeSeries $true } catch {
        $netSnapshot = [ordered]@{ available = $false; avgUpBps = $null; windowMinutes = 0; sampledAt = $null; series = @(); error = $_.Exception.Message }
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
        $item = [ordered]@{
            ts    = $ts
            type  = $eventType
            text  = $eventText
            player = $player
        }
        $playerId = Get-PortalProp $obj "playerId" $null
        if ($null -ne $playerId -and [string]$playerId -ne "") { $item.playerId = [string]$playerId }
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
    param($Context, [System.Collections.IDictionary]$Config)
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
        webRoot          = Get-PortalString $Config.webRoot ""
        dataDir          = Get-PortalString $Config.dataDirPath ""
        statsAvailable   = $statsAvailable
        eventsAvailable  = $eventsAvailable
        eventHistoryFiles = $historyFiles.files.Count
        dpDir            = Get-PortalString $Config.dpDirPath ""
        mapsDir          = Get-PortalString $Config.mapsDirPath ""
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
        logsDir          = (Get-PortalString $Config.logsDirPath "")
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
    [void](Start-PortalLogDownload -Context $Context -Config $Config -Name $name -ClientIp $ClientIp -Actor $access.actor)
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
    if (Test-PortalLoginBlocked -Config $Config -ClientIp $ClientIp) {
        Send-PortalApiError -Context $Context -StatusCode 429 -Code "RATE_LIMITED" -Message "登录失败次数过多，请稍后再试"
        return
    }
    $body = Read-PortalRequestJson -Context $Context -MaxBytes 65536
    if (-not $body.ok) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "请求体格式错误"
        return
    }
    $username = Get-PortalString (Get-PortalProp $body.data "username" "") ""
    $password = Get-PortalString (Get-PortalProp $body.data "password" "") ""
    if ($username -eq "" -or $password -eq "") {
        Add-PortalLoginFailure -Config $Config -ClientIp $ClientIp
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_REQUEST" -Message "请输入账号与密码"
        return
    }
    $adminUser = Get-PortalString $Config.admin.username "admin"
    $userOk = Test-PortalStringEquals -A $adminUser -B $username
    $passOk = Test-PortalAdminPassword -Config $Config -Password $password
    if (-not ($userOk -and $passOk)) {
        Add-PortalLoginFailure -Config $Config -ClientIp $ClientIp
        Write-PortalLog -Config $Config -Level "WARN" -Message "管理员登录失败：user=$username ip=$ClientIp"
        Send-PortalApiError -Context $Context -StatusCode 401 -Code "BAD_CREDENTIALS" -Message "账号或密码错误"
        return
    }
    Reset-PortalLoginFailures -ClientIp $ClientIp
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
    $multipart = Read-PortalMultipart -Context $Context -MaxRequestBytes ([long]$Config.upload.maxRequestBytes) -MaxFileBytes ([long]$Config.upload.maxFileBytes)
    if (-not $multipart.ok) {
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
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message "文件名非法"
        return
    }
    $allowed = Get-PortalKindAllowedExtensions -Config $Config -Kind $kind
    if (-not (Test-PortalAllowedExtension -FileName $leaf -AllowedExtensions $allowed)) {
        Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message ("不支持的文件类型（允许：{0}）" -f ($allowed -join " / "))
        return
    }
    $ext = Get-PortalFileExtension -FileName $leaf
    $bytes = [byte[]]$file.bytes
    if ($kind -eq "dp") {
        if ($ext -eq ".zip") {
            if (-not (Test-PortalZipMagic -Bytes $bytes)) {
                Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message "ZIP 文件头无效"
                return
            }
        } else {
            if (-not (Test-PortalJsonLikeMagic -Bytes $bytes)) {
                Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message "JSON/HJSON 文件内容看起来不是对象/数组"
                return
            }
        }
    } elseif ($kind -eq "map") {
        if (-not (Test-PortalMsavMagic -Bytes $bytes)) {
            Send-PortalApiError -Context $Context -StatusCode 400 -Code "BAD_TYPE" -Message "不是有效的 Mindustry 地图文件（.msav）"
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
        $payload = Save-PortalUploadedPayload -Config $Config -Id $id -FileName $leaf -Bytes $bytes
    } catch {
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "UPLOAD_SAVE_FAILED" -Message ("保存上传文件失败：" + $_.Exception.Message)
        return
    }
    $payload.fileName = $leaf
    $fieldsForStore = @{ title = $title; author = $author; desc = $desc; replaceOf = $replaceOf }
    try {
        $item = Add-PortalSubmission -Config $Config -Kind $kind -Fields $fieldsForStore -Payload $payload -ClientIp $ClientIp
    } catch {
        try { Remove-Item -LiteralPath ([System.IO.Path]::GetDirectoryName($payload.fullPath)) -Recurse -Force -ErrorAction SilentlyContinue } catch { }
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "UPLOAD_SAVE_FAILED" -Message ("保存提交记录失败：" + $_.Exception.Message)
        return
    }
    $newQueue = Get-PortalQueueState -Config $Config -Kind $kind
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
