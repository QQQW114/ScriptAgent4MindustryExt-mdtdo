# ============================================================================
# MDT DO 标准化 Agent / 协作者开发调试与验收工具（Windows PowerShell 5.1 / 7+）
# 兼容两种目录布局：
#   1) 主工作区 server/mdtdo/（自动定位 ../mdtserver 与 ../mdtserver/config/scripts）
#   2) 开源插件仓 ScriptAgent4MindustryExt-mdtdo/（脚本在 ../scripts；测试服路径通过
#      -ServerDir / 环境变量 MDT_SERVER_DIR / tools/dev-local.json 指定）
# 本文件必须保持 UTF-8 with BOM，禁止使用全角弯引号。
# ============================================================================
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("help", "check", "start", "stop", "status", "cmd", "reload", "verify", "logs", "smoke-web", "smoke-email")]
    [string]$Action = "help",

    [Parameter(Position = 1)]
    [string]$Target = "",

    [string]$Command = "",
    [string[]]$Scripts = @(),
    [ValidateSet("cold", "hot")]
    [string]$Mode = "cold",
    [string]$ServerDir = "",
    [switch]$ClearCache,
    [switch]$SyncToServer,
    [switch]$ForceRestart,
    [switch]$KeepRunning,
    [switch]$Scan,
    [switch]$SkipDocLint,
    [int]$Timeout = 120,
    [string]$SocketHost = "",
    [int]$SocketPort = 0,
    [int]$GamePort = 0,
    [int]$WaitMs = 2500,
    [int]$IdleMs = 600,
    [ValidateSet("console", "game", "script", "error")]
    [string]$Type = "console",
    [int]$Tail = 80,
    [int]$Port = 0
)

$ErrorActionPreference = "Stop"

try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch { }

$ToolDir = $PSScriptRoot
$RepoRoot = (Resolve-Path (Join-Path $ToolDir "..")).Path
$PidFilePath = Join-Path $ToolDir ".dev-server.pid"
$LocalConfigPath = Join-Path $ToolDir "dev-local.json"

# ---------- 布局识别与配置读取 ----------
if (Test-Path -LiteralPath (Join-Path $RepoRoot "mdtserver\config\scripts")) {
    $RepoLayout = "mdtdo-workspace"
    $ScriptsSourceDir = (Resolve-Path (Join-Path $RepoRoot "mdtserver\config\scripts")).Path
    $DefaultServerDir = (Resolve-Path (Join-Path $RepoRoot "mdtserver")).Path
} elseif (Test-Path -LiteralPath (Join-Path $RepoRoot "scripts")) {
    $RepoLayout = "plugin-repo"
    $ScriptsSourceDir = (Resolve-Path (Join-Path $RepoRoot "scripts")).Path
    $DefaultServerDir = ""
} else {
    throw "未识别到脚本目录（期望 ../mdtserver/config/scripts 或 ../scripts），当前根路径: $RepoRoot"
}

$LocalCfg = $null
if (Test-Path -LiteralPath $LocalConfigPath) {
    try {
        $LocalCfg = Get-Content -LiteralPath $LocalConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Write-Warning "读取 tools/dev-local.json 失败：$($_.Exception.Message)"
    }
}

function Get-EffectiveServerDir {
    if (-not [string]::IsNullOrWhiteSpace($ServerDir)) { return (Resolve-Path -LiteralPath $ServerDir).Path }
    if (-not [string]::IsNullOrWhiteSpace($env:MDT_SERVER_DIR)) { return (Resolve-Path -LiteralPath $env:MDT_SERVER_DIR).Path }
    if ($null -ne $LocalCfg -and -not [string]::IsNullOrWhiteSpace([string]$LocalCfg.serverDir)) {
        return (Resolve-Path -LiteralPath ([string]$LocalCfg.serverDir)).Path
    }
    if (-not [string]::IsNullOrWhiteSpace($DefaultServerDir)) { return $DefaultServerDir }
    throw "未指定本机测试服目录。请复制 tools/dev-local.example.json 为 tools/dev-local.json 并设置 serverDir，或传入 -ServerDir <路径>。"
}

if ([string]::IsNullOrWhiteSpace($SocketHost)) {
    $SocketHost = if ($null -ne $LocalCfg -and -not [string]::IsNullOrWhiteSpace([string]$LocalCfg.socketHost)) { [string]$LocalCfg.socketHost } else { "127.0.0.1" }
}
if ($SocketPort -le 0) {
    $SocketPort = if ($null -ne $LocalCfg -and [int]$LocalCfg.socketPort -gt 0) { [int]$LocalCfg.socketPort } else { 6859 }
}
if ($GamePort -le 0) {
    $GamePort = if ($null -ne $LocalCfg -and [int]$LocalCfg.gamePort -gt 0) { [int]$LocalCfg.gamePort } else { 6567 }
}

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# ---------- 通用工具函数 ----------
function Test-TcpPortOpen {
    param([string]$HostName, [int]$PortNum, [int]$TimeoutMs = 400)
    $client = $null
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $ar = $client.BeginConnect($HostName, $PortNum, $null, $null)
        $ok = $ar.AsyncWaitHandle.WaitOne($TimeoutMs)
        if (-not $ok) { return $false }
        $client.EndConnect($ar)
        return $true
    } catch {
        return $false
    } finally {
        if ($null -ne $client) { try { $client.Close() } catch { } }
    }
}

function Test-UdpOrTcpPortListening {
    param([int]$PortNum)
    try {
        $lines = (& netstat -ano 2>$null)
        foreach ($line in $lines) {
            if ($line -match "[:\]]${PortNum}\s+" -and ($line -match "\bLISTENING\b" -or $line -match "^\s*UDP\b")) {
                return $true
            }
        }
    } catch { }
    return $false
}

function Invoke-ConsoleSocketCommand {
    param(
        [Parameter(Mandatory = $true)][string]$CmdText,
        [string]$HostName = $SocketHost,
        [int]$PortNum = $SocketPort,
        [int]$MaxWaitMs = $WaitMs,
        [int]$IdleTimeoutMs = $IdleMs
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.NoDelay = $true
        $ar = $client.BeginConnect($HostName, $PortNum, $null, $null)
        if (-not $ar.AsyncWaitHandle.WaitOne(3000)) {
            throw "连接控制台 Socket 超时（${HostName}:${PortNum}）"
        }
        $client.EndConnect($ar)
        $stream = $client.GetStream()
        $bytes = $Utf8NoBom.GetBytes($CmdText + "`n")
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()

        $buf = New-Object byte[] 8192
        $sb = New-Object System.Text.StringBuilder
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $lastDataMs = -1L

        while ($sw.ElapsedMilliseconds -lt $MaxWaitMs) {
            $gotData = $false
            while ($stream.DataAvailable) {
                $n = $stream.Read($buf, 0, $buf.Length)
                if ($n -le 0) { break }
                [void]$sb.Append($Utf8NoBom.GetString($buf, 0, $n))
                $lastDataMs = $sw.ElapsedMilliseconds
                $gotData = $true
            }
            if ($lastDataMs -ge 0 -and ($sw.ElapsedMilliseconds - $lastDataMs) -ge $IdleTimeoutMs) {
                break
            }
            if (-not $gotData) {
                Start-Sleep -Milliseconds 40
            }
        }

        return $sb.ToString().TrimEnd("`r", "`n")
    } finally {
        try { $client.Close() } catch { }
    }
}

function Get-CleanTextNoAnsi {
    param([string]$Text)
    if ($null -eq $Text) { return "" }
    $noAnsi = [regex]::Replace($Text, '\x1B\[[0-?]*[ -/]*[@-~]', '')
    return [regex]::Replace($noAnsi, '\[[a-zA-Z0-9_#]+\]', '')
}

function Sync-RepoScriptsToServer {
    param([string]$TargetServerDir)
    $dstScripts = Join-Path $TargetServerDir "config\scripts"
    if ([string]::Equals($ScriptsSourceDir.TrimEnd('\'), $dstScripts.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)) {
        return
    }
    if (-not (Test-Path -LiteralPath $dstScripts)) {
        throw "测试服脚本目录不存在: $dstScripts"
    }
    Write-Host "[Sync] 镜像脚本 $ScriptsSourceDir -> $dstScripts（保护 data / external-cp / cache）" -ForegroundColor Cyan
    & robocopy $ScriptsSourceDir $dstScripts /MIR /XD cache data external-cp /XF .gitkeep /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) {
        throw "robocopy 同步脚本失败（退出码 $LASTEXITCODE）"
    }
}

function Read-PidMeta {
    if (-not (Test-Path -LiteralPath $PidFilePath)) { return $null }
    try {
        return (Get-Content -LiteralPath $PidFilePath -Raw -Encoding UTF8 | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Get-ServerManagedProcesses {
    param([string]$TargetServerDir)
    $result = New-Object System.Collections.Generic.List[object]
    $seen = New-Object System.Collections.Generic.HashSet[int]

    $meta = Read-PidMeta
    if ($null -ne $meta) {
        foreach ($pidVal in @($meta.javaPid, $meta.wrapperPid)) {
            if ($pidVal -and [int]$pidVal -gt 0 -and $seen.Add([int]$pidVal)) {
                $p = Get-Process -Id ([int]$pidVal) -ErrorAction SilentlyContinue
                if ($null -ne $p) {
                    $result.Add([pscustomobject]@{ Id = $p.Id; Name = $p.ProcessName; Source = "pidfile" })
                }
            }
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($TargetServerDir)) {
        $escaped = [regex]::Escape($TargetServerDir)
        try {
            $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
                ($_.Name -ieq "java.exe" -or $_.Name -ieq "powershell.exe" -or $_.Name -ieq "pwsh.exe") -and
                $_.CommandLine -match $escaped -and
                $_.ProcessId -ne $PID -and
                ($_.CommandLine -match "server-.*\.jar" -or $_.CommandLine -match "start-server\.ps1")
            }
            foreach ($proc in $procs) {
                if ($seen.Add([int]$proc.ProcessId)) {
                    $result.Add([pscustomobject]@{ Id = [int]$proc.ProcessId; Name = $proc.Name; Source = "cmdline" })
                }
            }
        } catch { }
    }
    return ,$result.ToArray()
}

function Stop-DevServerInternal {
    param([string]$TargetServerDir, [int]$WaitSec = 20)
    $procs = Get-ServerManagedProcesses -TargetServerDir $TargetServerDir
    if ($procs.Count -eq 0) {
        if (Test-Path -LiteralPath $PidFilePath) { Remove-Item -LiteralPath $PidFilePath -Force -ErrorAction SilentlyContinue }
        Write-Host "[Stop] 未发现运行中的本工作区开发服进程。" -ForegroundColor DarkGray
        return $true
    }

    # 先停 wrapper（避免任何潜在重启），再停 java
    $ordered = @($procs | Sort-Object { if ($_.Name -like "java*") { 1 } else { 0 } })
    foreach ($p in $ordered) {
        Write-Host ("[Stop] 结束进程 PID={0} ({1}, {2})" -f $p.Id, $p.Name, $p.Source) -ForegroundColor Yellow
        try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch { }
    }

    if (Test-Path -LiteralPath $PidFilePath) {
        Remove-Item -LiteralPath $PidFilePath -Force -ErrorAction SilentlyContinue
    }

    $deadline = (Get-Date).AddSeconds($WaitSec)
    while ((Get-Date) -lt $deadline) {
        $busy6567 = Test-UdpOrTcpPortListening -PortNum $GamePort
        $busy6859 = Test-TcpPortOpen -HostName $SocketHost -PortNum $SocketPort -TimeoutMs 200
        if (-not $busy6567 -and -not $busy6859) {
            Write-Host ("[Stop] 开发服已停止，端口 {0}/{1} 已释放。" -f $GamePort, $SocketPort) -ForegroundColor Green
            return $true
        }
        Start-Sleep -Milliseconds 300
    }
    Write-Warning ("端口 {0}/{1} 在 {2}s 后仍未完全释放，请手工检查。" -f $GamePort, $SocketPort, $WaitSec)
    return $false
}

# ---------- 1. 静态体检（check） ----------
function Invoke-StaticChecks {
    $errors = New-Object System.Collections.Generic.List[string]
    $warnings = New-Object System.Collections.Generic.List[string]

    Write-Host ("=== MDT DO 静态体检（布局: {0}）===" -f $RepoLayout) -ForegroundColor Cyan

    $scriptFiles = @(Get-ChildItem -LiteralPath $ScriptsSourceDir -Recurse -File | Where-Object {
        ($_.Extension -ieq ".kts" -or $_.Extension -ieq ".kt") -and
        $_.FullName -notmatch '\\(cache|data|external-cp)\\'
    })

    # [1/7] KDoc 嵌套块注释扫描
    $kdocHits = New-Object System.Collections.Generic.List[string]
    foreach ($f in $scriptFiles) {
        $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
        $line = 1; $inBlock = $false; $inLine = $false; $inStr = $false; $inChar = $false; $i = 0
        while ($i -lt $text.Length) {
            $c = $text[$i]
            $next = if ($i + 1 -lt $text.Length) { $text[$i + 1] } else { [char]0 }
            if ($c -eq "`n") { $line++; $inLine = $false; $i++; continue }
            if ($inLine) { $i++; continue }
            if ($inStr) { if ($c -eq '\') { $i += 2; continue }; if ($c -eq '"') { $inStr = $false }; $i++; continue }
            if ($inChar) { if ($c -eq '\') { $i += 2; continue }; if ($c -eq "'") { $inChar = $false }; $i++; continue }
            if ($inBlock) {
                if ($c -eq '/' -and $next -eq '*') {
                    $rel = $f.FullName.Substring($ScriptsSourceDir.Length + 1).Replace('\', '/')
                    $kdocHits.Add("${rel}:${line} 块注释内出现 '/*'")
                }
                if ($c -eq '*' -and $next -eq '/') { $inBlock = $false; $i += 2; continue }
                $i++; continue
            }
            if ($c -eq '/' -and $next -eq '/') { $inLine = $true; $i += 2; continue }
            if ($c -eq '/' -and $next -eq '*') { $inBlock = $true; $i += 2; continue }
            if ($c -eq '"') { $inStr = $true; $i++; continue }
            if ($c -eq "'") { $inChar = $true; $i++; continue }
            $i++
        }
    }
    if ($kdocHits.Count -eq 0) {
        Write-Host ("[1/7] KDoc 嵌套块注释扫描：PASS（{0} 文件，0 命中）" -f $scriptFiles.Count) -ForegroundColor Green
    } else {
        Write-Host ("[1/7] KDoc 嵌套块注释扫描：FAIL（{0} 处命中）" -f $kdocHits.Count) -ForegroundColor Red
        foreach ($h in $kdocHits) { $errors.Add("KDoc 嵌套注释: $h") }
    }

    # [2/7] SA 事件监听 listen<T> 误用扫描
    $listenAllow = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($allowed in @("TextInputEvent", "WorldLoadBeginEvent", "ResetEvent", "Any")) { [void]$listenAllow.Add($allowed) }
    $listenHits = New-Object System.Collections.Generic.List[string]
    foreach ($f in $scriptFiles) {
        $rel = $f.FullName.Substring($ScriptsSourceDir.Length + 1).Replace('\', '/')
        $lines = [System.IO.File]::ReadAllLines($f.FullName, [System.Text.Encoding]::UTF8)
        for ($ln = 0; $ln -lt $lines.Count; $ln++) {
            $raw = $lines[$ln]
            $trimmed = $raw.TrimStart()
            if ($trimmed.StartsWith("//") -or $trimmed.StartsWith("*")) { continue }
            foreach ($m in [regex]::Matches($raw, '\blisten\s*<\s*([A-Za-z0-9_.]+)\s*>')) {
                $tName = $m.Groups[1].Value
                if (-not $tName.StartsWith("EventType.") -and -not $listenAllow.Contains($tName)) {
                    $listenHits.Add("${rel}:$($ln+1) 非原生 EventType 使用了 listen<${tName}>（自定义事件必须用 listenTo<T>）")
                }
            }
        }
    }
    if ($listenHits.Count -eq 0) {
        Write-Host "[2/7] SA 事件监听 listen<T> 扫描：PASS（0 误用）" -ForegroundColor Green
    } else {
        Write-Host ("[2/7] SA 事件监听 listen<T> 扫描：FAIL（{0} 处疑似误用）" -f $listenHits.Count) -ForegroundColor Red
        foreach ($h in $listenHits) { $errors.Add("listen<T> 误用: $h") }
    }

    # [3/7] contextScript<T>() <-> @file:Depends 静态一致性扫描（防止 PR #14 类漏依赖编译事故）
    $ktsFiles = @($scriptFiles | Where-Object { $_.Extension -ieq ".kts" })
    $classToScriptIds = @{}
    $directDepends = @{}
    $fileTextCache = @{}

    foreach ($f in $ktsFiles) {
        $relNoExt = $f.FullName.Substring($ScriptsSourceDir.Length + 1).Replace('\', '/')
        $scriptId = $relNoExt.Substring(0, $relNoExt.Length - 4)
        $baseName = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
        # ScriptAgent 将文件名转为类名：按非字母数字分段并首字母大写（或直接首字母大写）
        $parts = @($baseName -split '[^A-Za-z0-9]+' | Where-Object { $_ -ne '' })
        $clsName = ($parts | ForEach-Object { $_.Substring(0,1).ToUpperInvariant() + $_.Substring(1) }) -join ''
        if (-not $classToScriptIds.ContainsKey($clsName)) {
            $classToScriptIds[$clsName] = New-Object System.Collections.Generic.List[string]
        }
        $classToScriptIds[$clsName].Add($scriptId)
        $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
        $fileTextCache[$scriptId] = $txt

        $deps = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
        foreach ($dm in [regex]::Matches($txt, '@file:Depends\(\s*"([^"]+)"')) {
            [void]$deps.Add($dm.Groups[1].Value.Trim('/'))
        }
        # 同模块 module.kts 默认隐式依赖
        $topMod = $scriptId.Split('/')[0]
        if ($scriptId -ne "$topMod/module") { [void]$deps.Add("$topMod/module") }
        $directDepends[$scriptId] = $deps
    }

    $depMissHits = New-Object System.Collections.Generic.List[string]
    foreach ($scriptId in ($fileTextCache.Keys | Sort-Object)) {
        $txt = $fileTextCache[$scriptId]
        $ctxMatches = [regex]::Matches($txt, '\bcontextScript\s*<\s*([A-Za-z0-9_.]+)\s*>')
        if ($ctxMatches.Count -eq 0) { continue }

        # 计算所有传递依赖（含模块内 lib/module 默认可见关系）
        $reachable = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
        $queue = New-Object System.Collections.Generic.Queue[string]
        [void]$reachable.Add($scriptId)
        foreach ($d in $directDepends[$scriptId]) {
            if ($reachable.Add($d)) { $queue.Enqueue($d) }
        }
        while ($queue.Count -gt 0) {
            $cur = $queue.Dequeue()
            if ($directDepends.ContainsKey($cur)) {
                foreach ($nx in $directDepends[$cur]) {
                    if ($reachable.Add($nx)) { $queue.Enqueue($nx) }
                }
            }
            # 如果依赖了某个模块名（如 coreLibrary / coreMindustry / wayzer），也把该模块 module 节点加入
            if ($directDepends.ContainsKey("$cur/module") -and $reachable.Add("$cur/module")) {
                $queue.Enqueue("$cur/module")
            }
        }

        $checkedClasses = New-Object System.Collections.Generic.HashSet[string]
        foreach ($cm in $ctxMatches) {
            $rawType = $cm.Groups[1].Value
            $simpleType = $rawType.Split('.')[-1]
            if (-not $checkedClasses.Add($simpleType)) { continue }
            if (-not $classToScriptIds.ContainsKey($simpleType)) { continue }

            $candidates = $classToScriptIds[$simpleType]
            $matched = $false
            foreach ($cand in $candidates) {
                if ($reachable.Contains($cand)) { $matched = $true; break }
                # 允许对模块本身（例如 contextScript<Module>() 或依赖了整个子目录前缀）
                if ($cand.EndsWith("/module") -and $reachable.Contains($cand.Substring(0, $cand.Length - 7))) { $matched = $true; break }
            }
            if (-not $matched) {
                $depMissHits.Add("${scriptId}.kts 使用 contextScript<${rawType}>() 但未通过 @file:Depends 声明依赖（候选: $($candidates -join ', ')）")
            }
        }
    }
    if ($depMissHits.Count -eq 0) {
        Write-Host "[3/7] contextScript/@file:Depends 依赖扫描：PASS（0 遗漏）" -ForegroundColor Green
    } else {
        Write-Host ("[3/7] contextScript/@file:Depends 依赖扫描：FAIL（{0} 处缺失）" -f $depMissHits.Count) -ForegroundColor Red
        foreach ($h in $depMissHits) { $errors.Add("缺失 @file:Depends: $h") }
    }

    # [4/7] PowerShell UTF-8 BOM 与 .bat/.cmd ASCII+CRLF 编码检查
    $bomMiss = New-Object System.Collections.Generic.List[string]
    $batIssues = New-Object System.Collections.Generic.List[string]
    $skipDirRegex = '^\\(\.git|\.tmp-160test|ScriptAgent4MindustryExt-mdtdo|logs|cache|data|web-data|backups|sa-cache-backups|\.scratch|external-cp)\\|\\(\.git|logs|cache|data|web-data|backups|sa-cache-backups|\.scratch|external-cp)\\'
    $allWinScripts = @(Get-ChildItem -LiteralPath $RepoRoot -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
        ($_.Extension -ieq ".ps1" -or $_.Extension -ieq ".bat" -or $_.Extension -ieq ".cmd") -and
        ('\' + $_.FullName.Substring($RepoRoot.Length + 1)) -notmatch $skipDirRegex
    })
    foreach ($f in $allWinScripts) {
        $rel = $f.FullName.Substring($RepoRoot.Length + 1).Replace('\', '/')
        $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
        if ($f.Extension -ieq ".ps1") {
            if ($bytes.Length -lt 3 -or $bytes[0] -ne 0xEF -or $bytes[1] -ne 0xBB -or $bytes[2] -ne 0xBF) {
                $bomMiss.Add($rel)
            }
        } else {
            $nonAscii = $false
            for ($k = 0; $k -lt $bytes.Length; $k++) { if ($bytes[$k] -gt 127) { $nonAscii = $true; break } }
            $rawStr = [System.Text.Encoding]::ASCII.GetString($bytes)
            $hasBareLf = [regex]::IsMatch($rawStr, '(?<!\r)\n')
            if ($nonAscii -or $hasBareLf) {
                $batIssues.Add("${rel} (nonAscii=${nonAscii}, bareLF=${hasBareLf})")
            }
        }
    }
    if ($bomMiss.Count -eq 0 -and $batIssues.Count -eq 0) {
        Write-Host ("[4/7] 脚本编码检查（.ps1 BOM / .bat ASCII+CRLF）：PASS（{0} 文件）" -f $allWinScripts.Count) -ForegroundColor Green
    } else {
        Write-Host ("[4/7] 脚本编码检查：FAIL（缺 BOM=$($bomMiss.Count)，bat 违规=$($batIssues.Count)）" -f $bomMiss.Count, $batIssues.Count) -ForegroundColor Red
        foreach ($x in $bomMiss) { $errors.Add("缺少 UTF-8 BOM: $x") }
        foreach ($x in $batIssues) { $errors.Add(".bat/.cmd 编码违规: $x") }
    }

    # [5/7] 文档防退化卫生 Linter
    if ($SkipDocLint) {
        Write-Host "[5/7] 文档卫生检查：SKIPPED（-SkipDocLint）" -ForegroundColor Yellow
    } else {
        $docErrors = New-Object System.Collections.Generic.List[string]
        $docWarns = New-Object System.Collections.Generic.List[string]
        $docsDir = Join-Path $RepoRoot "docs"

        # 5a. 禁止的过程性/临时文档文件名
        $bannedFileRegex = '(?i)^(scripts-maintenance|doc-sync-audit|temp-.*|.*-test-plan|.*-audit|.*-report|.*-changelog|.*-draft|session-.*|upstream-review-.*|issue-.*)\.md$'
        if (Test-Path -LiteralPath $docsDir) {
            Get-ChildItem -LiteralPath $docsDir -Filter "*.md" -File | ForEach-Object {
                if ($_.Name -match $bannedFileRegex) {
                    $docErrors.Add("docs/$($_.Name) 属于禁止提交的过程性/临时文档名（临时草稿请放入 .scratch/，进度请走 GitHub Issue/PR）")
                }
            }
        }

        # 5b. 扫描黑话、绝对路径、遗留归档路径
        $bannedPatterns = @(
            @{ Name = "多Agent角色黑话"; Regex = '\b(punish-docs|punish-system|ipguard-audit|docs-diag|task-\d+)\b|(由|交给)\s*Lead\b|子\s*Agent\s*(负责|干活|分工)' },
            @{ Name = "历史批次/会话代号"; Regex = '第[一二三四五六七八九十百\d]+批|已闭环基线|待[^\s。，]{1,10}回写|本轮实测|\b(N1[-~～]N3|N4/D5|D1[-~～]D4|Q1[-~～]Q\d)\b' },
            @{ Name = "本机硬编码/归档旧路径"; Regex = 'C:\\Users\\|mdt保留[/\\]mdtdo|\.agents[/\\]|复制插件用|\.\.[/\\]参考项目' }
        )

        $docFilesToScan = New-Object System.Collections.Generic.List[System.IO.FileInfo]
        foreach ($topMd in @("README.md", "AGENTS.md", "CONTRIBUTING.md", "CHANGELOG.md")) {
            $tp = Join-Path $RepoRoot $topMd
            if (Test-Path -LiteralPath $tp) { $docFilesToScan.Add((Get-Item -LiteralPath $tp)) }
        }
        foreach ($subMd in @("stats-web\README.md", "updater\README.md", "email-service\README.md", "mdtserver\启动说明.md")) {
            $sp = Join-Path $RepoRoot $subMd
            if (Test-Path -LiteralPath $sp) { $docFilesToScan.Add((Get-Item -LiteralPath $sp)) }
        }
        if (Test-Path -LiteralPath $docsDir) {
            Get-ChildItem -LiteralPath $docsDir -Filter "*.md" -File | ForEach-Object { $docFilesToScan.Add($_) }
        }

        foreach ($df in $docFilesToScan) {
            $rel = $df.FullName.Substring($RepoRoot.Length + 1).Replace('\', '/')
            $lines = [System.IO.File]::ReadAllLines($df.FullName, [System.Text.Encoding]::UTF8)
            $lintDisabled = $false
            for ($ln = 0; $ln -lt $lines.Count; $ln++) {
                $line = $lines[$ln]
                if ($line -match '<!--\s*doc-lint-disable\s*-->') { $lintDisabled = $true; continue }
                if ($line -match '<!--\s*doc-lint-enable\s*-->')  { $lintDisabled = $false; continue }
                if ($lintDisabled -or $line -match '<!--\s*doc-lint-ignore\s*-->') { continue }
                foreach ($bp in $bannedPatterns) {
                    if ($line -match $bp.Regex) {
                        $docErrors.Add("${rel}:$($ln+1) 命中禁写项[$($bp.Name)]: $($Matches[0])")
                    }
                }
            }

            # 5c. docs/backlog.md 禁止残留已完成项
            if ($rel -ieq "docs/backlog.md") {
                for ($ln = 0; $ln -lt $lines.Count; $ln++) {
                    if ($lines[$ln] -match '✅|历史快照|^\s*-\s*\[x\]') {
                        $docErrors.Add("docs/backlog.md:$($ln+1) 存在已完成或历史快照项（backlog.md 仅保留未完成项，完成后请直接删除）")
                    }
                }
            }

            # 5d. 文档体量告警（docs/ 下单文件超过 450 行）
            if ($rel.StartsWith("docs/") -and $lines.Count -gt 450) {
                $docWarns.Add("${rel} 共 $($lines.Count) 行（超过 450 行推荐上限，建议精简或拆归 Issue/PR）")
            }
        }

        # 5e. 路由表登记检查：docs/*.md 必须在 docs/architecture.md 中出现引用
        $archPath = Join-Path $docsDir "architecture.md"
        if ((Test-Path -LiteralPath $archPath) -and (Test-Path -LiteralPath $docsDir)) {
            $archText = [System.IO.File]::ReadAllText($archPath, [System.Text.Encoding]::UTF8)
            $coreSkip = @("architecture.md", "workflow.md", "dev-and-debug.md", "deployment.md", "backlog.md")
            Get-ChildItem -LiteralPath $docsDir -Filter "*.md" -File | ForEach-Object {
                if ($coreSkip -notcontains $_.Name -and $archText -notmatch [regex]::Escape($_.Name)) {
                    $docWarns.Add("docs/$($_.Name) 未登记在 docs/architecture.md 路由表中")
                }
            }
        }

        if ($docErrors.Count -eq 0) {
            Write-Host ("[5/7] 文档防退化卫生 Linter：PASS（{0} 篇文档，0 违规，{1} 告警）" -f $docFilesToScan.Count, $docWarns.Count) -ForegroundColor Green
        } else {
            Write-Host ("[5/7] 文档防退化卫生 Linter：FAIL（{0} 处违规）" -f $docErrors.Count) -ForegroundColor Red
            foreach ($de in $docErrors) { $errors.Add("文档卫生: $de") }
        }
        foreach ($dw in $docWarns) { $warnings.Add("文档建议: $dw") }
    }

    # [6/7] 敏感文件防泄漏 & 核心库修改告警
    $sensitiveHits = New-Object System.Collections.Generic.List[string]
    $libChangeWarns = New-Object System.Collections.Generic.List[string]
    $gitExe = Get-Command git -ErrorAction SilentlyContinue
    if ($null -ne $gitExe -and (Test-Path -LiteralPath (Join-Path $RepoRoot ".git"))) {
        $stLines = @(& git -C $RepoRoot status --porcelain -uall 2>$null)
        foreach ($sl in $stLines) {
            if ($sl.Length -lt 4) { continue }
            $pathPart = $sl.Substring(3).Trim().Trim('"').Replace('\', '/')
            if ($pathPart -match '(^|/)(email-service/config\.json|mdtserver/server\.properties|tools/dev-local\.json|tools/\.dev-server\.pid)$' -or
                $pathPart -match '(^|/)(web-data/|scripts/data/|scripts/cache/|\.scratch/(?!README\.md$))' -or
                $pathPart -match '\.(mv\.db|sqlite|sqlite3|pid)$') {
                $sensitiveHits.Add($pathPart)
            }
            if ($pathPart -match 'wayzer/(vote\.lib\.kt|lib/[^/]+\.kt)$') {
                $realDiff = (& git -C $RepoRoot diff --ignore-cr-at-eol -- $pathPart 2>$null)
                if ($realDiff) {
                    $libChangeWarns.Add($pathPart)
                }
            }
        }
    }
    if ($sensitiveHits.Count -eq 0) {
        Write-Host "[6/7] 敏感文件与核心库检查：PASS（0 敏感泄漏）" -ForegroundColor Green
    } else {
        Write-Host ("[6/7] 敏感文件检查：FAIL（{0} 个敏感/运行文件出现在 git 状态）" -f $sensitiveHits.Count) -ForegroundColor Red
        foreach ($sh in $sensitiveHits) { $errors.Add("敏感/运行文件不可入库: $sh") }
    }
    foreach ($lw in $libChangeWarns) {
        $warnings.Add("检测到核心库改动 ($lw)：严禁热重载，必须执行 verify -Mode cold -ClearCache 全量验证")
    }

    # [7/7] 双仓同步漂移检查（仅主工作区）
    $syncScript = Join-Path $ToolDir "sync-repo.ps1"
    if ($RepoLayout -eq "mdtdo-workspace" -and (Test-Path -LiteralPath $syncScript)) {
        Write-Host "[7/7] 双仓同步状态：" -ForegroundColor Cyan
        & $syncScript -Action status -Brief
    } else {
        Write-Host "[7/7] 双仓同步状态：N/A（独立插件仓库布局）" -ForegroundColor DarkGray
    }

    if ($warnings.Count -gt 0) {
        Write-Host "`n--- 告警 ($($warnings.Count)) ---" -ForegroundColor Yellow
        foreach ($w in $warnings) { Write-Host "  [WARN] $w" -ForegroundColor Yellow }
    }

    if ($errors.Count -gt 0) {
        Write-Host "`n--- 失败详情 ($($errors.Count)) ---" -ForegroundColor Red
        foreach ($e in $errors) { Write-Host "  [ERR]  $e" -ForegroundColor Red }
        Write-Host "`n静态体检结果：FAIL" -ForegroundColor Red
        return $false
    }

    Write-Host "`n静态体检结果：PASS（全部门禁通过）" -ForegroundColor Green
    return $true
}

# ---------- 2. 冷启动开发服（start） ----------
function Start-DevServerInternal {
    $srvDir = Get-EffectiveServerDir
    if ($SyncToServer) {
        Sync-RepoScriptsToServer -TargetServerDir $srvDir
    }

    $existing = Get-ServerManagedProcesses -TargetServerDir $srvDir
    $portBusy = (Test-UdpOrTcpPortListening -PortNum $GamePort) -or (Test-TcpPortOpen -HostName $SocketHost -PortNum $SocketPort -TimeoutMs 250)
    if ($existing.Count -gt 0 -or $portBusy) {
        if ($ForceRestart) {
            $stopped = Stop-DevServerInternal -TargetServerDir $srvDir -WaitSec 20
            if (-not $stopped) { throw "强制停服后端口仍被占用，无法启动。" }
        } else {
            throw "端口 ${GamePort}/${SocketPort} 已被占用或已有开发服在运行。可先执行 stop，或传入 -ForceRestart。"
        }
    }

    if ($ClearCache) {
        $cacheDir = Join-Path $srvDir "config\scripts\cache"
        if (Test-Path -LiteralPath $cacheDir) {
            Write-Host "[Start] 清空编译缓存: $cacheDir" -ForegroundColor Cyan
            Get-ChildItem -LiteralPath $cacheDir -Force | Remove-Item -Recurse -Force
        }
    }

    $startPs1 = Join-Path $srvDir "start-server.ps1"
    if (-not (Test-Path -LiteralPath $startPs1)) {
        throw "未在测试服目录找到 start-server.ps1: $startPs1"
    }
    $launcherText = [System.IO.File]::ReadAllText($startPs1, [System.Text.Encoding]::UTF8)
    $supportsHeadless = ($launcherText -match '\$Headless' -and $launcherText -match '\$PidFile')
    if (-not $supportsHeadless) {
        throw "测试服的 start-server.ps1 缺少 -Headless / -PidFile 支持，请先更新到最新版 start-server.ps1。"
    }

    if (Test-Path -LiteralPath $PidFilePath) { Remove-Item -LiteralPath $PidFilePath -Force -ErrorAction SilentlyContinue }

    $psExe = (Get-Command powershell.exe -ErrorAction SilentlyContinue).Source
    if ([string]::IsNullOrWhiteSpace($psExe)) { $psExe = "pwsh" }

    $logsDir = Join-Path $srvDir "config\logs"
    if (-not (Test-Path -LiteralPath $logsDir)) { New-Item -ItemType Directory -Force -Path $logsDir | Out-Null }
    $launcherOut = Join-Path $logsDir "dev-launcher.out.log"
    $launcherErr = Join-Path $logsDir "dev-launcher.err.log"
    $startWallTime = Get-Date

    $proc = Start-Process -FilePath $psExe `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$startPs1`"", "-Once", "-Headless", "-PidFile", "`"$PidFilePath`"") `
        -WorkingDirectory $srvDir -WindowStyle Hidden `
        -RedirectStandardOutput $launcherOut -RedirectStandardError $launcherErr -PassThru
    if ($null -eq $proc) { throw "拉起开发服启动器失败。" }

    Write-Host ("[Start] 已启动后台开发服（wrapper PID={0}，目录={1}），等待脚本编译与加载（超时 {2}s）..." -f $proc.Id, $srvDir, $Timeout) -ForegroundColor Cyan

    $log0 = Join-Path $logsDir "log-0.txt"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $bannerLine = ""
    $serverLoaded = $false
    $meta = $null

    while ($sw.Elapsed.TotalSeconds -lt $Timeout) {
        if ($null -eq $meta) { $meta = Read-PidMeta }

        if ($proc.HasExited) {
            Write-Host ("[Start] 启动器提前退出（exit={0}）" -f $proc.ExitCode) -ForegroundColor Red
            if ($null -ne $meta -and (Test-Path -LiteralPath $meta.consoleLog)) {
                Write-Host "--- console log tail ---" -ForegroundColor Yellow
                Get-Content -LiteralPath $meta.consoleLog -Tail 40 -Encoding UTF8 | ForEach-Object { Write-Host $_ }
            } elseif (Test-Path -LiteralPath $launcherErr) {
                Get-Content -LiteralPath $launcherErr -Tail 30 -Encoding UTF8 | ForEach-Object { Write-Host $_ }
            }
            return $false
        }

        # 仅读取本次启动生成的新日志，彻底避免误读上一轮尚未归档的旧 log-0.txt
        $candidateFiles = New-Object System.Collections.Generic.List[string]
        if ($null -ne $meta -and -not [string]::IsNullOrWhiteSpace($meta.consoleLog)) {
            $candidateFiles.Add([string]$meta.consoleLog)
        }
        if ((Test-Path -LiteralPath $log0) -and ((Get-Item -LiteralPath $log0).LastWriteTime -ge $startWallTime.AddSeconds(-1))) {
            $candidateFiles.Add($log0)
        }

        foreach ($cf in $candidateFiles) {
            if (-not (Test-Path -LiteralPath $cf)) { continue }
            try {
                $fs = [System.IO.File]::Open($cf, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                try {
                    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
                    $content = $sr.ReadToEnd()
                } finally { $fs.Dispose() }

                if ([string]::IsNullOrEmpty($bannerLine)) {
                    $bm = [regex]::Match($content, '共找到\d+脚本,加载成功\d+,启用成功\d+,出错\d+')
                    if ($bm.Success) { $bannerLine = $bm.Value }
                }
                if (-not $serverLoaded -and $content -match 'Server loaded\.') {
                    $serverLoaded = $true
                }
            } catch { }
        }

        if (-not [string]::IsNullOrEmpty($bannerLine) -and $serverLoaded) {
            if (Test-TcpPortOpen -HostName $SocketHost -PortNum $SocketPort -TimeoutMs 300) {
                break
            }
        }
        Start-Sleep -Milliseconds 500
    }

    if ([string]::IsNullOrEmpty($bannerLine) -or -not $serverLoaded) {
        Write-Host ("[Start] 等待冷启动就绪超时（{0}s）。banner='{1}', serverLoaded={2}" -f $Timeout, $bannerLine, $serverLoaded) -ForegroundColor Red
        $meta = Read-PidMeta
        if ($null -ne $meta -and $meta.javaPid) {
            $jstack = Get-Command jstack -ErrorAction SilentlyContinue
            if ($null -ne $jstack) {
                $dumpPath = Join-Path $logsDir "dev-timeout-jstack.txt"
                & $jstack.Source $meta.javaPid > $dumpPath 2>&1
                Write-Host "[Start] 已抓取 jstack 堆栈到: $dumpPath" -ForegroundColor Yellow
            }
        }
        return $false
    }

    # 等待 socket 稳定并重试获取 scriptdiag 统计
    $errMatch = [regex]::Match($bannerLine, '出错(\d+)')
    $bannerErrs = if ($errMatch.Success) { [int]$errMatch.Groups[1].Value } else { -1 }

    $diagOut = ""
    $failedOut = ""
    $socketOk = Test-TcpPortOpen -HostName $SocketHost -PortNum $SocketPort -TimeoutMs 500
    if ($socketOk) {
        for ($try = 0; $try -lt 6; $try++) {
            Start-Sleep -Milliseconds 500
            $diagOut = Get-CleanTextNoAnsi (Invoke-ConsoleSocketCommand -CmdText "scriptdiag count" -MaxWaitMs 3000 -IdleTimeoutMs 600)
            if ($diagOut -match '脚本统计') { break }
        }
        $failedOut = Get-CleanTextNoAnsi (Invoke-ConsoleSocketCommand -CmdText "sa listFailed" -MaxWaitMs 3000 -IdleTimeoutMs 600)
    }

    $elapsedSec = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
    Write-Host ""
    Write-Host "=== 冷启动验证摘要 ===" -ForegroundColor Cyan
    Write-Host ("  耗时       : {0}s" -f $elapsedSec)
    if ($null -ne $meta) {
        Write-Host ("  Java PID   : {0}" -f $meta.javaPid)
        Write-Host ("  控制台日志 : {0}" -f $meta.consoleLog)
    }
    Write-Host ("  启动汇总   : {0}" -f $bannerLine) -ForegroundColor $(if ($bannerErrs -eq 0) { "Green" } else { "Red" })
    if ($socketOk) {
        Write-Host ("  scriptdiag : {0}" -f (($diagOut -replace '\r?\n', ' | ').Trim()))
        Write-Host ("  listFailed : {0}" -f $(if ([string]::IsNullOrWhiteSpace($failedOut)) { "(空)" } else { ($failedOut -replace '\r?\n', ' | ').Trim() }))
    } else {
        Write-Warning "6859 命令 Socket 未监听（请检查测试服 server.properties 的 socketInput=true）。"
    }

    $diagFailMatch = [regex]::Match($diagOut, '失败(\d+)')
    $diagFails = if ($diagFailMatch.Success) { [int]$diagFailMatch.Groups[1].Value } else { 0 }
    $hasListFailed = (-not [string]::IsNullOrWhiteSpace($failedOut)) -and ($failedOut -notmatch '^(?i)\s*(\(空\)|none|0|没有.*|共0.*)$') -and ($failedOut -match '(失败|Failed|Exception|Error|/)')

    if ($bannerErrs -ne 0 -or $diagFails -gt 0 -or $hasListFailed) {
        Write-Host "冷启动验收：FAIL" -ForegroundColor Red
        return $false
    }
    Write-Host "冷启动验收：PASS" -ForegroundColor Green
    return $true
}

# ---------- 3. 热重载指定脚本（reload） ----------
function Invoke-HotReloadInternal {
    $srvDir = Get-EffectiveServerDir
    $scriptList = New-Object System.Collections.Generic.List[string]
    foreach ($item in $Scripts) {
        foreach ($part in ($item -split ",")) {
            $t = $part.Trim()
            if ($t -ne "") { $scriptList.Add($t) }
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($Target)) {
        foreach ($part in ($Target -split ",")) {
            $t = $part.Trim()
            if ($t -ne "") { $scriptList.Add($t) }
        }
    }
    if ($scriptList.Count -eq 0) {
        throw "请通过 -Scripts 或位置参数指定至少一个脚本 ID（例如 wayzer/cmds/scriptDiag）。"
    }

    # 先过静态红线检查，避免把截断脚本喂给运行服
    $okCheck = Invoke-StaticChecks
    if (-not $okCheck) {
        throw "静态体检未通过，已中止热重载。"
    }

    if ($SyncToServer) {
        Sync-RepoScriptsToServer -TargetServerDir $srvDir
    }

    if (-not (Test-TcpPortOpen -HostName $SocketHost -PortNum $SocketPort -TimeoutMs 500)) {
        throw "开发服控制台 Socket (${SocketHost}:${SocketPort}) 未监听，请先运行 start。"
    }

    $reloadLog = Join-Path $srvDir "config\logs\script-load\current.log"
    if ($Scan) {
        Write-Host "[Reload] 执行 sa scan..." -ForegroundColor Cyan
        $scanOut = Invoke-ConsoleSocketCommand -CmdText "sa scan" -MaxWaitMs 3000 -IdleTimeoutMs 600
        if (-not [string]::IsNullOrWhiteSpace($scanOut)) { Write-Host (Get-CleanTextNoAnsi $scanOut) }
    }

    $allOk = $true
    foreach ($sid in $scriptList) {
        $normId = $sid.Trim('/').Replace('\', '/')
        if ($normId.EndsWith(".kts")) { $normId = $normId.Substring(0, $normId.Length - 4) }
        if ($normId.EndsWith(".kt")) {
            throw "禁止对 .kt 库文件执行热重载（$sid），请使用: verify -Mode cold -ClearCache"
        }

        $offset = if (Test-Path -LiteralPath $reloadLog) { (Get-Item -LiteralPath $reloadLog).Length } else { 0L }
        Write-Host ("[Reload] sa load {0}" -f $normId) -ForegroundColor Cyan
        $cmdEcho = Get-CleanTextNoAnsi (Invoke-ConsoleSocketCommand -CmdText ("sa load " + $normId) -MaxWaitMs 3000 -IdleTimeoutMs 600)
        if (-not [string]::IsNullOrWhiteSpace($cmdEcho)) { Write-Host ("  echo: {0}" -f $cmdEcho) }

        # 等待 script-load/current.log 出现完成标记或静默缓存命中
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $sawLogActivity = $false
        $done = $false
        while ($sw.Elapsed.TotalSeconds -lt $Timeout) {
            if (Test-Path -LiteralPath $reloadLog) {
                $len = (Get-Item -LiteralPath $reloadLog).Length
                if ($len -gt $offset) {
                    $sawLogActivity = $true
                    $fs = [System.IO.File]::Open($reloadLog, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                    try {
                        [void]$fs.Seek($offset, [System.IO.SeekOrigin]::Begin)
                        $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
                        $delta = $sr.ReadToEnd()
                    } finally { $fs.Dispose() }
                    if ($delta -match '加载模块|启用脚本|无法启用|编译.*失败|加载失败') {
                        Start-Sleep -Milliseconds 1200
                        $done = $true
                        break
                    }
                }
            }
            if (-not $sawLogActivity -and $sw.ElapsedMilliseconds -ge 2500) {
                # 源码未变动时 SA 会静默命中缓存
                $done = $true
                break
            }
            Start-Sleep -Milliseconds 250
        }

        $stateOut = Get-CleanTextNoAnsi (Invoke-ConsoleSocketCommand -CmdText ("scriptdiag state " + $normId) -MaxWaitMs 3000 -IdleTimeoutMs 600)
        Write-Host ("  state: {0}" -f (($stateOut -replace '\r?\n', ' | ').Trim()))
        if (-not $done -or $stateOut -notmatch 'Enabled' -or $stateOut -notmatch 'failReason=无') {
            $allOk = $false
            Write-Host ("  [FAIL] {0} 未处于健康 Enabled 状态" -f $normId) -ForegroundColor Red
        }
    }

    $diagOut = Get-CleanTextNoAnsi (Invoke-ConsoleSocketCommand -CmdText "scriptdiag count" -MaxWaitMs 3000 -IdleTimeoutMs 600)
    $failedOut = Get-CleanTextNoAnsi (Invoke-ConsoleSocketCommand -CmdText "sa listFailed" -MaxWaitMs 3000 -IdleTimeoutMs 600)
    Write-Host ("  scriptdiag : {0}" -f (($diagOut -replace '\r?\n', ' | ').Trim()))
    Write-Host ("  listFailed : {0}" -f $(if ([string]::IsNullOrWhiteSpace($failedOut)) { "(空)" } else { ($failedOut -replace '\r?\n', ' | ').Trim() }))

    if ($diagOut -notmatch '失败0' -or (-not [string]::IsNullOrWhiteSpace($failedOut) -and $failedOut -match '/')) {
        $allOk = $false
    }
    return $allOk
}

# ---------- 4. 日志查看（logs） ----------
function Show-DevLogs {
    $srvDir = Get-EffectiveServerDir
    $logsDir = Join-Path $srvDir "config\logs"
    $logKind = if (-not [string]::IsNullOrWhiteSpace($Target) -and $Target -in @("console", "game", "script", "error")) { $Target } else { $Type }

    $filePath = switch ($logKind) {
        "game" {
            $p = Join-Path $logsDir "log-0.txt"
            if (Test-Path -LiteralPath $p) { $p } else { Join-Path $logsDir "last.log" }
        }
        "script" {
            $p = Join-Path $logsDir "script-load\current.log"
            if (Test-Path -LiteralPath $p) { $p } else { Join-Path $logsDir "script-load\last.log" }
        }
        "error" { Join-Path $logsDir "script-load\last-error.log" }
        default {
            $latest = Get-ChildItem -LiteralPath $logsDir -Filter "console-*.log" -File -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($null -ne $latest) { $latest.FullName } else { Join-Path $logsDir "log-0.txt" }
        }
    }

    if (-not (Test-Path -LiteralPath $filePath)) {
        Write-Warning "未找到日志文件: $filePath"
        return
    }
    Write-Host ("=== {0} (最后 {1} 行) ===" -f $filePath, $Tail) -ForegroundColor Cyan
    Get-Content -LiteralPath $filePath -Tail $Tail -Encoding UTF8 | ForEach-Object { Write-Output $_ }
}

# ---------- 5. Web / Email 隔离冒烟测试 ----------
function Invoke-SmokeWeb {
    $webDir = Join-Path $RepoRoot "stats-web"
    $startWeb = Join-Path $webDir "start-web.ps1"
    if (-not (Test-Path -LiteralPath $startWeb)) { throw "未找到 stats-web/start-web.ps1" }

    $testPort = if ($Port -gt 0) { $Port } else { 18090 }
    $tmpDir = Join-Path $env:TEMP ("mdt-smoke-web-" + $PID + "-" + (Get-Date -Format "HHmmss"))
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    $cfgPath = Join-Path $tmpDir "web-config.smoke.json"
    $dataDir = Join-Path $tmpDir "web-data"
    $emptyStats = Join-Path $tmpDir "server-status.json"
    $emptyEvents = Join-Path $tmpDir "live-events.jsonl"
    $dummyLogs = Join-Path $tmpDir "logs"
    New-Item -ItemType Directory -Force -Path $dummyLogs | Out-Null
    [System.IO.File]::WriteAllText($emptyStats, '{"schema":2,"online":0,"maxPlayers":16}', $Utf8NoBom)
    [System.IO.File]::WriteAllText($emptyEvents, "", $Utf8NoBom)

    $cfgObj = [ordered]@{
        bind        = "127.0.0.1"
        port        = $testPort
        statsJson   = $emptyStats
        eventsJsonl = $emptyEvents
        dataDir     = $dataDir
        admin       = @{ username = "smoke"; password = ("Smoke_" + [Guid]::NewGuid().ToString("N").Substring(0, 12)) }
        console     = @{ enabled = $false; host = "127.0.0.1"; port = 6859 }
        logs        = @{ enabled = $true; dir = $dummyLogs; requireLogin = $true }
        bandwidth   = @{ enabled = $false }
    }
    [System.IO.File]::WriteAllText($cfgPath, ($cfgObj | ConvertTo-Json -Depth 6), $Utf8NoBom)

    $psExe = (Get-Command powershell.exe -ErrorAction SilentlyContinue).Source
    if ([string]::IsNullOrWhiteSpace($psExe)) { $psExe = "pwsh" }

    $outLog = Join-Path $tmpDir "web.out.log"
    $errLog = Join-Path $tmpDir "web.err.log"
    $proc = $null
    try {
        $proc = Start-Process -FilePath $psExe `
            -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$startWeb`"", "-Config", "`"$cfgPath`"", "-Bind", "127.0.0.1", "-Port", "$testPort") `
            -WorkingDirectory $webDir -WindowStyle Hidden `
            -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
        Write-Host ("[Smoke-Web] 临时实例已启动 (PID={0}, http://127.0.0.1:{1})" -f $proc.Id, $testPort) -ForegroundColor Cyan

        $ready = $false
        for ($i = 0; $i -lt 30; $i++) {
            if ($proc.HasExited) { throw "Web 进程提前退出（exit=$($proc.ExitCode)）" }
            if (Test-TcpPortOpen -HostName "127.0.0.1" -PortNum $testPort -TimeoutMs 250) { $ready = $true; break }
            Start-Sleep -Milliseconds 300
        }
        if (-not $ready) { throw "等待 Web 端口 $testPort 就绪超时。" }

        $baseUrl = "http://127.0.0.1:$testPort"
        $health = Invoke-RestMethod -Uri "$baseUrl/api/health" -TimeoutSec 5
        if (-not $health.ok) { throw "/api/health 返回 ok=false" }
        $status = Invoke-RestMethod -Uri "$baseUrl/api/status" -TimeoutSec 5
        $events = Invoke-RestMethod -Uri "$baseUrl/api/events" -TimeoutSec 5
        foreach ($page in @("/", "/upload.html", "/admin.html")) {
            $r = Invoke-WebRequest -Uri "$baseUrl$page" -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -ne 200) { throw "页面 $page 返回 HTTP $($r.StatusCode)" }
        }
        Write-Host "[Smoke-Web] PASS：/api/health、/api/status、/api/events 及三页面均返回 200 OK。" -ForegroundColor Green
        return $true
    } finally {
        if ($null -ne $proc -and -not $proc.HasExited) { try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { } }
        Start-Sleep -Milliseconds 300
        Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-SmokeEmail {
    $emailDir = Join-Path $RepoRoot "email-service"
    $srvPs1 = Join-Path $emailDir "server.ps1"
    if (-not (Test-Path -LiteralPath $srvPs1)) { throw "未找到 email-service/server.ps1" }

    $testPort = if ($Port -gt 0) { $Port } else { 40159 }
    $tmpDir = Join-Path $env:TEMP ("mdt-smoke-email-" + $PID + "-" + (Get-Date -Format "HHmmss"))
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    $cfgPath = Join-Path $tmpDir "email-config.smoke.json"
    $token = "smoke-" + [Guid]::NewGuid().ToString("N")
    $cfgObj = [ordered]@{
        listen        = @{ host = "127.0.0.1"; port = $testPort }
        internalToken = $token
        aliyun        = @{ endpoint = "https://dm.aliyuncs.com/"; accessKeyId = "MOCK"; accessKeySecret = "MOCK"; senderAddress = "noreply@example.com"; fromAlias = "MDT-Smoke" }
        allowDomains  = @("qq.com", "foxmail.com")
        limits        = @{ perEmailCooldownSeconds = 60; perEmailDailyLimit = 5; globalDailyLimit = 100 }
        mockSend      = $true
        logDir        = (Join-Path $tmpDir "logs")
    }
    [System.IO.File]::WriteAllText($cfgPath, ($cfgObj | ConvertTo-Json -Depth 5), $Utf8NoBom)

    $psExe = (Get-Command powershell.exe -ErrorAction SilentlyContinue).Source
    if ([string]::IsNullOrWhiteSpace($psExe)) { $psExe = "pwsh" }

    $outLog = Join-Path $tmpDir "email.out.log"
    $errLog = Join-Path $tmpDir "email.err.log"
    $proc = $null
    try {
        $proc = Start-Process -FilePath $psExe `
            -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$srvPs1`"", "-Config", "`"$cfgPath`"") `
            -WorkingDirectory $emailDir -WindowStyle Hidden `
            -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
        Write-Host ("[Smoke-Email] 临时 mock 实例已启动 (PID={0}, http://127.0.0.1:{1})" -f $proc.Id, $testPort) -ForegroundColor Cyan

        $ready = $false
        for ($i = 0; $i -lt 30; $i++) {
            if ($proc.HasExited) { throw "email-service 提前退出（exit=$($proc.ExitCode)）" }
            if (Test-TcpPortOpen -HostName "127.0.0.1" -PortNum $testPort -TimeoutMs 250) { $ready = $true; break }
            Start-Sleep -Milliseconds 300
        }
        if (-not $ready) { throw "等待邮件服务端口 $testPort 就绪超时。" }

        $baseUrl = "http://127.0.0.1:$testPort"
        $health = Invoke-RestMethod -Uri "$baseUrl/health" -TimeoutSec 5
        if (-not $health.ok) { throw "/health 返回 ok=false" }

        # 无 token 必须返回 401
        $got401 = $false
        try {
            $bodyBytes = $Utf8NoBom.GetBytes('{"email":"123456@qq.com","code":"123456"}')
            Invoke-RestMethod -Uri "$baseUrl/send" -Method Post -ContentType "application/json; charset=utf-8" -Body $bodyBytes -TimeoutSec 5 | Out-Null
        } catch {
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 401) { $got401 = $true }
        }
        if (-not $got401) { throw "缺少 X-Internal-Token 时未返回 HTTP 401" }

        # 非 QQ 域名必须返回 400
        $got400 = $false
        try {
            $bodyBytes = $Utf8NoBom.GetBytes('{"email":"test@example.com","code":"123456"}')
            Invoke-RestMethod -Uri "$baseUrl/send" -Method Post -ContentType "application/json; charset=utf-8" -Headers @{ "X-Internal-Token" = $token } -Body $bodyBytes -TimeoutSec 5 | Out-Null
        } catch {
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 400) { $got400 = $true }
        }
        if (-not $got400) { throw "非 QQ 邮箱域名时未返回 HTTP 400" }

        # 合法 QQ 邮箱 + token（mockSend=true）必须返回 200 ok=true
        $okBytes = $Utf8NoBom.GetBytes('{"email":"123456789@qq.com","code":"654321","expireMinutes":10}')
        $sendResp = Invoke-RestMethod -Uri "$baseUrl/send" -Method Post -ContentType "application/json; charset=utf-8" -Headers @{ "X-Internal-Token" = $token } -Body $okBytes -TimeoutSec 5
        if (-not $sendResp.ok) { throw "合法 mock /send 返回 ok=false" }

        Write-Host "[Smoke-Email] PASS：/health(200)、无鉴权(401)、非法域名(400)、合法 mock 发信(200) 全部符合契约。" -ForegroundColor Green
        return $true
    } finally {
        if ($null -ne $proc -and -not $proc.HasExited) { try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { } }
        Start-Sleep -Milliseconds 300
        Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ---------- 6. 状态面板（status） ----------
function Show-DevStatus {
    Write-Host ("=== MDT DO 本地开发环境状态（布局: {0}）===" -f $RepoLayout) -ForegroundColor Cyan
    $srvDir = ""
    try { $srvDir = Get-EffectiveServerDir } catch { $srvDir = "(未配置)" }
    Write-Host ("  测试服目录 : {0}" -f $srvDir)

    $procs = if ($srvDir -ne "(未配置)") { Get-ServerManagedProcesses -TargetServerDir $srvDir } else { @() }
    Write-Host ("  托管进程   : {0}" -f $(if ($procs.Count -eq 0) { "无" } else { ($procs | ForEach-Object { "$($_.Name)#$($_.Id)" }) -join ", " }))
    Write-Host ("  游戏端口   : {0} -> {1}" -f $GamePort, $(if (Test-UdpOrTcpPortListening -PortNum $GamePort) { "LISTENING" } else { "空闲" }))
    $sockOpen = Test-TcpPortOpen -HostName $SocketHost -PortNum $SocketPort -TimeoutMs 300
    Write-Host ("  命令Socket : {0}:{1} -> {2}" -f $SocketHost, $SocketPort, $(if ($sockOpen) { "可达" } else { "未监听" }))
    Write-Host ("  Web 门户   : 127.0.0.1:8090 -> {0}" -f $(if (Test-TcpPortOpen -HostName "127.0.0.1" -PortNum 8090 -TimeoutMs 200) { "运行中" } else { "未监听" }))
    Write-Host ("  邮件服务   : 127.0.0.1:40150 -> {0}" -f $(if (Test-TcpPortOpen -HostName "127.0.0.1" -PortNum 40150 -TimeoutMs 200) { "运行中" } else { "未监听" }))

    if ($sockOpen) {
        $diagOut = Get-CleanTextNoAnsi (Invoke-ConsoleSocketCommand -CmdText "scriptdiag count" -MaxWaitMs 2500 -IdleTimeoutMs 500)
        $failedOut = Get-CleanTextNoAnsi (Invoke-ConsoleSocketCommand -CmdText "sa listFailed" -MaxWaitMs 2500 -IdleTimeoutMs 500)
        Write-Host ("  scriptdiag : {0}" -f (($diagOut -replace '\r?\n', ' | ').Trim()))
        Write-Host ("  listFailed : {0}" -f $(if ([string]::IsNullOrWhiteSpace($failedOut)) { "(空)" } else { ($failedOut -replace '\r?\n', ' | ').Trim() }))
    }

    if ($srvDir -ne "(未配置)") {
        $log0 = Join-Path $srvDir "config\logs\log-0.txt"
        if (-not (Test-Path -LiteralPath $log0)) { $log0 = Join-Path $srvDir "config\logs\last.log" }
        if (Test-Path -LiteralPath $log0) {
            $b = Select-String -LiteralPath $log0 -Pattern '共找到\d+脚本' -ErrorAction SilentlyContinue | Select-Object -Last 1
            if ($null -ne $b) { Write-Host ("  最近加载   : {0}" -f $b.Line.Trim()) }
        }
    }

    $syncScript = Join-Path $ToolDir "sync-repo.ps1"
    if ($RepoLayout -eq "mdtdo-workspace" -and (Test-Path -LiteralPath $syncScript)) {
        Write-Host "  双仓状态   :"
        & $syncScript -Action status -Brief
    }
}

# ---------- 帮助信息 ----------
function Show-Help {
    @"
MDT DO 标准化开发调试与验收工具（tools/mdt-dev.ps1）

常用命令：
  check                             一键静态体检（KDoc / listen<T> / contextScript依赖 / 编码 / 文档卫生 / 敏感文件）
  start [-ClearCache] [-ForceRestart] [-SyncToServer]
                                    非阻塞冷启动开发服并自动校验 banner、scriptdiag count、sa listFailed
  stop                              精准停止本工作区开发服并等待端口释放
  status                            查看开发服/端口/Web/邮件服务/双仓状态
  cmd "<控制台指令>"                 通过 6859 Socket 发送控制台指令（UTF-8 无 BOM）
  reload <脚本ID[,ID2]> [-Scan]     先静态体检再热重载 .kts 脚本，并校验 Enabled 状态
  verify [-Mode cold|hot] [-ClearCache] [-Scripts ...] [-KeepRunning] [-SyncToServer]
                                    标准交付前一键验收（cold 模式验收后默认自动停服清场）
  logs [-Type console|game|script|error] [-Tail 80]
                                    查看最新控制台/游戏/脚本加载/错误日志
  smoke-web [-Port 18090]           隔离端口冒烟测试 stats-web（不触碰正式配置与运行数据）
  smoke-email [-Port 40159]         隔离端口 mock 冒烟测试 email-service（不真实发信）

协作者独立克隆仓库说明：
  1) check 可在无服务端环境下直接运行；
  2) 复制 tools/dev-local.example.json 为 tools/dev-local.json 并填写本机 serverDir，
     即可通过 -SyncToServer 自动把仓库 scripts/ 镜像到测试服并执行 start / reload / verify。
"@ | Write-Host
}

# ---------- 路由执行 ----------
switch ($Action) {
    "help" { Show-Help; exit 0 }
    "check" {
        if (Invoke-StaticChecks) { exit 0 } else { exit 1 }
    }
    "status" {
        Show-DevStatus; exit 0
    }
    "start" {
        if (Start-DevServerInternal) { exit 0 } else { exit 1 }
    }
    "stop" {
        $srvDir = Get-EffectiveServerDir
        if (Stop-DevServerInternal -TargetServerDir $srvDir -WaitSec ([Math]::Min($Timeout, 30))) { exit 0 } else { exit 1 }
    }
    "cmd" {
        $cmdToSend = if (-not [string]::IsNullOrWhiteSpace($Command)) { $Command } else { $Target }
        if ([string]::IsNullOrWhiteSpace($cmdToSend)) { throw "请提供要执行的控制台指令，例如: mdt-dev.ps1 cmd `"scriptdiag count`"" }
        $resp = Invoke-ConsoleSocketCommand -CmdText $cmdToSend
        if (-not [string]::IsNullOrWhiteSpace($resp)) { Write-Output (Get-CleanTextNoAnsi $resp) }
        exit 0
    }
    "reload" {
        if (Invoke-HotReloadInternal) { exit 0 } else { exit 1 }
    }
    "logs" {
        Show-DevLogs; exit 0
    }
    "smoke-web" {
        if (Invoke-SmokeWeb) { exit 0 } else { exit 1 }
    }
    "smoke-email" {
        if (Invoke-SmokeEmail) { exit 0 } else { exit 1 }
    }
    "verify" {
        $okCheck = Invoke-StaticChecks
        if (-not $okCheck) { exit 1 }

        if ($Mode -eq "hot") {
            if ($Scripts.Count -eq 0 -and [string]::IsNullOrWhiteSpace($Target)) {
                throw "verify -Mode hot 必须通过 -Scripts 指定要热重载的 .kts 脚本 ID。"
            }
            if (Invoke-HotReloadInternal) {
                Write-Host "`n[Verify] 热重载验收通过。" -ForegroundColor Green
                exit 0
            } else {
                exit 1
            }
        } else {
            $srvDir = Get-EffectiveServerDir
            $ForceRestart = $true
            $okStart = $false
            try {
                $okStart = Start-DevServerInternal
            } finally {
                if (-not $KeepRunning) {
                    $null = Stop-DevServerInternal -TargetServerDir $srvDir -WaitSec 20
                }
            }
            if ($okStart) {
                Write-Host "`n[Verify] 冷启动全量验收通过。" -ForegroundColor Green
                exit 0
            } else {
                Write-Host "`n[Verify] 冷启动验收失败。" -ForegroundColor Red
                exit 1
            }
        }
    }
}
