# Self-check for lunar-env.ps1. Run: powershell -NoProfile -File check-env.ps1
#
# The detection code is the part of the build that cannot be exercised by javac,
# so it gets its own assertions: version parsing, version ordering, pool indexing
# over a synthetic tree, and the error messages an unprepared machine will hit.
# Deliberately offline -- the synthetic pool means this passes with no Eclipse
# installed, so a detection regression is a real failure and not a missing install.

$ErrorActionPreference = 'Stop'
$script:Failures = 0

function Assert-True {
    param([string]$What, [bool]$Condition)
    if ($Condition) {
        Write-Host ('  ok   ' + $What)
    } else {
        Write-Host ('  FAIL ' + $What)
        $script:Failures++
    }
}

function Assert-Throws {
    param([string]$What, [scriptblock]$Action, [string]$MustMention)
    try {
        & $Action
        Write-Host ('  FAIL ' + $What + ' (no error raised)')
        $script:Failures++
    } catch {
        $message = $_.Exception.Message
        $ok = -not $MustMention -or $message -like ('*' + $MustMention + '*')
        if ($ok) {
            Write-Host ('  ok   ' + $What)
        } else {
            Write-Host ('  FAIL ' + $What + ' -- message never mentions "' + $MustMention + '"')
            Write-Host ('       got: ' + ($message -split "`n")[0])
            $script:Failures++
        }
    }
}

. (Join-Path $PSScriptRoot 'lunar-env.ps1')
. (Join-Path $PSScriptRoot 'lunar-p2.ps1')

Write-Host 'ConvertTo-LunarVersion'
Assert-True 'manifest form 3.34.100' ((ConvertTo-LunarVersion '3.34.100').Micro -eq 100)
Assert-True 'qualifier is dropped' ((ConvertTo-LunarVersion '3.34.100.v20251111-1421').Raw -eq '3.34.100')
Assert-True 'two segments are zero padded' ((ConvertTo-LunarVersion '1.5').Minor -eq 5 -and (ConvertTo-LunarVersion '1.5').Micro -eq 0)
Assert-True 'a four-segment qualifier is ignored' ((ConvertTo-LunarVersion '1.5.1.202212101352').Micro -eq 1)
Assert-True 'empty input yields nothing' ($null -eq (ConvertTo-LunarVersion ''))
Assert-True 'non-numeric input yields nothing' ($null -eq (ConvertTo-LunarVersion 'not-a-version'))

Write-Host 'Compare-LunarVersion'
$v330 = ConvertTo-LunarVersion '3.30.0'
$v340 = ConvertTo-LunarVersion '3.40.0'
$v340b = ConvertTo-LunarVersion '3.40.0.v20260101-0001'
Assert-True 'equal majors compare by minor' ((Compare-LunarVersion $v330 $v340) -lt 0)
Assert-True 'a newer version compares greater' ((Compare-LunarVersion $v340 $v330) -gt 0)
Assert-True 'qualifiers are equal for comparison' ((Compare-LunarVersion $v340 $v340b) -eq 0)

Write-Host 'Resolve-LunarBundle'
# A pool holding, per bundle, a stale version and a current one -- plus an
# exploded bundle directory, which is how p2 ships bundles with a nested jar.
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('lunar-env-' + [Guid]::NewGuid().ToString('N'))
$fakePool = Join-Path $sandbox 'pool'
$exploded = Join-Path $fakePool 'org.eclipse.jdt.debug_3.25.0.v20251031-2243'
New-Item -ItemType Directory -Path $exploded -Force | Out-Null
try {
    New-Item -ItemType File -Path (Join-Path $fakePool 'org.eclipse.core.runtime_3.30.0.v20240101-0001.jar') -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $fakePool 'org.eclipse.core.runtime_3.34.100.v20251111-1421.jar') -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $fakePool 'org.apache.felix.scr_2.2.18.jar') -Force | Out-Null
    # A bundle whose id is a prefix of another: the '_' in the lookup filter does
    # not separate these, so only the exact id match keeps this honest.
    New-Item -ItemType File -Path (Join-Path $fakePool 'org.eclipse.osgi.services_3.11.200.jar') -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $fakePool 'org.eclipse.osgi_3.24.0.jar') -Force | Out-Null
    # A name with no underscore carries no version, so it must never resolve.
    New-Item -ItemType File -Path (Join-Path $fakePool 'README.txt') -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $exploded 'jdimodel.jar') -Force | Out-Null

    Assert-True 'the newest version of a bundle wins' `
        ((Resolve-LunarBundle -PoolDir $fakePool -Id 'org.eclipse.core.runtime').Version.Raw -eq '3.34.100')
    Assert-True 'a two-segment version is found' `
        ((Resolve-LunarBundle -PoolDir $fakePool -Id 'org.apache.felix.scr').Version.Raw -eq '2.2.18')
    Assert-True 'a prefix-sharing bundle does not satisfy its own prefix' `
        ((Resolve-LunarBundle -PoolDir $fakePool -Id 'org.eclipse.osgi').Version.Raw -eq '3.24.0')
    Assert-True 'the longer bundle still resolves on its own' `
        ((Resolve-LunarBundle -PoolDir $fakePool -Id 'org.eclipse.osgi.services').Version.Raw -eq '3.11.200')
    Assert-True 'a file with no version never resolves' `
        ($null -eq (Resolve-LunarBundle -PoolDir $fakePool -Id 'README.txt'))
    Assert-True 'an absent bundle resolves to nothing' `
        ($null -eq (Resolve-LunarBundle -PoolDir $fakePool -Id 'org.eclipse.absent'))
    Assert-True 'an exploded bundle resolves to its nested jar' `
        ((Resolve-LunarBundle -PoolDir $fakePool -Id 'org.eclipse.jdt.debug').Path -eq (Join-Path $exploded 'jdimodel.jar'))

    Write-Host 'Resolve-LunarClasspath'
    $resolved = @(Resolve-LunarClasspath -PoolDir $fakePool -Requirements @(
        'org.eclipse.core.runtime@3.34.0', 'org.eclipse.jdt.debug'))
    Assert-True 'both requirements resolve' ($resolved.Count -eq 2)
    Assert-True 'the minimum version is accepted when met' ($resolved[0] -like '*3.34.100*')
    Assert-Throws 'an unmet minimum names the bundle and both versions' {
        Resolve-LunarClasspath -PoolDir $fakePool -Requirements @('org.eclipse.core.runtime@9.9.9')
    } 'need >= 9.9.9'
    Assert-Throws 'an absent bundle is reported by name' {
        Resolve-LunarClasspath -PoolDir $fakePool -Requirements @('org.eclipse.does.not.exist')
    } 'org.eclipse.does.not.exist'
    Assert-Throws 'every missing bundle is listed at once, not one at a time' {
        Resolve-LunarClasspath -PoolDir $fakePool -Requirements @('org.eclipse.absent.one', 'org.eclipse.absent.two')
    } 'org.eclipse.absent.two'
} finally {
    Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'Resolve-LunarEclipseHome'
# Cleared for the discovery cases below, and put back in the finally: a developer's own
# LUNAR_ECLIPSE_HOME is what makes those cases meaningful, and a suite that silently
# removed it would break the very next build.ps1 run on this machine.
$realEclipseHome = $env:LUNAR_ECLIPSE_HOME
$env:LUNAR_ECLIPSE_HOME = $null
Assert-True 'an explicit path is taken as given' `
    ((Resolve-LunarEclipseHome -Explicit $PSScriptRoot) -eq (Resolve-Path -LiteralPath $PSScriptRoot).Path)

# The discovery case that matters most: a real Eclipse ships both eclipse.exe and
# eclipsec.exe, so one installation yields two matches that must collapse to one.
# Get-Command cannot express this -- it resolves only the first hit per name -- so
# a second installation on PATH is what proves the ambiguity guard is reachable.
$binA = Join-Path $sandbox 'eclipseA'
$binB = Join-Path $sandbox 'eclipseB'
New-Item -ItemType Directory -Path $binA -Force | Out-Null
New-Item -ItemType Directory -Path $binB -Force | Out-Null
foreach ($exe in @('eclipse.exe', 'eclipsec.exe')) {
    New-Item -ItemType File -Path (Join-Path $binA $exe) -Force | Out-Null
}
New-Item -ItemType File -Path (Join-Path $binB 'eclipse.exe') -Force | Out-Null
$savedPath = $env:PATH
$sep = [IO.Path]::PathSeparator
try {
    $env:PATH = $binA + $sep + $savedPath
    Assert-True 'both eclipse.exe and eclipsec.exe on PATH resolve to one install' `
        ((Resolve-LunarEclipseHome) -eq (Resolve-Path -LiteralPath $binA).Path)

    # Two installations is the case a Get-Command-based check can never see, and the
    # one that would otherwise install into whichever Eclipse happened to be first.
    $env:PATH = $binB + $sep + $binA + $sep + $savedPath
    Assert-Throws 'two Eclipse installations on PATH are refused, not guessed' {
        Resolve-LunarEclipseHome
    } 'will not guess'

    $env:PATH = $binA + $sep + $savedPath
} finally {
    $env:PATH = $savedPath
}

# Recovery must be documented, not just refused: the message is the manual path.
if (@(Get-Command eclipse.exe, eclipsec.exe -ErrorAction SilentlyContinue).Count -eq 0) {
    Assert-Throws 'an absent Eclipse names both manual overrides' {
        Resolve-LunarEclipseHome
    } 'LUNAR_ECLIPSE_HOME'
} else {
    Write-Host '  skip Eclipse is on PATH here, so the not-found branch cannot be reached'
}
$env:LUNAR_ECLIPSE_HOME = $realEclipseHome

Write-Host 'Resolve-LunarPoolDir / Get-LunarProfileName'
# A synthetic install reproducing the layout that broke the first version of this
# function: eclipse.ini's -startup jar sits in <home>/plugins and looks exactly
# like a pool jar, while the real pool is elsewhere entirely.
$fakeHome = Join-Path $sandbox 'eclipse'
$homePlugins = Join-Path $fakeHome 'plugins'
# Deliberately NOT <sandbox>/.p2: the config.ini assertion below is worthless unless
# the sibling-convention candidate points somewhere else, so that resolving to it
# instead of to config.ini's pool is a visible failure rather than a coincidence.
$siblingPool = Join-Path $sandbox '.p2\pool\plugins'
$namedPool = Join-Path $sandbox 'shared\.p2\pool\plugins'
New-Item -ItemType Directory -Path (Join-Path $fakeHome 'configuration') -Force | Out-Null
New-Item -ItemType Directory -Path $homePlugins -Force | Out-Null
New-Item -ItemType Directory -Path $siblingPool -Force | Out-Null
New-Item -ItemType Directory -Path $namedPool -Force | Out-Null
try {
    $frameworkJar = 'org.eclipse.osgi_3.24.0.v20251126-0427.jar'
    New-Item -ItemType File -Path (Join-Path $namedPool $frameworkJar) -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $siblingPool $frameworkJar) -Force | Out-Null
    @('-startup', 'plugins/org.eclipse.equinox.launcher_1.7.100.jar', '-product', 'x') |
        Set-Content -LiteralPath (Join-Path $fakeHome 'eclipse.ini')
    # <home>/plugins holds a launcher jar and so passes a naive "has jars" test.
    New-Item -ItemType File -Path (Join-Path $homePlugins 'org.eclipse.equinox.launcher_1.7.100.jar') -Force | Out-Null

    Assert-True 'the launcher jar under <home>/plugins is not mistaken for the pool' `
        (-not (Test-LunarPoolDir -Candidate $homePlugins))
    Assert-True 'a pool is recognised by its framework jar' `
        (Test-LunarPoolDir -Candidate $namedPool)

    # A real config.ini stores a URI, so the path carries a slash BEFORE the drive
    # letter AND an escaped drive colon: 'file\:/D\:/path'. Driving the test with
    # the drive-letter form 'file\:C:/path' instead lets the leading-slash bug pass.
    # The line is built in a variable first: inside an @( ... ) array literal a
    # top-level '+' is read as an element separator, which silently writes five
    # lines instead of two and leaves the parser reading an empty value.
    $asUri = 'file\:/' + (($namedPool -replace '\\', '/') -replace '^([A-Za-z]):', '$1\:')
    Assert-True 'the test value really is the slash-before-drive form' `
        ($asUri -match '^file\\:/[A-Za-z]\\?:/')
    $frameworkLine = 'osgi.framework=' + $asUri + '/' + $frameworkJar
    Set-Content -LiteralPath (Join-Path $fakeHome 'configuration\config.ini') `
        -Value @('eclipse.p2.profile=Fake_Profile', $frameworkLine)
    # A three-line file here would mean the array literal mis-parsed again and the
    # assertions below would be testing an empty value rather than a real path.
    Assert-True 'config.ini holds exactly the two lines written' `
        (@(Get-Content -LiteralPath (Join-Path $fakeHome 'configuration\config.ini')).Count -eq 2)
    Assert-True 'the profile name is read from config.ini' `
        ((Get-LunarProfileName -EclipseHome $fakeHome) -eq 'Fake_Profile')
    Assert-True 'the pool named by config.ini wins over the sibling convention' `
        ((Resolve-LunarPoolDir -EclipseHome $fakeHome) -eq (Resolve-Path -LiteralPath $namedPool).Path)

    # Remove config.ini and the same install must fall back to the sibling pool,
    # which proves the previous assertion followed config.ini rather than luck.
    Remove-Item -LiteralPath (Join-Path $fakeHome 'configuration\config.ini')
    Assert-True 'without config.ini the sibling pool is used instead' `
        ((Resolve-LunarPoolDir -EclipseHome $fakeHome) -eq (Resolve-Path -LiteralPath $siblingPool).Path)

    $env:LUNAR_POOL_DIR = $fakeHome
    Assert-True 'an explicit pool argument outranks discovery' `
        ((Resolve-LunarPoolDir -EclipseHome $fakeHome -Explicit $namedPool) -eq (Resolve-Path -LiteralPath $namedPool).Path)
    Assert-True 'the pool environment variable outranks discovery' `
        ((Resolve-LunarPoolDir -EclipseHome $fakeHome) -eq (Resolve-Path -LiteralPath $fakeHome).Path)
    $env:LUNAR_POOL_DIR = $null

    # With no config.ini and no sibling pool, nothing is derivable: that has to fail
    # loudly with the manual option, not silently fall back to a wrong directory.
    Remove-Item -LiteralPath (Join-Path $sandbox '.p2') -Recurse -Force
    Assert-Throws 'an undeducible pool prints the manual override' {
        Resolve-LunarPoolDir -EclipseHome $fakeHome
    } 'LUNAR_POOL_DIR'
    Assert-True 'an explicit override is honoured even for a directory that is not a pool' `
        ((Resolve-LunarPoolDir -EclipseHome $fakeHome -Explicit $homePlugins) -eq (Resolve-Path -LiteralPath $homePlugins).Path)
} finally {
    $env:LUNAR_POOL_DIR = $null
    Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'build.ps1 floors agree with the manifests'
# build.ps1 documents the floors on bundles lunar Require-Bundle as coming from the
# manifests so they cannot drift. Nothing enforced that, so a manifest bump would
# have silently disagreed with the build. This is what makes the claim true.
$manifestText = ''
foreach ($manifest in @(
        (Join-Path $PSScriptRoot 'src\META-INF\MANIFEST.MF')) +
        @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'bundles') -Filter 'MANIFEST.MF' -Recurse |
            ForEach-Object FullName)) {
    # Unfold first. A wrapped header continues on the next line with one leading
    # space, which routinely splits a bundle id -- 'org.eclip' / 'se.jdt.launching'.
    # Matching before unfolding yields ids that match nothing, and the floors then
    # look like they disagree with the build when they never did.
    $manifestText += "`n" + ((Get-Content -LiteralPath $manifest -Raw) -replace "\r?\n ", '')
}
# Require-Bundle is wrapped, so the value is recovered before it is matched. Only
# the floor is read: the upper bound is 4.0.0 for everything and carries no
# information. The id is anchored to a token boundary -- an unanchored [\w.]+ also
# matches a suffix like 'urces', which silently compares the wrong bundle. lunar's
# own bundles are excluded: they are built here, not resolved from a pool.
$manifestFloors = @{}
foreach ($match in [regex]::Matches($manifestText,
        '(?<![.\w])([\w]+(?:\.[\w]+)+);bundle-version="\[([0-9.]+),')) {
    $manifestFloors[$match.Groups[1].Value] = $match.Groups[2].Value
}
Assert-True 'manifest floors were actually found to compare against' ($manifestFloors.Count -ge 8)

$buildText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'build.ps1') -Raw
$buildFloors = @{}
foreach ($match in [regex]::Matches($buildText, "'([\w.]+)@([0-9.]+)'")) {
    $buildFloors[$match.Groups[1].Value] = $match.Groups[2].Value
}
foreach ($id in $manifestFloors.Keys) {
    if ($id -like 'com.github.lunar.*') { continue }
    Assert-True ("$id floor is " + $manifestFloors[$id] + ' in build.ps1') `
        ($buildFloors.ContainsKey($id) -and $buildFloors[$id] -eq $manifestFloors[$id])
}
Assert-True 'no bundle carries a floor that no manifest declares' `
    (@($buildFloors.Keys | Where-Object {
        $_ -notlike 'com.github.lunar.*' -and -not $manifestFloors.ContainsKey($_)
      }).Count -eq 0)

Write-Host 'lunar-p2: identity, feature.xml and category.xml'
$bundles = Get-LunarBundleIdentityList -LunarRoot $PSScriptRoot
Assert-True 'five bundles are found' ($bundles.Count -eq 5)
Assert-True 'every bundle id is a com.github.lunar id' `
    (@($bundles | Where-Object { $_.id -notlike 'com.github.lunar.*' }).Count -eq 0)
Assert-True 'the io bundle is last, as the publisher order expects' `
    ($bundles[4].id -eq 'com.github.lunar.io')

# Every version must be purely numeric. A qualifier would ship literally here --
# there is no PDE or Tycho to substitute it, so p2 would keep seeing one IU id and
# would never install the rebuilt artifact.
Assert-True 'every bundle version is numeric, with nothing left to substitute' `
    (@($bundles | Where-Object { $_.version -notmatch '^\d+(\.\d+){2,3}$' }).Count -eq 0)

# build.ps1 names each jar <id>_<version>.jar, so the identity and the artifact name
# are one fact. Verified against the name build.ps1 actually produces.
foreach ($bundle in $bundles) {
    Assert-True ($bundle.id + ' is packaged as ' + $bundle.id + '_' + $bundle.version + '.jar') `
        ((Select-String -LiteralPath (Join-Path $PSScriptRoot 'build.ps1') -SimpleMatch `
            '$identity.id + ''_'' + $identity.version' -Quiet))
}

$p2Sandbox = Join-Path ([IO.Path]::GetTempPath()) ('lunar-p2-' + [Guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $p2Sandbox -Force | Out-Null
    $featurePath = Join-Path $p2Sandbox 'feature.xml'
    $featureVersion = New-LunarFeatureXml -Bundles $bundles -FeatureId 'com.github.lunar.feature' -Path $featurePath
    Assert-True 'the feature version is the highest bundle version' `
        ($featureVersion -eq (($bundles | Sort-Object { [version]$_.version } -Descending)[0].version))

    $featureXml = [xml](Get-Content -LiteralPath $featurePath -Raw)
    Assert-True 'feature.xml is well-formed and named as asked' `
        ($featureXml.feature.id -eq 'com.github.lunar.feature')
    Assert-True 'feature.xml lists every bundle' `
        (@($featureXml.feature.plugin).Count -eq $bundles.Count)

    # The trap worth a test: p2 reads a feature entry's version as an EXACT match
    # against Bundle-Version, not a floor. A feature that says 3.24.0 for a bundle
    # built at 3.24.100 publishes, then resolves to nothing at install time.
    $pluginsById = @{}
    foreach ($plugin in @($featureXml.feature.plugin)) { $pluginsById[$plugin.id] = $plugin.version }
    foreach ($bundle in $bundles) {
        Assert-True ($bundle.id + ' is pinned at exactly ' + $bundle.version) `
            ($pluginsById.ContainsKey($bundle.id) -and $pluginsById[$bundle.id] -eq $bundle.version)
    }

    # Set-Content -Encoding UTF8 writes a BOM under Windows PowerShell 5.1, and a
    # BOM ahead of the declaration is a free way to fail in a Java parser.
    $leading = [System.IO.File]::ReadAllBytes($featurePath)[0..2]
    Assert-True 'feature.xml is written without a BOM' `
        (-not ($leading[0] -eq 0xEF -and $leading[1] -eq 0xBB -and $leading[2] -eq 0xBF))

    $categoryPath = Join-Path $p2Sandbox 'category.xml'
    New-LunarCategoryXml -FeatureId 'com.github.lunar.feature' -Version $featureVersion -Path $categoryPath
    $categoryXml = [xml](Get-Content -LiteralPath $categoryPath -Raw)
    Assert-True 'category.xml points at the feature that was published' `
        ($categoryXml.site.feature.id -eq 'com.github.lunar.feature' -and
         $categoryXml.site.feature.version -eq $featureVersion)
    # A category whose IUs do not exist produces no IU at all, so the mapping has to
    # be by name: <category-def name=..> is what <category name=..> refers to.
    Assert-True 'the category name matches the def it is assigned to' `
        ($categoryXml.site.feature.category.name -eq $categoryXml.site.'category-def'.name)

    Assert-True 'the installable IU id keeps .feature.group as part of the id' `
        ((Get-LunarFeatureGroupId -FeatureId 'com.github.lunar.feature') -eq 'com.github.lunar.feature.feature.group')

    # 'file:/D:/path'. Handing the director 'file:D:/path' or a bare path fails to
    # resolve, so the slash before the drive letter is the thing to hold.
    $uri = Get-LunarP2Uri -Path $p2Sandbox
    Assert-True 'a repository URI keeps the slash before the drive letter' `
        ($uri -match '^file:/{1,3}[A-Za-z]:/')

    # A '#' is a URI fragment delimiter and a '%' starts an escape, so a repository
    # path containing either truncates or decodes to somewhere else entirely. Both
    # are legal in a Windows directory name, so this is a real path, not a contrived
    # one -- and the failure it causes is 'repository not found' with nothing else.
    $oddDir = Join-Path $p2Sandbox 'we#ird 100%'
    New-Item -ItemType Directory -Path $oddDir -Force | Out-Null
    $oddUri = Get-LunarP2Uri -Path $oddDir
    Assert-True 'a repository URI escapes # and % in the path' `
        ($oddUri -match '%23' -and $oddUri -match '%25' -and $oddUri -notmatch '#')
    Assert-True 'an escaped repository URI resolves back to the same directory' `
        (([uri]$oddUri).LocalPath.TrimEnd('\') -eq (Resolve-Path -LiteralPath $oddDir).Path.TrimEnd('\'))

    Assert-Throws 'a manifest without Bundle-SymbolicName is rejected' {
        $badDir = Join-Path $p2Sandbox 'badbundle'
        New-Item -ItemType Directory -Path (Join-Path $badDir 'META-INF') -Force | Out-Null
        $mf = Join-Path $badDir 'META-INF\MANIFEST.MF'
        Set-Content -LiteralPath $mf -Value @('Manifest-Version: 1.0', 'Bundle-Version: 0.0.1')
        Get-LunarBundleIdentity -ManifestDir $badDir
    } 'No Bundle-SymbolicName'

    # Installing the site on top of loose jars resolves every bundle twice.
    $fakeEclipse = Join-Path $p2Sandbox 'eclipse'
    $fakeDropins = Join-Path $fakeEclipse 'dropins'
    New-Item -ItemType Directory -Path $fakeDropins -Force | Out-Null
    Assert-True 'an empty dropins is not a conflict' `
        ($null -eq (Assert-LunarNoDropinsCopy -EclipseHome $fakeEclipse))
    New-Item -ItemType File -Path (Join-Path $fakeDropins 'com.github.lunar.core_0.0.1.jar') -Force | Out-Null
    Assert-Throws 'loose jars in dropins block the site install' {
        Assert-LunarNoDropinsCopy -EclipseHome $fakeEclipse
    } 'two copies'

    # The exploded form is picked up just as eagerly as the jar, and a jar-only test
    # would wave it straight through into an install that breaks Eclipse's resolution.
    Remove-Item -LiteralPath (Join-Path $fakeDropins 'com.github.lunar.core_0.0.1.jar') -Force
    New-Item -ItemType Directory -Path (Join-Path $fakeDropins 'com.github.lunar.core_0.0.1') -Force | Out-Null
    Assert-Throws 'an exploded bundle directory in dropins blocks the site install too' {
        Assert-LunarNoDropinsCopy -EclipseHome $fakeEclipse
    } 'two copies'
    Remove-Item -LiteralPath (Join-Path $fakeDropins 'com.github.lunar.core_0.0.1') -Recurse -Force

    # config.ini states the data area as an escaped file: URI, and an install lands
    # there rather than anywhere derivable from the install path.
    $fakeConfig = Join-Path $fakeEclipse 'configuration\config.ini'
    New-Item -ItemType Directory -Path (Split-Path -Parent $fakeConfig) -Force | Out-Null
    $fakeArea = Join-Path $p2Sandbox 'my area\.p2'
    $areaIni = 'eclipse.p2.data.area=file\:/' + ($fakeArea -replace '\\', '/') -replace ' ', '%20'
    Set-Content -LiteralPath $fakeConfig -Value $areaIni
    Assert-True 'the p2 data area is read out of config.ini' `
        ((Resolve-LunarP2DataArea -EclipseHome $fakeEclipse) -eq $fakeArea)

    # The post-condition that catches p2 exiting 0 without installing anything, which
    # is what a rebuild at an unchanged Bundle-Version does.
    Assert-Throws 'an install that landed nothing is not reported as success' {
        Assert-LunarP2Installed -DataArea $fakeArea -Bundles $bundles
    } 'the same version as one already in'
    $fakePool = Join-Path $fakeArea 'pool\plugins'
    New-Item -ItemType Directory -Path $fakePool -Force | Out-Null
    foreach ($bundle in $bundles) {
        New-Item -ItemType File -Path (Join-Path $fakePool ($bundle.id + '_' + $bundle.version + '.jar')) -Force | Out-Null
    }
    Assert-True 'an install with every bundle in the pool passes the post-condition' `
        ($null -eq (Assert-LunarP2Installed -DataArea $fakeArea -Bundles $bundles))
    # One stale version is enough: p2 installs the IU as a unit or not at all.
    Remove-Item -LiteralPath (Join-Path $fakePool ($bundles[0].id + '_' + $bundles[0].version + '.jar')) -Force
    Assert-Throws 'one bundle missing from the pool fails the post-condition' {
        Assert-LunarP2Installed -DataArea $fakeArea -Bundles $bundles
    } 'The director reported success'

    Assert-Throws 'a missing eclipsec.exe names both manual routes' {
        Get-LunarEclipseConsole -EclipseHome $fakeEclipse
    } '-InstallP2'

    # Every eclipsec.exe call has to go through Invoke-LunarEclipseApp, because that is
    # what pins the working directory. A direct `& $console` added later would put the
    # Eclipse product's own files back in the checkout, and nothing else here would
    # notice. Cheap to assert, and it is the only thing that catches a fifth call site.
    $directCalls = @(Select-String -LiteralPath (Join-Path $PSScriptRoot 'lunar-p2.ps1') `
        -Pattern '&\s+\$console' -AllMatches)
    Assert-True 'every eclipsec.exe invocation goes through Invoke-LunarEclipseApp' `
        ($directCalls.Count -eq 0)
    Assert-True 'Invoke-LunarEclipseApp is reached from publish, install and uninstall' `
        ((@(Select-String -LiteralPath (Join-Path $PSScriptRoot 'lunar-p2.ps1') `
            -Pattern 'Invoke-LunarEclipseApp -EclipseConsole' -AllMatches)).Count -ge 4)
} finally {
    Remove-Item -LiteralPath $p2Sandbox -Recurse -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# lunar.ps1 -- the config file, the endpoint URL, the four subcommands, and the
# ordering of the uninstall guard.
#
# Run against a temporary USERPROFILE, because the real one holds the token a running
# Eclipse is using. Everything here is offline: no registry writes, no Eclipse, no
# client CLI.
# ---------------------------------------------------------------------------

$sandboxProfile = Join-Path ([System.IO.Path]::GetTempPath()) `
    ('lunar-userprofile-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$realUserProfile = $env:USERPROFILE
try {
    $env:USERPROFILE = $sandboxProfile
    # Dot-sourced for its functions. The switch at the bottom does not run, so nothing here
    # touches the registry or a real Eclipse.
    . (Join-Path $PSScriptRoot 'lunar.ps1')

    # The two environment accessors, replaced for the duration of this block.
    #
    # Set-* is replaced because the subcommands are the only thing that writes the token to
    # the user environment, so a test of them that really called it would be a test nobody
    # could afford to run -- it would overwrite the token the developer's Eclipse is
    # answering with. Get-* is replaced for the same reason in the other direction: read
    # live, and on any machine with a token exported every assertion below about "adopt the
    # live token" would quietly be an assertion about the developer's own machine.
    $script:fakeEnvironment = @{}
    $script:environmentWrites = New-Object System.Collections.ArrayList
    function Get-LunarUserEnvironment {
        param([string]$Name)
        if ($script:fakeEnvironment.ContainsKey($Name)) { return $script:fakeEnvironment[$Name] }
        return $null
    }
    function Set-LunarUserEnvironment {
        # Same untyped $Value as the real one, so what is recorded is what the caller
        # actually passed -- a null is what deletes a variable, an empty string does not.
        param([string]$Name, $Value)
        [void]$script:environmentWrites.Add(@{ Name = $Name; Value = $Value })
        $script:fakeEnvironment[$Name] = $Value
    }

    # The subcommands report through Write-Host, which reaches the information stream, so
    # what they say is only readable through 6>&1. It is worth asserting on: "setup adopts
    # the token" is a claim about a message as much as about a file, and the messages are
    # the part a person actually sees.
    function Get-LunarConsoleText {
        param([scriptblock]$Action)
        ((& $Action 6>&1) | ForEach-Object { [string]$_ }) -join "`n"
    }

    $configPath = Get-LunarConfigPath
    Assert-True 'the config file lands under the user profile' `
        ($configPath -eq (Join-Path (Join-Path $sandboxProfile '.lunar') 'config.json'))
    Assert-True 'an absent config file reads as absent, not as an error' `
        ($null -eq (Read-LunarConfig))

    # The URL has to match the one the Java side builds, byte for byte, because this is
    # what a client is told to connect to. A disagreement shows up as a 404 at the client
    # and nothing anywhere else.
    $full = [pscustomobject]@{ token = 't'; host = 'localhost'; port = 9001 }
    Assert-True 'the endpoint URL matches the server default' `
        ((Get-LunarEndpointUrl -Config ([pscustomobject]@{ token = 't' })) -eq 'http://127.0.0.1:8124/mcp')
    Assert-True 'host and port from the file reach the URL' `
        ((Get-LunarEndpointUrl -Config $full) -eq 'http://localhost:9001/mcp')
    Assert-True 'a port from the config file reaches the URL' `
        ((Get-LunarEndpointUrl -Config ([pscustomobject]@{ token = 't'; port = 1234 })) `
            -eq 'http://127.0.0.1:1234/mcp')
    # An IPv6 literal has to be bracketed, or the port separator is indistinguishable
    # from the address and the client parses a nonsense port.
    Assert-True 'an IPv6 host is bracketed in the URL' `
        ((Get-LunarEndpointUrl -Config ([pscustomobject]@{ token = 't'; host = '::1' })) `
            -eq 'http://[::1]:8124/mcp')

    # Half-written files. The Java side falls back to defaults, so the script has to read
    # them the same way, or setup would print one URL and the server would bind another.
    Assert-True 'a missing host falls back to the server default' `
        ((Get-LunarSetting -Config ([pscustomobject]@{ port = 9001 }) -Name 'host' -Default '127.0.0.1') `
            -eq '127.0.0.1')
    Assert-True 'a blank host falls back to the server default' `
        ((Get-LunarSetting -Config ([pscustomobject]@{ host = '  ' }) -Name 'host' -Default '127.0.0.1') `
            -eq '127.0.0.1')
    Assert-True 'a present host is used' `
        ((Get-LunarSetting -Config ([pscustomobject]@{ host = 'localhost' }) -Name 'host' -Default 'x') `
            -eq 'localhost')

    # Round trip. A config file the script writes and the script cannot read back is a
    # file that has to be hand-edited, which is the opposite of the point of it.
    $roundTrip = [pscustomobject]@{ token = 'abc123'; host = '127.0.0.1'; port = 8124 }
    Write-LunarConfig -Config $roundTrip | Out-Null
    $reread = Read-LunarConfig
    Assert-True 'a written config file reads back identically' `
        ($reread.token -eq 'abc123' -and $reread.port -eq 8124)
    # UTF-8 without a BOM: ConvertFrom-Json on a BOM-prefixed file under Windows PowerShell
    # throws, and so does the Java reader.
    $bytes = [System.IO.File]::ReadAllBytes($configPath)
    Assert-True 'the config file is written without a byte order mark' `
        (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF))
    Assert-Throws 'a malformed config file is reported, not silently ignored' {
        Set-Content -LiteralPath $configPath -Value 'not json' -NoNewline
        Read-LunarConfig | Out-Null
    } 'not valid JSON'
    # Valid JSON is not a usable config. ConvertFrom-Json accepts these happily, every
    # property lookup on the result misses, and setup would then report success and leave
    # behind a file the server cannot read -- so it refuses to bind and the user is told
    # nothing until they go looking. Refusing here is the only place that can catch it.
    foreach ($notAnObject in @('[1,2,3]', '"hello"', '42', 'true', 'null')) {
        Assert-Throws ('a config file holding ' + $notAnObject + ' is refused, not half-used') {
            Set-Content -LiteralPath $configPath -Value $notAnObject -NoNewline
            Read-LunarConfig | Out-Null
        } 'not an object'
    }

    $tokenA = New-LunarToken
    $tokenB = New-LunarToken
    Assert-True 'the token generator produces something long enough to be a token' `
        ($tokenA.Length -ge 32)
    Assert-True 'two generated tokens differ' ($tokenA -ne $tokenB)

    Write-Host 'Invoke-LunarSetup'
    # From nothing: the state a stranger is in who has just cloned the repository. The file
    # goes first, because the assertions above deliberately left unusable ones behind.
    Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
    $script:fakeEnvironment = @{}
    $script:environmentWrites.Clear()
    $out = Get-LunarConsoleText { Invoke-LunarSetup }
    $setup = Read-LunarConfig
    Assert-True 'setup on a bare machine writes a config file' ($null -ne $setup)
    Assert-True 'setup writes a token long enough to be one' ($setup.token.Length -ge 32)
    Assert-True 'setup fills in the same defaults the server uses' `
        ($setup.host -eq '127.0.0.1' -and $setup.port -eq 8124)
    Assert-True 'setup publishes the token to the user environment' `
        ($script:fakeEnvironment['ECLIPSE_MCP_TOKEN'] -eq $setup.token)
    Assert-True 'setup never prints the token it just wrote' `
        (-not ($out -match [regex]::Escape($setup.token)))
    Assert-True 'setup says to restart Eclipse, because the token just changed' `
        ($out -match 'Restart Eclipse')

    # The failure this second run guards against is silent. A rotated token leaves a running
    # Eclipse holding the old value in memory while every client reads the new one, so all of
    # them get 401 until somebody restarts Eclipse -- and nothing anywhere says why.
    $first = $setup.token
    $script:environmentWrites.Clear()
    $out = Get-LunarConsoleText { Invoke-LunarSetup }
    Assert-True 'a second setup does not rotate the token' ((Read-LunarConfig).token -eq $first)
    Assert-True 'a second setup does not rewrite an unchanged variable' `
        ($script:environmentWrites.Count -eq 0)
    Assert-True 'a second setup says no restart is needed' ($out -match 'needs no restart')

    # The README tells people to hand-write a config file, so a partial one with no token in
    # it is the likeliest way setup meets an existing install.
    $script:fakeEnvironment = @{ ECLIPSE_MCP_TOKEN = 'a-token-already-in-use' }
    $script:environmentWrites.Clear()
    Set-Content -LiteralPath $configPath -Value '{"host":"127.0.0.1","port":8124}' -NoNewline
    Get-LunarConsoleText { Invoke-LunarSetup } | Out-Null
    Assert-True 'setup adopts a live token rather than cutting a new one' `
        ((Read-LunarConfig).token -eq 'a-token-already-in-use')
    Assert-True 'adopting a live token leaves the variable alone' `
        ($script:environmentWrites.Count -eq 0)

    # A config file the server cannot read is the round trip the phase exists to hold, and
    # the shape that breaks it is not an unusable type but a type the script happens to
    # accept. Read as "complete" before this was fixed, {"token":42,"host":123,"port":true}
    # published a one-character bearer token to the user environment and handed the client
    # http://123:True/mcp -- while LunarConfig.java rejected all three. So: setup rewrites
    # what the server would refuse, and reports the server's own defaults for the rest.
    $script:fakeEnvironment = @{}
    $script:environmentWrites.Clear()
    Set-Content -LiteralPath $configPath `
        -Value '{"token":42,"host":123,"port":true}' -NoNewline
    $out = Get-LunarConsoleText { Invoke-LunarSetup }
    $rewritten = Read-LunarConfig
    Assert-True 'setup replaces a numeric token with a generated one' `
        ($rewritten.token -is [string] -and $rewritten.token.Length -ge 32)
    Assert-True 'setup replaces a numeric host with the server default' `
        ($rewritten.host -eq '127.0.0.1')
    Assert-True 'setup replaces a boolean port with the server default' `
        ($rewritten.port -eq 8124)
    Assert-True 'setup publishes a real token, not the number it found' `
        ($script:fakeEnvironment['ECLIPSE_MCP_TOKEN'] -eq $rewritten.token)
    Assert-True 'the rewritten endpoint is the one the server binds' `
        ($out -match 'http://127\.0\.0\.1:8124/mcp')
    Assert-True 'no bogus host or port reaches the printed endpoint' `
        (-not ($out -match '123|True'))

    # The same file, one key at a time. A port that is a string but not a number is the one
    # that used to reach status as a [int] cast and kill the whole report.
    Set-Content -LiteralPath $configPath -Value '{"port":"abc"}' -NoNewline
    Assert-True 'an unparseable port falls back to the server default' `
        ((Get-LunarSetting -Config (Read-LunarConfig) -Name 'port' -Default 8124) -eq 8124)
    Get-LunarConsoleText { Invoke-LunarSetup } | Out-Null
    Assert-True 'setup rewrites an unparseable port' ((Read-LunarConfig).port -eq 8124)
    # Both spellings the server accepts have to survive setup, or it repairs working files.
    Set-Content -LiteralPath $configPath -Value '{"token":"keepme","port":"9100"}' -NoNewline
    $script:fakeEnvironment = @{}
    $script:environmentWrites.Clear()
    Get-LunarConsoleText { Invoke-LunarSetup } | Out-Null
    Assert-True 'a port written as a number string is left alone' `
        ((Read-LunarConfig).port -eq '9100')
    Set-Content -LiteralPath $configPath -Value '{"port":9200}' -NoNewline
    $script:fakeEnvironment = @{}
    Get-LunarConsoleText { Invoke-LunarSetup } | Out-Null
    Assert-True 'a port written as a number is left alone' ((Read-LunarConfig).port -eq 9200)
    Set-Content -LiteralPath $configPath -Value '{"token":"   ","port":9200}' -NoNewline
    $script:fakeEnvironment = @{}
    Get-LunarConsoleText { Invoke-LunarSetup } | Out-Null
    $repaired = Read-LunarConfig
    Assert-True 'a blank token is repaired rather than kept' ($repaired.token.Length -ge 32)
    Assert-True 'repairing one key leaves the readable ones alone' ($repaired.port -eq 9200)

    Write-Host 'Invoke-LunarConnect'
    Write-LunarConfig -Config ([pscustomobject]@{ token = 'a-token-worth-not-printing'
        host = '127.0.0.1'; port = 8124 }) | Out-Null
    # Only the codex branch. opencode is on PATH for anyone running this suite, and the
    # opencode branch really does run `opencode mcp add`, which would write a registration
    # into whichever project the terminal happened to be sitting in.
    $Client = 'codex'
    $out = Get-LunarConsoleText { Invoke-LunarConnect }
    Assert-True 'codex is handed a snippet naming the environment variable' `
        ($out -match 'bearer_token_env_var = "ECLIPSE_MCP_TOKEN"')
    Assert-True 'the codex snippet carries the resolved URL' `
        ($out -match 'http://127\.0\.0\.1:8124/mcp')
    Assert-True 'no client snippet carries the token' `
        (-not ($out -match 'a-token-worth-not-printing'))
    $Client = 'nonesuch'
    Assert-Throws 'an unknown client is named in the error' `
        { Invoke-LunarConnect 6>&1 | Out-Null } "Unknown client 'nonesuch'"
    $Client = 'opencode'

    # Everything from here down touches Eclipse, so the resolver is replaced first: a real
    # one finds the developer's own installation, and the suite would then read -- or worse,
    # delete from -- a live Eclipse while claiming to be offline. The port probe is replaced
    # for the same reason: it opens a real socket, and "not answering" is as true on a
    # developer's machine with Eclipse running as it is on one without.
    function Resolve-LunarEclipseHome { throw 'no Eclipse in this test' }
    function Test-LunarPortAnswering { return $false }

    Write-Host 'Invoke-LunarStatus'
    # A config file with no token in it is what a hand-written file becomes once only host
    # and port were filled in. $null.Length is 0, so a status that measures the token reports
    # "0 characters" -- which is what a broken install looks like, and is not one.
    $script:fakeEnvironment = @{}
    Write-LunarConfig -Config ([pscustomobject]@{ host = '127.0.0.1'; port = 8124 }) | Out-Null
    $out = Get-LunarConsoleText { Invoke-LunarStatus }
    Assert-True 'status says the token is unset rather than measuring nothing' `
        ($out -match 'UNSET')
    Assert-True 'status never reports a zero-length token' (-not ($out -match '0 characters'))
    Assert-True 'status never claims the port is answering' (-not ($out -cmatch 'ANSWERING'))
    # status used to cast the port itself, so a config file holding a non-numeric port killed
    # the whole report after it had already printed a URL. The default has to reach both.
    Set-Content -LiteralPath $configPath -Value '{"token":"t","host":"127.0.0.1","port":"abc"}' -NoNewline
    $out = Get-LunarConsoleText { Invoke-LunarStatus }
    Assert-True 'status survives a non-numeric port in the file' ($out -match 'port 8124')
    Assert-True 'status quotes one endpoint, and it is the one the server binds' `
        (([regex]::Matches($out, 'http://[^ ]+/mcp').Count -eq 1) `
            -and ($out -notmatch 'http://127\.0\.0\.1:abc'))
    Write-LunarConfig -Config ([pscustomobject]@{ token = 'a-token-worth-not-printing'
        host = '127.0.0.1'; port = 8124 }) | Out-Null
    Assert-True 'status never prints the token' `
        (-not ((Get-LunarConsoleText { Invoke-LunarStatus }) -match 'a-token-worth-not-printing'))

    Write-Host 'Invoke-LunarUninstall'
    Write-LunarConfig -Config ([pscustomobject]@{ token = 'the-last-token'
        host = '127.0.0.1'; port = 8124 }) | Out-Null
    $out = Get-LunarConsoleText { Invoke-LunarUninstall }
    Assert-True 'uninstall without -Force keeps the config file' (Test-Path -LiteralPath $configPath)
    Assert-True 'uninstall without -Force names what it left behind' `
        ($out -match 'Re-run with -Force')

    $script:environmentWrites.Clear()
    $Force = $true
    $out = Get-LunarConsoleText { Invoke-LunarUninstall }
    $Force = $false
    Assert-True 'uninstall -Force moves the config file aside rather than deleting it' `
        (Test-Path -LiteralPath ($configPath + '.bak'))
    Assert-True 'uninstall -Force removes the config file' (-not (Test-Path -LiteralPath $configPath))
    Assert-True 'uninstall -Force clears the token variable' `
        ((@($script:environmentWrites | Where-Object {
            $_.Name -eq 'ECLIPSE_MCP_TOKEN' -and $null -eq $_.Value })).Count -ge 1)
    Assert-True 'the backup still holds the token, so nothing is lost' `
        ((Get-Content -LiteralPath ($configPath + '.bak') -Raw) -match 'the-last-token')

    # The p2 half, which the blocks above never reach because the resolver throws first. A
    # failed removal has to stop before the settings go: the settings have to outlive the
    # bundles, or a server that is still installed answers 401 to the person undoing it.
    # Reachable offline by replacing the resolver, the console lookup and the p2 run.
    Write-Host 'Invoke-LunarUninstall, p2 removal fails'
    function Resolve-LunarEclipseHome { return $fakeEclipseHome }
    function Get-LunarEclipseConsole { return (Join-Path $fakeEclipseHome 'eclipsec.exe') }
    function Invoke-LunarEclipseApp { return 1 }
    # site\ is what uninstall looks for, and it only exists once someone has run -InstallP2,
    # so this creates it if absent and takes it away again -- but only if it was not there.
    $siteDir = Join-Path $PSScriptRoot 'site'
    $madeSite = -not (Test-Path -LiteralPath $siteDir)
    if ($madeSite) { New-Item -ItemType Directory -Path $siteDir -Force | Out-Null }
    $fakeEclipseHome = Join-Path $sandboxProfile 'fake-eclipse'
    New-Item -ItemType Directory -Path (Join-Path $fakeEclipseHome 'dropins') -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $fakeEclipseHome 'dropins\com.github.lunar.core_0.0.1.jar') `
        -Force | Out-Null
    try {
        Write-LunarConfig -Config ([pscustomobject]@{ token = 'still-the-live-one'
            host = '127.0.0.1'; port = 8124 }) | Out-Null
        $Force = $true
        Assert-Throws 'a failed p2 removal stops uninstall' `
            { Invoke-LunarUninstall 6>&1 | Out-Null } 'failed'
        Assert-True 'a failed p2 removal leaves the settings in place' `
            (Test-Path -LiteralPath $configPath)
        Assert-True 'a failed p2 removal says the bundles are still installed' `
            ((Get-Content -LiteralPath $configPath -Raw) -match 'still-the-live-one')
    } finally {
        $Force = $false
        if ($madeSite) { Remove-Item -LiteralPath $siteDir -Recurse -Force -ErrorAction SilentlyContinue }
    }

    # The ordering this script is safe because of, which no offline test can reach: removing
    # the bundles happens after the guard, and a live Eclipse is the only thing that can
    # prove it. So this one is asserted on the source -- which call comes first in the file
    # is the whole claim.
    $uninstall = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'lunar.ps1') -Raw
    $guardAt = $uninstall.IndexOf("Assert-LunarEclipseStopped -EclipseHome `$eclipseHome -What 'removing Lunar'")
    $removeAt = $uninstall.IndexOf('Remove-Item -LiteralPath $jar.FullName')
    Assert-True 'uninstall checks for a running Eclipse before deleting anything' `
        ($guardAt -gt 0 -and $removeAt -gt $guardAt)
    Assert-True 'the guard names removing, not installing' `
        ($uninstall -match "-What 'removing Lunar'")

    # The stub above is a reimplementation, so nothing else here would notice the real
    # signature drifting back to [string] -- and a [string] $Value is what turns the $null
    # that means "delete this variable" into an empty string on its way in.
    Assert-True 'Set-LunarUserEnvironment passes a null $Value through untyped' `
        ((Get-Content -LiteralPath (Join-Path $PSScriptRoot 'lunar.ps1') -Raw) `
            -match '(?s)function Set-LunarUserEnvironment.*?param\(\[string\]\$Name, \$Value\)')
} finally {
    Remove-Item -LiteralPath $sandboxProfile -Recurse -Force -ErrorAction SilentlyContinue
    $env:USERPROFILE = $realUserProfile
}

Write-Host ''
if ($script:Failures -gt 0) {
    Write-Host ('LUNAR ENV CHECK FAIL: ' + $script:Failures + ' assertion(s) failed')
    exit 1
}
Write-Host 'LUNAR ENV CHECK PASS'
