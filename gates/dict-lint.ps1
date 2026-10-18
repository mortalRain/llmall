# 字典与跨库引用一致性闸门
#
# 为什么需要它：注释里最有杀伤力的一类错误是**状态字典漂移** —— DDL 的 COMMENT 声明的取值
# 与 Java 代码对不上。本仓库出过真实事故：`user.status` 注释写「1 正常 2 冻结」而枚举是
# FROZEN(0)/NORMAL(1)，按注释写库会让「冻结」静默失效。人工逐文件核对不可持续，
# 所以把它变成机器检查。
#
# 四项检查（第一、第三、第四项可自动裁决，第二项输出候选清单）：
#   ① 取值冲突   —— 同一字段名 + 同一标签，在 DDL 与 Java 里的取值不同
#   ② 字典孤值   —— DDL 声明的取值，在 Java 侧找不到同标签。分两桶输出：
#                   ②-真候选   ：Java 侧确实没有对应取值，需要人工决定改注释还是改代码；
#                   ②-口径误报 ：DDL/Java 其实一致，只是写法没被解析器认出来 ——
#                                否定句（"不存在 2 已回滚"）、长度上限（"≤128 字"）、
#                                全角括号（"置 1（有效）"）、噪声词（"0 表示未开通"）。
#                   分桶的理由很直接：不分的话 2 条真问题会被 10 条误报淹没，人就不看了。
#                   两桶都逐条打印、总数不变，只是标签与颜色不同 —— 不隐藏任何候选。
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
# 「Java 侧其实写了、只是解析器没认出来」的两种写法，单独收集：用于把 ② 的口径误报与真候选分开。
#   ① 全角括号：`状态置 1（有效）` —— 解析器只认空格/冒号/逗号分隔，括号里的标签会被漏掉；
#   ② 噪声词前缀：`0 表示未开通` —— 标签以"表示/为/即/是/含/见"开头时按噪声整条丢弃。
# 这两种都属于"同一件事有两种写法，闸门只认其中一种"，属于口径问题，不是字典不一致。
$javaFullwidthPairs = @{}
$javaNoisePairs = @{}
$javaFiles = Get-ChildItem $root -Recurse -File -Filter *.java | Where-Object { $_.FullName -notlike '*\target\*' }
foreach ($f in $javaFiles) {
    $rel = $f.FullName.Replace("$root\", '')
    $text = [System.IO.File]::ReadAllText($f.FullName)
    foreach ($m in [regex]::Matches($text, '(\d+)\s*[（(]\s*([\u4e00-\u9fa5]{1,8})')) {
        $javaFullwidthPairs[$m.Groups[1].Value + '|' + $m.Groups[2].Value] = $true
    }
    foreach ($m in [regex]::Matches($text, '(\d+)\s*[：:，,、\s]\s*(?:表示|为|即|是|含|见)\s*([\u4e00-\u9fa5]{1,8})')) {
        $javaNoisePairs[$m.Groups[1].Value + '|' + $m.Groups[2].Value] = $true
    }
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
$noiseLabels = '^(表示|为一级|为无|为|即|含|见|单位|超出|范围|默认|之间|以上|以下|元|分|天|小时|分钟|秒)'

# 判定一条 ② 候选是不是"解析口径误报"：命中则返回原因字符串，否则返回空（=真候选）。
# 四类规则都要求**同一条候选**能给出具体依据，不做"看着像误报就放过"的模糊判断：
#   否定句   —— DDL 原句把该取值写成"不存在 / 无 / 没有 / 不含 / 非"，取值不是被声明的状态；
#   长度限制 —— 取值后面紧跟"字 / 位 / 条 / 个字符 / 字节"等单位，并带 ≤、<、上限 之类的界限词；
#   全角括号 —— Java 侧写成 `1（有效）`，标签在全角括号里，解析器只认半角分隔；
#   噪声词   —— Java 侧写成 `0 表示未开通`，标签以"表示/为/即/是"开头，被噪声过滤整条丢弃。
function Get-DictLintMisparseReason {
    param($Candidate)
    $raw = [string]$Candidate.Raw
    $value = [string]$Candidate.Value
    $label = [string]$Candidate.Label

    # 标签本身就是否定词（"改回 1，无 4 已退款" 会切出 `1(无)`）
    if ($label -match '^(无|没有|不存在|非)$') { return '否定句（标签是否定词）' }

    # 取值在 DDL 原文里的位置之前 8 个字符内出现否定词
    $idx = $raw.IndexOf($value, [System.StringComparison]::Ordinal)
    if ($idx -ge 0) {
        $from = [Math]::Max(0, $idx - 8)
        $before = $raw.Substring($from, $idx - $from)
        if ($before -match '(不存在|没有|不含|不是|非|无)') { return '否定句（原文写明该取值不存在）' }
    }

    # 长度/数量上限：取值后面紧跟单位，且前面有界限标记（≤ < 上限 最多 不超过）
    if ($raw -match ('((≤|<|不超过|最多|上限)\s*' + [regex]::Escape($value) + '\s*(字|位|条|个字符|字节))')) {
        return '长度限制（是长度上限，不是枚举取值）'
    }

    if ($javaFullwidthPairs.ContainsKey($value + '|' + $label)) { return '全角括号（Java 写成 1（有效））' }
    if ($javaNoisePairs.ContainsKey($value + '|' + $label)) { return '噪声词（Java 写成 0 表示未开通）' }
    return ''
}

$realCandidates = @()
$misparseCandidates = @()
foreach ($s in $sqlDict) {
    if ($s.Label -match $noiseLabels) { continue }
    $found = $false
    foreach ($j in $javaDict) {
        if (($j.Label -ceq $s.Label) -or ($j.Label.Length -ge 2 -and $s.Label.Contains($j.Label)) -or ($s.Label.Length -ge 2 -and $j.Label.Contains($s.Label))) { $found = $true; break }
    }
    if (-not $found) {
        $script:Hit2++
        $reason = Get-DictLintMisparseReason $s
        $row = [pscustomobject]@{ Column = $s.Column; Value = $s.Value; Label = $s.Label; File = $s.File; Line = $s.Line; Table = $s.Table; Raw = $s.Raw; Reason = $reason }
        if ($reason) { $misparseCandidates += $row } else { $realCandidates += $row }
    }
}

# 真候选与口径误报分开呈现，各自计数：2 条真问题不会再被 10 条误报淹没
Write-Host ("  ── ②-真候选（需要人工决定改注释还是改代码）: " + $realCandidates.Count + " 条 ──") -ForegroundColor Yellow
foreach ($c in $realCandidates) {
    Write-Host ("  [真候选] " + $c.Column + " = " + $c.Value + "(" + $c.Label + ")   " + $c.File + ":" + $c.Line + "  表 " + $c.Table)
}
if ($realCandidates.Count -eq 0) { Write-Host '  OK 无真候选' -ForegroundColor Green }
Write-Host ""
Write-Host ("  ── ②-口径误报（解析器把非字典写法当成字典，不计入待办）: " + $misparseCandidates.Count + " 条 ──") -ForegroundColor DarkGray
foreach ($c in $misparseCandidates) {
    Write-Host ("  [误报·" + $c.Reason + "] " + $c.Column + " = " + $c.Value + "(" + $c.Label + ")   " + $c.File + ":" + $c.Line) -ForegroundColor DarkGray
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
Write-Host ("① 取值冲突 " + $script:Hit1 + "   ② 疑似死状态 " + $script:Hit2 + "（真候选 " + $realCandidates.Count + " / 口径误报 " + $misparseCandidates.Count + "）   ③ 库名指向错误 " + $script:Hit3 + "   ④ 同列表述不一 " + $script:Hit4)
$decisive = $script:Hit1 + $script:Hit3 + $script:Hit4
if ($decisive -gt 0) {
    Write-Host ('结论: 待处理 —— ①③④ 可直接判定（每一条都要人工决定改注释还是改代码），② 有 ' + $realCandidates.Count + ' 条真候选需人工筛选（口径误报已单独归类）') -ForegroundColor Red
    exit 1
}
Write-Host ('结论: 通过（①③④ 无命中；② 有 ' + $realCandidates.Count + ' 条真候选待人工确认，' + $misparseCandidates.Count + ' 条为口径误报不计入）') -ForegroundColor Green
exit 0



