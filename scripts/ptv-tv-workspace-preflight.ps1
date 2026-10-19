[CmdletBinding()]
param(
    [ValidateSet('Audit', 'Edit', 'Build', 'Package', 'Install', 'Publish', 'Gradle')]
    [string]$Mode = 'Audit',
    [string]$VersionOverride = '',
    [string]$AuditPath = '',
    [string]$GradleTasks = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ptv-tv-workspace-rules.ps1')

$approvedRoot = 'C:\Users\Piggie\Documents\GitHub\PiggieTV-AndoidTV'
$policySubKey = 'SOFTWARE\PiggieTV\WorkspaceAuthority\AndroidTV'

function Assert-NoReparseAncestor {
    param([string]$Path)
    $entry = Get-Item -LiteralPath $Path -Force
    while ($null -ne $entry) {
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Path uses a junction or symlink: $($entry.FullName)"
        }
        $entry = $entry.Parent
    }
}

function Invoke-GitRead {
    param([string]$Root, [string[]]$Arguments)
    $gitExe = 'C:\Program Files\Git\cmd\git.exe'
    if (-not (Test-Path -LiteralPath $gitExe -PathType Leaf)) { throw 'Trusted Git executable is missing.' }
    $lines = & $gitExe --no-optional-locks -C $Root @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Git identity check failed: $($Arguments -join ' ')" }
    return ($lines -join [Environment]::NewLine).Trim()
}

function Assert-ProtectedRegistryAcl {
    param([Microsoft.Win32.RegistryKey]$Key, [string]$Path)
    $acl = $Key.GetAccessControl()
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier])
    if ($owner -eq $identity.User -or $principal.IsInRole($owner)) {
        throw "Operator policy path can be repermissioned by this process: $Path"
    }
    $writeRights = [Security.AccessControl.RegistryRights]::SetValue -bor
        [Security.AccessControl.RegistryRights]::CreateSubKey -bor
        [Security.AccessControl.RegistryRights]::CreateLink -bor
        [Security.AccessControl.RegistryRights]::Delete -bor
        [Security.AccessControl.RegistryRights]::ChangePermissions -bor
        [Security.AccessControl.RegistryRights]::TakeOwnership
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) { continue }
        $sid = [Security.Principal.SecurityIdentifier]$rule.IdentityReference
        if (($rule.RegistryRights -band $writeRights) -ne 0 -and
            ($sid -eq $identity.User -or $principal.IsInRole($sid))) {
            throw "Operator policy path is writable by this process: $Path"
        }
    }
}

function Read-ProtectedPolicySnapshot {
    $baseKey = [Microsoft.Win32.RegistryKey]::OpenBaseKey(
        [Microsoft.Win32.RegistryHive]::LocalMachine,
        [Microsoft.Win32.RegistryView]::Registry64
    )
    try {
        Assert-ProtectedRegistryAcl -Key $baseKey -Path 'HKLM'
        $snapshot = [pscustomobject]@{
            SharedPolicy = $null
            ChildPolicy = $null
            MinimumSchemaVersion = $null
        }
        foreach ($path in @('SOFTWARE', 'SOFTWARE\PiggieTV',
            'SOFTWARE\PiggieTV\WorkspaceAuthority', $policySubKey)) {
            $key = $baseKey.OpenSubKey($path, $false)
            if ($null -eq $key) { break }
            try {
                Assert-ProtectedRegistryAcl -Key $key -Path "HKLM\$path"
                if ($path -ceq 'SOFTWARE\PiggieTV\WorkspaceAuthority') {
                    if (@($key.GetValueNames()) -contains 'MinimumSchemaVersion') {
                        if ($key.GetValueKind('MinimumSchemaVersion') -ne
                            [Microsoft.Win32.RegistryValueKind]::DWord) {
                            throw 'Protected minimum schema marker must be a DWORD.'
                        }
                        $snapshot.MinimumSchemaVersion = $key.GetValue('MinimumSchemaVersion', $null)
                    }
                    if (@($key.GetValueNames()) -contains 'PolicyJson') {
                        if ($key.GetValueKind('PolicyJson') -ne
                            [Microsoft.Win32.RegistryValueKind]::String) {
                            throw 'Shared operator policy must be a REG_SZ value.'
                        }
                        $raw = $key.GetValue('PolicyJson', $null)
                        if ([string]::IsNullOrWhiteSpace([string]$raw)) {
                            throw 'Shared operator policy is empty.'
                        }
                        $snapshot.SharedPolicy = $raw | ConvertFrom-Json
                    }
                }
                if ($path -ceq $policySubKey -and @($key.GetValueNames()) -contains 'PolicyJson') {
                    if ($key.GetValueKind('PolicyJson') -ne
                        [Microsoft.Win32.RegistryValueKind]::String) {
                        throw 'TV child operator policy must be a REG_SZ value.'
                    }
                    $raw = $key.GetValue('PolicyJson', $null)
                    if ([string]::IsNullOrWhiteSpace([string]$raw)) {
                        throw 'TV child operator policy is empty.'
                    }
                    $snapshot.ChildPolicy = $raw | ConvertFrom-Json
                }
            } finally {
                $key.Dispose()
            }
        }
        return $snapshot
    } finally {
        $baseKey.Dispose()
    }
}

try {
    foreach ($variable in @('GIT_DIR', 'GIT_WORK_TREE', 'GIT_COMMON_DIR', 'GIT_INDEX_FILE',
        'GIT_CONFIG_COUNT', 'GIT_CONFIG_PARAMETERS', 'GIT_CONFIG_GLOBAL', 'GIT_CONFIG_SYSTEM')) {
        if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($variable))) {
            throw "Git environment override is not approved: $variable"
        }
    }
    if ($Mode -eq 'Gradle') {
        $taskNames = if ([string]::IsNullOrWhiteSpace($GradleTasks)) { @() } else { @($GradleTasks -split '\|') }
        $Mode = Get-PTVTvGradleMode -TaskNames $taskNames
    } elseif ($GradleTasks) {
        throw 'GradleTasks is valid only for the Gradle entrypoint.'
    }
    $repoRoot = Normalize-PTVTvPath (Join-Path $PSScriptRoot '..')
    $scanRoot = if ($Mode -eq 'Audit' -and -not [string]::IsNullOrWhiteSpace($AuditPath)) {
        Normalize-PTVTvPath $AuditPath
    } else {
        $repoRoot
    }
    if (-not (Test-Path -LiteralPath $scanRoot -PathType Container)) { throw 'Source path is missing.' }
    Assert-NoReparseAncestor $scanRoot
    $actualRoot = Normalize-PTVTvPath (Invoke-GitRead -Root $scanRoot -Arguments @('rev-parse', '--show-toplevel'))
    $commonReported = Invoke-GitRead -Root $scanRoot -Arguments @('rev-parse', '--path-format=absolute', '--git-common-dir')
    $commonDirectory = Normalize-PTVTvPath $commonReported
    $remote = Invoke-GitRead -Root $scanRoot -Arguments @('config', '--local', '--get', 'remote.origin.url')
    $head = Invoke-GitRead -Root $scanRoot -Arguments @('rev-parse', 'HEAD')
    $tree = Invoke-GitRead -Root $scanRoot -Arguments @('rev-parse', 'HEAD^{tree}')
    $branch = Invoke-GitRead -Root $scanRoot -Arguments @('branch', '--show-current')
    $statusText = Invoke-GitRead -Root $scanRoot -Arguments @('status', '--porcelain=v1', '--untracked-files=all')
    $dirtyPaths = @($statusText -split '\r?\n' | Where-Object { $_.Length -gt 0 }).Count

    if ($Mode -eq 'Audit') {
        Write-Output "PTV_TV_AUDIT=READ_ONLY ROOT=$actualRoot COMMON=$commonDirectory REMOTE=$remote BRANCH=$branch HEAD=$head DIRTY_PATHS=$dirtyPaths"
        exit 0
    }
    if ($AuditPath) { throw 'AuditPath is permitted only for read-only Audit mode.' }
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'Local writable authority is Windows-only; non-Windows CI needs a separately approved exact-ref policy.'
    }
    Assert-NoReparseAncestor $repoRoot
    $cwd = Normalize-PTVTvPath (Get-Location).Path
    Assert-NoReparseAncestor $cwd
    $pathComparison = [StringComparison]::OrdinalIgnoreCase
    if (-not ($cwd.Equals($repoRoot, $pathComparison) -or
        $cwd.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, $pathComparison))) {
        throw 'Working directory is outside the approved source root.'
    }
    if (-not $actualRoot.Equals($repoRoot, $pathComparison)) { throw 'Script source is not the Git root.' }
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot '.git') -PathType Container)) {
        throw 'Linked worktree or invalid Git administrative directory is not approved.'
    }
    Assert-NoReparseAncestor (Join-Path $repoRoot '.git')
    if ([string]::IsNullOrWhiteSpace($branch)) { throw 'Detached local source is not approved.' }
    if ($head -cnotmatch '^[0-9a-f]{40}$' -or $tree -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Git source commit or tree is invalid.'
    }
    $versionLines = @(Get-Content -LiteralPath (Join-Path $repoRoot 'gradle.properties') |
        Where-Object { $_ -match '^piggietv\.version=(.+)$' })
    if ($versionLines.Count -ne 1) { throw 'Official gradle.properties version is missing or ambiguous.' }
    $version = ($versionLines[0] -split '=', 2)[1].Trim()
    $appBuild = Get-Content -LiteralPath (Join-Path $repoRoot 'app/build.gradle.kts') -Raw
    if ($appBuild -cnotmatch 'applicationId\s*=\s*"com\.piggie\.tv"') {
        throw 'Application package identity differs from approved Android TV app.'
    }
    Assert-PTVTvSourceIdentity -Root $actualRoot -CommonDirectory $commonDirectory -Remote $remote `
        -ApplicationId 'com.piggie.tv' -Version $version -OverrideVersion $VersionOverride
    if (-not [string]::IsNullOrWhiteSpace($env:PIGGIETV_VERSION) -and $env:PIGGIETV_VERSION -cne $version) {
        throw 'PIGGIETV_VERSION differs from gradle.properties.'
    }

    if ($Mode -in @('Install', 'Publish')) {
        throw 'Device installation and publication require a separate implementation and exact operator approval.'
    }
    if ($Mode -eq 'Package' -and $dirtyPaths -ne 0) {
        throw 'Release packaging requires clean committed source.'
    }
    $policySnapshot = Read-ProtectedPolicySnapshot
    $sharedArgs = @{
        SharedPolicy = $policySnapshot.SharedPolicy
        ChildPolicy = $policySnapshot.ChildPolicy
        MinimumSchemaVersion = $policySnapshot.MinimumSchemaVersion
        Mode = $Mode
        Root = $actualRoot
        CommonDirectory = $commonDirectory
        Remote = $remote
        ApplicationId = 'com.piggie.tv'
    }
    Assert-PTVTvSharedPolicy @sharedArgs
    if ($Mode -eq 'Package') {
        $policy = $policySnapshot.ChildPolicy
        Assert-PTVTvOperationApproval -Policy $policy -Mode $Mode -Root $actualRoot `
            -CommonDirectory $commonDirectory -Remote $remote -ApplicationId 'com.piggie.tv' `
            -Head $head -Tree $tree -Version $version
        throw 'Release packaging remains disabled until complete artifact, signer, and transfer provenance is implemented and approved.'
    }
    Write-Output "PTV_TV_PREFLIGHT=PASS MODE=$Mode HEAD=$head TREE=$tree BRANCH=$branch VERSION=$version DIRTY_PATHS=$dirtyPaths"
    exit 0
} catch {
    [Console]::Error.WriteLine("PTV TV preflight denied: $($_.Exception.Message)")
    exit 1
}
