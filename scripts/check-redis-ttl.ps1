# =============================================================================
# Redis 无 TTL key 巡检（本次 Redis 治理的回归护栏）
#
# 【为什么需要这个脚本】
# 本项目现役 Redis 是 maxmemory 0 + maxmemory-policy noeviction（已实测）。
# 也就是说：没有 TTL 的 key 既不会过期、也不会被淘汰，只会一直堆积，
# 直到内存耗尽后所有写命令直接报错 —— 故障面比"缓存穿透"大得多。
#
# 而"忘记给 key 设 TTL"是这类问题里最容易复发的一种：它不报错、不留日志，
# 只在几周后以"Redis 内存莫名涨满"的形式出现。唯一可靠的发现方式就是定期扫一遍。
#
# 实测（本次修复前）：81 个 key 里有 5 个 TTL = -1，全部集中在门店域
#   hot:shop:all(5)  hot:shop:1(3)  hot:shop:2(1)  hot:shop:8(1)  geo:shop(30)
# 修复后同一实例：无 TTL 的 key 数为 0。
#
# 【用法】
#   pwsh -File scripts/check-redis-ttl.ps1
#   pwsh -File scripts/check-redis-ttl.ps1 -RedisHost 192.168.189.128 -Password 123456
# 有发现时以退出码 1 结束，便于接到 CI / 巡检任务里。
# =============================================================================
param(
    [string]$RedisHost = '192.168.189.128',
    [int]$RedisPort = 6379,
    [string]$Password = $env:LLMALL_REDIS_PW,
    # 允许清单：确实应该永不过期的 key（没有就留空）。
    # 不要为了"让脚本变绿"往这里塞业务缓存 key —— 那等于把护栏拆了。
    [string[]]$AllowNoTtlPattern = @(),
    # redis-cli 路径：默认取项目文档里记录的 Windows 版 Redis
    [string]$RedisCli = 'D:\develop\redis7.2\Redis-7.2.16-Windows-x64-msys2-with-Service\redis-cli.exe'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $RedisCli)) {
    $found = Get-Command redis-cli -ErrorAction SilentlyContinue
    if (-not $found) {
        Write-Error "找不到 redis-cli：请用 -RedisCli 指定路径（默认查找 $RedisCli）"
        exit 2
    }
    $RedisCli = $found.Source
}
if ([string]::IsNullOrWhiteSpace($Password)) { $Password = '123456' }

$base = @('-h', $RedisHost, '-p', "$RedisPort", '-a', $Password, '--no-auth-warning')

function Invoke-Redis {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CmdArgs)
    return ((& $RedisCli @base @CmdArgs 2>&1) | Out-String).Trim()
}

# 连通性自检：宁可明确报"连不上"，也不要因为连不上而输出"0 个问题"的假绿
$ping = Invoke-Redis PING
if ($ping -ne 'PONG') {
    Write-Error "无法连接 Redis ${RedisHost}:${RedisPort}（PING 返回：$ping）"
    exit 2
}

$dbsize = Invoke-Redis DBSIZE
Write-Output "Redis ${RedisHost}:${RedisPort}  DBSIZE=$dbsize"

# 【注意】SCAN 的输出在 PowerShell 里是一个多行字符串，不是字符串数组：
# 直接 @(Invoke-Redis --scan) 会得到"1 个元素"（内容里带换行），
# 于是后面的 TTL 检查只对一个巨型字符串做了一次，最后输出"全部带 TTL"的假绿。
# 必须先按换行拆开再逐条检查 —— 巡检脚本给出错误的"没问题"比不巡检更危险。
$keys = @(Invoke-Redis --scan) -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() }
$keys = @($keys)
if ($keys.Count -eq 0) {
    Write-Output '实例为空，无需巡检。'
    exit 0
}
# 对账：SCAN 拿到的数量应与 DBSIZE 一致，不一致说明扫描方式有问题，不能当作"通过"
if ("$dbsize" -match '^\d+$' -and [int]$dbsize -ne $keys.Count) {
    Write-Error "SCAN 到 $($keys.Count) 个 key，但 DBSIZE=$dbsize，两者不一致，巡检结果不可信，请检查脚本。"
    exit 2
}

$noTtl = @()
foreach ($k in $keys) {
    if ($k -isnot [string] -or [string]::IsNullOrWhiteSpace($k)) { continue }
    $ttl = Invoke-Redis TTL $k
    if ($ttl -eq '-1') { $noTtl += $k }
}

if ($AllowNoTtlPattern.Count -gt 0) {
    $noTtl = @($noTtl | Where-Object {
            $key = $_
            -not ($AllowNoTtlPattern | Where-Object { $key -like $_ })
        })
}

if ($noTtl.Count -eq 0) {
    Write-Output "OK：$($keys.Count) 个 key 全部带 TTL。"
    exit 0
}

Write-Output ''
Write-Output "【发现 $($noTtl.Count) 个没有 TTL 的 key】按前缀聚合："
$noTtl | Group-Object { ($_ -split ':')[0..1] -join ':' } | Sort-Object Count -Descending |
    ForEach-Object { Write-Output ("  {0,-28} {1} 个" -f $_.Name, $_.Count) }
Write-Output ''
Write-Output '明细（前 50 条）：'
$noTtl | Select-Object -First 50 | ForEach-Object {
    $type = Invoke-Redis TYPE $_
    Write-Output ("  {0,-40} type={1}" -f $_, $type)
}
Write-Output ''
Write-Output '处理建议：'
Write-Output '  1) 业务缓存/推导数据（hot: / geo: / cache: / uv: / follow: / blog: 等）→ 代码里写 key 时必须带 TTL，不要手工补了就算完；'
Write-Output '  2) 一次性补救可用：redis-cli EXPIRE <key> <秒数>；'
Write-Output '  3) 确实需要永不过期的 key 请加进 -AllowNoTtlPattern，并在代码注释里写清为什么。'
exit 1
