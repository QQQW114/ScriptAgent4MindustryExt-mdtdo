# ============================================================================
# MDT DO 服务器门户 - 审核数据层（提交记录 / 上传暂存 / 目录清单 / 审核动作）
# 兼容 Windows PowerShell 5.1 与 PowerShell 7+。
# ============================================================================

$script:PortalKindInfo = @{
    dp  = @{ dirKey = "dpDirPath"; extKey = "allowedDpExtensions"; label = "DP"; consoleName = "external-cp" }
    map = @{ dirKey = "mapsDirPath"; extKey = "allowedMapExtensions"; label = "地图"; consoleName = "maps" }
}

function Get-PortalKindInfo {
    param([string]$Kind)
    $k = ([string]$Kind).Trim().ToLowerInvariant()
    if ($script:PortalKindInfo.ContainsKey($k)) { return $script:PortalKindInfo[$k] }
    return $null
}

function Get-PortalSha256Hex {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = $sha.ComputeHash($Bytes) } finally { $sha.Dispose() }
    return (($hash | ForEach-Object { $_.ToString("x2") }) -join "")
}

# ---------------------------------------------------------------------------
# 提交记录存储（web-data/submissions.json，单写者=请求线程）
# ---------------------------------------------------------------------------

function New-PortalEmptyStore {
    return @{ schema = 1; items = (New-Object System.Collections.ArrayList) }
}

function Read-PortalStore {
    param([System.Collections.IDictionary]$Config)
    $store = New-PortalEmptyStore
    $path = Get-PortalString $Config.submissionsPath ""
    if ($path -eq "" -or -not (Test-Path -LiteralPath $path)) { return $store }
    try {
        $text = Read-PortalTextFile -Path $path
        if ([string]::IsNullOrWhiteSpace($text)) { return $store }
        $obj = ConvertFrom-Json -InputObject $text -ErrorAction Stop
        if ($null -eq $obj) { return $store }
        $items = $null
        if ($obj.PSObject.Properties.Name -contains "items") { $items = $obj.items }
        $list = New-Object System.Collections.ArrayList
        if ($null -ne $items) {
            if ($items -is [System.Collections.IEnumerable] -and $items -isnot [string]) {
                foreach ($it in $items) {
                    $conv = ConvertTo-PortalData $it
                    if ($conv -is [System.Collections.IDictionary]) { [void]$list.Add($conv) }
                }
            } elseif ($items -is [System.Management.Automation.PSCustomObject]) {
                $conv = ConvertTo-PortalData $items
                if ($conv -is [System.Collections.IDictionary]) { [void]$list.Add($conv) }
            }
        }
        $store.items = $list
        return $store
    } catch {
        Write-PortalLog -Config $Config -Level "WARN" -Message "读取提交记录失败（按空库处理）：$($_.Exception.Message)"
        return $store
    }
}

function Save-PortalStore {
    param([System.Collections.IDictionary]$Config, [System.Collections.IDictionary]$Store)
    $obj = [ordered]@{ schema = 1; items = @($Store.items) }
    $json = ConvertTo-Json -InputObject $obj -Depth 16
    Write-PortalFileAtomic -Path $Config.submissionsPath -Text $json
}

function Get-PortalSubmissionById {
    param([System.Collections.IDictionary]$Store, [string]$Id)
    if ($null -eq $Store -or [string]::IsNullOrWhiteSpace($Id)) { return $null }
    foreach ($item in $Store.items) {
        if ([string]$item.id -eq $Id) { return $item }
    }
    return $null
}

function Get-PortalPendingSubmissions {
    param([System.Collections.IDictionary]$Store, [string]$Kind)
    $out = New-Object System.Collections.ArrayList
    if ($null -eq $Store) { return ,$out }
    foreach ($item in $Store.items) {
        if ([string]$item.status -ne "pending") { continue }
        if ($Kind -ne "" -and [string]$item.kind -ne $Kind) { continue }
        [void]$out.Add($item)
    }
    return ,$out
}

function Get-PortalPendingCount {
    param([System.Collections.IDictionary]$Store, [string]$Kind)
    return (Get-PortalPendingSubmissions -Store $Store -Kind $Kind).Count
}

function New-PortalSubmissionId {
    param([string]$Kind)
    return ("{0}-{1}-{2}" -f $Kind, (Get-Date).ToString("yyyyMMdd-HHmmss"), (New-PortalRandomHex 3))
}

function Save-PortalUploadedPayload {
    # 把上传字节写入隔离暂存目录 web-data/uploads/<id>/<安全文件名>；返回相对 dataDir 的路径。
    param([System.Collections.IDictionary]$Config, [string]$Id, [string]$FileName, [byte[]]$Bytes)
    $leaf = Get-PortalSafeLeafName -RawName $FileName
    if ($leaf -eq "") { throw "非法文件名" }
    $dir = Join-Path $Config.uploadsDirPath $Id
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $full = Join-Path $dir $leaf
    if (-not (Test-PortalPathInside -ChildPath $full -ParentDir $Config.uploadsDirPath)) { throw "非法上传路径" }
    [System.IO.File]::WriteAllBytes($full, $Bytes)
    $rel = "uploads/" + $Id + "/" + $leaf
    return @{ relPath = $rel; fullPath = $full; sha256 = (Get-PortalSha256Hex -Bytes $Bytes); size = $Bytes.Length }
}

function Add-PortalSubmission {
    param([System.Collections.IDictionary]$Config, [string]$Kind, [hashtable]$Fields, [hashtable]$Payload, [string]$ClientIp)
    $store = Read-PortalStore -Config $Config
    $id = New-PortalSubmissionId -Kind $Kind
    $now = Get-PortalNowMillis
    $item = [ordered]@{
        id         = $id
        kind       = $Kind
        title      = Get-PortalString $Fields.title ""
        author     = Get-PortalString $Fields.author ""
        desc       = Get-PortalString $Fields.desc ""
        replaceOf  = Get-PortalString $Fields.replaceOf ""
        fileName   = $Payload.fileName
        storedPath = $Payload.relPath
        size       = [long]$Payload.size
        sha256     = $Payload.sha256
        createdAt  = $now
        createdAtText = Format-PortalMillis $now
        createdIp  = $ClientIp
        status     = "pending"
        reviewedAt = 0
        reviewedBy = ""
        rejectReason = ""
        finalName  = ""
    }
    [void]$store.items.Add($item)
    # 历史元数据有界：pending 全保留，已处理只留最近 historyLimit 条。
    $historyLimit = Get-PortalInt $Config.upload.historyLimit 200 20 5000
    $resolved = @($store.items | Where-Object { [string]$_.status -ne "pending" } | Sort-Object { [long]$_.createdAt })
    if ($resolved.Count -gt $historyLimit) {
        $removeCount = $resolved.Count - $historyLimit
        $removeIds = @{}
        for ($i = 0; $i -lt $removeCount; $i++) { $removeIds[[string]$resolved[$i].id] = $true }
        $kept = New-Object System.Collections.ArrayList
        foreach ($it in $store.items) {
            if ($removeIds.ContainsKey([string]$it.id)) { continue }
            [void]$kept.Add($it)
        }
        $store.items = $kept
    }
    Save-PortalStore -Config $Config -Store $store
    Write-PortalAudit -Config $Config -Action "upload" -Actor "anonymous" -ClientIp $ClientIp -Detail ([ordered]@{
        id = $id; kind = $Kind; fileName = $Payload.fileName; size = $Payload.size; title = $item.title
    })
    return $item
}

# ---------------------------------------------------------------------------
# 目录清单与元数据
# ---------------------------------------------------------------------------

function Read-PortalCatalogMeta {
    param([System.Collections.IDictionary]$Config)
    $meta = [ordered]@{ schema = 1; dp = [ordered]@{}; maps = [ordered]@{} }
    $path = Get-PortalString $Config.catalogMetaPath ""
    if ($path -eq "" -or -not (Test-Path -LiteralPath $path)) { return $meta }
    try {
        $text = Read-PortalTextFile -Path $path
        if ([string]::IsNullOrWhiteSpace($text)) { return $meta }
        $data = ConvertTo-PortalData (ConvertFrom-Json -InputObject $text -ErrorAction Stop)
        if ($data -is [System.Collections.IDictionary]) {
            if ($data.Contains("dp") -and $data.dp -is [System.Collections.IDictionary]) { $meta.dp = $data.dp }
            if ($data.Contains("maps") -and $data.maps -is [System.Collections.IDictionary]) { $meta.maps = $data.maps }
        }
    } catch {
        Write-PortalLog -Config $Config -Level "WARN" -Message "读取 catalog-meta.json 失败（按空处理）：$($_.Exception.Message)"
    }
    return $meta
}

function Save-PortalCatalogMeta {
    param([System.Collections.IDictionary]$Config, [System.Collections.IDictionary]$Meta)
    $json = ConvertTo-Json -InputObject $Meta -Depth 12
    Write-PortalFileAtomic -Path $Config.catalogMetaPath -Text $json
}

function Get-PortalTargetDir {
    param([System.Collections.IDictionary]$Config, [string]$Kind)
    $info = Get-PortalKindInfo -Kind $Kind
    if ($null -eq $info) { return "" }
    return [string]$Config[$info.dirKey]
}

function Get-PortalKindAllowedExtensions {
    param([System.Collections.IDictionary]$Config, [string]$Kind)
    $info = Get-PortalKindInfo -Kind $Kind
    if ($null -eq $info) { return @() }
    return @($Config.upload[$info.extKey])
}

function Get-PortalDirectoryList {
    # 列出目标目录中允许扩展名的文件（dp/maps 通用）。
    param([System.Collections.IDictionary]$Config, [string]$Kind)
    $dir = Get-PortalTargetDir -Config $Config -Kind $Kind
    $allowed = Get-PortalKindAllowedExtensions -Config $Config -Kind $Kind
    $out = New-Object System.Collections.ArrayList
    if ($dir -eq "" -or -not (Test-Path -LiteralPath $dir -PathType Container)) { return ,$out }
    try {
        $files = Get-ChildItem -LiteralPath $dir -File -ErrorAction Stop
    } catch {
        return ,$out
    }
    foreach ($f in $files) {
        if (-not (Test-PortalAllowedExtension -FileName $f.Name -AllowedExtensions $allowed)) { continue }
        if ($f.Name.StartsWith(".portal-tmp")) { continue }
        [void]$out.Add($f)
    }
    return ,$out
}

function Get-PortalCatalog {
    param([System.Collections.IDictionary]$Config)
    $meta = Read-PortalCatalogMeta -Config $Config
    $result = [ordered]@{ dp = @(); maps = @() }
    foreach ($kind in @("dp", "map")) {
        $metaBucket = if ($kind -eq "dp") { $meta.dp } else { $meta.maps }
        $items = New-Object System.Collections.ArrayList
        $files = Get-PortalDirectoryList -Config $Config -Kind $kind
        $sorted = @($files | Sort-Object -Property Name)
        foreach ($f in $sorted) {
            $entry = $null
            if ($metaBucket -is [System.Collections.IDictionary] -and $metaBucket.Contains($f.Name)) { $entry = $metaBucket[$f.Name] }
            $item = [ordered]@{
                name      = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
                file      = $f.Name
                size      = [long]$f.Length
                updatedAt = $f.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
                author    = $null
                desc      = $null
                title     = $null
            }
            if ($entry -is [System.Collections.IDictionary]) {
                if ($entry.Contains("author")) { $item.author = Get-PortalString $entry.author "" }
                if ($entry.Contains("desc")) { $item.desc = Get-PortalString $entry.desc "" }
                if ($entry.Contains("title")) { $item.title = Get-PortalString $entry.title "" }
            }
            [void]$items.Add($item)
        }
        if ($kind -eq "dp") { $result.dp = @($items) } else { $result.maps = @($items) }
    }
    return $result
}

function Get-PortalExistingFileByName {
    # 在目标目录里按 文件名 / 不含扩展名的名字 查找现有文件；返回 FileInfo 或 $null。
    param([System.Collections.IDictionary]$Config, [string]$Kind, [string]$Name)
    $leaf = Get-PortalSafeLeafName -RawName $Name
    if ($leaf -eq "") { return $null }
    $files = Get-PortalDirectoryList -Config $Config -Kind $kind
    foreach ($f in $files) {
        if ([string]::Equals($f.Name, $leaf, [System.StringComparison]::OrdinalIgnoreCase)) { return $f }
    }
    foreach ($f in $files) {
        if ([string]::Equals([System.IO.Path]::GetFileNameWithoutExtension($f.Name), $leaf, [System.StringComparison]::OrdinalIgnoreCase)) { return $f }
    }
    return $null
}

function Get-PortalUniqueFileName {
    param([string]$Dir, [string]$FileName)
    $leaf = Get-PortalSafeLeafName -RawName $FileName
    if ($leaf -eq "") { $leaf = "upload" }
    $final = $leaf
    if (-not (Test-Path -LiteralPath (Join-Path $Dir $final))) { return $final }
    $base = [System.IO.Path]::GetFileNameWithoutExtension($leaf)
    $ext = [System.IO.Path]::GetExtension($leaf)
    for ($i = 2; $i -le 200; $i++) {
        $candidate = "$base ($i)$ext"
        if (-not (Test-Path -LiteralPath (Join-Path $Dir $candidate))) { return $candidate }
    }
    return ("{0}-{1}{2}" -f $base, (New-PortalRandomHex 4), $ext)
}

function Move-PortalFileIntoPlace {
    # 复制到目标目录临时文件，再用 File.Replace（同卷原子替换）或 File.Move 落位。
    param([string]$SourceFile, [string]$TargetDir, [string]$FinalName)
    $finalPath = Join-Path $TargetDir $FinalName
    $tempPath = Join-Path $TargetDir (".portal-tmp-" + (New-PortalRandomHex 6))
    [System.IO.File]::Copy($SourceFile, $tempPath, $true)
    try {
        if (Test-Path -LiteralPath $finalPath) {
            $backup = Join-Path $TargetDir (".portal-bak-" + (New-PortalRandomHex 6))
            try {
                [System.IO.File]::Replace($tempPath, $finalPath, $backup)
                if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue }
            } catch {
                Remove-Item -LiteralPath $finalPath -Force -ErrorAction SilentlyContinue
                [System.IO.File]::Move($tempPath, $finalPath)
            }
        } else {
            [System.IO.File]::Move($tempPath, $finalPath)
        }
    } finally {
        if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue }
    }
    return $finalPath
}

# ---------------------------------------------------------------------------
# 审核动作
# ---------------------------------------------------------------------------

function Resolve-PortalReloadMode {
    # "default" = 用配置默认值；true/false = 调用方显式指定。
    param([System.Collections.IDictionary]$Config, [string]$Kind, [string]$Mode)
    $m = ([string]$Mode).Trim().ToLowerInvariant()
    if ($m -eq "true" -or $m -eq "1") { return $true }
    if ($m -eq "false" -or $m -eq "0") { return $false }
    if ($Kind -eq "dp") { return (Get-PortalBool $Config.reload.dpOnApprove $false) }
    return (Get-PortalBool $Config.reload.mapListOnApprove $true)
}

function Approve-PortalSubmission {
    param(
        [System.Collections.IDictionary]$Config,
        [string]$Id,
        [string]$ReloadMode,
        [string]$Actor,
        [string]$ClientIp
    )
    $store = Read-PortalStore -Config $Config
    $item = Get-PortalSubmissionById -Store $store -Id $Id
    if ($null -eq $item) {
        return @{ ok = $false; code = "NOT_FOUND"; message = "提交不存在" }
    }
    if ([string]$item.status -ne "pending") {
        return @{ ok = $false; code = "ALREADY_REVIEWED"; message = "该提交已处理（$($item.status)）" }
    }
    $kind = [string]$item.kind
    if ($null -eq (Get-PortalKindInfo -Kind $kind)) {
        return @{ ok = $false; code = "BAD_REQUEST"; message = "未知提交类型：$kind" }
    }
    $targetDir = Get-PortalTargetDir -Config $Config -Kind $kind
    if ($targetDir -eq "") {
        return @{ ok = $false; code = "CONFIG_ERROR"; message = "目标目录未配置" }
    }
    if (-not (Test-Path -LiteralPath $targetDir)) {
        try { New-Item -ItemType Directory -Force -Path $targetDir | Out-Null } catch {
            return @{ ok = $false; code = "TARGET_DIR_ERROR"; message = "目标目录不存在且无法创建：$targetDir" }
        }
    }
    $storedRel = Get-PortalString $item.storedPath ""
    $sourceFile = Resolve-PortalPath -BaseDir $Config.dataDirPath -Path $storedRel
    if (-not (Test-PortalPathInside -ChildPath $sourceFile -ParentDir $Config.dataDirPath) -or
        -not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
        return @{ ok = $false; code = "PAYLOAD_MISSING"; message = "上传文件已丢失，无法通过审核" }
    }
    $originalLeaf = Get-PortalSafeLeafName -RawName (Get-PortalString $item.fileName "")
    if ($originalLeaf -eq "") { return @{ ok = $false; code = "BAD_REQUEST"; message = "记录中的文件名非法" } }
    $originalExt = Get-PortalFileExtension -FileName $originalLeaf

    $replaceOf = Get-PortalString $item.replaceOf ""
    $existingReplaceTarget = $null
    if ($replaceOf -ne "") {
        $existingReplaceTarget = Get-PortalExistingFileByName -Config $Config -Kind $kind -Name $replaceOf
    }
    $finalName = ""
    $replacedName = ""
    $removeOldName = ""
    if ($null -ne $existingReplaceTarget) {
        $oldExt = Get-PortalFileExtension -FileName $existingReplaceTarget.Name
        if ($oldExt -eq $originalExt) {
            $finalName = $existingReplaceTarget.Name       # 同名同格式：原地替换，保持加载名稳定
            $replacedName = $existingReplaceTarget.Name
        } else {
            $finalName = Get-PortalUniqueFileName -Dir $targetDir -FileName $originalLeaf
            $removeOldName = $existingReplaceTarget.Name    # 格式变化无法同名替换：先在新名落位，再删旧文件
            $replacedName = $existingReplaceTarget.Name
        }
    } else {
        $finalName = Get-PortalUniqueFileName -Dir $targetDir -FileName $originalLeaf
    }

    $finalPath = $null
    try {
        $finalPath = Move-PortalFileIntoPlace -SourceFile $sourceFile -TargetDir $targetDir -FinalName $finalName
    } catch {
        return @{ ok = $false; code = "MOVE_FAILED"; message = "文件落位失败：$($_.Exception.Message)" }
    }
    if ($removeOldName -ne "" -and $removeOldName -ne $finalName) {
        $oldPath = Join-Path $targetDir $removeOldName
        try {
            if (Test-PortalPathInside -ChildPath $oldPath -ParentDir $targetDir -and (Test-Path -LiteralPath $oldPath)) {
                Remove-Item -LiteralPath $oldPath -Force
            }
        } catch {
            Write-PortalLog -Config $Config -Level "WARN" -Message "审核替换后删除旧文件失败：$oldPath ($($_.Exception.Message))"
        }
    }

    $now = Get-PortalNowMillis
    $item.status = "approved"
    $item.reviewedAt = $now
    $item.reviewedBy = $Actor
    $item.finalName = $finalName
    $item.rejectReason = ""
    $cleaned = New-Object System.Collections.ArrayList
    foreach ($it in $store.items) { [void]$cleaned.Add($it) }
    $store.items = $cleaned
    Save-PortalStore -Config $Config -Store $store

    # 记录作者/介绍等元数据，供 /api/catalog 展示（历史文件没有元数据时显示 null）。
    try {
        $meta = Read-PortalCatalogMeta -Config $Config
        $bucket = if ($kind -eq "dp") { $meta.dp } else { $meta.maps }
        $bucket[$finalName] = [ordered]@{
            title        = Get-PortalString $item.title ""
            author       = Get-PortalString $item.author ""
            desc         = Get-PortalString $item.desc ""
            submissionId = [string]$item.id
            approvedAt   = $now
            size         = [long]$item.size
        }
        Save-PortalCatalogMeta -Config $Config -Meta $meta
    } catch {
        Write-PortalLog -Config $Config -Level "WARN" -Message "写入 catalog-meta 失败：$($_.Exception.Message)"
    }

    # 清理暂存文件（落位用的是复制，源文件仍在隔离目录）。
    try {
        $tmpDir = [System.IO.Path]::GetDirectoryName($sourceFile)
        if ($tmpDir -and (Test-PortalPathInside -ChildPath $tmpDir -ParentDir $Config.uploadsDirPath)) {
            Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    } catch { }

    $wantReload = Resolve-PortalReloadMode -Config $Config -Kind $kind -Mode $ReloadMode
    $reload = @{ dispatched = $false; requested = $wantReload; note = "" }
    $warnings = New-Object System.Collections.ArrayList
    if ($wantReload) {
        $cmd = ""
        if ($kind -eq "dp") {
            $cmd = 'externalcp load "' + $finalName + '"'
        } else {
            $cmd = "maps"
        }
        $dispatch = Invoke-PortalConsoleCommand -Config $Config -Command $cmd
        $reload = @{ dispatched = [bool]$dispatch.dispatched; requested = $true; command = $cmd; note = [string]$dispatch.reason }
        if (-not $dispatch.dispatched) {
            # 控制台桥是可选的：未开启/不可达不是故障，审核仍然成功，只给中性提示（INFO 一条，限频）。
            if ($kind -eq "dp") {
                $reloadHintText = "游戏控制台未开启：DP 文件已就位，游戏服重启后生效（或开启控制台后重新热重载）"
            } else {
                $reloadHintText = "游戏控制台未开启：地图文件已就位，游戏服重启后出现在地图列表（或开启控制台后手动刷新地图）"
            }
            [void]$warnings.Add($reloadHintText)
            Write-PortalConsoleInfo -Config $Config -Message ("审核通过后可选热重载未执行（控制台为可选依赖，不影响审核结果）：{0} / {1}" -f $cmd, [string]$dispatch.reason)
        }
    }

    Write-PortalAudit -Config $Config -Action "approve" -Actor $Actor -ClientIp $ClientIp -Detail ([ordered]@{
        id = $item.id; kind = $kind; finalName = $finalName; replaced = $replacedName; reload = $reload; warnings = @($warnings)
    })
    return @{ ok = $true; data = [ordered]@{
        id = [string]$item.id
        kind = $kind
        status = "approved"
        finalName = $finalName
        replaced = $replacedName
        reload = $reload
        reloadHint = $(if ($warnings.Count -gt 0) { [string]$warnings[0] } else { $null })
        warnings = @($warnings)
    } }
}

function Reject-PortalSubmission {
    param(
        [System.Collections.IDictionary]$Config,
        [string]$Id,
        [string]$Reason,
        [string]$Actor,
        [string]$ClientIp
    )
    $store = Read-PortalStore -Config $Config
    $item = Get-PortalSubmissionById -Store $store -Id $Id
    if ($null -eq $item) { return @{ ok = $false; code = "NOT_FOUND"; message = "提交不存在" } }
    if ([string]$item.status -ne "pending") {
        return @{ ok = $false; code = "ALREADY_REVIEWED"; message = "该提交已处理（$($item.status)）" }
    }
    $reason = ([string]$Reason).Trim()
    if ($reason -eq "") { return @{ ok = $false; code = "BAD_REQUEST"; message = "拒绝原因不能为空" } }
    if ($reason.Length -gt 500) { $reason = $reason.Substring(0, 500) }
    $item.status = "rejected"
    $item.reviewedAt = Get-PortalNowMillis
    $item.reviewedBy = $Actor
    $item.rejectReason = $reason
    Save-PortalStore -Config $Config -Store $store

    if (Get-PortalBool $Config.upload.deleteRejectedPayloads $true) {
        try {
            $storedRel = Get-PortalString $item.storedPath ""
            $payloadDir = [System.IO.Path]::GetDirectoryName((Resolve-PortalPath -BaseDir $Config.dataDirPath -Path $storedRel))
            if ($payloadDir -and (Test-PortalPathInside -ChildPath $payloadDir -ParentDir $Config.uploadsDirPath)) {
                Remove-Item -LiteralPath $payloadDir -Recurse -Force -ErrorAction SilentlyContinue
            }
        } catch { }
    }
    Write-PortalAudit -Config $Config -Action "reject" -Actor $Actor -ClientIp $ClientIp -Detail ([ordered]@{
        id = $item.id; kind = $item.kind; fileName = $item.fileName; reason = $reason
    })
    return @{ ok = $true; data = [ordered]@{ id = [string]$item.id; status = "rejected"; reason = $reason } }
}

function Remove-PortalItem {
    param(
        [System.Collections.IDictionary]$Config,
        [string]$Kind,
        [string]$Name,
        [bool]$Unload,
        [string]$Actor,
        [string]$ClientIp
    )
    if ($null -eq (Get-PortalKindInfo -Kind $Kind)) {
        return @{ ok = $false; code = "BAD_REQUEST"; message = "未知类型：$Kind（只支持 dp/map）" }
    }
    $leaf = Get-PortalSafeLeafName -RawName $Name
    if ($leaf -eq "") { return @{ ok = $false; code = "BAD_REQUEST"; message = "非法名称" } }
    $targetDir = Get-PortalTargetDir -Config $Config -Kind $Kind
    $existing = Get-PortalExistingFileByName -Config $Config -Kind $Kind -Name $leaf
    if ($null -eq $existing) {
        return @{ ok = $false; code = "NOT_FOUND"; message = "文件不存在：$leaf" }
    }
    $fullPath = Join-Path $targetDir $existing.Name
    if (-not (Test-PortalPathInside -ChildPath $fullPath -ParentDir $targetDir)) {
        return @{ ok = $false; code = "BAD_REQUEST"; message = "路径越界" }
    }
    try {
        Remove-Item -LiteralPath $fullPath -Force
    } catch {
        return @{ ok = $false; code = "DELETE_FAILED"; message = "删除失败：$($_.Exception.Message)" }
    }
    # 同步移除 catalog 元数据
    try {
        $meta = Read-PortalCatalogMeta -Config $Config
        $bucket = if ($Kind -eq "dp") { $meta.dp } else { $meta.maps }
        if ($bucket.Contains($existing.Name)) { $bucket.Remove($existing.Name) }
        Save-PortalCatalogMeta -Config $Config -Meta $meta
    } catch { }

    # 可选：游戏侧热卸载 / 刷新地图列表（失败只记录 + 返回 warnings，不影响删除结果）。
    $reload = @{ dispatched = $false; requested = $false; note = "" }
    $warnings = New-Object System.Collections.ArrayList
    $wantUnload = $false
    if ($Kind -eq "dp") {
        $wantUnload = $Unload -or (Get-PortalBool $Config.reload.unloadOnRemove $false)
        if ($wantUnload) {
            $cmd = 'externalcp unload "' + $existing.Name + '"'
            $dispatch = Invoke-PortalConsoleCommand -Config $Config -Command $cmd
            $reload = @{ dispatched = [bool]$dispatch.dispatched; requested = $true; command = $cmd; note = [string]$dispatch.reason }
            if (-not $dispatch.dispatched) {
                [void]$warnings.Add("游戏控制台未开启：DP 文件已删除；若当前局已加载该 DP，换图/重启后彻底生效（可选开启控制台后自动卸载）")
                Write-PortalConsoleInfo -Config $Config -Message ("删除 DP 后可选卸载未执行（控制台为可选依赖，不影响删除结果）：{0} / {1}" -f $cmd, [string]$dispatch.reason)
            }
        }
    } else {
        $wantReloadMaps = Get-PortalBool $Config.reload.mapListOnRemove $true
        if ($wantReloadMaps) {
            $dispatch = Invoke-PortalConsoleCommand -Config $Config -Command "maps"
            $reload = @{ dispatched = [bool]$dispatch.dispatched; requested = $true; command = "maps"; note = [string]$dispatch.reason }
            if (-not $dispatch.dispatched) {
                [void]$warnings.Add("游戏控制台未开启：地图文件已删除，游戏服重启后列表更新（或游戏内 /maps 手动刷新）")
                Write-PortalConsoleInfo -Config $Config -Message ("删除地图后可选列表刷新未执行（控制台为可选依赖，不影响删除结果）：{0}" -f [string]$dispatch.reason)
            }
        }
    }
    Write-PortalAudit -Config $Config -Action "remove" -Actor $Actor -ClientIp $ClientIp -Detail ([ordered]@{
        kind = $Kind; file = $existing.Name; reload = $reload; warnings = @($warnings)
    })
    return @{ ok = $true; data = [ordered]@{
        kind = $Kind
        name = [System.IO.Path]::GetFileNameWithoutExtension($existing.Name)
        file = $existing.Name
        deleted = $true
        reload = $reload
        reloadHint = $(if ($warnings.Count -gt 0) { [string]$warnings[0] } else { $null })
        warnings = @($warnings)
    } }
}

function Get-PortalReviewList {
    param([System.Collections.IDictionary]$Config, [string]$Kind)
    if ($null -eq (Get-PortalKindInfo -Kind $Kind)) {
        return @{ ok = $false; code = "BAD_REQUEST"; message = "kind 只支持 dp/map" }
    }
    $store = Read-PortalStore -Config $Config
    $pending = Get-PortalPendingSubmissions -Store $store -Kind $Kind
    $items = New-Object System.Collections.ArrayList
    foreach ($it in @($pending | Sort-Object { [long]$_.createdAt } -Descending)) {
        [void]$items.Add([ordered]@{
            id = [string]$it.id
            kind = [string]$it.kind
            title = Get-PortalString $it.title ""
            author = Get-PortalString $it.author ""
            desc = Get-PortalString $it.desc ""
            replaceOf = Get-PortalString $it.replaceOf ""
            fileName = Get-PortalString $it.fileName ""
            size = [long]$it.size
            createdAt = [long]$it.createdAt
            createdAtText = Get-PortalString $it.createdAtText ""
            status = Get-PortalString $it.status ""
        })
    }
    $history = New-Object System.Collections.ArrayList
    $resolved = @($store.items | Where-Object { [string]$_.status -ne "pending" -and [string]$_.kind -eq $Kind } |
        Sort-Object { [long]$_.reviewedAt } -Descending)
    $maxHistory = 20
    foreach ($it in ($resolved | Select-Object -First $maxHistory)) {
        [void]$history.Add([ordered]@{
            id = [string]$it.id
            kind = [string]$it.kind
            title = Get-PortalString $it.title ""
            author = Get-PortalString $it.author ""
            fileName = Get-PortalString $it.fileName ""
            status = Get-PortalString $it.status ""
            reviewedAt = [long]$it.reviewedAt
            reviewedAtText = Format-PortalMillis ([long]$it.reviewedAt)
            rejectReason = Get-PortalString $it.rejectReason ""
            finalName = Get-PortalString $it.finalName ""
        })
    }
    $limit = Get-PortalInt $Config.upload.queueLimitPerKind 10 1 100
    return @{ ok = $true; data = [ordered]@{
        kind = $Kind
        items = @($items)
        history = @($history)
        queue = [ordered]@{ limit = $limit; pending = $items.Count }
    } }
}

function Get-PortalQueueState {
    param([System.Collections.IDictionary]$Config, [string]$Kind)
    $store = Read-PortalStore -Config $Config
    $limit = Get-PortalInt $Config.upload.queueLimitPerKind 10 1 100
    return @{ limit = $limit; pending = (Get-PortalPendingCount -Store $store -Kind $Kind) }
}
