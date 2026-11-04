package com.codex.tvphone

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log shown at the bottom of the screen. */
object EventLog {
    private val lines = ArrayList<String>()

    fun add(message: String) {
        synchronized(lines) {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            lines.add("$stamp  $message")
            while (lines.size > 40) lines.removeAt(0)
        }
    }

    fun text(max: Int = 12): String = synchronized(lines) {
        if (lines.isEmpty()) "لا أحداث بعد" else lines.takeLast(max).reversed().joinToString("\n")
    }
}
