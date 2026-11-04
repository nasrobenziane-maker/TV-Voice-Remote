package com.codex.tvphone

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

data class SendResult(val ok: Boolean, val message: String)

/** Sends one command to the TV over the local network: "TVR1 <pin> <command>". */
object CommandSender {
    private const val PORT = 8765
    private val executor = Executors.newSingleThreadExecutor()

    fun send(context: Context, command: String, onResult: (SendResult) -> Unit) {
        val app = context.applicationContext
        executor.execute { onResult(sendBlocking(app, command)) }
    }

    private fun sendBlocking(context: Context, command: String): SendResult {
        val ip = Prefs.tvIp(context).trim()
        val pin = Prefs.pin(context).trim()
        if (ip.isEmpty() || pin.isEmpty()) return SendResult(false, "أدخل عنوان IP للتلفاز والرمز السري أولًا")
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, PORT), 3000)
                socket.soTimeout = 5000
                val out = socket.getOutputStream()
                out.write("TVR1 $pin $command\n".toByteArray(Charsets.UTF_8))
                out.flush()
                val reply = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine() ?: "ERR no_reply"
                when {
                    reply.startsWith("OK ") -> SendResult(true, reply.removePrefix("OK "))
                    reply == "ERR bad_pin" -> SendResult(false, "الرمز السري خاطئ")
                    reply == "ERR locked" -> SendResult(false, "محاولات خاطئة كثيرة، انتظر دقيقة")
                    else -> SendResult(false, reply)
                }
            }
        } catch (e: Exception) {
            SendResult(false, "تعذر الاتصال بالتلفاز: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
