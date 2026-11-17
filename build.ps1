param(
    [switch]$Install,
    [string]$Device = 'emulator-5554',
    [int]$VersionCode = 22,
    [string]$VersionName = '2.10.0',
    [string]$AndroidSdkPath = '',
    [string]$JavaHome = '',
    [string]$BuildToolsVersion = '',
    [int]$PlatformApi = 36
)

$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$sourceDirectory = Join-Path $projectDirectory 'android'
$privateDirectory = Join-Path $projectDirectory '.private'
$outputDirectory = Join-Path $projectDirectory 'dist'
$relativeBuildDirectory = '.build/android/' + [Guid]::NewGuid().ToString('N')
$buildDirectory = Join-Path $projectDirectory $relativeBuildDirectory
$nativeExtension = if ([Environment]::OSVersion.Platform -eq 'Win32NT') { '.exe' } else { '' }
$localTools = @{}
$localToolsFile = Join-Path $privateDirectory 'build-tools.json'
if (Test-Path -LiteralPath $localToolsFile) {
    $localTools = Get-Content -LiteralPath $localToolsFile -Raw | ConvertFrom-Json -AsHashtable
}
if (-not $JavaHome) { $JavaHome = $localTools['java_home'] }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $javaCommand = Get-Command ('javac' + $nativeExtension) -ErrorAction SilentlyContinue
    if ($javaCommand) { $JavaHome = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent }
}
if (-not $JavaHome) { throw '没有找到 JDK，请设置 JAVA_HOME 或传入 -JavaHome。' }
$javaDirectory = Join-Path $JavaHome 'bin'
$java = Join-Path $javaDirectory ('java' + $nativeExtension)
$javac = Join-Path $javaDirectory ('javac' + $nativeExtension)
$jar = Join-Path $javaDirectory ('jar' + $nativeExtension)
$keytool = Join-Path $javaDirectory ('keytool' + $nativeExtension)
if (-not $AndroidSdkPath) { $AndroidSdkPath = $env:ANDROID_HOME }
if (-not $AndroidSdkPath) { $AndroidSdkPath = $env:ANDROID_SDK_ROOT }
$sdkBuildTools = ''
if ($AndroidSdkPath) {
    if (-not $BuildToolsVersion) {
        $buildToolsDirectory = Join-Path $AndroidSdkPath 'build-tools'
        $versions = @(Get-ChildItem -LiteralPath $buildToolsDirectory -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
            Sort-Object { [version]$_.Name } -Descending)
        if ($versions.Count -gt 0) { $BuildToolsVersion = $versions[0].Name }
    }
    if ($BuildToolsVersion) { $sdkBuildTools = Join-Path $AndroidSdkPath ('build-tools/' + $BuildToolsVersion) }
}
function Resolve-AndroidTool([string]$Name, [string]$SdkRelative) {
    if ($localTools[$Name]) { return [string]$localTools[$Name] }
    if ($sdkBuildTools) { return Join-Path $sdkBuildTools $SdkRelative }
    throw '没有找到 Android SDK，请设置 ANDROID_HOME，安装平台和构建工具后重试。'
}
$androidJar = $localTools['android_jar']
if (-not $androidJar -and $AndroidSdkPath) { $androidJar = Join-Path $AndroidSdkPath "platforms/android-$PlatformApi/android.jar" }
if (-not $androidJar) { throw '没有找到安卓平台，请安装 Android SDK 平台并设置 ANDROID_HOME。' }
$aapt = Resolve-AndroidTool 'aapt2' ('aapt2' + $nativeExtension)
$r8 = Resolve-AndroidTool 'r8' 'lib/d8.jar'
$zipalign = Resolve-AndroidTool 'zipalign' ('zipalign' + $nativeExtension)
$apksigner = Resolve-AndroidTool 'apksigner' 'lib/apksigner.jar'
$adb = $localTools['adb']
if (-not $adb -and $AndroidSdkPath) { $adb = Join-Path $AndroidSdkPath ('platform-tools/adb' + $nativeExtension) }
if (-not $adb) {
    $adbCommand = Get-Command ('adb' + $nativeExtension) -ErrorAction SilentlyContinue
    if ($adbCommand) { $adb = $adbCommand.Source }
}
$manifest = Join-Path $sourceDirectory 'AndroidManifest.xml'
$keystore = Join-Path $privateDirectory 'album-signing.p12'
$passwordFile = Join-Path $privateDirectory 'signing-password.txt'
$apk = Join-Path $outputDirectory 'FAM宝贝相册.apk'
$buildLog = Join-Path $projectDirectory '.build\last-build.log'

function Invoke-BuildCommand {
    param([string]$Program, [string[]]$Arguments, [string]$FailureMessage)
    $commandOutput = & $Program @Arguments 2>&1
    $commandExitCode = $LASTEXITCODE
    [string[]]$logLines = @($commandOutput | ForEach-Object { [string]$_ })
    [IO.File]::AppendAllLines($buildLog, $logLines, [Text.UTF8Encoding]::new($false))
    if ($commandExitCode -ne 0) {
        throw $FailureMessage
    }
}

Push-Location -LiteralPath $projectDirectory
try {
    foreach ($requiredPath in @($java, $javac, $jar, $keytool, $androidJar, $aapt, $r8, $zipalign, $apksigner)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw '安卓构建工具不完整，请先检查本机开发依赖。'
        }
    }
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
        throw '安卓应用源码尚未准备完成。'
    }
    [xml]$manifestDocument = [IO.File]::ReadAllText($manifest)
    if ($manifestDocument.manifest.package -ne 'cn.xiaojia.album') {
        throw '应用标识与当前项目不一致，请先检查应用清单。'
    }
    if ($VersionCode -lt 1) {
        throw '应用版本序号必须大于零。'
    }

    $generatedDirectory = Join-Path $buildDirectory 'generated'
    $classesDirectory = Join-Path $buildDirectory 'classes'
    $dexDirectory = Join-Path $buildDirectory 'dex'
    foreach ($directory in @($buildDirectory, $generatedDirectory, $classesDirectory, $dexDirectory,
                             $privateDirectory, $outputDirectory)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    [IO.File]::WriteAllText($buildLog, '', [Text.UTF8Encoding]::new($false))

    Write-Host '正在整理应用资源。'
    $resourceDirectory = Join-Path $sourceDirectory 'res'
    $resourceApk = Join-Path $buildDirectory 'resources.apk'
    $relativeResourceArchive = "$relativeBuildDirectory/resources.zip"
    $linkArguments = @('link', '-I', $androidJar, '--manifest', 'android/AndroidManifest.xml',
        '--java', "$relativeBuildDirectory/generated", '--min-sdk-version', '26', '--target-sdk-version', '35',
        '--version-code', [string]$VersionCode, '--version-name', $VersionName,
        '--auto-add-overlay', '-o', "$relativeBuildDirectory/resources.apk")
    if (Test-Path -LiteralPath $resourceDirectory -PathType Container) {
        Invoke-BuildCommand $aapt @('compile', '--dir', 'android/res', '-o', $relativeResourceArchive) '应用资源编译未通过。'
        $linkArguments += $relativeResourceArchive
    }
    $assetDirectory = Join-Path $sourceDirectory 'assets'
    if (Test-Path -LiteralPath $assetDirectory -PathType Container) {
        $linkArguments += @('-A', 'android/assets')
    }
    Invoke-BuildCommand $aapt $linkArguments '应用资源打包未通过。'

    Write-Host '正在检查家人权限、宝宝访问、存储保存、邀请、年龄定位和照片浏览。'
    $invitationTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/InvitationTextTest.java'
    $gestureTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/MediaPagerDecisionTest.java'
    $positionTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/TimeCollectionPositionTest.java'
    $storybookTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/StorybookOrderTest.java'
    $babyScopeTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/BabyScopeTest.java'
    $ageNavigationTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/AlbumAgeNavigationTest.java'
    $handlePositionTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/AlbumHandlePositionTest.java'
    $timelineProgressTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/AlbumTimelineProgressTest.java'
    $dragNavigationTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/AlbumDragNavigationTest.java'
    $memberAccessTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/MemberAccessStateTest.java'
    $storageSaveTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/StorageSaveStateTest.java'
    $childAccessTests = Join-Path $sourceDirectory 'test/cn/xiaojia/album/ChildAccessStateTest.java'
    foreach ($testSource in @($invitationTests, $gestureTests, $positionTests, $storybookTests, $babyScopeTests, $ageNavigationTests, $handlePositionTests, $timelineProgressTests, $dragNavigationTests, $memberAccessTests, $storageSaveTests, $childAccessTests)) {
        if (-not (Test-Path -LiteralPath $testSource -PathType Leaf)) {
            throw '安卓自动检查文件不完整，请恢复完整源码后重新构建。'
        }
    }
    $testClasses = Join-Path $buildDirectory 'tests'
    New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
    Invoke-BuildCommand $javac @('--release', '8', '-encoding', 'UTF-8', '-classpath', $androidJar,
        '-d', $testClasses, (Join-Path $sourceDirectory 'src/cn/xiaojia/album/InvitationText.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/ZoomImageView.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/MediaPagerView.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/TimeCollectionPosition.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/StorybookOrder.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/BabyScope.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/AlbumAgeNavigation.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/AlbumHandlePosition.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/AlbumTimelineProgress.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/AlbumDragNavigation.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/MemberAccessState.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/StorageSaveState.java'),
        (Join-Path $sourceDirectory 'src/cn/xiaojia/album/ChildAccessState.java'),
        $invitationTests, $gestureTests, $positionTests, $storybookTests, $babyScopeTests, $ageNavigationTests, $handlePositionTests, $timelineProgressTests, $dragNavigationTests, $memberAccessTests, $storageSaveTests, $childAccessTests) '安卓自动检查未能编译。'
    $testClasspath = $testClasses + [IO.Path]::PathSeparator + $androidJar
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.InvitationTextTest') '邀请内容检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.MediaPagerDecisionTest') '翻页手势检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.TimeCollectionPositionTest') '浏览位置检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.StorybookOrderTest') '故事册排序检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.BabyScopeTest') '宝宝切换与权限检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.AlbumAgeNavigationTest') '年龄定位检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.AlbumHandlePositionTest') '把手跟随与位置检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.AlbumTimelineProgressTest') '相册浏览进度检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.AlbumDragNavigationTest') '拖动时实时浏览检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.MemberAccessStateTest') '家人权限开关检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.StorageSaveStateTest') '存储保存与重试检查未通过。'
    Invoke-BuildCommand $java @('-cp', $testClasspath, 'cn.xiaojia.album.ChildAccessStateTest') '宝宝访问范围检查未通过。'

    Write-Host '正在编译安卓应用。'
    $javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $sourceDirectory 'src') -Recurse -Filter '*.java' -File)
    $javaFiles += @(Get-ChildItem -LiteralPath $generatedDirectory -Recurse -Filter '*.java' -File)
    if ($javaFiles.Count -eq 0) {
        throw '没有找到安卓应用代码。'
    }
    $argumentsFile = Join-Path $buildDirectory 'javac-files.txt'
    [string[]]$quotedPaths = $javaFiles | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
    [IO.File]::WriteAllLines($argumentsFile, $quotedPaths, [Text.UTF8Encoding]::new($false))
    Invoke-BuildCommand $javac @('--release', '8', '-encoding', 'UTF-8', '-classpath', $androidJar,
        '-d', $classesDirectory, ('@' + $argumentsFile)) '安卓应用代码编译未通过。'
    $classesJar = Join-Path $buildDirectory 'classes.jar'
    Invoke-BuildCommand $jar @('--create', '--file', $classesJar, '-C', $classesDirectory, '.') '安卓代码归档未完成。'
    Invoke-BuildCommand $java @('-cp', $r8, 'com.android.tools.r8.D8', '--release',
        '--min-api', '26', '--lib', $androidJar, '--output', $dexDirectory, $classesJar) '安卓代码转换未完成。'

    $unsignedApk = Join-Path $buildDirectory 'unsigned.apk'
    Copy-Item -LiteralPath $resourceApk -Destination $unsignedApk
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($unsignedApk, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($dexFile in Get-ChildItem -LiteralPath $dexDirectory -Filter '*.dex' -File) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $dexFile.FullName, $dexFile.Name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    }
    finally {
        $archive.Dispose()
    }

    Write-Host '正在签名并检查安装包。'
    if ((Test-Path -LiteralPath $keystore) -ne (Test-Path -LiteralPath $passwordFile)) {
        throw '应用签名资料不完整，需要恢复原有签名资料后才能继续发布更新。'
    }
    if (-not (Test-Path -LiteralPath $keystore)) {
        $randomBytes = New-Object byte[] 32
        $randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $randomGenerator.GetBytes($randomBytes) } finally { $randomGenerator.Dispose() }
        [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String($randomBytes), [Text.UTF8Encoding]::new($false))
        Invoke-BuildCommand $keytool @('-genkeypair', '-noprompt', '-keystore', $keystore,
            '-storetype', 'PKCS12', '-storepass:file', $passwordFile, '-keypass:file', $passwordFile,
            '-alias', 'xiaojia-album', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
            '-dname', 'CN=FAM Baby Album, OU=Personal Album, O=Private Family, C=CN') '应用签名创建未完成。'
    }
    $alignedApk = Join-Path $buildDirectory 'aligned.apk'
    $signedApk = Join-Path $buildDirectory 'signed.apk'
    Invoke-BuildCommand $zipalign @('-f', '-p', '4', $unsignedApk, $alignedApk) '安装包对齐检查未完成。'
    Invoke-BuildCommand $java @('-jar', $apksigner, 'sign', '--ks', $keystore,
        '--ks-key-alias', 'xiaojia-album', '--ks-pass', ('file:' + $passwordFile),
        '--out', $signedApk, $alignedApk) '安装包签名未完成。'
    Invoke-BuildCommand $java @('-jar', $apksigner, 'verify', $signedApk) '安装包签名检查未通过。'
    Invoke-BuildCommand $zipalign @('-c', '-p', '4', $signedApk) '安装包对齐检查未通过。'
    Copy-Item -LiteralPath $signedApk -Destination $apk -Force
    Write-Host '安卓安装包已生成，保存在 dist 文件夹中的“FAM宝贝相册.apk”。'

    if ($Install) {
        if (-not $adb -or -not (Test-Path -LiteralPath $adb)) {
            throw '没有找到用于安装应用的安卓连接工具。'
        }
        Invoke-BuildCommand $adb @('-s', $Device, 'install', '-r', $apk) '应用安装未完成，请检查模拟器或手机连接。'
        Write-Host 'FAM宝贝相册已安装。'
    }
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
finally {
    Pop-Location
}
