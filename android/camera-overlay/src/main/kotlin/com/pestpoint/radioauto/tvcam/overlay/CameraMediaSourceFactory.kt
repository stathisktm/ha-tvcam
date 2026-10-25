package com.pestpoint.radioauto.tvcam.overlay

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Routes rtsp:// URIs to [RtspMediaSource] with TCP-interleaved transport forced, everything else
 * (HLS/HTTP) to the normal [DefaultMediaSourceFactory] path. Needed because ExoPlayer's default
 * RTSP transport negotiation tries UDP first — go2rtc (Home Assistant's own RTSP restreamer, what
 * a user-supplied `rtsp://` video_url most often actually points at) rejects that with a
 * `SETUP 461 Unsupported Transport` response, confirmed live: the overlay window appeared but
 * never played anything until this was forced to TCP.
 *
 * The HTTP/HLS path also uses a browser User-Agent, not ExoPlayer's default one — YouTube's CDN
 * 403s the default identity on the signed googlevideo.com URLs YoutubeResolver hands back, same
 * issue already hit and fixed in the tv module for the exact same reason.
 */
@UnstableApi
class CameraMediaSourceFactory(context: Context) : MediaSource.Factory {

    private val defaultFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory()
                .setUserAgent(BROWSER_USER_AGENT)
                .setAllowCrossProtocolRedirects(true),
        ),
    )
    private val rtspFactory = RtspMediaSource.Factory().setForceUseRtpTcp(true)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val scheme = mediaItem.localConfiguration?.uri?.scheme?.lowercase()
        return if (scheme == "rtsp") {
            rtspFactory.createMediaSource(mediaItem)
        } else {
            defaultFactory.createMediaSource(mediaItem)
        }
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        defaultFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        defaultFactory.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

    private companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
