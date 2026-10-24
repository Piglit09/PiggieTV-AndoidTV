Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$fixture = Join-Path ([IO.Path]::GetTempPath()) ("ptv-tv-version-guard-" + [guid]::NewGuid().ToString('N') + '.properties')
Set-Content -LiteralPath $fixture -Value 'piggietv.version=0.8.13' -Encoding Ascii
$before = (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash
try {
    $script = Join-Path $PSScriptRoot '..\bump-version.ps1'
    $denied = $false
    try {
        $null = & 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $script -PropertiesPath $fixture 2>&1
        $denied = $LASTEXITCODE -ne 0
    } catch {
        $denied = $true
    }
    if (-not $denied) { throw 'Version bump accepted an arbitrary properties file.' }
    $after = (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash
    if ($after -cne $before) { throw 'Denied version bump modified the fixture.' }
    Write-Output 'PASS version bump rejects an external file without modifying it'
} finally {
    Remove-Item -LiteralPath $fixture -Force
}
