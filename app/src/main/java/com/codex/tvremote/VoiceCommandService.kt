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
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipFile
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
        setModelStatus(null)
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
            setModelStatus("Loading model…")
            val model = Model(modelDir.absolutePath)
            val grammar = """["تشغيل","ابدأ","ابدأ التشغيل","إيقاف","توقف","أوقف","التالي","الفيديو التالي","السابق","الفيديو السابق","تقديم دقيقة","قدم دقيقة","إرجاع دقيقة","ارجع دقيقة","ارفع الصوت","علي الصوت","اخفض الصوت","خفض الصوت","كتم","اكتم الصوت","ألغ الكتم","إلغاء الكتم","تي في تشغيل","تي في إيقاف","تي في التالي","تي في السابق","تي في تقديم دقيقة","تي في إرجاع دقيقة","تي في ارفع الصوت","تي في اخفض الصوت","تي في كتم","تي في ألغ الكتم"]"""
            recognizer = Recognizer(model, 16000.0f, grammar)
            startRecordingLoop()
        } catch (_: DownloadCancelled) {
            // stopped by the user while downloading
        } catch (e: Exception) {
            event("Error: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }
    }

    private class DownloadCancelled : Exception()

    private fun setModelStatus(text: String?) {
        val editor = getSharedPreferences("remote", MODE_PRIVATE).edit()
        if (text == null) editor.remove("model_status") else editor.putString("model_status", text)
        editor.apply()
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    private fun reportProgress(done: Long, total: Long) {
        val text = if (total > 0) "Downloading model: ${done * 100 / total}% (${mb(done)} / ${mb(total)} MB)" else "Downloading model: ${mb(done)} MB"
        setModelStatus(text)
        updateNotification(text)
    }

    /** A truncated ZIP has no central directory at its end, so opening it fails: this catches incomplete downloads. */
    private fun isValidZip(file: File): Boolean = file.isFile && file.length() > 1_000_000L && try {
        ZipFile(file).use { zip -> zip.entries().asSequence().any { it.name.endsWith("am/final.mdl") } }
    } catch (_: Exception) { false }

    private fun waitOrCancel(millis: Long) {
        var left = millis
        while (left > 0) {
            if (destroyed) throw DownloadCancelled()
            Thread.sleep(500)
            left -= 500
        }
    }

    private fun downloadAndExtract() {
        val archive = File(filesDir, "$modelName.zip")
        val partial = File(filesDir, "$modelName.zip.part")
        // Keep the archive in filesDir, not cacheDir: Android may clear cache while a
        // large model is downloading. The .part file is kept between attempts so the download can resume.
        if (!isValidZip(archive)) {
            archive.delete()
            downloadWithResume(partial)
            check(partial.renameTo(archive)) { "Could not save model archive in app storage" }
        }

        updateNotification("Installing Arabic voice model…")
        setModelStatus("Installing model…")
        try {
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
            archive.delete()
            throw e
        }
    }

    /** Downloads into [partial], retrying and resuming from where it stopped. Returns only when it holds a complete, valid ZIP. */
    private fun downloadWithResume(partial: File) {
        val maxAttempts = 5
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            if (destroyed) throw DownloadCancelled()
            try {
                fetchRange(partial)
                if (!isValidZip(partial)) {
                    partial.delete()
                    throw IOException("Downloaded file is incomplete or corrupt")
                }
                return
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val message = "Download interrupted (attempt $attempt/$maxAttempts): ${e.message ?: e.javaClass.simpleName}"
                event(message)
                if (attempt < maxAttempts) {
                    setModelStatus("$message. Retrying…")
                    waitOrCancel(3_000L * attempt)
                }
            }
        }
        val reason = lastError?.message?.let { " ($it)" }.orEmpty()
        throw IOException("Model download failed after $maxAttempts attempts. Check internet and free storage, then press Start voice control to resume.$reason")
    }

    /** One download attempt: continues [partial] with an HTTP Range request when possible, otherwise restarts from zero. */
    private fun fetchRange(partial: File) {
        var existing = if (partial.isFile) partial.length() else 0L
        val connection = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TV-Voice-Remote/1.0 Android")
            setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            var total = -1L
            when (code) {
                206 -> {
                    val range = connection.getHeaderField("Content-Range").orEmpty()
                    total = range.substringAfter('/', "").toLongOrNull() ?: -1L
                    event("Resuming download from ${mb(existing)} MB")
                }
                200 -> {
                    // The server ignored the Range request: start again from the beginning.
                    existing = 0L
                    total = connection.contentLengthLong
                }
                416 -> {
                    val range = connection.getHeaderField("Content-Range").orEmpty()
                    val size = range.substringAfter('/', "").toLongOrNull()
                    if (size != null && size == existing) return // already complete
                    partial.delete()
                    throw IOException("Saved partial download did not match the server; restarting")
                }
                else -> throw IOException("Model download failed (HTTP $code)")
            }
            val append = code == 206
            var done = if (append) existing else 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (destroyed) throw DownloadCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= 1000) { lastReport = now; reportProgress(done, total) }
                    }
                }
            }
            reportProgress(done, total)
            if (total > 0 && done != total) throw IOException("Connection closed early at ${mb(done)} of ${mb(total)} MB")
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
        setModelStatus(null)
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
