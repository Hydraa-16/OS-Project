package com.vit.devicememorymonitor

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

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

    private lateinit var activityManager: ActivityManager
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var historyFile: File

    private val handler = Handler(Looper.getMainLooper())
    private val updateIntervalMs = 2000L
    private var thresholdAlreadyFired = false

    private val CHANNEL_ID = "memory_alert_channel"
    private val NOTIFICATION_ID = 1001

    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Notifications disabled — threshold alerts won't show", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        prefs = getSharedPreferences("memory_monitor_prefs", Context.MODE_PRIVATE)
        historyFile = File(filesDir, "memory_history.csv")

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        findViewById<android.widget.Button>(R.id.btnSetThreshold).setOnClickListener {
            showThresholdDialog()
        }

        findViewById<android.widget.Button>(R.id.btnViewHistory).setOnClickListener {
            showHistoryDialog()
        }

        updateThresholdLabel()
        startMemoryUpdates()
    }

    private fun startMemoryUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                updateMemoryStats()
                updateOwnAppFootprint()
                handler.postDelayed(this, updateIntervalMs)
            }
        })
    }

    private fun updateMemoryStats() {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val usedMb = totalMb - availMb
        val usedPercent = (usedMb.toDouble() / totalMb.toDouble() * 100).toInt()

        tvTotalMemory.text = "Total RAM: $totalMb MB"
        tvAvailMemory.text = "Available RAM: $availMb MB"
        tvUsedMemory.text = "Used RAM: $usedMb MB"
        tvUsedPercent.text = "Used: $usedPercent%"
        tvLowMemory.text = if (memInfo.lowMemory) "⚠ System reports LOW MEMORY" else ""

        checkThreshold(usedPercent)
        graphView.addDataPoint(usedPercent)
        logMemorySnapshot(totalMb, availMb, usedMb, usedPercent)
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

    private fun showThresholdDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(prefs.getInt("threshold_percent", 80).toString())

        AlertDialog.Builder(this)
            .setTitle("Set memory alert threshold (%)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value != null && value in 1..100) {
                    prefs.edit().putInt("threshold_percent", value).apply()
                    thresholdAlreadyFired = false
                    updateThresholdLabel()
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