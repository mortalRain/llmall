# 注释内容质量闸门：扫描 Java / YAML 注释里"不该再出现"的表述。
#
# 指纹闸门（gate.ps1）只能证明"没改代码"，
# 本脚本负责证明"该删的确实删干净了" —— 两者互补，缺一不可。
#
# 用法:
#   pwsh -File gates\lint-comments.ps1            # 全项目汇总
#   pwsh -File gates\lint-comments.ps1 -Detail    # 逐条列出
#   pwsh -File gates\lint-comments.ps1 -OnlyProcessed  # 只看注释行数低于现行基线的文件（见下方说明）
#
# 【-OnlyProcessed 的口径】它原本比对的是第一轮优化**之前**的基线（java.baseline.txt /
# yaml.baseline.txt），用来挑出"这一轮已经精修过"的文件。那两份基线属于历史快照，不入库；
# 现在比对的是现行基线 baseline\*.round2.txt，因此正常情况下选不出文件（当前状态就是基线本身）。
# 该开关保留是为了兼容 2026-10-14 那轮的调用方式，不是日常使用路径。
param(
    [switch]$Detail,
    [switch]$OnlyProcessed,
    [string[]]$Path,     # 只扫指定文件（相对仓库根的路径）；默认扫全项目。selftest.ps1 用它做规则自校验。
    [string]$Root        # 仓库根；默认取 gates/ 的上一级。**必须放在最后**，否则会抢走位置参数
)

. "$PSScriptRoot\gate-env.ps1"
if ($Root) { $RepoRoot = (Resolve-Path $Root).Path }
$root = $RepoRoot
$baseDir = Join-Path $GateRoot 'baseline'

# 规则：正则在"注释内容"上匹配。逐行判断该行是否为注释行或含行尾注释，只对注释部分取词。
$rules = [ordered]@{
    'HTML标签'      = '<\s*/?\s*(h[1-6]|p|ul|ol|li|b|i|strong|em|pre|br|code|table|tr|td|div|span)\b'
    # `改后` 必须用 (?<!误) 守卫：规范推荐的措辞「误改后果」里含 `改后`，会被误判成历史叙述 ——
    # 而这条措辞恰恰是本轮要求每个风险点都要写的。带误报的闸门会逼着执行者绕开正确写法（实测已发生）。
    '历史叙述'      = '改前|(?<!误)改后(?!需|要)|原先|原实现|原代码|原写法|课程原版|本次修复|本次改动|历史遗留|以前是|第一版|原来的实现'
    '实测叙述'      = '实测|联机检查|复现验证|连跑|跑了一遍|验证过|打印出来|控制台输出|日志里就能'
    '口语化'        = '手滑|白填|踩过|踩坑|坑点很多|别急着|说白了|实际上就是|一句话的事|救命|要命|非常坑'
    # 装饰横幅：`// ==== 以下是 xxx ====` 这类分隔线。阈值取 4 而不是 6 ——
    # 4 个以上连续符号的装饰线在语义上同样是横幅（本仓库 4~5 个字符的命中为 0，收紧无副作用）。
    '装饰横幅'      = '(//|#)\s*[=\-*]{4,}'
    # 粗体标记必须带 (?<!/) (?!/) 守卫：否则会把三种非 Markdown 的东西误判为违规 ——
    #   ① Javadoc 起始符 `/**`（`/** 脱敏格式：138****1234 */` 里的 `**` 会被当成一对强调）
    #   ② AntPathMatcher 的 glob 通配符 `/items/**`、`/actuator/**`
    #   ③ 手机号掩码 `138****1234`
    # 清理脚本用的是同一条正则，闸门与清理口径必须一致，否则会诱导出不存在的"违规"。
    '粗体标记'      = '(?<!/)\*\*([^*\r\n]+?)\*\*(?!/)'
    '待办标记'      = 'TODO|FIXME|XXX:|HACK'
    # 扫描 PO/服务类注释时发现的四类残留。
    # 收紧正则的理由：初版把「本次」「本轮」「新增了」也算进来，结果把
    # 「供本次请求的日志使用」「值都是本次调用的唯一凭证」「1=新增了一条」这类正常中文误判为违规。
    # 带误报的闸门比没有闸门更危险 —— 它会诱导出错误的"修复"。
    '审计标签'      = '审计\s*[A-Z]?\d|亮点\s*[A-C]\b|M\d+\s*接入|审计要求|验收清单'
    # `来源：` 要排除 `事实来源：`（后者是"唯一权威数据源"的正常表达，不是"从哪张表迁移过来"）
    '迁移叙述'      = '(?<!事实)来源：|合并来源|合并改造点|改造点：|tb_\w+'
    '变更叙述'      = '新增\s*\w+\s*——|原来是|原来只|现在改为|已改成|不再需要|本次改动|改动前'
    '模板残留'      = '^\*?\s*[\u4e00-\u9fa5]{2,8}(表|服务类|实体类)\s*[。.]?\s*$'
}

function Get-CommentText($line, $isYaml) {
    if ($isYaml) {
        $idx = $line.IndexOf('#')
        if ($idx -lt 0) { return $null }
        return $line.Substring($idx)
    }
    $t = $line.Trim()
    if ($t.StartsWith('//')) { return $t }
    if ($t.StartsWith('/*') -or $t.StartsWith('*')) { return $t }
    $idx = $line.IndexOf('//')
    if ($idx -ge 0) { return $line.Substring($idx) }
    return $null
}

if ($Path -and $Path.Count -gt 0) {
    # 自校验等场景：只扫指定文件（支持绝对路径）
    $files = @($Path | ForEach-Object { Get-Item -LiteralPath $_ -ErrorAction SilentlyContinue } |
        Where-Object { $_ -ne $null })
}
else {
    # gates 目录自身不在本闸门的扫描范围内，理由有两条，都不是"放宽阈值"：
    #   ① 这 11 条规则是为**产品源码的中文注释**定的（禁止把 <h3>核心职责</h3> 这类 HTML 当注释写）。
    #      而闸门工具自身的 JavaDoc 用 <p>/<ul>/<li>/<pre> 是标准 JavaDoc 写法，把它们算违规
    #      只会逼着工具源码放弃 JavaDoc 排版，属于规则用错了地方。
    #   ② 闸门工具的"代码有没有被改"由第 ① 道闸门（CommentFingerprint）覆盖，且 gates 下的
    #      Java 文件**照常纳入 java.round2.txt 基线** —— 只是注释风格这一项不适用。
    # 【可核对】排除前后本闸门扫到的项目文件数完全一致（323 个），项目侧的覆盖范围没有被缩小。
    # 需要单独校验 gates 下的某个文件时，用 -Path 显式指定即可（-Path 模式不受此排除影响）。
    $files = Get-ChildItem $root -Recurse -File -Include *.java,*.yaml,*.yml |
        Where-Object { $_.FullName -notlike '*\target\*' -and $_.FullName -notlike (Join-Path $GateRoot '*') }
}

# 已精修文件清单：注释行数已低于基线的文件
$processed = @{}
if ($OnlyProcessed) {
    $base = @{}
    foreach ($bf in @('java.round2.txt', 'yaml.round2.txt')) {
        $bp = Join-Path $baseDir $bf
        if (-not (Test-Path $bp)) { continue }
        Get-Content -Encoding UTF8 $bp | ForEach-Object {
            $p = $_ -split "`t"; $base[$p[0]] = [int]$p[3]
        }
    }
    foreach ($f in $files) {
        $rel = $f.FullName.Replace("$root\", '').Replace('\', '/')
        if (-not $base.ContainsKey($rel)) { continue }
        $ls = Get-Content -Encoding UTF8 $f.FullName
        $isYaml = $f.Extension -in '.yaml', '.yml'
        $c = 0
        foreach ($l in $ls) {
            $ct = Get-CommentText $l $isYaml
            if ($ct -and $ct.Trim().Length -gt 0) { $c++ }
        }
        if ($c -lt $base[$rel]) { $processed[$rel] = $true }
    }
}

$totals = [ordered]@{}
foreach ($k in $rules.Keys) { $totals[$k] = 0 }
$hits = @()
$scanned = 0
$skipped = 0

foreach ($f in $files) {
    $rel = $f.FullName.Replace("$root\", '').Replace('\', '/')
    if ($OnlyProcessed -and -not $processed.ContainsKey($rel)) { $skipped++; continue }
    $scanned++
    $isYaml = $f.Extension -in '.yaml', '.yml'
    $lineNo = 0
    foreach ($l in (Get-Content -Encoding UTF8 $f.FullName)) {
        $lineNo++
        $ct = Get-CommentText $l $isYaml
        if (-not $ct) { continue }
        foreach ($k in $rules.Keys) {
            if ($ct -match $rules[$k]) {
                $totals[$k]++
                $hits += [pscustomobject]@{ Rule = $k; File = $rel; Line = $lineNo; Text = $ct.Trim() }
            }
        }
    }
}

Write-Output "=== 注释内容质量闸门 ==="
Write-Output "扫描文件 : $scanned$(if($OnlyProcessed){"  （跳过未精修 $skipped）"})"
Write-Output ""
foreach ($k in $totals.Keys) {
    $mark = if ($totals[$k] -eq 0) { 'OK ' } else { '!! ' }
    Write-Output ("{0}{1,-10} {2,5} 处" -f $mark, $k, $totals[$k])
}
$bad = ($totals.Values | Measure-Object -Sum).Sum
Write-Output ""
if ($bad -eq 0) {
    Write-Output "结论: 通过 —— 未发现残留的 HTML 标签 / 历史叙述 / 实测叙述 / 口语化 / 装饰横幅"
} else {
    Write-Output "结论: 待清理 $bad 处"
    if ($Detail) {
        Write-Output ""
        $hits | Sort-Object File, Line | ForEach-Object {
            Write-Output ("  [{0}] {1}:{2}" -f $_.Rule, $_.File, $_.Line)
            Write-Output ("        {0}" -f $_.Text.Substring(0, [Math]::Min(110, $_.Text.Length)))
        }
    } else {
        Write-Output "（加 -Detail 查看逐条位置）"
    }
    # 【对应缺陷#4】这里曾经没有 exit 1：闸门明明打印了「待清理 N 处」，退出码却是 0 ——
    # 于是调用方（verify-all.ps1 等）按退出码判断时会把它当 PASS，注入测试当场抓到了这一点。
    # 凡是"有命中"的闸门都必须让退出码非 0；否则闸门只是在"说话"，不是在"拦人"。
    exit 1
}
