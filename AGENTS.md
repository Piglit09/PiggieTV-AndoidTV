# PiggieTV release versioning

- For each parent user request that materially changes tracked product files in this repository, bump the PiggieTV app version exactly once after the edits and before the final build, test, install, or deployment.
- Run `powershell -ExecutionPolicy Bypass -File .\scripts\bump-version.ps1` for that bump. Do not hand-edit or concatenate version digits.
- Do not bump for read-only work, status or clarification replies, requests that produce no tracked product change, retries or continuations of the same parent request, or prompts sent to child agents. Multi-agent work receives one bump from the parent request.
- The patch and minor fields are decimal counters from 0 through 99. After `0.0.99`, the next version is `0.1.0`.
- Never advance the major field automatically. When the current version is `x.99.99`, stop and require explicit user authorization for the next major release.
- Bump only the app release version. API, schema, contract, Android SDK, dependency, beta-channel, and server-compatibility versions follow their own change policies.

# Repository authority and build safety

- The sole writable Android TV source root is `C:\Users\Piggie\Documents\GitHub\PiggieTV-AndoidTV`. Its Git common directory is the `.git` directory in that root, and its origin is `https://github.com/Piglit09/PiggieTV-AndoidTV.git`. The historical `AndoidTV` spelling is intentional.
- Before every source-changing task, record the resolved Git root, common directory, origin, branch, full HEAD, staged/unstaged/untracked status, official `piggietv.version` from `gradle.properties`, intended changed paths, and artifact location. Stop source mutation if identity or preservation differs from the approved record.
- Keep source edits, commits, and supported local builds in this root. Historical Codex checkouts and linked worktrees are read-only. Do not create a persistent clone or worktree without operator approval and a reconciliation plan. Do not edit an installed app as source.
- Run `scripts/ptv-tv-workspace-preflight.ps1` for edit/build/package operations. Gradle invokes it from `settings.gradle.kts` for every supported task request. `Audit` may inspect a historical checkout read-only; it grants no writable authority.
- The official Android TV app version is `piggietv.version` in `gradle.properties`. Do not use a command-line or environment override to impersonate a different version. Governance-only edits do not trigger the product version bump above.
- Debug and isolated fixture builds are local development outputs. Release/Beta packaging is disabled. Any later packaging approval must require clean committed source, a protected operator policy tied to the exact commit/tree/version, and complete APK/AAB file hashes, signing identity, and transfer provenance. Device installation, publication, and production deployment remain denied.
- Keep persistent build evidence, hashes, screenshots, and reports under `C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts`. Never commit signing keys, credentials, local.properties, logs, or generated APKs. Report any artifacts outside that root.
- The accepted `0.8.12-beta.1` APK and installed Fire TV applications remain protected. The Adaptive Hero source checkpoint does not enable Hero in Beta/release variants; trailer code is excluded from this source checkpoint.
- Final task reports must name starting and ending branch/commit, changed paths, tests and build results, artifact hashes, dirty/untracked state, and whether any device, server, production installation, release channel, or Git remote was changed.
- The guard controls supported entrypoints. It cannot prevent arbitrary manual filesystem writes or direct use of third-party tools. Maintain operator-side filesystem/tool restrictions for those actions.

# Practical development workflow

- Continue routine source edits, tests, local development builds, log inspection, and focused debugging in this approved checkout when the `Edit` or `Build` preflight passes. These steps do not need a separate operator approval for each task.
- `assembleDebug` and `assemblePremiumFixture` produce local development APKs under the existing `Build` guard, including from a dirty development tree. Record the source commit, dirty state, version, build variant, and output hash. A debug APK is not an accepted Beta or release artifact.
- Keep Beta/release packaging, device installation, publication, and deployment denied by the current guard. A future registered test-device install needs device-specific authorization and a tested guard; permission to build a debug APK does not grant permission to install it.
- Make a local commit after relevant automated validation passes. State clearly when hosted or physical acceptance is still pending; a local checkpoint is not release acceptance. Pushes, protected-branch merges, releases, source deletion, new persistent source roots, and infrastructure creation need separate operator approval.
- If a protected operation is blocked, finish independent local work and report the exact operator action needed. Do not create another repository, worktree, device route, or infrastructure target to bypass the guard.
