param(
    [string]$ScriptPath = (Join-Path $PSScriptRoot '../gen-jks.ps1')
)
$ErrorActionPreference = 'Stop'
$ScriptPath = (Resolve-Path -LiteralPath $ScriptPath).Path
$shell = (Get-Process -Id $PID).Path
$keytool = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME/bin/keytool.exe")) {
    "$env:JAVA_HOME/bin/keytool.exe"
} else { (Get-Command keytool -ErrorAction Stop).Source }
$java = Join-Path (Split-Path $keytool -Parent) 'java.exe'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('llmall jks tests ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$password = 'fixture-only-password'
$oldPassword = $env:LLMALL_JKS_TEST_PASSWORD
$env:LLMALL_JKS_TEST_PASSWORD = $password
$results = @()

$verifier = @'
import java.nio.file.*;
import java.security.*;
public class VerifyJksPair {
  public static void main(String[] args) throws Exception {
    char[] pw = System.getenv("LLMALL_JKS_TEST_PASSWORD").toCharArray();
    KeyStore user = KeyStore.getInstance("JKS"), gateway = KeyStore.getInstance("JKS");
    try (var in = Files.newInputStream(Path.of(args[0]))) { user.load(in, pw); }
    try (var in = Files.newInputStream(Path.of(args[1]))) { gateway.load(in, pw); }
    Signature s = Signature.getInstance("SHA256withRSA");
    byte[] data = "llmall-auth-regression".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    s.initSign((PrivateKey) user.getKey("llmall", pw)); s.update(data);
    byte[] signed = s.sign();
    s.initVerify(gateway.getCertificate("llmall").getPublicKey()); s.update(data);
    if (!s.verify(signed)) throw new AssertionError("user signature rejected by gateway");
  }
}
'@
[IO.File]::WriteAllText((Join-Path $testRoot 'VerifyJksPair.java'), $verifier)

function New-Fixture([string]$Name) {
    $root = Join-Path $testRoot $Name
    New-Item -ItemType Directory -Force -Path "$root/scripts", "$root/llmall-gateway/src/main/resources", "$root/llmall-user-service/src/main/resources" | Out-Null
    Copy-Item -LiteralPath $ScriptPath -Destination "$root/scripts/gen-jks.ps1"
    return @{
        Root = $root
        Gateway = "$root/llmall-gateway/src/main/resources/llmall.jks"
        User = "$root/llmall-user-service/src/main/resources/llmall.jks"
    }
}
function New-Key([string]$Path) {
    $ErrorActionPreference = 'Continue'
    & $keytool -genkeypair -alias llmall -keyalg RSA -keysize 2048 -validity 3650 -storetype JKS `
        -keystore $Path -storepass:env LLMALL_JKS_TEST_PASSWORD -keypass:env LLMALL_JKS_TEST_PASSWORD `
        -dname 'CN=isolated-test' *> "$testRoot/keytool.log"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture key generation failed' }
}
function Invoke-Generator($Fixture, [string]$Pass = $password, [switch]$Force) {
    $ErrorActionPreference = 'Continue'
    $arguments = @('-NoProfile', '-File', "$($Fixture.Root)/scripts/gen-jks.ps1", '-Password', $Pass)
    if ($Force) { $arguments += '-Force' }
    & $shell @arguments *> "$($Fixture.Root)/generator.log"
    return $LASTEXITCODE
}
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Get-Hash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Assert-Pair($Fixture) {
    & $java "$testRoot/VerifyJksPair.java" $Fixture.User $Fixture.Gateway *> "$($Fixture.Root)/verify.log"
    Assert-True ($LASTEXITCODE -eq 0) 'User signature must verify with gateway certificate'
}
function Test-Case([string]$Name, [scriptblock]$Body) {
    try { & $Body; $script:results += @{name=$Name;passed=$true}; Write-Host "PASS $Name" }
    catch { $script:results += @{name=$Name;passed=$false;reason=$_.Exception.Message}; Write-Host "FAIL $Name : $($_.Exception.Message)" }
}

try {
    Test-Case 'fresh generation signs across services' {
        $f = New-Fixture fresh
        Assert-True ((Invoke-Generator $f) -eq 0) 'Fresh generation failed'
        Assert-Pair $f
        Assert-True ((Get-Hash $f.User) -eq (Get-Hash $f.Gateway)) 'Fresh stores must be the same generated store'
    }
    Test-Case 'repeat preserves existing keys' {
        $f = New-Fixture repeat
        New-Key $f.User; Copy-Item -LiteralPath $f.User -Destination $f.Gateway
        $before = Get-Hash $f.User
        Assert-True ((Invoke-Generator $f) -eq 0) 'Repeat failed'
        Assert-True ((Get-Hash $f.User) -eq $before -and (Get-Hash $f.Gateway) -eq $before) 'Repeat changed existing keys'
    }
    Test-Case 'restore user store from gateway' {
        $f = New-Fixture missingUser
        New-Key $f.Gateway
        $before = Get-Hash $f.Gateway
        Assert-True ((Invoke-Generator $f) -eq 0) 'Repair failed'
        Assert-True ((Get-Hash $f.User) -eq $before) 'Missing store must reuse existing key'
        Assert-Pair $f
    }
    Test-Case 'restore gateway store from user' {
        $f = New-Fixture missingGateway
        New-Key $f.User
        $before = Get-Hash $f.User
        Assert-True ((Invoke-Generator $f) -eq 0) 'Repair failed'
        Assert-True ((Get-Hash $f.Gateway) -eq $before) 'Missing store must reuse existing key'
        Assert-Pair $f
    }
    Test-Case 'mismatch fails without replacing either key' {
        $f = New-Fixture mismatch
        New-Key $f.User; New-Key $f.Gateway
        $userBefore = Get-Hash $f.User; $gatewayBefore = Get-Hash $f.Gateway
        Assert-True ((Invoke-Generator $f) -ne 0) 'Mismatched existing keys must be rejected'
        Assert-True ((Get-Hash $f.User) -eq $userBefore -and (Get-Hash $f.Gateway) -eq $gatewayBefore) 'Mismatch check modified stores'
    }
    Test-Case 'wrong password fails without changing keys' {
        $f = New-Fixture wrongPassword
        New-Key $f.User; Copy-Item -LiteralPath $f.User -Destination $f.Gateway
        $before = Get-Hash $f.User
        Assert-True ((Invoke-Generator $f -Pass 'wrong-test-password') -ne 0) 'Wrong password must be rejected'
        Assert-True ((Get-Hash $f.User) -eq $before -and (Get-Hash $f.Gateway) -eq $before) 'Wrong password changed stores'
    }
    Test-Case 'force rotates both to one new pair' {
        $f = New-Fixture force
        New-Key $f.User; Copy-Item -LiteralPath $f.User -Destination $f.Gateway
        $before = Get-Hash $f.User
        Assert-True ((Invoke-Generator $f -Force) -eq 0) 'Force rotation failed'
        Assert-True ((Get-Hash $f.User) -ne $before) 'Force must rotate key'
        Assert-True ((Get-Hash $f.User) -eq (Get-Hash $f.Gateway)) 'Rotated stores differ'
        Assert-Pair $f
    }
    Test-Case 'invalid generation leaves existing keys intact' {
        $f = New-Fixture generationFailure
        New-Key $f.User; Copy-Item -LiteralPath $f.User -Destination $f.Gateway
        $before = Get-Hash $f.User
        Assert-True ((Invoke-Generator $f -Pass 'short' -Force) -ne 0) 'Invalid password must fail'
        Assert-True ((Get-Hash $f.User) -eq $before -and (Get-Hash $f.Gateway) -eq $before) 'Failed generation changed stores'
    }
    Test-Case 'corrupt sole store is not propagated' {
        $f = New-Fixture corrupt
        [IO.File]::WriteAllText($f.User, 'not a keystore')
        Assert-True ((Invoke-Generator $f) -ne 0) 'Corrupt store must be rejected'
        Assert-True (-not (Test-Path -LiteralPath $f.Gateway)) 'Must not generate or copy after validation failure'
    }
    $failed = @($results | Where-Object { -not $_.passed }).Count
    Write-Host "RESULT total=$($results.Count) failed=$failed"
    if ($failed -gt 0) { exit 1 }
}
finally {
    [Environment]::SetEnvironmentVariable('LLMALL_JKS_TEST_PASSWORD', $oldPassword, 'Process')
    # The root is always a fresh, test-owned directory. Never touch project keystores.
    $safeRoot = [IO.Path]::GetFullPath($testRoot)
    if ($safeRoot.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $safeRoot -Leaf).StartsWith('llmall jks tests ')) {
        Remove-Item -LiteralPath $safeRoot -Recurse -Force
    }
}
