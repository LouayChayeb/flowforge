# Runs one load-test measurement against FlowForge on Kubernetes (e.g. Minikube):
# port-forwards the orchestrator, warms the JVMs up, then measures.
#
#   ./loadtest/run-k8s.ps1 -Rate 20 -Duration 60
#
# Requires kubectl pointing at the cluster and a JDK 17+ on PATH.
param(
    [double]$Rate = 10,
    [int]$Duration = 60,
    [double]$Failures = 0.3,
    [int]$WarmupSeconds = 20,
    [string]$Namespace = "flowforge",
    [string]$Service = "svc/flowforge-orchestrator",
    [int]$LocalPort = 8085
)
$ErrorActionPreference = "Stop"
$base = "http://localhost:$LocalPort"
$test = Join-Path $PSScriptRoot "LoadTest.java"
$jvm = @()
if ($IsWindows -or $env:OS -eq "Windows_NT") {
    # The JDK's HTTP client opens a Unix-domain socket in TEMP, which fails for some Windows user paths.
    $jvm += "-Djdk.net.unixdomain.tmpdir=$env:SystemRoot\Temp"
}

$forward = Start-Process kubectl -ArgumentList "port-forward", "-n", $Namespace, $Service, "${LocalPort}:8080" `
    -PassThru -WindowStyle Hidden
try {
    $deadline = (Get-Date).AddSeconds(30)
    while ($true) {
        try { Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2 | Out-Null; break }
        catch { if ((Get-Date) -gt $deadline) { throw "Orchestrator not reachable on $base" }; Start-Sleep -Milliseconds 500 }
    }
    # Make sure the workflow exists (registering it again just adds a version).
    try { Invoke-RestMethod "$base/api/workflows/order-processing" | Out-Null }
    catch {
        Invoke-RestMethod -Method Post "$base/api/workflows" -ContentType "application/json" `
            -InFile (Join-Path $PSScriptRoot "../docs/examples/order-processing.json") | Out-Null
    }
    if ($WarmupSeconds -gt 0) {
        Write-Host "Warming up for ${WarmupSeconds}s..."
        java @jvm $test --base $base --rate 10 --duration $WarmupSeconds --failures $Failures | Out-Null
    }
    java @jvm $test --base $base --rate $Rate --duration $Duration --failures $Failures --timeout 300
}
finally {
    Stop-Process -Id $forward.Id -Force -ErrorAction SilentlyContinue
}
