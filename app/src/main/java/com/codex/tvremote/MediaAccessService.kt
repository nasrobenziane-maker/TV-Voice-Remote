package com.codex.tvremote

import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.content.ComponentName

class MediaAccessService : NotificationListenerService() {
    companion object {
        @Volatile var instance: MediaAccessService? = null
    }

    override fun onListenerConnected() { super.onListenerConnected(); instance = this }
    override fun onListenerDisconnected() { instance = null; super.onListenerDisconnected() }
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun execute(command: String): String {
        val session = try {
            (getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(this, MediaAccessService::class.java))
                .sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .firstOrNull()
        } catch (_: SecurityException) { null }

        return when (command) {
            "play" -> session?.let { it.transportControls.play(); "Play sent to ${appName(it)}" } ?: "No active media session"
            "pause" -> session?.let { it.transportControls.pause(); "Pause sent to ${appName(it)}" } ?: "No active media session"
            "next" -> session?.let { it.transportControls.skipToNext(); "Next sent to ${appName(it)}" } ?: "No active media session"
            "previous" -> session?.let { it.transportControls.skipToPrevious(); "Previous sent to ${appName(it)}" } ?: "No active media session"
            "forward" -> seek(session, 60_000)
            "back" -> seek(session, -60_000)
            "volume_up" -> { audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); "Volume up" }
            "volume_down" -> { audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); "Volume down" }
            "mute" -> mute()
            "unmute" -> unmute()
            else -> "Unknown command"
        }
    }

    private fun seek(controller: MediaController?, delta: Long): String {
        val state = controller?.playbackState ?: return "No active media session"
        if ((state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) return "This app does not allow seeking"
        val durationValue = controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: Long.MAX_VALUE
        val duration = if (durationValue > 0L) durationValue else Long.MAX_VALUE
        val position = (state.position + delta).coerceAtLeast(0).coerceAtMost(duration)
        controller.transportControls.seekTo(position)
        return if (delta > 0) "Forward one minute" else "Back one minute"
    }

    private fun audio() = getSystemService(AUDIO_SERVICE) as AudioManager
    private fun mute(): String {
        val a = audio(); val prefs = getSharedPreferences("remote", MODE_PRIVATE)
        if (a.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) prefs.edit().putInt("previous_volume", a.getStreamVolume(AudioManager.STREAM_MUSIC)).apply()
        a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        return "Mute requested"
    }
    private fun unmute(): String {
        val a = audio(); val old = getSharedPreferences("remote", MODE_PRIVATE).getInt("previous_volume", 5)
        a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        if (a.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) a.setStreamVolume(AudioManager.STREAM_MUSIC, old.coerceAtLeast(1), AudioManager.FLAG_SHOW_UI)
        return "Unmute requested"
    }
    private fun appName(c: MediaController) = c.packageName.substringAfterLast('.')
}
