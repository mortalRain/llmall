# 注释工程化 · 统一校验闸门（第二轮）
#
# 用途：证明一次改动「只动了注释」——代码逐 token / 逐行未变。
#       按扩展名自动分派到对应的剥离器，调用者只需传文件清单。
#
# 用法:
#   pwsh -File gates\gate.ps1 <相对仓库根的路径> [更多路径...]
#   pwsh -File gates\gate.ps1 -Snapshot          # 重建「非 Java/YAML」基线（全项目）
#
# 基线（2026-10-15 本轮优化前快照）:
#   baseline\java.round2.txt   受保护 Java 文件清单：FP1(token流) FP2(代码行序列) + 注释/代码行数
#   baseline\yaml.round2.txt   受保护 YAML 文件清单：同构指纹
#   baseline\any.round2.txt    其它语言（Lua/SQL/PS1/SH/JS/Dockerfile）：剥注释后代码行序列 SHA256
#
# 三份清单各受保护多少个文件，以文件本身为准 —— 不在这段注释里写死数量，
# 因为基线一增删文件，写死的数字立刻变成错的。要核数直接数基线行数。
#
# 退出码 0 = 通过；1 = 存在代码差异（必须还原代码后重做）。
param(
    [switch]$Snapshot,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Files
)

. "$PSScriptRoot\gate-env.ps1"

# 仓库根固定取 gates/ 的上一级，**不开放 -Root**：本脚本的路径参数是"剩余位置参数"，
# 再放一个 [string] 参数会被 PowerShell 按声明顺序抢走第一个路径（表现为"文件不存在"），
# 而且每个路径都会去找那个不存在的子目录 —— 排查时会误以为是路径拼错。
$root = $RepoRoot
$java = Get-GateJava
$cp = Initialize-GateClasses
$base = Join-Path $GateRoot 'baseline'
$anyStore = Join-Path $base 'any.round2.txt'

. "$PSScriptRoot\strip.ps1"   # 剥离逻辑单独成文件：gate.ps1 与 selftest.ps1 共用同一份实现

# ---------- 收集「非 Java/YAML」目标文件 ----------
# gates 目录自身排除在扫描之外：它是测量仪器，不是被测量的项目源码。
# （Java/YAML 指纹走 CommentFingerprint / YamlFingerprint，那两个工具按仓库根递归，
#   无法排除 gates —— 所以 gates 下的 Java 文件是**照常纳入基线**的，只有这里例外。）
function Get-AnyTargets {
    Get-ChildItem $root -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object {
            # 【注意】这里刻意用了两类判据，别为了"统一"把它们合并：
            # 1) 任意深度的目录段（target / node_modules / .git / .idea）：每个**模块**都有自己的 target，
            #    只能按"路径段是否出现"排除，不能只看仓库根；
            # 2) 仓库根下的运行产物目录（logs / tmp）：**按相对仓库根的路径前缀**排除。
            #    这里不能用名字通配（'*\logs\*'）—— 那会把名叫 logs / tmp 的**模块目录**一起误伤，
            #    而 logs/（压测与冒烟产物）、tmp/（临时脚本）本来就只是工作痕迹，从根上按前缀排除才精确。
            $rel = ($_.FullName.Substring($root.Length) -replace '^[\\/]+', '') -replace '\\', '/'
            $_.FullName -notlike '*\target\*' -and
            $_.FullName -notlike '*\node_modules\*' -and
            $_.FullName -notlike '*\.git\*' -and
            $_.FullName -notlike '*\.idea\*' -and
            $_.FullName -notlike (Join-Path $GateRoot '*') -and
            -not ($rel -like 'logs/*' -or $rel -like 'tmp/*') -and
            $_.Length -lt 1MB -and          # 排除 sql\llmall-item.sql（36MB 数据导出，非脚本文本）
            ($_.Extension -in '.lua', '.sql', '.ps1', '.sh', '.js', '.toml', '.properties' -or $_.Name -like 'Dockerfile*' -or $_.Name -like '*.dockerignore')
        }
}

if ($Snapshot) {
    $rows = foreach ($f in Get-AnyTargets) {
        $rel = $f.FullName.Replace("$root\", '').Replace('\', '/')
        $lines = @(Get-CodeLines $f.FullName)
        $total = ([System.IO.File]::ReadAllText($f.FullName) -split "\r?\n").Count
        "{0}`t{1}`t{2}`t{3}" -f $rel, (Get-Sha $lines), $lines.Count, $total
    }
    # 用 .NET 显式写 UTF-8 无 BOM + 固定 LF：Set-Content -Encoding UTF8 在 Windows PowerShell 5.1 会写 BOM，
    # 而 WriteAllLines 用的是 Environment.NewLine（Windows 上是 CRLF）—— 两者都会让"同一份基线在两个宿主/
    # 两个平台上产生不同字节"，diff 时看不出是真改还是环境差异。这里把编码与行尾都固定下来。
    [System.IO.File]::WriteAllText($anyStore, (($rows -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "已写非 Java/YAML 基线: $anyStore ($($rows.Count) 个文件)"
    exit 0
}

if (-not $Files -or $Files.Count -eq 0) {
    Write-Output '用法: gate.ps1 <相对仓库根的路径> [更多路径...]'
    exit 1
}

$javaRel = @(); $yamlRel = @(); $anyRel = @()
foreach ($f in $Files) {
    $p = $f.Replace('\', '/')
    if ($p -like '*.java') { $javaRel += $p }
    elseif ($p -like '*.yaml' -or $p -like '*.yml') { $yamlRel += $p }
    else { $anyRel += $p }
}

$fail = 0
if ($javaRel.Count) {
    & $java -cp $cp CommentFingerprint check $root "$base\java.round2.txt" @javaRel 2>&1 |
        Where-Object { $_ -notmatch 'Picked up' }
    if ($LASTEXITCODE -ne 0) { $fail++ }
}
if ($yamlRel.Count) {
    & $java -cp $cp YamlFingerprint check $root "$base\yaml.round2.txt" @yamlRel 2>&1 |
        Where-Object { $_ -notmatch 'Picked up' }
    if ($LASTEXITCODE -ne 0) { $fail++ }
}
if ($anyRel.Count) {
    $store = @{}
    if (Test-Path $anyStore) {
        foreach ($line in Get-Content -Encoding UTF8 $anyStore) {
            if ($line.Trim() -eq '') { continue }
            $p = $line -split "`t"; $store[$p[0]] = $p
        }
    }
    foreach ($rel in $anyRel) {
        $full = Join-Path $root $rel
        if (-not (Test-Path $full)) { Write-Output "  [文件不存在] $rel"; $fail++; continue }
        $b = $store[$rel]
        if ($null -eq $b) { Write-Output "  [无基线] $rel"; $fail++; continue }
        $lines = @(Get-CodeLines $full)
        $sha = Get-Sha $lines
        $total = ([System.IO.File]::ReadAllText($full) -split "\r?\n").Count
        if ($b[1] -ne $sha) {
            Write-Output "  [代码被改动!] $rel  代码行 $($b[2]) -> $($lines.Count)"
            $fail++
        }
        else {
            Write-Output "  [OK] $rel  代码行 $($b[2]) -> $($lines.Count) 未变，总行数 $($b[3]) -> $total"
        }
    }
}

Write-Output ''
if ($fail -gt 0) { Write-Output "结论: 失败 —— $fail 项不满足「只改注释」约束"; exit 1 }
Write-Output '结论: 通过 —— 剥离注释后，代码逐行未变'
exit 0
