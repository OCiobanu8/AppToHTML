[CmdletBinding()]
param(
    # Local folder that receives <target-package>\snapshot-<stamp>.
    [string]$DestinationRoot,

    # Overrides the ScreenNaming-derived folder label, e.g. "empty-cart".
    [string]$Name,

    # Capture the visible viewport only: no rewind, no scrolling, no merge.
    [switch]$NoScroll,

    # Leave the snapshot directory on the device after pulling it.
    [switch]$KeepOnDevice,

    # How long to wait for the on-device capture to finish.
    [int]$TimeoutSeconds = 45,

    [string]$AppToHtmlPackage = "com.example.apptohtml",

    # Override the on-device root that holds html/<target-package>/snapshots.
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

function Assert-AccessibilityServiceEnabled {
    # Without this pre-check a disabled service produces a silent timeout at the
    # poll below instead of a one-line diagnosis, which is the single most common
    # way this workflow fails.
    $enabled = @(
        Invoke-Adb -AllowFailure -Arguments @(
            "shell", "settings", "get", "secure", "enabled_accessibility_services"
        )
    ) -join ""

    # The platform may store either notation depending on how the service was enabled:
    #   com.example.apptohtml/.AppToHtmlAccessibilityService              (shorthand)
    #   com.example.apptohtml/com.example.apptohtml.AppToHtmlAccessibilityService  (qualified)
    # Matching one exact string rejects the other, so require both halves independently.
    $serviceClass = "AppToHtmlAccessibilityService"
    $enabledText = "$enabled".Trim()
    $listed = ($enabledText -like "*$AppToHtmlPackage*") -and ($enabledText -like "*$serviceClass*")
    if (-not $listed) {
        throw ("The AppToHTML accessibility service is not enabled on the device. " +
            "Enable it under Settings > Accessibility > AppToHTML, then retry. " +
            "(enabled_accessibility_services = '$enabledText')")
    }

    # Listed but globally switched off is a distinct state that would otherwise present as a
    # silent timeout: the service is registered, so the string check passes, but it never runs.
    $master = (@(
        Invoke-Adb -AllowFailure -Arguments @(
            "shell", "settings", "get", "secure", "accessibility_enabled"
        )
    ) -join "").Trim()
    if ($master -ne "1") {
        throw ("The AppToHTML accessibility service is listed but accessibility is switched off " +
            "globally on the device (accessibility_enabled = '$master'). Turn it on under " +
            "Settings > Accessibility, then retry.")
    }
}

function Find-SnapshotDirectory {
    param([Parameter(Mandatory = $true)][string]$Token)

    # Globbing on the token means the script never has to know which package was
    # in the foreground when the capture ran.
    $found = Invoke-Adb -AllowFailure -Arguments @(
        "shell", "find", $script:HtmlRoot,
        "-mindepth", "3", "-maxdepth", "3", "-type", "d", "-name", "*_${Token}_*"
    )

    foreach ($line in $found) {
        $remotePath = "$line".Trim()
        if ($remotePath -and $remotePath -notlike "*No such file*") {
            return $remotePath
        }
    }

    return $null
}

function Test-RemoteFile {
    param([Parameter(Mandatory = $true)][string]$RemotePath)

    $listing = @(Invoke-Adb -AllowFailure -Arguments @("shell", "ls", $RemotePath))
    foreach ($line in $listing) {
        $text = "$line".Trim()
        if ($text -and $text -notlike "*No such file*") {
            return $true
        }
    }

    return $false
}

function Get-RemoteFileText {
    param([Parameter(Mandatory = $true)][string]$RemotePath)

    return (@(Invoke-Adb -AllowFailure -Arguments @("shell", "cat", $RemotePath)) -join "`n").Trim()
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

Assert-AccessibilityServiceEnabled

$token = ([guid]::NewGuid().ToString("N")).Substring(0, 12)

$broadcastArgs = @(
    "shell", "am", "broadcast",
    "-p", $AppToHtmlPackage,
    "-a", "com.example.apptohtml.CAPTURE_SCREEN",
    "--es", "token", $token
)
if ($Name) {
    $broadcastArgs += @("--es", "name", $Name)
}
if ($NoScroll) {
    $broadcastArgs += @("--ez", "scroll", "false")
}

Invoke-Adb -Arguments $broadcastArgs | Out-Null

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$remoteDir = $null
$terminal = $null
while ((Get-Date) -lt $deadline) {
    if (-not $remoteDir) {
        $remoteDir = Find-SnapshotDirectory -Token $token
    }

    if ($remoteDir) {
        if (Test-RemoteFile -RemotePath "$remoteDir/.done") {
            $terminal = "done"
            break
        }
        if (Test-RemoteFile -RemotePath "$remoteDir/.failed") {
            $terminal = "failed"
            break
        }
    }

    Start-Sleep -Milliseconds 400
}

if (-not $terminal) {
    throw ("Timed out after $TimeoutSeconds s waiting for the capture to finish. " +
        "No completion marker appeared for token $token under $script:HtmlRoot.")
}

if ($terminal -eq "failed") {
    $reason = Get-RemoteFileText -RemotePath "$remoteDir/.failed"
    throw "The device reported that the capture failed: $reason"
}

$packageName = Split-Path -Path (Split-Path -Path (Split-Path -Path $remoteDir -Parent) -Parent) -Leaf

if (-not $DestinationRoot) {
    return [pscustomobject]@{
        PackageName    = $packageName
        RemotePath     = $remoteDir
        Token          = $token
        LocalDirectory = $null
        Note           = "Captured on device. Pass -DestinationRoot to pull it locally."
    }
}

$destinationRootFull = [System.IO.Path]::GetFullPath($DestinationRoot)
if (-not (Test-Path -LiteralPath $destinationRootFull)) {
    New-Item -ItemType Directory -Path $destinationRootFull | Out-Null
}

$packageDir = Join-Path $destinationRootFull $packageName
if (-not (Test-Path -LiteralPath $packageDir)) {
    New-Item -ItemType Directory -Path $packageDir | Out-Null
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$finalDir = Join-Path $packageDir "snapshot-$stamp"
$suffix = 1
while (Test-Path -LiteralPath $finalDir) {
    $finalDir = Join-Path $packageDir "snapshot-$stamp-$suffix"
    $suffix += 1
}

# Screen files are long ("screen_00000_..._merged_accessibility.xml") and adb reports
# a MAX_PATH overrun as a bare "Not a directory", so check it up front.
$longestName = 0
foreach ($entry in @(Invoke-Adb -AllowFailure -Arguments @("shell", "ls", "-1", $remoteDir))) {
    $text = "$entry".Trim()
    if ($text.Length -gt $longestName) {
        $longestName = $text.Length
    }
}
$stagingDir = Join-Path $packageDir ".pull-tmp"
$deepestDir = $finalDir
$stagedDir = Join-Path $stagingDir (Split-Path -Path $remoteDir -Leaf)
if ($stagedDir.Length -gt $deepestDir.Length) {
    $deepestDir = $stagedDir
}
$longestPath = $deepestDir.Length + 1 + $longestName
if ($longestPath -gt 259) {
    throw ("Destination path is too long for Windows: '$deepestDir' plus a " +
        "$longestName-character file name is $longestPath characters (max 259). " +
        "Pull to a shorter -DestinationRoot.")
}

# Stage the pull so an interrupted transfer never leaves a half-written result
# where a complete one is expected.
if (Test-Path -LiteralPath $stagingDir) {
    Remove-Item -LiteralPath $stagingDir -Recurse -Force -Confirm:$false
}
New-Item -ItemType Directory -Path $stagingDir | Out-Null
try {
    Invoke-Adb -Arguments @("pull", $remoteDir, $stagingDir) | Out-Null

    $pulledDir = Join-Path $stagingDir (Split-Path -Path $remoteDir -Leaf)
    if (-not (Test-Path -LiteralPath $pulledDir)) {
        throw "adb pull did not produce a snapshot directory for $packageName."
    }

    Move-Item -LiteralPath $pulledDir -Destination $finalDir
} finally {
    if (Test-Path -LiteralPath $stagingDir) {
        Remove-Item -LiteralPath $stagingDir -Recurse -Force -Confirm:$false
    }
}

if (-not $KeepOnDevice) {
    Invoke-Adb -AllowFailure -Arguments @("shell", "rm", "-rf", $remoteDir) | Out-Null
}

$metadataPath = Join-Path $finalDir "snapshot.json"
$screenName = ""
$elementCount = ""
$scrollStepCount = ""
if (Test-Path -LiteralPath $metadataPath) {
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $screenName = $metadata.screenName
    $elementCount = $metadata.elementCount
    $scrollStepCount = $metadata.scrollStepCount
}

return [pscustomobject]@{
    PackageName     = $packageName
    ScreenName      = $screenName
    ElementCount    = $elementCount
    ScrollStepCount = $scrollStepCount
    Token           = $token
    LocalDirectory  = $finalDir
    HtmlFile        = @(Get-ChildItem -LiteralPath $finalDir -Filter "*.html" -File | Select-Object -First 1 -ExpandProperty FullName)
    XmlFile         = @(Get-ChildItem -LiteralPath $finalDir -Filter "*.xml" -File | Where-Object { $_.Name -notlike "*_merged_accessibility.xml" } | Select-Object -First 1 -ExpandProperty FullName)
}
