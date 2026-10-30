# Generate one local JWT keystore shared by the user service and gateway.
# Existing mismatched stores require an explicit -Force rotation (old JWTs expire).
# Production keys should be mounted externally; never commit generated .jks files.
param(
    [string]$Password = 'llmall123',
    [ValidateNotNullOrEmpty()]
    [string]$Alias = 'llmall',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$targets = @(
    (Join-Path $root 'llmall-gateway/src/main/resources/llmall.jks'),
    (Join-Path $root 'llmall-user-service/src/main/resources/llmall.jks')
)

$keytool = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
    if (Test-Path -LiteralPath $candidate) { $keytool = $candidate }
}
if (-not $keytool) {
    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if (-not $command) { throw 'Install a JDK and configure JAVA_HOME or keytool on PATH.' }
    $keytool = $command.Source
}

function Invoke-Keytool([string[]]$Arguments) {
    # Windows PowerShell 5.1 turns native stderr warnings into ErrorRecords.
    $ErrorActionPreference = 'Continue'
    $output = & $keytool '-J-Duser.language=en' '-J-Duser.country=US' @Arguments `
        '-storepass:env' 'LLMALL_KEYTOOL_PASSWORD' 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Keytool failed. Check the password, alias, JDK and keystore file.' }
    return ($output | Out-String)
}

function Get-KeyFingerprint([string]$Path) {
    $details = Invoke-Keytool -Arguments @('-list', '-v', '-storetype', 'JKS', '-keystore', $Path, '-alias', $Alias)
    if ($details -notmatch 'Entry type:\s*PrivateKeyEntry') {
        throw "The alias must contain a private key: $Path"
    }
    $match = [regex]::Match($details, 'SHA256:\s*([0-9A-Fa-f:]+)')
    if (-not $match.Success) { throw "Cannot read certificate fingerprint: $Path" }
    return $match.Groups[1].Value.ToUpperInvariant()
}

$previousPassword = [Environment]::GetEnvironmentVariable('LLMALL_KEYTOOL_PASSWORD', 'Process')
$temporaryStore = $null
try {
    if ([string]::IsNullOrEmpty($Password) -or $Password.Length -lt 6) {
        throw 'Keystore password must contain at least 6 characters.'
    }
    [Environment]::SetEnvironmentVariable('LLMALL_KEYTOOL_PASSWORD', $Password, 'Process')
    $existing = @($targets | Where-Object { Test-Path -LiteralPath $_ })

    if (-not $Force -and $existing.Count -gt 0) {
        $fingerprint = Get-KeyFingerprint $existing[0]
        foreach ($path in $existing) {
            if ((Get-KeyFingerprint $path) -ne $fingerprint) {
                throw 'Gateway and user keystores differ. Reconcile existing keys or explicitly rotate both with -Force.'
            }
        }
        $source = $existing[0]
    } else {
        $temporaryStore = Join-Path ([IO.Path]::GetTempPath()) ('llmall-key-' + [guid]::NewGuid().ToString('N') + '.jks')
        Invoke-Keytool -Arguments @('-genkeypair', '-alias', $Alias, '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '3650', '-keystore', $temporaryStore, '-keypass:env', 'LLMALL_KEYTOOL_PASSWORD',
            '-storetype', 'JKS', '-dname', 'CN=llmall-demo, OU=dev, O=llmall, C=CN') | Out-Null
        $fingerprint = Get-KeyFingerprint $temporaryStore
        $source = $temporaryStore
    }

    # Validate/generate before touching either target; roll back ordinary write errors.
    $bytes = [IO.File]::ReadAllBytes($source)
    $changed = @()
    $originals = @{}
    try {
        foreach ($path in $targets) {
            if (-not $Force -and (Test-Path -LiteralPath $path)) { continue }
            $originals[$path] = if (Test-Path -LiteralPath $path) { [IO.File]::ReadAllBytes($path) } else { $null }
            New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
            $changed += $path
            [IO.File]::WriteAllBytes($path, $bytes)
        }
    } catch {
        foreach ($path in $changed) {
            if ($null -ne $originals[$path]) { [IO.File]::WriteAllBytes($path, $originals[$path]) }
            elseif (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
        }
        throw
    }
    Write-Host "JWT keystores ready. Certificate SHA256: $fingerprint"
    Write-Host 'Use the same LLMALL_JKS_PASSWORD for both services. A forced rotation invalidates old JWTs.'
} finally {
    [Environment]::SetEnvironmentVariable('LLMALL_KEYTOOL_PASSWORD', $previousPassword, 'Process')
    if ($temporaryStore -and (Test-Path -LiteralPath $temporaryStore)) {
        Remove-Item -LiteralPath $temporaryStore -Force
    }
}
