# lunar.ps1 - one script for setting up, connecting, checking and removing lunar.
#
# Dot-sources lunar-env.ps1 and lunar-p2.ps1, so everything it automates is also available
# to be called directly, and every step it takes is written out by hand in the README under
# "Doing it by hand". Nothing here is the only way to do any of it.
#
#   .\lunar.ps1 setup      create the config file, cut a token, publish it to the environment
#   .\lunar.ps1 connect    register lunar with an MCP client
#   .\lunar.ps1 status     what is configured, and whether the endpoint is answering
#   .\lunar.ps1 uninstall  take it back off
#
# Windows PowerShell 5.1 compatible throughout: no ternary operator, no Join-Path with more
# than two arguments, no Join-String.

[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$Command = 'help',
    [string]$Client = 'opencode',
    # Where `connect` writes. Defaults to the current directory, which is where a client
    # reads project configuration from.
    [string]$ProjectPath = '.',
    # Offer the client's global scope. OpenCode's project scope is the default because a
    # shared repository should not decide what its contributors' agents can reach.
    [switch]$Global,
    # Lets `uninstall` delete the config file and the environment variable.
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lunar-env.ps1')
. (Join-Path $PSScriptRoot 'lunar-p2.ps1')

$ConfigDirName = '.lunar'
$ConfigFileName = 'config.json'

# ---------------------------------------------------------------------------
# The config file: the one thing a user has to know about.
# ---------------------------------------------------------------------------

function Get-LunarConfigPath {
    Join-Path (Join-Path $env:USERPROFILE $ConfigDirName) $ConfigFileName
}

function Read-LunarConfig {
    $path = Get-LunarConfigPath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try {
        $parsed = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    } catch {
        throw @"
$path is not valid JSON, so lunar cannot read its settings from it.

    $($_.Exception.Message)

Fix or delete the file. To start over, delete it and run: .\lunar.ps1 setup
"@
    }
    # Valid JSON is not the same as a usable config. ConvertFrom-Json happily returns an
    # array or a bare string, and every property lookup on one of those silently yields
    # nothing -- so setup would report success, hand the array to Add-Member element by
    # element, write a file the server cannot read, and the server would then refuse to
    # bind. Refusing here is the only place that can catch it.
    if ($parsed -isnot [System.Management.Automation.PSCustomObject]) {
        # ConvertFrom-Json turns the file's own `null` into $null, so the type name has to
        # be asked for conditionally -- asking a null for its type raises the very error
        # this message exists to replace.
        $kind = if ($null -eq $parsed) { 'null' } else { $parsed.GetType().Name }
        throw @"
$path is valid JSON but not an object, so lunar cannot read any settings from it.

It holds a $kind. It has to be an object with "token", "host" and "port" keys, for example:

    {
      "token": "<a long random string>",
      "host": "127.0.0.1",
      "port": 8124
    }

Fix or delete the file. To start over, delete it and run: .\lunar.ps1 setup
"@
    }
    $parsed
}

function Write-LunarConfig {
    param([Parameter(Mandatory = $true)]$Config)
    $path = Get-LunarConfigPath
    $dir = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $json = $Config | ConvertTo-Json
    # Write to a sibling temp file and move it into place, the way EndpointFile does. A failure
    # mid-write used to leave truncated JSON where the token lives, and Read-LunarConfig then
    # throws on every later command until the file is replaced.
    # UTF-8 without a BOM. Set-Content -Encoding UTF8 writes one under Windows PowerShell
    # 5.1, and both the Java side and ConvertFrom-Json would then have to cope with it.
    $temp = Join-Path $dir (".config.json.{0}.tmp" -f [Guid]::NewGuid().ToString('N'))
    try {
        [System.IO.File]::WriteAllText($temp, $json, (New-Object System.Text.UTF8Encoding($false)))
        Move-Item -LiteralPath $temp -Destination $path -Force
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
    }
    $path
}

# 32 random bytes, hex. Not a password-derivation function and not meant to be reversible --
# it exists because a hand-written token is a token somebody pasted into a chat log.
function New-LunarToken {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

# The MCP clients expand environment variables, not files, so the token has to be in the
# environment as well as on disk. User scope rather than machine: setting a machine-scope
# variable needs an elevation this script has no business demanding.
function Set-LunarUserEnvironment {
    # $Value is deliberately not [string], so a $null meant as "remove this variable"
    # arrives as $null rather than as an empty string, which is what the cast would
    # produce and what the [Environment] documentation says deletes a variable. Both
    # spellings happen to work on Windows PowerShell today; this one is the documented
    # one, and it does not depend on which of them the runtime is feeling charitable.
    param([string]$Name, $Value)
    [Environment]::SetEnvironmentVariable($Name, $Value, 'User')
}

function Get-LunarUserEnvironment {
    param([string]$Name)
    # Not $env:$Name. A process started before the variable was set inherits the old
    # environment, so the process view can disagree with the user view, and only the user
    # view is what the next Eclipse launch will get.
    [Environment]::GetEnvironmentVariable($Name, 'User')
}

# The endpoint URL, resolved exactly the way the server resolves it: environment first,
# then the config file, then the default. Every subcommand that prints or writes a URL goes
# through here, because a client pointed at a port the server is not listening on fails with
# a connection error that looks nothing like a settings mistake.
function Get-LunarEndpointUrl {
    param([Parameter(Mandatory = $true)]$Config)
    $host_ = [string](Get-LunarSetting -Config $Config -Name 'host' -Default '127.0.0.1')
    $port_ = Get-LunarSetting -Config $Config -Name 'port' -Default 8124
    # A literal IPv6 address has to be bracketed in a URL, or the colon that separates the
    # port from the host is indistinguishable from part of the address.
    if ($host_.Contains(':') -and -not $host_.StartsWith('[')) { $host_ = '[' + $host_ + ']' }
    'http://' + $host_ + ':' + $port_ + '/mcp'
}

# One setting, read one way, by everything here. Missing, blank and the wrong type are all
# the same thing: use the default. The last of those is not hypothetical -- a config file
# with "port": 1234 is a number, and letting it through turned a fallback into a crash.
#
# The environment is consulted first, and it wins, because that is what LunarConfig.java
# does. If these two ever disagreed, `connect` would write a URL into a client config that
# nothing is listening on, and `status` would report the wrong port as if it were true.
# The three are read in this one place within this script, so status cannot quote a URL
# it did not build from the same answer connect wrote.
function Get-LunarSetting {
    param($Config, [string]$Name, $Default)
    if ($Name -eq 'token') { $variable = 'ECLIPSE_MCP_TOKEN' }
    elseif ($Name -eq 'host') { $variable = 'LUNAR_MCP_HOST' }
    elseif ($Name -eq 'port') { $variable = 'LUNAR_MCP_PORT' }
    else { throw "Get-LunarSetting does not know about '$Name'. Add it here and in LunarConfig.java together." }

    # Through Get-LunarUserEnvironment, so user scope and not $env:NAME -- the process
    # environment is whatever the terminal that launched this happened to have, which is
    # stale exactly when it matters -- and so there is one place reading these three rather
    # than two that can drift. It also gives check-env.ps1 a function to replace instead of
    # a registry call it cannot stub.
    $value = Get-LunarUserEnvironment -Name $variable
    if (-not [string]::IsNullOrWhiteSpace($value)) { return $value }

    if ($null -eq $Config) { return $Default }
    $property = $Config.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    if (-not (Test-LunarConfigValue -Name $Name -Value $property.Value)) { return $Default }
    $property.Value
}

# Whether one value in the config file is one the server can actually read, which is
# narrower than "has a value". Mirrors LunarConfig.java: a string for the token and the
# host, a number or a numeric string in range for the port.
#
# The gap this closes is not hypothetical and not subtle. Accepting any value type let
# setup read {"token":42,"host":123,"port":true} as a complete config, publish the token
# "42" to the user environment, and print the endpoint http://123:True/mcp -- into the
# client config, since connect writes what this builds. The server, meanwhile, rejected
# every one of those values, so the client pointed at nothing and nothing said why.
function Test-LunarConfigValue {
    param([string]$Name, $Value)
    if ($null -eq $Value) { return $false }
    if ($Name -eq 'port') {
        if ($Value -is [string]) {
            $parsed = 0
            return [int]::TryParse($Value.Trim(), [ref]$parsed) -and $parsed -ge 1 -and $parsed -le 65535
        }
        # Long as well as Int32, because ConvertFrom-Json widens a large JSON integer and
        # the server reads both -- but range-checked on both paths, since LunarConfig
        # rejects a port outside 1-65535 and a script that accepted one would hand out a
        # URL nothing listens on. A Double is refused: 8124.5 is not a port either side
        # will take.
        if (($Value -is [int]) -or ($Value -is [long])) {
            return ($Value -ge 1) -and ($Value -le 65535)
        }
        return $false
    }
    return ($Value -is [string]) -and (-not [string]::IsNullOrWhiteSpace($Value))
}

# A whole config file, for the case where there is nothing to read. The token argument is
# whatever is already exported, so the caller decides whether that is adopted or replaced.
function New-LunarConfigObject {
    param([string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) { $Token = New-LunarToken }
    [pscustomobject]@{
        token = $Token
        host  = '127.0.0.1'
        port  = 8124
    }
}

function Write-LunarStep {
    param([string]$Message)
    Write-Host ('  ' + $Message)
}

# ---------------------------------------------------------------------------
# setup
# ---------------------------------------------------------------------------

function Invoke-LunarSetup {
    $path = Get-LunarConfigPath
    $existing = Read-LunarConfig
    $created = $false

    if ($null -eq $existing) {
        $config = New-LunarConfigObject -Token (Get-LunarUserEnvironment -Name 'ECLIPSE_MCP_TOKEN')
        $created = $true
    } else {
        # Complete the file, one key at a time. A key that is present but blank is the same
        # problem as one that is absent: the server refuses to bind on a blank token, so this
        # is a broken install either way, and nothing here requires the file to have been
        # written by this version of the script.
        $config = $existing
        foreach ($name in @('token', 'host', 'port')) {
            # Whether the *file* carries a value the server can read, not whether the value
            # resolves to something. Get-LunarSetting asks the environment first, so on a
            # machine that already has a token exported it answers "yes" for a file with no
            # token in it -- and setup then writes the file out unchanged, compares a null
            # token against the live one, and clears the very variable it was about to adopt.
            $property = $config.PSObject.Properties[$name]
            $usable = ($null -ne $property) -and (Test-LunarConfigValue -Name $name -Value $property.Value)
            if ($usable) { continue }
            $value = if ($name -eq 'token') {
                          # Adopt the live token rather than cutting a new one. A fresh token
                          # here breaks a working install silently: a running Eclipse holds the
                          # old value in memory and every client reads the new one, so the
                          # result is a 401 for everybody until Eclipse restarts. README.md
                          # tells people to hand-write a partial config file, so this is the
                          # likeliest way anyone reaches this line.
                          Get-LunarUserEnvironment -Name 'ECLIPSE_MCP_TOKEN'
                      } elseif ($name -eq 'host') { '127.0.0.1' }
                      else { 8124 }
            if ([string]::IsNullOrWhiteSpace([string]$value)) { $value = New-LunarToken }
            $config | Add-Member -NotePropertyName $name -NotePropertyValue $value -Force
            $created = $true
        }
    }

    $environmentToken = Get-LunarUserEnvironment -Name 'ECLIPSE_MCP_TOKEN'
    # Whether Eclipse has to be restarted follows from whether the variable actually moved.
    # Telling someone to restart when nothing changed is noise; not telling them when it did
    # is the 401 they will spend an hour on.
    $environmentChanged = $environmentToken -ne $config.token
    if ($environmentChanged) {
        Set-LunarUserEnvironment -Name 'ECLIPSE_MCP_TOKEN' -Value $config.token
    }
    Write-LunarConfig -Config $config | Out-Null

    Write-Host 'lunar is configured.'
    if ($created) {
        Write-LunarStep ('config file  ' + $path + '  (written)')
    } else {
        Write-LunarStep ('config file  ' + $path + '  (already complete, left as it was)')
    }
    Write-LunarStep ('token        ' + $config.token.Length + ' characters, in the config file and in ECLIPSE_MCP_TOKEN')
    Write-LunarStep ('endpoint     ' + (Get-LunarEndpointUrl -Config $config))
    Write-Host ''
    Write-Host 'Next:'
    if ($environmentChanged) {
        Write-LunarStep 'Restart Eclipse. It reads ECLIPSE_MCP_TOKEN when it starts, so a running'
        Write-LunarStep 'one still holds the old value and answers 401 to the new token.'
        Write-LunarStep 'A terminal that is already open keeps the old environment too, so a'
        Write-LunarStep 'client started from it sends an unexpanded ${ECLIPSE_MCP_TOKEN}.'
    } else {
        Write-LunarStep 'ECLIPSE_MCP_TOKEN already held this token, so Eclipse needs no restart.'
    }
    Write-LunarStep '.\lunar.ps1 connect     register lunar with your MCP client'
    Write-LunarStep '.\lunar.ps1 status      confirm the endpoint is answering'
}

# ---------------------------------------------------------------------------
# connect
# ---------------------------------------------------------------------------

function Invoke-LunarConnect {
    $config = Read-LunarConfig
    if ($null -eq $config) {
        throw "No lunar config file at $(Get-LunarConfigPath). Run '.\lunar.ps1 setup' first."
    }
    $url = Get-LunarEndpointUrl -Config $config
    # OpenCode expands {env:NAME} itself, so the token never reaches a file. Every snippet
    # here does the same, which is the whole reason the token is not pasted into a config.
    $header = 'Authorization=Bearer {env:ECLIPSE_MCP_TOKEN}'

    Write-Host ('Registering lunar with ' + $Client + ' at ' + $url)

    if ($Client -eq 'opencode') {
        $opencode = Get-Command opencode -ErrorAction SilentlyContinue
        if ($opencode) {
            $arguments = @('mcp', 'add', 'lunar', '--url', $url, '--header', $header)
            if ($Global) { $arguments += '--global' }
            Write-Host ('  ' + $opencode.Source + ' ' + ($arguments -join ' '))
            Push-Location $ProjectPath
            try {
                & $opencode.Source @arguments
                if ($LASTEXITCODE -ne 0) { throw 'opencode mcp add failed' }
            } finally {
                Pop-Location
            }
            Write-Host ''
            $scope = if ($Global) { 'global' } else { 'project' }
            Write-LunarStep ('Written to the ' + $scope + ' OpenCode config.')
            Write-LunarStep 'OpenCode has to be restarted to load it.'
            return
        }
        Write-Host ''
        Write-Host 'opencode is not on PATH, so here is the same registration by hand.'
        Write-Host 'Run it from the project you want lunar in:'
        Write-Host ''
        Write-Host ('  opencode mcp add lunar --url ' + $url +
            " --header 'Authorization=Bearer {env:ECLIPSE_MCP_TOKEN}'" +
            $(if ($Global) { ' --global' } else { '' }))
        Write-Host ''
        Write-Host 'Or add this to opencode.json yourself:'
        Write-Host ''
        Write-Host (@'
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "lunar": {
        "type": "remote",
        "url": "URL",
        "oauth": false,
        "headers": {
          "Authorization": "Bearer {env:ECLIPSE_MCP_TOKEN}"
        }
      }
    }
  }
}
'@).Replace('URL', $url)
        return
    }

    if ($Client -eq 'codex') {
        $toml = @"
[mcp_servers.lunar]
url = "$url"
bearer_token_env_var = "ECLIPSE_MCP_TOKEN"
"@
        Write-Host 'codex has no add command for a remote server, so add this by hand to'
        Write-Host '~/.codex/config.toml, or to .codex/config.toml in a project:'
        Write-Host ''
        Write-Host $toml
        return
    }

    # Nothing beyond the two supported clients is wired up, and the README documents the endpoint
    # format rather than any client, so name the accepted values here instead of pointing elsewhere.
    throw "Unknown client '$Client'. This script knows opencode and codex; for anything else add the endpoint by hand -- see the README."
}

# ---------------------------------------------------------------------------
# status
# ---------------------------------------------------------------------------

function Test-LunarPortAnswering {
    param([string]$Host_, [int]$Port)
    # A short connect, not Test-NetConnection: that takes seconds to fail, and this runs
    # every time somebody wonders whether lunar is up.
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($Host_, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(1500)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Invoke-LunarStatus {
    $config = Read-LunarConfig
    Write-Host 'lunar configuration'
    Write-Host ''
    $path = Get-LunarConfigPath
    if ($null -eq $config) {
        Write-LunarStep ('config file   MISSING  (' + $path + ')')
        Write-LunarStep 'ECLIPSE_MCP_TOKEN  (user scope) not needed yet'
        Write-Host ''
        Write-Host 'Not set up. Run: .\lunar.ps1 setup'
        return
    }
    Write-LunarStep ('config file   ' + $path)
    # The token the server will actually use, which is the environment one if there is one.
    # A missing key on the config object yields $null here, and $null.Length is 0 -- so this
    # has to ask rather than measure, or an absent token reads as "0 characters", which is
    # indistinguishable from a broken install.
    $token = [string](Get-LunarSetting -Config $config -Name 'token' -Default '')
    if ($token) {
        Write-LunarStep ('token         ' + $token.Length + ' characters')
    } else {
        Write-LunarStep 'token         UNSET in the config file and in the environment'
        Write-LunarStep '  The server refuses to bind without one. Fix: .\lunar.ps1 setup'
    }
    $url = Get-LunarEndpointUrl -Config $config
    Write-LunarStep ('endpoint      ' + $url)

    $environmentToken = Get-LunarUserEnvironment -Name 'ECLIPSE_MCP_TOKEN'
    if ($environmentToken) {
        $same = if ($environmentToken -eq $token) { 'matches the config file' } else { 'DIFFERS from the config file' }
        Write-LunarStep ('ECLIPSE_MCP_TOKEN  ' + $environmentToken.Length + ' characters, ' + $same)
        if ($environmentToken -ne $token) {
            Write-LunarStep '  The environment wins at runtime. Run .\lunar.ps1 setup to re-sync it.'
        }
    } else {
        Write-LunarStep 'ECLIPSE_MCP_TOKEN  NOT SET at user scope'
        Write-LunarStep '  Clients that expand it will send nothing and lunar answers 401.'
        Write-LunarStep '  Fix: .\lunar.ps1 setup'
    }

    Write-Host ''
    Write-Host 'install'
    Write-Host ''
    $eclipseHome = $null
    try {
        $eclipseHome = (Resolve-LunarEclipseHome)
    } catch {
        Write-LunarStep 'Eclipse       not found. Set LUNAR_ECLIPSE_HOME or pass -EclipseHome.'
    }
    if ($eclipseHome) {
        Write-LunarStep ('Eclipse       ' + $eclipseHome)
        $dropins = Join-Path $eclipseHome 'dropins'
        $jars = @(Get-ChildItem -LiteralPath $dropins -Filter 'com.github.lunar.*' -ErrorAction SilentlyContinue)
        if ($jars.Count -gt 0) {
            Write-LunarStep ('dropins       ' + $jars.Count + ' bundle(s)')
        }
        try {
            $pool = Resolve-LunarPoolDir -EclipseHome $eclipseHome
            $inPool = @(Get-ChildItem -LiteralPath $pool -Filter 'com.github.lunar.*' -ErrorAction SilentlyContinue)
            if ($inPool.Count -gt 0) {
                Write-LunarStep ('p2 pool       ' + $inPool.Count + ' bundle(s)')
            } elseif ($jars.Count -eq 0) {
                Write-LunarStep 'bundles       NOT INSTALLED in this Eclipse'
                Write-LunarStep '  Fix: .\build.ps1 -Install   (loose jars, no p2 needed)'
            }
        } catch {
            Write-LunarStep 'p2 pool       not found (fine for a loose-jar install)'
        }
    }

    Write-Host ''
    Write-Host 'endpoint'
    Write-Host ''
    # Through Get-LunarSetting, like every other reading of these two. Re-deriving them here
    # is how status came to disagree with the URL printed three lines above it, and with the
    # port the server actually bound.
    $host_ = [string](Get-LunarSetting -Config $config -Name 'host' -Default '127.0.0.1')
    $port_ = [int](Get-LunarSetting -Config $config -Name 'port' -Default 8124)
    $answering = Test-LunarPortAnswering -Host_ $host_ -Port $port_
    if ($answering) {
        Write-LunarStep ('port ' + $port_ + '   ANSWERING')
    } else {
        Write-LunarStep ('port ' + $port_ + '   not answering')
        Write-LunarStep '  Eclipse has to be running, and has to have been restarted since the'
        Write-LunarStep '  token was set. Its own console says why if it failed to start.'
    }
}

# ---------------------------------------------------------------------------
# uninstall
# ---------------------------------------------------------------------------

function Invoke-LunarUninstall {
    $eclipseHome = $null
    try {
        $eclipseHome = (Resolve-LunarEclipseHome)
    } catch {
        Write-Host 'Eclipse not found, so only the settings will be removed.'
    }

    if ($eclipseHome) {
        # Before anything is deleted, not after. Removing a jar out from under a running
        # Eclipse leaves the bundle active in memory and gone from disk, which is the one
        # state where the next launch silently has no lunar and the current one still
        # answers 200. So this throws, and nothing has been touched yet.
        Assert-LunarEclipseStopped -EclipseHome $eclipseHome -What 'removing Lunar'

        $dropins = Join-Path $eclipseHome 'dropins'
        $jars = @(Get-ChildItem -LiteralPath $dropins -Filter 'com.github.lunar.*' -ErrorAction SilentlyContinue)
        if ($jars.Count -gt 0) {
            Write-Host ('Removing ' + $jars.Count + ' bundle(s) from ' + $dropins)
            foreach ($jar in $jars) { Remove-Item -LiteralPath $jar.FullName -Recurse -Force }
        }

        $site = Join-Path $PSScriptRoot 'site'
        if (Test-Path -LiteralPath $site -PathType Container) {
            # The exit code has to be read, not piped to Out-Host. p2 reports a removal it
            # could not do by exiting non-zero and saying why on the console, and throwing
            # that away reports a feature that is still installed as removed. Throwing here
            # rather than warning stops before the config file goes, because the settings have
            # to outlive the bundles: a server still installed with no token answers 401 to
            # everything, including the person trying to undo this.
            $exit = Uninstall-LunarP2 -EclipseHome $eclipseHome -SiteDir $site `
                -Profile (Get-LunarProfileName -EclipseHome $eclipseHome) `
                -FeatureId 'com.github.lunar.feature'
            if ($exit -ne 0) { throw "Removing the lunar feature through p2 failed (exit code $exit). Its bundles are still installed. The reason is above, or in <eclipse>\configuration\*.log; remove it by hand with the director command under 'Removing it' in README.md, or install it that way and retry." }
        } else {
            Write-Host 'No site\ directory, so there is nothing installed through p2 to remove.'
        }
    }

    $path = Get-LunarConfigPath
    if (-not $Force) {
        Write-Host ''
        Write-Host 'That leaves these in place:'
        Write-LunarStep ('config file   ' + $path)
        Write-LunarStep 'ECLIPSE_MCP_TOKEN  (user scope)'
        Write-Host ''
        Write-Host 'Re-run with -Force to remove them too.'
        return
    }

    if (Test-Path -LiteralPath $path -PathType Leaf) {
        # Not deleted straight: the token inside is the one thing here that cannot be
        # regenerated by re-running setup and handed back to whatever client was using it.
        $backup = $path + '.bak'
        Copy-Item -LiteralPath $path -Destination $backup -Force
        Remove-Item -LiteralPath $path -Force
        Write-Host ('Moved the config file to ' + $backup)
    }
    Set-LunarUserEnvironment -Name 'ECLIPSE_MCP_TOKEN' -Value $null
    Write-Host 'Cleared ECLIPSE_MCP_TOKEN at user scope.'
}

# ---------------------------------------------------------------------------

# Only when run, not when dot-sourced. check-env.ps1 loads this file for its functions and
# must not have it dispatch a command as a side effect of doing so.
if ($MyInvocation.InvocationName -eq '.') { return }

switch ($Command.ToLowerInvariant()) {
    'setup' { Invoke-LunarSetup }
    'connect' { Invoke-LunarConnect }
    'status' { Invoke-LunarStatus }
    'uninstall' { Invoke-LunarUninstall }
    'help' {
        Write-Host @'
lunar - an MCP server for the Eclipse IDE

  .\lunar.ps1 setup       write the config file, cut a token, publish it to the environment
  .\lunar.ps1 connect     register lunar with an MCP client (opencode or codex)
  .\lunar.ps1 status      what is configured, and whether the endpoint is answering
  .\lunar.ps1 uninstall   remove the bundles, and with -Force the settings too

Every one of these steps is written out by hand in README.md under "Doing it by hand".
Nothing here is the only way to do any of it.
'@
    }
    default { throw "Unknown command '$Command'. Try: .\lunar.ps1 help" }
}
