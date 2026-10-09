param(
    [Parameter(Mandatory = $true)]
    [string]$PackageDir,

    [Parameter(Mandatory = $true)]
    [string]$RepoPath,

    [switch]$DryRun,

    [string]$PythonExe,

    [string]$GitExe
)

$ErrorActionPreference = "Stop"

$PackageDir = (Resolve-Path -LiteralPath $PackageDir).Path
$RepoPath = (Resolve-Path -LiteralPath $RepoPath).Path
$Runner = Join-Path $RepoPath "tools\phase_package_runner.py"

if (-not (Test-Path -LiteralPath $Runner -PathType Leaf)) {
    throw "Phase package runner not installed: $Runner"
}

$pythonArgs = @($Runner, "--package", $PackageDir, "--repo", $RepoPath)

if ($DryRun) {
    $pythonArgs += "--dry-run"
}

if ($PythonExe) {
    $PythonExe = (Resolve-Path -LiteralPath $PythonExe).Path
    if (-not (Test-Path -LiteralPath $PythonExe -PathType Leaf)) {
        throw "Python executable not found"
    }
}

if ($GitExe) {
    $GitExe = (Resolve-Path -LiteralPath $GitExe).Path
    if (-not (Test-Path -LiteralPath $GitExe -PathType Leaf)) {
        throw "Git executable not found"
    }
}

$OriginalPath = $env:PATH

try {
    if ($GitExe) {
        $GitDirectory = Split-Path -Parent $GitExe
        $env:PATH = $GitDirectory + ";" + $OriginalPath
    }

    if ($PythonExe) {
        & $PythonExe @pythonArgs
    }
    elseif (Get-Command py -CommandType Application -ErrorAction SilentlyContinue) {
        & py -3 @pythonArgs
    }
    elseif (Get-Command python -CommandType Application -ErrorAction SilentlyContinue) {
        & python @pythonArgs
    }
    else {
        throw "Python 3 was not found. Specify -PythonExe."
    }

    $ResultCode = $LASTEXITCODE
}
finally {
    $env:PATH = $OriginalPath
}

exit $ResultCode
