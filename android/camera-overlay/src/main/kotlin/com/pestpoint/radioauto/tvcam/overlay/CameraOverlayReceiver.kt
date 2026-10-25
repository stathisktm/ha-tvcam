package com.pestpoint.radioauto.tvcam.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Forwards SHOW_CAMERA/HIDE_CAMERA straight to the service — identical shape to the tv module's
 *  PlaybackReceiver, the entry point Home Assistant's adb_command integration actually calls. */
class CameraOverlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Opportunistically (re)starts KeepAliveService on every real trigger — the app should
        // stay alive from the very first time Home Assistant ever talks to it, without needing a
        // manual app open (which would disturb the TV screen) or waiting for a reboot. A no-op if
        // it's already running.
        context.startForegroundService(Intent(context, KeepAliveService::class.java))

        val serviceIntent = Intent(context, CameraOverlayService::class.java).apply {
            action = intent.action
            intent.extras?.let { putExtras(it) }
        }
        context.startForegroundService(serviceIntent)
    }
}
