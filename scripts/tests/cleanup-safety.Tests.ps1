param([string]$RepoRoot = (Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$results = @()
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('llmall-cleanup-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$testRoot/classes/META-INF/services" -Force | Out-Null
$oldProbe = $env:SMOKE_JDBC_PROBE
$oldDrift = $env:SMOKE_FIXTURE_DRIFT
$env:SMOKE_JDBC_PROBE = "$testRoot/jdbc.log"
$env:SMOKE_FIXTURE_DRIFT = '0'
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = Join-Path (Split-Path $javac -Parent) 'java.exe'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Case([string]$Name, [scriptblock]$Body) {
    try { & $Body; $script:results += [pscustomobject]@{name=$Name; passed=$true}; Write-Host "PASS $Name" }
    catch { $script:results += [pscustomobject]@{name=$Name; passed=$false}; Write-Host "FAIL $Name : $($_.Exception.Message)" }
}
function Invoke-Probe([string]$Mode, [string]$File = "$testRoot/snapshot.json") {
    $ErrorActionPreference = 'Continue'
    $output = & $java -cp "$testRoot/classes" SmokeRollback $Mode 'offline.invalid:1' 'fixture' 'unused' $File 2>&1
    return @{Code=$LASTEXITCODE; Text=($output | Out-String)}
}

try {
    $tokens = $null; $errors = $null
    $demo = [Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'demo.ps1'), [ref]$tokens, [ref]$errors)
    Case 'demo parses and keeps NoCleanup compatibility' {
        Assert-True ($errors.Count -eq 0) 'demo.ps1 parse error'
        Assert-True (@($demo.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'NoCleanup' }).Count -eq 1) 'NoCleanup parameter removed'
    }
    Case 'demo has no watermark SQL deletion path' {
        $source = $demo.Extent.Text
        Assert-True ($source -notmatch '(?i)DELETE\s+FROM|\$watermark|\$wmTables') 'Watermark deletion remains'
    }
    foreach ($flag in @($false, $true)) {
        Case "demo preservation policy NoCleanup=$flag" {
            $function = $demo.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Write-SmokeDataPolicy'}, $true)
            Assert-True ($null -ne $function) 'Missing preservation policy'
            $script:messages = @()
            function Write-Info([string]$Text) { $script:messages += $Text }
            function Invoke-SqlText { throw 'Must not access database' }
            Invoke-Expression $function.Extent.Text
            $NoCleanup = $flag
            Write-SmokeDataPolicy
            Assert-True (($script:messages -join '') -match '保留') 'Missing retained-data notice'
            Assert-True (($script:messages -join '') -match '停用') 'Missing disabled-cleanup notice'
            $calls = @($demo.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Write-SmokeDataPolicy'}, $true))
            Assert-True ($calls.Count -ge 1) 'Preservation policy never called'
        }
    }
    foreach ($file in @('sql/cleanup-e2e-data.sql','sql/cleanup-bench-data.sql','sql/merged/52-fix-orphan-pay-orders.sql')) {
        Case "SQL entry disabled: $file" {
            $sql = [IO.File]::ReadAllText((Join-Path $RepoRoot $file)) -replace '(?m)^\s*--[^\r\n]*', ''
            Assert-True ($sql.Trim() -match "\ASIGNAL SQLSTATE '45000'\s+SET MESSAGE_TEXT = 'CLEANUP_DISABLED:[^']+';\z") 'SQL must contain only one explicit disabled error, no trailing writes'
        }
    }

    [IO.File]::WriteAllText("$testRoot/classes/META-INF/services/java.sql.Driver", 'ReadOnlyJdbcFixture', $utf8)
    $direct = @'
public class CleanupDirectProbe {
  public static void main(String[] args) throws Exception {
    try { SmokeRollback.rollback(null, "offline.invalid:1", args[0]); }
    catch (UnsupportedOperationException e) {
      if (e.getMessage().contains("CLEANUP_DISABLED")) { System.out.println("BLOCKED"); return; }
      throw e;
    }
    throw new AssertionError("rollback accepted");
  }
}
'@
    [IO.File]::WriteAllText("$testRoot/CleanupDirectProbe.java", $direct, $utf8)
    & $javac --release 11 -encoding UTF-8 -d "$testRoot/classes" (Join-Path $RepoRoot 'scripts/SmokeRollback.java') (Join-Path $PSScriptRoot 'ReadOnlyJdbcFixture.java') "$testRoot/CleanupDirectProbe.java"
    if ($LASTEXITCODE -ne 0) { throw 'JDK 11 compilation failed' }

    Case 'rollback denied before JDBC connection and snapshot access' {
        $reply = Invoke-Probe 'rollback' "$testRoot/does-not-exist.json"
        Assert-True ($reply.Code -eq 2 -and $reply.Text -match 'CLEANUP_DISABLED') 'Missing explicit rollback rejection'
        Assert-True (-not (Test-Path -LiteralPath $env:SMOKE_JDBC_PROBE)) 'rollback connected to database'
    }
    Case 'capture remains read-only and records legacy format' {
        $reply = Invoke-Probe 'capture'
        Assert-True ($reply.Code -eq 0) $reply.Text
        $snapshot = [IO.File]::ReadAllText("$testRoot/snapshot.json", [Text.Encoding]::UTF8) | ConvertFrom-Json
        Assert-True ($snapshot.format -eq 'smoke-watermark/1') 'Snapshot compatibility broken'
        Assert-True (@($snapshot.tables.PSObject.Properties).Count -eq 12) 'Primary snapshots missing'
        Assert-True ((Get-Content -LiteralPath $env:SMOKE_JDBC_PROBE -Raw) -match 'READ_ONLY') 'Connection not read-only'
    }
    Case 'verify remains read-only for matching snapshot' {
        $reply = Invoke-Probe 'verify'
        Assert-True ($reply.Code -eq 0) $reply.Text
    }
    Case 'verify reports changed data with nonzero exit' {
        $env:SMOKE_FIXTURE_DRIFT = '1'
        try { $reply = Invoke-Probe 'verify'; Assert-True ($reply.Code -eq 1 -and $reply.Text -match 'FAIL') 'Drift silently accepted' }
        finally { $env:SMOKE_FIXTURE_DRIFT = '0' }
    }
    Case 'direct rollback method cannot bypass CLI guard' {
        $ErrorActionPreference = 'Continue'
        $reply = & $java -cp "$testRoot/classes" CleanupDirectProbe "$testRoot/snapshot.json" 2>&1
        Assert-True ($LASTEXITCODE -eq 0 -and ($reply | Out-String) -match 'BLOCKED') 'Direct rollback not disabled'
    }
    Case 'Java source contains no database mutation helpers' {
        $source = [IO.File]::ReadAllText((Join-Path $RepoRoot 'scripts/SmokeRollback.java'))
        Assert-True ($source -notmatch 'executeUpdate\(|setAutoCommit\(|\.commit\(|\.rollback\(|deleteAbove\(|static Object\[\] restore\(') 'Writable JDBC path remains'
    }
    Case 'current operating docs no longer recommend old cleanup' {
        $readme = [IO.File]::ReadAllText((Join-Path $RepoRoot 'README.md'))
        $bench = [IO.File]::ReadAllText((Join-Path $RepoRoot 'benchmark/README.md'))
        Assert-True ($readme -notmatch '(?m)^docker exec.*cleanup-e2e-data.sql') 'README still runs old SQL'
        Assert-True ($bench -notmatch '(?m)^java .*SmokeRollback.java rollback') 'Benchmark still runs rollback'
        Assert-True ($readme -match 'test-data-safety.md' -and $bench -match 'test-data-safety.md') 'New policy missing'
    }
    Case 'summary docs describe cleanup retirement consistently' {
        $bench = [IO.File]::ReadAllText((Join-Path $RepoRoot 'benchmark/README.md'))
        $merged = [IO.File]::ReadAllText((Join-Path $RepoRoot 'sql/merged/README.md'))
        Assert-True (-not $bench.Contains('压完 `rollback` 复原并复核')) 'Benchmark safety section recommends rollback'
        Assert-True (-not $merged.Contains('孤儿单退一次款后即无孤儿')) 'Migration table still promises legacy refund idempotence'
    }
}
finally {
    $env:SMOKE_JDBC_PROBE = $oldProbe
    $env:SMOKE_FIXTURE_DRIFT = $oldDrift
    # Keep isolated fixtures for inspection; no recursive filesystem deletion.
    Write-Host "Fixtures: $testRoot"
}
$failed = @($results | Where-Object { -not $_.passed }).Count
Write-Host "Results: $($results.Count) cases, $failed failures"
if ($failed -gt 0) { exit 1 }
