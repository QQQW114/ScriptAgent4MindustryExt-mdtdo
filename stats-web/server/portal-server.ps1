# ============================================================================
# MDT DO 服务器门户 - HTTP 服务主循环与路由
# 兼容 Windows PowerShell 5.1 与 PowerShell 7+。
# ============================================================================

function Get-PortalListenPrefix {
    param([string]$Bind, [int]$Port)
    $b = ""
    if ($null -ne $Bind) { $b = $Bind.Trim() }
    if ($b -eq "" -or $b -eq "*" -or $b -eq "0.0.0.0" -or $b -eq "+") { return "http://+:$Port/" }
    if ($b -eq "localhost") { return "http://localhost:$Port/" }
    return ("http://{0}:{1}/" -f $b, $Port)
}

function Test-PortalPublicBind {
    param([string]$Bind)
    $b = ""
    if ($null -ne $Bind) { $b = $Bind.Trim() }
    return ($b -eq "" -or $b -eq "*" -or $b -eq "0.0.0.0" -or $b -eq "+")
}

function Install-PortalUrlAcl {
    param([string]$Bind, [int]$Port)
    $prefix = Get-PortalListenPrefix -Bind $Bind -Port $Port
    $who = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    Write-Host "正在为当前用户注册 URL 保留：$prefix"
    Write-Host "  用户: $who"
    & netsh http add urlacl url=$prefix user="$who" 2>&1 | Out-Host
    Write-Host ""
    Write-Host "如果上面提示需要管理员权限，请用【管理员】PowerShell 再执行一次本命令。" -ForegroundColor Yellow
    Write-Host "注册成功后无需管理员即可运行：.\start-web.ps1 -Bind 0.0.0.0 -Port $Port" -ForegroundColor Green
}

function Show-PortalBanner {
    param([System.Collections.IDictionary]$Config, [string]$Prefix)
    $isDefaultPassword = ((Get-PortalString $Config.admin.passwordHash "") -eq "" -and
        (Test-PortalStringEquals -A (Get-PortalString $Config.admin.password "") -B $script:PortalDefaultAdminPassword))
    $isDefaultSecret = (Get-PortalString $Config.session.secret $script:PortalDefaultSessionSecret) -eq $script:PortalDefaultSessionSecret

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor DarkCyan
    Write-Host "  MDT DO 服务器门户（状态 / 事件流 / DP 与地图上传审核 / 管理后台）" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor DarkCyan
    Write-Host ("  监听地址:   {0}" -f $Prefix)
    Write-Host ("  配置文件:   {0}" -f (Get-PortalString $Config.configPath "(默认配置)")) -ForegroundColor DarkGray
    Write-Host ("  数据目录:   {0}" -f $Config.dataDirPath) -ForegroundColor DarkGray
    Write-Host ("  统计 JSON:  {0}" -f $Config.statsJsonPath) -ForegroundColor DarkGray
    Write-Host ("  事件 JSONL: {0}" -f $Config.eventsJsonlPath) -ForegroundColor DarkGray
    Write-Host ("  DP 目录:    {0}" -f $Config.dpDirPath) -ForegroundColor DarkGray
    Write-Host ("  地图目录:   {0}" -f $Config.mapsDirPath) -ForegroundColor DarkGray
    Write-Host ("  管理员账号: {0}（同一账号允许多 IP/多会话登录）" -f (Get-PortalString $Config.admin.username "admin"))
    Write-Host ("  上传限制:   单文件 {0:N1} MB，待审队列 每种类型上限 {1} 个" -f ([double]$Config.upload.maxFileBytes / 1MB), $Config.upload.queueLimitPerKind)
    Write-Host ("  日志下载:   {0}（{1}，并发 {2}，{3} 次/{4} 分钟/IP，单文件上限 {5:N0} MB）" -f `
        $(if (Get-PortalBool $Config.logs.enabled $true) { "启用" } else { "禁用" }), `
        $(if (Get-PortalBool $Config.logs.requireLogin $true) { "需管理员登录" } else { "公开（注意日志含敏感信息）" }), `
        (Get-PortalInt $Config.logs.maxConcurrentDownloads 2 1 16), `
        (Get-PortalInt $Config.logs.rateLimitPerWindow 5 1 10000), `
        (Get-PortalInt $Config.logs.rateWindowMinutes 5 1 1440), `
        ([double](Get-PortalLong $Config.logs.maxFileBytes 52428800 1024 1073741824) / 1MB))
    Write-Host ("  带宽采样:   {0}（接口={1}，间隔={2}s，窗口={3}min）" -f `
        $(if (Get-PortalBool $Config.bandwidth.enabled $true) { "启用" } else { "禁用" }), `
        (Get-PortalString $Config.bandwidth.interface "auto"), `
        (Get-PortalInt $Config.bandwidth.intervalSeconds 10 2 300), `
        (Get-PortalInt $Config.bandwidth.windowMinutes 5 1 60))
    Write-Host ("  控制台桥:   可选依赖（{0}，console.host/port={1}:{2}）；未开启时审核仍成功，只是不热重载/不刷新地图列表" -f `
        $(if (Get-PortalBool $Config.console.enabled $true) { "enabled=true（连不上自动降级为提示）" } else { "enabled=false" }), `
        (Get-PortalString $Config.console.host "127.0.0.1"), `
        (Get-PortalInt $Config.console.port 6859 1 65535)) -ForegroundColor DarkGray
    Write-Host "  API:        /api/status /api/events /api/events/history /api/catalog /api/logs/* /api/login /api/upload /api/review/* /api/item"
    if (Test-PortalPublicBind -Bind $Config.bind) {
        Write-Host "  [提示] 已监听所有网卡：请确认已执行 -InstallAcl 且防火墙已放行端口。" -ForegroundColor Yellow
    } else {
        Write-Host "  提示: 默认仅本机可访问；公网访问见 docs\web-portal.md。" -ForegroundColor DarkGray
    }
    if ($isDefaultPassword) {
        Write-Host ""
        Write-Host "  [安全警告] 正在使用默认管理员密码！请立即修改 web-config.json 的 admin.password" -ForegroundColor Red
        Write-Host "            （或改用 admin.passwordHash，用 start-web.ps1 -GenPasswordHash 生成）。" -ForegroundColor Red
    }
    if ($isDefaultSecret) {
        Write-Host "  [安全警告] 正在使用默认 session.secret，建议立即改为随机字符串。" -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "按 Ctrl+C 停止服务。" -ForegroundColor Green
}

function Handle-PortalRequest {
    param($Context, [System.Collections.IDictionary]$Config)
    $method = "GET"
    try { $method = ([string]$Context.Request.HttpMethod).ToUpperInvariant() } catch { }
    $path = "/"
    try { $path = [Uri]::UnescapeDataString([string]$Context.Request.Url.AbsolutePath) } catch { $path = "/" }
    $clientIp = Get-PortalClientIp -Context $Context -Config $Config
    try { $path = $path.Trim() } catch { $path = "/" }
    if ($path -eq "") { $path = "/" }

    if ($method -eq "OPTIONS") {
        Send-PortalResponseBytes -Context $Context -StatusCode 204 -ContentType "text/plain; charset=utf-8" -Body (New-Object byte[] 0) -Headers @{ "Allow" = "GET, POST, DELETE, OPTIONS" }
        return
    }

    # 旧入口兼容：/server-status.json 直接回传服务器脚本写的 JSON 原文（原 start-web.ps1 行为）。
    if ($path -eq "/server-status.json") {
        if ($method -ne "GET" -and $method -ne "HEAD") {
            Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "只支持 GET"
            return
        }
        $statsPath = Get-PortalString $Config.statsJsonPath ""
        if ($statsPath -eq "" -or -not (Test-Path -LiteralPath $statsPath)) {
            Send-PortalJson -Context $Context -StatusCode 404 -Object ([ordered]@{ error = "stats file not found"; path = $statsPath })
            return
        }
        try {
            $bytes = [System.IO.File]::ReadAllBytes($statsPath)
            Send-PortalResponseBytes -Context $Context -StatusCode 200 -ContentType "application/json; charset=utf-8" -Body $bytes -Headers @{ "Cache-Control" = "no-store" } -HeadOnly:($method -eq "HEAD")
        } catch {
            Send-PortalApiError -Context $Context -StatusCode 500 -Code "READ_FAILED" -Message "读取统计文件失败"
        }
        return
    }

    if ($path.StartsWith("/api/", [System.StringComparison]::OrdinalIgnoreCase)) {
        $pathLower = $path.ToLowerInvariant().TrimEnd('/')
        Handle-PortalApiRequest -Context $Context -Config $Config -Method $method -PathLower $pathLower -ClientIp $clientIp
        return
    }

    if ($method -ne "GET" -and $method -ne "HEAD") {
        Send-PortalApiError -Context $Context -StatusCode 405 -Code "METHOD_NOT_ALLOWED" -Message "静态资源只支持 GET"
        return
    }
    Send-PortalStaticFile -Context $Context -Config $Config -RelativePath $path -HeadOnly:($method -eq "HEAD")
}

function Send-PortalInternalError {
    # 真异常：回 500 + 写 ERROR 日志；客户端断开类静默忽略（只累计计数），
    # 同一错误签名 60s 内最多记一条，堆栈只保留前 3 帧。
    param($Context, [System.Collections.IDictionary]$Config, [string]$Message, $Exception = $null)
    if ($null -ne $Exception -and (Test-PortalClientDisconnectError -Exception $Exception)) {
        $script:PortalClientDisconnectCount++
        return
    }
    try {
        Send-PortalApiError -Context $Context -StatusCode 500 -Code "INTERNAL" -Message "服务器内部错误"
    } catch {
        if (Test-PortalClientDisconnectError -Exception $_.Exception) {
            $script:PortalClientDisconnectCount++
            return
        }
        try { $Context.Response.Abort() } catch { }
    }
    try {
        $signature = $Message
        if ($signature.Length -gt 200) { $signature = $signature.Substring(0, 200) }
        if ($null -ne $Exception) { $signature = $signature + " | " + $Exception.GetType().Name }
        $now = Get-PortalNowMillis
        $last = 0L
        if ($script:PortalErrorLogTs.ContainsKey($signature)) { $last = [long]$script:PortalErrorLogTs[$signature] }
        if ($last -eq 0L -or ($now - $last) -ge 60000L) {
            $script:PortalErrorLogTs[$signature] = $now
            $stack = ""
            if ($null -ne $Exception) {
                try {
                    if ($null -ne $Exception.StackTrace) {
                        $stack = (($Exception.StackTrace -split "`n") | Select-Object -First 3) -join "`n"
                    }
                } catch { }
            }
            $logText = $Message
            if ($stack -ne "") { $logText = $logText + "`n" + $stack }
            Write-PortalLog -Config $Config -Level "ERROR" -Message $logText
        }
    } catch { }
}

function Start-PortalServer {
    param([System.Collections.IDictionary]$Config)
    $prefix = Get-PortalListenPrefix -Bind $Config.bind -Port $Config.port
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add($prefix)
    try {
        $listener.Start()
    } catch {
        Write-Host ""
        Write-Host ("[错误] 监听失败：{0}" -f $_.Exception.Message) -ForegroundColor Red
        if (Test-PortalPublicBind -Bind $Config.bind) {
            Write-Host "  公网监听需要 URL 保留：请以【管理员】先执行一次：" -ForegroundColor Yellow
            Write-Host ("    .\start-web.ps1 -InstallAcl -Bind {0} -Port {1}" -f $Config.bind, $Config.port) -ForegroundColor Yellow
            Write-Host "  然后重新运行本程序。" -ForegroundColor Yellow
        } else {
            Write-Host ("  请检查端口是否被占用：netstat -ano | findstr :{0}" -f $Config.port) -ForegroundColor Yellow
            Write-Host "  或通过 -Port 指定其它端口（也可改 web-config.json）。" -ForegroundColor Yellow
        }
        try { $listener.Close() } catch { }
        throw
    }
    Show-PortalBanner -Config $Config -Prefix $prefix
    Write-PortalLog -Config $Config -Level "INFO" -Message ("门户已启动：{0}（config={1}）" -f $prefix, (Get-PortalString $Config.configPath "(默认配置)"))
    $contextAsync = $listener.BeginGetContext($null, $null)
    try {
        while ($listener.IsListening) {
            # 每轮主循环：控制台回显收尾 / 上行带宽采样 / 日志下载分块泵
            try { Receive-PortalConsoleResponses -Config $Config } catch { }
            try { Update-PortalBandwidthIfDue -Config $Config } catch { }
            try { Invoke-PortalDownloadPump } catch { }
            $waitMs = 500
            if ($script:PortalDownloadActive.Count -gt 0) { $waitMs = 25 }
            $gotRequest = $false
            try {
                $gotRequest = $contextAsync.AsyncWaitHandle.WaitOne($waitMs)
            } catch {
                break
            }
            if (-not $gotRequest) { continue }
            $context = $null
            try {
                $context = $listener.EndGetContext($contextAsync)
            } catch {
                break
            }
            try { $contextAsync = $listener.BeginGetContext($null, $null) } catch { break }
            $script:PortalResponseDeferred = $false
            try {
                Handle-PortalRequest -Context $context -Config $Config
            } catch {
                $caughtException = $_.Exception
                if ($script:PortalResponseDeferred) {
                    # 响应已进入分块下载泵，不能再写 JSON 错误体，直接中止该响应。
                    try { $context.Response.Abort() } catch { }
                    if (Test-PortalClientDisconnectError -Exception $caughtException) {
                        $script:PortalClientDisconnectCount++
                    } else {
                        try { Write-PortalLog -Config $Config -Level "WARN" -Message ("分块下载请求异常：" + $caughtException.Message) } catch { }
                    }
                } else {
                    $errMsg = $caughtException.Message
                    $stack = $_.ScriptStackTrace
                    Send-PortalInternalError -Context $context -Config $Config -Message ("处理请求异常：$errMsg`n$stack") -Exception $caughtException
                }
            }
        }
    } finally {
        try { Write-PortalLog -Config $Config -Level "INFO" -Message "门户正在停止..." } catch { }
        try { Close-PortalDownloads -Config $Config } catch { }
        try { Close-PortalConsolePending -Config $Config } catch { }
        try { $listener.Stop() } catch { }
        try { $listener.Close() } catch { }
        Write-Host ""
        Write-Host "门户已停止。" -ForegroundColor DarkGray
    }
}
