package com.pestpoint.radioauto.tvcam.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** Plain OkHttp-backed [Downloader] — the only piece of HTTP plumbing NewPipeExtractor needs us
 *  to supply. Identical to the tv module's own copy (separate Gradle modules, no shared library
 *  to put this in instead). */
class NewPipeDownloaderImpl private constructor(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val dataToSend = request.dataToSend()
        val body = dataToSend?.toRequestBody(null, 0, dataToSend.size)

        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)

        for ((key, values) in request.headers()) {
            if (values.isEmpty()) continue
            builder.removeHeader(key)
            for (value in values) builder.addHeader(key, value)
        }

        val response = client.newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val bodyString = response.body?.string()
        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), bodyString, latestUrl)
    }

    companion object {
        fun create(client: OkHttpClient): NewPipeDownloaderImpl = NewPipeDownloaderImpl(client)
    }
}
