package com.example.udprelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class RelayService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var lastError: String? = null
    }

    private var server: Socks5Server? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") {
            stopSelf(); return START_NOT_STICKY
        }
        val port = intent.getIntExtra("port", 1080)
        val user = intent.getStringExtra("user") ?: ""
        val pass = intent.getStringExtra("pass") ?: ""

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("relay", "Relay", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "relay")
            .setContentTitle("SOCKS5 UDP Relay çalışıyor")
            .setContentText("Port $port")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        startForeground(1, n)

        try {
            server?.stop()
            server = Socks5Server(port, user, pass).also { it.start() }
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "udprelay:wl").also { it.acquire() }
            running = true
            lastError = null
        } catch (e: Exception) {
            running = false
            lastError = e.message
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        server?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
