package com.codex.tvremote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val requestMic = 100
    private val requestBackupFolder = 201
    private val requestRestoreFolder = 202
    private var operationStatus: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() { refresh(); uiHandler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(refreshTask)
        uiHandler.post(refreshTask)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 28, 48, 28)
            setBackgroundColor(0xFF101827.toInt())
        }
        val title = TextView(this).apply { text = "TV VOICE REMOTE"; textSize = 32f; setTextColor(0xFF75C9FF.toInt()); gravity = Gravity.CENTER }
        status = TextView(this).apply { textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, 16, 0, 16) }
        root.addView(title)
        root.addView(status)
        root.addView(action("Enable media access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(action("Start voice control") { startVoice() })
        root.addView(action("Stop listening") { stopService(Intent(this, VoiceCommandService::class.java)); refresh() })
        root.addView(action("Back up model to shared storage / USB") { chooseModelFolder(requestBackupFolder) })
        root.addView(action("Restore model from shared storage / USB") {
            stopService(Intent(this, VoiceCommandService::class.java))
            chooseModelFolder(requestRestoreFolder)
        })
        val commands = TextView(this).apply {
            text = "Commands: تشغيل • إيقاف • التالي • السابق\nتقديم دقيقة • إرجاع دقيقة • ارفع الصوت • اخفض الصوت • كتم\nOptional wake phrase: تي في، التالي"
            textSize = 17f; setTextColor(0xFFE0E8F3.toInt()); gravity = Gravity.CENTER; setPadding(0, 14, 0, 14)
        }
        root.addView(commands)
        root.addView(TextView(this).apply { text = "Recent voice events"; textSize = 20f; setTextColor(0xFF75C9FF.toInt()); gravity = Gravity.CENTER })
        log = TextView(this).apply { textSize = 14f; setTextColor(0xFFD1D8E2.toInt()); gravity = Gravity.CENTER; setPadding(8, 8, 8, 8) }
        val scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(720, 170))
        setContentView(root)
        refresh()
    }

    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isFocusable = true; setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(540, 64); lp.setMargins(8, 5, 8, 5); layoutParams = lp
    }

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestMic); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestMic); return
        }
        val intent = Intent(this, VoiceCommandService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        refresh()
    }

    private fun chooseModelFolder(code: Int) {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(picker, code)
    }

    @Deprecated("TV compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK || requestCode !in requestBackupFolder..requestRestoreFolder) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { }
        operationStatus = if (requestCode == requestBackupFolder) "Saving model to selected folder…" else "Restoring model from selected folder…"
        refresh()
        Thread {
            val result = try {
                if (requestCode == requestBackupFolder) ModelStore.backup(applicationContext, uri)
                else ModelStore.restore(applicationContext, uri)
            } catch (e: Exception) { "Model storage failed: ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread { operationStatus = result; refresh() }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == requestMic && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startVoice()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val active = getSharedPreferences("remote", MODE_PRIVATE).getBoolean("listening", false)
        val sessionReady = MediaAccessService.instance != null
        status.text = operationStatus ?: "Microphone: ${if (active) "ON" else "OFF"}     Media access: ${if (sessionReady) "READY" else "enable in Settings"}\nArabic model downloads on first start (about 158 MB)."
        log.text = getSharedPreferences("remote", MODE_PRIVATE).getString("events", "No events yet")
            ?.lines()?.takeLast(8)?.reversed()?.joinToString("\n") ?: "No events yet"
    }
}
