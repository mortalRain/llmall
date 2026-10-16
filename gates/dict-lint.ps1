# 字典与跨库引用一致性闸门
#
# 为什么需要它：注释里最有杀伤力的一类错误是**状态字典漂移** —— DDL 的 COMMENT 声明的取值
# 与 Java 代码对不上。本仓库出过真实事故：`user.status` 注释写「1 正常 2 冻结」而枚举是
# FROZEN(0)/NORMAL(1)，按注释写库会让「冻结」静默失效。人工核对 280 个文件不可持续，
# 所以把它变成机器检查。
#
# 四项检查（前两项与第四项可自动裁决，第二项是候选清单）：
#   ① 取值冲突   —— 同一字段名 + 同一标签，在 DDL 与 Java 里的取值不同
#   ② 字典孤值   —— DDL 声明的取值，在 Java 侧找不到同标签（疑似死状态；含少量噪声，需人工判断）
#   ③ 库名指向   —— 注释里的 `X.Y`：Y 是真实表名，但该表不在 X 库（多库同名表已按集合处理）
#   ④ 同表同列表述不一 —— 同一 `表.列` 在不同 SQL 文件里的字典写法不同（改一处忘另一处）
#
# 用法: pwsh -File gates\dict-lint.ps1    退出码 0 = 无命中；1 = 有命中
param([string]$Root)

. "$PSScriptRoot\gate-env.ps1"
if ($Root) { $RepoRoot = (Resolve-Path $Root).Path }
$root = $RepoRoot
$script:Hit1 = 0; $script:Hit2 = 0; $script:Hit3 = 0; $script:Hit4 = 0

# 已知库名（用于过滤 `llmall.shop.cache` 这类配置路径被误当成「库.表」）
$knownDb = @('llmall-user', 'llmall-trade', 'llmall-pay', 'llmall-item', 'llmall-cart',
    'llmall-shop', 'llmall-promotion', 'llmall-content', 'llmall-search', 'llmall-common', 'seata')
$knownDbBare = $knownDb | ForEach-Object { $_ -replace '^llmall-', '' }

function Normalize-Db {
    param([string]$Db)
    $x = $Db -replace '^llmall-', ''
    switch ($x) {
        'user' { 'user' } 'trade' { 'trade' } 'pay' { 'pay' } 'item' { 'item' } 'cart' { 'cart' }
        'shop' { 'shop' } 'promotion' { 'promotion' } 'content' { 'content' } 'search' { 'search' }
        default { $x }
    }
}

# ---------------------------------------------------------------- 收集 Java 侧字典
$javaDict = @()   # @{ File; Owner; Field; Value; Label; Kind }
$javaFiles = Get-ChildItem $root -Recurse -File -Filter *.java | Where-Object { $_.FullName -notlike '*\target\*' }
foreach ($f in $javaFiles) {
    $rel = $f.FullName.Replace("$root\", '')
    $text = [System.IO.File]::ReadAllText($f.FullName)
    # PO 的表名：用于把「同名字段」限定在**同一张表**内比对（否则各处都有的 status 会跨表误配）
    $tbl = ''
    if ($text -match '@TableName\(\s*"([^"]+)"\s*\)') { $tbl = $matches[1] }
    # 枚举项
    foreach ($m in [regex]::Matches($text, '([A-Z][A-Z0-9_]{1,40})\s*\(\s*(-?\d+)\s*,\s*"([^"]{1,24})"')) {
        $javaDict += [pscustomobject]@{ File = $rel; Table = $tbl; Owner = '枚举'; Field = $m.Groups[1].Value; Value = $m.Groups[2].Value; Label = $m.Groups[3].Value; Kind = 'enum' }
    }
    # 常量 + 行尾注释
    foreach ($m in [regex]::Matches($text, '(\w+)\s*=\s*(-?\d+)\s*;\s*//\s*([\u4e00-\u9fa5]{2,12})')) {
        $javaDict += [pscustomobject]@{ File = $rel; Table = $tbl; Owner = '常量'; Field = $m.Groups[1].Value; Value = $m.Groups[2].Value; Label = $m.Groups[3].Value; Kind = 'const' }
    }
    # 字段注释里的字典：/** 1 未使用 2 已使用 3 已过期 */  private Integer status;
    # 注意两个坑：① 注释块正则必须允许 `*`（多行 JavaDoc 每行以 * 开头）；
    #            ② 类型部分必须用**懒惰**量词，否则 `[\w .]+` 会把字段名一起吞掉（实测踩过）。
    foreach ($m in [regex]::Matches($text, '/\*\*([\s\S]{0,300}?)\*/\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:private|public|protected)\s+[\w<>\[\], .]+?\s+(\w+)\s*[;=]')) {
        $blk = $m.Groups[1].Value; $field = $m.Groups[2].Value
        # 剔除块内的「反例/说明」行：注释里常写「例如按 1 正常 2 冻结 写库会让冻结失效」，
        # 那些取值属于**反面教材**而不是本字段的字典；不剔除会产生假阳性（实测在 User.java 上踩过）。
        $keep = ($blk -split "\r?\n") | Where-Object { $_ -notmatch '例如|反例|误改|误传|禁止|不要|失效' }
        $blk2 = ($keep -join ' ')
        # 标签长度放宽到 1：`1 男 2 女` 这类单字标签是合法的，卡在 {2,} 会让整块被判为非字典而漏检。
        $pairs = @([regex]::Matches($blk2, '(\d+)\s*[：:，,、\s]\s*([\u4e00-\u9fa5]{1,8})') |
            Where-Object { $_.Groups[2].Value -notmatch '^(表示|为|即|是|含|见|之间|以上|以下)' })
        if ($pairs.Count -lt 2) { continue }    # 至少两个值对才算字典，避免单值噪声
        foreach ($p in $pairs) {
            $javaDict += [pscustomobject]@{ File = $rel; Table = $tbl; Owner = '字段注释'; Field = $field; Value = $p.Groups[1].Value; Label = $p.Groups[2].Value; Kind = 'field' }
        }
    }
}

# ---------------------------------------------------------------- 收集 SQL 侧字典与建表登记
$sqlFiles = Get-ChildItem $root -Recurse -File -Include *.sql |
    Where-Object { $_.FullName -notlike '*\target\*' -and $_.Length -lt 1MB }
$sqlDict = @()        # @{ File; Line; Table; Column; Value; Label; Raw }
$tableOwner = @{}     # 表名 -> 库名集合
foreach ($f in $sqlFiles) {
    $rel = $f.FullName.Replace("$root\", '')
    $lines = Get-Content -Encoding UTF8 $f.FullName
    $curDb = ''; $curTable = ''
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '^\s*USE\s+`?([\w-]+)`?\s*;') { $curDb = $matches[1] }
        if ($line -match 'CREATE TABLE(?:\s+IF NOT EXISTS)?\s+`?(?:([\w-]+)`?\.)?`?(\w+)`?') {
            $db = if ($matches[1]) { $matches[1] } else { $curDb }
            $curTable = $matches[2]
            if ($db -ne '') {
                if (-not $tableOwner.ContainsKey($curTable)) { $tableOwner[$curTable] = @() }
                $tableOwner[$curTable] = @($tableOwner[$curTable] + (Normalize-Db $db) | Select-Object -Unique)
            }
        }
        if ($line -match '^\s*\)\s*ENGINE') { $curTable = '' }
        foreach ($m in [regex]::Matches($line, 'COMMENT\s+''((?:[^'']|'''')*)''')) {
            $raw = $m.Groups[1].Value -replace "''", "'"
            $col = if ($line -match '^\s*`(\w+)`') { $matches[1] } else { '' }
            foreach ($p in [regex]::Matches($raw, '(\d+)\s*[：:，,、\s]\s*([\u4e00-\u9fa5]{1,8})')) {
                if ($p.Groups[2].Value -match '^(表示|为|即|是|含|见|之间|以上|以下)') { continue }
                $sqlDict += [pscustomobject]@{ File = $rel; Line = $i + 1; Table = $curTable; Column = $col
                    Value = $p.Groups[1].Value; Label = $p.Groups[2].Value; Raw = $raw }
            }
        }
    }
}

Write-Host '=== 字典与跨库引用一致性闸门 ===' -ForegroundColor Cyan
Write-Host ("Java 侧字典项 " + $javaDict.Count + "   SQL 侧字典项 " + $sqlDict.Count + "   建表登记 " + $tableOwner.Count)
Write-Host ''

# ---------------------------------------------------------------- ① 同字段名 + 同标签 + 取值不同
Write-Host '【①】同一字段名与标签下，DDL 与 Java 的取值冲突' -ForegroundColor Yellow
foreach ($s in $sqlDict) {
    foreach ($j in $javaDict) {
        if ($j.Kind -eq 'enum') { continue }               # 枚举常量名与列名不同源，不做同名比对
        if ($j.Field -cne $s.Column) { continue }
        # 必须**同表**：不同表都有 status 字段，跨表比对会产生大量假阳性（实测踩过）
        if ($j.Table -eq '' -or $j.Table -cne $s.Table) { continue }
        if ($j.Label -cne $s.Label) { continue }
        if ($j.Value -eq $s.Value) { continue }
        $script:Hit1++
        Write-Host ("  [冲突] " + $s.Column + " 的「" + $s.Label + "」：DDL=" + $s.Value + "  Java=" + $j.Value)
        Write-Host ("         DDL  " + $s.File + ":" + $s.Line + "   表 " + $s.Table + "  原文: " + $s.Raw)
        Write-Host ("         Java " + $j.File)
    }
}
if ($script:Hit1 -eq 0) { Write-Host '  OK 未发现取值冲突' -ForegroundColor Green }
Write-Host ''

# ---------------------------------------------------------------- ② DDL 取值在 Java 侧无同标签（候选）
Write-Host '【②】DDL 声明的取值在 Java 侧找不到同标签（疑似死状态，需人工确认）' -ForegroundColor Yellow
$noise = '^(表示|为一级|为无|为|即|含|见|单位|超出|范围|默认|之间|以上|以下|元|分|天|小时|分钟|秒)'
foreach ($s in $sqlDict) {
    if ($s.Label -match $noise) { continue }
    $found = $false
    foreach ($j in $javaDict) {
        if (($j.Label -ceq $s.Label) -or ($j.Label.Length -ge 2 -and $s.Label.Contains($j.Label)) -or ($s.Label.Length -ge 2 -and $j.Label.Contains($s.Label))) { $found = $true; break }
    }
    if (-not $found) {
        $script:Hit2++
        Write-Host ("  [孤值] " + $s.Column + " = " + $s.Value + "(" + $s.Label + ")   " + $s.File + ":" + $s.Line + "  表 " + $s.Table)
    }
}
if ($script:Hit2 -eq 0) { Write-Host '  OK' -ForegroundColor Green }
Write-Host ''

# ---------------------------------------------------------------- ③ 库名指向错误
Write-Host '【③】注释里的 `X.Y`：Y 是真实表名但该表不在 X 库' -ForegroundColor Yellow
$scanFiles = Get-ChildItem $root -Recurse -File -Include *.sql,*.java |
    Where-Object { $_.FullName -notlike '*\target\*' -and $_.Length -lt 1MB }
foreach ($f in $scanFiles) {
    $rel = $f.FullName.Replace("$root\", '')
    $i = 0
    foreach ($line in (Get-Content -Encoding UTF8 $f.FullName)) {
        $i++
        $t = $line.Trim()
        if (-not ($t.StartsWith('--') -or $t.StartsWith('//') -or $t.StartsWith('*') -or $t.StartsWith('/*'))) { continue }
        foreach ($m in [regex]::Matches($line, '`?([a-zA-Z][\w-]*)`?\.`?(\w+)`?')) {
            $db = $m.Groups[1].Value; $tb = $m.Groups[2].Value
            if (-not $tableOwner.ContainsKey($tb)) { continue }
            $nd = Normalize-Db $db
            # 只接受已知库名（排除 llmall.shop.cache 这类配置路径与 xxx 之类的示例占位）
            if (($knownDbBare -notcontains $nd) -and ($knownDb -notcontains $db)) { continue }
            $owners = @($tableOwner[$tb])
            if ($owners -contains $nd) { continue }
            # 多库同名表（undo_log / message_log / consume_record）：只要表在任一库存在且库名合法，不算错
            if ($owners.Count -gt 1) { continue }
            $script:Hit3++
            Write-Host ("  [库名可疑] " + $db + "." + $tb + "  →  " + $tb + " 实际在 " + ($owners -join '/') + " 库")
            Write-Host ("             " + $rel + ":" + $i + "   " + $t)
        }
    }
}
if ($script:Hit3 -eq 0) { Write-Host '  OK 未发现库名指向错误' -ForegroundColor Green }
Write-Host ''

# ---------------------------------------------------------------- ④ 同一 `表.列` 在不同文件里表述不一
Write-Host '【④】同一 `表.列` 在不同 SQL 文件里的字典写法不一致' -ForegroundColor Yellow
$byKey = @{}
foreach ($s in $sqlDict) {
    if ($s.Table -eq '' -or $s.Column -eq '') { continue }
    $key = $s.Table + '.' + $s.Column
    if (-not $byKey.ContainsKey($key)) { $byKey[$key] = @() }
    $byKey[$key] += $s
}
foreach ($key in ($byKey.Keys | Sort-Object)) {
    $items = $byKey[$key]
    $raws = $items | ForEach-Object { ($_.Raw -replace '\s+', '') } | Select-Object -Unique
    if ($raws.Count -le 1) { continue }
    $script:Hit4++
    Write-Host ("  [表述不一] " + $key)
    foreach ($r in $raws) { Write-Host ("      " + $r) }
    $locs = $items | ForEach-Object { $_.File + ':' + $_.Line } | Select-Object -Unique
    foreach ($l in $locs) { Write-Host ("      ← " + $l) }
}
if ($script:Hit4 -eq 0) { Write-Host '  OK 各文件对同一表列的字典写法一致' -ForegroundColor Green }
Write-Host ''

# ---------------------------------------------------------------- 汇总
Write-Host ('=' * 62)
Write-Host ("① 取值冲突 " + $script:Hit1 + "   ② 疑似死状态 " + $script:Hit2 + "（候选）   ③ 库名指向错误 " + $script:Hit3 + "   ④ 同列表述不一 " + $script:Hit4)
$decisive = $script:Hit1 + $script:Hit3 + $script:Hit4
if ($decisive -gt 0) {
    Write-Host ('结论: 待处理 —— ①③④ 可直接判定（每一条都要人工决定改注释还是改代码），② 需人工筛选') -ForegroundColor Red
    exit 1
}
Write-Host '结论: 通过（①③④ 无命中；② 的候选请人工过一遍）' -ForegroundColor Green
exit 0
