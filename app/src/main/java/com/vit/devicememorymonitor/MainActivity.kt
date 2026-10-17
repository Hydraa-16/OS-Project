package com.vit.devicememorymonitor

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Pages
    private lateinit var pageMonitor: View
    private lateinit var pageOptimize: View
    private lateinit var pageInsights: View
    private lateinit var pageKernel: View
    private lateinit var currentPage: View

    // Monitor page
    private lateinit var tvTotalMemory: TextView
    private lateinit var tvAvailMemory: TextView
    private lateinit var tvUsedMemory: TextView
    private lateinit var tvUsedPercent: TextView
    private lateinit var tvLowMemory: TextView
    private lateinit var tvThresholdValue: TextView
    private lateinit var graphView: MemoryGraphView
    private lateinit var tvAppPss: TextView
    private lateinit var tvAppPrivateDirty: TextView
    private lateinit var tvAppSharedDirty: TextView

    // Optimize page
    private lateinit var switchAuto: SwitchCompat
    private lateinit var tvAutoInfo: TextView
    private lateinit var tvUsageStatus: TextView
    private lateinit var btnGrantUsage: Button
    private lateinit var btnCleanNow: Button
    private lateinit var tvCandidatesEmpty: TextView
    private lateinit var layoutCandidates: LinearLayout
    private lateinit var tvCleanResult: TextView
    private lateinit var tvCleanLog: TextView

    // Insights page
    private lateinit var tvTrendSummary: TextView
    private lateinit var tvTrimEvents: TextView
    private lateinit var tvExitReasons: TextView

    // Kernel page
    private lateinit var tvNativeProc: TextView
    private lateinit var tvNativeSys: TextView
    private lateinit var tvNativeCompare: TextView

    private lateinit var activityManager: ActivityManager
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var historyFile: File

    private val handler = Handler(Looper.getMainLooper())
    private val updateIntervalMs = 2000L
    private var thresholdAlreadyFired = false
    private var lastAvailBytes: Long = -1
    private var candidates: List<Candidate> = emptyList()
    private val analytics = PressureAnalytics(60)

    private val CHANNEL_ID = "memory_alert_channel"
    private val NOTIFICATION_ID = 1001

    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Notifications disabled, alerts won't show", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }

        bindViews()

        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        prefs = getSharedPreferences("memory_monitor_prefs", Context.MODE_PRIVATE)
        historyFile = File(filesDir, "memory_history.csv")

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        setupNavigation()
        setupMonitorPage()
        setupOptimizePage()

        updateThresholdLabel()
        updateAutoLabel()
        startMemoryUpdates()

        if (prefs.getBoolean("auto_enabled", false)) startOptimizerService()
    }

    override fun onResume() {
        super.onResume()
        if (currentPage === pageOptimize) refreshOptimizeTab()
        if (currentPage === pageInsights) refreshInsightsStatic()
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        pageMonitor = findViewById(R.id.pageMonitor)
        pageOptimize = findViewById(R.id.pageOptimize)
        pageInsights = findViewById(R.id.pageInsights)
        pageKernel = findViewById(R.id.pageKernel)
        currentPage = pageMonitor

        tvTotalMemory = findViewById(R.id.tvTotalMemory)
        tvAvailMemory = findViewById(R.id.tvAvailMemory)
        tvUsedMemory = findViewById(R.id.tvUsedMemory)
        tvUsedPercent = findViewById(R.id.tvUsedPercent)
        tvLowMemory = findViewById(R.id.tvLowMemory)
        tvThresholdValue = findViewById(R.id.tvThresholdValue)
        graphView = findViewById(R.id.graphView)
        tvAppPss = findViewById(R.id.tvAppPss)
        tvAppPrivateDirty = findViewById(R.id.tvAppPrivateDirty)
        tvAppSharedDirty = findViewById(R.id.tvAppSharedDirty)

        switchAuto = findViewById(R.id.switchAuto)
        tvAutoInfo = findViewById(R.id.tvAutoInfo)
        tvUsageStatus = findViewById(R.id.tvUsageStatus)
        btnGrantUsage = findViewById(R.id.btnGrantUsage)
        btnCleanNow = findViewById(R.id.btnCleanNow)
        tvCandidatesEmpty = findViewById(R.id.tvCandidatesEmpty)
        layoutCandidates = findViewById(R.id.layoutCandidates)
        tvCleanResult = findViewById(R.id.tvCleanResult)
        tvCleanLog = findViewById(R.id.tvCleanLog)

        tvTrendSummary = findViewById(R.id.tvTrendSummary)
        tvTrimEvents = findViewById(R.id.tvTrimEvents)
        tvExitReasons = findViewById(R.id.tvExitReasons)

        tvNativeProc = findViewById(R.id.tvNativeProc)
        tvNativeSys = findViewById(R.id.tvNativeSys)
        tvNativeCompare = findViewById(R.id.tvNativeCompare)
    }

    private fun setupNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_monitor -> showPage(pageMonitor)
                R.id.nav_optimize -> {
                    showPage(pageOptimize)
                    refreshOptimizeTab()
                }
                R.id.nav_insights -> {
                    showPage(pageInsights)
                    refreshInsightsStatic()
                }
                R.id.nav_kernel -> showPage(pageKernel)
            }
            true
        }
    }

    private fun showPage(target: View) {
        currentPage = target
        listOf(pageMonitor, pageOptimize, pageInsights, pageKernel).forEach {
            it.visibility = if (it === target) View.VISIBLE else View.GONE
        }
    }

    private fun setupMonitorPage() {
        findViewById<Button>(R.id.btnSetThreshold).setOnClickListener {
            showPercentDialog("Set memory alert threshold (%)", "threshold_percent", 80) {
                thresholdAlreadyFired = false
                updateThresholdLabel()
            }
        }
        findViewById<Button>(R.id.btnViewHistory).setOnClickListener {
            showHistoryDialog()
        }
    }

    private fun setupOptimizePage() {
        switchAuto.isChecked = prefs.getBoolean("auto_enabled", false)
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_enabled", checked).apply()
            if (checked) {
                if (!MemoryCleaner.hasUsageAccess(this)) {
                    Toast.makeText(this, "Grant usage access so the optimizer has apps to clean", Toast.LENGTH_LONG).show()
                }
                startOptimizerService()
            } else {
                stopService(Intent(this, MemoryMonitorService::class.java))
            }
        }

        findViewById<Button>(R.id.btnAutoThreshold).setOnClickListener {
            showPercentDialog("Auto-clean when memory used is at least (%)", "auto_threshold", 85) {
                updateAutoLabel()
            }
        }

        btnGrantUsage.setOnClickListener {
            startActivity(MemoryCleaner.usageAccessIntent())
        }

        findViewById<Button>(R.id.btnRefreshCandidates).setOnClickListener {
            refreshCandidates()
        }

        btnCleanNow.setOnClickListener {
            if (!MemoryCleaner.hasUsageAccess(this)) {
                Toast.makeText(this, "Grant usage access first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val protectedSet = MemoryCleaner.getUserProtected(this)
            val targets = candidates.map { it.pkg }.filter { it !in protectedSet }
            if (targets.isEmpty()) {
                Toast.makeText(this, "No apps selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnCleanNow.isEnabled = false
            tvCleanResult.text = "Cleaning ${targets.size} apps..."
            MemoryCleaner.cleanAsync(this, targets, "manual") { r ->
                btnCleanNow.isEnabled = true
                tvCleanResult.text = formatClean(r)
                refreshCleanLog()
            }
        }
    }

    private fun startOptimizerService() {
        ContextCompat.startForegroundService(this, Intent(this, MemoryMonitorService::class.java))
    }

    // ---------------------------------------------------------------- polling loop

    private fun startMemoryUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                updateMemoryStats()
                updateOwnAppFootprint()
                updateNativeStats()
                handler.postDelayed(this, updateIntervalMs)
            }
        })
    }

    private fun updateMemoryStats() {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        lastAvailBytes = memInfo.availMem

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val usedMb = totalMb - availMb
        val usedPercent = (usedMb.toDouble() / totalMb.toDouble() * 100).toInt()

        tvTotalMemory.text = "Total RAM: $totalMb MB"
        tvAvailMemory.text = "Available RAM: $availMb MB"
        tvUsedMemory.text = "Used RAM: $usedMb MB"
        tvUsedPercent.text = "Used: $usedPercent%"
        tvLowMemory.text = if (memInfo.lowMemory) "System reports LOW MEMORY" else ""

        checkThreshold(usedPercent)
        graphView.addDataPoint(usedPercent)
        logMemorySnapshot(totalMb, availMb, usedMb, usedPercent)
        updateTrend(usedPercent)
    }

    private fun updateOwnAppFootprint() {
        val debugMemInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemInfo)

        val totalPssMb = debugMemInfo.totalPss / 1024.0
        val privateDirtyMb = debugMemInfo.totalPrivateDirty / 1024.0
        val sharedDirtyMb = debugMemInfo.totalSharedDirty / 1024.0

        tvAppPss.text = "Total PSS: %.2f MB".format(totalPssMb)
        tvAppPrivateDirty.text = "Private Dirty: %.2f MB".format(privateDirtyMb)
        tvAppSharedDirty.text = "Shared Dirty: %.2f MB".format(sharedDirtyMb)
    }

    // ---------------------------------------------------------------- kernel page

    private fun fmtKb(kb: Long): String =
        if (kb < 0) "n/a" else "%.1f MB".format(kb / 1024.0)

    private fun fmtBytes(bytes: Long): String =
        if (bytes < 0) "n/a" else "%.1f MB".format(bytes / (1024.0 * 1024.0))

    private fun updateNativeStats() {
        if (!NativeBridge.isAvailable) {
            tvNativeProc.text = "Native library failed to load"
            tvNativeSys.text = ""
            tvNativeCompare.text = ""
            return
        }

        val proc = NativeBridge.procMeminfo()
        val sys = NativeBridge.sysInfo()

        tvNativeProc.text = if (proc == null) {
            "unavailable"
        } else {
            buildString {
                appendLine("MemTotal: ${fmtKb(proc.memTotalKb)}")
                appendLine("MemFree: ${fmtKb(proc.memFreeKb)}")
                appendLine("MemAvailable: ${fmtKb(proc.memAvailableKb)}")
                appendLine("Buffers: ${fmtKb(proc.buffersKb)}")
                appendLine("Cached: ${fmtKb(proc.cachedKb)}")
                appendLine("SwapTotal: ${fmtKb(proc.swapTotalKb)}")
                appendLine("SwapFree: ${fmtKb(proc.swapFreeKb)}")
                appendLine("Dirty: ${fmtKb(proc.dirtyKb)}")
                append("Slab: ${fmtKb(proc.slabKb)}")
            }
        }

        tvNativeSys.text = if (sys == null) {
            "unavailable"
        } else {
            buildString {
                appendLine("totalram: ${fmtBytes(sys.totalRamBytes)}")
                appendLine("freeram: ${fmtBytes(sys.freeRamBytes)}")
                appendLine("bufferram: ${fmtBytes(sys.bufferRamBytes)}")
                appendLine("totalswap: ${fmtBytes(sys.totalSwapBytes)}")
                appendLine("freeswap: ${fmtBytes(sys.freeSwapBytes)}")
                appendLine("uptime: ${sys.uptimeSec / 60} min")
                append("processes: ${sys.procs}")
            }
        }

        tvNativeCompare.text = buildString {
            appendLine("ActivityManager availMem: ${fmtBytes(lastAvailBytes)}")
            appendLine("/proc MemAvailable: ${if (proc == null) "n/a" else fmtKb(proc.memAvailableKb)}")
            append("sysinfo freeram (no cache): ${if (sys == null) "n/a" else fmtBytes(sys.freeRamBytes)}")
        }
    }

    // ---------------------------------------------------------------- insights page

    private fun updateTrend(usedPercent: Int) {
        analytics.addSample(System.currentTimeMillis(), usedPercent.toDouble())
        val threshold = prefs.getInt("threshold_percent", 80)
        val slope = analytics.slopePerMinute()
        val eta = analytics.etaMinutes(usedPercent.toDouble(), threshold)
        val windowSec = analytics.sampleCount * updateIntervalMs / 1000

        val etaLine = when {
            usedPercent >= threshold -> "Already at or above the $threshold% alert threshold"
            eta == null -> "No estimate, usage is not rising toward $threshold%"
            else -> "Estimated time to reach $threshold%%: %.1f min".format(eta)
        }

        tvTrendSummary.text = buildString {
            appendLine("Trend: ${analytics.label()}")
            appendLine("Current: $usedPercent%")
            appendLine("Rolling average (last $windowSec s): %.1f%%".format(analytics.average()))
            appendLine("Rate: %+.2f %% per minute".format(slope))
            append(etaLine)
        }
    }

    private fun refreshInsightsStatic() {
        val trim = TrimLog.recent(this, 10)
        tvTrimEvents.text = if (trim.isEmpty()) {
            "No events yet. Press Home to hide the app and come back to see UI_HIDDEN."
        } else {
            trim.joinToString("\n")
        }
        tvExitReasons.text = loadExitReasons()
    }

    private fun loadExitReasons(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Requires Android 11 (API 30) or higher"
        }
        return try {
            exitReasonsText()
        } catch (e: Exception) {
            "Unavailable: ${e.message}"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonsText(): String {
        val list = activityManager.getHistoricalProcessExitReasons(packageName, 0, 10)
        if (list.isEmpty()) return "No recorded exits yet"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return list.joinToString("\n") { info ->
            val desc = info.description?.takeIf { it.isNotBlank() } ?: ""
            "${fmt.format(Date(info.timestamp))}   ${reasonName(info.reason)}   $desc".trim()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by lmkd)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "CODE $reason"
    }

    // ---------------------------------------------------------------- optimize page

    private fun refreshOptimizeTab() {
        refreshCandidates()
        refreshCleanLog()
        updateAutoLabel()
    }

    private fun refreshCandidates() {
        val hasAccess = MemoryCleaner.hasUsageAccess(this)
        tvUsageStatus.text = if (hasAccess) {
            "Usage access: granted"
        } else {
            "Usage access: not granted. It is needed to list recently used apps."
        }
        btnGrantUsage.visibility = if (hasAccess) View.GONE else View.VISIBLE

        layoutCandidates.removeAllViews()
        candidates = MemoryCleaner.loadCandidates(this, 25, true)
        tvCandidatesEmpty.visibility = if (candidates.isEmpty()) View.VISIBLE else View.GONE

        val protectedSet = MemoryCleaner.getUserProtected(this)
        for (c in candidates) {
            val row = layoutInflater.inflate(R.layout.item_candidate, layoutCandidates, false)
            val cb = row.findViewById<CheckBox>(R.id.cbCandidate)
            val icon = row.findViewById<ImageView>(R.id.ivIcon)
            val name = row.findViewById<TextView>(R.id.tvName)
            val last = row.findViewById<TextView>(R.id.tvLastUsed)

            name.text = c.label
            last.text = "Last used ${ago(c.lastUsed)}"
            icon.setImageDrawable(c.icon)
            cb.isChecked = c.pkg !in protectedSet
            cb.setOnCheckedChangeListener { _, checked ->
                MemoryCleaner.setProtected(this, c.pkg, !checked)
            }
            layoutCandidates.addView(row)
        }
    }

    private fun refreshCleanLog() {
        val lines = MemoryCleaner.recentLog(this, 5)
        tvCleanLog.text = if (lines.isEmpty()) "Nothing yet" else lines.joinToString("\n")
    }

    private fun updateAutoLabel() {
        val t = prefs.getInt("auto_threshold", 85)
        tvAutoInfo.text = "Cleans cached apps when memory used reaches $t% (60 s cooldown). Runs as a foreground service with a notification."
    }

    private fun formatClean(r: CleanResult): String {
        val deltaMb = (r.afterBytes - r.beforeBytes) / (1024.0 * 1024.0)
        val change = "%+.1f".format(deltaMb)
        return "Targeted ${r.attempted} apps\n" +
                "Available before: ${fmtBytes(r.beforeBytes)}\n" +
                "Available after: ${fmtBytes(r.afterBytes)}\n" +
                "Change: $change MB"
    }

    private fun ago(ms: Long): String {
        val mins = (System.currentTimeMillis() - ms) / 60000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 1440 -> "${mins / 60} h ago"
            else -> "${mins / 1440} d ago"
        }
    }

    // ---------------------------------------------------------------- alerts and history

    private fun checkThreshold(usedPercent: Int) {
        val threshold = prefs.getInt("threshold_percent", 80)
        if (usedPercent >= threshold) {
            if (!thresholdAlreadyFired) {
                sendThresholdNotification(usedPercent, threshold)
                thresholdAlreadyFired = true
            }
        } else {
            thresholdAlreadyFired = false
        }
    }

    private fun showPercentDialog(title: String, key: String, default: Int, afterSave: () -> Unit) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(prefs.getInt(key, default).toString())

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value != null && value in 1..100) {
                    prefs.edit().putInt(key, value).apply()
                    afterSave()
                } else {
                    Toast.makeText(this, "Enter a value between 1 and 100", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateThresholdLabel() {
        val threshold = prefs.getInt("threshold_percent", 80)
        tvThresholdValue.text = "Alert threshold: $threshold%"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Memory Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when device memory usage crosses your threshold"
                enableLights(true)
                lightColor = Color.RED
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun sendThresholdNotification(usedPercent: Int, threshold: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Memory Usage Alert")
            .setContentText("Memory usage is at $usedPercent%, above your $threshold% threshold")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun logMemorySnapshot(totalMb: Long, availMb: Long, usedMb: Long, usedPercent: Int) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$timestamp,$totalMb,$availMb,$usedMb,$usedPercent\n"
        try {
            historyFile.appendText(line)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showHistoryDialog() {
        val lines = try {
            historyFile.readLines()
        } catch (e: Exception) {
            emptyList()
        }

        val display = if (lines.isEmpty()) {
            "No history recorded yet."
        } else {
            val header = "Timestamp,Total,Avail,Used,Used%\n\n"
            header + lines.takeLast(20).joinToString("\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Memory History (last 20 entries)")
            .setMessage(display)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear Log") { _, _ ->
                historyFile.writeText("")
                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}