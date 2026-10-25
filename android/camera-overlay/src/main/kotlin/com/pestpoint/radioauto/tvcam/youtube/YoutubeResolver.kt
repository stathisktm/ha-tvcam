package com.pestpoint.radioauto.tvcam.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Resolves a youtube.com/youtu.be URL into an actual playable stream URL — the page URL itself
 * isn't media, ExoPlayer needs the real video (or muxed video+audio) file behind it.
 *
 * Deliberately simpler than the main tv app's own YoutubeRepository: this overlay renders in a
 * small ~320x180dp corner box (not full-screen) and already forces a *software* video decoder for
 * every stream it plays (to avoid this TV's hardware decoder conflicting with its live broadcast
 * program, confirmed necessary this session) — grabbing the tv app's true-max-resolution DASH
 * video-only+audio-only pair would just burn extra CPU on decoding pixels nobody can see at this
 * size. Picking the best *muxed* (video+audio already combined) stream up to [RESOLUTION_CAP_P]
 * is plenty for this box and avoids MergingMediaSource entirely.
 */
object YoutubeResolver {

    @Volatile
    private var initialized = false

    fun isYoutubeUrl(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host.endsWith("youtube.com") || host == "youtu.be"
    }

    /** Never throws — null on any failure (network, no matching stream, extraction error), same
     *  silent idiom used everywhere else in this app. */
    suspend fun resolveStreamUrl(youtubeUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            ensureInitialized()
            val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeUrl)
            val capped = info.videoStreams
                .filter { resolutionRank(it.resolution) in 1..RESOLUTION_CAP_P }
                .maxByOrNull { resolutionRank(it.resolution) }
                ?: info.videoStreams.minByOrNull { resolutionRank(it.resolution) }
            capped?.content
        }.getOrNull()
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(NewPipeDownloaderImpl.create(OkHttpClient()))
            initialized = true
        }
    }

    /** "360p" / "480p60" -> 360 / 480, so the highest resolution under the cap sorts first;
     *  unparsable -> excluded via the `in 1..CAP` range check above. */
    private fun resolutionRank(resolution: String): Int =
        resolution.takeWhile { it.isDigit() }.toIntOrNull() ?: -1

    private const val RESOLUTION_CAP_P = 480
}
