package com.codex.tvremote

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Adds a line to the "Recent voice events" list shown on the TV screen. */
object TvEvents {
    fun add(context: Context, message: String) {
        val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
        synchronized(this) {
            val old = prefs.getString("events", "").orEmpty().lines().filter { it.isNotBlank() }
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            prefs.edit().putString("events", (old + "$stamp  $message").takeLast(30).joinToString("\n")).apply()
        }
    }
}
