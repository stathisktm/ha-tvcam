package com.pestpoint.radioauto.tvcam.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pestpoint.radioauto.tvcam.overlay.KeepAliveService

/** One-time (or whenever it changes) device setup for the Home Assistant connection — the
 *  primary way this gets configured, matching this project's established adb-driven device-setup
 *  convention rather than requiring the Setup activity to be opened by hand on the TV. */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Earliest possible moment to get the app running persistently — no need to wait for the
        // first SHOW_CAMERA or a reboot.
        context.startForegroundService(Intent(context, KeepAliveService::class.java))

        val prefs = HaConfigPrefs(context)
        intent.getStringExtra(EXTRA_HA_URL)?.takeIf { it.isNotBlank() }?.let { prefs.haBaseUrl = it }
        intent.getStringExtra(EXTRA_HA_TOKEN)?.takeIf { it.isNotBlank() }?.let { prefs.haToken = it }
    }

    companion object {
        const val ACTION_CONFIGURE = "com.pestpoint.radioauto.tvcam.action.CONFIGURE"
        const val EXTRA_HA_URL = "ha_url"
        const val EXTRA_HA_TOKEN = "ha_token"
    }
}
