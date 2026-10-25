package com.pestpoint.radioauto.tvcam.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Plays a multipart MJPEG stream (HA's `/api/camera_proxy_stream/<entity>`) as a sequence of
 *  decoded frames — ExoPlayer has no MJPEG support at all. Frames are cut out by the JPEG
 *  start/end markers themselves rather than by parsing the multipart headers, which differ
 *  between camera platforms (some omit Content-Length) — FF D9 can't occur inside JPEG
 *  entropy-coded data thanks to byte stuffing, so the end marker is unambiguous. */
object MjpegStreamReader {
    private const val TAG = "MjpegStreamReader"

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Runs until the stream ends, fails, or the calling coroutine is cancelled (the overlay's
     *  teardown). [onFrame] is invoked on the main thread. Never throws except cancellation. */
    suspend fun play(url: String, token: String?, onFrame: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "play: HTTP ${response.code} for $url")
                    return@use
                }
                val input = BufferedInputStream(response.body?.byteStream() ?: return@use, 64 * 1024)
                val frame = ByteArrayOutputStream(256 * 1024)
                var inFrame = false
                var prev = -1
                while (currentCoroutineContext().isActive) {
                    val b = input.read()
                    if (b < 0) break
                    if (!inFrame) {
                        if (prev == 0xFF && b == 0xD8) {
                            frame.reset()
                            frame.write(0xFF)
                            frame.write(0xD8)
                            inFrame = true
                        }
                    } else {
                        frame.write(b)
                        if (prev == 0xFF && b == 0xD9) {
                            inFrame = false
                            val bytes = frame.toByteArray()
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                                withContext(Dispatchers.Main) { onFrame(bitmap) }
                            }
                        }
                    }
                    prev = b
                }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Log.w(TAG, "play: stream ended", it) }
    }
}
