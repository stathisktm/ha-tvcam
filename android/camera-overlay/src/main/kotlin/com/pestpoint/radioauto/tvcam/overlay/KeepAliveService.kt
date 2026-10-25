package com.pestpoint.radioauto.tvcam.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.pestpoint.radioauto.tvcam.R

/**
 * Does nothing but exist. A separate, always-running foreground service from
 * [CameraOverlayService] (which still only runs on-demand while actively showing/resolving
 * something) — its only job is keeping this app's process alive and "recently used" so a
 * background-only Android TV box doesn't fully evict it from memory, keeping SHOW/HIDE triggers
 * responding instantly instead of paying a cold-start penalty (or missing the trigger entirely if
 * the process was gone). START_STICKY means the OS restarts it if it's ever killed under memory
 * pressure; [com.pestpoint.radioauto.tvcam.overlay.BootReceiver] restarts it after a reboot.
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Ενεργή σύνδεση", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 2
    }
}
