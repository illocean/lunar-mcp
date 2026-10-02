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

Write-Host ''
if ($script:Failures -gt 0) {
    Write-Host ('LUNAR ENV CHECK FAIL: ' + $script:Failures + ' assertion(s) failed')
    exit 1
}
Write-Host 'LUNAR ENV CHECK PASS'