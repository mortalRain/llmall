# 一键验证：单测 + 八道闸门。
# 用法：
#   pwsh -File scripts\verify.ps1              # 62 个单测 + 编译 + 八道闸门
#   pwsh -File scripts\verify.ps1 -SkipTests   # 只跑闸门（不重跑单测）
#   pwsh -File scripts\verify.ps1 -SkipBuild   # 单测 + 闸门，跳过闸门内置编译
param(
    [switch]$SkipTests,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not $SkipTests) {
        Write-Host '=== [1/2] 单元测试 ===' -ForegroundColor Cyan
        & mvn -B -o test
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } else {
        Write-Host '=== [1/2] 单元测试：已跳过 ===' -ForegroundColor Yellow
    }

    Write-Host '=== [2/2] 八道闸门 ===' -ForegroundColor Cyan
    $gateArgs = @('-NoProfile', '-File', 'gates/verify-all.ps1')
    if ($SkipBuild) { $gateArgs += '-SkipBuild' }
    & pwsh @gateArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
