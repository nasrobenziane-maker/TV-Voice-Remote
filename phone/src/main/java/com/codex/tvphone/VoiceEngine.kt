package com.codex.tvphone

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.concurrent.thread

/**
 * Offline speech recognition with Vosk, shared by the push-to-talk button and the always-listening service.
 * PUSH: records while the button is held; no wake word needed.
 * ALWAYS: records continuously; a command is only accepted after the wake word.
 */
object VoiceEngine {
    enum class Mode { PUSH, ALWAYS }

    private val lock = Any()
    private val modelLock = Any()
    @Volatile private var model: Model? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var generation = 0
    @Volatile private var running = false
    @Volatile private var stopDeadline = 0L
    @Volatile private var armedUntil = 0L
    @Volatile var activeMode: Mode? = null
        private set
    private var appContext: Context? = null

    /** Loads the model in the background so the first press does not wait. */
    fun preload(context: Context) {
        appContext = context.applicationContext
        thread(name = "model-preload") {
            try { loadModel() } catch (e: Exception) { EventLog.add("تعذر تحميل النموذج: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun releaseModel() {
        synchronized(modelLock) {
            try { model?.close() } catch (_: Exception) { }
            model = null
        }
    }

    fun start(context: Context, mode: Mode) {
        appContext = context.applicationContext
        stopAndJoin(1500)
        val gen = synchronized(lock) {
            generation += 1
            running = true
            stopDeadline = 0L
            armedUntil = 0L
            activeMode = mode
            generation
        }
        worker = thread(name = "voice-engine") { runLoop(mode, gen) }
    }

    /** Stops recording after [graceMs] (a short tail so the end of the last word is not cut). Does not block. */
    fun stop(graceMs: Long) {
        stopDeadline = SystemClock.elapsedRealtime() + graceMs
        running = false
    }

    fun stopAndJoin(timeoutMs: Long) {
        stop(0)
        val w = worker
        if (w != null && w !== Thread.currentThread()) {
            try { w.join(timeoutMs) } catch (_: InterruptedException) { }
        }
    }

    private fun loadModel(): Model? = synchronized(modelLock) {
        var m = model
        val ctx = appContext
        if (m == null && ctx != null && ModelManager.isInstalled(ctx)) {
            EventLog.add("جارٍ تحميل النموذج…")
            m = Model(ModelManager.modelDir(ctx).absolutePath)
            model = m
            EventLog.add("النموذج جاهز")
        }
        m
    }

    private fun runLoop(mode: Mode, gen: Int) {
        val ctx = appContext ?: return
        var audio: AudioRecord? = null
        var rec: Recognizer? = null
        val effects = ArrayList<AudioEffect>()
        try {
            val m = loadModel()
            if (m == null) {
                EventLog.add("النموذج غير مثبت: نزّله أو استورده أولًا")
                return
            }
            if (generation != gen) return
            val recognizer = Recognizer(m, 16000.0f, CommandParser.grammar(ctx, Prefs.wake(ctx)))
            rec = recognizer
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "الميكروفون غير متاح" }
            val size = maxOf(min, 8192)
            val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            audio = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "تعذر تهيئة الميكروفون" }
            addEffects(record.audioSessionId, effects)
            record.startRecording()
            EventLog.add(if (mode == Mode.PUSH) "أسمعك… تكلم" else "الاستماع الدائم يعمل — قل: ${Prefs.wake(ctx)} ثم الأمر")
            val buffer = ShortArray(size / 2)
            while (generation == gen && (running || SystemClock.elapsedRealtime() < stopDeadline)) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0 && recognizer.acceptWaveForm(buffer, n)) {
                    handle(ctx, mode, JSONObject(recognizer.result).optString("text"))
                }
            }
            if (mode == Mode.PUSH && generation == gen) {
                handle(ctx, mode, JSONObject(recognizer.finalResult).optString("text"))
            }
        } catch (e: Exception) {
            EventLog.add("خطأ: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { audio?.stop() } catch (_: Exception) { }
            audio?.release()
            for (e in effects) { try { e.release() } catch (_: Exception) { } }
            try { rec?.close() } catch (_: Exception) { }
        }
    }

    private fun addEffects(session: Int, out: MutableList<AudioEffect>) {
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.setEnabled(true); out.add(it) } } catch (_: Exception) { }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.setEnabled(true); out.add(it) } } catch (_: Exception) { }
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.setEnabled(true); out.add(it) } } catch (_: Exception) { }
    }

    private fun handle(ctx: Context, mode: Mode, raw: String) {
        val cleaned = CommandParser.normalize(raw.replace("[unk]", " "))
        if (cleaned.isBlank()) {
            if (mode == Mode.PUSH && raw.isNotBlank()) EventLog.add("لم أفهم الأمر")
            return
        }
        val wake = CommandParser.normalize(Prefs.wake(ctx))
        val now = SystemClock.elapsedRealtime()
        val hasWake = cleaned == wake || cleaned.startsWith("$wake ")
        val rest = if (hasWake) cleaned.removePrefix(wake).trim() else cleaned
        if (hasWake && rest.isEmpty()) {
            armedUntil = now + 5000
            EventLog.add("سمعت كلمة التنبيه — قل الأمر الآن")
            return
        }
        val command = CommandParser.parse(ctx, rest)
        val allowed = mode == Mode.PUSH || hasWake || now < armedUntil
        if (command == null) {
            if (allowed) EventLog.add("سمعت: $raw (ليس أمرًا)")
            return
        }
        if (!allowed) {
            EventLog.add("تجاهلت (بدون كلمة التنبيه): $raw")
            return
        }
        armedUntil = 0L
        EventLog.add("سمعت: $raw ← $command")
        val outgoingCommand = when (command) {
            "forward", "back" -> "${command}_${Prefs.seekSeconds(ctx)}"
            else -> command
        }
        CommandSender.send(ctx, outgoingCommand) { result ->
            EventLog.add(result.message)
            if (result.ok) vibrate(ctx)
        }
    }

    private fun vibrate(ctx: Context) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vib.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }
}
