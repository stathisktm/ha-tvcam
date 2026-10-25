package com.pestpoint.radioauto.tvcam.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts [KeepAliveService] again after the TV reboots, so the app doesn't need to be manually
 *  reopened for Home Assistant's SHOW/HIDE triggers to keep working. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startForegroundService(Intent(context, KeepAliveService::class.java))
    }
}
