Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
Push-Location -LiteralPath $root
try {
    foreach ($attempt in 1..2) {
        $output = & (Join-Path $root 'gradlew.bat') help --offline --console=plain 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw ("Gradle help failed on attempt {0}: {1}" -f $attempt, ($output -join [Environment]::NewLine))
        }
        if (($output -join [Environment]::NewLine) -notmatch 'PTV_TV_PREFLIGHT=PASS MODE=Build') {
            throw "Gradle help skipped the TV preflight on attempt $attempt."
        }
    }
    Write-Output 'PASS every Gradle help invocation runs TV preflight'

    $output = & (Join-Path $root 'gradlew.bat') help --offline --console=plain --configuration-cache 2>&1
    if ($LASTEXITCODE -eq 0) {
        throw 'Explicit configuration cache request bypassed TV preflight enforcement.'
    }
    if (($output -join [Environment]::NewLine) -notmatch 'configuration cache') {
        throw 'Explicit configuration cache request failed for an unrelated reason.'
    }
    Write-Output 'PASS explicit configuration cache request denied'
} finally {
    Pop-Location
}
