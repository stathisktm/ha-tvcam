package com.pestpoint.radioauto.tvcam.overlay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Fires the ok_webhook_url callback (a Home Assistant webhook URL, typically) when the user
 *  presses OK/Enter on an interactive overlay — see [OverlayWindowController]. Fire-and-forget:
 *  errors are logged, never surfaced (same silent idiom used everywhere else in this app), since
 *  there's nothing useful to show on a small notification box for "the webhook call failed". */
object OkButtonListener {
    private const val TAG = "OkButtonListener"

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient()

    fun fire(url: String) {
        Log.d(TAG, "fire: $url")
        scope.launch {
            runCatching {
                val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
                client.newCall(request).execute().close()
            }.onFailure { Log.e(TAG, "fire: webhook call failed", it) }
        }
    }
}
