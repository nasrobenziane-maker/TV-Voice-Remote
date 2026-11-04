package com.codex.tvphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/** Keeps the always-listening mode alive in the background with a visible notification. */
class ListeningService : Service() {
    companion object {
        @Volatile var active = false
        const val ACTION_STOP = "com.codex.tvphone.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= 30) startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(41, n)
            active = true
        } catch (e: Exception) {
            EventLog.add("تعذر بدء الاستماع: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) VoiceEngine.start(this, VoiceEngine.Mode.ALWAYS)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        VoiceEngine.stopAndJoin(2000)
        super.onDestroy()
    }

    private fun createChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("listening", "الاستماع الدائم", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "إيقاف", stop
        ).build()
        return Notification.Builder(this, "listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TV Voice Phone")
            .setContentText("يستمع الآن — قل: ${Prefs.wake(this)} ثم الأمر")
            .setOngoing(true)
            .addAction(action)
            .build()
    }
}
