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
    if (@($simple | Where-Object { $_ -cmatch '(Beta|Release)' }).Count -gt 0) {
        return 'Package'
    }
    $buildNames = '^(help|tasks|projects|properties|dependencies|dependencyInsight|clean|check|test.*|lint.*|compile.*|generate.*|process.*|merge.*|kapt.*|ksp.*|assembleDebug|assemblePremiumFixture)$'
    if (@($simple | Where-Object { $_ -notmatch $buildNames }).Count -eq 0) { return 'Build' }
    return 'Package'
}

function Assert-PTVTvPolicyFields {
    param($Value, [string[]]$Names, [string]$Label)
    if ($null -eq $Value -or $Value -isnot [pscustomobject]) { throw "$Label must be an object." }
    $actual = @($Value.PSObject.Properties.Name)
    if ($actual.Count -ne $Names.Count) { throw "$Label has missing or unsupported fields." }
    foreach ($name in $actual) {
        if ($Names -cnotcontains $name) { throw "$Label has an unsupported field: $name" }
    }
}

function Assert-PTVTvPolicyString {
    param($Value, [string]$Label)
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value) -or
        $Value -cne $Value.Trim()) {
        throw "$Label must be a nonempty string without surrounding whitespace."
    }
}

function Normalize-PTVTvPolicyPath {
    param($Path, [string]$Label)
    Assert-PTVTvPolicyString $Path $Label
    if ($Path -cnotmatch '^[A-Za-z]:\\') { throw "$Label must be a fully qualified drive path." }
    $normalized = Normalize-PTVTvPath $Path
    if (-not $Path.TrimEnd([char[]]@('\', '/')).Equals($normalized,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label uses a noncanonical path."
    }
    return $normalized
}

function Assert-PTVTvPolicyExistingPath {
    param([string]$Path, [string]$Kind, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType $Kind)) {
        throw "$Label is absent."
    }
    $entry = Get-Item -LiteralPath $Path -Force
    while ($null -ne $entry) {
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Label uses a junction or symlink."
        }
        $entry = [IO.Directory]::GetParent($entry.FullName)
    }
}

function Assert-PTVTvPolicyTime {
    param($Value, [string]$Label)
    Assert-PTVTvPolicyString $Value $Label
    if ($Value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$') {
        throw "$Label must be a UTC timestamp."
    }
    try { return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture) }
    catch { throw "$Label is invalid." }
}

function Assert-PTVTvSharedPolicy {
    param(
        $SharedPolicy,
        $ChildPolicy,
        $MinimumSchemaVersion,
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$CommonDirectory,
        [Parameter(Mandatory = $true)][string]$Remote,
        [Parameter(Mandatory = $true)][string]$ApplicationId
    )
    if ($null -ne $MinimumSchemaVersion -and
        ($MinimumSchemaVersion -isnot [int] -or $MinimumSchemaVersion -lt 0)) {
        throw 'Protected minimum schema version is invalid.'
    }
    $sharedIsV2 = $false
    if ($null -ne $SharedPolicy) {
        if ($SharedPolicy -isnot [pscustomobject]) { throw 'Shared policy must be an object.' }
        $schema = $SharedPolicy.PSObject.Properties['schemaVersion']
        if ($null -ne $schema) {
            if (($schema.Value -isnot [int] -and $schema.Value -isnot [long]) -or
                $schema.Value -ne 2) {
                throw 'Shared policy schema is unsupported.'
            }
            $sharedIsV2 = $true
        } elseif ($SharedPolicy.PSObject.Properties['projectId'] -eq $null -or
            $SharedPolicy.projectId -cne 'PTV_WEBOS') {
            throw 'Unversioned shared policy is not Android TV authority.'
        }
    }
    if ($null -ne $MinimumSchemaVersion -and $MinimumSchemaVersion -ge 2 -and
        ($MinimumSchemaVersion -gt 2 -or -not $sharedIsV2)) {
        throw 'Protected minimum schema requires a supported shared policy.'
    }
    if (-not $sharedIsV2) { return }
    if ($null -ne $ChildPolicy) { throw 'TV child and shared policy authorities are ambiguous.' }

    Assert-PTVTvPolicyFields $SharedPolicy @('schemaVersion', 'policyId', 'issuedUtc',
        'expiresUtc', 'projects', 'approvals') 'Shared policy'
    Assert-PTVTvPolicyString $SharedPolicy.policyId 'Shared policy ID'
    $issued = Assert-PTVTvPolicyTime $SharedPolicy.issuedUtc 'Shared policy issue time'
    $expires = Assert-PTVTvPolicyTime $SharedPolicy.expiresUtc 'Shared policy expiry'
    $now = [DateTimeOffset]::UtcNow
    if ($issued -gt $now -or $expires -le $now -or $expires -le $issued) {
        throw 'Shared policy is unissued, expired, or has an invalid time range.'
    }
    # Privileged approval records need a separate reviewed contract and implementation.
    if ($SharedPolicy.approvals -isnot [array] -or $SharedPolicy.approvals.Count -ne 0) {
        throw 'Shared policy approvals are disabled in this build-only adapter.'
    }
    if ($SharedPolicy.projects -isnot [array] -or $SharedPolicy.projects.Count -ne 4) {
        throw 'Shared policy must contain exactly four project records.'
    }

    $allowedIds = @('PTV_WEB', 'PTV_ANDROID_TV', 'PTV_WEBOS', 'PTV_ANDROID_MOBILE')
    $seenIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $activeRoots = [Collections.Generic.List[string]]::new()
    $tvRecord = $null
    foreach ($project in $SharedPolicy.projects) {
        if ($null -eq $project -or $project -isnot [pscustomobject]) {
            throw 'Shared project record must be an object.'
        }
        Assert-PTVTvPolicyString $project.projectId 'Shared project ID'
        if ($allowedIds -cnotcontains $project.projectId -or -not $seenIds.Add($project.projectId)) {
            throw 'Shared policy has an unknown or duplicate project ID.'
        }
        if ($project.state -ceq 'HOLD') {
            Assert-PTVTvPolicyFields $project @('projectId', 'state', 'reason') 'HOLD project'
            Assert-PTVTvPolicyString $project.reason 'HOLD reason'
            if ($project.projectId -ceq 'PTV_ANDROID_TV') {
                throw 'Android TV is on hold in shared policy.'
            }
            continue
        }
        if ($project.state -cne 'ACTIVE' -or $project.projectId -ceq 'PTV_WEB') {
            throw 'Shared project state is unsupported.'
        }
        Assert-PTVTvPolicyFields $project @('projectId', 'state', 'sourceRoot',
            'gitCommonDir', 'remote', 'applicationId', 'versionSource', 'artifactRoots',
            'allowedModes', 'packageEnabled', 'externalOperations') 'ACTIVE project'
        $projectRoot = Normalize-PTVTvPolicyPath $project.sourceRoot 'Shared source root'
        $projectCommon = Normalize-PTVTvPolicyPath $project.gitCommonDir 'Shared Git common directory'
        Assert-PTVTvPolicyString $project.versionSource 'Shared version source'
        $projectVersionSource = $null
        if ($project.projectId -ceq 'PTV_ANDROID_TV') {
            $projectVersionSource = Normalize-PTVTvPolicyPath $project.versionSource 'Shared TV version source'
        }
        if (-not $projectCommon.Equals((Join-Path $projectRoot '.git'),
            [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Shared Git common directory must be the project .git directory.'
        }
        if ($null -ne $projectVersionSource -and
            -not $projectVersionSource.StartsWith($projectRoot + '\',
            [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Shared version source is outside its project.'
        }
        Assert-PTVTvPolicyString $project.remote 'Shared remote'
        Assert-PTVTvPolicyString $project.applicationId 'Shared application ID'
        if ($project.artifactRoots -isnot [array] -or $project.artifactRoots.Count -eq 0) {
            throw 'Shared artifact roots must be a nonempty array.'
        }
        $artifactSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($artifactRoot in $project.artifactRoots) {
            $artifact = Normalize-PTVTvPolicyPath $artifactRoot 'Shared artifact root'
            if (-not $artifactSet.Add($artifact)) {
                throw 'Shared artifact root is duplicated.'
            }
        }
        if ($project.allowedModes -isnot [array]) { throw 'Shared allowed modes must be an array.' }
        $modeSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($allowedMode in $project.allowedModes) {
            if ($allowedMode -isnot [string] -or $allowedMode -cnotin @('Edit', 'Build') -or
                -not $modeSet.Add($allowedMode)) {
                throw 'Shared policy has an unsupported or duplicate mode.'
            }
        }
        if ($project.packageEnabled -isnot [bool] -or $project.packageEnabled -or
            $project.externalOperations -isnot [array] -or $project.externalOperations.Count -ne 0) {
            throw 'Shared privileged operations are disabled in this build-only adapter.'
        }
        foreach ($priorRoot in $activeRoots) {
            if ($projectRoot.Equals($priorRoot, [StringComparison]::OrdinalIgnoreCase) -or
                $projectRoot.StartsWith($priorRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
                $priorRoot.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Shared active source roots overlap.'
            }
        }
        Assert-PTVTvPolicyExistingPath $projectRoot 'Container' 'Shared source root'
        Assert-PTVTvPolicyExistingPath $projectCommon 'Container' 'Shared Git common directory'
        if ($null -ne $projectVersionSource) {
            Assert-PTVTvPolicyExistingPath $projectVersionSource 'Leaf' 'Shared TV version source'
        }
        $activeRoots.Add($projectRoot)
        if ($project.projectId -ceq 'PTV_ANDROID_TV') { $tvRecord = $project }
    }
    foreach ($requiredId in $allowedIds) {
        if (-not $seenIds.Contains($requiredId)) { throw 'Shared project identity is missing.' }
    }
    if ($null -eq $tvRecord) { throw 'Shared Android TV record is absent.' }
    Assert-PTVTvEqualPath $tvRecord.sourceRoot $Root 'Shared TV source root'
    Assert-PTVTvEqualPath $tvRecord.gitCommonDir $CommonDirectory 'Shared TV Git common directory'
    Assert-PTVTvEqualPath $tvRecord.versionSource (Join-Path $Root 'gradle.properties') 'Shared TV version source'
    if ($tvRecord.remote -cne $Remote -or $tvRecord.applicationId -cne $ApplicationId) {
        throw 'Shared Android TV repository or application differs.'
    }
    if ($tvRecord.artifactRoots.Count -ne 1) {
        throw 'Shared Android TV artifact root is ambiguous.'
    }
    Assert-PTVTvEqualPath $tvRecord.artifactRoots[0] `
        'C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\Android-TV' 'Shared TV artifact root'
    if ($Mode -cnotin @('Edit', 'Build') -or $tvRecord.allowedModes -cnotcontains $Mode) {
        throw 'Shared Android TV operation is not authorized.'
    }
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
