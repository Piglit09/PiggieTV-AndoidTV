package com.piggie.tv.util

import android.content.Context
import android.os.Build
import com.piggie.tv.diagnostics.PtvRedactor
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val FILE_NAME = "ptv_crash_reports.json"
    private const val REDACTION_VERSION = 1
    private const val REDACTION_VERSION_KEY = "redactionVersion"
    private const val DIAGNOSTIC_DIRECTORY = "diagnostics"
    private val LEGACY_DIAGNOSTIC_EXPORTS = listOf("ptv-diagnostics.json", "ptv-diagnostics.txt")
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            purgeLegacySensitiveFiles(context.applicationContext)
            initialized = true
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            saveCrash(context, error)
            defaultHandler?.uncaughtException(thread, error)
        }
    }

    private fun saveCrash(context: Context, error: Throwable) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val reports = readTrustedReports(file) ?: emptyReports()
            val array = reports.getJSONArray("crashes")

            val crash = JSONObject().apply {
                put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                put("version", Build.VERSION.RELEASE)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("exception", error.javaClass.simpleName)
                put("message", sanitize(error.message))
                put("stacktrace", sanitize(error.stackTraceToString()))
            }

            array.put(crash)
            // Keep only last 10 crashes
            if (array.length() > 10) {
                val newArray = org.json.JSONArray()
                for (i in (array.length() - 10) until array.length()) {
                    newArray.put(array.get(i))
                }
                reports.put("crashes", newArray)
            }

            file.writeText(reports.toString())
        }
    }

    fun getReports(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val reports = readTrustedReports(file)
        if (reports == null) {
            file.delete()
            return null
        }
        return reports.toString()
    }

    fun clearReports(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    internal fun sanitize(value: String?): String? = PtvRedactor.text(value)

    /**
     * Pre-hardening crash reports and saved diagnostic exports may contain raw auth text. Their
     * format cannot prove that every arbitrary legacy value is safe, so discard only those
     * ephemeral legacy diagnostics instead of risking later export or backup.
     */
    internal fun purgeLegacySensitiveFiles(context: Context) {
        runCatching {
            val crashFile = File(context.filesDir, FILE_NAME)
            if (crashFile.exists() && readTrustedReports(crashFile) == null) crashFile.delete()
        }
        runCatching {
            val directory = File(context.filesDir, DIAGNOSTIC_DIRECTORY)
            LEGACY_DIAGNOSTIC_EXPORTS.forEach { name -> File(directory, name).delete() }
        }
    }

    private fun readTrustedReports(file: File): JSONObject? = runCatching {
        if (!file.exists()) return@runCatching null
        JSONObject(file.readText()).takeIf { reports ->
            reports.optInt(REDACTION_VERSION_KEY, 0) == REDACTION_VERSION &&
                reports.optJSONArray("crashes") != null
        }
    }.getOrNull()

    private fun emptyReports(): JSONObject = JSONObject()
        .put(REDACTION_VERSION_KEY, REDACTION_VERSION)
        .put("crashes", org.json.JSONArray())
}
