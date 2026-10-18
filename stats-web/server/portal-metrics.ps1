# ============================================================================
# MDT DO 服务器门户 - 宿主机上行带宽采样（第二期）
#
# 口径：Windows 网卡累计发送字节（Get-NetAdapterStatistics.SentBytes）差分 / 时间，
#       即「宿主机网卡上行总量」，包含游戏服 + Web + 系统其它流量，不代表纯游戏流量；
#       游戏服与 Web 不在同一台机器时该指标无意义。
# 开销：默认每 10 秒读一次系统计数器（毫秒级），不占用游戏服务器主线程（Web 独立进程）。
# 兼容 Windows PowerShell 5.1 与 PowerShell 7+。
# ============================================================================

$script:PortalBandwidth = @{
    samples        = (New-Object System.Collections.ArrayList)   # @{ts=<epoch ms>; bps=<double>}
    lastTotalBytes = -1L
    lastSampleTs   = 0L
    lastAttemptTs  = 0L
    ifaceName      = ""
    ifaceIndex     = -1
    available      = $false
    lastError      = ""
    lastErrorLogTs = 0L
}

function Resolve-PortalBandwidthInterface {
    # 返回网卡对象（Get-NetAdapter）；失败返回 $null。interface=auto 时取默认路由网卡。
    param([System.Collections.IDictionary]$Config)
    $wanted = Get-PortalString $Config.bandwidth.interface "auto"
    try {
        if ($wanted -ne "" -and $wanted -ne "auto") {
            $adapter = Get-NetAdapter -Name $wanted -ErrorAction SilentlyContinue
            if ($null -eq $adapter) {
                $adapter = Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceDescription -eq $wanted } | Select-Object -First 1
            }
            return $adapter
        }
        # auto：按默认路由（0.0.0.0/0）选活动网卡；取不到再退回任一 Up 的物理网卡。
        $route = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
            Sort-Object -Property RouteMetric, ifMetric |
            Select-Object -First 1
        if ($null -ne $route) {
            $adapter = Get-NetAdapter -InterfaceIndex $route.InterfaceIndex -ErrorAction SilentlyContinue
            if ($null -ne $adapter) { return $adapter }
        }
        $adapter = Get-NetAdapter -Physical -ErrorAction SilentlyContinue |
            Where-Object { $_.Status -eq "Up" } |
            Select-Object -First 1
        if ($null -ne $adapter) { return $adapter }
        return Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq "Up" } | Select-Object -First 1
    } catch {
        return $null
    }
}

function Get-PortalNetAdapterSentBytes {
    param($Adapter)
    $stats = Get-NetAdapterStatistics -Name $Adapter.Name -ErrorAction Stop
    return [long]$stats.SentBytes
}

function Set-PortalBandwidthError {
    param([System.Collections.IDictionary]$Config, [string]$Message)
    $script:PortalBandwidth.available = $false
    $script:PortalBandwidth.lastError = $Message
    $now = Get-PortalNowMillis
    if (($now - [long]$script:PortalBandwidth.lastErrorLogTs) -gt 60000L) {
        $script:PortalBandwidth.lastErrorLogTs = $now
        try { Write-PortalLog -Config $Config -Level "WARN" -Message ("上行带宽采样失败（已降级，不影响其它接口）：" + $Message) } catch { }
    }
}

function Update-PortalBandwidthSample {
    # 采一次样（调用方按 intervalSeconds 控制频率）。
    param([System.Collections.IDictionary]$Config)
    if (-not (Get-PortalBool $Config.bandwidth.enabled $true)) {
        $script:PortalBandwidth.available = $false
        $script:PortalBandwidth.lastError = "disabled"
        return
    }
    $now = Get-PortalNowMillis
    try {
        $adapter = $null
        if ($script:PortalBandwidth.ifaceIndex -ge 0 -and $script:PortalBandwidth.ifaceName -ne "") {
            $adapter = Get-NetAdapter -InterfaceIndex $script:PortalBandwidth.ifaceIndex -ErrorAction SilentlyContinue
        }
        if ($null -eq $adapter) {
            $adapter = Resolve-PortalBandwidthInterface -Config $Config
        }
        if ($null -eq $adapter) {
            Set-PortalBandwidthError -Config $Config -Message "未找到可用的网络适配器"
            return
        }
        $script:PortalBandwidth.ifaceName = [string]$adapter.Name
        $script:PortalBandwidth.ifaceIndex = [int]$adapter.ifIndex
        $total = Get-PortalNetAdapterSentBytes -Adapter $adapter
        $previousTotal = [long]$script:PortalBandwidth.lastTotalBytes
        $previousTs = [long]$script:PortalBandwidth.lastSampleTs
        $script:PortalBandwidth.lastTotalBytes = [long]$total
        $script:PortalBandwidth.lastSampleTs = $now
        $script:PortalBandwidth.available = $true
        $script:PortalBandwidth.lastError = ""
        if ($previousTotal -ge 0 -and $previousTs -gt 0 -and $total -ge $previousTotal) {
            $deltaBytes = [double]($total - $previousTotal)
            $deltaSeconds = [double]($now - $previousTs) / 1000.0
            if ($deltaSeconds -gt 0.2) {
                $bps = $deltaBytes / $deltaSeconds
                [void]$script:PortalBandwidth.samples.Add(@{ ts = $now; bps = [double]$bps })
            }
        }
        # 只保留窗口内的样本
        $windowMs = [long](Get-PortalInt $Config.bandwidth.windowMinutes 5 1 60) * 60000L
        $cut = $now - $windowMs
        while ($script:PortalBandwidth.samples.Count -gt 0 -and [long]$script:PortalBandwidth.samples[0].ts -lt $cut) {
            $script:PortalBandwidth.samples.RemoveAt(0)
        }
        $maxSeries = Get-PortalInt $Config.bandwidth.seriesPoints 30 0 240
        if ($maxSeries -gt 0) {
            while ($script:PortalBandwidth.samples.Count -gt ($maxSeries * 2)) {
                $script:PortalBandwidth.samples.RemoveAt(0)
            }
        }
    } catch {
        Set-PortalBandwidthError -Config $Config -Message $_.Exception.Message
    }
}

function Update-PortalBandwidthIfDue {
    param([System.Collections.IDictionary]$Config)
    $intervalMs = [long](Get-PortalInt $Config.bandwidth.intervalSeconds 10 2 300) * 1000L
    $now = Get-PortalNowMillis
    if (($now - [long]$script:PortalBandwidth.lastAttemptTs) -lt $intervalMs) { return }
    $script:PortalBandwidth.lastAttemptTs = $now
    Update-PortalBandwidthSample -Config $Config
}

function Get-PortalBandwidthSnapshot {
    # 返回 @{available;avgUpBps;windowMinutes;sampledAt;sampledAtText;interface;series;error}
    param([System.Collections.IDictionary]$Config, [bool]$IncludeSeries = $true)
    $windowMinutes = Get-PortalInt $Config.bandwidth.windowMinutes 5 1 60
    $snapshot = [ordered]@{
        available      = [bool]$script:PortalBandwidth.available
        unit           = "bytes_per_second"   # avgUpBps/series[].bps 的单位都是「字节/秒」（SentBytes 差分），不是 bit/s
        avgUpBps       = $null
        avgUpBytesPerSec = $null
        windowMinutes  = $windowMinutes
        intervalSeconds = (Get-PortalInt $Config.bandwidth.intervalSeconds 10 2 300)
        sampledAt      = $null
        sampledAtText  = $null
        interface      = $(if ($script:PortalBandwidth.ifaceName -ne "") { [string]$script:PortalBandwidth.ifaceName } else { $null })
        series         = @()
        error          = $(if ($script:PortalBandwidth.lastError -ne "") { [string]$script:PortalBandwidth.lastError } else { $null })
        note           = "宿主机网卡上行总量（含游戏服/Web/系统流量），不等于纯游戏流量；游戏服与 Web 不同机时无意义"
    }
    $samples = $script:PortalBandwidth.samples
    if ($null -ne $samples -and $samples.Count -gt 0) {
        $sum = 0.0
        foreach ($s in $samples) { $sum += [double]$s.bps }
        $snapshot.avgUpBps = [Math]::Round($sum / [double]$samples.Count, 1)
        $snapshot.avgUpBytesPerSec = $snapshot.avgUpBps
        $last = $samples[$samples.Count - 1]
        $snapshot.sampledAt = [long]$last.ts
        $snapshot.sampledAtText = Format-PortalMillis ([long]$last.ts)
        $snapshot.available = $true
    } elseif ($script:PortalBandwidth.lastSampleTs -gt 0) {
        $snapshot.sampledAt = [long]$script:PortalBandwidth.lastSampleTs
        $snapshot.sampledAtText = Format-PortalMillis ([long]$script:PortalBandwidth.lastSampleTs)
    }
    if ($IncludeSeries) {
        $seriesPoints = Get-PortalInt $Config.bandwidth.seriesPoints 30 0 240
        if ($seriesPoints -gt 0 -and $null -ne $samples -and $samples.Count -gt 0) {
            $start = [Math]::Max(0, $samples.Count - $seriesPoints)
            $series = New-Object System.Collections.ArrayList
            for ($i = $start; $i -lt $samples.Count; $i++) {
                [void]$series.Add([ordered]@{ ts = [long]$samples[$i].ts; bps = [Math]::Round([double]$samples[$i].bps, 1) })
            }
            $snapshot.series = @($series)
        }
    }
    return $snapshot
}
