# lunar-p2.ps1 - publishes lunar as a p2 update site and installs it.
#
# Dot-sourced by build.ps1 (-InstallP2) and by lunar.ps1 (uninstall). Nothing here
# executes at load time. All of the logic that does not need an Eclipse instance is
# testable offline; check-env.ps1 covers it against a synthetic site.
#
# Every step here has an equivalent the user can perform by hand, and the error
# messages name that hand step. See README "Installing from the update site".

# Reads Bundle-SymbolicName and Bundle-Version out of a bundle manifest.
#
# The manifests under bundles/ are the source of truth, not the built jars: the jars
# are named after these two headers, and the feature's <plugin version> must equal
# Bundle-Version exactly -- p2 treats a feature entry's version as an exact match, not
# a floor. Reading the manifests therefore means feature.xml cannot drift from the
# jars, and a release bump needs no edit to any script.
function Get-LunarBundleIdentity {
    param([Parameter(Mandatory = $true)][string]$ManifestDir)
    # Manifest headers wrap across lines, each continuation starting with one space.
    # Left unfolded, a bundle id can be split mid-token.
    $text = (Get-Content -LiteralPath (Join-Path $ManifestDir 'META-INF\MANIFEST.MF') -Raw) -replace "\r?\n ", ''
    if ($text -notmatch 'Bundle-SymbolicName:\s*([^\r\n;]+)') {
        throw "No Bundle-SymbolicName in $ManifestDir"
    }
    $id = $Matches[1].Trim()
    if ($text -notmatch 'Bundle-Version:\s*([^\r\n]+)') {
        throw "No Bundle-Version in $ManifestDir"
    }
    @{ id = $id; version = $Matches[1].Trim() }
}

# All five bundles, in the order the feature lists them.
function Get-LunarBundleIdentityList {
    param([Parameter(Mandatory = $true)][string]$LunarRoot)
    $identities = @(foreach ($bundle in @('core', 'workspace', 'run', 'debug', 'io')) {
        Get-LunarBundleIdentity -ManifestDir (Join-Path $LunarRoot ('bundles\' + $bundle))
    })
    if ($identities.Count -eq 0) { throw 'No lunar bundles found' }
    $identities
}

# A Windows path as the file: URI that p2 expects.
#
# The slash before the drive letter is required: 'file:/D:/path'. Handing the
# director 'file:D:/path' or a bare path makes it fail to resolve the repository.
# [uri] rather than concatenation, because a directory whose name contains '#' or '%'
# is a URI with a fragment or a percent-escape: hand-built, the publisher is pointed
# at a path that does not exist and reports nothing but 'repository not found'.
function Get-LunarP2Uri {
    param([Parameter(Mandatory = $true)][string]$Path)
    ([uri](Resolve-Path -LiteralPath $Path).Path).AbsoluteUri
}

# A feature version must not be lower than anything it contains, so the highest
# bundle version is used rather than a separate value to keep in step. All five are
# 0.0.1 today; if one were ever bumped alone this still yields a legal feature.
function Get-LunarFeatureVersion {
    param([Parameter(Mandatory = $true)]$Bundles)
    ($Bundles | Sort-Object { [version]$_.version } -Descending | Select-Object -First 1).version
}

# The IU that installs a whole feature. '.feature.group' is part of the id the
# publisher synthesises, not a version suffix, so it must not be split on the slash.
function Get-LunarFeatureGroupId {
    param([Parameter(Mandatory = $true)][string]$FeatureId)
    $FeatureId + '.feature.group'
}

function Write-LunarXml {
    param([string]$Path, [string]$Xml)
    # UTF-8 without a BOM. Set-Content -Encoding UTF8 writes one under Windows
    # PowerShell 5.1, and a BOM ahead of the <?xml?> declaration is a needless way to
    # fail in a Java parser.
    [System.IO.File]::WriteAllText($Path, $Xml, (New-Object System.Text.UTF8Encoding($false)))
}

# The publisher does not validate against a schema, so only id and version are
# required on <feature> and on each <plugin>. description/copyright/license are read
# when present and ignored when absent. No feature.properties and no build.properties:
# those only resolve %key tokens, and nothing here uses one. There is also no
# qualifier substitution available -- there is no PDE or Tycho here -- so the version
# is written literally. That is not a problem because the value is read from the
# manifests rather than typed in, but it does mean a rebuilt jar keeps the same IU id,
# and p2 will not reinstall it. Bump Bundle-Version to cut a new one.
function New-LunarFeatureXml {
    param([string]$FeatureId, $Bundles, [string]$Version, [string]$Path)
    $version = if ($Version) { $Version } else { Get-LunarFeatureVersion -Bundles $Bundles }
    $xml = '<?xml version="1.0" encoding="UTF-8"?>' + "`n" +
        '<feature' + "`n" +
        '      id="' + $FeatureId + '"' + "`n" +
        '      label="Lunar"' + "`n" +
        '      version="' + $version + '">' + "`n`n" +
        '   <description url="https://github.com/illocean/lunar-mcp">' + "`n" +
        '      MCP server for the Eclipse IDE.' + "`n" +
        '   </description>' + "`n`n" +
        '   <copyright>' + "`n" +
        '      Copyright (c) 2026 lunar contributors.' + "`n" +
        '   </copyright>' + "`n`n" +
        '   <license url="https://opensource.org/licenses/MIT">' + "`n" +
        '      MIT License. This program and the accompanying materials are made' + "`n" +
        '      available under the terms of the MIT License.' + "`n" +
        '   </license>' + "`n`n"
    foreach ($bundle in $Bundles) {
        $xml += '   <plugin id="' + $bundle.id + '" version="' + $bundle.version +
            '" download-size="0" install-size="0" unpack="false"/>' + "`n"
    }
    $xml += '</feature>' + "`n"
    Write-LunarXml -Path $Path -Xml $xml
    $version
}

# The category is what gives the site a name in Install New Software. It is optional --
# the director installs the group IU either way -- so this exists purely so the site is
# not an unnamed entry in the wizard.
#
# <feature> takes no url: the publisher ignores the attribute, and the entry resolves
# by id. <category-def> needs a name, which is what <category name=..> refers to; the
# label is only the display string.
function New-LunarCategoryXml {
    param([string]$FeatureId, [string]$Version, [string]$Path, [string]$Name = 'lunar')
    $xml = '<?xml version="1.0" encoding="UTF-8"?>' + "`n" +
        '<site>' + "`n" +
        '   <feature id="' + $FeatureId + '" version="' + $Version + '">' + "`n" +
        '        <category name="' + $Name + '"/>' + "`n" +
        '   </feature>' + "`n`n" +
        '   <category-def name="' + $Name + '" label="Lunar">' + "`n" +
        '        <description>MCP server for the Eclipse IDE.</description>' + "`n" +
        '   </category-def>' + "`n" +
        '</site>' + "`n"
    Write-LunarXml -Path $Path -Xml $xml
}

# Refuses an install that would resolve each bundle twice.
#
# A p2 install lands in the bundle pool named by eclipse.p2.data.area, while the
# dropins jars are picked up separately by the update configurator. Both satisfy the
# same Bundle-SymbolicName and version, and Eclipse then reports the bundle as already
# installed from two locations and fails to resolve it. Lunar cannot detect this from
# inside a running Eclipse, so it is refused up front.
function Assert-LunarNoDropinsCopy {
    param([Parameter(Mandatory = $true)][string]$EclipseHome)
    $dropins = Join-Path $EclipseHome 'dropins'
    if (-not (Test-Path -LiteralPath $dropins)) { return }
    # Directories too, not just jars. dropins\eclipse\plugins is the exploded form of
    # the same thing and is picked up just as eagerly, so a jar-only test would miss it
    # and let the conflicting install through.
    $copies = @(Get-ChildItem -LiteralPath $dropins -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'com.github.lunar.*' })
    if ($copies.Count -eq 0) { return }
    throw @"
Lunar is already installed as loose bundles in
    $dropins

Installing the update site on top of those would leave two copies of every bundle
resolving to the same id, and Eclipse fails to start cleanly when it sees that.

Move them out of the way first:

    Move-Item -LiteralPath '$dropins' -Destination 'backup'
    New-Item -ItemType Directory -Path '$dropins'

Then run the install again. To go back to the loose jars, uninstall the site and
use: .\build.ps1 -Install
"@
}

# eclipsec, never eclipse: the GUI launcher detaches, which costs both the exit code
# and the log, and a silent failure here would look like a successful install.
function Get-LunarEclipseConsole {
    param([Parameter(Mandatory = $true)][string]$EclipseHome)
    $console = Join-Path $EclipseHome 'eclipsec.exe'
    if (-not (Test-Path -LiteralPath $console -PathType Leaf)) {
        throw @"
$console not found.

The p2 publisher and director run inside an Eclipse installation, and lunar will not
guess which one. Point it at an installation that has the p2 applications:

    .\build.ps1 -InstallP2 -EclipseHome 'C:\path\to\eclipse'

or use the loose-jar install, which needs no p2:

    .\build.ps1 -Install
"@
    }
    $console
}

# Runs a p2 application against this Eclipse installation and returns its exit code.
#
# The Push-Location is not tidiness. Run a p2 application from a directory that is not
# an Eclipse installation and the OSGi runtime lays the product's own files down in the
# current directory -- about.html, lib/, icons/, org/, jnicrypt64.dll and thirty more.
# So a -InstallP2 run from a lunar checkout fills the checkout with someone else's
# product and buries the real changes in `git status`. Running from the installation
# that is actually being driven puts that back where it belongs.
#
# Safe because every path handed to these applications is absolute: Get-LunarP2Uri
# resolves, and the source, site and destination arguments are all joined off
# $EclipseHome or $LunarRoot. Nothing here depends on the working directory.
function Invoke-LunarEclipseApp {
    param(
        # Deliberately not $console: PowerShell variables are case-insensitive, so a
        # bare $Console here would be indistinguishable from a direct call at the call
        # sites, and check-env.ps1 greps for those.
        [Parameter(Mandatory = $true)][string]$EclipseConsole,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    Push-Location (Split-Path -Parent $EclipseConsole)
    try {
        # Out-Host, not capture: the reason for any failure goes to the console and to
        # the log, which is the entire reason this uses eclipsec rather than the
        # detaching GUI launcher. Letting that stdout fall into the return value would
        # hand the caller the log lines instead of the exit code.
        & $EclipseConsole @Arguments | Out-Host
        return $LASTEXITCODE
    } finally {
        Pop-Location
    }
}

# Publishes bundles and feature into a repository, then publishes the category into
# the same one.
#
# Order matters: a category whose IUs do not exist yet produces no IU at all, so
# running these the other way round yields a site with no entries.
function Publish-LunarSite {
    param(
        [string]$EclipseHome,
        [string]$SourceDir,
        [string]$SiteDir,
        [string]$FeatureId,
        [string]$Version
    )
    $console = Get-LunarEclipseConsole -EclipseHome $EclipseHome
    # Created here so a caller cannot hand over a path that does not exist yet and
    # get a Resolve-Path error naming a directory the publisher would have made.
    New-Item -ItemType Directory -Path $SiteDir -Force | Out-Null
    $uri = Get-LunarP2Uri -Path $SiteDir
    # The publisher reads <source>/features and <source>/plugins by default.
    $exit = Invoke-LunarEclipseApp -EclipseConsole $console -Arguments @(
        '-nosplash', '-consoleLog',
        '-application', 'org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher',
        '-source', $SourceDir, '-metadataRepository', $uri, '-artifactRepository', $uri, '-append', '-compress')
    if ($exit -ne 0) { throw 'Publishing the lunar bundles to the site failed' }

    $categoryXml = Join-Path $SiteDir 'category.xml'
    New-LunarCategoryXml -FeatureId $FeatureId -Version $Version -Path $categoryXml
    $exit = Invoke-LunarEclipseApp -EclipseConsole $console -Arguments @(
        '-nosplash', '-consoleLog',
        '-application', 'org.eclipse.equinox.p2.publisher.CategoryPublisher',
        '-metadataRepository', $uri, '-artifactRepository', $uri,
        '-categoryDefinition', (Get-LunarP2Uri -Path $categoryXml), '-categoryQualifier', 'lunar')
    if ($exit -ne 0) { throw 'Publishing the lunar category to the site failed' }
}

function Install-LunarP2 {
    param(
        [string]$EclipseHome,
        [string]$SiteDir,
        [string]$Profile,
        [string]$FeatureId,
        [switch]$VerifyOnly
    )
    $console = Get-LunarEclipseConsole -EclipseHome $EclipseHome
    Assert-LunarNoDropinsCopy -EclipseHome $EclipseHome
    $arguments = @(
        '-nosplash', '-consoleLog',
        '-application', 'org.eclipse.equinox.p2.director',
        '-repository', (Get-LunarP2Uri -Path $SiteDir),
        '-destination', $EclipseHome
    )
    if ($Profile) { $arguments += @('-profile', $Profile) }
    # -installIU is required even to verify. With no roots to install and no print
    # flag, the director prints its usage and returns EXIT_OK without ever loading
    # the repository, so a verify that omits it passes unconditionally.
    $arguments += @('-installIU', (Get-LunarFeatureGroupId -FeatureId $FeatureId))
    if ($VerifyOnly) { $arguments += '-verifyOnly' }
    Invoke-LunarEclipseApp -EclipseConsole $console -Arguments $arguments
}

# Post-condition for an install: the bundles are on disk in the pool.
#
# Exit 0 from the director does not mean lunar was installed. Rebuilding at an
# unchanged Bundle-Version yields the same IU id, p2 recognises it as already in the
# profile, does nothing, and still exits 0 -- the failure the README documents and the
# one nothing else here can see. The jars in the pool are the only direct evidence, and
# they are also the evidence that the install landed at all.
function Assert-LunarP2Installed {
    param(
        [Parameter(Mandatory = $true)][string]$DataArea,
        [Parameter(Mandatory = $true)]$Bundles
    )
    $pool = Join-Path $DataArea 'pool\plugins'
    $missing = @($Bundles | Where-Object {
        -not (Test-Path -LiteralPath (Join-Path $pool ($_.id + '_' + $_.version + '.jar')))
    })
    if ($missing.Count -eq 0) { return }
    $names = ($missing | ForEach-Object { '    ' + $_.id + '_' + $_.version + '.jar' }) -join "`n"
    throw @"
The director reported success but lunar is not in
    $pool

missing:
$names

Either the install did not land, or this build is the same version as one already in
the profile: p2 treats the same id and version as the same artifact, so it installs
nothing and still exits 0.

Bump Bundle-Version in bundles\*\META-INF\MANIFEST.MF and build again. Or use the
loose-jar install, which always overwrites: .\build.ps1 -Install
"@
}

function Uninstall-LunarP2 {
    param([string]$EclipseHome, [string]$SiteDir, [string]$Profile, [string]$FeatureId)
    $console = Get-LunarEclipseConsole -EclipseHome $EclipseHome
    $arguments = @(
        '-nosplash', '-consoleLog',
        '-application', 'org.eclipse.equinox.p2.director',
        '-repository', (Get-LunarP2Uri -Path $SiteDir),
        '-destination', $EclipseHome
    )
    if ($Profile) { $arguments += @('-profile', $Profile) }
    $arguments += @('-uninstallIU', (Get-LunarFeatureGroupId -FeatureId $FeatureId))
    Invoke-LunarEclipseApp -EclipseConsole $console -Arguments $arguments
}