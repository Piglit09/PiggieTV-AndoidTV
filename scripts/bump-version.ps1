[CmdletBinding()]
param(
    [string] $PropertiesPath = (Join-Path $PSScriptRoot "..\gradle.properties")
)

$approvedRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$approvedPropertiesPath = [System.IO.Path]::GetFullPath((Join-Path $approvedRoot 'gradle.properties'))
$path = [System.IO.Path]::GetFullPath($PropertiesPath)
if (-not $path.Equals($approvedPropertiesPath, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Version bump may edit only the approved root gradle.properties.'
}
if (((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Version properties file is a junction or symlink.'
}
$preflight = Join-Path $PSScriptRoot 'ptv-tv-workspace-preflight.ps1'
& $preflight -Mode Edit
if ($LASTEXITCODE -ne 0) { throw 'Android TV source Edit preflight denied version bump.' }

$propertyName = "piggietv.version"
$pattern = "^\s*$([regex]::Escape($propertyName))\s*=\s*v?(?<major>\d{1,2})\.(?<minor>\d{1,2})\.(?<patch>\d{1,2})\s*$"

if (-not (Test-Path -LiteralPath $path)) {
    throw "Version properties file was not found: $path"
}

$lines = @(Get-Content -LiteralPath $path)
$versionLineIndex = -1
$currentVersion = $null

for ($index = 0; $index -lt $lines.Count; $index++) {
    $match = [regex]::Match($lines[$index], $pattern)
    if ($match.Success) {
        $versionLineIndex = $index
        $currentVersion = @{
            Major = [int] $match.Groups["major"].Value
            Minor = [int] $match.Groups["minor"].Value
            Patch = [int] $match.Groups["patch"].Value
        }
        break
    }
}

if ($versionLineIndex -eq -1) {
    throw "Required version property '$propertyName' was not found in $path"
}

$major = $currentVersion.Major
$minor = $currentVersion.Minor
$patch = $currentVersion.Patch

if ($patch -lt 99) {
    $patch += 1
} elseif ($minor -lt 99) {
    $minor += 1
    $patch = 0
} else {
    throw "Automatic major-version rollover is disabled at $major.99.99. Set the next major release explicitly."
}

$nextVersion = "$major.$minor.$patch"
$lines[$versionLineIndex] = "$propertyName=$nextVersion"
Set-Content -LiteralPath $path -Value $lines -Encoding utf8
Write-Output $nextVersion
