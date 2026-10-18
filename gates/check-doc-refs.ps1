# 第 8 道闸门：文档引用有效性
#
# 拦什么：
#   docs/ 下两份对外文档（简历条目索引、面试问答手册）里每一处「文件:行」引用，
#   是否还指得到东西。判据三条，缺一不可：
#     ① 文件在仓库内存在        ② 行号不超过该文件总行数        ③ 被指的那一行不是空行
#   区间引用（:a-b）按「区间内至少有一行非空」判 —— 方法体内部本来就有空行，
#   逐行要求非空只会制造噪音。
#
# 为什么这道闸门有必要：
#   前七道闸门管的是代码与配置。文档里的行号不在任何指纹基线的保护范围内，
#   而它恰恰是面试时「当场打开给面试官看」的东西：代码一改动，行号就悄悄漂了，
#   闸门全绿也不会有人发现。这道闸门把「文档声称自己指得到」变成可验证的断言。
#
# 两类失败为什么必须分开（处置完全不同）：
#   平移 —— 内容还在，只是行号漂了。本脚本给出旧→新行号，由人确认后改文档；
#           脚本本身绝不代改。求证方式见 Resolve-Drift：优先拿「文档最后一次提交时
#           该文件的同一行文本」当锚点，在当前文件里反查它的新位置；锚不到才退回
#           「最近非空行」候选，并在输出里标明是候选（候选可能是错的，锚点不会）。
#   失效 —— 文件不存在 / 行号越界 / 区间全空且锚不到。需要人判断是引用写错了，
#           还是被引用的内容已经不存在了。
#
# 判不了的两类必须计数披露，不许静默（本闸门只负责披露，不判红）：
#   歧义  —— 只写了文件名而仓库里有多份同名文件（application.yaml 等），无从判断指哪一份
#   无行号 —— 只写了文件名，本闸门不校验行号（存在性由指纹闸门另行覆盖）
#
# 本闸门不做的事：
#   不判断行号指向的内容「在语义上是否就是要说的那段代码」—— 那需要人读，脚本判不了。
#   所以 -Strict 只追加一条机械判据：被指行如果是纯符号行（只剩 } 或 ]; 之类），
#   几乎必然是漂移的产物。默认关闭，因为「指向一行闭括号」偶尔是有意的。
#
# 退出码（与 verify-all.ps1 的三态口径一致）：
#   0 = 全绿
#   1 = 有失效项 —— 必须处理
#   2 = 无失效，但有平移项 —— 可继续，但要知道文档行号已经不实
#
# 用法：
#   pwsh -File gates\check-doc-refs.ps1                  # 默认两份文档
#   pwsh -File gates\check-doc-refs.ps1 -Strict          # 追加「纯符号行」可疑判定
#   pwsh -File gates\check-doc-refs.ps1 -Detail          # 同时列出被跳过的条目
#   pwsh -File gates\check-doc-refs.ps1 -NoGit           # 不用 git 文本锚点，只出候选
#   pwsh -File gates\check-doc-refs.ps1 -Format json     # 机读输出

param(
    [string]$Root,                                      # 仓库根；默认取 gates/ 的上一级
    [string[]]$Docs,                                    # 要校验的文档（相对仓库根）
    [switch]$Strict,                                    # 追加「纯符号行」可疑判定
    [int]$Window = 40,                                  # 邻近非空行的搜索半径
    [switch]$NoGit,                                     # 关闭 git 文本锚点
    [switch]$Detail,                                    # 列出被跳过的条目
    [ValidateSet('table', 'json')][string]$Format = 'table'
)

$ErrorActionPreference = 'Stop'

if (-not $Root) {
    if ($PSScriptRoot) { $Root = Split-Path -Parent $PSScriptRoot }
    else { $Root = (Get-Location).Path }
}
$Root = (Resolve-Path -LiteralPath $Root).Path

if (-not $Docs -or $Docs.Count -eq 0) {
    $Docs = @('docs/简历条目索引.md', 'docs/面试问答手册.md')
}

# 台账文件的相对路径：文档里形如「台账 C-01（`:57`）」的裸行号引用一律指它。
$LedgerRel = 'docs/已知偏差台账-2026-10-15.md'

# ---------------------------------------------------------------- 仓库文件索引

# 运行产物与依赖目录不参与解析：文档约定引用只指向仓库内的制品。
# 按路径段排除而不是只看顶层 —— 模块下的 llmall-xxx/target 里也有同名 .class，
# 不排除会让「去扩展名唯一」全部退化成歧义。
$SkipSegs = @('.git', 'target', 'node_modules', '.idea', '.mvn', '.gradle', 'logs', 'tmp')

$AllFiles = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath $Root -Recurse -File -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $rel = $_.FullName.Substring($Root.Length + 1).Replace('\', '/')
    $segs = $rel.Split('/')
    $skip = $false
    for ($i = 0; $i -lt $segs.Count - 1; $i++) {
        if ($SkipSegs -contains $segs[$i]) { $skip = $true; break }
    }
    if (-not $skip) { $AllFiles.Add($rel) }
}

$ByRel = @{}
$ByName = @{}
$ByStem = @{}
foreach ($rel in $AllFiles) {
    $ByRel[$rel.ToLowerInvariant()] = $rel
    $cut = $rel.LastIndexOf('/')
    $bn = $(if ($cut -ge 0) { $rel.Substring($cut + 1) } else { $rel }).ToLowerInvariant()
    if (-not $ByName.ContainsKey($bn)) { $ByName[$bn] = New-Object System.Collections.Generic.List[string] }
    $ByName[$bn].Add($rel)

    # 主干索引：去掉最后一段扩展名后的文件名。文档里大量缩写写法只写主干
    # （content/FollowServiceImpl 指 FollowServiceImpl.java），靠它定位。
    $dot = $bn.LastIndexOf('.')
    $stem = $(if ($dot -gt 0) { $bn.Substring(0, $dot) } else { $bn })
    if (-not $ByStem.ContainsKey($stem)) { $ByStem[$stem] = New-Object System.Collections.Generic.List[string] }
    $ByStem[$stem].Add($rel)
}

# 把文档里的路径写法解析成仓库内的真实相对路径。
# 依次尝试：精确相对路径 → 通配（省略号展开）→ 文件名唯一。
# 返回 Ok=$false 时，Reason 只会是「文件不存在」或「歧义」。
function Resolve-RepoPath([string]$raw) {
    $p = $raw.Replace('\', '/').Trim()
    # 文档约定：路径相对仓库根 llmall/，个别行写成 llmall/xxx 也按仓库根理解
    if ($p.StartsWith('llmall/')) { $p = $p.Substring(7) }
    while ($p.StartsWith('./')) { $p = $p.Substring(2) }

    # 省略号（中文 … 或三个点）表示「中间层级省略」，展开成通配符再匹配；
    # 通配只在命中唯一时才认 —— 命中多份说明这段省略不足以定位。
    $wild = $p.Replace('...', '*').Replace('…', '*')
    if ($wild.Contains('*')) {
        $hits = @($AllFiles | Where-Object { $_ -like $wild })
        if ($hits.Count -eq 1) { return [pscustomobject]@{ Ok = $true; Rel = $hits[0]; How = '通配' } }
        if ($hits.Count -gt 1) {
            return [pscustomobject]@{ Ok = $false; Reason = '歧义'; Detail = ('省略路径命中 {0} 份：{1}' -f $hits.Count, (($hits | Select-Object -First 3) -join '、')) }
        }
        return [pscustomobject]@{ Ok = $false; Reason = '文件不存在'; Detail = $p }
    }

    if ($ByRel.ContainsKey($p.ToLowerInvariant())) {
        return [pscustomobject]@{ Ok = $true; Rel = $ByRel[$p.ToLowerInvariant()]; How = '精确' }
    }

    # 缩写写法（pay/PayOrderServiceImpl.java 之类，目录层级被省掉）：靠文件名定位
    $cut = $p.LastIndexOf('/')
    $bn = $(if ($cut -ge 0) { $p.Substring($cut + 1) } else { $p }).ToLowerInvariant()
    if ($ByName.ContainsKey($bn)) {
        $cand = @($ByName[$bn])
        if ($cand.Count -eq 1) { return [pscustomobject]@{ Ok = $true; Rel = $cand[0]; How = '文件名唯一' } }

        # 同名多份时，如果引用本身带了目录前缀，就用目录段消歧：
        # trade-service/application.yaml 里 trade-service 按后缀对上 llmall-trade-service，
        # 于是不必写全 llmall-trade-service/src/main/resources/application.yaml 那么长。
        # 只在"文件名已经撞车"这一支里启用，不影响其余引用的解析路径；
        # 消歧后仍多份就还是歧义 —— 宁可报判不了，也不替人挑一个。
        if ($cut -ge 0) {
            $dirSeg = ($p.Substring(0, $cut) -split '/')[-1].ToLowerInvariant()
            if ($dirSeg -ne '') {
                $filtered = @($cand | Where-Object {
                    $segs = $_.ToLowerInvariant().Split('/')
                    $found = $false
                    for ($k = 0; $k -lt $segs.Count - 1; $k++) {
                        if ($segs[$k].EndsWith($dirSeg)) { $found = $true; break }
                    }
                    $found
                })
                if ($filtered.Count -eq 1) { return [pscustomobject]@{ Ok = $true; Rel = $filtered[0]; How = '目录后缀消歧' } }
                if ($filtered.Count -gt 1) {
                    return [pscustomobject]@{ Ok = $false; Reason = '歧义'; Detail = ('目录后缀 {0} 消歧后仍命中 {1} 份：{2}' -f $dirSeg, $filtered.Count, (($filtered | Select-Object -First 3) -join '、')) }
                }
            }
        }

        return [pscustomobject]@{ Ok = $false; Reason = '歧义'; Detail = ('同名文件 {0} 份：{1}' -f $cand.Count, (($cand | Select-Object -First 3) -join '、')) }
    }

    return [pscustomobject]@{ Ok = $false; Reason = '文件不存在'; Detail = $p }
}

# 不带扩展名的写法走这里：先补 .md（README 这种），再按主干唯一匹配
# （content/FollowServiceImpl 指 FollowServiceImpl.java）。都失败就交回调用方按「判不了」处理。
function Resolve-LoosePath([string]$raw) {
    $p = $raw.Replace('\', '/').Trim()
    $r = Resolve-RepoPath ($p + '.md')
    if ($r.Ok) { return $r }

    $cut = $p.LastIndexOf('/')
    $seg = $(if ($cut -ge 0) { $p.Substring($cut + 1) } else { $p }).ToLowerInvariant()
    if ($ByStem.ContainsKey($seg)) {
        $cand = @($ByStem[$seg])
        if ($cand.Count -eq 1) { return [pscustomobject]@{ Ok = $true; Rel = $cand[0]; How = '去扩展名唯一' } }
        return [pscustomobject]@{ Ok = $false; Reason = '歧义'; Detail = ('同主干文件 {0} 份：{1}' -f $cand.Count, (($cand | Select-Object -First 3) -join '、')) }
    }

    # 目录加前缀的缩写（docs/P-02 指 docs/P-02-…md）：按前缀唯一匹配
    if ($p.Contains('/')) {
        $pre = $p.ToLowerInvariant()
        $hits = @($AllFiles | Where-Object { $_.ToLowerInvariant().StartsWith($pre) })
        if ($hits.Count -eq 1) { return [pscustomobject]@{ Ok = $true; Rel = $hits[0]; How = '前缀唯一' } }
        if ($hits.Count -gt 1) {
            return [pscustomobject]@{ Ok = $false; Reason = '歧义'; Detail = ('前缀命中 {0} 份：{1}' -f $hits.Count, (($hits | Select-Object -First 3) -join '、')) }
        }
    }

    return [pscustomobject]@{ Ok = $false; Reason = '无扩展名'; Detail = $p }
}

# ---------------------------------------------------------------- 文件内容缓存

$LineCache = @{}
function Get-FileLines([string]$rel) {
    if ($LineCache.ContainsKey($rel)) { return $LineCache[$rel] }
    $full = Join-Path $Root $rel
    $ok = $true
    $arr = @()
    try { $arr = @([System.IO.File]::ReadAllLines($full, [System.Text.Encoding]::UTF8)) }
    catch { $ok = $false; $arr = @() }
    $entry = [pscustomobject]@{ Ok = $ok; Lines = $arr }
    $LineCache[$rel] = $entry
    return $entry
}

# ---------------------------------------------------------------- git 文本锚点

# 这些外部命令失败是常态（无 git / 文件未跟踪），失败一律降级为「取不到锚点」，
# 不能让闸门本身抛错 —— 闸门自己崩掉比报红更糟。
$GitOk = $false
if (-not $NoGit) {
    try {
        $null = & git -C $Root rev-parse --is-inside-work-tree 2>$null
        $GitOk = ($LASTEXITCODE -eq 0)
    }
    catch { $GitOk = $false }
}

$DocCommitCache = @{}
function Get-DocCommit([string]$docRel) {
    if ($DocCommitCache.ContainsKey($docRel)) { return $DocCommitCache[$docRel] }
    $h = $null
    if ($GitOk) {
        try {
            $out = & git -C $Root log -1 --format=%H -- $docRel 2>$null
            if ($LASTEXITCODE -eq 0 -and $out) { $h = ([string]($out | Select-Object -First 1)).Trim() }
        }
        catch { $h = $null }
    }
    $DocCommitCache[$docRel] = $h
    return $h
}

$BlobCache = @{}
function Get-LinesAtCommit([string]$commit, [string]$rel) {
    $key = $commit + '|' + $rel
    if ($BlobCache.ContainsKey($key)) { return $BlobCache[$key] }
    $arr = $null
    if ($GitOk) {
        try {
            $txt = & git -C $Root show ($commit + ':' + $rel) 2>$null
            if ($LASTEXITCODE -eq 0 -and $null -ne $txt) { $arr = @($txt -split "`r?`n") }
        }
        catch { $arr = $null }
    }
    $BlobCache[$key] = $arr
    return $arr
}

$DocLineCache = @{}
# 文档末次提交时某一行的原文。用来判断「这条引用本身是不是刚被改过」：
# 改过的引用不能再拿旧行号做锚点 —— 旧行号上的内容已经被作者有意识地换掉了。
function Get-DocLineAtCommit([string]$docRel, [int]$docLine) {
    $commit = Get-DocCommit $docRel
    if (-not $commit) { return $null }
    $key = $commit + '|' + $docRel
    if (-not $DocLineCache.ContainsKey($key)) { $DocLineCache[$key] = Get-LinesAtCommit $commit $docRel }
    $lines = $DocLineCache[$key]
    if (-not $lines -or $docLine -lt 1 -or $docLine -gt $lines.Count) { return $null }
    return [string]$lines[$docLine - 1]
}

# 在一份文件的行里找某段文本，多个命中时取离 fromLine 最近的。
function Find-AnchorLine($anchorText, $lines, [int]$fromLine) {
    $t = ([string]$anchorText).Trim()
    if ($t.Length -lt 6) { return 0 }        # 太短的行当不了锚点（空括号、单个词到处都是）
    $best = 0
    $bestD = [int]::MaxValue
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if (([string]$lines[$i]).Trim() -eq $t) {
            $d = [Math]::Abs(($i + 1) - $fromLine)
            if ($d -lt $bestD) { $bestD = $d; $best = $i + 1 }
        }
    }
    return $best
}

# 在一份文件里找一段「整块迁移」的内容：给定成文时的连续行（可能含空行），返回它现在
# 起始于第几行；找不到返回 0。做法是拿最长的一行当锚点，再用其余各行逐行校验 ——
# 只认整块对上的位置，近似匹配一律不返回，宁可不报也不给假的新行号。
function Find-BlockShift($oldSpan, $lines) {
    $norm = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt $oldSpan.Count; $i++) {
        $t = ([string]$oldSpan[$i]).Trim()
        if ($t -ne '') { $null = $norm.Add([pscustomobject]@{ Off = $i; Text = $t }) }
    }
    if ($norm.Count -eq 0) { return 0 }

    $anchor = $norm[0]
    foreach ($p in $norm) { if ($p.Text.Length -gt $anchor.Text.Length) { $anchor = $p } }
    if ($anchor.Text.Length -lt 6) { return 0 }

    for ($s = 1; $s -le $lines.Count; $s++) {
        if (([string]$lines[$s - 1]).Trim() -ne $anchor.Text) { continue }
        $start = $s - $anchor.Off
        if ($start -lt 1) { continue }
        $ok = $true
        foreach ($p in $norm) {
            $pos = $start + $p.Off
            if ($pos -lt 1 -or $pos -gt $lines.Count) { $ok = $false; break }
            if (([string]$lines[$pos - 1]).Trim() -ne $p.Text) { $ok = $false; break }
        }
        if ($ok) { return $start }
    }
    return 0
}

# 行号已经指不到内容时，判断是「漂移」「成文即空」还是「失效」。
# 返回 Kind 的三种取值：
#   平移     —— 新行号有依据（How=文本锚点最强，How=邻近候选只是候选）
#   成文即空 —— 文档末次提交时该行同样为空，说明这条引用从一开始就没指到东西，不是漂移
#   失效     —— 什么都锚不到
# 这个区分很重要：把「成文即空」当成「平移」会让人以为改个行号就好了，
# 实际上那条引用想说什么只有作者知道。
# SkipOld：这条引用所在行刚被作者改过，旧行号上的内容已经不再代表作者的意图，
# 一档锚点与「成文即空」都不能用，只能退到邻近候选或直接判失效。
function Resolve-Drift([string]$docRel, [string]$refRel, [int]$line, $lines, [bool]$SkipOld) {
    $total = $lines.Count
    $oldBlank = $false
    $oldTotal = 0

    # 一档：用文档最后一次提交时的同一行做文本锚点，在当前文件里反查新位置。
    # 这一档给出的旧→新是可证伪的，不是猜的。
    $commit = Get-DocCommit $docRel
    if ($commit -and -not $SkipOld) {
        $old = Get-LinesAtCommit $commit $refRel
        if ($old -and $line -le $old.Count) {
            $oldTotal = $old.Count
            $t = [string]$old[$line - 1]
            if ($t.Trim() -ne '') {
                $new = Find-AnchorLine $t $lines $line
                if ($new -gt 0 -and $new -ne $line) {
                    return [pscustomobject]@{ Kind = '平移'; New = $new; How = '文本锚点'; Note = ('文档末次提交 ' + $commit.Substring(0, 7) + ' 时该行：' + $t.Trim()) }
                }
            }
            else { $oldBlank = $true }
        }
    }

    # 二档：只取最近的邻近非空行，够不上证据，只能算候选。
    # 行号越界时不给候选 —— 从越界位置往回收，收回来的是文件尾巴，没有参考意义。
    $near = 0
    if ($line -le $total) {
        for ($d = 1; $d -le $Window; $d++) {
            foreach ($c in @(($line - $d), ($line + $d))) {
                if ($c -ge 1 -and $c -le $total) {
                    if (([string]$lines[$c - 1]).Trim() -ne '') { $near = $c; break }
                }
            }
            if ($near -gt 0) { break }
        }
    }

    if ($oldBlank) {
        $note = ('文档末次提交 ' + $commit.Substring(0, 7) + ' 时该文件共 ' + $oldTotal + ' 行，第 ' + $line + ' 行同样是空行')
        if ($near -gt 0) { $note = $note + ('；附近第 ' + $near + ' 行有内容，但只能算线索') }
        return [pscustomobject]@{ Kind = '成文即空'; New = $near; How = '成文即空'; Note = $note }
    }

    if ($near -gt 0) {
        return [pscustomobject]@{ Kind = '平移'; New = $near; How = '邻近候选'; Note = ('取不到文本锚点时的最近非空行，Δ=' + ($near - $line)) }
    }

    return [pscustomobject]@{ Kind = '失效'; New = 0; How = ''; Note = '' }
}

# ---------------------------------------------------------------- 引用提取

# 只有带 :行 的才算引用；纯文件名不归本闸门管。
# 边界组 (?<b>...) 让路径只能从分隔符之后开始 —— 否则中文正文会和路径粘在一起
# （docs/设计文档-v1.1.md 这类写法，没有边界锚点会把前面几个汉字也吃进路径）。
# 取的是「冒号前的整段」而不是「最后一个点之后的扩展名」，因为省略号路径
# （docs/P-01-...md）和中文文件名都必须原样留下，交给 Resolve-RepoPath 展开。
$RefRx = [regex]'(?<b>^|[\s`|>*（(\[【，,；;、])[`"'']?(?<tok>[^\s`|<>（）()\[\]【】，,；;、：:''"]+):(?<l1>\d+)(?:-(?<l2>\d+))?'

# 令牌是否带扩展名。带扩展名的引用找不到文件算失效；不带扩展名的（README、Dockerfile、
# 正文里的 item:20 之类）走「宽松解析」，仍然解析不出来只算判不了。
# 这里用白名单而不是「点加几个字母」：否则 192.168.189.128:8091 这种主机端口会被当成文件。
$ExtRx = [regex]'[.…](java|ps1|yaml|yml|md|json|xml|html|sql|lua|properties|py|sh|cmd|bat|txt|jks|env|vmoptions|js|css|tpl|ftl|feature|conf|example)$'

# 手册开头的记号表把两个短名声明成固定指向。新增别名必须同步加在这里，
# 否则该引用会落到「无扩展名」的跳过项里 —— 漏校验但不报红，这是本闸门唯一的静默风险点。
$AliasMap = @{
    '台账'  = $LedgerRel
    'bench' = 'benchmark/README.md'
}

# 同一路径后接「、311-324」这种并列行号：一次匹配只能吃到一个，其余的在这里补。
$MoreRx = [regex]'^[、，,]\s*(?<l1>\d+)(?:-(?<l2>\d+))?'

# 台账裸行号引用：「台账 C-01（`:57`）」这种没有文件名的写法，一律指台账文件。
$LedgerRx = [regex]'台账[^`\n]{0,24}`\s*:\s*(?<l1>\d+)(?:-(?<l2>\d+))?'

# ---------------------------------------------------------------- 校验

$Checked = 0
$Pass = 0
$Anchored = 0          # 内容与成文时逐行一致 —— 强通过
$RangeCount = 0        # 区间引用条数（其余为单行引用）
$Drift = New-Object System.Collections.ArrayList
$Fail = New-Object System.Collections.ArrayList
$Skips = New-Object System.Collections.ArrayList

function Add-Ref([string]$docRel, [int]$docLine, [string]$raw, [int]$l1, [int]$l2, [bool]$hasExt, [bool]$justEdited) {
    $script:Checked++

    $label = $raw + ':' + $l1
    if ($l2 -gt 0) { $label = $label + '-' + $l2 }

    # 文档自己声明 logs/ 与 tmp/ 是不入库的运行产物，这两处的引用无法在仓库内证实。
    # 判不了就如实归到「跳过」，不能冒充失效，也不能装作通过。
    if (($raw -replace '\\', '/') -match '^(\./)*(logs|tmp)/') {
        $null = $script:Skips.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Why = '运行产物不入库'; Detail = '按文档约定 logs/ tmp/ 不入库，本闸门无从校验' })
        return
    }

    $res = Resolve-RepoPath $raw
    if (-not $res.Ok) {
        $loose = ($raw -replace '\\', '/').Trim()
        if ($AliasMap.ContainsKey($loose)) { $res = Resolve-RepoPath $AliasMap[$loose] }
    }
    if (-not $res.Ok -and -not $hasExt) {
        $res = Resolve-LoosePath $raw
        # 缩写在正文里常和汉字粘在一起（容量数字见台账:158），逐个剥掉开头的非 ASCII 字再试
        $cand = ($raw -replace '\\', '/').Trim()
        while (-not $res.Ok -and $cand.Length -gt 1 -and [int][char]$cand[0] -gt 127) {
            $cand = $cand.Substring(1)
            $res = Resolve-RepoPath $cand
            if (-not $res.Ok -and $AliasMap.ContainsKey($cand)) { $res = Resolve-RepoPath $AliasMap[$cand] }
            if (-not $res.Ok) { $res = Resolve-LoosePath $cand }
        }
    }

    if (-not $res.Ok) {
        if ($res.Reason -eq '歧义') {
            $null = $script:Skips.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Why = '歧义'; Detail = $res.Detail })
            return
        }
        if (-not $hasExt) {
            $null = $script:Skips.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Why = '无扩展名'; Detail = '正文里的非文件引用，判不了' })
            return
        }
        $null = $script:Fail.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $raw; Kind = '失效·文件不存在'; New = 0; How = ''; Note = $res.Detail })
        return
    }

    $entry = Get-FileLines $res.Rel
    if (-not $entry.Ok) {
        $null = $script:Fail.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Kind = '失效·无法读取'; New = 0; How = ''; Note = '' })
        return
    }

    $lines = $entry.Lines
    $total = $lines.Count

    if ($l2 -gt 0 -and $l2 -lt $l1) {
        $null = $script:Fail.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Kind = '失效·区间倒置'; New = 0; How = ''; Note = ('起点 {0} 大于终点 {1}' -f $l1, $l2) })
        return
    }

    # 越界：内容只可能往上挪，所以只认文本锚点，不接受邻近候选
    if ($l1 -gt $total -or ($l2 -gt 0 -and $l2 -gt $total)) {
        $d = Resolve-Drift $docRel $res.Rel $l1 $lines $justEdited
        if ($d.Kind -eq '平移' -and $d.How -eq '文本锚点') {
            $null = $script:Drift.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Old = $l1; New = $d.New; How = $d.How; Note = $d.Note })
        }
        elseif ($d.Kind -eq '成文即空') {
            $null = $script:Fail.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Kind = '疑似失效·成文即越界'; New = $d.New; How = $d.How; Note = $d.Note })
        }
        else {
            $null = $script:Fail.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Kind = '失效·行号越界'; New = 0; How = ''; Note = ('文件现共 {0} 行' -f $total) })
        }
        return
    }

    # 空行判定：单行要求非空；区间要求至少一行非空
    $nonEmpty = $false
    if ($l2 -eq 0) {
        $nonEmpty = (([string]$lines[$l1 - 1]).Trim() -ne '')
    }
    else {
        for ($i = $l1; $i -le $l2; $i++) {
            if (([string]$lines[$i - 1]).Trim() -ne '') { $nonEmpty = $true; break }
        }
    }

    if (-not $nonEmpty) {
        $d = Resolve-Drift $docRel $res.Rel $l1 $lines $justEdited
        if ($d.Kind -eq '平移') {
            $null = $script:Drift.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Old = $l1; New = $d.New; How = $d.How; Note = $d.Note })
        }
        elseif ($d.Kind -eq '成文即空') {
            $null = $script:Fail.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Kind = '疑似失效·成文即为空行'; New = $d.New; How = $d.How; Note = $d.Note })
        }
        else {
            $null = $script:Fail.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Kind = '失效·区间无内容'; New = 0; How = ''; Note = ('线索 {0} 行内无任何非空行' -f $Window) })
        }
        return
    }

    # -Strict 才追加的机械判据：整行只剩闭合符号 / 分号 / 逗号
    if ($Strict -and $l2 -eq 0) {
        if (([string]$lines[$l1 - 1]) -match '^\s*[}\)\];,]+\s*$') {
            $d = Resolve-Drift $docRel $res.Rel $l1 $lines $justEdited
            if ($d.Kind -eq '平移') {
                $null = $script:Drift.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Old = $l1; New = $d.New; How = $d.How + '·纯符号行'; Note = ((('该行只有闭合符号：' + ([string]$lines[$l1 - 1]).Trim())) + '；' + $d.Note) })
                return
            }
        }
    }

    # 内容档：与文档末次提交时的同一区间逐行比对。
    # 结构档只能保证「行号落在文件内且该处非空」，抓不到「内容整体搬走、而旧行号恰好还落在
    # 有效位置」—— 区间引用最容易出这种问题：代码整体上移一段，旧行号依然有内容，
    # 结构档全绿，可它已经指到另一段代码了。
    # 但这条判据只对「作者没动过的引用」成立：引用本身刚被改过时，旧行号上的内容已经不是
    # 作者想指的东西，拿它做锚点必然误报，所以直接跳过内容档，只留结构档。
    $anchored = $false
    $lEnd = $(if ($l2 -gt 0) { $l2 } else { $l1 })
    $commit = Get-DocCommit $docRel
    if ($commit -and -not $justEdited) {
        $old = Get-LinesAtCommit $commit $res.Rel
        if ($old -and $lEnd -le $old.Count) {
            $oldSpan = @()
            $curSpan = @()
            for ($i = $l1; $i -le $lEnd; $i++) {
                $oldSpan += ([string]$old[$i - 1]).Trim()
                $curSpan += ([string]$lines[$i - 1]).Trim()
            }
            if (($oldSpan -join "`n") -eq ($curSpan -join "`n")) {
                $anchored = $true
            }
            else {
                $newStart = Find-BlockShift $oldSpan $lines
                if ($newStart -gt 0 -and $newStart -ne $l1) {
                    $null = $script:Drift.Add([pscustomobject]@{ Doc = $docRel; DocLine = $docLine; Ref = $label; Target = $res.Rel; Old = $l1; New = $newStart; How = '整块迁移'; Note = ('与文档末次提交 ' + $commit.Substring(0, 7) + ' 时的内容逐行一致，现在整体起于第 ' + $newStart + ' 行') })
                    return
                }
            }
        }
    }

    if ($anchored) { $script:Anchored++ }
    if ($l2 -gt 0) { $script:RangeCount++ }
    $script:Pass++
}

foreach ($docRel in $Docs) {
    $full = Join-Path $Root $docRel
    if (-not (Test-Path -LiteralPath $full)) {
        Write-Host ('文档不存在：' + $docRel) -ForegroundColor Red
        exit 1
    }
    $docLines = [System.IO.File]::ReadAllLines($full, [System.Text.Encoding]::UTF8)

    for ($i = 0; $i -lt $docLines.Count; $i++) {
        $text = [string]$docLines[$i]
        $docLine = $i + 1

        # 这一行自文档末次提交以来是否被改过：改过的行上，旧行号不再代表作者意图。
        # 取不到旧版本（新文件 / 超出旧文件长度）时按"没改过"处理，此时也没有锚点可用。
        $justEdited = $false
        $oldDocLine = Get-DocLineAtCommit $docRel $docLine
        if ($null -ne $oldDocLine -and $oldDocLine -ne $text) { $justEdited = $true }

        foreach ($m in $RefRx.Matches($text)) {
            $tok = $m.Groups['tok'].Value.Trim('*')
            $hasExt = $ExtRx.IsMatch($tok)
            $l1 = [int]$m.Groups['l1'].Value
            $l2 = $(if ($m.Groups['l2'].Success) { [int]$m.Groups['l2'].Value } else { 0 })
            Add-Ref $docRel $docLine $tok $l1 $l2 $hasExt $justEdited

            # 补 「、311-324」 这类并列行号
            $tail = $text.Substring($m.Index + $m.Length)
            $more = $MoreRx.Match($tail)
            while ($more.Success) {
                $m1 = [int]$more.Groups['l1'].Value
                $m2 = $(if ($more.Groups['l2'].Success) { [int]$more.Groups['l2'].Value } else { 0 })
                Add-Ref $docRel $docLine $tok $m1 $m2 $hasExt $justEdited
                $tail = $tail.Substring($more.Length)
                $more = $MoreRx.Match($tail)
            }
        }

        foreach ($m in $LedgerRx.Matches($text)) {
            $l1 = [int]$m.Groups['l1'].Value
            $l2 = $(if ($m.Groups['l2'].Success) { [int]$m.Groups['l2'].Value } else { 0 })
            # 已经是完整路径的「…台账…md:158」由上面的规则覆盖，这里只处理裸行号
            $pre = $text.Substring(0, $m.Index + 2)
            if ($pre -match '台账[\w\-]*\.md\s*$') { continue }
            Add-Ref $docRel $docLine $LedgerRel $l1 $l2 $true $justEdited
        }
    }
}

# ---------------------------------------------------------------- 输出

if ($Format -eq 'json') {
    $payload = [pscustomobject]@{
        统计 = [pscustomobject]@{ 引用总数 = $Checked; 通过 = $Pass; 锚定一致 = $Anchored; 区间引用 = $RangeCount; 平移 = $Drift.Count; 失效 = $Fail.Count; 跳过 = $Skips.Count }
        失效 = @($Fail)
        平移 = @($Drift)
        跳过 = @($Skips)
    }
    $payload | ConvertTo-Json -Depth 6
    if ($Fail.Count -gt 0) { exit 1 }
    if ($Drift.Count -gt 0) { exit 2 }
    exit 0
}

Write-Host ''
Write-Host ('=' * 74) -ForegroundColor DarkGray
Write-Host '文档引用校验（第 8 道闸门）' -ForegroundColor Cyan
Write-Host ('=' * 74) -ForegroundColor DarkGray
Write-Host ('  仓库根   : ' + $Root)
Write-Host ('  校验文档 : ' + ($Docs -join '、'))
Write-Host ('  git 锚点 : ' + $(if ($GitOk) { '可用（失败项优先用文本锚点定位新行号）' } else { '不可用（只出候选行号）' }))
Write-Host ('  严格模式 : ' + $(if ($Strict) { '开（追加纯符号行判定）' } else { '关' }))
Write-Host ''

if ($Fail.Count -gt 0) {
    Write-Host ('失效 ' + $Fail.Count + ' 项 —— 这些引用已经指不到东西，需要人判断怎么处理：') -ForegroundColor Red
    foreach ($f in $Fail) {
        Write-Host ('  [' + $f.Kind + '] ' + $f.Doc + ':' + $f.DocLine)
        Write-Host ('      引用：' + $f.Ref)
        if ($f.Note) { Write-Host ('      说明：' + $f.Note) -ForegroundColor DarkGray }
    }
    Write-Host ''
}

if ($Drift.Count -gt 0) {
    Write-Host ('平移 ' + $Drift.Count + ' 项 —— 内容还在、行号漂了。下面是新行号，确认后由人改文档：') -ForegroundColor Yellow
    foreach ($d in $Drift) {
        Write-Host ('  ' + $d.Doc + ':' + $d.DocLine + '  ' + $d.Ref + '  →  ' + $d.Target + ':' + $d.New + '  (Δ' + $(if (($d.New - $d.Old) -ge 0) { '+' } else { '' }) + ($d.New - $d.Old) + '，' + $d.How + ')')
        if ($d.Note) { Write-Host ('      ' + $d.Note) -ForegroundColor DarkGray }
    }
    Write-Host ''
}

if ($Skips.Count -gt 0) {
    $byWhy = $Skips | Group-Object Why
    Write-Host ('跳过 ' + $Skips.Count + ' 项 —— 本闸门判不了（' + (($byWhy | ForEach-Object { $_.Name + ' ' + $_.Count }) -join '，') + '）：') -ForegroundColor DarkGray
    if ($Detail) {
        foreach ($s in $Skips) {
            Write-Host ('  [' + $s.Why + '] ' + $s.Doc + ':' + $s.DocLine + '  ' + $s.Ref) -ForegroundColor DarkGray
            if ($s.Detail) { Write-Host ('      ' + $s.Detail) -ForegroundColor DarkGray }
        }
    }
    else { Write-Host '  （加 -Detail 看具体条目）' -ForegroundColor DarkGray }
    Write-Host ''
}

Write-Host ('  ' + ('-' * 68)) -ForegroundColor DarkGray
Write-Host ('  引用总数 ' + $Checked + ' ｜ 通过 ' + $Pass + ' ｜ 平移 ' + $Drift.Count + ' ｜ 失效 ' + $Fail.Count + ' ｜ 跳过 ' + $Skips.Count)
Write-Host ('  通过分两档：与成文时逐行一致 ' + $Anchored + ' 条（强通过）；只保证行号有效 ' + ($Pass - $Anchored) + ' 条') -ForegroundColor DarkGray
Write-Host ('  另外，通过项里有 ' + $RangeCount + ' 条是区间引用：区间只验「区间内有内容」，指到的究竟是不是那段代码要靠人看') -ForegroundColor DarkGray
Write-Host ''

if ($Fail.Count -gt 0) {
    Write-Host '结论: 有失效引用 —— 先判断是文档写错了还是被引用的内容没了' -ForegroundColor Red
    exit 1
}
if ($Drift.Count -gt 0) {
    Write-Host '结论: 无失效，行号需人工平移（本闸门不改文档）' -ForegroundColor Yellow
    exit 2
}
Write-Host '结论: 全部引用有效' -ForegroundColor Green
exit 0
