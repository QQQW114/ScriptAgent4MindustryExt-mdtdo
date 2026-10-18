# ============================================================================
# MDT DO 服务器门户 - 游戏控制台桥（非阻塞 + 可达性健康检查）
# 说明：游戏服控制台 Socket（server.properties socketInput=true）只接受
#       "一行 UTF-8 文本 + \n" 的原始字节协议。为避免慢命令（externalcp load
#       可能几十秒）阻塞 Web 请求，这里采用「发送后不等待」：
#       - 连接/写入是毫秒级操作，失败立即返回；
#       - 回显在后续请求循环里通过 DataAvailable 非阻塞收集（到期/空闲即收尾）；
#       - 结果只写入 web-data/console-actions.log，不影响审核结果。
#   /api/health 的 console 字段用「TCP 连接探测 + 10s 缓存」判断可达性，
#   不做每请求连接；审核/删除若因控制台不可达而未能触发游戏侧动作，
#   会在响应 data.warnings 里返回明确提示（管理后台可展示）。
# 兼容 Windows PowerShell 5.1 与 PowerShell 7+。
# ============================================================================

$script:PortalConsolePending = New-Object System.Collections.ArrayList

# 控制台可达性健康状态（lastCheck 缓存 10s；失败日志限频 60s 同错误最多一条）
$script:PortalConsoleHealth = @{
    reachable      = $false
    lastError      = ""
    lastCheckTs    = 0L
    lastOkTs       = 0L
    lastErrorLogTs = 0L
    lastErrorLogTx = ""
}

function Get-PortalConsoleHint {
    param([System.Collections.IDictionary]$Config)
    return "游戏控制台（server.properties socketInput=true）是可选增强：开启后可热重载 DP / 刷新地图列表；未开启时审核仍成功（文件已落盘），需游戏服重启或手动操作后生效。检查 server.properties 的 socketInput/socketInputPort（默认 6859）与 web-config.json 的 console.host/port"
}

function Write-PortalConsoleInfo {
    # 控制台不可用属「可选功能未开启」的正常情况：只记 INFO（同一文本 60s 内最多一条），不报 WARN/ERROR。
    param([System.Collections.IDictionary]$Config, [string]$Message)
    $now = Get-PortalNowMillis
    $same = ([string]$script:PortalConsoleHealth.lastErrorLogTx -eq $Message)
    if ($same -and (($now - [long]$script:PortalConsoleHealth.lastErrorLogTs) -lt 60000L)) { return }
    $script:PortalConsoleHealth.lastErrorLogTs = $now
    $script:PortalConsoleHealth.lastErrorLogTx = $Message
    try { Write-PortalLog -Config $Config -Level "INFO" -Message $Message } catch { }
}

function Set-PortalConsoleHealthState {
    param([bool]$Reachable, [string]$ErrorText = "")
    $now = Get-PortalNowMillis
    $script:PortalConsoleHealth.lastCheckTs = $now
    $script:PortalConsoleHealth.reachable = $Reachable
    if ($Reachable) {
        $script:PortalConsoleHealth.lastOkTs = $now
        $script:PortalConsoleHealth.lastError = ""
    } else {
        $script:PortalConsoleHealth.lastError = $ErrorText
    }
}

function Test-PortalConsolePort {
    # 轻量 TCP 可达性探测（500ms 上限）；结果缓存 10s，-Force 可强制刷新。
    param([System.Collections.IDictionary]$Config, [switch]$Force)
    $now = Get-PortalNowMillis
    if (-not $Force -and (($now - [long]$script:PortalConsoleHealth.lastCheckTs) -lt 10000L)) {
        return [bool]$script:PortalConsoleHealth.reachable
    }
    if (-not (Get-PortalBool $Config.console.enabled $true)) {
        Set-PortalConsoleHealthState -Reachable $false -ErrorText "console bridge disabled（web-config.json console.enabled=false）"
        return $false
    }
    $hostName = Get-PortalString $Config.console.host "127.0.0.1"
    $portNum = Get-PortalInt $Config.console.port 6859 1 65535
    $client = $null
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.NoDelay = $true
        $connectTask = $client.BeginConnect($hostName, $portNum, $null, $null)
        $connected = $connectTask.AsyncWaitHandle.WaitOne(500)
        if (-not $connected) {
            Set-PortalConsoleHealthState -Reachable $false -ErrorText ("connect timeout（{0}:{1} 未监听或被防火墙拦截）" -f $hostName, $portNum)
            Write-PortalConsoleInfo -Config $Config -Message ("游戏控制台未开启/不可达（可选功能）：connect timeout {0}:{1}；文件落盘不受影响。{2}" -f $hostName, $portNum, (Get-PortalConsoleHint -Config $Config))
            return $false
        }
        try { $client.EndConnect($connectTask) } catch { }
        Set-PortalConsoleHealthState -Reachable $true
        return $true
    } catch {
        Set-PortalConsoleHealthState -Reachable $false -ErrorText ("连接失败：" + $_.Exception.Message)
        Write-PortalConsoleInfo -Config $Config -Message ("游戏控制台未开启/不可达（可选功能）：{0}:{1} {2}；文件落盘不受影响。{3}" -f $hostName, $portNum, $_.Exception.Message, (Get-PortalConsoleHint -Config $Config))
        return $false
    } finally {
        if ($null -ne $client) { try { $client.Close() } catch { } }
    }
}

function Get-PortalConsoleHealth {
    # /api/health 用：{enabled,reachable,optional,status,host,port,lastError,checkedAt,lastOkAt,hint}
    # 控制台桥是可选增强：未开启/不可达属正常情况，前端应按灰色「可选/未开启」展示，不应作为错误。
    param([System.Collections.IDictionary]$Config, [switch]$Force)
    $enabled = Get-PortalBool $Config.console.enabled $true
    $hostName = Get-PortalString $Config.console.host "127.0.0.1"
    $portNum = Get-PortalInt $Config.console.port 6859 1 65535
    $reachable = $false
    if ($enabled) {
        try { $reachable = Test-PortalConsolePort -Config $Config -Force:$Force } catch { $reachable = $false }
    } else {
        Set-PortalConsoleHealthState -Reachable $false -ErrorText "console bridge disabled（web-config.json console.enabled=false）"
    }
    $result = [ordered]@{
        enabled   = $enabled
        reachable = $reachable
        optional  = $true
        status    = $(if ($reachable) { "ok" } elseif ($enabled) { "not-reachable" } else { "disabled" })
        host      = $hostName
        port      = $portNum
        lastError = $(if ($script:PortalConsoleHealth.lastError -ne "") { [string]$script:PortalConsoleHealth.lastError } else { $null })
        checkedAt = [long]$script:PortalConsoleHealth.lastCheckTs
        lastOkAt  = [long]$script:PortalConsoleHealth.lastOkTs
        hint      = $(if ($reachable) { $null } else { (Get-PortalConsoleHint -Config $Config) })
    }
    return $result
}

function Invoke-PortalConsoleCommand {
    param([System.Collections.IDictionary]$Config, [string]$Command)
    if ($null -eq $Config -or -not (Get-PortalBool $Config.console.enabled $true)) {
        Set-PortalConsoleHealthState -Reachable $false -ErrorText "console bridge disabled（web-config.json console.enabled=false）"
        return @{ dispatched = $false; reason = "console bridge disabled（web-config.json console.enabled=false）" }
    }
    if ([string]::IsNullOrWhiteSpace($Command)) {
        return @{ dispatched = $false; reason = "empty command" }
    }
    $maxPending = Get-PortalInt $Config.security.maxPendingConsoleCommands 8 1 64
    try { Receive-PortalConsoleResponses -Config $Config } catch { }
    if ($script:PortalConsolePending.Count -ge $maxPending) {
        return @{ dispatched = $false; reason = "too many pending console commands（上次游戏侧命令尚未收尾，稍后重试）" }
    }
    $client = $null
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.NoDelay = $true
        $connectTask = $client.BeginConnect([string]$Config.console.host, [int]$Config.console.port, $null, $null)
        $connected = $connectTask.AsyncWaitHandle.WaitOne(600)
        if (-not $connected) {
            $client.Close()
            $reason = "connect timeout（游戏服控制台端口未监听？）"
            Set-PortalConsoleHealthState -Reachable $false -ErrorText $reason
            Write-PortalConsoleInfo -Config $Config -Message ("游戏控制台未开启/不可达（可选功能）：{0}:{1} {2}；文件落盘不受影响。{3}" -f [string]$Config.console.host, [int]$Config.console.port, $reason, (Get-PortalConsoleHint -Config $Config))
            return @{ dispatched = $false; reason = $reason }
        }
        $client.EndConnect($connectTask)
        $stream = $client.GetStream()
        $stream.WriteTimeout = 3000
        $bytes = (Get-PortalUtf8NoBom).GetBytes($Command + "`n")
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        $waitSeconds = Get-PortalInt $Config.console.waitSeconds 30 2 120
        $now = Get-PortalNowMillis
        $entry = @{
            client     = $client
            stream     = $stream
            command    = $Command
            output     = (New-Object System.Text.StringBuilder)
            createdAt  = $now
            lastDataAt = 0L
            deadline   = $now + ([long]$waitSeconds * 1000L)
        }
        [void]$script:PortalConsolePending.Add($entry)
        Set-PortalConsoleHealthState -Reachable $true
        return @{ dispatched = $true; async = $true; command = $Command }
    } catch {
        if ($null -ne $client) { try { $client.Close() } catch { } }
        $reason = "connect/write failed: " + $_.Exception.Message
        Set-PortalConsoleHealthState -Reachable $false -ErrorText $reason
        Write-PortalConsoleInfo -Config $Config -Message ("游戏控制台未开启/不可达（可选功能）：{0}:{1} {2}；文件落盘不受影响。{3}" -f [string]$Config.console.host, [int]$Config.console.port, $reason, (Get-PortalConsoleHint -Config $Config))
        return @{ dispatched = $false; reason = $reason }
    }
}

function Receive-PortalConsoleResponses {
    param([System.Collections.IDictionary]$Config)
    if ($script:PortalConsolePending.Count -eq 0) { return }
    $buffer = New-Object byte[] 8192
    $finished = New-Object System.Collections.ArrayList
    $now = Get-PortalNowMillis
    foreach ($entry in @($script:PortalConsolePending)) {
        try {
            while ($true) {
                $hasData = $false
                try { $hasData = $entry.stream.DataAvailable } catch { $hasData = $false }
                if (-not $hasData) { break }
                $read = $entry.stream.Read($buffer, 0, $buffer.Length)
                if ($read -le 0) { break }
                [void]$entry.output.Append((Get-PortalUtf8NoBom).GetString($buffer, 0, $read))
                $entry.lastDataAt = $now
            }
        } catch { }
        $closed = $false
        try { $closed = -not $entry.client.Connected } catch { $closed = $true }
        $hasOutput = ($entry.output.Length -gt 0)
        $idleDone = ($hasOutput -and $entry.lastDataAt -gt 0 -and ($now - $entry.lastDataAt) -ge 3000L)
        $timedOut = ($now -ge [long]$entry.deadline)
        if ($timedOut -or $idleDone -or ($closed -and ($hasOutput -or ($now - $entry.createdAt) -ge 2000L))) {
            [void]$finished.Add($entry)
        }
    }
    foreach ($entry in $finished) {
        try { $entry.stream.Close() } catch { }
        try { $entry.client.Close() } catch { }
        $outputText = $entry.output.ToString().Trim()
        $status = if ($outputText -eq "") { "no-output" } else { "ok" }
        try {
            Write-PortalConsoleLog -Config $Config -Command $entry.command -Output $outputText -Status $status
        } catch { }
        $summary = $outputText
        if ($summary.Length -gt 300) { $summary = $summary.Substring(0, 300) + "..." }
        if ($summary -eq "") { $summary = "（无回显）" }
        Write-PortalLog -Config $Config -Level "INFO" -Message ("游戏侧控制台命令收尾：{0} -> {1}" -f $entry.command, $summary)
        try { [void]$script:PortalConsolePending.Remove($entry) } catch { }
    }
}

function Close-PortalConsolePending {
    param([System.Collections.IDictionary]$Config)
    foreach ($entry in @($script:PortalConsolePending)) {
        $outputText = $entry.output.ToString().Trim()
        try { Write-PortalConsoleLog -Config $Config -Command $entry.command -Output $outputText -Status "shutdown" } catch { }
        try { $entry.stream.Close() } catch { }
        try { $entry.client.Close() } catch { }
        try { [void]$script:PortalConsolePending.Remove($entry) } catch { }
    }
}
