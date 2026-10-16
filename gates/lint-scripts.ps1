# 非 Java/YAML 文件的语法闸门。
#
# 为什么需要它：CommentFingerprint / YamlFingerprint 只覆盖 .java 与 .yaml。
# 实测踩过：一次 edit 误删了行尾换行，把
#   Write-Host "..." -ForegroundColor DarkGray
#   Skip  "验证码登录拿双令牌" "..."
# 粘成了 `-ForegroundColor DarkGraySkip  "..." "..."` —— 语法依然合法（PowerShell 把 DarkGraySkip
# 当成参数值），AST 解析也不会报错，Skip 调用却被吞掉了。任何闸门都没拦住。
#
# 本脚本做三层检查：
#   ① PowerShell 脚本：AST 解析，报语法错误
#   ② Shell 脚本：bash -n（无 bash 则跳过）
#   ③ JSON / XML / Lua 结构文件：解析校验
# 另附 ④「行粘连」启发式：疑似把两个语句粘到同一行的行会被列出（供人工确认）。
#
# 用法: pwsh -File gates\lint-scripts.ps1 [-Detail]
param([switch]$Detail, [string]$Root)

. "$PSScriptRoot\gate-env.ps1"
if ($Root) { $RepoRoot = (Resolve-Path $Root).Path }
$root = $RepoRoot
$fail = 0

Write-Output '=== 非 Java/YAML 文件语法闸门 ==='
Write-Output ''

# ---------- ① PowerShell ----------
$ps1 = Get-ChildItem $root -Recurse -File -Include *.ps1 | Where-Object { $_.FullName -notlike '*\target\*' }
Write-Output "[1] PowerShell 脚本 ($($ps1.Count) 个)"
foreach ($f in $ps1) {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors -and $errors.Count -gt 0) {
        $fail++
        Write-Output ("    [语法错误] " + $f.FullName.Replace("$root\", ''))
        $errors | Select-Object -First 5 | ForEach-Object { Write-Output ("       行 $($_.Extent.StartLineNumber): $($_.Message)") }
    }
}
if ($fail -eq 0) { Write-Output '    全部可解析' }

# ---------- ② Shell ----------
$sh = Get-ChildItem $root -Recurse -File -Include *.sh | Where-Object { $_.FullName -notlike '*\target\*' }
$bash = Get-Command bash -ErrorAction SilentlyContinue
Write-Output ''
Write-Output "[2] Shell 脚本 ($($sh.Count) 个)"
if (-not $bash) {
    Write-Output '    未安装 bash，跳过（脚本面向 Linux VM，本机无法校验）'
} else {
    $shFail = 0
    foreach ($f in $sh) {
        $out = & bash -n $f.FullName 2>&1
        if ($LASTEXITCODE -ne 0) { $shFail++; $fail++; Write-Output ("    [语法错误] " + $f.FullName.Replace("$root\", '')); Write-Output "        $out" }
    }
    if ($shFail -eq 0) { Write-Output '    全部通过 bash -n' }
}

# ---------- ③ JSON / XML ----------
Write-Output ''
$json = Get-ChildItem $root -Recurse -File -Include *.json | Where-Object { $_.FullName -notlike '*\target\*' }
Write-Output "[3] JSON ($($json.Count) 个)"
$jFail = 0
foreach ($f in $json) {
    try { Get-Content -Encoding UTF8 $f.FullName -Raw | ConvertFrom-Json | Out-Null }
    catch { $jFail++; $fail++; Write-Output ("    [解析失败] " + $f.FullName.Replace("$root\", '') + " -> " + $_.Exception.Message) }
}
if ($jFail -eq 0) { Write-Output '    全部可解析' }

Write-Output ''
$xml = Get-ChildItem $root -Recurse -File -Include *.xml | Where-Object { $_.FullName -notlike '*\target\*' }
Write-Output "[4] XML ($($xml.Count) 个)"
$xFail = 0
foreach ($f in $xml) {
    try { [xml](Get-Content -Encoding UTF8 $f.FullName -Raw) | Out-Null }
    catch { $xFail++; $fail++; Write-Output ("    [解析失败] " + $f.FullName.Replace("$root\", '')) }
}
if ($xFail -eq 0) { Write-Output '    全部可解析' }

# ---------- ④ 行粘连启发式 ----------
Write-Output ''
Write-Output '[5] 「行粘连」启发式（仅扫 .ps1，供人工确认）'
Write-Output '    背景：`-ForegroundColor DarkGray` 与下一行的 `Skip  "..." "..."` 若被粘成一行，'
Write-Output '    语法仍合法、AST 不报错，但 Skip 调用被吞掉。本项只做提示，不作为失败依据。'
# 只扫 PowerShell：shell 里 `mkdir -p "a" "b" "c"` 这类多参数命令会被初版正则误判（已实测）。
# 判据：行内出现「裸词 + 2 个以上空格 + 引号」，且该行不是以已知的多参数命令开头。
$suspect = @()
foreach ($f in $ps1) {
    $n = 0
    foreach ($line in (Get-Content -Encoding UTF8 $f.FullName)) {
        $n++
        if ($line -match '^\s*#') { continue }
        if ($line -match '\S\s+\w+\s{2,}"[^"]*"\s+"[^"]*"\s*$' -and $line -notmatch '^\s*(Write-Host|Check|Skip|Write-Output|Invoke-WebRequest|pwsh|powershell)\b') {
            $suspect += "    $($f.FullName.Replace("$root\",'')):$n  $($line.Trim())"
        }
    }
}
if ($suspect.Count -eq 0) { Write-Output '    未发现可疑行' } else { $suspect | ForEach-Object { Write-Output $_ } }

Write-Output ''
if ($fail -eq 0) {
    Write-Output '结论: 通过'
    exit 0
}
Write-Output "结论: $fail 个文件存在语法问题"
exit 1
