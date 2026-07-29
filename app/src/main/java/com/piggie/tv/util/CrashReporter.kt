package com.piggie.tv.util

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val FILE_NAME = "ptv_crash_reports.json"
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
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
            val reports = if (file.exists()) JSONObject(file.readText()) else JSONObject().put("crashes", org.json.JSONArray())
            val array = reports.getJSONArray("crashes")

            val crash = JSONObject().apply {
                put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                put("version", Build.VERSION.RELEASE)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("exception", error.javaClass.simpleName)
                put("message", error.message)
                put("stacktrace", error.stackTraceToString().take(2000)) // Limit size
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
        return if (file.exists()) file.readText() else null
    }

    fun clearReports(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
