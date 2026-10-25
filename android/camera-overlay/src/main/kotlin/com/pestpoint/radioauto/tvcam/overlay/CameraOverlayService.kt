package com.pestpoint.radioauto.tvcam.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.pestpoint.radioauto.tvcam.R
import com.pestpoint.radioauto.tvcam.config.HaConfigPrefs
import com.pestpoint.radioauto.tvcam.ha.HaCameraStreamClient
import com.pestpoint.radioauto.tvcam.youtube.YoutubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Receives SHOW_CAMERA/HIDE_CAMERA (via [CameraOverlayReceiver], started from a background
 * broadcast) and drives [OverlayWindowController] accordingly. Same startForeground()-first shape
 * as the tv module's TvPlaybackService, for the same reason: a service started via
 * `context.startForegroundService()` from a background broadcast has to call startForeground()
 * within ~5 seconds or the OS kills the app outright with a RemoteServiceException — resolving the
 * HA WebSocket handshake first and only calling startForeground() once that's done would blow
 * straight through that deadline.
 */
class CameraOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val overlay by lazy { OverlayWindowController(this) }

    /** Every distinct slot (see [EXTRA_SLOT]) runs as a fully independent unit — its own in-flight
     *  resolve/auto-hide jobs and its own dedup state — so a live camera stream stays up
     *  undisturbed while a separate plain-text trigger, or any number of small permanent widgets
     *  (weather, temperature, ...), come and go elsewhere on screen, and vice versa. Two SHOW calls
     *  for the SAME slot still replace one another as before (only one thing at a time per slot);
     *  different slots never interact at all. */
    private class SlotJobs {
        var resolveJob: Job? = null
        var autoHideJob: Job? = null

        /** The raw (pre-resolve) request currently showing or being resolved in this slot —
         *  compared on the request itself, not the resolved hlsUrl, since that gets a fresh HA
         *  stream token on every resolve even for the exact same camera. */
        var activeRequest: ShowRequest? = null
    }

    private val slotJobs = mutableMapOf<String, SlotJobs>()
    private fun jobsFor(slot: String): SlotJobs = slotJobs.getOrPut(slot) { SlotJobs() }

    private data class ShowRequest(
        val entityId: String?,
        val videoUrl: String?,
        val imageUrl: String?,
        val title: String?,
        val message: String?,
        val position: OverlayPosition,
        val size: OverlaySize,
        val loadingOpacity: Int,
        val textPosition: TextPosition,
        val textBackgroundOpacity: Int,
        val roundedCorners: Boolean,
        val okWebhookUrl: String?,
        val okResponseMessage: String?,
        val okResponseImageUrl: String?,
        val muted: Boolean,
        val marginDp: Int,
        val textLayout: TextLayout,
        val iconUrl: String?,
        val iconSizeDp: Int,
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_CAMERA -> handleShow(intent)
            ACTION_HIDE_CAMERA -> handleHideAction(intent)
        }
        return START_NOT_STICKY
    }

    private fun handleHideAction(intent: Intent) {
        val slot = intent.getStringExtra(EXTRA_SLOT)?.takeIf { it.isNotBlank() }
        if (slot != null) {
            hideSlot(slot)
        } else {
            // No slot given — a full reset, hides every overlay currently up in any slot (the
            // default visual/message ones and any named widget slots alike).
            slotJobs.keys.toList().forEach { hideSlot(it) }
        }
    }

    private fun handleShow(intent: Intent) {
        val entityId = intent.getStringExtra(EXTRA_ENTITY_ID)?.takeIf { it.isNotBlank() }
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)?.takeIf { it.isNotBlank() }
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)?.takeIf { it.isNotBlank() }
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        val message = intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }
        val position = OverlayPosition.fromExtra(intent.getStringExtra(EXTRA_POSITION))
        val size = OverlaySize.fromExtra(intent.getStringExtra(EXTRA_SIZE))
        val loadingOpacity = intent.getIntExtra(EXTRA_LOADING_OPACITY, 25)
        val textPosition = TextPosition.fromExtra(intent.getStringExtra(EXTRA_TEXT_POSITION))
        val textBackgroundOpacity = intent.getIntExtra(EXTRA_TEXT_BACKGROUND_OPACITY, 70)
        val roundedCorners = intent.getBooleanExtra(EXTRA_ROUNDED_CORNERS, true)
        val soundUrl = intent.getStringExtra(EXTRA_SOUND_URL)?.takeIf { it.isNotBlank() }
        val chime = intent.getBooleanExtra(EXTRA_CHIME, false)
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val okWebhookUrl = intent.getStringExtra(EXTRA_OK_WEBHOOK_URL)?.takeIf { it.isNotBlank() }
        val okResponseMessage = intent.getStringExtra(EXTRA_OK_RESPONSE_MESSAGE)?.takeIf { it.isNotBlank() }
        val okResponseImageUrl = intent.getStringExtra(EXTRA_OK_RESPONSE_IMAGE_URL)?.takeIf { it.isNotBlank() }
        val muted = intent.getBooleanExtra(EXTRA_MUTED, false)
        val marginDp = intent.getIntExtra(EXTRA_MARGIN_DP, 16)
        val textLayout = TextLayout.fromExtra(intent.getStringExtra(EXTRA_TEXT_LAYOUT))
        val explicitSlot = intent.getStringExtra(EXTRA_SLOT)?.takeIf { it.isNotBlank() }
        val iconUrl = intent.getStringExtra(EXTRA_ICON_URL)?.takeIf { it.isNotBlank() }
        val iconSizeDp = intent.getIntExtra(EXTRA_ICON_SIZE_DP, 32)

        // Independent of the visual overlay entirely — plays on every trigger that asks for it,
        // even one that's a no-op for the visual (the same camera/message already showing just
        // renews its timer below, but a second doorbell press should still audibly chime).
        if (soundUrl != null || chime) {
            overlay.playSound(soundUrl)
        }

        if (entityId == null && videoUrl == null && imageUrl == null && title == null && message == null && iconUrl == null) {
            if (soundUrl == null && !chime) {
                Log.w(TAG, "handleShow: nothing to show (no entity_id/video_url/image_url/title/message/icon_url/sound), stopping")
            }
            // No slot is touched by a sound/chime-only trigger — don't stopSelf() unconditionally
            // here, since some OTHER slot might still be legitimately showing something the
            // service needs to stay alive for.
            stopIfAllSlotsIdle()
            return
        }

        // An explicit slot (EXTRA_SLOT) is a freeform, independent "channel" the caller names
        // itself — e.g. "weather"/"temperature" for small permanent widgets that must never
        // interact with each other or with the usual camera/message triggers. Without one, the
        // original automatic behavior applies unchanged: a visual (camera_entity/video_url/
        // image_url) always owns the shared "visual" slot, combined with any title/message from
        // the SAME call exactly as before; a message-only call (no visual at all) gets the shared
        // "message" slot instead, so it can coexist with whatever's already showing in "visual"
        // rather than tearing it down.
        val slot = explicitSlot ?: if (entityId != null || videoUrl != null || imageUrl != null) DEFAULT_VISUAL_SLOT else DEFAULT_MESSAGE_SLOT
        val jobs = jobsFor(slot)

        val request = ShowRequest(
            entityId, videoUrl, imageUrl, title, message, position, size,
            loadingOpacity, textPosition, textBackgroundOpacity, roundedCorners, okWebhookUrl,
            okResponseMessage, okResponseImageUrl, muted, marginDp, textLayout, iconUrl, iconSizeDp,
        )

        // The exact same content is already showing in this slot (or already being resolved) — a
        // repeated trigger for it (e.g. someone holding a doorbell button) shouldn't tear down and
        // rebuild the player/window from scratch, just extend how long it stays up.
        if (request == jobs.activeRequest) {
            Log.d(TAG, "handleShow: slot=$slot same content already active, just renewing the auto-hide timer")
            jobs.autoHideJob?.cancel()
            if (durationMs > 0) {
                jobs.autoHideJob = serviceScope.launch {
                    delay(durationMs)
                    hideSlot(slot)
                }
            }
            return
        }

        // Only the latest trigger for THIS slot is ever allowed to reach the screen — an in-flight
        // resolve or a pending auto-hide from a previous SHOW in the same slot can't race with this
        // one. The other slot's jobs are completely untouched.
        jobs.resolveJob?.cancel()
        jobs.autoHideJob?.cancel()
        jobs.activeRequest = request
        jobs.resolveJob = serviceScope.launch {
            // A direct video_url is used as-is (an arbitrary HLS/HTTP/RTSP stream — a local-network
            // camera's own feed, an internet-exposed one, anything ExoPlayer can open) — except a
            // youtube.com/youtu.be link, which isn't itself playable media and needs resolving to
            // a real stream URL first. Only entity_id/a YouTube video_url needs a network
            // round-trip; a failed resolve doesn't block image_url/message from still showing
            // (e.g. message + a broken camera still shows the message) — only bail out if there's
            // nothing left.
            val hlsUrl = when {
                videoUrl != null && YoutubeResolver.isYoutubeUrl(videoUrl) -> YoutubeResolver.resolveStreamUrl(videoUrl)
                videoUrl != null -> videoUrl
                else -> null
            } ?: entityId?.let { id ->
                val prefs = HaConfigPrefs(this@CameraOverlayService)
                val baseUrl = prefs.haBaseUrl
                val token = prefs.haToken
                Log.d(TAG, "handleShow: entityId=$id baseUrl=$baseUrl tokenPresent=${!token.isNullOrBlank()}")
                if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
                    Log.w(TAG, "handleShow: missing HA config")
                    null
                } else {
                    HaCameraStreamClient(baseUrl, token).resolveHlsUrl(id)
                }
            }
            Log.d(TAG, "handleShow: hlsUrl=$hlsUrl imageUrl=$imageUrl title=$title message=$message position=$position")

            val content = OverlayContent(
                hlsUrl = hlsUrl,
                imageUrl = imageUrl,
                title = title,
                message = message,
                position = position,
                size = size,
                loadingOpacityPercent = loadingOpacity,
                textPosition = textPosition,
                textBackgroundOpacityPercent = textBackgroundOpacity,
                roundedCorners = roundedCorners,
                okWebhookUrl = okWebhookUrl,
                okResponseMessage = okResponseMessage,
                muted = muted,
                marginDp = marginDp,
                textLayout = textLayout,
                iconUrl = iconUrl,
                iconSizeDp = iconSizeDp,
            )
            if (content.isEmpty) {
                jobs.activeRequest = null
                stopIfAllSlotsIdle()
                return@launch
            }
            overlay.show(
                slot,
                content,
                onOkPressed = {
                    if (okResponseMessage != null || okResponseImageUrl != null) {
                        showOkResponse(slot, okResponseMessage, okResponseImageUrl, content)
                    } else {
                        hideSlot(slot)
                    }
                },
                onDismiss = {
                    // BACK — the user declined/dismissed without pressing OK, same cleanup as an
                    // explicit HIDE_CAMERA, just triggered from inside the window instead of a new
                    // broadcast. No response message either way; that's only for acknowledging OK.
                    hideSlot(slot)
                },
            )
            Log.d(TAG, "handleShow: slot=$slot overlay.show() called")
            if (durationMs > 0) {
                jobs.autoHideJob = launch {
                    delay(durationMs)
                    hideSlot(slot)
                }
            }
        }
    }

    /** Replaces the just-dismissed interactive overlay with a brief, non-interactive confirmation
     *  (e.g. "Door opened", optionally with its own photo — a checkmark icon, a fresh snapshot,
     *  whatever the caller wants) instead of just vanishing silently — same position/styling as
     *  the interactive overlay it replaces, auto-hides itself on a short fixed timer regardless of
     *  whatever duration_ms the original SHOW call asked for. Stays in whichever slot the
     *  interactive overlay itself was shown in. */
    private fun showOkResponse(slot: String, message: String?, imageUrl: String?, originalContent: OverlayContent) {
        val jobs = jobsFor(slot)
        jobs.autoHideJob?.cancel()
        jobs.activeRequest = null
        val confirmation = OverlayContent(
            imageUrl = imageUrl,
            message = message,
            position = originalContent.position,
            size = originalContent.size,
            loadingOpacityPercent = originalContent.loadingOpacityPercent,
            textBackgroundOpacityPercent = originalContent.textBackgroundOpacityPercent,
            roundedCorners = originalContent.roundedCorners,
            marginDp = originalContent.marginDp,
        )
        overlay.show(slot, confirmation)
        jobs.autoHideJob = serviceScope.launch {
            delay(OK_RESPONSE_DISPLAY_MS)
            hideSlot(slot)
        }
    }

    /** Tears down only [slot] — every other slot's window/jobs, if any, are left completely
     *  untouched, which is what lets a live camera stream, a separate message notification, and
     *  any number of independent widget slots coexist. Only actually stops the service once EVERY
     *  slot is idle, since this is a single foreground service backing all of them — stopping it
     *  while another slot is still showing something would tear that down too via [onDestroy]. */
    private fun hideSlot(slot: String) {
        val jobs = jobsFor(slot)
        jobs.resolveJob?.cancel()
        jobs.autoHideJob?.cancel()
        jobs.activeRequest = null
        overlay.teardown(slot)
        stopIfAllSlotsIdle()
    }

    private fun stopIfAllSlotsIdle() {
        if (slotJobs.values.all { it.activeRequest == null }) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        overlay.teardownAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Κάμερα", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildPlaceholderNotification(): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

    companion object {
        const val ACTION_SHOW_CAMERA = "com.pestpoint.radioauto.tvcam.action.SHOW_CAMERA"
        const val ACTION_HIDE_CAMERA = "com.pestpoint.radioauto.tvcam.action.HIDE_CAMERA"

        /** HA camera entity id, e.g. "camera.doorbell" — resolved to a live HLS URL. Ignored if
         *  [EXTRA_VIDEO_URL] is also present. */
        const val EXTRA_ENTITY_ID = "entity_id"

        /** A direct video URL — HLS/HTTP/RTSP, a local-network camera's own feed or an
         *  internet-exposed one, anything ExoPlayer can open — played as-is, no Home Assistant
         *  involved at all. A youtube.com/youtu.be link is resolved to a real stream URL first
         *  (see [YoutubeResolver]) rather than played directly. Takes priority over
         *  [EXTRA_ENTITY_ID] if both are present. */
        const val EXTRA_VIDEO_URL = "video_url"

        /** A direct photo URL (an arbitrary image, or an HA camera's own snapshot/camera_proxy
         *  URL) — ignored if a live stream ([EXTRA_VIDEO_URL] or a resolved [EXTRA_ENTITY_ID])
         *  is also present. */
        const val EXTRA_IMAGE_URL = "image_url"

        /** A heading, shown above [EXTRA_MESSAGE] in the same text block — combinable with either
         *  visual, or shown on its own. */
        const val EXTRA_TITLE = "title"

        /** Text shown as a caption strip — combinable with either visual, or shown on its own. */
        const val EXTRA_MESSAGE = "message"

        /** "vertical" (default — title above message, each on its own line) or "horizontal"
         *  (title and message side by side on one line, e.g. a weather emoji next to a
         *  temperature reading in a small permanent widget). */
        const val EXTRA_TEXT_LAYOUT = "text_layout"

        /** A small inline icon shown first in the title/message block (not a photo box) — an
         *  animated WebP/GIF keeps playing, e.g. an animated weather icon next to "22°C". */
        const val EXTRA_ICON_URL = "icon_url"

        /** Int dp — the [EXTRA_ICON_URL] icon's square size. Defaults to 32. */
        const val EXTRA_ICON_SIZE_DP = "icon_size"

        /** One of top_left/top_right/bottom_left/bottom_right/center — defaults to bottom_right. */
        const val EXTRA_POSITION = "position"

        /** One of small/medium/large — defaults to medium (320x180dp, unchanged from before this
         *  was configurable). Only affects the video/photo box; a message-only overlay is always
         *  sized to its text regardless. */
        const val EXTRA_SIZE = "size"

        /** 0-100 — how opaque the placeholder background is while a video/photo is still loading
         *  (0 = fully transparent, 100 = solid black). Defaults to 25. */
        const val EXTRA_LOADING_OPACITY = "loading_opacity"

        /** "overlay" (default — on top of the video/photo's bottom edge) or "below" (a separate
         *  strip directly underneath the video/photo box, not overlapping it). Only matters when
         *  there's also a visual — title/message alone always just render as their own block. */
        const val EXTRA_TEXT_POSITION = "text_position"

        /** 0-100 — how opaque the title/message's own background strip is. Defaults to 70. */
        const val EXTRA_TEXT_BACKGROUND_OPACITY = "text_background_opacity"

        /** Whether the video/photo box and the title/message background have rounded corners.
         *  Defaults to true. */
        const val EXTRA_ROUNDED_CORNERS = "rounded_corners"

        /** A direct audio URL to play once as an alert sound, independent of the visual overlay.
         *  Takes priority over [EXTRA_CHIME]'s bundled default if both are present. */
        const val EXTRA_SOUND_URL = "sound_url"

        /** Boolean — if true and [EXTRA_SOUND_URL] isn't given, plays a bundled default two-tone
         *  chime once as an alert sound. */
        const val EXTRA_CHIME = "chime"

        /** Optional — if present, the overlay auto-hides itself after this many milliseconds, so
         *  a single SHOW command can be "self-expiring" without a follow-up HIDE_CAMERA. An
         *  explicit HIDE_CAMERA broadcast always works too, independent of this timer. */
        const val EXTRA_DURATION_MS = "duration_ms"

        /** If present, this overlay becomes briefly interactive — it takes D-pad focus away from
         *  whatever's underneath for as long as it's showing, and pressing OK/Enter fires an HTTP
         *  POST to this URL (a Home Assistant webhook URL, typically) and dismisses the overlay;
         *  BACK dismisses without firing it. See [OverlayWindowController.show]/[OkButtonListener]. */
        const val EXTRA_OK_WEBHOOK_URL = "ok_webhook_url"

        /** Ignored unless [EXTRA_OK_WEBHOOK_URL] is also present — text shown as a brief
         *  confirmation (e.g. "Door opened") after OK is pressed and the webhook fires, instead of
         *  the overlay just vanishing silently. Combinable with [EXTRA_OK_RESPONSE_IMAGE_URL].
         *  See [showOkResponse]. */
        const val EXTRA_OK_RESPONSE_MESSAGE = "ok_response_message"

        /** Ignored unless [EXTRA_OK_WEBHOOK_URL] is also present — a photo shown alongside (or
         *  instead of) [EXTRA_OK_RESPONSE_MESSAGE] in that same brief confirmation. */
        const val EXTRA_OK_RESPONSE_IMAGE_URL = "ok_response_image_url"

        /** Boolean — whether live/played video's own audio track is silenced. Only relevant with
         *  [EXTRA_ENTITY_ID]/[EXTRA_VIDEO_URL] (a photo has no audio). Defaults to false
         *  (audible), matching the original, only behavior before this was configurable. */
        const val EXTRA_MUTED = "muted"

        /** Distance in dp from the screen edge(s) for a non-CENTER position — defaults to 16,
         *  matching the original fixed value. 0 (or close to it) hugs the very edge, e.g. for a
         *  small permanent status-style widget that should sit right at the top/side limit rather
         *  than visibly inset from it. Ignored for a CENTER position. */
        const val EXTRA_MARGIN_DP = "margin"

        /** Freeform identifier for which independent overlay this SHOW/HIDE targets — a new SHOW
         *  for the same slot replaces only that slot's own window; a different slot coexists
         *  completely independently (e.g. a permanent small "weather" widget in one corner and a
         *  separate "temperature" one elsewhere, both up at once alongside the usual camera-stream/
         *  message triggers). Omit for the original, automatic behavior: any call with a
         *  camera_entity/video_url/image_url uses the shared "visual" slot, a title/message-only
         *  call uses the shared "message" slot — unchanged from before this existed. On HIDE_CAMERA,
         *  omitting this hides every slot that's currently showing anything. */
        const val EXTRA_SLOT = "slot"

        private const val TAG = "CameraOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "camera_overlay"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_VISUAL_SLOT = "visual"
        private const val DEFAULT_MESSAGE_SLOT = "message"

        /** How long the brief post-OK confirmation ([EXTRA_OK_RESPONSE_MESSAGE]) stays up before
         *  auto-hiding, regardless of the original SHOW call's own duration_ms. */
        private const val OK_RESPONSE_DISPLAY_MS = 4000L
    }
}
