package com.piggie.tv.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.view.WindowInsets
import android.view.WindowManager
import com.piggie.tv.ui.layout.TvLayoutProfileResolver

data class PtvDeviceSnapshot(
    val capturedAtMs: Long,
    val appVersion: String,
    val versionCode: Long,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val widthPixels: Int,
    val heightPixels: Int,
    val availableWindowWidth: Int,
    val availableWindowHeight: Int,
    val insetLeft: Int,
    val insetTop: Int,
    val insetRight: Int,
    val insetBottom: Int,
    val density: Float,
    val densityDpi: Int,
    val scaledDensity: Float,
    val xdpi: Float,
    val ydpi: Float,
    val logicalWidthDp: Int,
    val logicalHeightDp: Int,
    val smallestScreenWidthDp: Int,
    val fontScale: Float,
    val layoutProfile: String,
    val displayMode: String,
    val refreshRateHz: Float,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val availableMemoryBytes: Long,
    val lowMemory: Boolean,
    val availableStorageBytes: Long,
    val networkTransport: String,
    val batteryPercent: Int?,
    val batteryTemperatureC: Float?,
    val thermalStatus: Int?,
    val videoDecoders: List<String>,
    val audioCapabilities: String,
    val serverReachability: String = "unknown"
) {
    companion object {
        @Suppress("DEPRECATION")
        fun capture(context: Context): PtvDeviceSnapshot {
            val metrics = context.resources.displayMetrics
            val configuration = context.resources.configuration
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            var windowWidth = metrics.widthPixels
            var windowHeight = metrics.heightPixels
            var insetLeft = 0
            var insetTop = 0
            var insetRight = 0
            var insetBottom = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val windowMetrics = windowManager.currentWindowMetrics
                    val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    intArrayOf(
                        windowMetrics.bounds.width() - insets.left - insets.right,
                        windowMetrics.bounds.height() - insets.top - insets.bottom,
                        insets.left,
                        insets.top,
                        insets.right,
                        insets.bottom
                    )
                }.getOrNull()?.let { values ->
                    windowWidth = values[0]
                    windowHeight = values[1]
                    insetLeft = values[2]
                    insetTop = values[3]
                    insetRight = values[4]
                    insetBottom = values[5]
                }
            }
            val display = windowManager.defaultDisplay
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) display.mode else null
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val storage = StatFs(Environment.getDataDirectory().absolutePath)
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
            val batteryScale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
            val batteryPercent = if (batteryLevel != null && batteryScale != null) batteryLevel * 100 / batteryScale else null
            val batteryTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.div(10f)
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val profileAndViewport = TvLayoutProfileResolver.from(context)

            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            }

            return PtvDeviceSnapshot(
                capturedAtMs = System.currentTimeMillis(),
                appVersion = packageInfo.versionName ?: "unknown",
                versionCode = versionCode,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                widthPixels = metrics.widthPixels,
                heightPixels = metrics.heightPixels,
                availableWindowWidth = windowWidth,
                availableWindowHeight = windowHeight,
                insetLeft = insetLeft,
                insetTop = insetTop,
                insetRight = insetRight,
                insetBottom = insetBottom,
                density = metrics.density,
                densityDpi = metrics.densityDpi,
                scaledDensity = metrics.scaledDensity,
                xdpi = metrics.xdpi,
                ydpi = metrics.ydpi,
                logicalWidthDp = profileAndViewport.second.widthDp,
                logicalHeightDp = profileAndViewport.second.heightDp,
                smallestScreenWidthDp = configuration.smallestScreenWidthDp,
                fontScale = configuration.fontScale,
                layoutProfile = profileAndViewport.first.name,
                displayMode = mode?.let { "${it.physicalWidth}x${it.physicalHeight} modeId=${it.modeId}" }
                    ?: "${metrics.widthPixels}x${metrics.heightPixels}",
                refreshRateHz = mode?.refreshRate ?: display.refreshRate,
                memoryClassMb = activityManager.memoryClass,
                largeMemoryClassMb = activityManager.largeMemoryClass,
                availableMemoryBytes = memoryInfo.availMem,
                lowMemory = memoryInfo.lowMemory,
                availableStorageBytes = storage.availableBytes,
                networkTransport = networkTransport(capabilities),
                batteryPercent = batteryPercent,
                batteryTemperatureC = batteryTemp,
                thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else null,
                videoDecoders = videoDecoders(),
                audioCapabilities = audioCapabilities(context)
            )
        }

        private fun networkTransport(capabilities: NetworkCapabilities?): String = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }

        private fun videoDecoders(): List<String> = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.startsWith("video/") } }
                .map { codec -> codec.name + ":" + codec.supportedTypes.filter { it.startsWith("video/") }.joinToString("|") }
                .take(24)
                .toList()
        }.getOrDefault(emptyList())

        private fun audioCapabilities(context: Context): String {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return buildString {
                append("sampleRate=")
                append(audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "unknown")
                append(", framesPerBuffer=")
                append(audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "unknown")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    append(", outputDevices=")
                    append(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString("|") { it.type.toString() })
                }
            }
        }
    }
}
