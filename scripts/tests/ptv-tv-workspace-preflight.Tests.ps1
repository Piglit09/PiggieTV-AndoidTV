Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$rules = Join-Path $PSScriptRoot '..\ptv-tv-workspace-rules.ps1'
if (-not (Test-Path -LiteralPath $rules)) {
    throw 'PTV TV workspace rules are missing.'
}
. $rules

$script:checks = 0
function Assert-Pass {
    param([scriptblock]$Action, [string]$Name)
    & $Action
    $script:checks++
    Write-Output "PASS $Name"
}
function Assert-Denied {
    param([scriptblock]$Action, [string]$Name)
    $denied = $false
    try { & $Action } catch { $denied = $true }
    if (-not $denied) { throw "Expected denial: $Name" }
    $script:checks++
    Write-Output "PASS $Name"
}
function With-Overrides {
    param([hashtable]$Source, [hashtable]$Overrides)
    $copy = @{}
    foreach ($key in $Source.Keys) { $copy[$key] = $Source[$key] }
    foreach ($key in $Overrides.Keys) { $copy[$key] = $Overrides[$key] }
    return $copy
}

$root = 'C:\Users\Piggie\Documents\GitHub\PiggieTV-AndoidTV'
$common = Join-Path $root '.git'
$remote = 'https://github.com/Piglit09/PiggieTV-AndoidTV.git'
$head = '4c5671a7b0dfc9a8210ac803ccc480a1c0812829'
$tree = '4993006c21421aed695059821cc43c9c32f69457'
$version = '0.8.13'
$id = @{
    Root = $root; CommonDirectory = $common; Remote = $remote
    ApplicationId = 'com.piggie.tv'; Version = $version; OverrideVersion = ''
}

Assert-Pass { Assert-PTVTvSourceIdentity @id } 'approved source identity'
Assert-Denied { $c = With-Overrides $id @{ Root = 'C:\temp\other' }; Assert-PTVTvSourceIdentity @c } 'wrong source root'
Assert-Denied { $c = With-Overrides $id @{ CommonDirectory = 'C:\temp\other\.git' }; Assert-PTVTvSourceIdentity @c } 'wrong Git common directory'
Assert-Denied { $c = With-Overrides $id @{ Remote = 'https://example.com/other.git' }; Assert-PTVTvSourceIdentity @c } 'wrong remote'
Assert-Denied { $c = With-Overrides $id @{ ApplicationId = 'com.example.other' }; Assert-PTVTvSourceIdentity @c } 'wrong app identity'
Assert-Denied { $c = With-Overrides $id @{ OverrideVersion = '0.8.99' }; Assert-PTVTvSourceIdentity @c } 'forged version override'

Assert-Pass {
    if ((Get-PTVTvGradleMode @(':app:testDebugUnitTest', 'lintDebug')) -cne 'Build') { throw 'wrong mode' }
} 'test and lint classified as local build'
Assert-Pass {
    if ((Get-PTVTvGradleMode @(':app:assemblePremiumFixture')) -cne 'Build') { throw 'wrong mode' }
} 'fixture classified as local build'
Assert-Pass {
    if ((Get-PTVTvGradleMode @(':app:assembleRelease')) -cne 'Package') { throw 'wrong mode' }
} 'release classified as package'
Assert-Pass {
    foreach ($task in @('testBetaUnitTest', 'compileBetaKotlin', 'lintRelease', 'generateBetaResources')) {
        if ((Get-PTVTvGradleMode @(":app:$task")) -cne 'Package') { throw "$task escaped protected variant policy" }
    }
} 'Beta and Release variant tasks require protected policy'
Assert-Pass {
    if ((Get-PTVTvGradleMode @(':app:installDebug')) -cne 'Install') { throw 'wrong mode' }
} 'install classified as external action'
Assert-Pass {
    if ((Get-PTVTvGradleMode @(':app:customUnknownTask')) -cne 'Package') { throw 'wrong mode' }
} 'unknown task fails closed'

$policy = [pscustomobject]@{
    schemaVersion = 1; projectId = 'PTV_ANDROID_TV'; sourceRoot = $root
    gitCommonDir = $common; remote = $remote; applicationId = 'com.piggie.tv'
    sourceCommit = $head; sourceTree = $tree; version = $version
    allowedModes = @('Package'); expiresUtc = '2099-01-01T00:00:00Z'
    approvalId = 'operator-test'; rollbackReference = 'protected-backup-test'
    artifactRoot = 'C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\Android-TV'
    targetId = $null; targetClass = $null; artifactSha256 = $null
    artifactManifestPath = $null; artifactManifestSha256 = $null
}
$policyArgs = @{
    Policy = $policy; Mode = 'Package'; Root = $root; CommonDirectory = $common
    Remote = $remote; ApplicationId = 'com.piggie.tv'; Head = $head
    Tree = $tree; Version = $version
}
Assert-Pass { Assert-PTVTvOperationApproval @policyArgs } 'exact package policy shape'
Assert-Denied { $c = With-Overrides $policyArgs @{ Policy = $null }; Assert-PTVTvOperationApproval @c } 'missing protected policy'
Assert-Denied { $c = With-Overrides $policyArgs @{ Head = ('0' * 40) }; Assert-PTVTvOperationApproval @c } 'forged source commit'
Assert-Denied { $c = With-Overrides $policyArgs @{ Tree = ('0' * 40) }; Assert-PTVTvOperationApproval @c } 'forged source tree'
Assert-Denied { $c = With-Overrides $policyArgs @{ Mode = 'Install' }; Assert-PTVTvOperationApproval @c } 'device install denied'
Assert-Denied { $c = With-Overrides $policyArgs @{ Mode = 'Publish' }; Assert-PTVTvOperationApproval @c } 'publication denied'
Assert-Denied {
    $bad = $policy.PSObject.Copy(); $bad.expiresUtc = '2000-01-01T00:00:00Z'
    $c = With-Overrides $policyArgs @{ Policy = $bad }; Assert-PTVTvOperationApproval @c
} 'expired approval denied'
Assert-Denied {
    $bad = $policy.PSObject.Copy(); $bad.rollbackReference = ''
    $c = With-Overrides $policyArgs @{ Policy = $bad }; Assert-PTVTvOperationApproval @c
} 'missing rollback reference denied'
Assert-Denied {
    $bad = $policy.PSObject.Copy(); $bad.allowedModes = @('Package', 'Install')
    $c = With-Overrides $policyArgs @{ Policy = $bad }; Assert-PTVTvOperationApproval @c
} 'package approval cannot include device permission'
Assert-Denied {
    $bad = $policy.PSObject.Copy(); $bad.targetId = 'unapproved-device'
    $c = With-Overrides $policyArgs @{ Policy = $bad }; Assert-PTVTvOperationApproval @c
} 'package policy cannot name a device'

# Shared-v2 fixtures use the current cross-project identity and version formats.
$webOsRoot = 'C:\Users\Piggie\Documents\GitHub\PiggieTV-WebOS'
$mobileRoot = 'C:\Users\Piggie\Documents\GitHub\PiggieTV-Android'
$shared = [pscustomobject]@{
    schemaVersion = 2; policyId = 'tv-shared-fixture'
    issuedUtc = '2026-01-01T00:00:00Z'; expiresUtc = '2099-01-01T00:00:00Z'
    projects = @(
        [pscustomobject]@{ projectId = 'PTV_WEB'; state = 'HOLD'; reason = 'separate review required' }
        [pscustomobject]@{
            projectId = 'PTV_ANDROID_TV'; state = 'ACTIVE'; sourceRoot = $root
            gitCommonDir = $common; remote = $remote; applicationId = 'com.piggie.tv'
            versionSource = (Join-Path $root 'gradle.properties')
            artifactRoots = @('C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\Android-TV')
            allowedModes = @('Edit', 'Build'); packageEnabled = $false; externalOperations = @()
        }
        [pscustomobject]@{
            projectId = 'PTV_WEBOS'; state = 'ACTIVE'; sourceRoot = $webOsRoot
            gitCommonDir = (Join-Path $webOsRoot '.git')
            remote = 'https://github.com/Piglit09/PiggieTV-WebOS.git'
            applicationId = 'com.piggietv.webosnative'; versionSource = 'appinfo.json'
            artifactRoots = @((Join-Path $webOsRoot 'dist'))
            allowedModes = @('Edit', 'Build'); packageEnabled = $false; externalOperations = @()
        }
        [pscustomobject]@{
            projectId = 'PTV_ANDROID_MOBILE'; state = 'ACTIVE'; sourceRoot = $mobileRoot
            gitCommonDir = (Join-Path $mobileRoot '.git')
            remote = 'https://github.com/Piglit09/PiggieTV-Andoid.git'
            applicationId = 'com.piggietv.android'; versionSource = 'gradle.properties:piggietv.version'
            artifactRoots = @('C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\Android-Mobile')
            allowedModes = @('Edit', 'Build'); packageEnabled = $false; externalOperations = @()
        }
    )
    approvals = @()
}
function Copy-SharedFixture {
    $json = $shared | ConvertTo-Json -Depth 10
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) {
        return ($json | ConvertFrom-Json -DateKind String)
    }
    return ($json | ConvertFrom-Json)
}
$sharedArgs = @{
    SharedPolicy = $shared; ChildPolicy = $null; MinimumSchemaVersion = 2
    Mode = 'Build'; Root = $root; CommonDirectory = $common; Remote = $remote
    ApplicationId = 'com.piggie.tv'
}
Assert-Pass { $null = Assert-PTVTvSharedPolicy @sharedArgs } 'exact shared-v2 TV build approval'
Assert-Pass {
    $c = With-Overrides $sharedArgs @{ SharedPolicy = (Copy-SharedFixture) }
    $null = Assert-PTVTvSharedPolicy @c
} 'JSON-decoded shared-v2 TV build approval'
Assert-Pass {
    $v2 = Copy-SharedFixture; $v2.schemaVersion = [long]2
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $v2 }
    $null = Assert-PTVTvSharedPolicy @c
} '64-bit JSON integer schema version accepted'
Assert-Denied {
    $v2 = Copy-SharedFixture; $v2.schemaVersion = '2'
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $v2 }
    $null = Assert-PTVTvSharedPolicy @c
} 'text schema version rejected'
Assert-Pass {
    $c = With-Overrides $sharedArgs @{ Mode = 'Edit' }
    $null = Assert-PTVTvSharedPolicy @c
} 'exact shared-v2 TV edit approval'
Assert-Denied {
    $c = With-Overrides $sharedArgs @{ Mode = 'Package' }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 build policy cannot authorize TV package'
Assert-Denied {
    $c = With-Overrides $sharedArgs @{ Mode = 'Install' }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 build policy cannot authorize TV device action'
Assert-Pass {
    $c = With-Overrides $sharedArgs @{
        SharedPolicy = [pscustomobject]@{ projectId = 'PTV_WEBOS' }
        MinimumSchemaVersion = $null
    }
    $null = Assert-PTVTvSharedPolicy @c
} 'legacy webOS parent does not replace TV local build authority'
Assert-Denied {
    $c = With-Overrides $sharedArgs @{
        SharedPolicy = [pscustomobject]@{ projectId = 'PTV_WEBOS' }
    }
    $null = Assert-PTVTvSharedPolicy @c
} 'protected v2 floor rejects legacy parent'
Assert-Denied {
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $null }
    $null = Assert-PTVTvSharedPolicy @c
} 'protected v2 floor rejects missing shared policy'
Assert-Denied {
    $c = With-Overrides $sharedArgs @{ MinimumSchemaVersion = 3 }
    $null = Assert-PTVTvSharedPolicy @c
} 'higher protected schema floor rejects v2'
Assert-Denied {
    $c = With-Overrides $sharedArgs @{ ChildPolicy = $policy }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 and TV child policy cannot combine'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[3].projectId = 'PTV_ANDROID_TV'
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'duplicate project identity rejected'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[3].projectId = 'PTV_UNKNOWN'
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'unknown project identity rejected'
Assert-Denied {
    $bad = Copy-SharedFixture
    $nested = Join-Path $root 'nested'
    $bad.projects[3].sourceRoot = $nested
    $bad.projects[3].gitCommonDir = (Join-Path $nested '.git')
    $bad.projects[3].versionSource = (Join-Path $nested 'gradle.properties')
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'overlapping active roots rejected'
Assert-Denied {
    $bad = Copy-SharedFixture
    $missing = 'C:\PTV-Missing-Source-Root-Fixture'
    $bad.projects[3].sourceRoot = $missing
    $bad.projects[3].gitCommonDir = (Join-Path $missing '.git')
    $bad.projects[3].versionSource = (Join-Path $missing 'gradle.properties')
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'missing active source root rejected'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[1].remote = 'https://example.invalid/other.git'
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 TV remote mismatch rejected'
Assert-Denied {
    $c = With-Overrides $sharedArgs @{ Root = $mobileRoot }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 TV root mismatch rejected'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[1].applicationId = 'fixture.wrong.app'
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 TV app mismatch rejected'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[1].versionSource = (Join-Path $root 'other.properties')
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 TV version source mismatch rejected'
Assert-Denied {
    $bad = Copy-SharedFixture
    $bad.projects[1].artifactRoots = @('C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\Other')
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 TV artifact root mismatch rejected'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[2].versionSource = ''
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'other active project still needs a version source'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[2].artifactRoots = @('dist')
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'other active project still needs an absolute artifact root'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[1].allowedModes = @('Build', 'Package')
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 package mode cannot be smuggled into TV record'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[1].packageEnabled = $true
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 TV package switch remains off'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.projects[1].externalOperations = @('Install')
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'shared-v2 external operation remains off'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.approvals = @([pscustomobject]@{ approvalId = 'unreviewed' })
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'unreviewed shared-v2 approval record rejected'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad.expiresUtc = '2000-01-01T00:00:00Z'
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'expired shared-v2 policy rejected'
Assert-Denied {
    $bad = Copy-SharedFixture; $bad | Add-Member -NotePropertyName unexpected -NotePropertyValue 'x'
    $c = With-Overrides $sharedArgs @{ SharedPolicy = $bad }
    $null = Assert-PTVTvSharedPolicy @c
} 'unknown shared-v2 field rejected'

$settings = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\..\settings.gradle.kts') -Raw
if ($settings -notmatch 'ptv-tv-workspace-preflight\.ps1' -or
    $settings -notmatch 'gradle\.startParameter\.taskNames' -or
    $settings -notmatch 'gradle\.startParameter\.projectProperties') {
    throw 'Gradle settings must invoke the TV preflight with task names and source-version override.'
}
$script:checks++
Write-Output 'PASS Gradle entrypoint wiring'

Write-Output "PTV_TV_GUARD_TESTS=$script:checks"
