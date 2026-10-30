param(
    [string]$RedisServer = $env:LLMALL_TEST_REDIS_SERVER,
    [string]$RepoRoot = (Join-Path $PSScriptRoot '../..')
)
$ErrorActionPreference = 'Stop'
if (-not $RedisServer) { $RedisServer = (Get-Command redis-server -ErrorAction Stop).Source }
$RedisServer = (Resolve-Path -LiteralPath $RedisServer).Path
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
function Server-Path([string]$Path) {
    # MSYS Windows Redis expects /cygdrive paths rather than Windows drive paths.
    if (Test-Path -LiteralPath (Join-Path (Split-Path $RedisServer -Parent) 'msys-2.0.dll')) {
        return '/cygdrive/' + $Path.Substring(0,1).ToLowerInvariant() + '/' + $Path.Substring(3).Replace('\','/')
    }
    return $Path.Replace('\','/')
}
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('llmall-paypwd-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
$listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
$lua = [IO.File]::ReadAllText((Join-Path $RepoRoot 'llmall-user-service/src/main/resources/lua/password-attempt.lua'))
$utf8 = New-Object Text.UTF8Encoding($false)
$config = "bind 127.0.0.1`nport $port`nprotected-mode yes`nsave `"`"`nappendonly no`nmaxmemory 16mb`nmaxmemory-policy noeviction`ndir `"$(Server-Path $fixture)`"`nlogfile `"redis.log`"`n"
[IO.File]::WriteAllText("$fixture/redis.conf", $config, $utf8)
# Minimal RESP client and parallel runner: no external packages and no existing Redis instance.
if (-not ('PayPasswordRedisProbe' -as [type])) {
    Write-Host 'Preparing offline Redis test client...'
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
public static class PayPasswordRedisProbe {
    public static string Call(int port, params string[] args) {
        using (var c = new TcpClient()) {
            c.ReceiveTimeout = 3000; c.SendTimeout = 3000;
            c.Connect("127.0.0.1", port);
            using (var stream = c.GetStream()) {
                var payload = new MemoryStream();
                byte[] header = Encoding.UTF8.GetBytes("*" + args.Length + "\r\n");
                payload.Write(header, 0, header.Length);
                foreach (string arg in args) {
                    byte[] value = Encoding.UTF8.GetBytes(arg);
                    header = Encoding.UTF8.GetBytes("$" + value.Length + "\r\n");
                    payload.Write(header, 0, header.Length); payload.Write(value, 0, value.Length);
                    payload.WriteByte(13); payload.WriteByte(10);
                }
                byte[] data = payload.ToArray(); stream.Write(data, 0, data.Length);
                using (var reader = new StreamReader(stream, Encoding.UTF8)) {
                    string line = reader.ReadLine();
                    if (line == null) throw new IOException("Missing Redis response");
                    if (line[0] == '-') throw new IOException(line);
                    if (line[0] == '$') return line == "$-1" ? null : reader.ReadLine();
                    return line.Substring(1);
                }
            }
        }
    }
    public static string[] ConcurrentFailures(int port, string lua, string fail, string locked) {
        var results = new string[20];
        Parallel.For(0, results.Length, new ParallelOptions { MaxDegreeOfParallelism = 4 },
                i => results[i] = Call(port, "EVAL", lua, "2", fail, locked, "FAIL", "5", "300", "360"));
        return results;
    }
}
'@
    Write-Host 'Offline Redis test client ready.'
}
function Redis([string[]]$RedisArgs) { [PayPasswordRedisProbe]::Call($port, $RedisArgs) }
function Attempt([string]$Action, [string]$Suffix = '1') {
    Redis @('EVAL', $lua, '2', "auth:pwdfail:$Suffix", "auth:pwdlck:$Suffix", $Action, '5', '300', '360')
}
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
$failed = 0; $total = 0; $server = $null
function Case([string]$Name, [scriptblock]$Body) {
    $script:total++
    try { & $Body; Write-Host "PASS $Name" }
    catch { $script:failed++; Write-Host "FAIL $Name : $($_.Exception.Message)" }
}
try {
    Write-Host "Starting isolated Redis on loopback port $port..."
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $RedisServer
    $start.Arguments = '"' + (Server-Path "$fixture\redis.conf") + '"'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $server = [Diagnostics.Process]::Start($start)
    $ready = $false
    for ($i=0; $i -lt 40; $i++) {
        if ($server.HasExited) { throw "Redis fixture exited: $fixture/redis.log" }
        try { if ((Redis @('PING')) -eq 'PONG') { $ready=$true; break } } catch { }
        Start-Sleep -Milliseconds 100
    }
    if (-not $ready) { throw 'Isolated Redis did not start' }
    Case 'first four failures allowed; fifth locks for five minutes' {
        foreach ($i in 1..4) { Assert-True ((Attempt 'FAIL') -eq '0') "Failure $i locked too soon" }
        Assert-True ((Redis @('GET','auth:pwdfail:1')) -eq '4') 'Counter lost'
        Assert-True ([int](Redis @('TTL','auth:pwdfail:1')) -le 360) 'Counter TTL missing'
        Assert-True ((Attempt 'FAIL') -eq '300') 'Fifth failure did not lock'
        Assert-True ((Redis @('EXISTS','auth:pwdfail:1')) -eq '0') 'New lock must reset failures'
    }
    Case 'locked requests neither clear nor extend the lock' {
        Redis @('PEXPIRE','auth:pwdlck:1','2500') | Out-Null
        $before = [int](Redis @('PTTL','auth:pwdlck:1'))
        foreach ($action in @('CHECK','FAIL','SUCCESS')) { Assert-True ([int](Attempt $action) -gt 0) "$action bypassed lock" }
        $after = [int](Redis @('PTTL','auth:pwdlck:1'))
        Assert-True ($after -gt 0 -and $after -le $before) 'Lock extended or cleared'
        Assert-True ((Redis @('EXISTS','auth:pwdfail:1')) -eq '0') 'Locked calls changed counter'
    }
    Case 'after unlock the next fifth failure locks for five minutes again' {
        Redis @('PEXPIRE','auth:pwdlck:1','1') | Out-Null
        Start-Sleep -Milliseconds 30
        foreach ($i in 1..4) { Assert-True ((Attempt 'FAIL') -eq '0') 'Post-unlock counter not reset' }
        Assert-True ((Attempt 'FAIL') -eq '300') 'Repeat lock policy changed'
    }
    Case 'correct password resets unlocked failure budget' {
        Attempt 'FAIL' '2' | Out-Null; Attempt 'FAIL' '2' | Out-Null
        Assert-True ((Attempt 'SUCCESS' '2') -eq '0') 'Success rejected'
        Assert-True ((Redis @('EXISTS','auth:pwdfail:2')) -eq '0') 'Success did not clear count'
        Attempt 'FAIL' '2' | Out-Null
        Assert-True ((Redis @('GET','auth:pwdfail:2')) -eq '1') 'Budget not restarted'
    }
    Case 'twenty failures with four workers cannot bypass fifth failure' {
        $replies = [PayPasswordRedisProbe]::ConcurrentFailures($port, $lua, 'auth:pwdfail:3','auth:pwdlck:3')
        Assert-True (@($replies | Where-Object { $_ -eq '0' }).Count -eq 4) 'Unexpected unlocked result count'
        Assert-True (@($replies | Where-Object { [int]$_ -gt 0 }).Count -eq 16) 'Concurrent requests escaped lock'
    }
    Case 'login password change cannot reset payment failures or lock' {
        Attempt 'FAIL' '4' | Out-Null
        Attempt 'SUCCESS' 'login-change:4' | Out-Null
        Assert-True ((Redis @('GET','auth:pwdfail:4')) -eq '1') 'Payment count cleared by login scope'
        Attempt 'SUCCESS' 'login-change:3' | Out-Null
        Assert-True ([int](Attempt 'CHECK' '3') -gt 0) 'Payment lock cleared by login scope'
    }
    Case 'permanent legacy lock is respected without removal' {
        Redis @('SET','auth:pwdlck:5','1') | Out-Null
        Assert-True ((Attempt 'SUCCESS' '5') -eq '300') 'Legacy lock bypassed'
        Assert-True ((Redis @('TTL','auth:pwdlck:5')) -eq '-1') 'Legacy lock modified'
    }
    Case 'expired failure budget restarts at one' {
        Attempt 'FAIL' '6' | Out-Null
        Redis @('PEXPIRE','auth:pwdfail:6','1') | Out-Null
        Start-Sleep -Milliseconds 30
        Attempt 'FAIL' '6' | Out-Null
        Assert-True ((Redis @('GET','auth:pwdfail:6')) -eq '1') 'Expired failures retained'
    }
} finally {
    # Stop only the process this test created, never connect to or stop an existing instance.
    if ($server -and -not $server.HasExited) {
        $server.Refresh()
        Write-Host ('Test Redis working set: ' + [math]::Round($server.WorkingSet64/1MB,1) + ' MiB; data limit: 16 MiB')
        Stop-Process -Id $server.Id -Force; $server.WaitForExit()
    }
    Write-Host "Isolated Redis fixture retained: $fixture"
}
Write-Host "Results: $total cases, $failed failures"
if ($failed -gt 0) { exit 1 }
