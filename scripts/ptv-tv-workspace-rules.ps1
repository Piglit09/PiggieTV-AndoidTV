Set-StrictMode -Version Latest

function Normalize-PTVTvPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\', '/'))
}

function Assert-PTVTvEqualPath {
    param([string]$Actual, [string]$Expected, [string]$Label)
    $comparison = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        [StringComparison]::OrdinalIgnoreCase
    } else {
        [StringComparison]::Ordinal
    }
    if (-not (Normalize-PTVTvPath $Actual).Equals((Normalize-PTVTvPath $Expected), $comparison)) {
        throw "$Label differs from approved path."
    }
}

function Assert-PTVTvSourceIdentity {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$CommonDirectory,
        [Parameter(Mandatory = $true)][string]$Remote,
        [Parameter(Mandatory = $true)][string]$ApplicationId,
        [Parameter(Mandatory = $true)][string]$Version,
        [AllowEmptyString()][string]$OverrideVersion = ''
    )
    $approvedRoot = 'C:\Users\Piggie\Documents\GitHub\PiggieTV-AndoidTV'
    Assert-PTVTvEqualPath $Root $approvedRoot 'Source root'
    Assert-PTVTvEqualPath $CommonDirectory (Join-Path $approvedRoot '.git') 'Git common directory'
    if ($Remote -cne 'https://github.com/Piglit09/PiggieTV-AndoidTV.git') {
        throw 'Origin remote differs from approved repository.'
    }
    if ($ApplicationId -cne 'com.piggie.tv') { throw 'Application ID differs from approved package.' }
    if ($Version -cnotmatch '^\d{1,2}\.\d{1,2}\.\d{1,2}$') { throw 'Official version is invalid.' }
    if (-not [string]::IsNullOrWhiteSpace($OverrideVersion) -and $OverrideVersion -cne $Version) {
        throw 'Version override differs from gradle.properties.'
    }
}

function Get-PTVTvGradleMode {
    param([string[]]$TaskNames)
    if ($null -eq $TaskNames -or $TaskNames.Count -eq 0) { return 'Build' }
    $simple = @($TaskNames | ForEach-Object { ($_ -split ':')[-1] })
    if (@($simple | Where-Object { $_ -match '^(install|uninstall|connected|device|adb)' }).Count -gt 0) {
        return 'Install'
    }
    if (@($simple | Where-Object { $_ -match '^(publish|upload|releaseTo)' }).Count -gt 0) {
        return 'Publish'
    }
    $buildNames = '^(help|tasks|projects|properties|dependencies|dependencyInsight|clean|check|test.*|lint.*|compile.*|generate.*|process.*|merge.*|kapt.*|ksp.*|assembleDebug|assemblePremiumFixture)$'
    if (@($simple | Where-Object { $_ -notmatch $buildNames }).Count -eq 0) { return 'Build' }
    return 'Package'
}

function Assert-PTVTvOperationApproval {
    param(
        $Policy,
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$CommonDirectory,
        [Parameter(Mandatory = $true)][string]$Remote,
        [Parameter(Mandatory = $true)][string]$ApplicationId,
        [Parameter(Mandatory = $true)][string]$Head,
        [Parameter(Mandatory = $true)][string]$Tree,
        [Parameter(Mandatory = $true)][string]$Version
    )
    if ($Mode -ne 'Package') { throw 'Device installation and publication are disabled by this governance checkpoint.' }
    if ($null -eq $Policy) { throw 'Protected operator policy is absent.' }
    if ($Policy.schemaVersion -ne 1 -or $Policy.projectId -cne 'PTV_ANDROID_TV') {
        throw 'Operator policy schema or project differs.'
    }
    Assert-PTVTvEqualPath $Policy.sourceRoot $Root 'Policy source root'
    Assert-PTVTvEqualPath $Policy.gitCommonDir $CommonDirectory 'Policy Git common directory'
    Assert-PTVTvEqualPath $Policy.artifactRoot 'C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\Android-TV' 'Policy artifact root'
    if ($Policy.remote -cne $Remote -or $Policy.applicationId -cne $ApplicationId) {
        throw 'Operator policy repository or application differs.'
    }
    if ($Policy.sourceCommit -cne $Head -or $Policy.sourceTree -cne $Tree -or $Policy.version -cne $Version) {
        throw 'Operator policy does not authorize this exact source.'
    }
    if ($Head -cnotmatch '^[0-9a-f]{40}$' -or $Tree -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Source commit or tree is invalid.'
    }
    if (@($Policy.allowedModes).Count -ne 1 -or @($Policy.allowedModes)[0] -cne 'Package') {
        throw 'Build-only policy must authorize Package alone.'
    }
    if ([string]::IsNullOrWhiteSpace([string]$Policy.approvalId) -or
        [string]::IsNullOrWhiteSpace([string]$Policy.rollbackReference)) {
        throw 'Approval ID or rollback reference is absent.'
    }
    if ([DateTimeOffset]::Parse([string]$Policy.expiresUtc) -le [DateTimeOffset]::UtcNow) {
        throw 'Operator approval has expired.'
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$Policy.targetId) -or
        -not [string]::IsNullOrWhiteSpace([string]$Policy.targetClass)) {
        throw 'Build-only policy cannot authorize a device target.'
    }
}
