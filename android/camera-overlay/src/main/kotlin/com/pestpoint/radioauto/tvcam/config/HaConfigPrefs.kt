package com.pestpoint.radioauto.tvcam.config

import android.content.Context

/** Home Assistant connection details — base URL and a long-lived access token. Same
 *  single-value-per-key SharedPreferences shape as the tv/app modules' own settings classes
 *  (e.g. PlaybackSettingsPrefs), just for a different pair of values. */
class HaConfigPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ha_config_prefs", Context.MODE_PRIVATE)

    var haBaseUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().putString(KEY_URL, value?.trim()?.trimEnd('/')).apply()

    var haToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value?.trim()).apply()

    private companion object {
        const val KEY_URL = "ha_base_url"
        const val KEY_TOKEN = "ha_access_token"
    }
}
