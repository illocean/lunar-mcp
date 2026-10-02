param(
    [switch]$Install,
    [switch]$NoInstall,
    # Install the built bundles as a p2 update site instead of loose jars in dropins.
    [switch]$InstallP2,
    # Both default to discovery; see lunar-env.ps1 for the full resolution order.
    [string]$EclipseHome,
    [string]$PoolDir
)
$ErrorActionPreference = 'Stop'
$lunarRoot = $PSScriptRoot
. (Join-Path $lunarRoot 'lunar-env.ps1')
. (Join-Path $lunarRoot 'lunar-p2.ps1')

# Compile-time classpath. Only the bundles lunar actually Require-Bundle carry a
# minimum version, and those match the versions declared in the manifests -- a
# manifest that names a floor the build does not honour is a broken install, and
# check-env.ps1 asserts the two agree. The remaining entries are compile-only
# dependencies (OSGi annotations, Equinox internals) that no manifest names, so
# they carry no floor: a minimum there would be an undocumented hard failure for
# anyone on an older release train, buying nothing, because the build uses these
# only as a classpath and never reads their version.
$compileBundles = @(
    'org.eclipse.osgi@3.24.0',
    'org.eclipse.core.runtime@3.34.0',
    'org.eclipse.core.resources@3.23.0',
    'org.eclipse.jdt.core@3.44.0',
    'org.eclipse.core.filebuffers@3.8.0',
    'org.eclipse.text@3.14.0',
    'org.eclipse.equinox.app@1.7.0',
    'org.eclipse.jdt.launching@3.24.0',
    'org.eclipse.debug.core@3.23.0',
    'org.eclipse.jdt.junit.core@3.14.0',
    'org.eclipse.debug.ui@3.19.0',
    'org.eclipse.swt@3.132.0',
    'org.eclipse.jdt.debug@3.25.0',
    'org.eclipse.ui.console@3.15.0',
    'org.eclipse.ui.workbench@3.137.0',
    'org.eclipse.equinox.common',
    'org.eclipse.core.jobs',
    'org.eclipse.equinox.registry',
    'org.eclipse.osgi.services',
    'org.apache.felix.scr',
    'org.osgi.service.component',
    'org.eclipse.jdt.core.compiler.batch',
    'org.eclipse.jdt.junit',
    'org.eclipse.jface.text',
    'org.eclipse.jface',
    'org.eclipse.ui',
    'org.eclipse.swt.win32.win32.x86_64',
    'org.eclipse.core.expressions',
    'org.eclipse.equinox.preferences',
    'org.osgi.service.prefs'
)
$eclipseHomePath = Resolve-LunarEclipseHome -Explicit $EclipseHome
$poolDir = Resolve-LunarPoolDir -EclipseHome $eclipseHomePath -Explicit $PoolDir
$compileJars = Resolve-LunarClasspath -PoolDir $poolDir -Requirements $compileBundles
$classPath = $compileJars -join ';'
# Keep previous artifacts for rollback; builds never recursively delete a directory.
$outputDir = Join-Path $lunarRoot ('out\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$classesDir = Join-Path $outputDir 'classes'
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $lunarRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
if ($sources.Count -eq 0) { throw 'No Lunar sources' }
& javac --release 21 -encoding UTF-8 -cp $classPath -d $classesDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Lunar compilation failed' }
$sourceDir = Join-Path $lunarRoot 'src'
$artifacts = @()
$classFiles = @(Get-ChildItem -LiteralPath $classesDir -Filter '*.class' -Recurse)
# The four check classes are run by this build and are deliberately not shipped, so the
# coverage gate below has to be told about them. Named once, here, and the same list
# decides what gets staged into the directory the checks are run from.
$buildOnlyClasses = @('SelfCheck', 'FrameworkCheck', 'IntegrationCheck', 'ProtocolCheck')
foreach ($bundle in @('core', 'workspace', 'run', 'debug', 'io')) {
    $stageDir = Join-Path $outputDir ('bundles\' + $bundle)
    New-Item -ItemType Directory -Path $stageDir -Force | Out-Null
    foreach ($classFile in $classFiles) {
        $relative = $classFile.FullName.Substring($classesDir.Length + 1)
        $include = switch ($bundle) {
            'core' { $relative -like 'com\github\lunar\tools\*' -or $relative -like 'com\github\lunar\io\Json*.class' }
            'workspace' { $relative -like 'com\github\lunar\workspace\*' -or $relative -like 'com\github\lunar\verify\*' }
            'run' { $relative -like 'com\github\lunar\run\*' }
            'debug' { $relative -like 'com\github\lunar\debug\*' }
            # LunarConfig is here for the same reason LunarServer is: it is package-private
            # in com.github.lunar and LunarServer reads it, so the two have to be in the
            # same package in the same bundle. Core exports com.github.lunar.tools and
            # com.github.lunar.io but not com.github.lunar, so moving it there would mean
            # a split package that nothing exports.
            'io' { $relative -like 'com\github\lunar\io\McpHttpServer*.class' -or $relative -like 'com\github\lunar\LunarServer*.class' -or $relative -like 'com\github\lunar\EndpointFile*.class' -or $relative -like 'com\github\lunar\LunarConfig*.class' }
        }
        if ($include) {
            $destination = Join-Path $stageDir $relative
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            Copy-Item -LiteralPath $classFile.FullName -Destination $destination
        }
    }
    $metadataDir = Join-Path $lunarRoot ('bundles\' + $bundle)
    Copy-Item -LiteralPath (Join-Path $metadataDir 'plugin.xml') -Destination $stageDir
    if ($bundle -eq 'io') {
        Copy-Item -LiteralPath (Join-Path $sourceDir 'OSGI-INF') -Destination $stageDir -Recurse
    }
    # Named after the manifest's own headers, not a literal here. The p2 feature has
    # to list each <plugin> at exactly Bundle-Version, so a name typed in this file is
    # one more thing a release bump has to remember to change.
    $identity = Get-LunarBundleIdentity -ManifestDir $metadataDir
    $artifact = Join-Path $outputDir ($identity.id + '_' + $identity.version + '.jar')
    & jar cfm $artifact (Join-Path $metadataDir 'META-INF\MANIFEST.MF') -C $stageDir .
    if ($LASTEXITCODE -ne 0) { throw "Lunar $bundle packaging failed" }
    $artifacts += $artifact
}
# The gate that replaced silent dropping. Run against the jars that were just produced,
# so what is checked is the artifact that ships and not the exploded directory it came
# from. See Assert-LunarBundleCoverage for why these four are excluded.
$coverage = Assert-LunarBundleCoverage -ClassesDir $classesDir -Jars $artifacts `
    -BuildOnlyClasses $buildOnlyClasses
Write-Output ("Lunar class coverage: " + $coverage)
# The checks run against the packaged jars, not $classesDir. Running them against the
# exploded directory is what let 133 checks pass over a build whose shipped artifact
# could not load LunarConfig: every class resolved out of classes\, which still had the
# class the jars were missing. Jars first, in dependency order (core first, io last).
#
# The exploded directory cannot be on this classpath at all -- it would satisfy anything
# the jars are missing, which is the defect being guarded against. The check classes
# themselves are not in any jar, so they get their own directory holding only them.
$checksDir = Join-Path $outputDir 'checks'
New-Item -ItemType Directory -Path $checksDir -Force | Out-Null
foreach ($classFile in @(Get-ChildItem -LiteralPath $classesDir -Filter '*.class' -Recurse)) {
    $relative = $classFile.FullName.Substring($classesDir.Length + 1)
    $name = ($relative -split '\\')[-1] -replace '\$.*\.class$', '' -replace '\.class$', ''
    if ($name -notin $buildOnlyClasses) { continue }
    $destination = Join-Path $checksDir $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $classFile.FullName -Destination $destination
}
$checkPath = ((@($checksDir) + $artifacts) -join ';') + ';' + $classPath
$ioArtifact = $artifacts | Where-Object { $_ -like '*com.github.lunar.io_*.jar' }
& java -ea -cp $checkPath com.github.lunar.SelfCheck $ioArtifact
if ($LASTEXITCODE -ne 0) { throw 'Lunar lifecycle check failed' }
& java -ea -cp $checkPath com.github.lunar.io.ProtocolCheck
if ($LASTEXITCODE -ne 0) { throw 'Lunar protocol check failed' }
& java -ea -cp $checkPath com.github.lunar.FrameworkCheck
if ($LASTEXITCODE -ne 0) { throw 'Lunar framework check failed' }
& java -ea -cp $checkPath com.github.lunar.IntegrationCheck @artifacts
if ($LASTEXITCODE -ne 0) { throw 'Lunar domain integration check failed' }
if ($Install -and $NoInstall) { throw 'Choose -Install or -NoInstall, not both' }
if ($Install -and $InstallP2) {
    throw @'
Choose one way to install. The loose jars in dropins and the update site both
resolve the same bundles, and having both installed breaks Eclipse's resolution.

    -Install    loose jars in <eclipse home>\dropins
    -InstallP2  the update site in .\site, managed by p2
'@
}
$backupDir = $null
$p2SiteDir = $null
if ($Install -or $InstallP2) {
    Assert-LunarEclipseStopped -EclipseHome $eclipseHomePath
}
if ($InstallP2) {
    # Also checked inside Install-LunarP2, where the invariant belongs so every
    # caller gets it. Checked here too so the user finds out before compiling
    # rather than after the site has already been published.
    Assert-LunarNoDropinsCopy -EclipseHome $eclipseHomePath
}
if ($Install) {
    $dropinsDir = Join-Path $eclipseHomePath 'dropins'
    if (-not (Test-Path -LiteralPath $dropinsDir)) {
        New-Item -ItemType Directory -Path $dropinsDir -Force | Out-Null
    }
    $backupDir = Join-Path $lunarRoot ('backup\installed-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    foreach ($old in @(Get-ChildItem -LiteralPath $dropinsDir -Filter 'com.github.lunar.*_*.jar')) {
        Copy-Item -LiteralPath $old.FullName -Destination $backupDir
    }
    foreach ($artifact in $artifacts) { Copy-Item -LiteralPath $artifact -Destination $dropinsDir -Force }
}
if ($InstallP2) {
    $p2SiteDir = Join-Path $lunarRoot 'site'
    # The publisher reads <source>/features and <source>/plugins; anything else in
    # the directory is ignored, so this is staged rather than pointed at the build
    # output to keep the two layouts from colliding.
    $p2SourceDir = Join-Path $outputDir 'p2repo'
    foreach ($sub in @('features\com.github.lunar.feature', 'plugins')) {
        New-Item -ItemType Directory -Path (Join-Path $p2SourceDir $sub) -Force | Out-Null
    }
    foreach ($artifact in $artifacts) {
        Copy-Item -LiteralPath $artifact -Destination (Join-Path $p2SourceDir 'plugins')
    }
    $bundles = Get-LunarBundleIdentityList -LunarRoot $lunarRoot
    $featureVersion = New-LunarFeatureXml -Bundles $bundles `
        -FeatureId 'com.github.lunar.feature' -Path (Join-Path $p2SourceDir 'features\com.github.lunar.feature\feature.xml')
    Publish-LunarSite -EclipseHome $eclipseHomePath -SourceDir $p2SourceDir -SiteDir $p2SiteDir `
        -FeatureId 'com.github.lunar.feature' -Version $featureVersion
    $profile = Get-LunarProfileName -EclipseHome $eclipseHomePath
    # Verify first. It costs a second and proves the site resolves and the plan is
    # satisfiable before the profile is touched at all.
    $verify = Install-LunarP2 -EclipseHome $eclipseHomePath -SiteDir $p2SiteDir -Profile $profile `
        -FeatureId 'com.github.lunar.feature' -VerifyOnly
    if ($verify -ne 0) { throw 'The update site did not resolve; nothing was installed.' }
    if ((Install-LunarP2 -EclipseHome $eclipseHomePath -SiteDir $p2SiteDir -Profile $profile `
            -FeatureId 'com.github.lunar.feature') -ne 0) {
        throw 'The p2 director could not install lunar.'
    }
    Assert-LunarP2Installed -DataArea (Resolve-LunarP2DataArea -EclipseHome $eclipseHomePath) -Bundles $bundles
}
$artifactDetails = @(foreach ($artifact in $artifacts) {
    @{ path = $artifact; bytes = (Get-Item -LiteralPath $artifact).Length;
       sha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash }
})
@{ classesPath = $classesDir; classPath = $classPath; artifact = $ioArtifact; artifacts = $artifacts;
    artifactDetails = $artifactDetails; checkedAt = (Get-Date -Format o);
    installed = [bool]$Install; installedBackup = $backupDir; installedP2 = [bool]$InstallP2;
    p2Site = $p2SiteDir; metadataPath = (Join-Path $lunarRoot 'bundles') } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $lunarRoot 'build-state.json') -Encoding UTF8
Write-Output ('LUNAR BUILD PASS: 5 bundles at ' + $outputDir + '; installed=' + [bool]$Install +
    '; installedP2=' + [bool]$InstallP2)
