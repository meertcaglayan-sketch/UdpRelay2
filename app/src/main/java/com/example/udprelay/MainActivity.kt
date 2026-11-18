package com.example.udprelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var ips: TextView
    private lateinit var btn: Button
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, pad / 2, 0, 0) }

        root.addView(TextView(this).apply { text = "SOCKS5 + UDP Relay"; textSize = 24f })
        status = TextView(this).apply { textSize = 18f; setPadding(0, pad, 0, pad) }
        root.addView(status)

        root.addView(label("Port"))
        port = EditText(this).apply { setText("1080"); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(port)

        root.addView(label("Kullanıcı adı (boş bırakırsan şifresiz)"))
        user = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(user)

        root.addView(label("Şifre"))
        pass = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(pass)

        btn = Button(this).apply { setOnClickListener { toggle() } }
        root.addView(btn)

        root.addView(label("Telefonun IP adresleri (iPhone'a bunlardan birini gireceksin):"))
        ips = TextView(this).apply { textSize = 16f }
        root.addView(ips)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun toggle() {
        if (RelayService.running) {
            startService(Intent(this, RelayService::class.java).setAction("STOP"))
        } else {
            val p = port.text.toString().toIntOrNull() ?: 1080
            val i = Intent(this, RelayService::class.java)
                .putExtra("port", p)
                .putExtra("user", user.text.toString())
                .putExtra("pass", pass.text.toString())
            startForegroundService(i)
        }
    }

    private fun refresh() {
        val on = RelayService.running
        val err = RelayService.lastError
        status.text = when {
            on -> "● ÇALIŞIYOR"
            err != null -> "Hata: $err"
            else -> "○ Durdu"
        }
        btn.text = if (on) "DURDUR" else "BAŞLAT"
        ips.text = localIps().ifEmpty { "IP bulunamadı (Wi-Fi/hotspot açık mı?)" }
    }

    private fun localIps(): String {
        val list = NetworkInterface.getNetworkInterfaces()?.toList() ?: return ""
        return list.flatMap { ni ->
            ni.inetAddresses.toList()
                .filter { it is Inet4Address && !it.isLoopbackAddress }
                .map { "${ni.name}: ${it.hostAddress}" }
        }.joinToString("\n")
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }
}
