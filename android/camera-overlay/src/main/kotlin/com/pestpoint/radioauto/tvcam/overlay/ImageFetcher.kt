package com.pestpoint.radioauto.tvcam.overlay

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches and decodes a plain photo URL — works for an arbitrary image URL or an HA camera's own
 *  `camera_proxy` snapshot URL alike, no HA WebSocket handshake involved at all (that's only
 *  needed for a *live* stream, see HaCameraStreamClient). No Coil dependency for one use site —
 *  the OkHttpClient already pulled in for the HA client covers this too. */
object ImageFetcher {
    private val client = OkHttpClient()

    /** Never throws — null on any failure (unreachable URL, not an image, decode failure), same
     *  silent idiom used everywhere else in this app. */
    suspend fun fetch(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.byteStream()?.use(BitmapFactory::decodeStream)
            }
        }.getOrNull()
    }

    /** Like [fetch], but keeps animation: an animated WebP/GIF comes back as an
     *  AnimatedImageDrawable (API 28+, the caller starts it), anything else — or an older OS — as
     *  a plain static BitmapDrawable of its first frame. Never throws, null on any failure. */
    suspend fun fetchDrawable(url: String, resources: Resources): Drawable? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            val bytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()
            } ?: return@runCatching null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { BitmapDrawable(resources, it) }
            }
        }.getOrNull()
    }

    fun startIfAnimated(drawable: Drawable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
    }

    fun stopIfAnimated(drawable: Drawable?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.stop()
        }
    }
}
