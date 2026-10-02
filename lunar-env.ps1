# Locates the Eclipse installation and p2 pool a lunar build compiles against.
#
# Resolution order for every value, strongest first:
#   1. the explicit argument a caller passed (build.ps1 -EclipseHome / -PoolDir)
#   2. the LUNAR_ECLIPSE_HOME / LUNAR_POOL_DIR environment variable
#   3. discovery from the machine (PATH, eclipse.ini, config.ini)
#   4. a hard error naming every option, so the fix is printed with the failure
#
# Nothing here is machine-specific. Every path is either passed in, read from the
# environment, or derived from the Eclipse installation itself.
#
# Dot-source this file; it defines functions and returns nothing.

# Parses an OSGi/p2 version into a comparable object. Accepts the '3.34.100' form
# used in manifests and the '3.34.100.v20251111-1421' form used in pool file names.
function ConvertTo-LunarVersion {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $numbers = @()
    foreach ($part in ($Text.Trim() -split '[._]')) {
        # Three numeric segments are the version; anything after them is a
        # qualifier and is dropped. Stopping at three also keeps a qualifier that
        # happens to be all digits -- '1.5.1.202212101352' -- from overflowing.
        if ($numbers.Count -ge 3) { break }
        if ($part -notmatch '^\d+$') { break }
        $numbers += [int]$part
    }
    if ($numbers.Count -eq 0) { return $null }
    while ($numbers.Count -lt 3) { $numbers += 0 }
    [pscustomobject]@{
        Major = $numbers[0]; Minor = $numbers[1]; Micro = $numbers[2]
        Raw = ($numbers -join '.')
    }
}

function Compare-LunarVersion {
    param($Left, $Right)
    if ($Left.Major -ne $Right.Major) { if ($Left.Major -lt $Right.Major) { return -1 } else { return 1 } }
    if ($Left.Minor -ne $Right.Minor) { if ($Left.Minor -lt $Right.Minor) { return -1 } else { return 1 } }
    if ($Left.Micro -ne $Right.Micro) { if ($Left.Micro -lt $Right.Micro) { return -1 } else { return 1 } }
    return 0
}

# Reads the version out of a pool entry name. p2 pools hold both jars and exploded
# bundle directories, and a bundle may sit in a directory whose jar is nested, so
# both shapes are handled and the bundle id is taken from the outermost name.
function Get-LunarPoolEntry {
    param([System.IO.FileSystemInfo]$Item)
    $stem = $Item.Name
    if ($stem.EndsWith('.jar', [StringComparison]::OrdinalIgnoreCase)) {
        $stem = $stem.Substring(0, $stem.Length - 4)
    }
    $underscore = $stem.LastIndexOf('_')
    if ($underscore -lt 1) { return $null }
    $id = $stem.Substring(0, $underscore)
    $version = ConvertTo-LunarVersion $stem.Substring($underscore + 1)
    if (-not $version) { return $null }
    # A directory entry's jars are the ones javac should be given.
    $jar = $null
    if ($Item -is [System.IO.DirectoryInfo]) {
        $found = Get-ChildItem -LiteralPath $Item.FullName -Filter '*.jar' -ErrorAction SilentlyContinue |
            Where-Object { -not $_.PSIsContainer } | Select-Object -First 1
        if ($found) { $jar = $found.FullName }
        else { $jar = $Item.FullName }
    } else { $jar = $Item.FullName }
    [pscustomobject]@{ Id = $id; Version = $version; Path = $jar }
}

# Finds the highest version of one bundle in a pool. A targeted lookup rather than
# indexing the whole pool: a p2 pool holds well over a thousand entries, and a full
# enumeration on every build costs seconds for information only 30 bundles need.
function Resolve-LunarBundle {
    param([string]$PoolDir, [string]$Id)
    $best = $null
    foreach ($item in @(Get-ChildItem -LiteralPath $PoolDir -Filter ($Id + '_*') -ErrorAction SilentlyContinue)) {
        $entry = Get-LunarPoolEntry -Item $item
        # The '_' in the filter is not enough: another bundle can share the prefix,
        # so the parsed id has to match exactly or the lookup picks the wrong jar.
        if (-not $entry -or $entry.Id -ne $Id) { continue }
        if ($best -and (Compare-LunarVersion $best.Version $entry.Version) -ge 0) { continue }
        $best = $entry
    }
    $best
}

function Resolve-LunarEclipseHome {
    param([string]$Explicit)
    if ($Explicit) { return (Resolve-Path -LiteralPath $Explicit).Path }
    if ($env:LUNAR_ECLIPSE_HOME) { return (Resolve-Path -LiteralPath $env:LUNAR_ECLIPSE_HOME).Path }

    # eclipse.exe on PATH is the one discovery source that needs no guessing.
    # $env:PATH is walked directly rather than using Get-Command, which resolves
    # only the FIRST match per name -- so it reports one install where there are
    # two, and an ambiguity check built on it can never fire. An Eclipse ships
    # both eclipse.exe and eclipsec.exe, so directories are what get compared.
    $onPath = @()
    foreach ($dir in @($env:PATH -split ';')) {
        if (-not $dir) { continue }
        $candidate = Join-Path $dir 'eclipse.exe'
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $resolved = (Resolve-Path -LiteralPath $dir).Path
        if ($onPath -notcontains $resolved) { $onPath += $resolved }
    }
    if ($onPath.Count -eq 1) { return $onPath[0] }

    if ($onPath.Count -gt 1) {
        throw @"
Found $($onPath.Count) Eclipse installations on PATH. lunar will not guess which
one to build against or install into:

$($onPath | ForEach-Object { '  ' + $_ } | Out-String)Pick one:

    build.ps1 -EclipseHome '<dir>'
or set it for the session:
    `$env:LUNAR_ECLIPSE_HOME = '<dir>'
"@
    }

    throw @'
Could not find an Eclipse installation.

Pass one explicitly:
    build.ps1 -EclipseHome 'C:\path\to\eclipse'
or set it for the session:
    $env:LUNAR_ECLIPSE_HOME = 'C:\path\to\eclipse'
or put eclipse.exe on PATH.

lunar never guesses an install location, because installing into the wrong
Eclipse would overwrite bundles in an installation you did not mean to touch.
'@
}

# Derives the p2 pool from the installation.
#
# config.ini is the only source that states the pool outright: it names the exact
# framework jar the OSGi runtime loads, and a p2 pool always holds it. eclipse.ini's
# -startup is NOT usable on its own -- in a p2 install it points at the single
# launcher jar under <home>/plugins, so deriving the pool from it yields the
# installation directory. It is kept as a candidate, but every candidate has to
# pass the same pool invariant before it is accepted.
function Resolve-LunarPoolDir {
    param([string]$EclipseHome, [string]$Explicit)
    if ($Explicit) { return (Resolve-Path -LiteralPath $Explicit).Path }
    if ($env:LUNAR_POOL_DIR) { return (Resolve-Path -LiteralPath $env:LUNAR_POOL_DIR).Path }

    $candidates = @()

    # config.ini: file:/D:/path/.p2/pool/plugins/org.eclipse.osgi_3.24.0.jar
    $configIni = Join-Path $EclipseHome 'configuration\config.ini'
    if (Test-Path -LiteralPath $configIni) {
        $framework = (Get-Content -LiteralPath $configIni -ErrorAction SilentlyContinue |
            Where-Object { $_ -match '^osgi\.framework=' } | Select-Object -First 1)
        if ($framework) {
            # A java.util.Properties value, so ':' arrives escaped as '\:' and the URI as
            # 'file:/D:/...' -- note the slash BEFORE the drive letter. Unescaping the
            # colons first is required, or the 'file:' prefix never matches; dropping
            # the leading slash after that is equally required, or the result is
            # '\D:\...' which names a non-existent drive and silently never resolves.
            $jar = ($framework -replace '^osgi\.framework=', '')
            $jar = ($jar -replace '\\:', ':') -replace '^file:', ''
            $jar = ($jar -replace '^/', '') -replace '/', '\'
            if ($jar) { $candidates += (Split-Path -Parent $jar) }
        }
    }

    # eclipse.ini: -startup <relative path to a jar inside the pool>
    $ini = Join-Path $EclipseHome 'eclipse.ini'
    if (Test-Path -LiteralPath $ini) {
        $lines = @(Get-Content -LiteralPath $ini -ErrorAction SilentlyContinue)
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i].Trim() -eq '-startup' -and ($i + 1) -lt $lines.Count) {
                $target = $lines[$i + 1].Trim() -replace '/', '\'
                if (-not [IO.Path]::IsPathRooted($target)) { $target = Join-Path $EclipseHome $target }
                $parent = Split-Path -Parent ([IO.Path]::GetFullPath($target))
                # Both levels are offered because the layout differs: a pool jar
                # sits directly in the pool, while an exploded bundle sits one
                # level deeper. Test-LunarPoolDir decides which one is real.
                $candidates += $parent
                $candidates += (Split-Path -Parent $parent)
            }
        }
    }

    # The layout p2 itself creates when it relocates a pool.
    $candidates += (Join-Path (Split-Path -Parent $EclipseHome) '.p2\pool\plugins')

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            if (Test-LunarPoolDir -Candidate $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
        }
    }

    throw @"
Could not find the p2 pool for this Eclipse installation ($EclipseHome).

Tried: $($candidates | Where-Object { $_ } | ForEach-Object { '  ' + $_ } | Out-String)
Pass it explicitly:
    build.ps1 -PoolDir 'C:\path\to\.p2\pool\plugins'
or set it for the session:
    `$env:LUNAR_POOL_DIR = 'C:\path\to\.p2\pool\plugins'

The usual layout is a '.p2\pool\plugins' directory beside the installation.
"@
}

# A p2 pool always contains the OSGi framework bundle, because that is the jar the
# runtime loads. Requiring it rejects the installation directory, which can hold a
# launcher jar and therefore looks pool-shaped by accident. The trailing '_' keeps
# this from being satisfied by org.eclipse.osgi.services, which is not the
# framework. -File is avoided: on Windows PowerShell 5.1 it is a dynamic parameter
# that fails to bind on a path that does not exist, which throws instead of
# returning false and makes a function named Test-* lie about its result.
function Test-LunarPoolDir {
    param([string]$Candidate)
    @(Get-ChildItem -LiteralPath $Candidate -Filter 'org.eclipse.osgi_*.jar' -ErrorAction SilentlyContinue |
        Where-Object { -not $_.PSIsContainer }).Count -gt 0
}

# The director's -profile value. It lives in the installation's config.ini, so no
# caller has to know it: a profile name is generated per install path and is not
# stable across machines.
function Get-LunarProfileName {
    param([string]$EclipseHome)
    $configIni = Join-Path $EclipseHome 'configuration\config.ini'
    if (-not (Test-Path -LiteralPath $configIni)) { return $null }
    $line = (Get-Content -LiteralPath $configIni -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^eclipse\.p2\.profile=' } | Select-Object -First 1)
    if ($line) { return ($line -replace '^eclipse\.p2\.profile=', '').Trim() }
    return $null
}

# Maps each required bundle to a jar in the pool, choosing the highest version that
# satisfies the minimum. Pinning exact file names would tie the build to one
# release train; a minimum accepts any install whose bundles are new enough.
function Resolve-LunarClasspath {
    param([string]$PoolDir, [string[]]$Requirements)
    $resolved = @()
    $missing = @()
    foreach ($requirement in $Requirements) {
        $parts = $requirement -split '@', 2
        $id = $parts[0]
        $minimum = if ($parts.Count -gt 1) { ConvertTo-LunarVersion $parts[1] } else { $null }
        $entry = Resolve-LunarBundle -PoolDir $PoolDir -Id $id
        if (-not $entry) { $missing += "$id (not in $PoolDir)"; continue }
        if ($minimum -and (Compare-LunarVersion $entry.Version $minimum) -lt 0) {
            $missing += "$id (found $($entry.Version.Raw), need >= $($minimum.Raw))"
            continue
        }
        $resolved += $entry.Path
    }
    if ($missing.Count) {
        throw ("Missing or too-old bundles in " + $PoolDir + ":" + [Environment]::NewLine +
               "  " + ($missing -join [Environment]::NewLine + "  ") + [Environment]::NewLine +
               "Point -PoolDir at the pool of the Eclipse you intend to build against.")
    }
    $resolved
}