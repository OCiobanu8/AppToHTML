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

    # A crawl directory. On its own, opens the case overview in the browser; with -Survey, prints
    # the text survey; with -Settle and -Traits, settles one case from the command line.
    [string]$Crawl,

    # With -Crawl: print the text survey (every screen validated against its own capture, then the
    # crawl's cases) instead of opening the case overview.
    [switch]$Survey,

    # With -Crawl and -Traits: the case to settle from the command line.
    [string]$Settle,

    # With -Settle: a file holding one <traits> block, unstamped.
    [string]$Traits,

    # With -Settle: check the traits and write nothing.
    [switch]$DryRun,

    # With -Settle: push the three changed files to the device, refused if the device copy changed
    # since the pull.
    [switch]$Push,

    # The adb to push with. Defaults to the one on PATH, then the Android SDK.
    [string]$AdbPath,

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
    SETTLE_FAILED = 6
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
    Fail-Usage "Pass -Target <screen.xml> to validate one screen, or -Crawl <dir> to open the case overview (add -Survey for the text survey)."
}
if (($Survey -or $Settle) -and -not $Crawl) {
    Fail-Usage "-Survey and -Settle work on a crawl; pass -Crawl <dir>."
}
if ($Survey -and $Settle) {
    Fail-Usage "-Survey and -Settle are alternatives; pass one."
}
if ($Settle -and -not $Traits) {
    Fail-Usage "-Settle needs -Traits <file>. To write traits in the browser, run -Crawl <dir> alone and pick the case."
}
if (($Traits -or $DryRun -or $Push) -and -not $Settle) {
    Fail-Usage "-Traits, -DryRun and -Push go with -Settle <case>."
}
if ($DryRun -and $Push) {
    Fail-Usage "-DryRun writes nothing, so there is nothing to -Push."
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

function Get-LocalPropertiesSdkDir([string]$root) {
    $file = Join-Path $root "local.properties"
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    $line = Get-Content -LiteralPath $file | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\:', ':' -replace '\\\\', '\'
}

# The adb the pull-crawl skill would use: -AdbPath, PATH, then the SDK locations. Null when none.
function Find-Adb([string]$root) {
    if ($AdbPath) {
        if (-not (Test-Path -LiteralPath $AdbPath)) { Fail-Usage "No adb at $AdbPath." }
        return (Resolve-Path -LiteralPath $AdbPath).Path
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $sdkDirs = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Get-LocalPropertiesSdkDir $root), (Join-Path $env:LOCALAPPDATA "Android\Sdk")) |
        Where-Object { $_ }
    foreach ($sdk in $sdkDirs) {
        $candidate = Join-Path $sdk "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    return $null
}

$repoRoot = Get-RepoRoot
$gradlew = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradlew)) {
    Fail-Usage "No gradlew.bat at $gradlew."
}

$properties = New-Object System.Collections.Generic.List[string]

$gradleArgs = @(
    "testDebugUnitTest",
    "--tests", "*ScreenIdentityValidationEntryPoint",
    "--console=plain"
)

if ($Crawl) {
    if (-not (Test-Path -LiteralPath $Crawl)) { Fail-Usage "No such crawl directory: $Crawl" }
    $crawlPath = (Resolve-Path -LiteralPath $Crawl).Path
    $properties.Add("-Da2h.crawl=$crawlPath")

    if ($Survey) {
        $properties.Add("-Da2h.survey=true")
    } elseif ($Settle) {
        if (-not (Test-Path -LiteralPath $Traits)) { Fail-Usage "No such traits file: $Traits" }
        $properties.Add("-Da2h.settle=$Settle")
        $properties.Add("-Da2h.traits=$((Resolve-Path -LiteralPath $Traits).Path)")
        if ($DryRun) { $properties.Add("-Da2h.dryRun=true") }
        if ($Push) {
            $adb = Find-Adb $repoRoot
            if (-not $adb) { Fail-Usage "-Push needs adb; none was found. Pass -AdbPath." }
            $properties.Add("-Da2h.push=true")
            $properties.Add("-Da2h.adb=$adb")
            if ($DeviceSerial) { $properties.Add("-Da2h.serial=$DeviceSerial") }
        }
    } else {
        # The case overview: the JVM entry point serves it on 127.0.0.1 for as long as the operator
        # works, so Gradle starts once and every check after the first answers at once.
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $port = $listener.LocalEndpoint.Port
        $listener.Stop()
        $token = [guid]::NewGuid().ToString("N")
        # The token travels in a one-time file the tool deletes on reading, never as a -D value: Gradle
        # records -D values as task inputs on disk.
        $tokenFile = Join-Path ([System.IO.Path]::GetTempPath()) ("a2h-token-" + [guid]::NewGuid().ToString("N"))
        [System.IO.File]::WriteAllText($tokenFile, $token)
        $properties.Add("-Da2h.port=$port")
        $properties.Add("-Da2h.tokenFile=$tokenFile")
        $adb = Find-Adb $repoRoot
        if ($adb) {
            $properties.Add("-Da2h.adb=$adb")
            if ($DeviceSerial) { $properties.Add("-Da2h.serial=$DeviceSerial") }
        } else {
            Write-Host "No adb found: the pages will settle files locally and say they still need pushing." -ForegroundColor Yellow
        }

        $log = Join-Path ([System.IO.Path]::GetTempPath()) "a2h-case-tool-$port.log"
        # Windows PowerShell 5.1 joins -ArgumentList with spaces and does not quote, so an argument
        # holding a space (a crawl under "My Documents") is quoted here.
        $argList = @($gradleArgs + $properties) | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
        $gradle = Start-Process -FilePath $gradlew -ArgumentList $argList -WorkingDirectory $repoRoot `
            -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
        # Windows PowerShell 5.1 leaves ExitCode empty for a redirected Start-Process unless the
        # handle is read before the process exits, which reported every clean Done as an error.
        $null = $gradle.Handle

        $url = "http://127.0.0.1:$port/?token=$token"
        Write-Host "Starting the case tool (the first start builds the tool; this can take a minute)..." -ForegroundColor Cyan
        $deadline = (Get-Date).AddSeconds(300)
        $ready = $false
        while ((Get-Date) -lt $deadline) {
            if ($gradle.HasExited) { break }
            try {
                $reply = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri "http://127.0.0.1:$port/health?token=$token"
                if ($reply.StatusCode -eq 200) { $ready = $true; break }
            } catch {
                Start-Sleep -Milliseconds 750
            }
        }
        Remove-Item -LiteralPath $tokenFile -Force -ErrorAction SilentlyContinue
        if (-not $ready) {
            [Console]::Error.WriteLine("The case tool did not start. Gradle output: $log")
            if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 40 | ForEach-Object { [Console]::Error.WriteLine($_) } }
            if (-not $gradle.HasExited) { Stop-Process -Id $gradle.Id -Force -ErrorAction SilentlyContinue }
            exit $EXIT.USAGE
        }
        Write-Host "Case overview: $url" -ForegroundColor Green
        Write-Host "Press Done on the page when you have finished; the tool then exits." -ForegroundColor Cyan
        Start-Process $url
        $gradle.WaitForExit()
        if ($gradle.ExitCode -ne 0) {
            [Console]::Error.WriteLine("The case tool ended with an error (Gradle exit $($gradle.ExitCode)). Output: $log")
            exit $EXIT.USAGE
        }
        Remove-Item -LiteralPath $log, "$log.err" -Force -ErrorAction SilentlyContinue
        exit 0
    }
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

$gradleArgs = $gradleArgs + $properties

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
