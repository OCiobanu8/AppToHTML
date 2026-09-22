[CmdletBinding()]
param(
    # The screen whose identity is under test: its .xml (the .html beside it is read too).
    [string]$Target,

    # The screen to validate against. Defaults to $Target, i.e. "does this identity describe its
    # own capture", which is what you want while settling one by hand.
    [string]$Observed,

    # Capture what is on the device right now and validate against that, via the capture-screen
    # skill. No second device round trip is implemented here.
    [switch]$Live,

    # Validate every screen in a crawl directory against its own capture.
    [string]$Crawl,

    # The screens uniqueness is decided among. Defaults to the directory holding -Target.
    [string]$KnownScreens,

    # The target is the crawl root, whose stored identity predates any back affordance.
    [switch]$IsRootScreen,

    # Also fail when the element set differs or any element cannot be re-clicked.
    [switch]$Strict,

    [string]$DeviceSerial
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Exit codes are the contract this script exists to provide; see SKILL.md.
$EXIT = @{
    HOLDS         = 0
    DOES_NOT_HOLD = 1
    NOT_UNIQUE    = 2
    UNSETTLED     = 3
    INVALID_INPUT = 4
    USAGE         = 5
}

function Fail-Usage([string]$message) {
    # Written straight to stderr rather than through Write-Error: an operator who mistyped a path
    # needs the sentence, not a PowerShell stack trace pointing into this script.
    [Console]::Error.WriteLine($message)
    exit $EXIT.USAGE
}

function Get-RepoRoot {
    $current = (Get-Location).Path
    while ($true) {
        if (Test-Path -LiteralPath (Join-Path $current "settings.gradle.kts")) { return $current }
        $parent = Split-Path -Parent $current
        if (-not $parent -or $parent -eq $current) {
            Fail-Usage "Run this from inside the AppToHTML repository (no settings.gradle.kts found above $((Get-Location).Path))."
        }
        $current = $parent
    }
}

if (-not $Target -and -not $Crawl) {
    Fail-Usage "Pass -Target <screen.xml> to validate one screen, or -Crawl <dir> to survey a crawl."
}
if ($Target -and $Crawl) {
    Fail-Usage "-Target and -Crawl are alternatives; pass one."
}
if ($Live -and $Observed) {
    Fail-Usage "-Live captures the observed screen itself, so -Observed cannot also be given."
}
if ($Live -and $Crawl) {
    Fail-Usage "-Live validates one screen; it cannot be combined with -Crawl."
}

$repoRoot = Get-RepoRoot
$gradlew = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradlew)) {
    Fail-Usage "No gradlew.bat at $gradlew."
}

$properties = New-Object System.Collections.Generic.List[string]

if ($Crawl) {
    if (-not (Test-Path -LiteralPath $Crawl)) { Fail-Usage "No such crawl directory: $Crawl" }
    $properties.Add("-Da2h.crawl=$((Resolve-Path -LiteralPath $Crawl).Path)")
} else {
    if (-not (Test-Path -LiteralPath $Target)) { Fail-Usage "No such capture: $Target" }
    $targetPath = (Resolve-Path -LiteralPath $Target).Path
    $properties.Add("-Da2h.target=$targetPath")

    if ($Live) {
        # The live path chains the existing capture-screen skill rather than reimplementing the
        # device round trip: one way to take a capture, so a live check and a disk check compare
        # the same kind of artifact.
        $capture = Join-Path $repoRoot ".claude\skills\capture-screen\scripts\capture-screen.ps1"
        if (-not (Test-Path -LiteralPath $capture)) {
            Fail-Usage "-Live needs the capture-screen skill at $capture."
        }
        Write-Host "Capturing the current screen..." -ForegroundColor Cyan
        $captureArgs = @{}
        if ($DeviceSerial) { $captureArgs["DeviceSerial"] = $DeviceSerial }
        $result = & $capture @captureArgs
        $observedXml = $result.XmlFile
        if (-not $observedXml) {
            [Console]::Error.WriteLine("capture-screen returned no XmlFile; nothing to validate against.")
            exit $EXIT.INVALID_INPUT
        }
        Write-Host "Captured: $observedXml" -ForegroundColor Cyan
        $properties.Add("-Da2h.observed=$observedXml")
    } elseif ($Observed) {
        if (-not (Test-Path -LiteralPath $Observed)) { Fail-Usage "No such capture: $Observed" }
        $properties.Add("-Da2h.observed=$((Resolve-Path -LiteralPath $Observed).Path)")
    }

    $known = if ($KnownScreens) { $KnownScreens } else { Split-Path -Parent $targetPath }
    if (-not (Test-Path -LiteralPath $known)) { Fail-Usage "No such known-screens directory: $known" }
    $properties.Add("-Da2h.known=$((Resolve-Path -LiteralPath $known).Path)")

    if ($IsRootScreen) { $properties.Add("-Da2h.root=true") }
}

$gradleArgs = @(
    "testDebugUnitTest",
    "--tests", "*ScreenIdentityValidationEntryPoint",
    "--console=plain"
) + $properties

# Gradle's stderr is deliberately NOT redirected into the success stream. In Windows PowerShell
# 5.1, `2>&1` on a native executable wraps each stderr line in an ErrorRecord, which under
# `$ErrorActionPreference = "Stop"` is terminating: the script died here and exited 1, and 1 is
# this tool's code for DOES_NOT_HOLD. A broken build, or any JVM warning on stderr, was
# indistinguishable from "this fingerprint no longer holds" — the one confusion this tool must
# never produce.
$output = & $gradlew @gradleArgs
$gradleExit = $LASTEXITCODE
$lines = @($output) | ForEach-Object { $_.ToString() }

if ($gradleExit -ne 0 -and -not ($lines | Where-Object { $_ -match 'status=\w+' })) {
    [Console]::Error.WriteLine(
        "The validator did not run: gradle exited $gradleExit. This is a build or environment " +
        "failure, not a verdict about the screen.")
    $lines | ForEach-Object { [Console]::Error.WriteLine($_) }
    exit $EXIT.USAGE
}

# The report is printed by the entry point and arrives indented inside Gradle's test output.
# Everything Gradle says about itself is dropped, so what reaches the operator is the report and
# nothing else — the exit code carries the verdict, and the text has to be readable beside it.
$noise = '^(>|BUILD|Deprecated|Consider|Welcome|\d+ actionable|.*STANDARD_OUT\s*$|\s*$)'
$report = $lines | Where-Object { $_ -notmatch $noise }
$report | ForEach-Object { Write-Output ($_ -replace '^\s{4}', '') }

$statusLine = $lines | Where-Object { $_ -match 'status=(\w+)' } | Select-Object -Last 1
if (-not $statusLine) {
    [Console]::Error.WriteLine("No status reported. Gradle output above.")
    exit $EXIT.USAGE
}
$null = $statusLine -match 'status=(\w+)'
$status = $Matches[1]

if ($Strict -and $status -eq "HOLDS") {
    # Read the report's machine-readable facts rather than its prose: scraping headings meant a
    # wording change in the formatter would disable -Strict with nothing turning red.
    $elementSetDiffers = $report | Where-Object { $_ -match 'elementSetMatched=false' }
    $unresolved = $report | Where-Object { $_ -match 'unresolvedElements=([1-9]\d*)' }
    if ($elementSetDiffers -or $unresolved) {
        Write-Host "STRICT: the identity holds, but the element set or an element did not." -ForegroundColor Yellow
        exit $EXIT.DOES_NOT_HOLD
    }
}

if ($EXIT.ContainsKey($status)) { exit $EXIT[$status] }
exit $EXIT.USAGE
