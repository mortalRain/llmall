# 闸门运行环境的**单一解析点**（gates/ 下所有脚本共用，被点源引入）
#
# 为什么单独成文件：路径、JDK、PowerShell 宿主三件事原先硬编码在每个脚本里
# （$jdk = 'D:\develop\jdk11\bin'、$root = 'D:\dsh\llmall'、子进程直接写 pwsh），
# 结果是闸门只能在写出它的那台机器上跑，换台机器或换个 clone 目录就全线失效。
# 解析逻辑只留这一份，其余脚本一律引用这里的变量与函数。
#
# 解析优先级：
#   JDK          环境变量 JAVA_HOME → PATH 上的 java 反推安装目录 → 报错
#   仓库根        本文件所在目录的上一级（gates/ 就放在仓库根下）
#   PowerShell    当前正在运行本脚本的宿主（$PSHOME），不依赖 PATH 上有 pwsh
#   Maven         环境变量 MAVEN_BIN → PATH 上的 mvn.cmd / mvn
#   Maven settings 环境变量 MAVEN_SETTINGS → ~/.m2/settings.xml（都没有就不传 -s）

$GateRoot = $PSScriptRoot
$RepoRoot = Split-Path -Parent $GateRoot
$GateBuildRoot = Join-Path $GateRoot 'build'

# 让 JVM 的**输出流**用 UTF-8。
# 【为什么三个属性都要写】JDK 18 起 file.encoding 的语义变了（JEP 400）：它只决定默认字符集，
# 不再决定 System.out/System.err 用什么编码；JDK 19 起输出流编码改由 stdout.encoding /
# stderr.encoding 单独控制。只写 -Dfile.encoding=UTF-8 时，在 JDK 25 上 System.out 仍按
# 系统 ANSI 代码页（简体中文机器是 GBK）输出，而父脚本按 UTF-8 解码 —— 得到乱码，
# 而**乱码不报错**，只会让 '结论: 通过' 这类匹配静默失配（实测：全新 clone + PATH 上的 JDK 25，
# 自校验 E1~E6、F1~F2 全红，但闸门本身没有任何异常）。
# 多写的两个属性在 JDK 11 上是未知系统属性，被忽略，无副作用。
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'

# 控制台按 UTF-8 收发。Windows PowerShell 5.1 默认用本地代码页（简体中文机器上是 936）：
# 中文一旦跨进程传递（父脚本过滤子脚本输出、子脚本回读父脚本的清单）就会变成乱码，
# 而乱码不会报错，只会让 Where-Object 的匹配静默失配 —— 闸门"通过"了但根本没看。
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$script:GateOnWindows = ($env:OS -eq 'Windows_NT')

function Get-GateExeSuffix {
    if ($script:GateOnWindows) { return '.exe' }
    return ''
}

function Get-GatePowerShell {
    # 返回"当前宿主"的可执行文件全路径：谁跑起了闸门，子脚本就用谁跑，
    # 不要求 PATH 上有 pwsh（本机只有 Windows PowerShell 5.1 时同样可用）。
    $name = if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh' } else { 'powershell' }
    $here = Join-Path $PSHOME ($name + (Get-GateExeSuffix))
    if (Test-Path $here) { return $here }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "找不到 PowerShell 宿主（既不在 `$PSHOME=$PSHOME，也不在 PATH 上）"
}

function Get-GateJavaHome {
    if ($env:JAVA_HOME) {
        $probe = Join-Path $env:JAVA_HOME ('bin' + [System.IO.Path]::DirectorySeparatorChar + 'java' + (Get-GateExeSuffix))
        if (Test-Path $probe) { return $env:JAVA_HOME.TrimEnd('\', '/') }
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) {
        # ...\jdk-11\bin\java.exe → 去掉文件名得 bin，再去掉 bin 得 JAVA_HOME
        $bin = Split-Path -Parent $cmd.Source
        return (Split-Path -Parent $bin)
    }
    throw '找不到 JDK：请设置 JAVA_HOME，或把 java 放进 PATH（闸门需要 JDK 11+，见 gates/README.md）'
}

function Get-GateJava {
    Join-Path (Get-GateJavaHome) ('bin' + [System.IO.Path]::DirectorySeparatorChar + 'java' + (Get-GateExeSuffix))
}

function Get-GateJavac {
    Join-Path (Get-GateJavaHome) ('bin' + [System.IO.Path]::DirectorySeparatorChar + 'javac' + (Get-GateExeSuffix))
}

function Get-GateJavaVersion {
    # 解析出的 JDK 实际版本（打印在 verify-all 开头，把"跑在什么环境上"写进证据里）。
    # 项目本身要求 JDK 11（见 pom.xml 的 maven.compiler.*）；闸门脚本在 11~25 上都能跑，
    # 但编译段必须用项目要求的版本，所以这里把版本显示出来而不是悄悄用。
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # JAVA_TOOL_OPTIONS 会往 stderr 打一行 "Picked up ..."，先滤掉再取版本行
        $lines = & (Get-GateJava) -version 2>&1 | Where-Object { $_ -notmatch 'Picked up' }
        return ([string]($lines | Select-Object -First 1)).Trim()
    }
    catch {
        return '（无法取得版本）'
    }
    finally {
        $ErrorActionPreference = $old
    }
}

function Initialize-GateClasses {
    # 把两个指纹工具编译到 gates/build/classes（构建产物不入库，见 .gitignore）。
    # 判定"需要重编"用时间戳：类文件缺失，或源文件比类文件新。
    $out = Join-Path $GateBuildRoot 'classes'
    $sources = @(
        (Join-Path $GateRoot 'CommentFingerprint.java'),
        (Join-Path $GateRoot 'YamlFingerprint.java')
    )
    $need = $false
    foreach ($s in $sources) {
        $cls = Join-Path $out ([System.IO.Path]::GetFileNameWithoutExtension($s) + '.class')
        if (-not (Test-Path $cls)) { $need = $true; break }
        if ((Get-Item $s).LastWriteTimeUtc -gt (Get-Item $cls).LastWriteTimeUtc) { $need = $true; break }
    }
    if ($need) {
        New-Item -ItemType Directory -Force -Path $out | Out-Null
        Write-Host '编译指纹工具 → gates/build/classes ...' -ForegroundColor DarkGray
        & (Get-GateJavac) -encoding UTF-8 -d $out @sources
        if ($LASTEXITCODE -ne 0) { throw "编译指纹工具失败（javac 退出码 $LASTEXITCODE）" }
    }
    return $out
}

function Get-GateMaven {
    if ($env:MAVEN_BIN -and (Test-Path $env:MAVEN_BIN)) { return $env:MAVEN_BIN }
    foreach ($n in @('mvn.cmd', 'mvn')) {
        $cmd = Get-Command $n -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return $null
}

function Get-GateMavenSettings {
    if ($env:MAVEN_SETTINGS -and (Test-Path $env:MAVEN_SETTINGS)) { return $env:MAVEN_SETTINGS }
    $home = [System.Environment]::GetFolderPath('UserProfile')
    if ($home) {
        $def = Join-Path $home ('.m2' + [System.IO.Path]::DirectorySeparatorChar + 'settings.xml')
        if (Test-Path $def) { return $def }
    }
    return $null
}

function Invoke-GateNative {
    # 跑原生程序（java / javac / mvn）并把 stdout+stderr 一并收回。
    #
    # 【为什么必须包一层】Windows PowerShell 5.1 有一条坑：$ErrorActionPreference='Stop' 时，
    # 原生程序的 stderr 与 stdout 合流（2>&1）会抛 NativeCommandError 并中断脚本本身。
    # 而 JDK 每次启动都往 stderr 打一行 "Picked up JAVA_TOOL_OPTIONS: ..." ——
    # 于是只要脚本开了 Stop，整轮自校验会在第一条 java 命令处直接崩掉（pwsh 7 下完全正常）。
    # 这里临时把 EA 降为 Continue，既避开该行为，又不丢掉 stderr 的内容。
    param(
        [Parameter(Mandatory = $true)][string]$Exe,
        [string[]]$NativeArgs = @()
    )
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $Exe @NativeArgs 2>&1 | Out-String)
    }
    finally {
        $ErrorActionPreference = $old
    }
}

function Invoke-GateScript {
    # 在子进程里跑另一个闸门脚本，返回 @{ ExitCode; Output }。
    # 不能在当前进程内直接 & 调用：那些脚本用 exit 表达结论，会把父脚本一起终止。
    # 输出一并收回（而不是直接打到控制台），因为父脚本要按结论关键字过滤后再展示。
    param(
        [Parameter(Mandatory = $true)][string]$Script,
        [string[]]$Arguments = @()
    )
    $ps = Get-GatePowerShell
    $argv = @('-NoProfile')
    if ($script:GateOnWindows) { $argv += @('-ExecutionPolicy', 'Bypass') }
    $argv += @('-File', $Script)
    $argv += $Arguments
    $output = & $ps @argv 2>&1
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = @($output) }
}
