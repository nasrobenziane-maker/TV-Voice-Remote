package com.codex.tvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Receives commands from the phone app over the local Wi-Fi (no internet needed).
 * Protocol, one line per command:  "TVR1 <pin> <command>\n"  ->  "OK <message>\n" or "ERR <reason>\n".
 */
class PhoneLinkService : Service() {
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    // per remote address: [failed attempts, locked-until millis]
    private val failures = ConcurrentHashMap<String, LongArray>()
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val n = notification("Waiting for the phone on port ${LinkConfig.PORT}")
        if (Build.VERSION.SDK_INT >= 34) startForeground(78, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(78, n)
        running = true
        thread(name = "phone-link-server") { serve() }
        registerNsd()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) { }
        try { nsdListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) { }
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun serve() {
        while (running) {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(LinkConfig.PORT)) }
                server = s
                while (running) {
                    val client = s.accept()
                    pool.execute { handle(client) }
                }
            } catch (_: Exception) {
                if (!running) return
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 10_000
                val ip = socket.inetAddress?.hostAddress ?: "?"
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (true) {
                    val line = readLine(input) ?: break
                    val reply = process(ip, line)
                    output.write((reply + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (_: Exception) { }
        }
    }

    /** Reads one line (max 256 bytes); returns null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8")
            if (buf.size() >= 256) throw IOException("line too long")
            buf.write(b)
        }
    }

    private fun process(ip: String, line: String): String {
        val parts = line.trim().split(' ', limit = 3)
        if (parts.size < 3 || parts[0] != LinkConfig.PROTOCOL) return "ERR bad_request"
        val now = System.currentTimeMillis()
        val state = failures[ip]
        if (state != null && state[1] > now) return "ERR locked"
        val pinOk = MessageDigest.isEqual(parts[1].toByteArray(Charsets.UTF_8), LinkConfig.pin(this).toByteArray(Charsets.UTF_8))
        if (!pinOk) {
            val count = (state?.get(0) ?: 0L) + 1
            failures[ip] = if (count >= 5) longArrayOf(0L, now + 60_000L) else longArrayOf(count, 0L)
            TvEvents.add(this, "Phone $ip: wrong PIN")
            return "ERR bad_pin"
        }
        failures.remove(ip)
        val command = parts[2].trim()
        if (!CommandExecutor.isValidCommand(command)) return "ERR unknown_command"
        val result = try { CommandExecutor.run(applicationContext, command) } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
        if (command != "ping") TvEvents.add(this, "Phone: $command -> $result")
        return "OK " + result.replace('\n', ' ')
    }

    private fun registerNsd() {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "TVVoiceRemote"
                serviceType = "_tvvoice._tcp."
                port = LinkConfig.PORT
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
            }
            val manager = getSystemService(NSD_SERVICE) as NsdManager
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = manager
            nsdListener = listener
        } catch (_: Exception) { }
    }

    private fun createChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("phone_link", "Phone link", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Lets the phone app send commands to this TV"
            }
        )
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, "phone_link")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("TV Voice Remote - phone link")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
