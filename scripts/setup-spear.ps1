<#
  One-time, idempotent build of the vendored spear-cli for THIS checkout (Windows).
  Safe to re-run. PowerShell companion to setup-spear.sh.
#>
$ErrorActionPreference = "Stop"
$ExpectedVersion = "0.2.0"                            # match tools/spear-cli/VENDORED.md
$Root = (git rev-parse --show-toplevel).Trim()        # current checkout (main repo OR worktree)
$SpearDir = Join-Path $Root "tools\spear-cli"
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Write-Error "need Node >= 20 on PATH"; exit 1 }
Push-Location $SpearDir
try {
    if (Test-Path package-lock.json) { npm ci } else { npm install }   # reproducible from the lockfile
    npm run build
    $Actual = (node dist/cli.js --version).Trim()
    if ($Actual -ne $ExpectedVersion) { Write-Error "built version '$Actual' != '$ExpectedVersion'"; exit 1 }
    Write-Output "OK spear ready (v$Actual) at tools/spear-cli/dist/cli.js"
} finally {
    Pop-Location
}
