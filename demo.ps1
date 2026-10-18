# ============================================================================
# llmall 一键运行（demo.ps1）
#
# 定位：**只编排仓库里既有的材料**，不重造任何东西。流水线里每一步都对应一份既有文件：
#   构建   mvn package（父 pom）+ deploy/Dockerfile（单镜像多 jar）
#   中间件 docker/docker-compose-infra-lite.yml（mysql/redis/nacos/mq）
#           + docker/docker-compose-addon.yml 的 elasticsearch（ES 只在这份里，且不带 profile）
#   建库   sql/merged/00 → 99，顺序与例外全部照抄 sql/merged/README.md
#   配置   nacos-config/*.yaml 与 nacos-config/gateway-routes.json，走 Nacos Open API 导入
#   应用   deploy/docker-compose-apps.yml --profile <core|full>
#   冒烟   scripts/MallE2E.java
#   灌 ES  gates/EsBulkLoad.java + gates/es-shop-mapping.json（闸门进仓时一并迁入）
#
# 三条命令（清容器清卷 → 一键跑通 → 收尾）：
#   pwsh -File demo.ps1 -Reset      # 停容器 + 删卷 + 删网络（幂等，可反复跑）
#   pwsh -File demo.ps1             # 全流程；成功时退出码 0
#   pwsh -File demo.ps1 -Down       # 收尾（加 -Volumes 连卷一起删）
#
# 只有 Windows PowerShell 5.1 的机器同样可用（脚本为 UTF-8 with BOM）。
#
# 【已知缺口：本脚本尚未在装有容器运行时的机器上执行过】
#   编写环境没有 Docker / Podman / WSL，因此"清容器清卷 → 3 条命令 → MallE2E 全绿"这条
#   验收**没有跑过**。可静态验证的部分（语法、-Preflight、JDK/Maven 解析、SQL 顺序表）已验；
#   运行期行为未验。首次在真机上跑请先 `pwsh -File demo.ps1 -Preflight`。
#
# 【两处与施工单不一致，已按"能让冒烟真的全绿"取舍，见报告】
#   1) --profile 默认 full 而不是 core：core 只有 gateway/user/shop/item/content 五个服务，
#      而 MallE2E 走的是 登录→购物车→下单→支付→取消→评价，必须有 trade/cart/pay/promotion/search。
#      要按 core 起就传 -Profile core（此时请配 -SkipSmoke，否则冒烟必红）。
#   2) 冒烟账号用的是 MallE2E 硬编码的演示账号 Jack/123456 —— "专属账号"这一条**未做到**。
#      原因：MallE2E.java 没有 CLI 参数，改它属于"顺手重构代码"，是红线。
#      替代做法：冒烟前后取"自增 id 水位线"，finally 里只删本次冒烟新建的行（见 -NoCleanup）。
#
# 【ES 的两种来源】默认起 addon 容器（docker/docker-compose-addon.yml 的 elasticsearch）。
#   若 ES 已在本机原生装着（本机就有 D:\develop\elasticsearch-7.17.10，且 plugins\ik 已就位），
#   用 -ExternalEs -EsUrl http://127.0.0.1:9200：脚本不会再起容器版，避免抢 9200 端口。
# ============================================================================

param(
    [switch]$Reset,                 # 停容器 + 删卷 + 删网络，然后退出
    [switch]$Down,                  # 收尾：停容器（配 -Volumes 一并删卷）
    [switch]$Volumes,               # 与 -Down 搭配：删数据卷
    [switch]$Preflight,             # 只做前置检查，不动任何东西
    [switch]$SkipBuild,             # 跳过 mvn package + docker build（复用已有镜像）
    [switch]$SkipSql,               # 跳过建库与灌数
    [switch]$SkipNacos,             # 跳过 Nacos 配置导入
    [switch]$ForceNacos,            # Nacos 上已有与仓库不一致的配置时，允许覆盖（默认停下问）
    [switch]$SkipEs,                # 跳过 ES 索引与灌数
    [switch]$SkipSmoke,             # 跳过 MallE2E 冒烟
    [switch]$NoCleanup,             # 不清理冒烟新建的数据
    [switch]$Online,                # 允许 Maven 走网络（默认离线 -o）
    [switch]$WithDianpingMigration, # 额外执行 99-migrate-dianping-data.sql（需要外部库 dianping_src）
    [switch]$ExternalEs,            # ES 已在别处跑着（宿主原生安装 / VM），不起 addon 容器
    [string]$EsUrl = 'http://127.0.0.1:9200',
    [int]$DbHostPort = 0,           # 宿主上 mysql 容器映射的端口；0 = 取 $env:MYSQL_HOST_PORT，再缺省 3306
    [string]$Profile = 'full',      # core | full
    [int]$TimeoutMinutes = 30
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\gates\gate-env.ps1"      # 复用既有材料：JDK 解析、控制台 UTF-8、$RepoRoot

# ---------------------------------------------------------------- 常量（都来自既有文件）
$ImageTag   = 'local-life/llmall:1.0.0'                      # deploy/docker-compose-apps.yml 里硬编码的 tag
$Network    = 'local-life'                                   # 三份 compose 都引用这个 external 网络
$NacosUrl   = 'http://127.0.0.1:8848'
$GatewayUrl = 'http://127.0.0.1:8080'
$DbUser     = 'root'
# 凭据统一从 deploy/.env 读（.env 不入库；缺失时第 2 步会由 .env.example 生成）。
# 【注意】compose 里已经不再写任何口令：infra 的 MYSQL_ROOT_PASSWORD / REDIS_PASSWORD、
# 应用的 LLMALL_DB_PW / LLMALL_REDIS_PW、Seata 的控制台与落库口令，全部由同一个 .env 提供。
$EnvFile    = Join-Path $RepoRoot 'deploy\.env'
$EnvExample = Join-Path $RepoRoot 'deploy\.env.example'
function Get-EnvFileValue {
    param([string]$Path, [string]$Key)
    if (-not (Test-Path $Path)) { return $null }
    $hit = Select-String -Path $Path -Pattern ('^' + [regex]::Escape($Key) + '=(.*)$') -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { return $hit.Matches[0].Groups[1].Value.Trim() }
    return $null
}
$DbPw = Get-EnvFileValue $EnvFile 'MYSQL_ROOT_PASSWORD'
if (-not $DbPw) { $DbPw = Get-EnvFileValue $EnvExample 'MYSQL_ROOT_PASSWORD' }
if (-not $DbPw) { $DbPw = '123' }                            # 最后兜底：正常路径走不到（两个文件都应含该键）
$DbName     = 'llmall-search'                                # EsBulkLoad 的读模型来源库

# 宿主上 mysql 容器映射的端口：传 0 时按 MYSQL_HOST_PORT 环境变量解析，再缺省 3306。
# 与 docker-compose-infra-lite.yml 的 ${MYSQL_HOST_PORT:-3306} 是同一口径 —— 本机若已装着
# 原生 MySQL 服务占住 3306，设 MYSQL_HOST_PORT=3307 就能整套跑在容器里，不必停掉那套 MySQL。
# 容器内的服务之间仍按容器名 + 3306 互访，跟这个宿主端口无关。
if ($DbHostPort -le 0) {
    $DbHostPort = if ($env:MYSQL_HOST_PORT) { [int]$env:MYSQL_HOST_PORT } else { 3306 }
}

$InfraCompose = Join-Path $RepoRoot 'docker\docker-compose-infra-lite.yml'
$AddonCompose = Join-Path $RepoRoot 'docker\docker-compose-addon.yml'
$AppsCompose  = Join-Path $RepoRoot 'deploy\docker-compose-apps.yml'
$SqlMerged    = Join-Path $RepoRoot 'sql\merged'
$WorkDir      = Join-Path $RepoRoot 'logs\demo'              # logs/ 已在 .gitignore 里
$Stamp        = Get-Date -Format 'yyyyMMdd-HHmmss'
$ReportFile   = Join-Path $WorkDir ("demo-report-$Stamp.md")
$StatsFile    = Join-Path $WorkDir ("docker-stats-$Stamp.txt")

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

# ---------------------------------------------------------------- 小工具
$script:StepNo = 0
function Write-Step {
    param([string]$Title)
    $script:StepNo++
    Write-Host ''
    Write-Host ('=' * 74) -ForegroundColor DarkGray
    Write-Host ("[$script:StepNo] $Title") -ForegroundColor Cyan
    Write-Host ('=' * 74) -ForegroundColor DarkGray
}
function Write-Info { param([string]$Text) Write-Host "    $Text" }
function Write-Warn2 { param([string]$Text) Write-Host "    [注意] $Text" -ForegroundColor Yellow }
function Fail {
    param([string]$Text)
    Write-Host ''
    Write-Host "失败：$Text" -ForegroundColor Red
    exit 1
}

# 容器运行时是硬前置：中间件和应用都跑在容器里。
# 单独成函数是因为 -Reset / -Down 会在这条检查之前执行，必须先挡住，
# 否则 `& docker` 会抛 CommandNotFoundException，报错信息还不如这句话清楚。
function Get-DockerState {
    # 返回 daemon 版本字符串；不可用返回 $null。
    # 拆出这个"只判断、不退出"的版本，是为了让 -Preflight 能把所有问题一次报完，
    # 而不是撞到第一个就退出（预检的价值就在于一次说清）。
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return $null }
    $v = & docker version --format '{{.Server.Version}}' 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($v -join ' ').Trim()
}

function Assert-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-Host ''
        Write-Host '找不到 docker —— demo.ps1 的中间件与应用都通过容器运行。' -ForegroundColor Red
        Write-Host '装好 Docker Desktop（或 Linux 上的 docker engine），确认 `docker version` 能连上守护进程后重跑。' -ForegroundColor Red
        Write-Host '只想检查别的东西：pwsh -File gates\verify-all.ps1（闸门不依赖容器，clone 下来即可跑）。' -ForegroundColor Yellow
        exit 2
    }
    $v = Get-DockerState
    if (-not $v) {
        Write-Host ''
        Write-Host 'docker 守护进程不可用（docker version 失败）—— Docker Desktop 可能没启动。' -ForegroundColor Red
        exit 2
    }
    return $v
}

# 跑一条 docker / docker compose 命令。默认非 0 退出即失败；-AllowFail 只告警。
function Invoke-Docker {
    param([string[]]$DockerArgs, [switch]$AllowFail, [switch]$Quiet, [int]$Tail = 0)
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail '找不到 docker' }
    if (-not $Quiet) { Write-Info ('> docker ' + ($DockerArgs -join ' ')) }
    $out = & docker @DockerArgs 2>&1
    $code = $LASTEXITCODE
    if ($Tail -gt 0) { $out | Select-Object -Last $Tail | ForEach-Object { Write-Info ('  ' + $_) } }
    elseif (-not $Quiet) { $out | ForEach-Object { Write-Info ('  ' + $_) } }
    if ($code -ne 0 -and -not $AllowFail) { Fail ("docker " + ($DockerArgs -join ' ') + " 退出码 $code") }
    return [pscustomobject]@{ Code = $code; Output = @($out) }
}

# cmd.exe 做输入重定向：PowerShell 不支持 `<`，而 36MB 的 llmall-item.sql 不能读进内存再喂。
function Invoke-SqlFile {
    param([string]$Path)
    if (-not (Test-Path $Path)) { Fail "SQL 文件不存在：$Path" }
    Write-Info ('> mysql < ' + (Split-Path -Leaf $Path) + ('  ({0:N1} MB)' -f ((Get-Item $Path).Length / 1MB)))
    $line = 'docker exec -i mysql mysql -uroot -p' + $DbPw + ' --default-character-set=utf8mb4 < "' + $Path + '"'
    $out = & cmd.exe /c $line 2>&1
    $code = $LASTEXITCODE
    # 统一成字符串数组：PowerShell 7 会把 stderr 里的行收成 ErrorRecord 对象（这里就是 mysql 的
    # [Warning] Using a password ...），下游按行处理（.Trim() / -match / -split）时会在 ErrorRecord
    # 上直接炸 —— 报错还落在解析逻辑里，看起来像 SQL 出错。字符串化只影响展示，不影响判定。
    $out = @($out | ForEach-Object { [string]$_ })
    # mysql 会把 "Using a password on the command line interface can be insecure" 写到 stderr，不算错误
    $errs = @($out | Where-Object { $_ -match 'ERROR \d+ \(' })
    if ($code -ne 0 -or $errs.Count -gt 0) {
        $out | ForEach-Object { Write-Info ('  ' + $_) }
        Fail ("执行 " + (Split-Path -Leaf $Path) + " 失败（退出码 $code）")
    }
    return $out
}

function Invoke-SqlText {
    param([string]$Sql, [switch]$Batch)
    $flags = @('exec', '-i', 'mysql', 'mysql', '-uroot', ('-p' + $DbPw))
    if ($Batch) { $flags += @('-N', '-B') }
    $flags += @('-e', $Sql)
    $out = & docker @flags 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "SQL 执行失败：$Sql" }
    return @($out | ForEach-Object { [string]$_ } | Where-Object { $_ -notmatch 'Using a password' })
}

function Wait-Http {
    param([string]$Url, [string]$Name, [int]$Seconds = 180, [string]$Contains)
    Write-Info "等待 $Name 就绪：$Url"
    $deadline = (Get-Date).AddSeconds($Seconds)
    $last = ''
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -TimeoutSec 5 -UseBasicParsing
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300) {
                if (-not $Contains -or (Get-HttpText $r) -match $Contains) {
                    Write-Info "  $Name 就绪"
                    return $true
                }
                $last = "HTTP $($r.StatusCode)，响应体不含期望内容 $Contains"
            }
            else { $last = "HTTP $($r.StatusCode)" }
        }
        catch { $last = $_.Exception.Message }
        Start-Sleep -Seconds 3
    }
    if ($last) { Write-Info "  最后一次探测：$last" }
    Fail "$Name 在 $Seconds 秒内未就绪（$Url）"
}

# 取 HTTP 响应体文本。为什么不能直接用 $r.Content：
# PowerShell 7 在响应头没有带 charset 时把 .Content 给成 **byte[]**（Spring Boot 的
# /actuator/health 返回 application/vnd.spring-boot.actuator.v3+json，没有 charset），
# 而 Windows PowerShell 5.1 给的是字符串。于是在 pwsh 7 上 $r.Content -match '"status":"UP"'
# 是对字节数组做匹配 —— 恒不命中、还不报错，表现为"服务明明起来了，脚本一直说没就绪"。
function Get-HttpText {
    param($Response)
    if ($null -eq $Response) { return '' }
    if ($Response.Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Response.Content)
    }
    return [string]$Response.Content
}

function Wait-MySql {
    param([int]$Seconds = 180)
    Write-Info '等待 MySQL 就绪（mysqladmin ping）'
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $out = & docker exec -i mysql mysqladmin -uroot ('-p' + $DbPw) ping 2>&1
        if (($out -join ' ') -match 'mysqld is alive') { Write-Info '  MySQL 就绪'; return $true }
        Start-Sleep -Seconds 3
    }
    Fail "MySQL 在 $Seconds 秒内未就绪"
}

# RabbitMQ 就绪 + 启用两个插件。两个都是必需的：
#   rabbitmq_management                 —— 管理台 15672，排障时看队列/交换机
#   rabbitmq_delayed_message_exchange   —— trade 的超时未支付自动关单用它声明的 x-delayed-message 交换机
# 为什么放在脚本里而不是靠镜像默认：compose 把 enable 清单指向了数据卷里的 enabled_plugins 文件
# （为了容器重建后启用状态不丢），而该文件初始为空 —— 空清单会把 -management 镜像的默认启用项也一起关掉。
# 本步骤幂等：已经启用过再跑一遍只会打印 already enabled。
function Initialize-MqPlugins {
    param([int]$Seconds = 180)
    Write-Info '等待 RabbitMQ 就绪（rabbitmq-diagnostics ping）'
    $deadline = (Get-Date).AddSeconds($Seconds)
    $alive = $false
    while ((Get-Date) -lt $deadline) {
        $out = & docker exec mq rabbitmq-diagnostics -q ping 2>&1
        if (($out -join ' ') -match 'Ping succeeded') { $alive = $true; break }
        Start-Sleep -Seconds 3
    }
    if (-not $alive) {
        Write-Info ((& docker ps -a --filter 'name=^mq$' --format '{{.Status}}') -join ' ')
        Fail "RabbitMQ 在 $Seconds 秒内未就绪（看 docker logs mq）"
    }
    Write-Info '  RabbitMQ 就绪；启用 rabbitmq_management + rabbitmq_delayed_message_exchange'
    $r = & docker exec mq rabbitmq-plugins enable rabbitmq_management rabbitmq_delayed_message_exchange 2>&1
    $r | ForEach-Object { Write-Info ('  ' + $_) }
    if ($LASTEXITCODE -ne 0) { Fail 'rabbitmq-plugins enable 失败（插件文件是否已挂进容器？见 docker/mq-plugins/）' }
    $list = (& docker exec mq rabbitmq-plugins list 2>&1) -join "`n"
    if ($list -notmatch '\[E\*\]\s+rabbitmq_delayed_message_exchange') {
        Fail '延迟消息插件没有处于启用状态（[E*] 标记缺失），trade-service 会声明不了 x-delayed-message 交换机'
    }
    Write-Info '  延迟消息插件已启用'
}

# 从已构建的 fat jar 里取依赖 jar（MallE2E 要 jackson，EsBulkLoad 要 mysql-connector）。
# 为什么这么做而不是让使用者自己准备 jar：fat jar 是我们刚刚构建出来的既有产物，
# 里面的 BOOT-INF/lib 就是这套代码真实编译时用的那一组依赖版本。
function Get-LibsFromFatJar {
    param([string]$JarPath, [string[]]$Patterns, [string]$Dest, [switch]$Optional)
    if (-not (Test-Path $JarPath)) {
        if ($Optional) { return @() }
        Fail "找不到 $JarPath（需要先完成构建）"
    }
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    $got = @()
    try {
        foreach ($e in $zip.Entries) {
            if ($e.FullName -notlike 'BOOT-INF/lib/*.jar') { continue }
            $leaf = Split-Path -Leaf $e.FullName
            foreach ($p in $Patterns) {
                if ($leaf -like $p) {
                    $target = Join-Path $Dest $leaf
                    if (-not (Test-Path $target)) {
                        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
                    }
                    $got += $leaf
                }
            }
        }
    }
    finally { $zip.Dispose() }
    if ($got.Count -eq 0) {
        if ($Optional) { return @() }
        Fail ("在 $JarPath 的 BOOT-INF/lib 里没找到：" + ($Patterns -join ', '))
    }
    return $got
}

# ---------------------------------------------------------------- 生命周期：-Reset / -Down
function Invoke-ComposeDown {
    param([switch]$RemoveVolumes)
    $files = @($AppsCompose, $AddonCompose, $InfraCompose)
    foreach ($f in $files) {
        if (-not (Test-Path $f)) { continue }
        # --profile full 必须显式给：不带 profile 时 down 不会碰 profile 作用域内的服务
        $a = @('compose', '-f', $f, '--profile', 'full', 'down', '--remove-orphans')
        if ($RemoveVolumes) { $a += '-v' }
        Invoke-Docker -DockerArgs $a -AllowFail -Quiet
    }
}

if ($Reset) {
    Write-Step 'Reset：停容器 + 删卷 + 删网络'
    Assert-Docker | Out-Null
    Invoke-ComposeDown -RemoveVolumes
    Invoke-Docker -DockerArgs @('network', 'rm', $Network) -AllowFail -Quiet
    # compose 之外手工起的同名容器（历史上存在过）也要清掉，否则端口会被占住
    foreach ($n in @('gateway', 'llmall-user-service', 'llmall-shop-service', 'llmall-item-service',
                     'llmall-content-service', 'llmall-trade-service', 'llmall-cart-service',
                     'llmall-pay-service', 'llmall-promotion-service', 'llmall-search-service',
                     'mysql', 'redis', 'nacos', 'mq', 'es', 'seata-server', 'kibana')) {
        Invoke-Docker -DockerArgs @('rm', '-f', $n) -AllowFail -Quiet
    }
    Write-Host ''
    Write-Host 'Reset 完成。下一步：pwsh -File demo.ps1' -ForegroundColor Green
    exit 0
}

if ($Down) {
    Write-Step 'Down：收尾'
    Assert-Docker | Out-Null
    Invoke-ComposeDown -RemoveVolumes:$Volumes
    if ($Volumes) { Invoke-Docker -DockerArgs @('network', 'rm', $Network) -AllowFail -Quiet }
    Write-Host ''
    Write-Host ("Down 完成$(if($Volumes){'（含数据卷）'}else{'（数据卷保留，加 -Volumes 一并删）'})") -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------- 0. 前置检查
Write-Step '前置检查'
Write-Info ("仓库根 : " + $RepoRoot)
Write-Info ("JDK    : " + (Get-GateJavaHome))
Write-Info ("宿主   : PowerShell " + $PSVersionTable.PSVersion + " (" + $PSVersionTable.PSEdition + ")")
$mvn = Get-GateMaven
Write-Info ("Maven  : " + $(if ($mvn) { $mvn } else { '未找到（-SkipBuild 时不需要）' }))

# -Preflight 的语义是"把所有问题一次报完"，所以这里不立刻 Fail 而是记账，
# 跑完所有检查再统一给结论；真正跑流程时（不加 -Preflight）仍是撞到就停。
$problems = New-Object System.Collections.ArrayList
function Note-Problem {
    param([string]$Text)
    if ($Preflight) { [void]$script:problems.Add($Text); Write-Warn2 $Text; return }
    Fail $Text
}

foreach ($f in @($InfraCompose, $AddonCompose, $AppsCompose)) {
    if (-not (Test-Path $f)) { Note-Problem "缺少 compose 文件：$f" }
}
# deploy/docker-compose-infra-lite.yml 是废弃桩（services: {}），明确不用它
if (Test-Path (Join-Path $RepoRoot 'deploy\docker-compose-infra-lite.yml')) {
    Write-Warn2 'deploy/docker-compose-infra-lite.yml 是废弃桩（services: {}），本脚本按文档用 docker/ 下的那份'
}
if (-not $SkipBuild -and -not $mvn) { Note-Problem '需要 Maven 构建（或传 -SkipBuild 复用已有镜像）' }

if (-not $SkipEs -and -not $ExternalEs) {
    $ikDir = Join-Path $RepoRoot 'docker\es\plugins\ik'
    if (-not (Test-Path $ikDir)) {
        Write-Warn2 "缺少 IK 分词器目录 docker\es\plugins\ik —— ES 索引 mapping 用的是 ik_max_word/ik_smart，"
        Write-Warn2 "没有它索引建不起来。放法见 docker\M0-环境搭建说明.md（把 IK 解压到 docker\es\plugins，目录名必须是 ik）。"
        Write-Warn2 "若 ES 已在本机原生装着（例如 D:\develop\elasticsearch-7.17.10 且 plugins\ik 就位），改用 -ExternalEs 跳过容器版。"
    }
}

# 冒烟与灌数要用的依赖都从**已构建的 fat jar** 里取。这里先探一遍：
# 构建跑过就当场验证抽取逻辑，没跑过就明确说"首次运行会由构建段产出"，不让人误以为缺件。
# 放在 docker 检查之前 —— 这些是本机就能查清的事，先把本机问题报完再谈容器运行时。
foreach ($probe in @(
    @{ Jar = 'llmall-item-service\target\llmall-item-service.jar'; Libs = @('jackson-databind-*.jar', 'jackson-core-*.jar', 'jackson-annotations-*.jar'); Who = 'MallE2E' },
    @{ Jar = 'llmall-search-service\target\llmall-search-service.jar'; Libs = @('mysql-connector*.jar'); Who = 'EsBulkLoad' }
)) {
    $jarPath = Join-Path $RepoRoot $probe.Jar
    if (Test-Path $jarPath) {
        $got = Get-LibsFromFatJar -JarPath $jarPath -Patterns $probe.Libs -Dest (Join-Path $WorkDir 'preflight-libs') -Optional
        if ($got.Count -gt 0) { Write-Info ("依赖   : " + $probe.Who + " 需要的 " + ($got -join ', ') + " 已在 " + $probe.Jar + " 里") }
        else { Note-Problem ($probe.Jar + " 的 BOOT-INF/lib 里没找到 " + ($probe.Libs -join '/') + "（" + $probe.Who + " 会失败）") }
    }
    else { Write-Info ("依赖   : " + $probe.Jar + " 尚未构建，" + $probe.Who + " 的依赖将由构建段产出") }
}

$dockerVer = Get-DockerState
if ($dockerVer) {
    Write-Info ("Docker : server " + $dockerVer)
    $dc = Invoke-Docker -DockerArgs @('compose', 'version', '--short') -Quiet
    if ($dc.Code -ne 0) { Note-Problem 'docker compose 插件不可用' }
    else { Write-Info ("Compose: v" + ($dc.Output -join ' ')) }
}
else {
    Note-Problem 'docker 不可用（找不到 docker，或守护进程没启动）'
}

if ($Preflight) {
    Write-Host ''
    if ($problems.Count -eq 0) {
        Write-Host '前置检查通过（-Preflight：未做任何变更）' -ForegroundColor Green
        exit 0
    }
    Write-Host ("前置检查发现 " + $problems.Count + " 项问题：") -ForegroundColor Red
    $problems | ForEach-Object { Write-Host ("  - " + $_) -ForegroundColor Red }
    Write-Host ''
    Write-Host '修掉后重跑 -Preflight。闸门本身不依赖容器：pwsh -File gates\verify-all.ps1' -ForegroundColor Yellow
    exit 2
}

# 真正跑流程：docker 是硬前置，缺了立刻停（这里才用会 exit 的 Assert-Docker）
Assert-Docker | Out-Null

# ---------------------------------------------------------------- 运行期状态（供 finally 使用）
$statsJob = $null
$watermark = @{}
$smokeOk = $false
$summary = New-Object System.Collections.ArrayList
function Add-Summary { param([string]$Text) [void]$script:summary.Add($Text); Write-Info $Text }

try {
    # ------------------------------------------------------------ 1. 构建
    if (-not $SkipBuild) {
        Write-Step '构建：mvn package + deploy/Dockerfile 单镜像'
        $mvnArgs = @('-DskipTests', 'package')
        $settings = Get-GateMavenSettings
        if ($settings) { $mvnArgs = @('-s', $settings) + $mvnArgs }
        if (-not $Online) { $mvnArgs = @('-o') + $mvnArgs }
        $env:JAVA_HOME = Get-GateJavaHome
        $env:Path = (Join-Path $env:JAVA_HOME 'bin') + [System.IO.Path]::PathSeparator + $env:Path
        Write-Info ('> mvn ' + ($mvnArgs -join ' '))
        Push-Location $RepoRoot
        $mvnOut = & $mvn @mvnArgs 2>&1 | Select-String -Pattern 'BUILD|ERROR|Total time' | Select-Object -Last 6
        $mvnCode = $LASTEXITCODE
        Pop-Location
        $mvnOut | ForEach-Object { Write-Info ('  ' + $_) }
        if ($mvnCode -ne 0) { Fail "mvn package 失败（退出码 $mvnCode）" }
        Add-Summary 'mvn package：BUILD SUCCESS'

        Invoke-Docker -DockerArgs @('build', '-f', 'deploy/Dockerfile', '-t', $ImageTag, '.') -Tail 12 | Out-Null
        Add-Summary "docker build：$ImageTag"
    }

    # ------------------------------------------------------------ 2. 网络 + 中间件
    Write-Step '中间件：infra-lite + ES addon'
    # 凭据来自 deploy/.env（不入库）：缺失时由 .env.example 生成，infra 与 apps 两份 compose 共用同一份。
    if (-not (Test-Path $EnvFile)) {
        Copy-Item $EnvExample $EnvFile
        $envText = [System.IO.File]::ReadAllText($EnvFile)
        $envText = $envText -replace '(?m)^LLMALL_REDIS_HOST=.*$', 'LLMALL_REDIS_HOST=redis'
        $envText = $envText -replace '(?m)^LLMALL_MQ_HOST=.*$', 'LLMALL_MQ_HOST=mq'
        [System.IO.File]::WriteAllText($EnvFile, $envText, (New-Object System.Text.UTF8Encoding($false)))
        Write-Info '已由 deploy/.env.example 生成 deploy/.env（凭据为演示默认值，对外部署前请替换）'
    }
    Invoke-Docker -DockerArgs @('network', 'create', $Network) -AllowFail -Quiet
    Invoke-Docker -DockerArgs @('compose', '--env-file', $EnvFile, '-f', $InfraCompose, 'up', '-d') | Out-Null
    # ES 只在 addon 里且不带 profile；infra-lite 的 ES 属于 profile full，不传 profile 就不会起，两者不冲突。
    # -ExternalEs 时不动容器：ES 已在宿主原生装好（本机就是这种拓扑），起容器反而会抢 9200 端口。
    if ($ExternalEs) { Write-Info "ES 用外部实例：$EsUrl（不起 addon 容器）" }
    else { Invoke-Docker -DockerArgs @('compose', '-f', $AddonCompose, 'up', '-d', 'elasticsearch') | Out-Null }

    Wait-MySql
    Wait-Http -Url "$NacosUrl/nacos/v1/console/health/readiness" -Name 'Nacos' -Seconds 240
    Initialize-MqPlugins
    if (-not $SkipEs) { Wait-Http -Url "$EsUrl/_cluster/health" -Name 'Elasticsearch' -Seconds 300 -Contains '"status"' }
    Add-Summary ("中间件：mysql / redis / nacos / mq 已起（mq 已启用延迟消息插件）" + $(if ($ExternalEs) { "；ES 用外部实例 $EsUrl" } else { "；ES 用 addon 容器" }))

    # ------------------------------------------------------------ 3. 建库与灌数
    # 顺序照抄 sql/merged/README.md：两处刻意破数字序（23 在 30 之后；50/51 连跑两遍），
    # 52 冻结不执行，61 默认不执行，62 全新初始化可跳过但跑了也无害（纯 ALTER COMMENT，幂等），
    # 63 是演示 / 冒烟环境专用（把 Jack 的支付口令预置成 888888，见该脚本头部说明）。
    if (-not $SkipSql) {
        Write-Step '建库与灌数：sql/merged 00 → 99'
        $base = Join-Path $RepoRoot 'sql'
        $plan = @(
            @{ F = "$SqlMerged\00-create-databases.sql";           Why = '建库（含 seata）' }
            @{ F = "$base\llmall-user.sql";                        Why = '原商城用户库' }
            @{ F = "$base\llmall-item.sql";                        Why = '原商城商品库（约 36MB，慢）' }
            @{ F = "$base\llmall-cart.sql";                        Why = '原商城购物车库' }
            @{ F = "$base\llmall-trade.sql";                       Why = '原商城交易库' }
            @{ F = "$base\llmall-pay.sql";                         Why = '原商城支付库' }
            @{ F = "$SqlMerged\01-undo-log-all.sql";               Why = '各库 undo_log' }
            @{ F = "$SqlMerged\02-shared-infra.sql";               Why = '消息表与消费幂等表' }
            @{ F = "$SqlMerged\03-seata-server.sql";               Why = 'Seata 1.5.2 全量建表' }
            @{ F = "$SqlMerged\03b-seata-server-1.5.2-upgrade.sql"; Why = 'Seata 1.4.2→1.5.2 增量' }
            @{ F = "$SqlMerged\10-shop.sql";                       Why = '门店域建表' }
            # 55 必须紧跟 10-shop、排在 50 之前：50 号按 shop 表全量生成套餐，
            # 而 id 1~14 这 14 家核心门店原本只活在演示库（点评迁移的产物），
            # 没有它们则门店只有 16 家、套餐只有 80 个，MallE2E 的 /items/900013 与"150 个套餐"直接红。
            @{ F = "$SqlMerged\55-seed-core-shops.sql";            Why = '14 家核心门店 + 类型 1/2（MallE2E 依赖门店 id 1~14）' }
            @{ F = "$SqlMerged\11-item-alter.sql";                 Why = '商品域改造 + stock_flow' }
            @{ F = "$SqlMerged\20-promotion.sql";                  Why = '营销域建表' }
            @{ F = "$SqlMerged\21-user-alter.sql";                 Why = '用户域改造' }
            @{ F = "$SqlMerged\04-balance-flow.sql";               Why = '资金流水表' }
            @{ F = "$SqlMerged\05-shop-message-log.sql";           Why = '门店域本地消息表' }
            @{ F = "$SqlMerged\06-content-message-log.sql";        Why = '内容域本地消息表' }
            @{ F = "$SqlMerged\22-user-time-default.sql";          Why = 'user 时间字段默认值' }
            @{ F = "$SqlMerged\30-content.sql";                    Why = '内容域建表' }
            @{ F = "$SqlMerged\23-content-image-default.sql";      Why = '依赖 30 建出的 blog 表，必须排在后面' }
            @{ F = "$SqlMerged\31-order-alter.sql";                Why = '交易域改造' }
            @{ F = "$SqlMerged\40-search.sql";                     Why = '搜索域建表' }
            @{ F = "$SqlMerged\41-search-geo.sql";                 Why = '依赖 40，补 x/y' }
            @{ F = "$SqlMerged\62-align-dict-comments.sql";        Why = '字典型注释对齐（纯 ALTER COMMENT，幂等）' }
            @{ F = "$SqlMerged\60-user-pay-password.sql";          Why = 'pay_password 列，必须先于应用发布' }
            @{ F = "$SqlMerged\50-seed-local-life-data.sql";       Why = '演示数据（第 1 遍）' }
            @{ F = "$SqlMerged\51-seed-more-data.sql";             Why = '演示数据（第 1 遍）' }
            @{ F = "$SqlMerged\50-seed-local-life-data.sql";       Why = '为 51 新增的门店再生成套餐与评价' }
            @{ F = "$SqlMerged\51-seed-more-data.sql";             Why = '补齐依赖评价的聚合数据' }
            # 63 排在 60 与全部 seed 之后：它只改 Jack 一行的 pay_password，依赖 60 建出的列与
            # 第 2 步 llmall-user.sql 里的演示账号；放在 seed 尾部可避免将来 seed 脚本新增
            # 对 user 表的写操作时把它改回初始口令（那样冒烟会停在"首次支付前强制改密"）。
            # 口径仅演示 / 冒烟环境（把支付口令设成公开可知的 888888），生产库不得执行。
            @{ F = "$SqlMerged\63-user-demo-pay-password.sql";     Why = 'Jack 支付口令预置 888888（仅演示/冒烟）' }
        )
        foreach ($p in $plan) { Invoke-SqlFile -Path $p.F | Out-Null }

        Write-Info '跳过 sql/merged/52-fix-orphan-pay-orders.sql —— 冻结，本轮不动'
        Write-Info '跳过 sql/merged/61-user-weak-password-reset.sql —— 文档口径为「默认不执行，需人工启用」'

        if ($WithDianpingMigration) {
            Invoke-SqlFile -Path "$SqlMerged\99-migrate-dianping-data.sql" | Out-Null
        }
        else {
            Write-Info '跳过 sql/merged/99-migrate-dianping-data.sql —— 依赖仓库外的 dianping_src 库（加 -WithDianpingMigration 才跑）'
        }

        # 52a：只读清点，判据是最后一个结果集的第 1 列（非 user1 的已支付孤儿单数）
        Write-Step '52a 孤儿单只读清点（执行 52 之前的强制前置）'
        $auditOut = Invoke-SqlFile -Path "$SqlMerged\52a-orphan-pay-orders-audit.sql"
        $auditOut | Set-Content -Encoding UTF8 (Join-Path $WorkDir "52a-audit-$Stamp.txt")
        $auditOut | ForEach-Object { Write-Info ('  ' + $_) }
        # 取"最后一个结果集的第一个字段"。52a 的查询 3 是最后一条且只返回 1 行，
        # 所以 batch 模式（-N -B，无表头）下它就是最后一行、第一个 tab 字段。
        # 先滤掉 mysql 客户端自己的告警行（口令写在命令行上会多出一条 [Warning]），否则可能被当成数据读。
        $dataLines = @($auditOut | Where-Object { $_ -and $_.Trim() -ne '' -and $_ -notmatch '^mysql:' -and $_ -notmatch '^\[Warning\]' })
        if ($dataLines.Count -eq 0) { Fail '52a 没有任何输出 —— 建库段可能没跑成功，先看上面的日志' }
        $orphans = (($dataLines[-1]) -split "`t")[0].Trim()
        if ($orphans -ne '0') {
            Write-Host ''
            Write-Host "52a 清点结果 non_user1_paid_orphans = $orphans（≠ 0）—— 停下报告。" -ForegroundColor Red
            Write-Host '按 docs/已知偏差台账-2026-10-15.md：此值非 0 时不得执行 52；需先统一退款段与关闭段的口径。' -ForegroundColor Red
            exit 3
        }
        Add-Summary '52a 清点：non_user1_paid_orphans = 0（verdict 安全）'
    }

    # ------------------------------------------------------------ 4. Nacos 配置导入
    if (-not $SkipNacos) {
        Write-Step 'Nacos 共享配置与网关路由（Open API 导入）'
        $nacosDir = Join-Path $RepoRoot 'nacos-config'
        $files = @(Get-ChildItem $nacosDir -File | Where-Object { $_.Name -like '*.yaml' -or $_.Name -eq 'gateway-routes.json' })

        # 先用**原始文本**读一遍现网值，把每份分成"不存在 / 已一致 / 不一致"三类。
        # 【为什么必须用 Invoke-WebRequest 而不是 Invoke-RestMethod】RestMethod 会按 Content-Type 把
        # json 响应反序列化成对象，`"$back"` 拿到的就不是原文（gateway-routes.json 会变成一个对象数组的
        # 字符串），于是"回读比对"对 json 配置恒为假 —— 看上去在核对，实际什么都没核对。
        $toImport = @()
        $conflict = @()
        foreach ($f in $files) {
            $getUrl = "$NacosUrl/nacos/v1/cs/configs?dataId=$([System.Uri]::EscapeDataString($f.Name))&group=DEFAULT_GROUP"
            $remote = $null
            try { $remote = Get-HttpText (Invoke-WebRequest -Uri $getUrl -TimeoutSec 20 -UseBasicParsing) } catch { $remote = $null }
            $local = ([System.IO.File]::ReadAllText($f.FullName) -replace "`r`n", "`n").Trim()
            if ($null -eq $remote -or "$remote".Trim() -eq '') {
                Write-Info ("  " + $f.Name + " : Nacos 上不存在 → 将导入")
                $toImport += $f
            }
            elseif (("$remote" -replace "`r`n", "`n").Trim() -eq $local) {
                Write-Info ("  " + $f.Name + " : 已与仓库一致 → 跳过（幂等）")
            }
            else {
                Write-Warn2 ($f.Name + " : 与仓库版本**不一致**（远端 " + "$remote".Length + " B / 仓库 " + $local.Length + " B）")
                $conflict += $f
            }
        }
        # 不一致时默认停下：导入会覆盖别人在 Nacos 上改过的配置，属于"动别人的环境"，要显式授权。
        if ($conflict.Count -gt 0 -and -not $ForceNacos) {
            Write-Host ''
            Write-Host ("有 " + $conflict.Count + " 份配置与仓库版本不一致：" + (($conflict | ForEach-Object { $_.Name }) -join ', ')) -ForegroundColor Red
            Write-Host '导入会用仓库版本覆盖它们。确认这是你要的（例如这一整套本来就是按仓库重建的），加 -ForceNacos 重跑；' -ForegroundColor Red
            Write-Host '否则请先把 Nacos 上的版本导出备份，或改用 -SkipNacos。' -ForegroundColor Red
            exit 4
        }
        foreach ($f in ($toImport + $conflict)) {
            $type = if ($f.Extension -eq '.json') { 'json' } else { 'yaml' }
            $content = [System.IO.File]::ReadAllText($f.FullName)
            # 自己拼 form body 而不用哈希表：PowerShell 的哈希表表单编码在 5.1 下按 ASCII 走，
            # 中文配置会被写坏；EscapeDataString 先把非 ASCII 转成 %XX，发出去的一定是纯 ASCII。
            $form = 'dataId=' + [System.Uri]::EscapeDataString($f.Name) +
                    '&group=DEFAULT_GROUP&type=' + $type +
                    '&content=' + [System.Uri]::EscapeDataString($content)
            try {
                $r = Invoke-RestMethod -Method Post -Uri "$NacosUrl/nacos/v1/cs/configs" -Body $form `
                    -ContentType 'application/x-www-form-urlencoded; charset=UTF-8' -TimeoutSec 30
            }
            catch { Fail ("导入 " + $f.Name + " 失败：" + $_.Exception.Message) }
            if ("$r" -ne 'true') { Fail ("Nacos 拒绝导入 " + $f.Name + "（返回 $r）") }
            # 回读校验同样用原始文本（理由见上）。Nacos 的 GET 有读缓存，故规范化换行后比对。
            Start-Sleep -Milliseconds 300
            $getUrl = "$NacosUrl/nacos/v1/cs/configs?dataId=$([System.Uri]::EscapeDataString($f.Name))&group=DEFAULT_GROUP"
            $back = Get-HttpText (Invoke-WebRequest -Uri $getUrl -TimeoutSec 30 -UseBasicParsing)
            $a = ($content -replace "`r`n", "`n").Trim()
            $b = ("$back" -replace "`r`n", "`n").Trim()
            if ($a -ne $b) {
                $cmpFile = Join-Path $WorkDir ("nacos-readback-" + $f.Name + ".txt")
                [System.IO.File]::WriteAllText($cmpFile, "$back", (New-Object System.Text.UTF8Encoding($false)))
                Write-Warn2 ("回读与本地文件不一致：" + $f.Name + "（已存 " + $cmpFile + "），导入本身已成功")
            }
            else { Write-Info ("  导入并回读通过：" + $f.Name + "  (" + $type + ", " + $content.Length + " 字节)") }
        }
        Add-Summary ("Nacos：导入 " + ($toImport.Count + $conflict.Count) + " 份，跳过已一致 " + ($files.Count - $toImport.Count - $conflict.Count) + " 份；回读校验完成")
    }

    # ------------------------------------------------------------ 5. ES 索引与灌数
    if (-not $SkipEs) {
        Write-Step 'ES：建索引（mapping）并灌数'
        $mappingFile = Join-Path $RepoRoot 'gates\es-shop-mapping.json'
        if (-not (Test-Path $mappingFile)) { Fail "缺少 $mappingFile" }
        $mapping = [System.IO.File]::ReadAllText($mappingFile)
        $indexUrl = "$EsUrl/shop_search"
        $created = $false
        try {
            Invoke-RestMethod -Method Put -Uri $indexUrl -Body ([System.Text.Encoding]::UTF8.GetBytes($mapping)) -ContentType 'application/json' -TimeoutSec 30 | Out-Null
            $created = $true
            Write-Info '  已按 es-shop-mapping.json 建索引 shop_search'
        }
        catch {
            Write-Info '  索引已存在，跳过创建（下面会复核它的 mapping 是不是这一份）'
        }
        # 复核而不是"建不上就放过"：_bulk 会自动建索引，一旦 mapping 建失败而 bulk 先跑，
        # 就会留下一个动态映射的索引 —— 里面 name 是 standard 分词，检索结果会悄悄变差而没有任何报错。
        $idx = $null
        try { $idx = Invoke-RestMethod -Method Get -Uri $indexUrl -TimeoutSec 15 } catch { }
        if (-not $idx) { Fail "索引 shop_search 不存在（PUT 失败且 _bulk 也没能建出来）" }
        $analyzer = $idx.shop_search.mappings.properties.name.analyzer
        if ($analyzer -ne 'ik_max_word') {
            Fail ("shop_search 的 name.analyzer = '$analyzer'，不是 es-shop-mapping.json 里的 ik_max_word —— " +
                  "说明索引是被 _bulk 动态建出来的或来自别的 mapping。先删掉该索引再重跑：" +
                  "curl -X DELETE $indexUrl")
        }
        Write-Info ("  索引 mapping 复核通过（name.analyzer=" + $analyzer + "，本次" + $(if ($created) { '新建' } else { '复用' }) + "）")
        # 灌数不在这里做：EsBulkLoad 读的是 MySQL 读模型表 llmall-search.shop_search_doc，
        # 而这张表由 /search/view/rebuild 在应用起来之后生成 —— 此刻它还是空的，
        # _bulk 会拿到空 body 被 ES 以 400 request body is required 拒掉。灌数挪到 rebuild 之后。
        Write-Info '  灌数放在应用就绪 + /search/view/rebuild 之后（读模型此刻还是空的）'
    }

    # ------------------------------------------------------------ 6. 应用
    Write-Step ("应用：docker compose --profile $Profile up -d")
    Push-Location (Join-Path $RepoRoot 'deploy')
    try {
        # .env 缺失时按文档从 .env.example 生成，并把三处地址改成容器名（本次中间件都在 local-life 网络里）
        if (-not (Test-Path '.env')) {
            Copy-Item '.env.example' '.env'
            $envText = [System.IO.File]::ReadAllText((Join-Path (Get-Location) '.env'))
            $envText = $envText -replace '(?m)^LLMALL_REDIS_HOST=.*$', 'LLMALL_REDIS_HOST=redis'
            $envText = $envText -replace '(?m)^LLMALL_MQ_HOST=.*$', 'LLMALL_MQ_HOST=mq'
            [System.IO.File]::WriteAllText((Join-Path (Get-Location) '.env'), $envText, (New-Object System.Text.UTF8Encoding($false)))
            Write-Info '已由 .env.example 生成 deploy/.env（Redis/MQ 指向容器名 redis / mq）'
        }
        else { Write-Info 'deploy/.env 已存在，沿用（不改动你的配置）' }
        Invoke-Docker -DockerArgs @('compose', '-f', 'docker-compose-apps.yml', '--profile', $Profile, 'up', '-d') | Out-Null
    }
    finally { Pop-Location }

    Wait-Http -Url "$GatewayUrl/actuator/health" -Name '网关' -Seconds 420 -Contains '"status":"UP"'
    Add-Summary ("应用：profile $Profile 已起，网关 /actuator/health = UP")

    # 种子数据灌完后要重建一次视图与 GEO 缓存（README 第 6 步）。
    # 【注意】这两个都是 POST（写方法），网关的鉴权只对 GET/HEAD/OPTIONS 走只读白名单，
    # 写方法一律必须带令牌 —— 裸 POST 会得到 401（Unauthorized），看着像"接口不存在"。
    # 所以先按演示账号登录拿令牌，再带着 authorization 头调用（与 MallE2E 一样的裸令牌写法）。
    $demoToken = ''
    try {
        $loginResp = Invoke-RestMethod -Method Post -Uri ($GatewayUrl + '/users/login') `
            -Body '{"username":"Jack","password":"123456"}' -ContentType 'application/json' -TimeoutSec 20
        $demoToken = [string]$loginResp.accessToken
    }
    catch { Fail ("登录演示账号失败（重建类接口需要令牌）：" + $_.Exception.Message) }
    if (-not $demoToken) { Fail '登录演示账号没拿到 accessToken（种子数据里的演示账号或口令对不上？）' }
    Write-Info ("  已登录演示账号，令牌 " + $demoToken.Length + " 字符")

    # /search/view/rebuild 是硬检查：紧接着的 ES 灌数读的就是它生成的 llmall-search.shop_search_doc，
    # 它没跑成则灌数一定读到 0 行，后面所有"搜索"类断言都失去了数据基础。
    try {
        $rb = Invoke-WebRequest -Method Post -Uri ($GatewayUrl + '/search/view/rebuild') `
            -Headers @{ authorization = $demoToken } -TimeoutSec 120 -UseBasicParsing
        Write-Info ("  POST /search/view/rebuild -> " + $rb.StatusCode)
    }
    catch { Fail ("POST /search/view/rebuild 失败：" + $_.Exception.Message + "（ES 灌数依赖它生成的 MySQL 读模型）") }
    try {
        $rb2 = Invoke-WebRequest -Method Post -Uri ($GatewayUrl + '/shops/cache/geo/rebuild') `
            -Headers @{ authorization = $demoToken } -TimeoutSec 120 -UseBasicParsing
        Write-Info ("  POST /shops/cache/geo/rebuild -> " + $rb2.StatusCode)
    }
    catch { Write-Warn2 ("POST /shops/cache/geo/rebuild 未成功（" + $_.Exception.Message + "），不阻断流程") }

    # ------------------------------------------------------------ 6.5 ES 灌数（读模型 → ES）
    if (-not $SkipEs) {
        Write-Step 'ES：灌数（MySQL 读模型 → shop_search）'
        $libDir = Join-Path $WorkDir 'libs-es'
        Get-LibsFromFatJar -JarPath (Join-Path $RepoRoot 'llmall-search-service\target\llmall-search-service.jar') `
            -Patterns @('mysql-connector*.jar') -Dest $libDir | Out-Null
        $javaExe = Get-GateJava
        Write-Info '> EsBulkLoad（MySQL 读模型 → ES _bulk）'
        $bulkOut = & $javaExe -cp (Join-Path $libDir '*') (Join-Path $RepoRoot 'gates\EsBulkLoad.java') `
            ("127.0.0.1:" + $DbHostPort) $DbUser $DbPw $DbName $EsUrl 'shop_search' 2>&1
        $bulkOut | ForEach-Object { Write-Info ('  ' + $_) }
        if ($LASTEXITCODE -ne 0) { Fail 'EsBulkLoad 失败' }
        if (($bulkOut | Out-String) -match 'errors"\s*:\s*true') { Fail 'EsBulkLoad 的 _bulk 响应里 errors=true' }
        $cnt = Invoke-RestMethod -Method Get -Uri "$EsUrl/shop_search/_count" -TimeoutSec 15
        Add-Summary ("ES：shop_search 文档数 " + $cnt.count)
    }

    # ------------------------------------------------------------ 7. 冒烟 + 峰值内存
    if (-not $SkipSmoke) {
        Write-Step '冒烟：scripts/MallE2E.java'
        # 水位线：只记录，finally 里按它删除"本次冒烟新建的行"
        $wmTables = @(
            'llmall-cart.cart',
            'llmall-trade.order', 'llmall-trade.order_detail',
            'llmall-pay.pay_order',
            'llmall-user.balance_flow', 'llmall-user.points_flow',
            'llmall-item.stock_flow',
            'llmall-content.blog', 'llmall-content.blog_comments', 'llmall-content.blog_like', 'llmall-content.feed_inbox',
            'llmall-search.search_history'
        )
        # 先问 information_schema 哪些目标表真的存在且有 id 列 —— 免得某个库/表名与预期不符时
        # 直接把整个 demo 中断在"清理准备"这一步
        $schemaList = "'llmall-cart','llmall-trade','llmall-pay','llmall-user','llmall-item','llmall-content','llmall-search'"
        $existOut = Invoke-SqlText -Batch -Sql ("SELECT CONCAT(TABLE_SCHEMA,'.',TABLE_NAME) FROM information_schema.COLUMNS WHERE COLUMN_NAME='id' AND TABLE_SCHEMA IN (" + $schemaList + ")")
        $existSet = @{}
        foreach ($l in $existOut) { if ($l) { $existSet[$l.Trim()] = $true } }
        foreach ($t in $wmTables) {
            if (-not $existSet.ContainsKey($t)) { Write-Warn2 "冒烟清理水位线跳过 $t（该表不存在或没有 id 列）"; continue }
            $db, $tb = $t -split '\.', 2
            $v = Invoke-SqlText -Batch -Sql "SELECT IFNULL(MAX(id),0) FROM ``$db``.``$tb``"
            if ($v.Count -gt 0) { $watermark[$t] = [int64]$v[0] }
        }
        Write-Info ('已记录 ' + $watermark.Count + ' 张表的自增 id 水位线')

        # docker stats 采样（`docker stats --no-stream` 是瞬时值，峰值必须自己采）
        $statsJob = Start-Job -ScriptBlock {
            param($Out, $IntervalSec)
            $max = @{}
            while (-not (Test-Path ($Out -replace '\.txt$', '.stop'))) {
                $lines = & docker stats --no-stream --format '{{.Name}}|{{.MemUsage}}' 2>$null
                foreach ($l in $lines) {
                    $p = $l -split '\|'
                    if ($p.Count -ne 2) { continue }
                    $mb = 0.0
                    if ($p[1] -match '([\d\.]+)\s*GiB') { $mb = [double]$matches[1] * 1024 }
                    elseif ($p[1] -match '([\d\.]+)\s*MiB') { $mb = [double]$matches[1] }
                    elseif ($p[1] -match '([\d\.]+)\s*KiB') { $mb = [double]$matches[1] / 1024 }
                    if ($mb -gt 0 -and (-not $max.ContainsKey($p[0]) -or $mb -gt $max[$p[0]])) { $max[$p[0]] = $mb }
                }
                Start-Sleep -Seconds $IntervalSec
            }
            $max.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { $_.Key + [char]9 + ('{0:N1}' -f $_.Value) } |
                Set-Content -Encoding UTF8 $Out
        } -ArgumentList $StatsFile, 5

        $jacksonDir = Join-Path $WorkDir 'libs-e2e'
        # MallE2E 硬编码演示账号 Jack/123456、网关 http://localhost:8080，且**没有 System.exit** ——
        # 失败时进程仍以 0 退出，所以只能解析它的汇总行，不能看退出码。
        Get-LibsFromFatJar -JarPath (Join-Path $RepoRoot 'llmall-item-service\target\llmall-item-service.jar') `
            -Patterns @('jackson-databind-*.jar', 'jackson-core-*.jar', 'jackson-annotations-*.jar') -Dest $jacksonDir | Out-Null
        $javaExe = Get-GateJava
        $e2eOut = & $javaExe -cp (Join-Path $jacksonDir '*') (Join-Path $RepoRoot 'scripts\MallE2E.java') 2>&1
        $e2eOut | ForEach-Object { Write-Info ('  ' + $_) }
        $e2eText = ($e2eOut | Out-String)
        $e2eText | Set-Content -Encoding UTF8 (Join-Path $WorkDir "MallE2E-$Stamp.txt")

        if ($e2eText -match '网关未就绪') { Fail 'MallE2E：网关未就绪（服务没起全或健康检查未通过）' }
        if ($e2eText -notmatch '通过 (\d+) 项，失败 (\d+) 项') { Fail 'MallE2E：没有解析到汇总行，输出格式可能变了' }
        $passN = [int]$matches[1]; $failN = [int]$matches[2]
        if ($failN -ne 0) { Fail "MallE2E：通过 $passN 项，失败 $failN 项" }
        $smokeOk = $true
        Add-Summary "MallE2E 冒烟：通过 $passN 项，失败 0 项"
    }
    else {
        Write-Step '冒烟：已按 -SkipSmoke 跳过'
    }
}
finally {
    # ---------------------------------------------------------- 8. 清理冒烟数据 + 收峰值内存
    if ($statsJob) {
        New-Item -ItemType File -Force -Path ($StatsFile -replace '\.txt$', '.stop') | Out-Null
        Wait-Job $statsJob -Timeout 60 | Out-Null
        Receive-Job $statsJob | Out-Null
        Remove-Job $statsJob -Force
    }
    if ($watermark.Count -gt 0 -and -not $NoCleanup) {
        Write-Host ''
        Write-Host '清理本次冒烟新建的数据（按自增 id 水位线，只删冒烟之后的行）' -ForegroundColor Cyan
        foreach ($t in $watermark.Keys) {
            $db, $tb = $t -split '\.', 2
            $n = Invoke-SqlText -Sql "DELETE FROM ``$db``.``$tb`` WHERE id > $($watermark[$t])" -Batch
            Write-Info ("  $t : id > $($watermark[$t]) 已清理")
        }
        Write-Info '（删除范围仅限本次冒烟新建的行；种子数据不受影响）'
    }
    if (Test-Path $StatsFile) {
        Write-Host ''
        Write-Host 'docker stats 峰值内存（每 5 秒采样一次，取最大值）' -ForegroundColor Cyan
        Get-Content -Encoding UTF8 $StatsFile | ForEach-Object {
            $p = $_ -split "`t"
            if ($p.Count -eq 2) { Write-Host ("  {0,-28} {1,8} MiB" -f $p[0], $p[1]) }
        }
    }

    # 报告
    $rep = New-Object System.Collections.ArrayList
    [void]$rep.Add("# demo.ps1 运行报告 $Stamp")
    [void]$rep.Add('')
    [void]$rep.Add("- 仓库根：``$RepoRoot``")
    [void]$rep.Add("- JDK：``$(Get-GateJavaHome)``")
    [void]$rep.Add("- 宿主：PowerShell $($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))")
    [void]$rep.Add("- profile：$Profile")
    [void]$rep.Add('')
    [void]$rep.Add('## 各步结果')
    foreach ($s in $summary) { [void]$rep.Add("- $s") }
    [void]$rep.Add('')
    [void]$rep.Add('## 冒烟')
    [void]$rep.Add("- MallE2E 全绿：$smokeOk")
    if (Test-Path $StatsFile) {
        [void]$rep.Add('')
        [void]$rep.Add('## docker stats 峰值内存（MiB）')
        [void]$rep.Add('')
        [void]$rep.Add('| 容器 | 峰值 MiB |')
        [void]$rep.Add('|---|---|')
        Get-Content -Encoding UTF8 $StatsFile | ForEach-Object {
            $p = $_ -split "`t"
            if ($p.Count -eq 2) { [void]$rep.Add("| $($p[0]) | $($p[1]) |") }
        }
    }
    [System.IO.File]::WriteAllLines($ReportFile, $rep, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ''
    Write-Host "报告：$ReportFile" -ForegroundColor Cyan
}

Write-Host ''
if ($smokeOk) { Write-Host 'demo 全流程完成：MallE2E 全绿' -ForegroundColor Green; exit 0 }
Write-Host 'demo 流程完成（冒烟被跳过或未执行）' -ForegroundColor Yellow
exit 0
