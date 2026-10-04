<#
.SYNOPSIS
    Boots the installed Eclipse headless, waits for the MCP endpoint, and calls it.

.DESCRIPTION
    check-env.ps1 proves the jars, manifests and version ranges agree -- a claim about files.
    Nothing else in the repository proves the bundles resolve and activate in a real OSGi
    framework, and that is the failure with no other signal: a bundle whose dependency does not
    resolve never starts, and javac, the self-check and check-env all still pass.

    The host is com.github.lunar.workspace.verification, the packaged headless IApplication,
    because it boots a workspace and then blocks until its stop file appears. That block is the
    point: the only other thing that boots Eclipse headlessly here is a p2 application, which
    returns and takes the JVM with it before anything can call the endpoint.

    LunarConfig.fromFile prefers the environment over the config file for the token and the
    port, so exporting them here is deterministic even on a machine that already has a
    %USERPROFILE%\.lunar\config.json. The token is made per run and is never written out.
#>
[CmdletBinding()]
param(
    # The installation to boot. lunar must already be in its dropins.
    [Parameter(Mandatory = $true)][string]$EclipseHome,
    # Seconds to wait for the endpoint before failing.
    [int]$TimeoutSeconds = 300,
    # Leave the scratch workspace and logs in place for inspection.
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'

$launcher = Join-Path $EclipseHome 'eclipsec.exe'
if (-not (Test-Path -LiteralPath $launcher)) { throw "eclipsec.exe not found under $EclipseHome" }
$installed = @(Get-ChildItem -LiteralPath (Join-Path $EclipseHome 'dropins') -Filter 'com.github.lunar.*.jar' `
        -ErrorAction SilentlyContinue)
if ($installed.Count -ne 5) {
    throw "expected the five lunar jars in $EclipseHome\dropins, found $($installed.Count). Run build.cmd -Install first."
}

# UTF-8 without a BOM: javac rejects a BOM in a compilation unit as an illegal character, and
# Set-Content -Encoding UTF8 emits one on Windows PowerShell.
function Write-Source([string]$Path, [string[]]$Lines) {
    $text = ($Lines -join "`r`n") + "`r`n"
    [System.IO.File]::WriteAllText($Path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

$root = $PSScriptRoot
$work = Join-Path $root 'smoke\endpoint'
# The application refuses a workspace that still holds its fixture and refuses a stop or ready
# file that already exists, so every run starts from an empty directory.
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $work | Out-Null

# Written here rather than committed: smoke/ is gitignored, and VerificationApplication only
# copies fixtures out of <root>/smoke/seeds. The application advertises breakpoint line 10, so
# the probe puts the call it wants to stop on there.
$seeds = Join-Path $root 'smoke\seeds'
New-Item -ItemType Directory -Force -Path $seeds | Out-Null
Write-Source (Join-Path $seeds 'ProbeMain.java') @(
    'package lunar.verify;'
    ''
    'public class ProbeMain {'
    ''
    '    static int add(int a, int b) {'
    '        return a + b;'
    '    }'
    ''
    '    public static void main(String[] args) {'
    '        System.out.println(add(20, 22));'
    '    }'
    '}'
)
Write-Source (Join-Path $seeds 'ProbeTest.java') @(
    'package lunar.verify;'
    ''
    'import static org.junit.jupiter.api.Assertions.assertEquals;'
    'import org.junit.jupiter.api.Test;'
    ''
    'public class ProbeTest {'
    '    @Test public void adds() { assertEquals(42, ProbeMain.add(20, 22)); }'
    '}'
)

# Ask the OS for a free port rather than hoping 8124 is idle: this script may run beside a
# developer's own Eclipse, and a bind failure here would be indistinguishable from a real one.
$probe = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
$probe.Start()
$port = ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
$probe.Stop()
$token = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:ECLIPSE_MCP_TOKEN = $token
$env:LUNAR_MCP_PORT = "$port"

$workspace = Join-Path $work 'ws'
$stopFile = Join-Path $work 'stop'
$readyFile = Join-Path $work 'ready.json'
$endpointFile = Join-Path $workspace '.metadata\.plugins\com.github.lunar\server\endpoint.json'
$stdout = Join-Path $work 'eclipse-console.log'
$stderr = Join-Path $work 'eclipse-error.log'

# Quoted explicitly because Start-Process joins ArgumentList with spaces and does not quote it,
# and a workspace path is allowed to contain spaces.
$arguments = @('-nosplash', '-consoleLog', '-data', ('"' + $workspace + '"'),
    '-application', 'com.github.lunar.workspace.verification',
    '-lunarRoot', ('"' + $root + '"'),
    '-lunarStop', ('"' + $stopFile + '"'),
    '-lunarReady', ('"' + $readyFile + '"'))

$process = $null
try {
    $process = Start-Process -FilePath $launcher -ArgumentList ($arguments -join ' ') -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        if ((Test-Path -LiteralPath $endpointFile) -and (Test-Path -LiteralPath $readyFile)) { break }
        if ($process.HasExited) { throw "Eclipse exited with $($process.ExitCode) before the endpoint answered." }
        if ((Get-Date) -gt $deadline) { throw "No endpoint within $TimeoutSeconds s." }
        Start-Sleep -Seconds 2
    }

    $target = (Get-Content -LiteralPath $endpointFile -Raw | ConvertFrom-Json).url
    if (-not $target) { throw 'endpoint.json named no url' }
    Write-Host "endpoint: $target"

    function Invoke-Mcp([string]$Url, [hashtable]$Headers, [string]$Body) {
        try {
            $response = Invoke-WebRequest -Uri $Url -Method POST -Headers $Headers `
                -ContentType 'application/json' -Body $Body -UseBasicParsing
            return @{ Status = [int]$response.StatusCode; Body = [string]$response.Content; Headers = $response.Headers }
        } catch {
            # Invoke-WebRequest throws on 4xx, and the 401 assertion needs that status code.
            if ($null -eq $_.Exception.Response) { throw }
            return @{ Status = [int]$_.Exception.Response.StatusCode; Body = ''; Headers = $_.Exception.Response.Headers }
        }
    }

    $accept = 'application/json, text/event-stream'
    # initialize first: tools/list is accepted without it, so a bare tools/list would prove the
    # transport and nothing else.
    $initialize = Invoke-Mcp $target @{ 'Accept' = $accept; 'Authorization' = "Bearer $token" } `
        '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"lunar-smoke","version":"1"}}}'
    if ($initialize.Status -ne 200) { throw "initialize returned HTTP $($initialize.Status)" }
    $session = $initialize.Headers['Mcp-Session-Id']
    if (-not $session) { throw 'initialize returned no session id' }

    $announced = Invoke-Mcp $target @{ 'Accept' = $accept; 'Authorization' = "Bearer $token"; 'Mcp-Session-Id' = $session } `
        '{"jsonrpc":"2.0","method":"notifications/initialized"}'
    if ($announced.Status -ge 300) { throw "notifications/initialized returned HTTP $($announced.Status)" }

    $listed = Invoke-Mcp $target @{ 'Accept' = $accept; 'Authorization' = "Bearer $token"; 'Mcp-Session-Id' = $session } `
        '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
    if ($listed.Status -ne 200) { throw "tools/list returned HTTP $($listed.Status)" }
    $parsed = $listed.Body | ConvertFrom-Json
    $tools = @($parsed.result.tools)
    # 17 is published in the README (diagram, tools table, curl example) and IntegrationCheck pins
    # it too, so it has four homes and this is the one that got left behind last time. Worth
    # asserting here anyway: IntegrationCheck injects a registry, and only this boots the real
    # plugins, so it is the only check that sees what OSGi actually offers.
    if ($tools.Count -ne 17) { throw "expected 17 tools visible at start, got $($tools.Count)" }

    $refused = Invoke-Mcp $target @{ 'Accept' = $accept } '{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}'
    if ($refused.Status -ne 401) { throw "an unauthenticated tools/list returned HTTP $($refused.Status), expected 401" }

    Write-Host "SMOKE PASS: 17 tools live at $target, unauthenticated call refused with 401"
}
catch {
    Write-Host "SMOKE FAIL: $_"
    foreach ($file in @($stdout, $stderr, (Join-Path $workspace '.metadata\.log'))) {
        if (Test-Path -LiteralPath $file) {
            Write-Host "--- $file ---"
            Get-Content -LiteralPath $file -Tail 40 | ForEach-Object { "    $_" }
        }
    }
    throw
}
finally {
    # The application returns only once its stop file exists and deletes its fixture on the way
    # out, so this is the orderly shutdown rather than a kill.
    New-Item -ItemType File -Force -Path $stopFile | Out-Null
    if ($process) {
        if (-not $process.WaitForExit(120000)) {
            Write-Host 'Eclipse did not return after the stop file; terminating.'
            Stop-Process -Id $process.Id -Force
        }
    }
    if ($Keep) { Write-Host "logs kept under $work" }
    else { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}
