package com.pestpoint.radioauto.tvcam.ha

import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Resolves a Home Assistant camera entity's *current* live HLS stream URL. There is no plain REST
 * endpoint for this — it requires the WebSocket API's auth handshake followed by a `camera/stream`
 * request (see HaMessages.kt for the exact message shapes). A fresh connection is opened per call
 * and closed once the URL is in hand — HA's issued stream URLs are short-lived, session-style
 * tokens, so there's nothing worth keeping a persistent socket open for.
 */
class HaCameraStreamClient(
    private val baseUrl: String,
    private val accessToken: String,
    private val client: OkHttpClient = OkHttpClient(),
) {

    /** Never throws — returns null on any failure (HA unreachable, bad token, the camera entity
     *  has no stream support, or a timeout), matching this project's established silent-fallback
     *  idiom, since there's no UI on a TV overlay to show an error on. */
    suspend fun resolveHlsUrl(entityId: String, timeoutMs: Long = 8_000L): String? {
        Log.d(TAG, "resolveHlsUrl: connecting to ${toWebSocketUrl(baseUrl)} for $entityId")
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val request = Request.Builder().url(toWebSocketUrl(baseUrl)).build()
                val webSocket = client.newWebSocket(request, Listener(entityId, cont))
                cont.invokeOnCancellation { webSocket.cancel() }
            }
        }
        Log.d(TAG, "resolveHlsUrl: result = $result")
        return result
    }

    private inner class Listener(
        private val entityId: String,
        private val cont: CancellableContinuation<String?>,
    ) : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "onMessage: $text")
            val envelope = runCatching { json.decodeFromString<HaEnvelope>(text) }.getOrNull() ?: return
            when (envelope.type) {
                "auth_required" -> webSocket.send(json.encodeToString(HaAuthMessage(access_token = accessToken)))
                "auth_ok" -> webSocket.send(json.encodeToString(HaStreamRequest(id = REQUEST_ID, entity_id = entityId)))
                "auth_invalid" -> finish(webSocket, null)
                "result" -> if (envelope.id == REQUEST_ID) {
                    val resolved = envelope.result?.url
                        ?.takeIf { envelope.success == true }
                        ?.let { resolvePath(baseUrl, it) }
                    finish(webSocket, resolved)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "onFailure: response=$response", t)
            finish(webSocket, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosed: code=$code reason=$reason")
            finish(webSocket, null)
        }

        // onMessage/onFailure/onClosed can each try to complete this — OkHttp may call onFailure
        // after a partial exchange, so this guards against resuming the same continuation twice.
        private fun finish(webSocket: WebSocket, value: String?) {
            if (cont.isActive) cont.resumeWith(Result.success(value))
            webSocket.close(1000, "done")
        }
    }

    private companion object {
        const val TAG = "HaCameraStreamClient"
        const val REQUEST_ID = 1

        // encodeDefaults = true is load-bearing here: HaAuthMessage.type and
        // HaStreamRequest.type/format all carry default values (so the call sites don't have to
        // repeat them), but kotlinx.serialization omits any field left at its default when
        // encoding unless told otherwise — which silently dropped "type" from the outgoing auth
        // message entirely, and HA rejected it with "required key not provided at 'type'".
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun toWebSocketUrl(base: String): String =
            base.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/api/websocket"

        fun resolvePath(base: String, path: String): String =
            if (path.startsWith("http")) path else base.trimEnd('/') + path
    }
}
