param([switch]$Check, [string]$JavaHome = '', [string]$FormatterPath = '')

$ErrorActionPreference = 'Stop'
$nativeExtension = if ([Environment]::OSVersion.Platform -eq 'Win32NT') { '.exe' } else { '' }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) { $java = Join-Path $JavaHome ('bin/java' + $nativeExtension) }
else {
    $javaCommand = Get-Command ('java' + $nativeExtension) -ErrorAction SilentlyContinue
    if (-not $javaCommand) { throw '没有找到 Java，请设置 JAVA_HOME 后重试。' }
    $java = $javaCommand.Source
}
$formatter = if ($FormatterPath) { $FormatterPath } else {
    Join-Path $PSScriptRoot '.tools/formatter/google-java-format-1.37.0-all-deps.jar'
}
$source = Join-Path $PSScriptRoot 'android\src'

try {
    if (-not (Test-Path -LiteralPath $formatter)) {
        throw '本机 Java 排版工具缺失，需要先恢复开发工具目录。'
    }
    $sourceFiles = @(Get-ChildItem -LiteralPath $source -Filter '*.java' -File -Recurse)
    if ($sourceFiles.Count -eq 0) {
        throw '没有找到需要排版的 Java 代码。'
    }
    $formatArguments = @('-jar', $formatter, '--aosp')
    if ($Check) {
        $formatArguments += @('--dry-run', '--set-exit-if-changed')
    }
    else {
        $formatArguments += '--replace'
    }
    $formatArguments += @($sourceFiles | ForEach-Object { $_.FullName })
    $null = & $java @formatArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        if ($Check) { throw 'Java 代码排版需要更新，请运行排版脚本后重新构建。' }
        throw 'Java 代码排版未完成，请先检查代码是否可以正常编译。'
    }
    Write-Host ('Java 代码排版' + $(if ($Check) { '检查通过。' } else { '已完成。' }))
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
