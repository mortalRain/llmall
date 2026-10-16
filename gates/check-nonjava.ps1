# 「只改注释」约束在 Lua / YAML 上的等效校验。
#
# Java 侧用 CommentFingerprint（去注释后比对 token 流）。Lua 的 --[[ ]] 块与 -- 行注释、
# YAML 的 # 行注释同理由此剥离：剥离后剩余的"代码/配置行序列"必须逐字节不变。
#
# 用法:
#   pwsh -File check-nonjava.ps1 snapshot <out.txt> <file...>   写基线（路径需已存在）
#   pwsh -File check-nonjava.ps1 verify   <base.txt> <file...>  与基线比对
param(
    [Parameter(Mandatory = $true)][string]$Mode,
    [Parameter(Mandatory = $true)][string]$Store,
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Files
)

function Get-CodeLines([string]$path) {
    $raw = Get-Content -Raw -Encoding UTF8 $path
    if ($path -like '*.lua') {
        # 先剥 --[[ ... ]] 块注释，再剥 -- 行注释
        $raw = [regex]::Replace($raw, '(?s)--\[\[.*?\]\]', '')
        $raw = [regex]::Replace($raw, '--[^\r\n]*', '')
    } else {
        $raw = [regex]::Replace($raw, '(?m)#[^\r\n]*', '')
    }
    ($raw -split "\r?\n") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }
}

function Get-Sha([string[]]$lines) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
    ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
}

$rows = foreach ($f in $Files) {
    $full = (Resolve-Path $f).Path
    $lines = Get-CodeLines $full
    "{0}`t{1}`t{2}`t{3}" -f $full, (Get-Sha $lines), $lines.Count, ((Get-Content -Encoding UTF8 $full).Count)
}

if ($Mode -eq 'snapshot') {
    # 用 .NET 显式写 UTF-8 无 BOM：Set-Content -Encoding UTF8 在 Windows PowerShell 5.1 会写 BOM，
    # 同一份基线在两个宿主下产生不同字节，diff 时看不出是真改还是宿主差异。
    [System.IO.File]::WriteAllLines($Store, $rows, (New-Object System.Text.UTF8Encoding($false)))
    "已写基线: $Store ($($rows.Count) 个文件)"
    $rows
    exit 0
}

$base = @{}
foreach ($line in Get-Content -Encoding UTF8 $Store) {
    if ($line.Trim() -eq '') { continue }
    $p = $line -split "`t"
    $base[$p[0]] = $p
}

$fail = 0
foreach ($row in $rows) {
    $p = $row -split "`t"
    $b = $base[$p[0]]
    if ($null -eq $b) { "  [无基线] $($p[0])"; $fail++; continue }
    if ($b[1] -ne $p[1]) {
        "  [代码/配置被改!] $($p[0])  代码行 $($b[2]) -> $($p[2])"
        $fail++
    } else {
        "  [OK] $($p[0])  代码/配置行 $($b[2]) -> $($p[2]) 未变，总行数 $($b[3]) -> $($p[3])"
    }
}
if ($fail -gt 0) { "结论: 失败 —— $fail 个文件被改动"; exit 1 }
"结论: 通过 —— 剥离注释后，Lua 代码与 YAML 配置逐行未变"
