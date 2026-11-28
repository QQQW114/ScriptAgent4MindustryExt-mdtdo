# 精确扫描：在块注释内部再次出现 "/*"（即 KDoc 里嵌了 /** ...）的位置 —— SA 3.4.0 编译管线会因此静默截断脚本
# 兼容本地工作区（../mdtserver/config/scripts）与开源插件仓库（../scripts）。
[CmdletBinding()]
param(
    [string]$ScriptsDir = ""
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ScriptsDir)) {
    $candWorkspace = Join-Path $PSScriptRoot '..\mdtserver\config\scripts'
    $candRepo = Join-Path $PSScriptRoot '..\scripts'
    if (Test-Path -LiteralPath $candWorkspace) {
        $ScriptsDir = $candWorkspace
    } elseif (Test-Path -LiteralPath $candRepo) {
        $ScriptsDir = $candRepo
    } else {
        throw "未找到脚本目录，请通过 -ScriptsDir 指定。"
    }
}

$root = (Resolve-Path -LiteralPath $ScriptsDir).Path
$hits = New-Object System.Collections.Generic.List[string]
$files = Get-ChildItem -Path $root -Recurse -Include *.kts,*.kt | Where-Object {
    $_.FullName -notmatch '\\(cache|data|external-cp)\\'
}

foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $line = 1
    $inBlock = $false; $inLine = $false; $inStr = $false; $inChar = $false
    $i = 0
    while ($i -lt $text.Length) {
        $c = $text[$i]
        $next = if ($i + 1 -lt $text.Length) { $text[$i + 1] } else { [char]0 }
        if ($c -eq "`n") { $line++; $inLine = $false; $i++; continue }
        if ($inLine) { $i++; continue }
        if ($inStr) {
            if ($c -eq '\') { $i += 2; continue }
            if ($c -eq '"') { $inStr = $false }
            $i++; continue
        }
        if ($inChar) {
            if ($c -eq '\') { $i += 2; continue }
            if ($c -eq "'") { $inChar = $false }
            $i++; continue
        }
        if ($inBlock) {
            if ($c -eq '/' -and $next -eq '*') {
                $rel = $f.FullName.Replace($root + '\', '')
                $hits.Add("$rel : 第 $line 行 块注释内出现 '/*'")
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

if ($hits.Count -eq 0) {
    Write-Output "扫描完成：未发现块注释内嵌套 '/*'（共 $($files.Count) 个文件）"
    exit 0
} else {
    Write-Output "发现 $($hits.Count) 处："
    $hits | ForEach-Object { Write-Output $_ }
    exit 1
}
