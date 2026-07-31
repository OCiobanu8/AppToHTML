[CmdletBinding()]
param(
    # Local folder that receives <target-package>\crawl-<stamp> (or \crawl with -Mirror).
    # Not required when -List is used.
    [string]$DestinationRoot,

    # Target app whose crawl to pull, e.g. com.android.settings.
    # Omit when exactly one package has a crawl on the device.
    [string]$TargetPackage,

    # Pull every package that has a crawl directory.
    [switch]$All,

    # Only enumerate the crawls present on the device; copy nothing.
    [switch]$List,

    # Replace <DestinationRoot>\<package>\crawl in place instead of writing a
    # timestamped snapshot. The previous copy is only removed after the pull
    # succeeds.
    [switch]$Mirror,

    [string]$AppToHtmlPackage = "com.example.apptohtml",

    # Override the on-device root that holds html/<target-package>/crawl.
    [string]$RemoteHtmlRoot,

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
    param([string]$ExplicitAdbPath)

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
        [string[]]$Arguments,

        # Return whatever came back instead of throwing on a non-zero exit.
        [switch]$AllowFailure
    )

    $allArgs = @()
    if ($script:DeviceSerial) {
        $allArgs += "-s"
        $allArgs += $script:DeviceSerial
    }
    $allArgs += $Arguments

    # Send stderr to a file rather than merging with 2>&1: under Windows
    # PowerShell, merging wraps every native stderr line in a NativeCommandError
    # and buries the real output.
    $stderrPath = [System.IO.Path]::GetTempFileName()
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:ResolvedAdbPath @allArgs 2>$stderrPath
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0 -and -not $AllowFailure) {
            $stderrText = (Get-Content -LiteralPath $stderrPath -Raw -ErrorAction SilentlyContinue)
            throw "adb $($Arguments -join ' ') failed (exit $exitCode).`n$stderrText"
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Remove-Item -LiteralPath $stderrPath -Force -ErrorAction SilentlyContinue
    }

    return @($output)
}

function Get-CrawlDirectories {
    # Every remote command is a bare argv with no quotes, no $(), and no shell
    # control flow: Windows PowerShell re-quotes native arguments, which mangles
    # an embedded sh script badly enough that /system/bin/sh runs fragments of it.
    $found = Invoke-Adb -AllowFailure -Arguments @(
        "shell", "find", $script:HtmlRoot,
        "-mindepth", "2", "-maxdepth", "2", "-type", "d", "-name", "crawl"
    )

    $results = @()
    foreach ($line in $found) {
        $remotePath = "$line".Trim()
        if (-not $remotePath -or -not $remotePath.EndsWith("/crawl")) {
            continue
        }

        $entries = @(
            Invoke-Adb -AllowFailure -Arguments @("shell", "ls", "-1", $remotePath) |
                ForEach-Object { "$_".Trim() } |
                Where-Object { $_ }
        )
        $logs = @($entries | Where-Object { $_ -like "crawl_*.log" } | Sort-Object)

        $longestName = 0
        foreach ($entry in $entries) {
            if ($entry.Length -gt $longestName) {
                $longestName = $entry.Length
            }
        }

        $results += [pscustomobject]@{
            PackageName   = Split-Path -Path (Split-Path -Path $remotePath -Parent) -Leaf
            RemotePath    = $remotePath
            FileCount     = $entries.Count
            ScreenCount   = @($entries | Where-Object { $_ -like "*.html" }).Count
            LatestLog     = if ($logs.Count -gt 0) { $logs[-1] } else { "" }
            LongestName   = $longestName
        }
    }

    return @($results | Sort-Object PackageName)
}

function Copy-CrawlDirectory {
    param(
        [Parameter(Mandatory = $true)]$Crawl,
        [Parameter(Mandatory = $true)][string]$RootPath,
        [Parameter(Mandatory = $true)][string]$Stamp
    )

    $packageDir = Join-Path $RootPath $Crawl.PackageName
    if (-not (Test-Path -LiteralPath $packageDir)) {
        New-Item -ItemType Directory -Path $packageDir | Out-Null
    }

    if ($Mirror) {
        $finalDir = Join-Path $packageDir "crawl"
    } else {
        $finalDir = Join-Path $packageDir "crawl-$Stamp"
        if (Test-Path -LiteralPath $finalDir) {
            throw "Destination already exists: $finalDir"
        }
    }

    # Stage the pull so a failure or an interrupted transfer never destroys the
    # copy that is already on disk. Keep the staging name short — it sits on the
    # same path-length budget as the final directory.
    $stagingDir = Join-Path $packageDir ".pull-tmp"

    # Screen files are long ("screen_00000_..._merged_accessibility.xml") and adb
    # reports a MAX_PATH overrun as a bare "Not a directory", so check it up front.
    $deepestDir = $finalDir
    $stagedCrawlDir = Join-Path $stagingDir "crawl"
    if ($stagedCrawlDir.Length -gt $deepestDir.Length) {
        $deepestDir = $stagedCrawlDir
    }
    $longestPath = $deepestDir.Length + 1 + $Crawl.LongestName
    if ($longestPath -gt 259) {
        throw ("Destination path is too long for Windows: '$deepestDir' plus a " +
            "$($Crawl.LongestName)-character file name is $longestPath characters (max 259). " +
            "Pull to a shorter -DestinationRoot.")
    }

    if (Test-Path -LiteralPath $stagingDir) {
        Remove-Item -LiteralPath $stagingDir -Recurse -Force -Confirm:$false
    }
    New-Item -ItemType Directory -Path $stagingDir | Out-Null
    try {
        Invoke-Adb -Arguments @("pull", $Crawl.RemotePath, $stagingDir) | Out-Null

        $pulledDir = Join-Path $stagingDir "crawl"
        if (-not (Test-Path -LiteralPath $pulledDir)) {
            throw "adb pull did not produce a crawl directory for $($Crawl.PackageName)."
        }

        if ($Mirror -and (Test-Path -LiteralPath $finalDir)) {
            Remove-Item -LiteralPath $finalDir -Recurse -Force -Confirm:$false
        }
        Move-Item -LiteralPath $pulledDir -Destination $finalDir
    } finally {
        if (Test-Path -LiteralPath $stagingDir) {
            Remove-Item -LiteralPath $stagingDir -Recurse -Force -Confirm:$false
        }
    }

    return [pscustomobject]@{
        PackageName    = $Crawl.PackageName
        RemotePath     = $Crawl.RemotePath
        LocalDirectory = $finalDir
        FileCount      = @(Get-ChildItem -LiteralPath $finalDir -File).Count
        ScreenCount    = $Crawl.ScreenCount
        LatestLog      = $Crawl.LatestLog
    }
}

$script:DeviceSerial = $DeviceSerial
$script:ResolvedAdbPath = Resolve-AdbPath -ExplicitAdbPath $AdbPath
if ($RemoteHtmlRoot) {
    $script:HtmlRoot = $RemoteHtmlRoot.TrimEnd("/")
} else {
    $script:HtmlRoot = "/sdcard/Android/data/$AppToHtmlPackage/files/html"
}

$deviceState = (Invoke-Adb -Arguments @("get-state") | Select-Object -First 1).ToString().Trim()
if ($deviceState -ne "device") {
    throw "No ready Android device was detected by adb (state: $deviceState)."
}

# @() matters: PowerShell unrolls a one-element array on return, and Set-StrictMode
# then rejects .Count on the bare object.
$crawls = @(Get-CrawlDirectories)
if (-not $crawls) {
    throw "No crawl directories were found under $script:HtmlRoot on the device."
}

if ($List) {
    return $crawls
}

if (-not $DestinationRoot) {
    throw "-DestinationRoot is required unless -List is used."
}

if ($TargetPackage) {
    $selected = @($crawls | Where-Object { $_.PackageName -eq $TargetPackage })
    if (-not $selected) {
        $available = ($crawls | ForEach-Object { $_.PackageName }) -join ", "
        throw "No crawl directory for '$TargetPackage'. Available: $available"
    }
} elseif ($All) {
    $selected = $crawls
} elseif ($crawls.Count -eq 1) {
    $selected = $crawls
} else {
    $available = ($crawls | ForEach-Object { $_.PackageName }) -join ", "
    throw "Several packages have crawls; pass -TargetPackage or -All. Available: $available"
}

$destinationRootFull = [System.IO.Path]::GetFullPath($DestinationRoot)
if (-not (Test-Path -LiteralPath $destinationRootFull)) {
    New-Item -ItemType Directory -Path $destinationRootFull | Out-Null
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
foreach ($crawl in $selected) {
    Copy-CrawlDirectory -Crawl $crawl -RootPath $destinationRootFull -Stamp $stamp
}
