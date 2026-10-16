# YAML「只改注释」约束的校验入口（单文件版）。
#
# 用法: pwsh -File gates\yaml-check.ps1 <相对仓库根的路径> [更多路径...]
#   例: pwsh -File gates\yaml-check.ps1 llmall-shop-service/src/main/resources/application.yaml
#
# 退出码 0 = 通过（配置项/缩进/结构逐字未变）；1 = 有文件的配置被改动了，必须还原后重做。
#
# 【与 verify-all 的关系】本脚本是第一轮（2026-10-14）的单文件校验入口，供"改了一个 YAML、
# 只想验它"的场景用；全量校验走 verify-all.ps1 的第 ② 道闸门（同一份 YamlFingerprint）。
# 它原先指向第一轮的 yaml.baseline.txt，那份基线属于历史快照、不入库，故改指现行基线
# baseline\yaml.round2.txt —— 口径与 verify-all 一致，不再有第二份基线。
param(
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]]$Files,
    [string]$Root
)

. "$PSScriptRoot\gate-env.ps1"
if ($Root) { $RepoRoot = (Resolve-Path $Root).Path }

$java = Get-GateJava
$classes = Initialize-GateClasses
$base = Join-Path $GateRoot 'baseline\yaml.round2.txt'

& $java -cp $classes YamlFingerprint check $RepoRoot $base @Files 2>&1 |
    Where-Object { $_ -notmatch 'Picked up JAVA_TOOL_OPTIONS' }
exit $LASTEXITCODE
