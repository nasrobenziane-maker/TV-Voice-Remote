package com.codex.tvphone

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var ipField: EditText
    private lateinit var pinField: EditText
    private lateinit var wakeField: EditText
    private lateinit var talkButton: Button
    private lateinit var alwaysButton: Button

    private val reqPermissions = 100
    private val reqZip = 301
    private val reqTree = 302
    private val bg = 0xFF101827.toInt()
    private val accent = 0xFF75C9FF.toInt()
    private val ui = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceEngine.preload(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(refreshTask)
        ui.post(refreshTask)
    }

    override fun onPause() {
        ui.removeCallbacks(refreshTask)
        // Never leave the push-to-talk microphone open when the screen goes away.
        if (VoiceEngine.activeMode == VoiceEngine.Mode.PUSH) VoiceEngine.stop(0)
        super.onPause()
    }

    // ---- UI ---------------------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(root) }

        root.addView(label("TV Voice Remote", 26f, accent).apply { gravity = Gravity.CENTER })
        status = label("", 15f, Color.WHITE)
        root.addView(status)

        // Mode
        root.addView(heading("طريقة الاستعمال"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbPush = RadioButton(this).apply { text = "اضغط وتكلم"; setTextColor(Color.WHITE); id = View.generateViewId() }
        val rbAlways = RadioButton(this).apply { text = "استماع دائم (مع كلمة التنبيه)"; setTextColor(Color.WHITE); id = View.generateViewId() }
        group.addView(rbPush)
        group.addView(rbAlways)
        val always = Prefs.mode(this) == "always"
        (if (always) rbAlways else rbPush).isChecked = true
        group.setOnCheckedChangeListener { _, checkedId -> onModeChanged(checkedId == rbAlways.id) }
        root.addView(group)

        talkButton = button("اضغط مطولًا وتكلم", 22f) { }.apply {
            minimumHeight = dp(110)
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (ensurePermissions(false)) {
                            VoiceEngine.start(this@MainActivity, VoiceEngine.Mode.PUSH)
                            v.isPressed = true
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        if (VoiceEngine.activeMode == VoiceEngine.Mode.PUSH) VoiceEngine.stop(400)
                        true
                    }
                    else -> true
                }
            }
        }
        root.addView(talkButton)
        alwaysButton = button("ابدأ الاستماع الدائم", 18f) {
            if (ListeningService.active) stopAlways() else startAlways()
        }
        root.addView(alwaysButton)
        updateModeViews(always)

        // TV connection
        root.addView(heading("اتصال التلفاز (نفس شبكة Wi‑Fi)"))
        ipField = field("عنوان IP للتلفاز، مثل 192.168.4.5", InputType.TYPE_CLASS_TEXT).apply {
            setText(Prefs.tvIp(this@MainActivity)); textDirection = View.TEXT_DIRECTION_LTR
        }
        pinField = field("الرمز السري (يظهر على شاشة التلفاز)", InputType.TYPE_CLASS_NUMBER).apply {
            setText(Prefs.pin(this@MainActivity)); textDirection = View.TEXT_DIRECTION_LTR
        }
        wakeField = field("كلمة التنبيه", InputType.TYPE_CLASS_TEXT).apply { setText(Prefs.wake(this@MainActivity)) }
        root.addView(ipField)
        root.addView(pinField)
        root.addView(wakeField)
        root.addView(button("ابحث عن التلفاز تلقائيًا", 16f) { findTv() })
        root.addView(button("حفظ", 16f) { saveFields(); EventLog.add("تم الحفظ") })
        root.addView(button("اختبار الاتصال", 16f) { testConnection() })

        // Model
        root.addView(heading("نموذج التعرف على الصوت (مرة واحدة)"))
        root.addView(button("تنزيل النموذج (يحتاج إنترنت مرة واحدة)", 16f) {
            modelOp { ModelManager.download(applicationContext) }
        })
        root.addView(button("استيراد ملف ZIP للنموذج", 16f) { pick(Intent.ACTION_OPEN_DOCUMENT, reqZip) })
        root.addView(button("استيراد مجلد النموذج", 16f) { pick(Intent.ACTION_OPEN_DOCUMENT_TREE, reqTree) })

        // Log
        root.addView(heading("آخر الأحداث"))
        log = label("", 14f, 0xFFD1D8E2.toInt())
        root.addView(log)

        setContentView(scroll)
        refresh()
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
    }

    private fun heading(text: String) = label(text, 18f, accent).apply { setPadding(0, dp(22), 0, dp(6)) }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type; textSize = 16f
        setTextColor(Color.WHITE); setHintTextColor(0xFF8A94A6.toInt()); setSingleLine(true)
    }

    private fun button(text: String, size: Float, onClick: () -> Unit) = Button(this).apply {
        this.text = text; textSize = size; isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun refresh() {
        val model = when {
            ModelManager.busy -> ModelManager.status
            ModelManager.isInstalled(this) -> "جاهز ✓"
            else -> "غير مثبت"
        }
        status.text = "النموذج: $model\nالاستماع الدائم: ${if (ListeningService.active) "يعمل" else "متوقف"}\nكلمة التنبيه: ${Prefs.wake(this)}"
        alwaysButton.text = if (ListeningService.active) "أوقف الاستماع الدائم" else "ابدأ الاستماع الدائم"
        log.text = EventLog.text()
    }

    // ---- actions ----------------------------------------------------------------------------

    private fun updateModeViews(always: Boolean) {
        talkButton.visibility = if (always) View.GONE else View.VISIBLE
        alwaysButton.visibility = if (always) View.VISIBLE else View.GONE
    }

    private fun onModeChanged(always: Boolean) {
        Prefs.setMode(this, if (always) "always" else "push")
        if (!always) stopAlways()
        updateModeViews(always)
    }

    private fun saveFields() {
        Prefs.save(this, ipField.text.toString().trim(), pinField.text.toString().trim(), wakeField.text.toString().trim())
        // A new wake word needs a fresh recognizer.
        if (ListeningService.active) startService(Intent(this, ListeningService::class.java))
    }

    private fun startAlways() {
        if (!ensurePermissions(true)) { EventLog.add("امنح إذن الميكروفون ثم اضغط مرة أخرى"); return }
        if (!ModelManager.isInstalled(this)) { EventLog.add("ثبّت النموذج أولًا"); return }
        saveFields()
        try { startForegroundService(Intent(this, ListeningService::class.java)) }
        catch (e: Exception) { EventLog.add("تعذر بدء الاستماع: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun stopAlways() {
        stopService(Intent(this, ListeningService::class.java))
    }

    private fun testConnection() {
        saveFields()
        EventLog.add("جارٍ اختبار الاتصال…")
        CommandSender.send(this, "ping") { r -> EventLog.add(if (r.ok) "الاتصال بالتلفاز ناجح ✓" else r.message) }
    }

    private fun findTv() {
        EventLog.add("جارٍ البحث عن التلفاز…")
        TvDiscovery.find(this) { ip ->
            if (ip != null) { ipField.setText(ip); EventLog.add("وجدت التلفاز: $ip — اضغط حفظ") }
            else EventLog.add("لم أجد التلفاز تلقائيًا، أدخل العنوان يدويًا")
        }
    }

    private fun modelOp(work: () -> String) {
        if (ModelManager.busy) { EventLog.add("عملية النموذج جارية، انتظر"); return }
        stopAlways()
        ModelManager.runAsync(applicationContext) {
            VoiceEngine.stopAndJoin(2000)
            VoiceEngine.releaseModel()
            work()
        }
    }

    private fun pick(action: String, code: Int) {
        val intent = Intent(action)
        if (action == Intent.ACTION_OPEN_DOCUMENT) { intent.addCategory(Intent.CATEGORY_OPENABLE); intent.type = "*/*" }
        try { startActivityForResult(intent, code) }
        catch (_: ActivityNotFoundException) { EventLog.add("لا يوجد تطبيق ملفات لاختيار العنصر") }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Simple Activity-based picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val app = applicationContext
        when (requestCode) {
            reqZip -> modelOp { ModelManager.importZip(app, uri) }
            reqTree -> modelOp { ModelManager.importFolder(app, uri) }
        }
    }

    private fun ensurePermissions(forService: Boolean): Boolean {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val missing = ArrayList<String>()
        if (!micOk) missing.add(Manifest.permission.RECORD_AUDIO)
        if (forService && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), reqPermissions)
        return micOk
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == reqPermissions) {
            EventLog.add(if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) "تم منح الإذن، أعد المحاولة" else "الإذن مرفوض")
        }
    }
}
