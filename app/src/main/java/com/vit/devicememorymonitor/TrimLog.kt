package com.vit.devicememorymonitor

import android.content.ComponentCallbacks2
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrimLog {
    private const val FILE = "trim_events.csv"

    fun levelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE (80)"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE (60)"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND (40)"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN (20)"
        15 -> "RUNNING_CRITICAL (15)"
        10 -> "RUNNING_LOW (10)"
        5 -> "RUNNING_MODERATE (5)"
        else -> "LEVEL $level"
    }

    fun add(context: Context, level: Int) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        try {
            File(context.filesDir, FILE).appendText("$ts,${levelName(level)}\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recent(context: Context, n: Int = 10): List<String> {
        return try {
            File(context.filesDir, FILE).readLines()
                .takeLast(n)
                .reversed()
                .map { it.replace(",", "   ") }
        } catch (e: Exception) {
            emptyList()
        }
    }
}