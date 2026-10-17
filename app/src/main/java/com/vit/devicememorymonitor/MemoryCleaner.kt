package com.vit.devicememorymonitor

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Candidate(
    val pkg: String,
    val label: String,
    val lastUsed: Long,
    val icon: Drawable?
)

data class CleanResult(
    val attempted: Int,
    val beforeBytes: Long,
    val afterBytes: Long
)

object MemoryCleaner {

    private const val PREFS = "memory_monitor_prefs"
    private const val KEY_PROTECTED = "protected_packages"
    private const val LOG_FILE = "clean_log.csv"

    // ---------- Usage access ----------

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    // ---------- Protected (whitelist) apps ----------

    fun getUserProtected(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HashSet(prefs.getStringSet(KEY_PROTECTED, emptySet()) ?: emptySet())
    }

    fun setProtected(context: Context, pkg: String, protect: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(KEY_PROTECTED, emptySet()) ?: emptySet())
        if (protect) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet(KEY_PROTECTED, set).apply()
    }

    private fun systemProtected(context: Context): Set<String> {
        val result = mutableSetOf(
            context.packageName,
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.gms",
            "com.google.android.gsf"
        )
        val pm = context.packageManager

        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(home, 0).forEach { result.add(it.activityInfo.packageName) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.defaultDialerPackage?.let { result.add(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            Telephony.Sms.getDefaultSmsPackage(context)?.let { result.add(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ime?.substringBefore('/')?.let { result.add(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    // ---------- Candidates ----------

    fun loadCandidates(context: Context, maxCount: Int = 25, withIcons: Boolean = true): List<Candidate> {
        if (!hasUsageAccess(context)) return emptyList()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 24L * 60 * 60 * 1000
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end) ?: return emptyList()

        val pm = context.packageManager
        val blocked = systemProtected(context)

        val latest = HashMap<String, Long>()
        for (s in stats) {
            if (s.lastTimeUsed <= 0) continue
            val prev = latest[s.packageName] ?: 0L
            if (s.lastTimeUsed > prev) latest[s.packageName] = s.lastTimeUsed
        }

        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }.toSet()

        return latest.entries
            .filter { it.key in launchable && it.key !in blocked }
            .sortedByDescending { it.value }
            .take(maxCount)
            .mapNotNull { (pkg, lastUsed) ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    Candidate(
                        pkg,
                        pm.getApplicationLabel(info).toString(),
                        lastUsed,
                        if (withIcons) pm.getApplicationIcon(info) else null
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }
    }

    // ---------- Cleaning ----------

    fun availBytes(context: Context): Long {
        val proc = NativeBridge.procMeminfo()
        if (proc != null && proc.memAvailableKb >= 0) return proc.memAvailableKb * 1024
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    fun cleanAsync(
        context: Context,
        packages: List<String>,
        trigger: String,
        onDone: (CleanResult) -> Unit
    ) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = availBytes(app)

        for (pkg in packages) {
            try {
                am.killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Kills are asynchronous, so wait before measuring again
        Handler(Looper.getMainLooper()).postDelayed({
            val after = availBytes(app)
            val result = CleanResult(packages.size, before, after)
            appendLog(app, trigger, result)
            onDone(result)
        }, 2500)
    }

    private fun appendLog(context: Context, trigger: String, r: CleanResult) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val mb = 1024 * 1024
        val line = "$ts,$trigger,${r.attempted},${r.beforeBytes / mb},${r.afterBytes / mb},${(r.afterBytes - r.beforeBytes) / mb}\n"
        try {
            File(context.filesDir, LOG_FILE).appendText(line)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recentLog(context: Context, n: Int = 5): List<String> {
        return try {
            File(context.filesDir, LOG_FILE).readLines()
                .takeLast(n)
                .reversed()
                .map { line ->
                    val p = line.split(",")
                    if (p.size >= 6) {
                        "${p[0]}  [${p[1]}]  ${p[2]} apps  ${p[3]} to ${p[4]} MB  (change ${p[5]} MB)"
                    } else line
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}