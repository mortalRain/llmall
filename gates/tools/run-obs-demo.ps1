# 复跑 OBS-01 / OBS-02 的触发演示（不依赖 ES、不依赖数据库、不需要启动服务）
#
# 做法：从两个服务的 fat jar 里解出 BOOT-INF/lib 与 BOOT-INF/classes 拼成 classpath，
#       然后用桩驱动真实代码路径，由 ObsDemo 断言两行信号是否真的打出来。
#
# 【前置】需要先跑过一次构建（verify-all.ps1 的 BUILD 段，或 mvn -DskipTests package），
#         因为要的是 llmall-content-service / llmall-search-service 的 fat jar。
# 【为什么不把依赖入库】ObsDemo 走的是 Spring 上下文路径，classpath 是整个 BOOT-INF/lib
#         （近百 MB），入库不现实；FeignRetryProbe 那种能自足的才走 gates/lib。
#
# 用法: pwsh -File gates\tools\run-obs-demo.ps1
# 退出码：0 = 两个信号都触发；1 = 有信号未按预期触发

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

. "$PSScriptRoot\..\gate-env.ps1"

$java = Get-GateJava
$dest = Join-Path ([System.IO.Path]::GetTempPath()) 'llmall-demo'

# 1) 解出 fat jar 的内容（幂等：已存在则跳过）
foreach ($m in @('llmall-content-service', 'llmall-search-service')) {
    $jar = Join-Path $RepoRoot ($m + '\target\' + $m + '.jar')
    $d = Join-Path $dest $m
    if (-not (Test-Path (Join-Path $d 'BOOT-INF\lib'))) {
        if (-not (Test-Path $jar)) {
            throw "缺少 $jar —— 先跑一次 gates\verify-all.ps1（含编译段）或 mvn -DskipTests package 生成它"
        }
        Write-Host "解压 $m ..." -ForegroundColor DarkGray
        [System.IO.Compression.ZipFile]::ExtractToDirectory($jar, $d)
    }
}

# 2) 拼 classpath 并运行演示器
$cp = @(
    (Join-Path $dest 'llmall-search-service\BOOT-INF\lib\*'),
    (Join-Path $dest 'llmall-search-service\BOOT-INF\classes'),
    (Join-Path $dest 'llmall-content-service\BOOT-INF\lib\*'),
    (Join-Path $dest 'llmall-content-service\BOOT-INF\classes')
) -join ';'

Push-Location $PSScriptRoot
$out = Invoke-GateNative -Exe $java -NativeArgs @('-cp', $cp, 'ObsDemo.java')
$code = $LASTEXITCODE
$out -split "`r?`n" | Where-Object { $_ -notmatch 'Picked up|multiple SLF4J bindings|Found binding in|See http://www.slf4j.org|Actual binding is of type' }
Pop-Location

Write-Host ''
Write-Host "ObsDemo 退出码 = $code （0 = 两个信号都触发）"
exit $code
