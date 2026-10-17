# 压测守卫 + 执行器（宿主保护红线 1 / 4 / 7）
#
# 职责：
#   · 起压前检查磁盘与网关健康；
#   · 跑 JMeter 期间每 N 秒采样宿主 CPU / 内存 / C 盘剩余并落 CSV；
#   · 任一阈值触发即**立即停掉 JMeter 进程树**并标记 Aborted；
#   · 跑完从 .jtl 直接算吞吐 / P95 / P99 / 错误率，并按采样器（label）分开再算一份。
#
# 为什么不在 JMeter 里做限流/采样：JMeter 自己就是被测压力的一部分，让它监控宿主等于让被测方自证。
# 而且熔断要能在 JMeter 卡死时也生效 —— 所以用独立进程采样、必要时直接 kill。
#
# 一键中断：Ctrl+C 会在 finally 里停掉 JMeter（进程树），已采样的 CSV 与 .jtl 都保留。
#
# ---------- 环境准备（没有任何本机路径是写死的） ----------
#   JMETER_HOME : 指向 JMeter 解压目录（含 bin/jmeter.bat 或 bin/jmeter）
#   JAVA_HOME   : 用于跑 JMeter 的 JDK（JMeter 5.6 需要 8+，本项目用 11）
#   两个都可以用参数覆盖：-JmeterHome / -JavaHome
#
# ---------- 用法 ----------
#   # 最简：读接口，10 并发 60 秒
#   pwsh -File benchmark/bench-guard.ps1 -Jmx benchmark/read-c01-feed.jmx -Tag c01-t10 -Threads 10 -Duration 60
#
#   # 自定义采样间隔与阈值（例如更保守：内存 85% 就熔断）
#   pwsh -File benchmark/bench-guard.ps1 -Jmx benchmark/read-c02-search.jmx -Tag c02-t50 `
#        -Threads 50 -Duration 60 -SampleSeconds 5 -MaxMemPct 85
#
#   # 指定 JMeter 与结果目录（换机器/换输出位置）
#   pwsh -File benchmark/bench-guard.ps1 -Jmx ... -Tag ... -JmeterHome D:\tools\apache-jmeter-5.6.3 `
#        -OutDir .\logs\benchmark
#
#   # 不需要令牌的计划（例如纯公开读接口）：跳过登录与地址探测
#   pwsh -File benchmark/bench-guard.ps1 -Jmx ... -Tag ... -SkipLogin
#
# 结果文件（全部落在 -OutDir，默认 <仓库>/logs/benchmark）：
#   <Tag>.jtl               原始采样（CSV 格式，逐请求一行，可独立复算）
#   <Tag>.per-label.csv     按采样器拆分的吞吐/延迟/错误率
#   <Tag>.summary.json      本次运行的汇总（含熔断状态与宿主峰值）
#   <Tag>.host-samples.csv  宿主每 N 秒的 CPU/内存/磁盘采样
#   <Tag>.args.txt          实际传给 JMeter 的参数（复现留证）
#   <Tag>.jmeter.{log,out.txt,err.txt}  JMeter 自身日志
param(
    [Parameter(Mandatory = $true)][string]$Jmx,
    [Parameter(Mandatory = $true)][string]$Tag,
    [int]$Threads = 10,
    [int]$Duration = 60,
    [int]$Ramp = 5,
    [int]$CooldownSeconds = 30,

    # JMeter / JDK 位置：留空则取环境变量，再留空则从 PATH 找 jmeter
    [string]$JmeterHome = '',
    [string]$JavaHome = '',

    # 结果目录：默认 <仓库根>/logs/benchmark
    [string]$OutDir = '',

    # 熔断参数（红线默认值，可按需收紧，不建议放松）
    [int]$SampleSeconds = 5,
    [int]$MaxCpuPct = 90,
    [int]$CpuSustainSeconds = 30,
    [int]$MaxMemPct = 90,
    [int]$MinDiskGb = 5,
    [int]$MinDiskGbToStart = 20,

    # 被测网关与登录账号（-SkipLogin 时不使用）
    [string]$GatewayUrl = 'http://127.0.0.1:8080',
    [string]$Username = 'Jack',
    [string]$Password = '123456',
    [switch]$SkipLogin,

    # 透传给 JMeter 的额外参数（原样按空格切分）
    [string]$Extra = ''
)

$ErrorActionPreference = 'Stop'

# ---------- 路径解析：不写死任何本机目录 ----------
if (-not $OutDir) {
    $OutDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'logs\benchmark'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Resolve-JmeterBat {
    param([string]$Root)
    $candidates = @()
    if ($Root) {
        $candidates += (Join-Path $Root 'bin\jmeter.bat')
        $candidates += (Join-Path $Root 'bin\jmeter')
    }
    if ($env:JMETER_HOME) {
        $candidates += (Join-Path $env:JMETER_HOME 'bin\jmeter.bat')
        $candidates += (Join-Path $env:JMETER_HOME 'bin\jmeter')
    }
    $onPath = Get-Command 'jmeter.bat', 'jmeter' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    throw "找不到 JMeter：请设置 JMETER_HOME（含 bin/jmeter.bat），或用 -JmeterHome 指定解压目录。已尝试：$($candidates -join ' ; ')"
}

function Resolve-JavaHome {
    param([string]$Root)
    $probe = { param($h) if ($h -and (Test-Path (Join-Path $h 'bin\java.exe'))) { return $h }
               if ($h -and (Test-Path (Join-Path $h 'bin/java'))) { return $h }
               return $null }
    $hit = & $probe $Root; if ($hit) { return $hit }
    $hit = & $probe $env:JAVA_HOME; if ($hit) { return $hit }
    $java = Get-Command 'java' -ErrorAction SilentlyContinue
    if ($java) { return (Split-Path -Parent (Split-Path -Parent $java.Source)) }
    throw '找不到 JDK：请设置 JAVA_HOME，或用 -JavaHome 指定'
}

$jmeterBat = Resolve-JmeterBat -Root $JmeterHome
$javaHome = Resolve-JavaHome -Root $JavaHome
Write-Host ("[guard] JMeter = {0}" -f $jmeterBat)
Write-Host ("[guard] JDK    = {0}" -f $javaHome)

$jtl = Join-Path $OutDir ("$Tag.jtl")
$jmeterLog = Join-Path $OutDir ("$Tag.jmeter.log")
$sampleCsv = Join-Path $OutDir ("$Tag.host-samples.csv")
foreach ($f in @($jtl, $jmeterLog, $sampleCsv)) { if (Test-Path $f) { Remove-Item $f -Force } }

# 采样盘符：优先取结果目录所在盘（写不进去的盘才是真正会卡住压测的那个）
$diskDrive = (Split-Path -Qualifier (Resolve-Path $OutDir)).TrimEnd(':')

function Get-HostSample {
    $cpu = (Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq '_Total' } | Select-Object -First 1).PercentProcessorTime
    if ($null -eq $cpu) { $cpu = 0 }
    $os = Get-CimInstance Win32_OperatingSystem
    $memUsedPct = [math]::Round((1 - ($os.FreePhysicalMemory / $os.TotalVisibleMemorySize)) * 100, 1)
    $freeGb = [math]::Round((Get-PSDrive $diskDrive).Free / 1GB, 2)
    [pscustomobject]@{
        Time    = (Get-Date).ToString('HH:mm:ss')
        CpuPct  = [int]$cpu
        MemPct  = [double]$memUsedPct
        CFreeGb = [double]$freeGb
    }
}

# ---------- 起压前检查 ----------
$pre = Get-HostSample
Write-Host ("[guard] 启动前：CPU {0}% / 内存 {1}% / {2} 盘剩余 {3} GB" -f $pre.CpuPct, $pre.MemPct, $diskDrive, $pre.CFreeGb)
if ($pre.CFreeGb -lt $MinDiskGbToStart) { throw "$diskDrive 盘剩余 $($pre.CFreeGb) GB < $MinDiskGbToStart GB —— 红线要求先清理，拒绝启动压测" }
if ($pre.MemPct -ge $MaxMemPct) { throw "内存已用 $($pre.MemPct)% ≥ $MaxMemPct% —— 拒绝启动压测" }
try {
    $h = Invoke-WebRequest -Uri ($GatewayUrl + '/actuator/health') -TimeoutSec 5 -UseBasicParsing
    $body = if ($h.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($h.Content) } else { [string]$h.Content }
    if ($body -notmatch '"status":"UP"') { throw "网关健康检查非 UP：$body" }
    Write-Host '[guard] 网关 /actuator/health = UP'
}
catch { throw "压测环境不可用（网关未就绪）：$($_.Exception.Message)" }

# ---------- 令牌与地址（计划里 ${__P(token)} / ${__P(addressId)} 用到） ----------
$token = ''
$addressId = 1
if (-not $SkipLogin) {
    $login = Invoke-RestMethod -Method Post -Uri ($GatewayUrl + '/users/login') `
        -Body ("{""username"":""$Username"",""password"":""$Password""}") `
        -ContentType 'application/json' -TimeoutSec 20
    $token = [string]$login.accessToken
    if (-not $token) { throw "登录 $Username 失败，拿不到 accessToken" }
    # 地址 id 用正则从 JSON 里取第一个：Invoke-RestMethod 对"数组响应"的包装形态在不同版本下不一致，
    # 直接取 [0].id 可能拿到整列 id（拼成 "59 60 61 63"），带空格传进 -J 会被 JMeter 当成多个参数，
    # 症状是 "Unknown arg: 60" 这种莫名其妙的报错。
    try {
        $addrRaw = Invoke-WebRequest -Method Get -Uri ($GatewayUrl + '/addresses') `
            -Headers @{ authorization = $token } -TimeoutSec 20 -UseBasicParsing
        $addrJson = if ($addrRaw.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($addrRaw.Content) } else { [string]$addrRaw.Content }
        $m = [regex]::Match($addrJson, '"id"\s*:\s*"?(\d+)"?')
        if ($m.Success) { $addressId = [long]$m.Groups[1].Value }
    }
    catch { }
    Write-Host ("[guard] 已取令牌（{0} 字符）与地址 id={1}" -f $token.Length, $addressId)
}

# ---------- 启动 JMeter（堆默认封顶 512m） ----------
$env:HEAP = '-Xms256m -Xmx512m -XX:MaxMetaspaceSize=128m'
$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + [System.IO.Path]::PathSeparator + $env:Path
$commonArgs = @('-n', '-t', $Jmx, "-Jthreads=$Threads", "-Jduration=$Duration", "-Jramp=$Ramp",
                '-l', $jtl, '-j', $jmeterLog,
                '-Jjmeter.save.saveservice.output_format=csv',
                '-Jjmeter.save.saveservice.autoflush=true')
if (-not $SkipLogin) { $commonArgs += @("-Jtoken=$token", "-JaddressId=$addressId") }
if ($Extra) { $commonArgs += ($Extra -split ' ' | Where-Object { $_ }) }
($commonArgs | ForEach-Object { "[arg] $_" }) | Set-Content -Encoding UTF8 (Join-Path $OutDir "$Tag.args.txt")

Write-Host ("[guard] 启动 JMeter：threads={0} duration={1}s jmx={2}" -f $Threads, $Duration, (Split-Path -Leaf $Jmx))
$jmeter = Start-Process -FilePath $jmeterBat -ArgumentList $commonArgs -PassThru -NoNewWindow `
    -RedirectStandardOutput (Join-Path $OutDir "$Tag.jmeter.out.txt") `
    -RedirectStandardError (Join-Path $OutDir "$Tag.jmeter.err.txt")

$samples = New-Object System.Collections.ArrayList
$abortReason = $null
$cpuHighSince = $null
$sw = [System.Diagnostics.Stopwatch]::StartNew()

try {
    'Time,CpuPct,MemPct,CFreeGb' | Set-Content -Encoding UTF8 $sampleCsv
    $hardCap = $Duration + 180          # 兜底：JMeter 正常应在 duration+ramp+几十秒内结束
    while (-not $jmeter.HasExited) {
        $s = Get-HostSample
        [void]$samples.Add($s)
        ('{0},{1},{2},{3}' -f $s.Time, $s.CpuPct, $s.MemPct, $s.CFreeGb) | Add-Content -Encoding UTF8 $sampleCsv

        if ($s.CpuPct -gt $MaxCpuPct) { if ($null -eq $cpuHighSince) { $cpuHighSince = Get-Date } }
        else { $cpuHighSince = $null }

        if ($null -ne $cpuHighSince -and ((Get-Date) - $cpuHighSince).TotalSeconds -ge $CpuSustainSeconds) {
            $abortReason = "宿主 CPU 持续 >$MaxCpuPct% 达 $CpuSustainSeconds 秒（当前 $($s.CpuPct)%）"
        }
        elseif ($s.MemPct -gt $MaxMemPct) {
            $abortReason = "宿主内存占用 $($s.MemPct)% > $MaxMemPct%"
        }
        elseif ($s.CFreeGb -lt $MinDiskGb) {
            $abortReason = "$diskDrive 盘剩余 $($s.CFreeGb) GB < $MinDiskGb GB"
        }

        if ($abortReason) {
            Write-Host "[guard][熔断] $abortReason —— 立即停止压测" -ForegroundColor Red
            try { Stop-Process -Id $jmeter.Id -Force -ErrorAction SilentlyContinue } catch { }
            break
        }
        if ($sw.Elapsed.TotalSeconds -gt $hardCap) {
            $abortReason = "JMeter 超过兜底时限 $hardCap 秒仍未结束"
            try { Stop-Process -Id $jmeter.Id -Force -ErrorAction SilentlyContinue } catch { }
            break
        }
        Start-Sleep -Seconds $SampleSeconds
    }
}
finally {
    if (-not $jmeter.HasExited) { try { Stop-Process -Id $jmeter.Id -Force -ErrorAction SilentlyContinue } catch { } }
    # 停掉可能残留的 JMeter java 子进程（jmeter.bat 起的是 java 子进程）
    Get-Process -Name 'java' -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path -like "*$javaHome*" -and $_.StartTime -ge $jmeter.StartTime } |
        ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch { } }
    Write-Host ("[guard] 采样 {0} 次；峰值 CPU {1}% / 峰值内存 {2}%" -f `
        $samples.Count, ($samples | Measure-Object CpuPct -Maximum).Maximum, ($samples | Measure-Object MemPct -Maximum).Maximum)
    if ($CooldownSeconds -gt 0 -and -not $abortReason) {
        Write-Host "[guard] 冷却 $CooldownSeconds 秒"
        Start-Sleep -Seconds $CooldownSeconds
    }
}

# ---------- 统计（整体 + 按采样器） ----------
function Get-Pct($arr, $p) {
    if ($arr.Count -eq 0) { return 0 }
    $idx = [math]::Min($arr.Count - 1, [math]::Ceiling($arr.Count * $p / 100) - 1)
    return $arr[$idx]
}

$summary = [pscustomobject]@{
    Tag = $Tag; Jmx = (Split-Path -Leaf $Jmx); Threads = $Threads; Duration = $Duration
    Aborted = [bool]$abortReason; AbortReason = $abortReason
    PeakCpuPct = ($samples | Measure-Object CpuPct -Maximum).Maximum
    PeakMemPct = ($samples | Measure-Object MemPct -Maximum).Maximum
    MinCFreeGb = ($samples | Measure-Object CFreeGb -Minimum).Minimum
    Samples = $samples.Count
    RequestHost = $GatewayUrl
    Requests = 0; ThroughputRps = 0; AvgMs = 0; P95Ms = 0; P99Ms = 0; ErrorPct = 0; OkCount = 0; FailCount = 0
}

if (Test-Path $jtl) {
    $rows = Import-Csv $jtl
    if ($rows.Count -gt 0) {
        $ok = @($rows | Where-Object { $_.success -eq 'true' })
        $bad = @($rows | Where-Object { $_.success -ne 'true' })
        $elapsed = @($rows | ForEach-Object { [double]$_.elapsed } | Sort-Object)
        $ts = @($rows | ForEach-Object { [double]$_.timeStamp })
        $wall = ([math]::Max(($ts | Measure-Object -Maximum).Maximum - ($ts | Measure-Object -Minimum).Minimum, 1)) / 1000
        $summary.Requests = $rows.Count
        $summary.OkCount = $ok.Count
        $summary.FailCount = $bad.Count
        $summary.ThroughputRps = [math]::Round($rows.Count / $wall, 1)
        $summary.AvgMs = [math]::Round((($elapsed | Measure-Object -Average).Average), 1)
        $summary.P95Ms = [math]::Round((Get-Pct $elapsed 95), 1)
        $summary.P99Ms = [math]::Round((Get-Pct $elapsed 99), 1)
        $summary.ErrorPct = [math]::Round($bad.Count * 100.0 / $rows.Count, 2)
    }
}

# 为什么必须按采样器拆：一份计划里可能同时压了带限流的接口和不带限流的接口，
# 混在一起的 Error% 会把"设计内拒绝"当成"服务端故障"，也会让 400 的极短耗时污染 P95/P99。
if (Test-Path $jtl) {
    $all = Import-Csv $jtl
    $perLabel = foreach ($g in ($all | Group-Object label)) {
        $el = @($g.Group | ForEach-Object { [double]$_.elapsed } | Sort-Object)
        $okc = @($g.Group | Where-Object { $_.success -eq 'true' }).Count
        $badc = $g.Count - $okc
        [pscustomobject]@{
            Tag       = $Tag
            Label     = $g.Name
            Threads   = $Threads
            Requests  = $g.Count
            OkCount   = $okc
            FailCount = $badc
            ErrorPct  = [math]::Round($badc * 100.0 / $g.Count, 2)
            AvgMs     = [math]::Round((($el | Measure-Object -Average).Average), 1)
            P95Ms     = [math]::Round((Get-Pct $el 95), 1)
            P99Ms     = [math]::Round((Get-Pct $el 99), 1)
        }
    }
    $perLabel | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir "$Tag.per-label.csv")
    $perLabel | Format-Table -AutoSize | Out-String -Width 140 | Write-Host
}

$summary | ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 (Join-Path $OutDir "$Tag.summary.json")
$summary | Format-List | Out-String -Width 160 | Write-Host
if ($abortReason) { exit 9 }
exit 0
