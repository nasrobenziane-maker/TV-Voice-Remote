package com.codex.tvremote

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** TV side of the remote: displays pairing information and receives commands from the phone. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        stopService(Intent().setClassName(packageName, "$packageName.VoiceCommandService"))
        Thread({ removeLegacyVoiceModel() }, "legacy-model-cleanup").start()
        buildUi()
        startPhoneLink()
    }

    private fun removeLegacyVoiceModel() {
        val prefs = getSharedPreferences("legacy_cleanup", MODE_PRIVATE)
        if (prefs.getBoolean("tv_model_removed_v1", false)) return
        val oldDirs = listOf(
            File(filesDir, "vosk-model"), File(filesDir, "model-import"),
            getExternalFilesDir(null)?.let { File(it, "vosk-model") },
            getExternalFilesDir(null)?.let { File(it, "vosk-model-small-ar-tn-0.1-linto") }
        ).filterNotNull()
        try {
            oldDirs.forEach { dir -> if (dir.exists() && !dir.deleteRecursively()) return }
            prefs.edit().putBoolean("tv_model_removed_v1", true).apply()
        } catch (_: Exception) { /* Retry on next app launch; preserve all preferences and pairing data. */ }
    }

    private fun startPhoneLink() {
        try {
            val intent = Intent(this, PhoneLinkService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { }
    }

    override fun onResume() { super.onResume(); ui.removeCallbacks(refreshTask); ui.post(refreshTask) }
    override fun onPause() { ui.removeCallbacks(refreshTask); super.onPause() }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 20, 28, 20)
            setBackgroundColor(0xFF101827.toInt())
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        root.addView(TextView(this).apply {
            text = "TV VOICE REMOTE"; textSize = 30f; setTextColor(0xFF75C9FF.toInt()); gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            textSize = 19f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(8, 18, 8, 18)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(action("Enable media access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(TextView(this).apply {
            text = "يستقبل التلفاز الأوامر من الهاتف عبر Wi‑Fi. استخدم ريموت التلفاز العادي كالمعتاد.\n\nالأوامر تُفهم في الهاتف؛ التلفاز لا يحتاج إلى ميكروفون."
            textSize = 18f; setTextColor(0xFFE0E8F3.toInt()); gravity = Gravity.CENTER
            setPadding(12, 20, 12, 20)
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(scroll)
        refresh()
    }

    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isFocusable = true; setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(8, 8, 8, 8) }
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val sessionReady = MediaAccessService.instance != null
        val ip = LinkConfig.localAddress() ?: "no Wi-Fi"
        status.text = "Phone link: $ip:${LinkConfig.PORT}\nPIN: ${LinkConfig.pin(this)}\nMedia access: ${if (sessionReady) "READY" else "enable in Settings"}\nYouTube overlay access: ${if (Settings.canDrawOverlays(this)) "OK" else "see README"}"
    }
}
