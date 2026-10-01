param([switch]$Install, [switch]$NoInstall)
$ErrorActionPreference = 'Stop'
$lunarRoot = $PSScriptRoot
$poolDir = 'D:\EclipseIDE\.p2\pool\plugins'
$jarNames = @(
    'org.eclipse.osgi_3.24.0.v20251126-0427.jar',
    'org.eclipse.core.runtime_3.34.100.v20251111-1421.jar',
    'org.eclipse.equinox.common_3.20.300.v20251111-0312.jar',
    'org.eclipse.core.jobs_3.15.700.v20250725-1147.jar',
    'org.eclipse.core.resources_3.23.100.v20251106-1705.jar',
    'org.eclipse.equinox.registry_3.12.600.v20250906-0651.jar',
    'org.eclipse.osgi.services_3.11.200.v20231106-0901.jar',
    'org.apache.felix.scr_2.2.18.jar',
    'org.osgi.service.component_1.5.1.202212101352.jar',
    'org.eclipse.jdt.core_3.44.0.v20251118-1842.jar',
    'org.eclipse.jdt.core.compiler.batch_3.44.0.v20251118-1623.jar',
    'org.eclipse.debug.core_3.23.200.v20251107-0507.jar',
    'org.eclipse.debug.ui_3.19.100.v20251114-0802.jar',
    'org.eclipse.jdt.launching_3.24.0.v20251031-2243.jar',
    'org.eclipse.jdt.debug_3.25.0.v20251031-2243\jdimodel.jar',
    'org.eclipse.jdt.junit_3.17.300.v20251107-1918.jar',
    'org.eclipse.jdt.junit.core_3.14.0.v20251201-1407.jar',
    'org.eclipse.core.filebuffers_3.8.500.v20251103-0746.jar',
    'org.eclipse.text_3.14.500.v20251103-0730.jar',
    'org.eclipse.jface.text_3.29.0.v20251112-0859.jar',
    'org.eclipse.jface_3.38.100.v20251108-1551.jar',
    'org.eclipse.ui_3.207.400.v20251015-1301.jar',
    'org.eclipse.ui.workbench_3.137.0.v20251114-0005.jar',
    'org.eclipse.ui.console_3.15.0.v20251113-1013.jar',
    'org.eclipse.swt_3.132.0.v20251124-0642.jar',
    'org.eclipse.swt.win32.win32.x86_64_3.132.0.v20251124-0642.jar',
    'org.eclipse.equinox.app_1.7.500.v20250629-0337.jar',
    'org.eclipse.core.expressions_3.9.500.v20250608-0434.jar',
    'org.eclipse.equinox.preferences_3.12.100.v20251111-0704.jar',
    'org.osgi.service.prefs_1.1.2.202109301733.jar'
)
$compileJars = foreach ($jarName in $jarNames) {
    $jarFile = Join-Path $poolDir $jarName
    if (-not (Test-Path -LiteralPath $jarFile)) { throw "Missing pinned jar: $jarName" }
    $jarFile
}
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
    $running = @(Get-Process eclipse,eclipsec -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith('D:\EclipseIDE\eclipse\', [StringComparison]::OrdinalIgnoreCase) })
    if ($running.Count) { throw 'Stop this Eclipse instance before installing Lunar bundles' }
    $dropinsDir = 'D:\EclipseIDE\eclipse\dropins'
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
