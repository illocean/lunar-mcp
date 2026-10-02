param(
    [switch]$Install,
    [switch]$NoInstall,
    # Both default to discovery; see lunar-env.ps1 for the full resolution order.
    [string]$EclipseHome,
    [string]$PoolDir
)
$ErrorActionPreference = 'Stop'
$lunarRoot = $PSScriptRoot
. (Join-Path $lunarRoot 'lunar-env.ps1')

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
            'io' { $relative -like 'com\github\lunar\io\McpHttpServer*.class' -or $relative -like 'com\github\lunar\LunarServer*.class' -or $relative -like 'com\github\lunar\EndpointFile*.class' }
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
    $artifact = Join-Path $outputDir ('com.github.lunar.' + $bundle + '_0.0.1.jar')
    & jar cfm $artifact (Join-Path $metadataDir 'META-INF\MANIFEST.MF') -C $stageDir .
    if ($LASTEXITCODE -ne 0) { throw "Lunar $bundle packaging failed" }
    $artifacts += $artifact
}
$checkPath = $classesDir + ';' + $classPath
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
$backupDir = $null
if ($Install) {
    # Eclipse rewrites dropins/ while it is running, so an install into a live
    # instance either fails or is silently discarded on the next start.
    $running = @(Get-Process eclipse,eclipsec -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($eclipseHomePath + '\', [StringComparison]::OrdinalIgnoreCase) })
    if ($running.Count) { throw 'Stop this Eclipse instance before installing Lunar bundles' }
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
$artifactDetails = @(foreach ($artifact in $artifacts) {
    @{ path = $artifact; bytes = (Get-Item -LiteralPath $artifact).Length;
       sha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash }
})
@{ classesPath = $classesDir; classPath = $classPath; artifact = $ioArtifact; artifacts = $artifacts;
    artifactDetails = $artifactDetails; checkedAt = (Get-Date -Format o);
    installed = [bool]$Install; installedBackup = $backupDir; metadataPath = (Join-Path $lunarRoot 'bundles') } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $lunarRoot 'build-state.json') -Encoding UTF8
Write-Output ('LUNAR BUILD PASS: 5 bundles at ' + $outputDir + '; installed=' + [bool]$Install)
