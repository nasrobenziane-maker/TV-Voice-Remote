package com.codex.tvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class VoiceCommandService : Service() {
    @Volatile private var running = false
    @Volatile private var destroyed = false
    private var recorder: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private val modelName = "vosk-model-small-ar-tn-0.1-linto"
    private val modelUrl = "https://alphacephei.com/vosk/models/$modelName.zip"
    private val modelDir by lazy { ModelStore.localModelDir(this) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(77, notification("Preparing offline Arabic recognition…"))
        getSharedPreferences("remote", MODE_PRIVATE).edit().putBoolean("listening", true).apply()
        thread(name = "vosk-model-init") { prepareAndListen() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        running = false
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.release(); recorder = null
        recognizer?.close(); recognizer = null
        getSharedPreferences("remote", MODE_PRIVATE).edit().putBoolean("listening", false).apply()
        super.onDestroy()
    }

    private fun prepareAndListen() {
        try {
            if (!modelDir.resolve("am/final.mdl").isFile) downloadAndExtract()
            if (destroyed) return
            val model = Model(modelDir.absolutePath)
            val grammar = """["تشغيل","ابدأ","ابدأ التشغيل","إيقاف","توقف","أوقف","التالي","الفيديو التالي","السابق","الفيديو السابق","تقديم دقيقة","قدم دقيقة","إرجاع دقيقة","ارجع دقيقة","ارفع الصوت","علي الصوت","اخفض الصوت","خفض الصوت","كتم","اكتم الصوت","ألغ الكتم","إلغاء الكتم","تي في تشغيل","تي في إيقاف","تي في التالي","تي في السابق","تي في تقديم دقيقة","تي في إرجاع دقيقة","تي في ارفع الصوت","تي في اخفض الصوت","تي في كتم","تي في ألغ الكتم"]"""
            recognizer = Recognizer(model, 16000.0f, grammar)
            startRecordingLoop()
        } catch (e: Exception) {
            event("Error: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun downloadAndExtract() {
        updateNotification("Downloading Arabic voice model (158 MB)…")
        // Keep the archive in filesDir, not cacheDir: Android may clear cache while a
        // large model is downloading, which used to leave ZipInputStream with no file.
        val archive = File(filesDir, "$modelName.zip")
        val partial = File(filesDir, "$modelName.zip.part")
        val connection = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 45_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TV-Voice-Remote/1.0 Android")
        }
        try {
            connection.connect()
            val response = connection.responseCode
            check(response in 200..299) { "Model download failed (HTTP $response)" }
            partial.delete()
            connection.inputStream.use { input -> FileOutputStream(partial).use { output -> input.copyTo(output) } }
            check(partial.isFile && partial.length() > 1_000_000L) {
                "Model download incomplete (${partial.length()} bytes). Check internet and free storage, then try again."
            }
            if (archive.exists()) check(archive.delete()) { "Could not replace previous model download" }
            check(partial.renameTo(archive)) { "Could not save model archive in app storage" }

            updateNotification("Installing Arabic voice model…")
            val temp = File(filesDir, "model-extract").apply { deleteRecursively(); check(mkdirs()) }
            ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val rel = entry.name.removePrefix("$modelName/")
                    if (rel.isNotBlank()) {
                        val out = File(temp, rel).canonicalFile
                        require(out.toPath().startsWith(temp.canonicalFile.toPath())) { "Unsafe model archive" }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            check(temp.resolve("am/final.mdl").isFile) { "Downloaded file is not a valid Arabic Vosk model ZIP" }
            modelDir.deleteRecursively()
            check(temp.renameTo(modelDir)) { "Could not install model; check free storage" }
            archive.delete()
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun startRecordingLoop() {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "Microphone is unavailable" }
        val bufferSize = maxOf(min, 4096)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        recorder = audio
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }
        running = true
        updateNotification("Listening offline for Arabic commands")
        audio.startRecording()
        val buffer = ShortArray(bufferSize / 2)
        while (running) {
            val count = audio.read(buffer, 0, buffer.size)
            if (count > 0 && recognizer?.acceptWaveForm(buffer, count) == true) {
                val text = JSONObject(recognizer!!.result).optString("text")
                if (text.isNotBlank()) handleRecognized(text)
            }
        }
    }

    private fun handleRecognized(raw: String) {
        val text = normalize(raw)
        val commandText = text.removePrefix("تي في ").trim()
        val command = when {
            commandText.contains("التالي") -> "next"
            commandText.contains("السابق") -> "previous"
            commandText.contains("تقديم دقيقة") || commandText.contains("قدم دقيقة") -> "forward"
            commandText.contains("ارجاع دقيقة") || commandText.contains("ارجع دقيقة") -> "back"
            commandText.contains("ارفع الصوت") || commandText.contains("علي الصوت") -> "volume_up"
            commandText.contains("اخفض الصوت") || commandText.contains("خفض الصوت") -> "volume_down"
            commandText.contains("الغ الكتم") || commandText.contains("الغاء الكتم") -> "unmute"
            commandText == "كتم" || commandText.contains("اكتم الصوت") -> "mute"
            commandText.contains("تشغيل") || commandText.contains("ابدا") -> "play"
            commandText.contains("ايقاف") || commandText.contains("توقف") || commandText.contains("اوقف") -> "pause"
            else -> null
        }
        event("Heard: $raw" + if (command == null) " (no command)" else " → ${command}")
        if (command != null) {
            val result = MediaAccessService.instance?.execute(command) ?: "Enable media access in Settings"
            event(result)
            vibrate()
        }
    }

    private fun normalize(s: String): String = s.lowercase(Locale.ROOT).replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace(Regex("\\s+"), " ").trim()
    private fun event(message: String) {
        val prefs = getSharedPreferences("remote", MODE_PRIVATE)
        val old = prefs.getString("events", "").orEmpty().lines().filter { it.isNotBlank() }
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        prefs.edit().putString("events", (old + "$stamp  $message").takeLast(30).joinToString("\n")).apply()
    }
    private fun vibrate() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") vib.vibrate(70)
        } catch (_: Exception) { }
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("voice_remote", "Voice remote", NotificationManager.IMPORTANCE_LOW).apply { description = "Visible while voice commands are listening" })
    }
    private fun notification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "voice_remote") else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("TV Voice Remote").setContentText(text).setOngoing(true).build()
    }
    private fun updateNotification(text: String) = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(77, notification(text))
}
