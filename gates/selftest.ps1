# 闸门自校验用例集（每次改动 strip.ps1 / gate.ps1 / lint-comments.ps1 的规则，必须先跑通本文件）
#
# 为什么需要它：闸门本身也会出错，而且**出错时不报警**。
# 本轮就修掉过三处闸门自身的缺陷：
#   ① `--[[`（4 字符）被固定 2 字符比较误当成行注释 `--`
#   ② `#`（1 字符）永远比不中 → ps1/sh 一改注释就报「代码被改动」
#   ③ `历史叙述` 正则把规范推荐的「误改后果」判为违规，逼着执行者绕开正确写法
# 带缺陷的闸门比没有闸门更危险：它会放过真实损伤，或诱导出错误的「修复」。
#
# 用法: pwsh -File gates\selftest.ps1
# 退出码 0 = 全部用例通过；1 = 有用例失败（此时**不得**使用闸门结论）

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\gate-env.ps1"
. "$PSScriptRoot\strip.ps1"

$java = Get-GateJava
$cp = Initialize-GateClasses
$lint = "$PSScriptRoot\lint-comments.ps1"

$script:Pass = 0
$script:Fail = 0
$script:FailNames = @()

function Check {
    param([string]$Name, [string]$Expected, [string]$Actual)
    if ($Expected -ceq $Actual) {
        $script:Pass++
        Write-Host ("  [PASS] " + $Name)
    }
    else {
        $script:Fail++
        $script:FailNames += $Name
        Write-Host ("  [FAIL] " + $Name)
        Write-Host ("         期望: " + $Expected)
        Write-Host ("         实际: " + $Actual)
    }
}

function New-TempFile {
    param([string]$Text, [string]$Ext)
    $p = Join-Path $env:TEMP ("selftest_" + [guid]::NewGuid().ToString('N') + $Ext)
    [System.IO.File]::WriteAllText($p, $Text, (New-Object System.Text.UTF8Encoding($false)))
    return $p
}

# 剥注释后的「代码行序列」（与 gate.ps1 的 any 类型校验同一口径）
function CodesOf {
    param([string]$Text, [string]$Ext)
    $p = New-TempFile -Text $Text -Ext $Ext
    $lines = @(Get-CodeLines $p)
    Remove-Item $p -Force
    return ($lines -join '|')
}

function StrippedOf {
    param([string]$Text, [string]$Ext)
    return (Get-StrippedText -Text $Text -Ext $Ext)
}

Write-Host '=== 闸门自校验 ===' -ForegroundColor Cyan

# ---------------------------------------------------------------- 场景 A：只改注释
Write-Host ''
Write-Host '【A】正常注释改动 —— 期望：代码行序列完全不变' -ForegroundColor Yellow

$javaA = @('package a;', '', 'public class A {', '    // 旧的行注释', '    private int x = 1;', '}', '') -join "`n"
$javaB = @('package a;', '', 'public class A {', '    // 新的行注释（内容全变了）', '    private int x = 1;', '}', '') -join "`n"
Check 'A1 Java 行注释改动' (CodesOf $javaA '.java') (CodesOf $javaB '.java')

$javadocA = @('public class A {', '    /**', '     * 旧的三行 JavaDoc', '     * 第二行', '     */', '    void f() {}', '}', '') -join "`n"
$javadocB = @('public class A {', '    /**', '     * 新的 JavaDoc（只剩一行）', '     */', '    void f() {}', '}', '') -join "`n"
Check 'A2 Java JavaDoc 增删行' (CodesOf $javadocA '.java') (CodesOf $javadocB '.java')

$sqlA = "SELECT 1`n-- 旧注释`nFROM t`n"
$sqlB = "SELECT 1`n-- 新注释，更长一些`nFROM t`n"
Check 'A3 SQL 行注释改动' (CodesOf $sqlA '.sql') (CodesOf $sqlB '.sql')

# 长度陷阱回归：--[[ 是 4 字符，块注释内部的行**不能**被当成代码行
# 【对应缺陷#2】剥离器曾固定按 2 字符比较注释符 → --[[ 被当成行注释 -- → 改注释被判成改代码
$luaA = "--[[`n  块注释第一行（旧）`n  块注释第二行（旧）`n]]`nlocal x = 1`n"
$luaB = "--[[`n  块注释只剩一行（新）`n]]`nlocal x = 1`n"
Check 'A4【缺陷#2】Lua 块注释内部改动（--[[ 长度陷阱回归）' (CodesOf $luaA '.lua') (CodesOf $luaB '.lua')

# 长度陷阱回归：'#' 是 1 字符
# 【对应缺陷#2】单字符注释符永远比不中 2 字符的窗口 → ps1/sh 一改注释就报「代码被改动」
$psA = "# 旧注释`nWrite-Host 'hi'`n"
$psB = "# 新注释`nWrite-Host 'hi'`n"
Check 'A5【缺陷#2】PS1 # 注释改动（单字符注释符陷阱回归）' (CodesOf $psA '.ps1') (CodesOf $psB '.ps1')

$shA = "#!/bin/bash`n# 旧注释`necho hi`n"
$shB = "#!/bin/bash`n# 新注释`necho hi`n"
Check 'A6 SH # 注释改动' (CodesOf $shA '.sh') (CodesOf $shB '.sh')

$yamlA = "server:`n  # 旧注释`n  port: 8080`n"
$yamlB = "server:`n  # 新注释`n  port: 8080`n"
Check 'A7 YAML # 注释改动' (CodesOf $yamlA '.yaml') (CodesOf $yamlB '.yaml')

$luaLineA = "local x = 1 -- 旧`n"
$luaLineB = "local x = 1 -- 新`n"
Check 'A8 Lua 行尾注释改动' (CodesOf $luaLineA '.lua') (CodesOf $luaLineB '.lua')

# ---------------------------------------------------------------- 场景 B：代码篡改
Write-Host ''
Write-Host '【B】代码篡改 —— 期望：必须被识别为「代码已变」' -ForegroundColor Yellow

$codeB1 = $javaA.Replace('int x = 1;', 'int x = 2;')
Check 'B1 Java 改字面量应被发现' 'DIFFERENT' $(if ((CodesOf $javaA '.java') -cne (CodesOf $codeB1 '.java')) { 'DIFFERENT' } else { 'SAME' })

$sqlC = "SELECT 1`n-- 注释`nFROM t WHERE a = 2`n"
Check 'B2 SQL 改 WHERE 应被发现' 'DIFFERENT' $(if ((CodesOf $sqlA '.sql') -cne (CodesOf $sqlC '.sql')) { 'DIFFERENT' } else { 'SAME' })

$luaC = "local x = 1`nredis.call('INCR', KEYS[1])`n"
$luaCOrig = "local x = 1`nredis.call('DECR', KEYS[1])`n"
Check 'B3 Lua 改命令应被发现' 'DIFFERENT' $(if ((CodesOf $luaCOrig '.lua') -cne (CodesOf $luaC '.lua')) { 'DIFFERENT' } else { 'SAME' })

# ---------------------------------------------------------------- 场景 C：边界（字符串 / 字面量 / 行尾）
Write-Host ''
Write-Host '【C】词法边界 —— 期望：字符串里的注释符不当注释' -ForegroundColor Yellow

$javaUrl = 'class A { String url = "http://a/b//c"; }'
Check 'C1 字符串里的 // 不被剥离' 'True' $((StrippedOf $javaUrl '.java').Contains('http://a/b//c')).ToString()

$javaEsc = 'class A { String s = "he said \"hi\" // not a comment"; }'
Check 'C2 转义引号后的 // 不被剥离' 'True' $((StrippedOf $javaEsc '.java').Contains('// not a comment')).ToString()

$javaChar = "class A { char slash = '/'; }"
Check 'C3 字符字面量 "/" 不被剥离' 'True' $((StrippedOf $javaChar '.java').Contains("'/'")).ToString()

$sqlStr = "SELECT 'a--b' FROM t;"
Check 'C4 SQL 字符串里的 -- 不被剥离' 'True' $((StrippedOf $sqlStr '.sql').Contains("'a--b'")).ToString()

$luaStr = 'local s = "a--b"'
Check 'C5 Lua 字符串里的 -- 不被剥离' 'True' $((StrippedOf $luaStr '.lua').Contains('"a--b"')).ToString()

$javaTrail = 'class A { int x = 1; // 行尾注释 }'
$st = StrippedOf $javaTrail '.java'
Check 'C6 行尾注释被剥离、代码保留' 'True' (($st.Contains('int x = 1;')) -and (-not $st.Contains('行尾注释'))).ToString()

$javaBlock = 'class A { /* // 这不是行注释 */ int x = 1; }'
$st2 = StrippedOf $javaBlock '.java'
Check 'C7 块注释里的 // 不产生残留' 'True' ((-not $st2.Contains('这不是行注释')) -and $st2.Contains('int x = 1;')).ToString()

# 块注释跨行时**保留换行** → 剥离后的行号与原文件对齐（便于定位差异）
$multi = "class A {`n/* 一`n二`n三 */`nint x = 1;`n}"
Check 'C8 块注释跨行后行号仍对齐（剥离后行数不变）' '6' (@((StrippedOf $multi '.java') -split "`n").Count).ToString()
Check 'C8b 块注释内部的行不被算作代码行' '3' (@((StrippedOf $multi '.java') -split "`n" | Where-Object { $_.Trim() -ne '' }).Count).ToString()

# ---------------------------------------------------------------- 场景 D：内容闸门规则
Write-Host ''
Write-Host '【D】内容质量闸门 —— 期望：推荐表述不误报、违规表述必被报' -ForegroundColor Yellow

function LintResult {
    param([string]$Text)
    $p = New-TempFile -Text $Text -Ext '.java'
    $out = Invoke-GateNative -Exe (Get-GatePowerShell) -NativeArgs @('-NoProfile', '-File', $lint, '-Path', $p)
    Remove-Item $p -Force
    $joined = ($out | Out-String)
    if ($joined -match '结论: 通过') { return 'CLEAN' }
    if ($joined -match '结论: 待清理 (\d+) 处') { return ('DIRTY:' + $matches[1]) }
    return 'UNKNOWN'
}

# D1：规范**推荐**的措辞不能被误报（「误改后果」里含「改后」）
# 【对应缺陷#3】`历史叙述` 正则曾把规范推荐的「误改后果」判为违规，逼着执行者绕开正确写法
$good = @(
 'public class D {'
 '    // 【注意】误改后果：把本值调大（例如 2 小时）会让旧令牌在窗口内继续可用'
 '    private int x = 1;'
 '}'
 '') -join "`n"
Check 'D1【缺陷#3】「误改后果」不被判为历史叙述' 'CLEAN' (LintResult $good)

# D2：JavaDoc 起始符 /** 与手机号掩码 138****1234、glob /items/** 都不能被当成粗体
$good2 = @(
 'public class D {'
 '    /** 脱敏格式：138****1234 */'
 '    // 该路径匹配 /items/** 前缀'
 '    private String phone;'
 '}'
 '') -join "`n"
Check 'D2 /** 与 138****1234 与 /items/** 不被判为粗体' 'CLEAN' (LintResult $good2)

# D3–D6：反向用例 —— 闸门必须**真的在工作**（否则「全 0」毫无意义）
$bad1 = "public class D {`n    /**`n     * <h3>核心职责</h3>`n     */`n    private int x;`n}"
Check 'D3 HTML 标签必须被报出' 'DIRTY' $((LintResult $bad1) -replace ':\d+$','')

$bad2 = "public class D {`n    // 这是**粗体**写法`n    private int x;`n}"
Check 'D4 粗体标记必须被报出' 'DIRTY' $((LintResult $bad2) -replace ':\d+$','')

$bad3 = "public class D {`n    // ======= 以下是核心逻辑 =======`n    private int x;`n}"
Check 'D5 装饰横幅必须被报出' 'DIRTY' $((LintResult $bad3) -replace ':\d+$','')

$bad4 = "public class D {`n    // 改前这里是这样写的`n    private int x;`n}"
Check 'D6 历史叙述必须被报出' 'DIRTY' $((LintResult $bad4) -replace ':\d+$','')

# ---------------------------------------------------------------- 场景 E：端到端指纹
Write-Host ''
Write-Host '【E】端到端指纹（CommentFingerprint）—— 期望：注释改动通过、代码与缩进改动被拦' -ForegroundColor Yellow

$tmpRoot = Join-Path $env:TEMP ('selftest_fp_' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $tmpRoot 'a') -Force | Out-Null
$target = Join-Path $tmpRoot 'a\Test.java'
$baseFile = Join-Path $tmpRoot 'base.txt'
$enc = New-Object System.Text.UTF8Encoding($false)

$v0 = @(
 'package a;'
 ''
 '/** 旧类注释 */'
 'public class Test {'
 '    // 旧行注释'
 '    private int x = 1;'
 ''
 '    public int get() {'
 '        return x;'
 '    }'
 '}'
 '') -join "`n"

$vComment = @(
 'package a;'
 ''
 '/** 新类注释：职责说明、边界约束、误改后果都补上了 */'
 'public class Test {'
 '    // 新行注释'
 '    // 再加一行说明'
 '    private int x = 1;'
 ''
 '    public int get() {'
 '        return x;'
 '    }'
 '}'
 '') -join "`n"

$vCode = $v0.Replace('int x = 1;', 'int x = 2;')
$vIndent = $v0.Replace('        return x;', '            return x;')
# 变体二：两行合成一行（三行的方法体压成一行）
$vMerge = $v0.Replace("    public int get() {`n        return x;`n    }", "    public int get() { return x; }")
# 变体三：吞掉行尾换行，使 return 行与下面的 } 粘连
$vSwallow = $v0.Replace("        return x;`n    }", "        return x;    }")
# 口径边界：删掉全部空行（FP2 有意丢弃空行，故不应被判为改动）
$vNoBlank = ($v0 -split "`n" | Where-Object { $_ -ne '' }) -join "`n"

[System.IO.File]::WriteAllText($target, $v0, $enc)
Invoke-GateNative -Exe $java -NativeArgs @('-cp', $cp, 'CommentFingerprint', 'snapshot', $tmpRoot, $baseFile) | Out-Null

[System.IO.File]::WriteAllText($target, $vComment, $enc)
$o1 = Invoke-GateNative -Exe $java -NativeArgs @('-cp', $cp, 'CommentFingerprint', 'verify', $tmpRoot, $baseFile)
Check 'E1 只改注释 → 通过' 'True' ($o1 -match '结论: 通过').ToString()

[System.IO.File]::WriteAllText($target, $vCode, $enc)
$o2 = Invoke-GateNative -Exe $java -NativeArgs @('-cp', $cp, 'CommentFingerprint', 'verify', $tmpRoot, $baseFile)
Check 'E2 改代码字面量 → 被 FP1 拦下' 'True' (($o2 -match '结论: 失败') -and ($o2 -match 'FP1')).ToString()

# 【对应缺陷#1】verify 曾只看 FP1、忽略 FP2：输出打印 [FP2 格式被改动] 却仍给「结论: 通过」+ exit 0。
# 下面三个变体是那条缺陷的永久用例 —— 书写形态的任何改动都必须被拦下。
[System.IO.File]::WriteAllText($target, $vIndent, $enc)
$o3 = Invoke-GateNative -Exe $java -NativeArgs @('-cp', $cp, 'CommentFingerprint', 'verify', $tmpRoot, $baseFile)
Check 'E3【缺陷#1】变体一·纯缩进变化 → 被 FP2 拦下' 'True' (($o3 -match '结论: 失败') -and ($o3 -match 'FP2')).ToString()

[System.IO.File]::WriteAllText($target, $vMerge, $enc)
$o4 = Invoke-GateNative -Exe $java -NativeArgs @('-cp', $cp, 'CommentFingerprint', 'verify', $tmpRoot, $baseFile)
Check 'E4【缺陷#1】变体二·两行合成一行 → 被 FP2 拦下' 'True' (($o4 -match '结论: 失败') -and ($o4 -match 'FP2')).ToString()

[System.IO.File]::WriteAllText($target, $vSwallow, $enc)
$o5 = Invoke-GateNative -Exe $java -NativeArgs @('-cp', $cp, 'CommentFingerprint', 'verify', $tmpRoot, $baseFile)
Check 'E5【缺陷#1】变体三·吞掉行尾换行致两行粘连 → 被 FP2 拦下' 'True' (($o5 -match '结论: 失败') -and ($o5 -match 'FP2')).ToString()

# 口径边界（不是缺陷）：FP2 **有意**丢弃空行 —— 否则删掉一条注释行留下的空行也会被当成改动。
[System.IO.File]::WriteAllText($target, $vNoBlank, $enc)
$o6 = Invoke-GateNative -Exe $java -NativeArgs @('-cp', $cp, 'CommentFingerprint', 'verify', $tmpRoot, $baseFile)
Check 'E6 删掉空行 → 不拦（FP2 有意丢弃空行，否则删注释行必误报）' 'True' ($o6 -match '结论: 通过').ToString()

Remove-Item $tmpRoot -Recurse -Force

# ---------------------------------------------------------------- 场景 F：运行时行为（台账 F-07）
Write-Host ''
Write-Host '【F】运行时行为验证 —— 超时场景下请求只发出一次' -ForegroundColor Yellow

# 【对应台账 F-07】用 JDK 自带的 HttpServer 做"收到请求就挂住到超时"的桩服务端，
# 再用真实 Feign 客户端打它，数服务端收到几次。关键是**对照**：只断言"禁重试时 1 次"没有说服力，
# 必须同时证明"这条路径本来会重试"（对照组用 Retryer.Default 应收到多于 1 次）。
#
# 【依赖自足】这里只用仓库内自带的两样东西，不再去 %TEMP% 找解压出来的 fat jar ——
# 那要求"先跑过一次 run-obs-demo.ps1"，于是全新 clone 上闸门自校验会莫名其妙地红，
# 而"闸门自己跑不起来"和"用例不通过"在退出码上无法区分。
#   · gates/lib/feign-core-11.8.jar —— 被测的就是 Feign 的重试器本身，版本须与项目一致
#   · llmall-api 的 NoRetryFeignConfig.java 源码 —— 探针要取**生产代码**里的那个 Retryer，
#     而不是在测试里重写一遍 NEVER_RETRY（那样验证的是测试自己的复述，不是产品代码）
$probeOut = Join-Path $GateBuildRoot 'probe'
$feignJar = Join-Path $GateRoot 'lib\feign-core-11.8.jar'
$noRetrySrc = Join-Path $RepoRoot 'llmall-api\src\main\java\com\llmall\api\config\NoRetryFeignConfig.java'
$springCp = @(
    (Join-Path $GateRoot 'lib\spring-context-5.3.27.jar'),
    (Join-Path $GateRoot 'lib\spring-core-5.3.27.jar')
) -join ';'
if (-not (Test-Path $feignJar) -or -not (Test-Path $noRetrySrc)) {
    Check 'F0 运行时验证的前置：gates/lib/feign-core 与 NoRetryFeignConfig.java 均在位' 'True' 'False'
}
else {
    New-Item -ItemType Directory -Force -Path $probeOut | Out-Null
    Invoke-GateNative -Exe (Get-GateJavac) -NativeArgs @('-encoding', 'UTF-8', '-nowarn', '-cp', "$feignJar;$springCp", '-d', $probeOut, $noRetrySrc) | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Check 'F0 运行时验证的前置：NoRetryFeignConfig 可用 gates/lib 编译' 'True' 'False'
    }
    else {
        # llmall-api 的 NoRetryFeignConfig 单独编译到 gates/build/probe；llmall-api 是普通库 jar，
        # 其余类探针用不到，故不需要解压 fat jar。
        $cpF = "$probeOut;$feignJar"
        $outF = Invoke-GateNative -Exe $java -NativeArgs @('-cp', $cpF, (Join-Path $GateRoot 'tools\FeignRetryProbe.java'))
        Check 'F1【F-07】禁重试 → 服务端恰好收到 1 次' 'True' ($outF -match '断言 A（禁重试 → 恰好 1 次）: 通过').ToString()
        Check 'F2【F-07】对照有效：有重试时服务端收到多于 1 次' 'True' ($outF -match '断言 B（有重试 → 多于 1 次，证明对照有效）: 通过').ToString()
    }
}

# ---------------------------------------------------------------- 汇总
Write-Host ''
Write-Host ('=' * 60)
Write-Host ("通过 " + $script:Pass + " / 失败 " + $script:Fail) -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Red' })
if ($script:Fail -gt 0) {
    Write-Host '失败用例：'
    $script:FailNames | ForEach-Object { Write-Host ("  - " + $_) }
    Write-Host '结论: 闸门自校验未通过 —— 先修闸门，再使用它的结论'
    exit 1
}
Write-Host '结论: 闸门自校验全部通过 —— 剥离规则、内容规则、端到端指纹行为均符合预期'
exit 0
