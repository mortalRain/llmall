# Feign 禁重试一致性闸门
#
# 规则：若某服务用到的 Feign 客户端里存在**写方法**（@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping），
#       那么该服务的 @EnableFeignClients 必须显式引入 NoRetryFeignConfig。
#
# 为什么要求「显式」而不是「靠默认值」：
#   Feign 的重试**无法区分**「请求没发出去」与「已执行但响应丢失」——写接口被重试就是重复下单/重复扣款。
#   Spring Cloud OpenFeign 默认的 Retryer 恰好是 NEVER_RETRY，但那是**框架默认值**：
#   一旦有人引入 spring-retry、或在某处配了 Retryer，写接口就会开始重试，而**不会有任何编译或运行时报错**。
#   所以本闸门要求显式声明，并挡住「新增服务忘了配」这个最常见的回归路径。
#
# 用法: pwsh -File gates\feign-lint.ps1     退出码 0 = 一致；1 = 有服务该配而没配
param([string]$Root)

. "$PSScriptRoot\gate-env.ps1"
if ($Root) { $RepoRoot = (Resolve-Path $Root).Path }
$root = $RepoRoot
$clientDir = "$root\llmall-api\src\main\java\com\llmall\api\client"

# 1) 每个 Feign 客户端的写方法数
$clientWrites = @{}
Get-ChildItem $clientDir -Filter *.java | ForEach-Object {
    $t = [System.IO.File]::ReadAllText($_.FullName)
    $clientWrites[$_.BaseName] = ([regex]::Matches($t, '@(Post|Put|Delete|Patch)Mapping')).Count
}

# 2) 各服务：用到哪些客户端 + 是否引入 NoRetryFeignConfig
$services = Get-ChildItem $root -Directory | Where-Object { $_.Name -like 'llmall-*-service' } | Select-Object -ExpandProperty Name
$enabled = @()
$missing = @()

Write-Host '=== Feign 禁重试一致性闸门 ===' -ForegroundColor Cyan
Write-Host ("Feign 客户端 " + $clientWrites.Count + " 个；写方法分布: " +
    (($clientWrites.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Key + '=' + $_.Value }) -join '  '))
Write-Host ''

foreach ($svc in ($services | Sort-Object)) {
    # 【口径修正·2026-10-15（对应缺陷#5）】原先用「文件里出现过客户端类名」判断"是否使用" ——
    # 而 llmall-item-service 的启动类注释里恰好写了 {@code ItemClient}，于是它被判成"有 2 个写调用"，
    # 被要求补一份它根本用不上的配置。正确判据是**注入点**：装配了 bean ≠ 会调用它。
    $used = @()
    Get-ChildItem (Join-Path $root $svc) -Recurse -Filter *.java -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notlike '*\target\*' } | ForEach-Object {
            $t = [System.IO.File]::ReadAllText($_.FullName)
            foreach ($c in $clientWrites.Keys) {
                if ($used -contains $c) { continue }
                if ($t -match "(?:private|protected|public)\s+(?:final\s+)?$c\s+\w") { $used += $c }
            }
        }
    $wsum = 0
    foreach ($c in $used) { $wsum += $clientWrites[$c] }

    $app = Get-ChildItem (Join-Path $root $svc) -Recurse -Filter *Application.java -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notlike '*\target\*' } | Select-Object -First 1
    $hasNoRetry = $false
    if ($app) {
        $at = [System.IO.File]::ReadAllText($app.FullName)
        $hasNoRetry = $at -match 'NoRetryFeignConfig'
    }

    $verdict = if ($wsum -gt 0 -and -not $hasNoRetry) { 'MISSING' }
               elseif ($wsum -gt 0) { 'OK' }
               else { 'NOT-NEEDED' }

    $mark = switch ($verdict) {
        'MISSING'    { '!! 该配没配' }
        'OK'         { 'OK 已显式禁用' }
        'NOT-NEEDED' { '-- 无需（无写调用）' }
    }
    Write-Host ("  {0,-26} 写方法 {1}  客户端 [{2}]  {3}" -f $svc, $wsum, (($used | Sort-Object) -join ', '), $mark)

    if ($verdict -eq 'MISSING') { $missing += $svc }
    elseif ($verdict -eq 'OK') { $enabled += $svc }
}

Write-Host ''
if ($missing.Count -gt 0) {
    Write-Host ("结论: 待处理 —— " + $missing.Count + " 个服务存在写调用但未显式禁用 Feign 重试：" + ($missing -join ', ')) -ForegroundColor Red
    Write-Host '      修法：在该服务启动类的 @EnableFeignClients(defaultConfiguration = {DefaultFeignConfig.class, NoRetryFeignConfig.class}) 里补上'
    Write-Host '      【注意】这是配置变更（不是注释），改完后本闸门应转为全绿'
    exit 1
}
Write-Host '结论: 通过 —— 所有存在写调用的服务都已显式禁用 Feign 重试' -ForegroundColor Green
exit 0
