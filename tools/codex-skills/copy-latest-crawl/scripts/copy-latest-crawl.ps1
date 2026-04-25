[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DestinationRoot,

    [string]$TargetPackage,

    [string]$AppToHtmlPackage = "com.example.apptohtml",

    [string]$RemoteBaseDir,

    [string]$DeviceSerial,

    [string]$AdbPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-RepoLocalPropertiesSdkDir {
    $current = (Get-Location).Path
    while ($true) {
        $localProperties = Join-Path $current "local.properties"
        if (Test-Path -LiteralPath $localProperties) {
            foreach ($line in Get-Content -LiteralPath $localProperties) {
                if ($line -match '^sdk\.dir=(.+)$') {
                    return ($Matches[1] -replace '\\:', ':' -replace '\\\\', '\')
                }
            }
        }

        $parent = Split-Path -Path $current -Parent
        if (-not $parent -or $parent -eq $current) {
            break
        }
        $current = $parent
    }

    return $null
}

function Resolve-AdbPath {
    param(
        [string]$ExplicitAdbPath
    )

    if ($ExplicitAdbPath) {
        if (-not (Test-Path -LiteralPath $ExplicitAdbPath)) {
            throw "The provided adb path does not exist: $ExplicitAdbPath"
        }
        return (Resolve-Path -LiteralPath $ExplicitAdbPath).Path
    }

    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        return $adbCommand.Source
    }

    $sdkDirCandidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Get-RepoLocalPropertiesSdkDir),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { $_ }

    foreach ($sdkDir in $sdkDirCandidates) {
        $candidate = Join-Path $sdkDir "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "Could not find adb. Install Android platform-tools or provide -AdbPath."
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $allArgs = @()
    if ($script:DeviceSerial) {
        $allArgs += "-s"
        $allArgs += $script:DeviceSerial
    }
    $allArgs += $Arguments

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:ResolvedAdbPath @allArgs 2>&1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($LASTEXITCODE -ne 0) {
        $joined = ($output | Out-String).Trim()
        throw "adb command failed: $($Arguments -join ' ')`n$joined"
    }

    return @($output)
}

function Get-RemoteRoots {
    if ($RemoteBaseDir) {
        return @($RemoteBaseDir)
    }

    $base = "/sdcard/Android/data/$AppToHtmlPackage/files"
    if ($TargetPackage) {
        return @(
            "$base/html/$TargetPackage",
            "$base/crawler/html/$TargetPackage"
        )
    }

    return @(
        "$base/html",
        "$base/crawler/html"
    )
}

function Get-CrawlSessionCandidates {
    $results = @()

    foreach ($root in (Get-RemoteRoots)) {
        $remoteCommand = if ($TargetPackage) {
            "if [ -d `"$root`" ]; then find `"$root`" -mindepth 1 -maxdepth 1 -type d -name 'crawl_*' 2>/dev/null; fi"
        } else {
            "if [ -d `"$root`" ]; then find `"$root`" -mindepth 2 -maxdepth 2 -type d -name 'crawl_*' 2>/dev/null; fi"
        }

        # Pass the entire remote command as a single adb shell argument.
        # On Windows, `adb shell sh -c ...` can split the conditional across
        # argv boundaries and produce `/system/bin/sh: syntax error: unexpected 'then'`.
        $lines = Invoke-Adb -Arguments @("shell", $remoteCommand)
        foreach ($line in $lines) {
            $trimmed = "$line".Trim()
            if (-not $trimmed) {
                continue
            }

            $sessionId = Split-Path -Path $trimmed -Leaf
            $packageName = Split-Path -Path (Split-Path -Path $trimmed -Parent) -Leaf
            $match = [regex]::Match($sessionId, '^crawl_(\d{8}_\d{6})(?:_(\d+))?$')
            if (-not $match.Success) {
                continue
            }

            $timestamp = [datetime]::ParseExact(
                $match.Groups[1].Value,
                "yyyyMMdd_HHmmss",
                [System.Globalization.CultureInfo]::InvariantCulture
            )
            $suffix = if ($match.Groups[2].Success) { [int]$match.Groups[2].Value } else { 0 }

            $results += [pscustomobject]@{
                RemotePath    = $trimmed
                SessionId     = $sessionId
                TargetPackage = $packageName
                Timestamp     = $timestamp
                Suffix        = $suffix
            }
        }
    }

    return $results
}

$script:DeviceSerial = $DeviceSerial
$script:ResolvedAdbPath = Resolve-AdbPath -ExplicitAdbPath $AdbPath

$deviceState = (Invoke-Adb -Arguments @("get-state") | Select-Object -First 1).ToString().Trim()
if ($deviceState -ne "device") {
    throw "No ready Android device was detected by adb."
}

$candidates = Get-CrawlSessionCandidates
if (-not $candidates) {
    throw "No crawl session directories were found on the device."
}

$latest = $candidates |
    Sort-Object Timestamp, Suffix, SessionId |
    Select-Object -Last 1

$destinationRootFull = [System.IO.Path]::GetFullPath($DestinationRoot)
$localParent = Join-Path $destinationRootFull $latest.TargetPackage
if (-not (Test-Path -LiteralPath $localParent)) {
    New-Item -ItemType Directory -Path $localParent | Out-Null
}

$localSessionDir = Join-Path $localParent $latest.SessionId
if (Test-Path -LiteralPath $localSessionDir) {
    throw "Destination already exists: $localSessionDir"
}

Invoke-Adb -Arguments @("pull", $latest.RemotePath, $localParent) | Out-Null

[pscustomobject]@{
    SessionId          = $latest.SessionId
    TargetPackage      = $latest.TargetPackage
    RemotePath         = $latest.RemotePath
    LocalDirectory     = $localSessionDir
    DestinationRoot    = $destinationRootFull
    ResolvedAdbPath    = $script:ResolvedAdbPath
}
