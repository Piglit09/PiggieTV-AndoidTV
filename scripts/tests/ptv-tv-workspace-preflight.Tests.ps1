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

$settings = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\..\settings.gradle.kts') -Raw
if ($settings -notmatch 'ptv-tv-workspace-preflight\.ps1' -or
    $settings -notmatch 'gradle\.startParameter\.taskNames' -or
    $settings -notmatch 'gradle\.startParameter\.projectProperties') {
    throw 'Gradle settings must invoke the TV preflight with task names and source-version override.'
}
$script:checks++
Write-Output 'PASS Gradle entrypoint wiring'

Write-Output "PTV_TV_GUARD_TESTS=$script:checks"
