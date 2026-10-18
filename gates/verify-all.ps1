# 单一入口：一次跑完全部分析闸门 + 全量编译
#
# 用法:
#   pwsh -File gates\verify-all.ps1                 # 全部闸门 + 编译
#   pwsh -File gates\verify-all.ps1 -SkipBuild      # 跳过编译（只跑分析闸门，快）
#   pwsh -File gates\verify-all.ps1 -LedgerOnly     # 只看"已登记偏差"类闸门
#   powershell -File gates\verify-all.ps1 -SkipBuild  # 只有 Windows PowerShell 5.1 时同样可用
#
# 环境变量（都不设也能跑，见 gates/README.md 第一节）：
#   JAVA_HOME        JDK 安装目录（不设则从 PATH 上的 java 反推）
#   MAVEN_BIN        mvn 可执行文件全路径（不设则从 PATH 找）
#   MAVEN_SETTINGS   settings.xml 全路径（不设则用 ~/.m2/settings.xml，仍没有就不传 -s）
#
# 退出码语义（**刻意分成三档，避免把「闸门失效」和「已登记偏差」混为一谈**）：
#   0 = 全绿
#   1 = 硬闸门失败 —— 有未登记的问题或闸门本身出错，**必须修**，不得继续
#   2 = 硬闸门全过，但台账闸门报出**已登记**的待处理偏差（见《已知偏差台账》），可继续但要知道
#
# 硬闸门（必须绿）：① Java 指纹 ② YAML 指纹 ③ Lua/SQL/脚本指纹 ④ 内容质量 ⑤ 闸门自校验 ⑥ 文档引用
# 台账闸门（允许红，红的每一条都必须能在台账里找到）：⑦ 字典一致性 ⑧ Feign 一致性

param(
    [switch]$SkipBuild,
    [switch]$LedgerOnly,
    [switch]$Online,                                    # 默认离线编译；没有本地仓库时用 -Online 走网络
    [string]$Root,                                      # 仓库根；默认取 gates/ 的上一级
    [string]$MavenSettings                              # 覆盖 MAVEN_SETTINGS
)

. "$PSScriptRoot\gate-env.ps1"
if ($Root) { $RepoRoot = (Resolve-Path $Root).Path }
if ($MavenSettings) { $env:MAVEN_SETTINGS = $MavenSettings }

$cp = Initialize-GateClasses
$base = Join-Path $GateRoot 'baseline'
$java = Get-GateJava

Write-Host ''
Write-Host '运行环境' -ForegroundColor Cyan
Write-Host ("  仓库根      : " + $RepoRoot)
Write-Host ("  JDK         : " + (Get-GateJavaHome) + "  " + (Get-GateJavaVersion) + "  [主版本 " + (Get-GateJavaMajorVersion) + "，支持区间 11~17]")
Write-Host ("  PowerShell  : " + $PSVersionTable.PSVersion + " (" + $PSVersionTable.PSEdition + ")  " + (Get-GatePowerShell))
$mvnShown = Get-GateMaven
if ($mvnShown) { Write-Host ("  Maven       : " + $mvnShown) }
else { Write-Host '  Maven       : 未找到 —— 不加 -SkipBuild 时编译段会判为硬闸门失败' -ForegroundColor Yellow }

$hardFail = 0
$ledgerFail = 0
$results = @()

function Section($n, $title) {
    Write-Host ''
    Write-Host ('=' * 74) -ForegroundColor DarkGray
    Write-Host ("【$n】$title") -ForegroundColor Cyan
    Write-Host ('=' * 74) -ForegroundColor DarkGray
}

function Record($name, $kind, $code) {
    $script:results += [pscustomobject]@{ 闸门 = $name; 类别 = $kind; 退出码 = $code }
    if ($code -ne 0) {
        if ($kind -eq '硬') { $script:hardFail++ } else { $script:ledgerFail++ }
    }
}

# 基线文件里一行一个受保护文件，首列是相对路径。数量一律现读，
# 不写死 —— 写死的数字会在基线增删文件后变成错的，而它恰恰是这些小节标题里唯一的数字。
function Get-BaselineCount($name) {
    $f = Join-Path $base $name
    if (-not (Test-Path -LiteralPath $f)) { return '?' }
    return @(Get-Content -LiteralPath $f -Encoding UTF8 | Where-Object { ($_ -split "`t")[0].Trim() -ne '' }).Count
}

if (-not $LedgerOnly) {
    Section '1/8' ("Java 去注释指纹（" + (Get-BaselineCount 'java.round2.txt') + " 文件）")
    & $java -cp $cp CommentFingerprint verify $RepoRoot "$base\java.round2.txt" 2>&1 |
        Where-Object { $_ -match 'FP1|FP2|新增|删除|注释行|结论|被改动' }
    Record 'Java 指纹' '硬' $LASTEXITCODE

    Section '2/8' ("YAML 去注释指纹（" + (Get-BaselineCount 'yaml.round2.txt') + " 文件）")
    & $java -cp $cp YamlFingerprint verify $RepoRoot "$base\yaml.round2.txt" 2>&1 |
        Where-Object { $_ -match 'FP1|FP2|新增|删除|注释行|结论|被改动' }
    Record 'YAML 指纹' '硬' $LASTEXITCODE

    Section '3/8' 'Lua / SQL / 脚本指纹（剥注释后逐行比对）'
    $paths = Get-Content -Encoding UTF8 "$base\any.round2.txt" | ForEach-Object { ($_ -split "`t")[0] } | Where-Object { $_ -ne '' }
    $r = Invoke-GateScript -Script (Join-Path $GateRoot 'gate.ps1') -Arguments $paths
    $r.Output | Where-Object { $_ -match '代码被改动|无基线|不存在|结论' }
    Record 'Lua/SQL/脚本指纹' '硬' $r.ExitCode

    Section '4/8' '注释内容质量（11 条规则）'
    $r = Invoke-GateScript -Script (Join-Path $GateRoot 'lint-comments.ps1')
    $r.Output | Select-Object -Last 16
    Record '内容质量' '硬' $r.ExitCode

    Section '5/8' '闸门自校验（剥离规则 / 内容规则 / 端到端指纹 / Feign 重试运行时对照）'
    $r = Invoke-GateScript -Script (Join-Path $GateRoot 'selftest.ps1')
    $r.Output | Select-Object -Last 6
    Record '闸门自校验' '硬' $r.ExitCode

    Section '6/8' '文档引用有效性（简历索引 / 问答手册里的 文件:行）'
    $r = Invoke-GateScript -Script (Join-Path $GateRoot 'check-doc-refs.ps1') -Arguments @('-Root', $RepoRoot)
    $r.Output | Select-Object -Last 10
    # 退出码 1 = 有引用失效（硬失败）；2 = 只有行号漂移且已给出新行号，属于可继续但要知道，按台账通道记。
    $docKind = $(if ($r.ExitCode -eq 2) { '台账' } else { '硬' })
    Record '文档引用' $docKind $r.ExitCode
}
else {
    Write-Host '（-LedgerOnly：跳过硬闸门）' -ForegroundColor DarkGray
}

Section '7/8' '字典与跨库引用一致性【台账闸门：允许红，红项须在台账内】'
$r = Invoke-GateScript -Script (Join-Path $GateRoot 'dict-lint.ps1') -Arguments @('-Root', $RepoRoot)
$r.Output | Select-Object -Last 20
Record '字典一致性' '台账' $r.ExitCode

Section '8/8' 'Feign 禁重试一致性【台账闸门：允许红，红项须在台账内】'
$r = Invoke-GateScript -Script (Join-Path $GateRoot 'feign-lint.ps1') -Arguments @('-Root', $RepoRoot)
$r.Output | Select-Object -Last 14
Record 'Feign 一致性' '台账' $r.ExitCode

if (-not $SkipBuild) {
    # 模块数同样现读：这里只统计根 pom 的 <module> 声明
    $moduleCount = '?'
    $pomPath = Join-Path $RepoRoot 'pom.xml'
    if (Test-Path -LiteralPath $pomPath) { $moduleCount = @(Select-String -LiteralPath $pomPath -Pattern '<module>').Count }
    Section 'BUILD' ("全量编译（" + $moduleCount + " 模块）")
    $mvn = Get-GateMaven
    if (-not $mvn) {
        Write-Host '找不到 mvn —— 请设置 MAVEN_BIN，或把 maven 放进 PATH' -ForegroundColor Red
        Record '全量编译' '硬' 1
    }
    else {
        $javaHome = Get-GateJavaHome
        $env:JAVA_HOME = $javaHome
        $env:Path = (Join-Path $javaHome 'bin') + [System.IO.Path]::PathSeparator + $env:Path
        $mvnArgs = @('-DskipTests', 'package')
        $settings = Get-GateMavenSettings
        if ($settings) { $mvnArgs = @('-s', $settings) + $mvnArgs }
        if (-not $Online) { $mvnArgs = @('-o') + $mvnArgs }
        Push-Location $RepoRoot
        & $mvn @mvnArgs 2>&1 | Select-String -Pattern 'BUILD|ERROR|Total time' | Select-Object -Last 8
        $mvnCode = $LASTEXITCODE
        Pop-Location
        Record '全量编译' '硬' $mvnCode
    }
}

Write-Host ''
Write-Host ('=' * 74) -ForegroundColor DarkGray
Write-Host '闸门汇总' -ForegroundColor Cyan
Write-Host ('=' * 74) -ForegroundColor DarkGray
$results | ForEach-Object {
    $mark = if ($_.退出码 -eq 0) { 'PASS' } else { 'FAIL' }
    $color = if ($_.退出码 -eq 0) { 'Green' } elseif ($_.类别 -eq '硬') { 'Red' } else { 'Yellow' }
    Write-Host ("  [{0}] {1,-22} {2}  退出码={3}" -f $mark, $_.闸门, $_.类别, $_.退出码) -ForegroundColor $color
}

Write-Host ''
if ($hardFail -gt 0) {
    Write-Host ("结论: 硬闸门失败 $hardFail 项 —— 必须修（这是「闸门失效」或「未登记的违规」）") -ForegroundColor Red
    exit 1
}
if ($ledgerFail -gt 0) {
    Write-Host ("结论: 硬闸门全过；台账闸门报出 $ledgerFail 项已登记偏差 —— 逐条核对《已知偏差台账》") -ForegroundColor Yellow
    exit 2
}
Write-Host '结论: 全绿' -ForegroundColor Green
exit 0
