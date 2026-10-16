# 校验工具：从 Java 源码中剔除注释与空白后比对，证明"只改了注释"
# 用法: pwsh -File compare-code.ps1 -Baseline <dir> -Root <dir> -Rel <relative path>
param(
    [Parameter(Mandatory = $true)][string]$Baseline,
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string[]]$Rel
)

# 状态机剥注释：保留字符串/字符字面量原样，丢弃 // 与 /* */ 注释内容，最后只保留非空白字符
function Get-CodeOnly {
    param([string]$Text)
    $sb = New-Object System.Text.StringBuilder
    $i = 0
    $n = $Text.Length
    $state = 'normal'   # normal | line | block | str | chr
    while ($i -lt $n) {
        $c = $Text[$i]
        $next = if ($i + 1 -lt $n) { $Text[$i + 1] } else { [char]0 }
        switch ($state) {
            'normal' {
                if ($c -eq '/' -and $next -eq '/') { $state = 'line'; $i += 2; continue }
                if ($c -eq '/' -and $next -eq '*') { $state = 'block'; $i += 2; continue }
                if ($c -eq '"') { $state = 'str'; [void]$sb.Append($c); $i++; continue }
                if ($c -eq "'") { $state = 'chr'; [void]$sb.Append($c); $i++; continue }
                if (-not [char]::IsWhiteSpace($c)) { [void]$sb.Append($c) }
                $i++
            }
            'line' {
                if ($c -eq "`n") { $state = 'normal' }
                $i++
            }
            'block' {
                if ($c -eq '*' -and $next -eq '/') { $state = 'normal'; $i += 2; continue }
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

$bad = 0
foreach ($r in $Rel) {
    $oldPath = Join-Path $Baseline $r
    $newPath = Join-Path $Root $r
    if (-not (Test-Path $oldPath)) { Write-Output "MISSING-BASELINE $r"; $bad++; continue }
    if (-not (Test-Path $newPath)) { Write-Output "MISSING-NEW      $r"; $bad++; continue }
    $a = Get-CodeOnly ([System.IO.File]::ReadAllText($oldPath))
    $b = Get-CodeOnly ([System.IO.File]::ReadAllText($newPath))
    if ($a -ceq $b) {
        Write-Output ("CODE-IDENTICAL   " + $r + "   (可执行代码逐字符一致)")
    }
    else {
        $bad++
        Write-Output ("CODE-DIFF!!!     " + $r)
        $min = [Math]::Min($a.Length, $b.Length)
        $p = 0
        while ($p -lt $min -and $a[$p] -eq $b[$p]) { $p++ }
        $s = [Math]::Max(0, $p - 120)
        $len = [Math]::Min(240, $min - $s)
        Write-Output ("  基线: ..." + $a.Substring($s, $len))
        Write-Output ("  现状: ..." + $b.Substring($s, [Math]::Min(240, $b.Length - $s)))
    }
}
Write-Output ("---- 结果: " + $(if ($bad -eq 0) { "全部文件可执行代码未变 (OK)" } else { "$bad 个文件存在代码差异" }))
