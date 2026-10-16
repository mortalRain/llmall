# 注释剥离核心（单一实现，被 gate.ps1 与 selftest.ps1 共用）
#
# 为什么单独成文件：生产校验（gate.ps1）与闸门自校验（selftest.ps1）**必须用同一份实现** ——
# 两份实现迟早漂移，而闸门的可信度完全建立在「剥离规则与实际一致」之上。
#
# 三条设计要点（每条都对应一个踩过的坑）：
#   1. 注释符出现在字符串 / 字符字面量里时**不是注释**：
#      Java 的 "http://a//c"、SQL 的 'a--b'、Lua 的 "a--b"、PS1 的 "a#b"。
#   2. 块注释内部**保留换行符**，使剔除注释后的行号与原文件严格对齐，便于定位差异。
#   3. 所有注释符按**实际长度**比较：`--[[` 是 4 字符、`#` 是 1 字符。
#      若固定用 2 字符去比，`--[[` 会被当成行注释 `--`、`#` 永远比不中 ——
#      结果是「块注释内部」与「# 注释行」被算成代码行，**改注释反而被判成改代码**（假阳性）。

# 各语言注释符表。YAML 的 `#` 仅在行首（含前置空白）或前面是空白时才是注释，
# 本实现按状态机处理：出现在引号内不会被识别（`password: "a#b"` 安全），
# 但 `key: v#c`（# 前无空白）会被当作注释 —— 这不是 YAML 语义。
# 生产路径的 YAML 校验走 YamlFingerprint（实现了真正的 YAML 注释规则），
# 本表里的 YAML 项仅供 selftest 校验剥离器本身。
$CommentSyntax = @{
    '.java'       = @{ Line = @('//');      BlockStart = '/*';   BlockEnd = '*/' }
    '.lua'        = @{ Line = @('--');      BlockStart = '--[['; BlockEnd = ']]' }
    '.sql'        = @{ Line = @('--', '#'); BlockStart = '/*';   BlockEnd = '*/' }
    '.ps1'        = @{ Line = @('#');       BlockStart = '<#';   BlockEnd = '#>' }
    '.sh'         = @{ Line = @('#');       BlockStart = '';     BlockEnd = '' }
    '.js'         = @{ Line = @('//');      BlockStart = '/*';   BlockEnd = '*/' }
    '.yaml'       = @{ Line = @('#');       BlockStart = '';     BlockEnd = '' }
    '.yml'        = @{ Line = @('#');       BlockStart = '';     BlockEnd = '' }
    '.toml'       = @{ Line = @('#');       BlockStart = '';     BlockEnd = '' }
    '.properties' = @{ Line = @('#');       BlockStart = '';     BlockEnd = '' }
}
$DefaultSyntax = @{ Line = @('#'); BlockStart = ''; BlockEnd = '' }   # Dockerfile / .dockerignore / .env

function Get-CommentSyntax {
    param([string]$Ext)
    $e = $Ext.ToLower()
    if ($e -eq '') { $e = '.dockerfile' }
    if ($CommentSyntax.ContainsKey($e)) { return $CommentSyntax[$e] }
    return $DefaultSyntax
}

function Get-StrippedText {
    param([string]$Text, [string]$Ext)
    $syn = Get-CommentSyntax $Ext
    $lineTok = $syn.Line
    $blockStart = $syn.BlockStart
    $blockEnd = $syn.BlockEnd

    $sb = New-Object System.Text.StringBuilder
    $i = 0; $n = $Text.Length
    $state = 'normal'   # normal | line | block | str | chr
    while ($i -lt $n) {
        $c = $Text[$i]
        switch ($state) {
            'normal' {
                # 块注释起始符：按实际长度比较（--[[ 是 4 字符）
                if ($blockStart -ne '' -and $i + $blockStart.Length -le $n -and $Text.Substring($i, $blockStart.Length) -eq $blockStart) {
                    $state = 'block'; $i += $blockStart.Length; continue
                }
                # 行注释符：同样按实际长度比较（# 是 1 字符）
                $lineHit = $false
                foreach ($tok in $lineTok) {
                    if ($i + $tok.Length -le $n -and $Text.Substring($i, $tok.Length) -eq $tok) {
                        $state = 'line'; $i += $tok.Length; $lineHit = $true; break
                    }
                }
                if ($lineHit) { continue }
                if ($c -eq '"') { $state = 'str'; [void]$sb.Append($c); $i++; continue }
                if ($c -eq "'") { $state = 'chr'; [void]$sb.Append($c); $i++; continue }
                [void]$sb.Append($c); $i++
            }
            'line' {
                if ($c -eq "`n") { $state = 'normal'; [void]$sb.Append($c) }
                $i++
            }
            'block' {
                if ($blockEnd -ne '' -and $i + $blockEnd.Length -le $n -and $Text.Substring($i, $blockEnd.Length) -eq $blockEnd) {
                    $state = 'normal'; $i += $blockEnd.Length; continue
                }
                if ($c -eq "`n") { [void]$sb.Append($c) }   # 保留换行以对齐行号
                $i++
            }
            'str' {
                if ($c -eq '\') { [void]$sb.Append($c); if ($i + 1 -lt $n) { [void]$sb.Append($Text[$i + 1]) }; $i += 2; continue }
                if ($c -eq '"') { $state = 'normal' }
                [void]$sb.Append($c); $i++
            }
            'chr' {
                if ($c -eq '\') { [void]$sb.Append($c); if ($i + 1 -lt $n) { [void]$sb.Append($Text[$i + 1]) }; $i += 2; continue }
                if ($c -eq "'") { $state = 'normal' }
                [void]$sb.Append($c); $i++
            }
        }
    }
    return $sb.ToString()
}

# 剥注释后的「代码行序列」：逐行两端 Trim、丢弃空行。
# 口径说明：**缩进与空白不参与比对** —— 这是跨语言统一的内容级口径。
# 它比 Java 侧的 FP2（只 trimEnd，对书写形态敏感）宽松；两者分工见 docs/注释规范.md 附录。
function Get-CodeLines {
    param([string]$Path)
    $raw = [System.IO.File]::ReadAllText($Path)
    $ext = [System.IO.Path]::GetExtension($Path).ToLower()
    $stripped = Get-StrippedText -Text $raw -Ext $ext
    ($stripped -split "\r?\n") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }
}

function Get-Sha {
    param([string[]]$Lines)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($Lines -join "`n"))
    ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
}

# 逐行分类（供注释占比统计使用）：返回 @{ Total; Comment; Code; Blank }
# 判据：剥注释后为空但原行非空 → 注释行；剥注释后仍非空 → 代码行；原行本就空 → 空行。
function Get-LineStats {
    param([string]$Path)
    $raw = [System.IO.File]::ReadAllText($Path)
    $ext = [System.IO.Path]::GetExtension($Path).ToLower()
    $stripped = Get-StrippedText -Text $raw -Ext $ext
    $orig = $raw -split "\r?\n"
    $strip = $stripped -split "\r?\n"
    $comment = 0; $code = 0; $blank = 0
    for ($i = 0; $i -lt $orig.Count; $i++) {
        $o = $orig[$i].Trim()
        $s = if ($i -lt $strip.Count) { $strip[$i].Trim() } else { '' }
        if ($o -eq '') { $blank++ }
        elseif ($s -eq '') { $comment++ }
        else { $code++ }
    }
    return [pscustomobject]@{ Total = $orig.Count; Comment = $comment; Code = $code; Blank = $blank }
}
