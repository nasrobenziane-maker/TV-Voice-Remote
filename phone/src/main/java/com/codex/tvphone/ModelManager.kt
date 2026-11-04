package com.codex.tvphone

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** Downloads or imports the Vosk Arabic model once; after that everything works offline. */
object ModelManager {
    private const val NAME = "vosk-model-small-ar-tn-0.1-linto"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$NAME.zip"

    @Volatile var status: String = ""
    @Volatile var busy: Boolean = false

    fun modelDir(context: Context) = File(context.filesDir, "vosk-model")
    fun isInstalled(context: Context) = modelDir(context).resolve("am/final.mdl").isFile

    /** Runs [work] on a background thread; [work] returns the final status message. */
    fun runAsync(context: Context, work: () -> String) {
        if (busy) return
        busy = true
        val app = context.applicationContext
        thread(name = "model-op") {
            var ok = false
            try {
                status = work()
                ok = true
            } catch (e: Exception) {
                status = "فشل: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
                EventLog.add(status)
            }
            if (ok) VoiceEngine.preload(app)
        }
    }

    fun download(context: Context): String {
        val archive = File(context.filesDir, "$NAME.zip")
        val partial = File(context.filesDir, "$NAME.zip.part")
        if (!isValidZip(archive)) {
            archive.delete()
            downloadWithResume(partial)
            check(partial.renameTo(archive)) { "تعذر حفظ ملف النموذج" }
        }
        status = "جارٍ تثبيت النموذج…"
        try {
            archive.inputStream().use { installFromStream(context, it) }
        } finally {
            archive.delete()
        }
        return "تم تثبيت النموذج ✓"
    }

    fun importZip(context: Context, uri: Uri): String {
        status = "جارٍ تثبيت النموذج من الملف…"
        context.contentResolver.openInputStream(uri)?.use { installFromStream(context, it) } ?: error("تعذر قراءة الملف")
        return "تم تثبيت النموذج ✓"
    }

    fun importFolder(context: Context, tree: Uri): String {
        status = "جارٍ نسخ النموذج من المجلد…"
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val kids = children(context, tree, root)
        val source = if (kids.any { it.second.first == "am" }) root
        else kids.firstOrNull { it.second.first == "vosk-model" || it.second.first == NAME }?.first
            ?: error("المجلد المحدد لا يحتوي على النموذج (am/final.mdl)")
        val temp = File(context.filesDir, "model-import").apply { deleteRecursively(); check(mkdirs()) { "تعذر تجهيز المجلد" } }
        copyDocumentsToLocal(context, tree, source, temp)
        check(temp.resolve("am/final.mdl").isFile) { "المجلد لا يحتوي نموذجًا صالحًا" }
        val target = modelDir(context)
        target.deleteRecursively()
        check(temp.renameTo(target)) { "تعذر تثبيت النموذج، تحقق من المساحة الحرة" }
        return "تم تثبيت النموذج ✓"
    }

    // ---- ZIP installation -------------------------------------------------------------------

    private fun installFromStream(context: Context, input: InputStream) {
        val temp = File(context.filesDir, "model-extract").apply { deleteRecursively(); check(mkdirs()) { "تعذر تجهيز المجلد" } }
        val tempPath = temp.canonicalFile.toPath()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(temp, entry.name).canonicalFile
                require(out.toPath().startsWith(tempPath)) { "ملف مضغوط غير آمن" }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // The model is either at the top of the ZIP or inside one folder.
        val modelRoot = if (temp.resolve("am/final.mdl").isFile) temp
        else temp.listFiles()?.firstOrNull { it.isDirectory && it.resolve("am/final.mdl").isFile }
        if (modelRoot == null) {
            temp.deleteRecursively()
            error("الملف ليس نموذج Vosk صالحًا (am/final.mdl غير موجود)")
        }
        val target = modelDir(context)
        target.deleteRecursively()
        check(modelRoot.renameTo(target)) { "تعذر تثبيت النموذج، تحقق من المساحة الحرة" }
        temp.deleteRecursively()
    }

    private fun isValidZip(file: File): Boolean = file.isFile && file.length() > 1_000_000L && try {
        ZipFile(file).use { zip -> zip.entries().asSequence().any { it.name.endsWith("am/final.mdl") } }
    } catch (_: Exception) { false }

    // ---- download with resume ---------------------------------------------------------------

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    private fun downloadWithResume(partial: File) {
        val maxAttempts = 5
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                fetchRange(partial)
                if (!isValidZip(partial)) {
                    partial.delete()
                    throw IOException("الملف المحمّل ناقص أو تالف")
                }
                return
            } catch (e: Exception) {
                lastError = e
                status = "انقطع التنزيل (محاولة $attempt/$maxAttempts): ${e.message ?: e.javaClass.simpleName}"
                EventLog.add(status)
                if (attempt < maxAttempts) Thread.sleep(3000L * attempt)
            }
        }
        throw IOException("فشل تنزيل النموذج بعد $maxAttempts محاولات. تحقق من الإنترنت ثم اضغط تنزيل لإكمال ما بقي. ${lastError?.message.orEmpty()}")
    }

    private fun fetchRange(partial: File) {
        var existing = if (partial.isFile) partial.length() else 0L
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TV-Voice-Phone/1.0 Android")
            setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            var total = -1L
            when (code) {
                206 -> total = connection.getHeaderField("Content-Range").orEmpty().substringAfter('/', "").toLongOrNull() ?: -1L
                200 -> { existing = 0L; total = connection.contentLengthLong }
                416 -> {
                    val size = connection.getHeaderField("Content-Range").orEmpty().substringAfter('/', "").toLongOrNull()
                    if (size != null && size == existing) return
                    partial.delete()
                    throw IOException("الملف الجزئي لا يطابق الخادم، سأعيد التنزيل")
                }
                else -> throw IOException("فشل التنزيل (HTTP $code)")
            }
            val append = code == 206
            var done = if (append) existing else 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= 1000) { lastReport = now; report(done, total) }
                    }
                }
            }
            report(done, total)
            if (total > 0 && done != total) throw IOException("أُغلق الاتصال مبكرًا عند ${mb(done)} من ${mb(total)} م.ب")
        } finally {
            connection.disconnect()
        }
    }

    private fun report(done: Long, total: Long) {
        status = if (total > 0) "جارٍ التنزيل: ${done * 100 / total}% (${mb(done)} / ${mb(total)} م.ب)" else "جارٍ التنزيل: ${mb(done)} م.ب"
    }

    // ---- folder import helpers --------------------------------------------------------------

    private fun children(context: Context, tree: Uri, parent: Uri): List<Pair<Uri, Pair<String, String>>> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val result = ArrayList<Pair<Uri, Pair<String, String>>>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(idCol))
                result += uri to (c.getString(nameCol) to c.getString(mimeCol))
            }
        } ?: error("تعذر قراءة المجلد المحدد")
        return result
    }

    private fun copyDocumentsToLocal(context: Context, tree: Uri, source: Uri, target: File) {
        for ((childUri, meta) in children(context, tree, source)) {
            val (name, mime) = meta
            val out = File(target, name).canonicalFile
            check(out.toPath().startsWith(target.canonicalFile.toPath())) { "مسار غير صالح" }
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                out.mkdirs()
                copyDocumentsToLocal(context, tree, childUri, out)
            } else {
                out.parentFile?.mkdirs()
                context.contentResolver.openInputStream(childUri)?.use { input -> FileOutputStream(out).use(input::copyTo) }
                    ?: error("تعذر قراءة $name")
            }
        }
    }
}
