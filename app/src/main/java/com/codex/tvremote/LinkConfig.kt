package com.codex.tvremote

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** Settings shared by the phone link: TCP port, protocol tag and the pairing PIN. */
object LinkConfig {
    const val PORT = 8765
    const val PROTOCOL = "TVR1"

    /** A random 6-digit PIN created once and kept; the phone must send it with every command. */
    fun pin(context: Context): String {
        val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
        prefs.getString("link_pin", null)?.let { return it }
        val pin = (SecureRandom().nextInt(900_000) + 100_000).toString()
        prefs.edit().putString("link_pin", pin).apply()
        return pin
    }

    /** The TV's address on the local network (what the phone must connect to). */
    fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) { null }
}
