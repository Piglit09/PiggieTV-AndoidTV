pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

// Every supported Gradle invocation passes the source and requested tasks through
// the approved checkout guard before loading project code or running any task.
// Configuration-cache hits skip this settings script, so they cannot use this guard.
if (gradle.startParameter.isConfigurationCacheRequested) {
    throw GradleException("PTV Android TV configuration cache is disabled because it skips the workspace preflight.")
}
val ptvPreflight = file("scripts/ptv-tv-workspace-preflight.ps1")
if (!ptvPreflight.isFile) {
    throw GradleException("PTV Android TV workspace preflight is missing.")
}
val ptvPowerShell = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
val ptvCheck = providers.exec {
    workingDir(settingsDir)
    isIgnoreExitValue = true
    commandLine(
        ptvPowerShell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-File", ptvPreflight.absolutePath, "-Mode", "Gradle",
        "-GradleTasks", gradle.startParameter.taskNames.joinToString("|"),
        "-VersionOverride", gradle.startParameter.projectProperties["piggietv.version"].orEmpty(),
    )
}
val ptvCheckResult = ptvCheck.result.get()
val ptvCheckOutput = ptvCheck.standardOutput.asText.get().trim()
if (ptvCheckResult.exitValue != 0) {
    throw GradleException("PTV Android TV workspace preflight denied: ${ptvCheck.standardError.asText.get().trim()}")
}
println(ptvCheckOutput)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PiggieTVAndroidTV"
include(":app")
