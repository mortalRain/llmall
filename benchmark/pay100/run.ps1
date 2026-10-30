param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$RepoRoot=(Resolve-Path "$PSScriptRoot/../..").Path,
    [string]$JavaHome='D:/develop/jdk17',
    [string]$MySqlContainer='mysql'
)
$ErrorActionPreference='Stop'
$out=[IO.Path]::GetFullPath($OutputDirectory)
if((Test-Path -LiteralPath $out) -or (Test-Path -LiteralPath "$out.build")){throw 'Use a fresh output path'}
if((& docker port $MySqlContainer 3306) -ne '127.0.0.1:3307'){throw 'Expected private MySQL fixture mapping 127.0.0.1:3307'}
$reports=@(
 'llmall-trade-service/target/surefire-reports/TEST-com.llmall.trade.service.impl.OrderCreationTransactionTest.xml',
 'llmall-user-service/target/surefire-reports/TEST-com.llmall.user.service.impl.BalancePaymentTransactionTest.xml',
 'llmall-pay-service/target/surefire-reports/TEST-com.llmall.pay.service.impl.PaymentAttemptTransactionTest.xml'
)
$cp=@("$RepoRoot/llmall-pay-service/target/classes","$RepoRoot/llmall-user-service/target/classes")
foreach($rel in $reports){
 [xml]$x=Get-Content -LiteralPath (Join-Path $RepoRoot $rel) -Raw
 if([int]$x.testsuite.failures -ne 0 -or [int]$x.testsuite.errors -ne 0){throw 'Run current default regression first'}
 $cp+=(($x.testsuite.properties.property|Where-Object {$_.name -eq 'java.class.path'}).value -split ';')
}
# Use reports produced by a fresh build in this same checkout, not copied from another checkout.
foreach($module in @('llmall-common','llmall-api','llmall-user-service','llmall-pay-service','llmall-trade-service')){
 foreach($file in (Get-ChildItem "$RepoRoot/$module/src/main/java" -Recurse -Filter *.java)){
  $relative=[IO.Path]::GetRelativePath("$RepoRoot/$module/src/main/java",$file.FullName)
  $class=Join-Path "$RepoRoot/$module/target/classes" ($relative -replace '\.java$','.class')
  if(-not(Test-Path -LiteralPath $class) -or (Get-Item -LiteralPath $class).LastWriteTimeUtc -lt $file.LastWriteTimeUtc){throw "Fresh compile required: $relative"}
 }
}
$cp=($cp|Select-Object -Unique)-join ';'
New-Item -ItemType Directory -Path "$out.build/classes" -Force|Out-Null
$utf8=[Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText("$out.build/compile.args",'-encoding UTF-8 -cp "'+$cp.Replace('\','/')+'" -d "'+"$out.build/classes"+'" "'+"$PSScriptRoot/Pay100.java"+'"',$utf8)
[IO.File]::WriteAllText("$out.build/run.args",'-Xmx256m -Xss512k -XX:TieredStopAtLevel=1 -cp "'+"$out.build/classes;"+$cp.Replace('\','/')+'" Pay100',$utf8)
& "$JavaHome/bin/javac.exe" "@$out.build/compile.args" *> "$out.build/compile.log"
if($LASTEXITCODE -ne 0){throw 'Compile failed'}
$rows=@(& docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' $MySqlContainer)
if($LASTEXITCODE -ne 0){throw 'Cannot read fixture credential'}
$secret=@($rows|Where-Object {$_.StartsWith('MYSQL_ROOT_PASSWORD=')})
if($secret.Count -ne 1){throw 'Expected one root credential'}
$previous=$env:LLMALL_TEST_DB_PASSWORD
$env:LLMALL_TEST_DB_PASSWORD=$secret[0].Substring('MYSQL_ROOT_PASSWORD='.Length)
try {
 & "$JavaHome/bin/java.exe" "@$out.build/run.args" $out *> "$out.build/process.log"
 $code=$LASTEXITCODE
} finally {
 if($null -eq $previous){Remove-Item Env:LLMALL_TEST_DB_PASSWORD -ErrorAction SilentlyContinue}else{$env:LLMALL_TEST_DB_PASSWORD=$previous}
}
Get-Content "$out.build/process.log" -Tail 8
if($code -ne 0){exit $code}
& node "$PSScriptRoot/analyze.mjs" $out
if($LASTEXITCODE -ne 0){throw 'Raw HTTP result verification failed'}
