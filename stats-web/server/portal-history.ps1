# ============================================================================
# MDT DO 服务器门户 - 历史事件查询（第二期）
#
# 数据来源：live-events.jsonl（当前）+ live-events.N.jsonl（旋转历史，N=1 最新）。
# 游戏侧 liveEventFeed.kts 的 keepRotatedFiles 决定保留份数（默认 12），
# Web 侧 events.historyMaxFiles 建议 = keepRotatedFiles + 1。
#
# 实现要点：
#   - 倒序分页：从最新文件向最旧文件扫描，文件内从末行向前；cursor 语义为「只返回 ts < cursor」；
#   - 大文件不整读内存：按行文本缓存（按文件长度+修改时间失效，缓存总大小有上限），
#     先用正则/子串做 ts、type、keyword 过滤，只对命中的行做 JSON 解析；
#   - 命中 limit+1 即停止，避免每次全量解析。
# 兼容 Windows PowerShell 5.1 与 PowerShell 7+。
# ============================================================================

$script:PortalEventHistoryCache = New-Object 'System.Collections.Hashtable'
$script:PortalEventHistoryCacheBytes = 0L
$script:PortalEventTsRegex = [regex]'(?m)^\s*\{\s*"ts"\s*:\s*(\d+)'
$script:PortalEventAllowedTypes = @("chat", "join", "leave", "vote_start", "vote_pass", "vote_fail", "map_change", "system")

function Get-PortalEventHistoryFiles {
    # 返回 @{available;files=@( @{path;name;isCurrent} )}，按「最新→最旧」排序（当前文件最新，.1 次之…）
    param([System.Collections.IDictionary]$Config)
    $result = @{ available = $false; files = @() }
    $currentPath = Get-PortalString $Config.eventsJsonlPath ""
    if ($currentPath -eq "") { return $result }
    $dir = [System.IO.Path]::GetDirectoryName($currentPath)
    if ([string]::IsNullOrWhiteSpace($dir) -or -not (Test-Path -LiteralPath $dir -PathType Container)) { return $result }
    $currentName = [System.IO.Path]::GetFileName($currentPath)
    $baseName = $currentName
    if ($baseName.EndsWith(".jsonl", [System.StringComparison]::OrdinalIgnoreCase)) {
        $baseName = $baseName.Substring(0, $baseName.Length - 6)
    }
    $maxFiles = Get-PortalInt $Config.events.historyMaxFiles 13 1 100
    $files = New-Object System.Collections.ArrayList
    if (Test-Path -LiteralPath $currentPath -PathType Leaf) {
        [void]$files.Add(@{ path = $currentPath; name = $currentName; isCurrent = $true })
    }
    for ($i = 1; $i -lt $maxFiles; $i++) {
        $rotPath = Join-Path $dir ("{0}.{1}.jsonl" -f $baseName, $i)
        if (Test-Path -LiteralPath $rotPath -PathType Leaf) {
            [void]$files.Add(@{ path = $rotPath; name = [System.IO.Path]::GetFileName($rotPath); isCurrent = $false })
        }
    }
    if ($files.Count -eq 0) { return $result }
    $result.available = $true
    $result.files = @($files)
    return $result
}

function Get-PortalEventFileText {
    # 带缓存读取事件文件全文（缓存按 length + LastWriteTimeUtc 失效；总缓存大小有上限）。
    param([System.Collections.IDictionary]$Config, [string]$Path)
    try {
        $info = Get-Item -LiteralPath $Path -ErrorAction Stop
    } catch {
        return $null
    }
    $key = $Path
    $cacheMax = Get-PortalLong $Config.events.historyCacheMaxBytes 8388608 1048576 134217728
    if ($script:PortalEventHistoryCache.ContainsKey($key)) {
        $cached = $script:PortalEventHistoryCache[$key]
        if ($cached -and [long]$cached.length -eq [long]$info.Length -and [long]$cached.ticks -eq [long]$info.LastWriteTimeUtc.Ticks) {
            return [string]$cached.text
        }
        $script:PortalEventHistoryCacheBytes = $script:PortalEventHistoryCacheBytes - [long]$cached.length
        $script:PortalEventHistoryCache.Remove($key)
    }
    $text = $null
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        try { $text = Read-PortalTextFile -Path $Path; break } catch { Start-Sleep -Milliseconds 40 }
    }
    if ($null -eq $text) { return $null }
    $entry = @{ length = [long]$info.Length; ticks = [long]$info.LastWriteTimeUtc.Ticks; text = $text }
    if (([long]$script:PortalEventHistoryCacheBytes + [long]$info.Length) -gt $cacheMax) {
        $script:PortalEventHistoryCache.Clear()
        $script:PortalEventHistoryCacheBytes = 0L
    }
    $script:PortalEventHistoryCache[$key] = $entry
    $script:PortalEventHistoryCacheBytes = [long]$script:PortalEventHistoryCacheBytes + [long]$info.Length
    return $text
}

function Get-PortalEventHistory {
    # 返回 @{ok;data={items,hasMore,nextCursor,limit,fromTs,toTs,type,keyword,files,available}}
    param(
        [System.Collections.IDictionary]$Config,
        [long]$FromTs,
        [long]$ToTs,
        [string]$Type,
        [string]$Keyword,
        [int]$Limit,
        [long]$Cursor
    )
    $safeLimit = [Math]::Max(1, [Math]::Min($Limit, 200))
    $filesInfo = Get-PortalEventHistoryFiles -Config $Config
    $data = [ordered]@{
        available = [bool]$filesInfo.available
        items     = @()
        hasMore   = $false
        nextCursor = $null
        limit     = $safeLimit
        fromTs    = $FromTs
        toTs      = $ToTs
        type      = $Type
        keyword   = $Keyword
        cursor    = $Cursor
        files     = @()
        scannedFiles = 0
        scannedLines = 0
    }
    if (-not $filesInfo.available) { return @{ ok = $true; data = $data } }

    $typeNeedle = ""
    if ($Type -ne "") { $typeNeedle = '"type":"' + $Type + '"' }
    $items = New-Object System.Collections.ArrayList
    $names = New-Object System.Collections.ArrayList
    $stopAll = $false
    $scannedFiles = 0
    $scannedLines = 0
    foreach ($fileInfo in $filesInfo.files) {
        if ($stopAll) { break }
        $text = Get-PortalEventFileText -Config $Config -Path ([string]$fileInfo.path)
        if ($null -eq $text) { continue }
        $scannedFiles++
        [void]$names.Add([string]$fileInfo.name)
        $lines = @($text -split "`n")
        for ($i = $lines.Count - 1; $i -ge 0; $i--) {
            $line = $lines[$i].Trim()
            if ($line -eq "") { continue }
            $scannedLines++
            $m = $script:PortalEventTsRegex.Match($line)
            if (-not $m.Success) { continue }
            $ts = 0L
            if (-not [long]::TryParse($m.Groups[1].Value, [ref]$ts)) { continue }
            if ($ts -le 0) { continue }
            if ($Cursor -gt 0 -and $ts -ge $Cursor) { continue }
            if ($ToTs -gt 0 -and $ts -gt $ToTs) { continue }
            if ($FromTs -gt 0 -and $ts -lt $FromTs) {
                # 文件内已倒序到 fromTs 之前，更旧的文件/行必然更早 → 全部扫描结束
                $stopAll = $true
                break
            }
            if ($typeNeedle -ne "" -and $line.IndexOf($typeNeedle, [System.StringComparison]::Ordinal) -lt 0) { continue }
            if ($Keyword -ne "" -and $line.IndexOf($Keyword, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
            try { $obj = ConvertFrom-Json -InputObject $line -ErrorAction Stop } catch { continue }
            if ($null -eq $obj) { continue }
            $item = [ordered]@{
                ts     = $ts
                type   = Get-PortalString (Get-PortalProp $obj "type" "system") "system"
                text   = Get-PortalString (Get-PortalProp $obj "text" "") ""
                player = Get-PortalString (Get-PortalProp $obj "player" "") ""
            }
            $playerId = Get-PortalProp $obj "playerId" $null
            if ($null -ne $playerId -and [string]$playerId -ne "") { $item.playerId = [string]$playerId }
            [void]$items.Add($item)
            if ($items.Count -gt $safeLimit) {
                # 多取 1 条用于判断 hasMore；去掉这条不进响应
                [void]$items.RemoveAt($items.Count - 1)
                $data.hasMore = $true
                $stopAll = $true
                break
            }
        }
    }
    $data.items = @($items)
    $data.files = @($names)
    $data.scannedFiles = $scannedFiles
    $data.scannedLines = $scannedLines
    if ($data.hasMore -and $items.Count -gt 0) {
        $data.nextCursor = [long]$items[$items.Count - 1].ts
    }
    return @{ ok = $true; data = $data }
}
