package com.vit.devicememorymonitor

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class MemoryMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val intervalMs = 5000L
    private val cooldownMs = 60_000L
    private var lastCleanAt = 0L
    private var running = false

    private val channelId = "optimizer_service_channel"
    private val notifId = 2001

    private val loop = object : Runnable {
        override fun run() {
            checkAndClean()
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Monitoring memory")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notifId, notification)
        }
        if (!running) {
            running = true
            handler.post(loop)
        }
        return START_NOT_STICKY
    }

    private fun checkAndClean() {
        val prefs = getSharedPreferences("memory_monitor_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_enabled", false)) {
            stopSelf()
            return
        }

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val usedPct = ((mi.totalMem - mi.availMem).toDouble() / mi.totalMem.toDouble() * 100).toInt()
        val threshold = prefs.getInt("auto_threshold", 85)
        val now = System.currentTimeMillis()

        if (usedPct >= threshold && now - lastCleanAt >= cooldownMs) {
            lastCleanAt = now
            val protectedSet = MemoryCleaner.getUserProtected(this)
            val targets = MemoryCleaner.loadCandidates(this, 40, false)
                .map { it.pkg }
                .filter { it !in protectedSet }

            if (targets.isEmpty()) {
                updateNotification("Memory $usedPct%, no candidates to clean")
                return
            }

            updateNotification("Memory $usedPct%, cleaning ${targets.size} apps...")
            MemoryCleaner.cleanAsync(this, targets, "auto") { r ->
                val freedMb = (r.afterBytes - r.beforeBytes) / (1024 * 1024)
                val sign = if (freedMb >= 0) "+" else ""
                updateNotification("Last clean: $sign$freedMb MB available")
            }
        } else {
            updateNotification("Memory $usedPct%, auto-clean at $threshold%")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Memory optimizer",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Memory optimizer running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(notifId, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        running = false
    }
}