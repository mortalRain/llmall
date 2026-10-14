# 统一账号链路端到端验收脚本
#
# 用途：一条命令验证"统一账号 + 身份透传 + 防伪造 + 双令牌无感刷新"整条链路。
# 前置：Nacos / MySQL / Redis 已就绪；llmall-gateway(8080) 与 llmall-user-service(8084) 已启动并注册到 Nacos。
#
# 用法（Windows 默认禁止脚本执行，不加 -ExecutionPolicy Bypass 会报 running scripts is disabled）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-auth.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-auth.ps1 -Gateway http://localhost:8080 -Phone 13800001234
# PowerShell 7 亦可：pwsh -File .\scripts\verify-auth.ps1
#
# 【注意】第 1 节（防伪造）依赖越权自测端点 /users/_debug/**，该端点要求 llmall.debug.enabled=true，
#         默认关闭时返回 404，此时该节判为 SKIP 而非 FAIL；要完整验收请临时开启后重跑。
# 账号：默认 Jack / 123456

param(
    [string]$Gateway = "http://localhost:8080",
    [string]$Phone   = "13800001234"
)

$ErrorActionPreference = 'Continue'
$pass = 0; $fail = 0; $skip = 0

function Check([string]$name, [bool]$ok, [string]$detail) {
    if ($ok) { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
    else     { $script:fail++; Write-Host ("  [FAIL] " + $name + "  -> " + $detail) -ForegroundColor Red }
}
function Skip([string]$name, [string]$why) {
    $script:skip++; Write-Host ("  [SKIP] " + $name + "  (" + $why + ")") -ForegroundColor Yellow
}
function Try-Get([string]$url, [hashtable]$headers) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers $headers -TimeoutSec 8
        return @{ ok = $true; status = $r.StatusCode; body = $r.Content; headers = $r.Headers }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $status = [int]$resp.StatusCode
            return @{ ok = $false; status = $status; body = ""; headers = @{} }
        }
        return @{ ok = $false; status = 0; body = $_.Exception.Message; headers = @{} }
    }
}

Write-Host "`n=== 0. 网关可达性 ===" -ForegroundColor Cyan
$probe = Try-Get "$Gateway/users/_debug/whoami" @{}
if ($probe.status -eq 0) {
    Write-Host "  网关不可达（$Gateway），请先启动 llmall-gateway 与 llmall-user-service 后重跑。" -ForegroundColor Red
    exit 1
}
Write-Host "  网关响应状态: $($probe.status)"

Write-Host "`n=== 1. 防伪造：免鉴权路径上伪造 user-info 头 ===" -ForegroundColor Cyan
Write-Host "  原理：/users/_debug/** 在网关 excludePaths 中（免鉴权直接放行）。"
Write-Host "        修复前：伪造头会被透传到下游并被采信（越权）；修复后：网关入口剥离，下游只会看到 null。"
$forged = Try-Get "$Gateway/users/_debug/whoami" @{ "user-info" = "1" }
if ($forged.status -eq 200) {
    $json = $forged.body | ConvertFrom-Json
    Check "伪造 user-info 头已被网关剥离" ($null -eq $json.userInfoHeader) ("下游仍收到 userInfoHeader=" + $json.userInfoHeader)
    Check "下游身份上下文未被污染"       ($null -eq $json.userIdInContext) ("userIdInContext=" + $json.userIdInContext)
} elseif ($forged.status -eq 404) {
    # 自测端点是越权探针，默认关闭；关闭时无法验证防伪造，属预期而非失败，避免把"没开探针"误报成"防伪造失效"
    Skip "防伪造校验（需 llmall-user-service 开启 llmall.debug.enabled=true）" "自测端点未启用，返回 404"
} else {
    Check "防伪造校验前置：自测端点可达" $false "状态码 $($forged.status)"
}

Write-Host "`n=== 2. 受保护路径必须带令牌 ===" -ForegroundColor Cyan
$noToken = Try-Get "$Gateway/users/me" @{}
Check "无令牌访问 /users/me 返回 401" ($noToken.status -eq 401) "实际状态码 $($noToken.status)"

Write-Host "`n=== 3. 短信验证码接口（验证 Redis 链路）===" -ForegroundColor Cyan
# /users/code 是 POST 接口，用 GET 会返回 405
try {
    $codeResp = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$Gateway/users/code?phone=$Phone" -TimeoutSec 10
    Check "发送验证码接口可调用" ($codeResp.StatusCode -eq 200) "状态码 $($codeResp.StatusCode)"
} catch {
    $resp = $_.Exception.Response
    Check "发送验证码接口可调用" $false ("状态码 " + $(if ($resp) { [int]$resp.StatusCode } else { $_.Exception.Message }))
}
Write-Host "  验证码已写入 Redis，并在 llmall-user-service 日志中打印（【验证码】...），请从日志取码后继续。" -ForegroundColor DarkGray
Skip  "验证码登录拿双令牌" "需要人工从 llmall-user-service 日志读取验证码；也可用下面的用户名密码通道"

Write-Host "`n=== 4. 用户名密码登录 + 令牌链路 ===" -ForegroundColor Cyan
$loginBody = @{ username = "Jack"; password = "123456" } | ConvertTo-Json
try {
    $login = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$Gateway/users/login" `
        -ContentType "application/json" -Body $loginBody -TimeoutSec 8
    $loginJson = $login.Content | ConvertFrom-Json
    Check "登录成功返回 accessToken"  (-not [string]::IsNullOrEmpty($loginJson.accessToken)) "accessToken 为空"
    Check "登录成功返回 refreshToken" (-not [string]::IsNullOrEmpty($loginJson.refreshToken)) "refreshToken 为空"
    Check "兼容旧前端字段 token"      (-not [string]::IsNullOrEmpty($loginJson.token)) "token 为空"

    if ($loginJson.accessToken) {
        $withToken = Try-Get "$Gateway/users/me" @{ "authorization" = $loginJson.accessToken }
        Check "带令牌访问 /users/me 成功" ($withToken.status -eq 200) "状态码 $($withToken.status)"
        if ($withToken.status -eq 200) {
            $me = $withToken.body | ConvertFrom-Json
            Write-Host ("        当前用户: " + $me.username + " / uid=" + $me.userId) -ForegroundColor DarkGray
        }

        Write-Host "`n=== 5. 主动失效：注销后令牌立刻不可用 ===" -ForegroundColor Cyan
        try {
            Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$Gateway/users/logout" `
                -Headers @{ "authorization" = $loginJson.accessToken } -TimeoutSec 8 | Out-Null
            $afterLogout = Try-Get "$Gateway/users/me" @{ "authorization" = $loginJson.accessToken }
            Check "注销后原 access token 返回 401" ($afterLogout.status -eq 401) "实际状态码 $($afterLogout.status)"
        } catch {
            Check "注销接口可调用" $false $_.Exception.Message
        }

        Write-Host "`n=== 6. 无感刷新（说明性检查）===" -ForegroundColor Cyan
        Write-Host "  access TTL 为 30 分钟，脚本不做等待。手工验证方式：" -ForegroundColor DarkGray
        Write-Host "    1) 把 llmall-user-service/gateway 的 llmall.jwt.tokenTTL 临时改成 10s 并重启" -ForegroundColor DarkGray
        Write-Host "    2) 登录后等待过期，再带 authorization(旧access) + x-refresh-token(refresh) 访问 /users/me" -ForegroundColor DarkGray
        Write-Host "    3) 期望：请求成功（200），响应头 x-access-token 返回新 access token" -ForegroundColor DarkGray
    }
} catch {
    Check "用户名密码登录" $false $_.Exception.Message
}

Write-Host "`n=== 结果 ===" -ForegroundColor Cyan
Write-Host ("  PASS=" + $pass + "  FAIL=" + $fail + "  SKIP=" + $skip)
if ($fail -gt 0) { exit 1 } else { exit 0 }
