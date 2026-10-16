# 生成 JWT 签名密钥库（llmall.jks）
#
# 为什么需要这个脚本：llmall.jks 是 JWT 签名私钥，不入库（见 .gitignore）。
# 但它又是服务启动的必需件（llmall.jwt.location 指向 classpath:llmall.jks），
# 所以首次 clone 后需要先跑本脚本生成一份本地密钥库。
#
# 用法（在 llmall 根目录执行）：
#   pwsh -File .\scripts\gen-jks.ps1
#   pwsh -File .\scripts\gen-jks.ps1 -Password 你的口令 -Force
#
# 【注意】各服务读的口令是环境变量 LLMALL_JKS_PASSWORD，默认 llmall123。
#         若这里用了别的口令，启动时必须同步设置该环境变量，否则报密钥库口令错误。
#
# 生产环境：不要用本脚本的默认口令，密钥库应改由卷挂载（llmall.jwt.location 支持 file: 前缀）
#           并定期轮换，见 docs/最终验收与交付清单.md 的"上线前必做"。
param(
    [string]$Password = 'llmall123',
    [string]$Alias    = 'llmall',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$targets = @(
    (Join-Path $root 'llmall-gateway\src\main\resources\llmall.jks'),
    (Join-Path $root 'llmall-user-service\src\main\resources\llmall.jks')
)

# keytool 随 JDK 提供；优先用项目约定的 JDK 11，其次找 PATH 上的 keytool
$keytool = 'D:\develop\jdk11\bin\keytool.exe'
if (-not (Test-Path $keytool)) {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "找不到 keytool：请安装 JDK 并把它加入 PATH，或修改本脚本里的 `$keytool 路径" }
    $keytool = $cmd.Source
}

foreach ($jks in $targets) {
    if ((Test-Path $jks) -and -not $Force) {
        Write-Host "已存在，跳过：$jks  （要覆盖请加 -Force）" -ForegroundColor Yellow
        continue
    }
    $dir = Split-Path $jks -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

    & $keytool -genkeypair `
        -alias $Alias `
        -keyalg RSA -keysize 2048 -validity 3650 `
        -keystore $jks `
        -storepass $Password -keypass $Password `
        -storetype JKS `
        -dname "CN=llmall-demo, OU=dev, O=llmall, C=CN" 2>&1 | Out-Null

    if ($LASTEXITCODE -ne 0) { throw "生成失败：$jks" }
    Write-Host "已生成：$jks  （别名 $Alias）" -ForegroundColor Green
}

Write-Host ''
Write-Host "完成。启动服务前确认 llmall.jwt.password 与环境变量 LLMALL_JKS_PASSWORD 都等于你设置的口令。" -ForegroundColor Cyan
