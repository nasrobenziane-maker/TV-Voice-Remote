package com.codex.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the phone-link receiver when the TV boots, so the phone works without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try { context.startForegroundService(Intent(context, PhoneLinkService::class.java)) } catch (_: Exception) { }
        }
    }
}
