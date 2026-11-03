package com.codex.tvremote

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

/** Copies the Vosk model to/from a user-selected shared folder so it survives app uninstall. */
object ModelStore {
    private const val MODEL_FOLDER = "vosk-model"

    fun localModelDir(context: Context) = File(context.filesDir, MODEL_FOLDER)

    fun backup(context: Context, tree: Uri): String {
        val source = localModelDir(context)
        check(source.resolve("am/final.mdl").isFile) { "Download the voice model first by starting voice control." }
        val root = documentUri(tree, DocumentsContract.getTreeDocumentId(tree))
        findChild(context, tree, root, MODEL_FOLDER)?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
        val target = DocumentsContract.createDocument(context.contentResolver, root, DocumentsContract.Document.MIME_TYPE_DIR, MODEL_FOLDER)
            ?: error("Could not create model folder in selected location")
        copyLocalToDocuments(context, tree, source, target)
        return "Model backed up. After reinstall, select Restore and choose this same folder."
    }

    fun restore(context: Context, tree: Uri): String {
        val root = documentUri(tree, DocumentsContract.getTreeDocumentId(tree))
        val source = findChild(context, tree, root, MODEL_FOLDER) ?: error("No vosk-model folder in the selected location")
        val target = localModelDir(context)
        target.deleteRecursively(); target.mkdirs()
        copyDocumentsToLocal(context, tree, source, target)
        check(target.resolve("am/final.mdl").isFile) { "Selected folder does not contain a valid Vosk model" }
        return "Model restored from shared storage. You can start voice control without downloading it."
    }

    private fun documentUri(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun findChild(context: Context, tree: Uri, parent: Uri, wanted: String): Uri? =
        children(context, tree, parent).firstOrNull { it.second.first == wanted }?.first

    private fun children(context: Context, tree: Uri, parent: Uri): List<Pair<Uri, Pair<String, String>>> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val result = ArrayList<Pair<Uri, Pair<String, String>>>()
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getString(idCol)
                result += documentUri(tree, id) to (c.getString(nameCol) to c.getString(mimeCol))
            }
        } ?: error("Could not read the selected folder")
        return result
    }

    private fun copyLocalToDocuments(context: Context, tree: Uri, source: File, target: Uri) {
        source.walkTopDown().filter { it != source }.forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath
            val parts = relative.split('/')
            var parent = target
            parts.dropLast(1).forEach { dirName ->
                parent = findChild(context, tree, parent, dirName)
                    ?: DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, dirName)
                    ?: error("Could not create folder $dirName")
            }
            val name = parts.last()
            if (file.isDirectory) {
                if (findChild(context, tree, parent, name) == null) {
                    DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
                        ?: error("Could not create folder $name")
                }
            } else {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
                val outUri = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
                    ?: error("Could not create model file $name")
                file.inputStream().use { input -> context.contentResolver.openOutputStream(outUri, "w")?.use { output -> input.copyTo(output) } ?: error("Could not write $name") }
            }
        }
    }

    private fun copyDocumentsToLocal(context: Context, tree: Uri, source: Uri, target: File) {
        for ((childUri, meta) in children(context, tree, source)) {
            val (name, mime) = meta
            val out = File(target, name).canonicalFile
            check(out.toPath().startsWith(target.canonicalFile.toPath())) { "Invalid model path" }
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                out.mkdirs(); copyDocumentsToLocal(context, tree, childUri, out)
            } else {
                out.parentFile?.mkdirs()
                context.contentResolver.openInputStream(childUri)?.use { input -> FileOutputStream(out).use(input::copyTo) }
                    ?: error("Could not read $name")
            }
        }
    }
}
