package com.codex.tvphone

import android.content.Context

object Prefs {
    const val DEFAULT_WAKE = "تي في"
    private fun p(c: Context) = c.getSharedPreferences("phone", Context.MODE_PRIVATE)

    fun tvIp(c: Context): String = p(c).getString("tv_ip", "").orEmpty()
    fun pin(c: Context): String = p(c).getString("pin", "").orEmpty()
    fun wake(c: Context): String = p(c).getString("wake", DEFAULT_WAKE).orEmpty().ifBlank { DEFAULT_WAKE }
    fun mode(c: Context): String = p(c).getString("mode", "push").orEmpty()
    fun seekSeconds(c: Context): Int = p(c).getInt("seek_seconds", 10).coerceIn(5, 60)
    fun setSeekSeconds(c: Context, seconds: Int) { p(c).edit().putInt("seek_seconds", seconds.coerceIn(5, 60)).apply() }

    fun save(c: Context, ip: String, pin: String, wake: String) {
        p(c).edit().putString("tv_ip", ip).putString("pin", pin).putString("wake", wake.ifBlank { DEFAULT_WAKE }).apply()
    }

    fun setMode(c: Context, mode: String) {
        p(c).edit().putString("mode", mode).apply()
    }
}
