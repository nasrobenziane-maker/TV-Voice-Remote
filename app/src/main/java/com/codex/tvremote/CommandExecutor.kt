package com.codex.tvremote

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Runs one command name (the same names the voice service uses) on the TV. */
object CommandExecutor {
    val COMMANDS = setOf(
        "ping", "play", "pause", "next", "previous", "forward", "back",
        "volume_up", "volume_down", "mute", "unmute", "open_youtube"
    )

    private val youtubePackages = listOf("com.google.android.youtube.tv", "com.google.android.youtube")

    fun run(context: Context, command: String): String = when (command) {
        "ping" -> "pong"
        "open_youtube" -> openYoutube(context)
        else -> MediaAccessService.instance?.execute(command) ?: "Enable media access in Settings"
    }

    private fun openYoutube(context: Context): String {
        val pm = context.packageManager
        for (pkg in youtubePackages) {
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
            return try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                if (Settings.canDrawOverlays(context)) "YouTube opened"
                else "YouTube requested (if nothing opens, allow \"Display over other apps\" for TV Voice Remote - see README)"
            } catch (e: Exception) {
                "Could not open YouTube: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return "YouTube is not installed on this TV"
    }
}
