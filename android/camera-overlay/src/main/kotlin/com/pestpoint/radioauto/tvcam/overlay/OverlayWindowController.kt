package com.pestpoint.radioauto.tvcam.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pestpoint.radioauto.tvcam.R
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the actual floating window(s): video/photo/title/message content added directly via
 * WindowManager, not an Activity. TYPE_APPLICATION_OVERLAY composites above whatever app
 * currently has focus (in particular, the TV's own live-broadcast app) without taking focus away
 * from it — FLAG_NOT_FOCUSABLE/FLAG_NOT_TOUCHABLE are what make that true; without them this small
 * window could still intercept D-pad input and fight the broadcast app for the remote.
 *
 * Each distinct [slot] (a freeform string key — see [CameraOverlayService.EXTRA_SLOT]) gets its
 * own, fully independent window: a new SHOW for a given slot replaces only that slot's own window,
 * completely untouched by whatever's showing in any other slot — that's what lets a live camera
 * stream, a separate message notification, and any number of small permanent widgets (weather,
 * temperature, ...) all be up on screen at once.
 */
@UnstableApi
class OverlayWindowController(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)

    private class SlotViews {
        var rootView: View? = null
        var player: ExoPlayer? = null
        var imageJob: Job? = null
        var iconJob: Job? = null
        var iconView: ImageView? = null
        /** Remembered purely so [teardown] can animate the exit in the same direction the window
         *  entered from, without needing the caller to pass [OverlayContent] again just to hide it. */
        var position: OverlayPosition = OverlayPosition.BOTTOM_END

        // Only populated/used for a photo whose box should free-size to its own natural aspect
        // ratio once loaded (see the imageUrl branch of buildVisualBox) — a live-updated window
        // needs its own WindowManager + LayoutParams handle, unlike everything else here which is
        // only ever set up once per show().
        var windowManager: WindowManager? = null
        var layoutParams: WindowManager.LayoutParams? = null
        var isStackBelow: Boolean = false
    }

    private val slotViews = mutableMapOf<String, SlotViews>()
    private fun viewsFor(slot: String): SlotViews = slotViews.getOrPut(slot) { SlotViews() }

    // This TV box's hardware video decoder is already busy decoding the live broadcast program
    // running in a completely separate app — a second concurrent hardware decoder instance for
    // the camera stream fails at the driver level (confirmed: MediaCodec starts, then errors with
    // "Codec reported err 0x80001000" the moment it tries to actually decode a frame), leaving the
    // overlay window visible but showing nothing. Forcing a software-only decoder sidesteps that
    // hardware contention entirely, at the cost of some extra CPU for this small overlay surface.
    // Confirmed fixed live on-device (both over a normal broadcast and over YouTube playback).
    private val softwareDecoderPreferringRenderersFactory by lazy {
        DefaultRenderersFactory(context).setMediaCodecSelector(
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val all = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                val softwareOnly = all.filter { it.softwareOnly }
                softwareOnly.ifEmpty { all }
            },
        )
    }

    /** No-op if the "draw over other apps" permission isn't granted — some Android TV boxes'
     *  canDrawOverlays() and their real appops-granted state can disagree, so the addView() call
     *  itself is also wrapped defensively. Always tears down [slot]'s own previous window first
     *  (only that slot — the other slot's window, if any, is left completely alone), so rapid
     *  re-triggers of the same slot (even for different content) can never stack two windows there.
     *
     *  [onOkPressed]/[onDismiss] only ever fire for an interactive overlay (one with
     *  [OverlayContent.okWebhookUrl] set) — [onOkPressed] when OK/Enter was pressed (after the
     *  webhook itself already fired), [onDismiss] when BACK was pressed instead. Neither fires for
     *  a plain [teardown] from an external HIDE/new SHOW or the duration timeout, since the caller
     *  already knows about those. */
    fun show(slot: String, content: OverlayContent, onOkPressed: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
        teardown(slot)
        if (content.isEmpty) return
        Log.d(TAG, "show: slot=$slot canDrawOverlays=${Settings.canDrawOverlays(context)} content=$content")
        if (!Settings.canDrawOverlays(context)) return
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = context.resources.displayMetrics.density
            val cornerRadiusPx = if (content.roundedCorners) CORNER_RADIUS_DP * density else 0f
            val views = viewsFor(slot)

            val visualBox = if (content.hasVisual) buildVisualBox(content, density, cornerRadiusPx, views) else null
            val textBlock = if (content.hasText) buildTextBlock(content, density, cornerRadiusPx, views) else null

            val widthPx = (content.size.widthDp * density).toInt()
            val heightPx = (content.size.heightDp * density).toInt()

            // BELOW only makes sense (and only differs from OVERLAY) when there's a visual box to
            // sit apart from — title/message alone always renders as its own standalone block.
            val stackBelow = content.textPosition == TextPosition.BELOW && visualBox != null && textBlock != null

            val outer: View
            val windowWidth: Int
            val windowHeight: Int
            if (stackBelow) {
                outer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // MATCH_PARENT width (not an explicit widthPx copy) on both — a photo box that
                    // later free-resizes to its own natural aspect ratio (see
                    // resizeVisualToNaturalAspect) only has to change the WINDOW's own width for
                    // both this box and the text strip below it to follow automatically, instead
                    // of needing their own LayoutParams updated individually and kept in sync.
                    addView(visualBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx))
                    addView(textBlock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                windowWidth = widthPx
                windowHeight = ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                val frame = FrameLayout(context)
                visualBox?.let { frame.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
                textBlock?.let {
                    val gravity = if (visualBox != null) Gravity.BOTTOM else Gravity.CENTER
                    frame.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity))
                }
                outer = frame
                windowWidth = if (visualBox != null) widthPx else ViewGroup.LayoutParams.WRAP_CONTENT
                windowHeight = if (visualBox != null) heightPx else ViewGroup.LayoutParams.WRAP_CONTENT
            }

            // An interactive overlay (ok_webhook_url set) deliberately takes D-pad focus away from
            // whatever's underneath for as long as it's showing — the one Android mechanism that
            // reliably sees a remote-control key press on this hardware turned out to be plain
            // window focus; an AccessibilityService with canRequestFilterKeyEvents (which doesn't
            // need focus at all) was tried first and confirmed NOT to receive key events on this
            // TCL firmware, real remote included. A plain informational overlay (no ok_webhook_url)
            // stays exactly as non-focusable/non-intrusive as before.
            val interactive = content.okWebhookUrl != null
            val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                if (interactive) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            val params = WindowManager.LayoutParams(
                windowWidth,
                windowHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = content.position.toGravity()
                if (content.position != OverlayPosition.CENTER) {
                    x = (content.marginDp * density).toInt()
                    y = (content.marginDp * density).toInt()
                }
            }

            if (interactive) {
                outer.isFocusable = true
                outer.isFocusableInTouchMode = true
                outer.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            Log.d(TAG, "show: OK pressed, firing ok_webhook_url")
                            OkButtonListener.fire(content.okWebhookUrl!!)
                            teardown(slot)
                            onOkPressed?.invoke()
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            teardown(slot)
                            onDismiss?.invoke()
                        }
                        // Anything else (D-pad direction, etc.) is simply swallowed — there's
                        // nothing in this small box to navigate, and the underlying app can't see
                        // it anyway while this window holds focus.
                        else -> Unit
                    }
                    true
                }
            }

            // Set BEFORE addView so the very first composited frame is already in the "just
            // entering" state (invisible/offset) — no one-frame flash of the fully-shown content.
            applyEnterState(outer, content.position, density)

            wm.addView(outer, params)
            views.rootView = outer
            views.position = content.position
            views.windowManager = wm
            views.layoutParams = params
            views.isStackBelow = stackBelow
            if (interactive) outer.requestFocus()
            animateEnter(outer)
            Log.d(TAG, "show: addView succeeded")
        }.onFailure { Log.e(TAG, "show: failed", it) }
    }

    /** The video/photo box: content view + a spinner over an almost-transparent placeholder
     *  background while it's still loading, both cleared the moment there's something real to
     *  show. */
    private fun buildVisualBox(content: OverlayContent, density: Float, cornerRadiusPx: Float, views: SlotViews): FrameLayout {
        val box = FrameLayout(context)
        applyRoundedBackground(box, loadingColor(content.loadingOpacityPercent), cornerRadiusPx)
        applyRoundedClip(box, cornerRadiusPx)

        val spinner = ProgressBar(context).apply { isIndeterminate = true }
        box.addView(spinner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        if (content.hlsUrl != null) {
            val exo = ExoPlayer.Builder(context, softwareDecoderPreferringRenderersFactory)
                .setMediaSourceFactory(CameraMediaSourceFactory(context))
                .build().apply {
                    setMediaItem(MediaItem.fromUri(content.hlsUrl))
                    volume = if (content.muted) 0f else 1f
                    prepare()
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            box.background = null
                            spinner.visibility = View.GONE
                        }
                    })
                }
            // Inflated (not `PlayerView(context)` directly) — surface_type=texture_view can only
            // be set via this XML attribute at construction, there's no post-hoc setter for it.
            // texture_view is load-bearing here, not cosmetic: the default SurfaceView is
            // composited as its own opaque hardware layer and completely ignores this box's own
            // (semi-transparent, possibly rounded-corner-clipped) background — confirmed live, it
            // stayed a solid black rectangle regardless of shutter color. TextureView participates
            // in normal View compositing instead.
            val playerView = (LayoutInflater.from(context).inflate(R.layout.overlay_player_view, box, false) as PlayerView).apply {
                player = exo
                useController = false
                setShutterBackgroundColor(Color.TRANSPARENT)
            }
            box.addView(playerView, 0)
            views.player = exo
        } else if (content.imageUrl != null) {
            // FIT_CENTER, not CENTER_CROP — the whole photo always stays visible. The box itself
            // then gets resized to the photo's own natural aspect ratio once it's loaded (see
            // below), so FIT_CENTER's own letterboxing is normally never actually visible — it
            // only matters as a fallback while still loading (fixed placeholder size) or if the
            // fetch fails (box stays at its original fixed size either way).
            val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            box.addView(imageView, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            views.imageJob = scope.launch {
                val bitmap = ImageFetcher.fetch(content.imageUrl)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    // Free-size the box to the photo's own natural aspect ratio (still capped by
                    // content.size as a MAX, not a forced exact size) instead of leaving it
                    // letterboxed inside a fixed-aspect box — a deliberate one-time resize "pop"
                    // once the real dimensions are known, traded for never needing letterbox bars
                    // at all for a photo. No longer letterboxed, so the loading placeholder's
                    // background is simply cleared, same as the video case.
                    resizeVisualToNaturalAspect(views, box, bitmap.width, bitmap.height, content, density)
                    box.background = null
                } else {
                    // Fetch failed — solid black, not null, so a non-matching-aspect box (still at
                    // its original fixed size, since we never learned a real size to resize to)
                    // doesn't leave transparent letterbox bars with an invisible rounded clip; see
                    // the video case just above, which gets this for free from PlayerView itself.
                    box.setBackgroundColor(Color.BLACK)
                }
                spinner.visibility = View.GONE
            }
        }
        return box
    }

    /** Resizes an already-showing photo box to its own natural aspect ratio, scaled down (never
     *  up) to fit within [OverlayContent.size]'s dp values used as a MAX bound rather than a
     *  forced exact size — a portrait photo ends up narrow-and-tall, a panorama ends up
     *  wide-and-short, instead of either being letterboxed or cropped inside a fixed 16:9-ish box.
     *  A one-time live resize (via WindowManager.updateViewLayout, not a rebuild) once the photo's
     *  real pixel dimensions are known, which is unavoidably after it's already visible at its
     *  original placeholder size — the trade-off for never needing letterbox bars at all. */
    private fun resizeVisualToNaturalAspect(views: SlotViews, box: View, bitmapWidthPx: Int, bitmapHeightPx: Int, content: OverlayContent, density: Float) {
        if (bitmapWidthPx <= 0 || bitmapHeightPx <= 0) return
        val maxWidthPx = (content.size.widthDp * density).toInt()
        val maxHeightPx = (content.size.heightDp * density).toInt()
        val scale = minOf(maxWidthPx.toFloat() / bitmapWidthPx, maxHeightPx.toFloat() / bitmapHeightPx)
        val fitWidthPx = (bitmapWidthPx * scale).toInt().coerceAtLeast(1)
        val fitHeightPx = (bitmapHeightPx * scale).toInt().coerceAtLeast(1)

        if (views.isStackBelow) {
            // The box's WIDTH is MATCH_PARENT here (see show()) specifically so it — and the text
            // strip stacked below it, sharing the same MATCH_PARENT — both just follow the
            // window's own width once that's updated below, with nothing to touch on the box's
            // own LayoutParams for that axis. HEIGHT is the one dimension genuinely the box's own
            // (a fixed pixel value, not derived from its parent), so that's the one to update here.
            val boxParams = box.layoutParams ?: return
            boxParams.height = fitHeightPx
            box.layoutParams = boxParams
        }

        val wm = views.windowManager ?: return
        val rootView = views.rootView ?: return
        val params = views.layoutParams ?: return
        params.width = fitWidthPx
        // Not in the stackBelow case — the window's own height there stays WRAP_CONTENT (visual
        // box height + text strip height), never a fixed pixel value to overwrite here.
        if (!views.isStackBelow) {
            params.height = fitHeightPx
        }
        runCatching { wm.updateViewLayout(rootView, params) }
    }

    private fun buildTextBlock(content: OverlayContent, density: Float, cornerRadiusPx: Float, views: SlotViews): LinearLayout {
        val horizontal = content.textLayout == TextLayout.HORIZONTAL
        val block = LinearLayout(context).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (horizontal) gravity = Gravity.CENTER_VERTICAL
            val padHorizontal = (12 * density).toInt()
            val padVertical = (4 * density).toInt()
            setPadding(padHorizontal, padVertical, padHorizontal, padVertical)
        }
        applyRoundedBackground(block, textBackgroundColor(content.textBackgroundOpacityPercent), cornerRadiusPx)

        val titleView = if (!content.title.isNullOrBlank()) {
            TextView(context).apply {
                text = content.title
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        } else {
            null
        }
        val messageView = if (!content.message.isNullOrBlank()) {
            TextView(context).apply {
                text = content.message
                setTextColor(Color.WHITE)
                textSize = 16f
            }
        } else {
            null
        }

        // The icon's box is reserved at its final size up front (empty until loaded), so the block
        // doesn't jump in width once the image arrives — and a failed fetch just leaves a small gap.
        if (content.iconUrl != null) {
            val iconPx = (content.iconSizeDp * density).toInt()
            val iconView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val lp = LinearLayout.LayoutParams(iconPx, iconPx)
            if (titleView != null || messageView != null) {
                if (horizontal) lp.marginEnd = (6 * density).toInt() else lp.gravity = Gravity.CENTER_HORIZONTAL
            }
            block.addView(iconView, lp)
            views.iconView = iconView
            views.iconJob = scope.launch {
                val drawable = ImageFetcher.fetchDrawable(content.iconUrl, context.resources)
                Log.d(TAG, "show: icon loaded=${drawable?.javaClass?.simpleName}")
                if (drawable == null) return@launch
                iconView.setImageDrawable(drawable)
                ImageFetcher.startIfAnimated(drawable)
            }
        }

        // In HORIZONTAL layout, a small gap between title and message keeps them from touching —
        // in VERTICAL, each just stacks on its own line as before, no gap needed.
        if (titleView != null) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (horizontal && messageView != null) lp.marginEnd = (6 * density).toInt()
            block.addView(titleView, lp)
        }
        if (messageView != null) {
            // Explicit WRAP_CONTENT, not the no-LayoutParams overload — LinearLayout's own
            // generateDefaultLayoutParams() returns MATCH_PARENT width for VERTICAL orientation
            // (WRAP_CONTENT is only the default for HORIZONTAL), a well-known gotcha that bit us
            // for real here: a MATCH_PARENT message inside a WRAP_CONTENT block gets excluded from
            // that block's first sizing pass entirely, so the block's resolved width came ONLY
            // from the title — then the message got force-remeasured into that title-sized box in
            // the second pass and wrapped into a tall column of tiny lines. Confirmed live via
            // per-child measuredWidth logging (both title and message came back with the exact
            // same, title-sized width) before this fix.
            block.addView(messageView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return block
    }

    private fun applyRoundedBackground(view: View, color: Int, cornerRadiusPx: Float) {
        view.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = cornerRadiusPx
        }
    }

    private fun applyRoundedClip(view: View, cornerRadiusPx: Float) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, cornerRadiusPx)
            }
        }
    }

    /** A one-shot alert sound, independent of either overlay slot entirely — plays on every
     *  trigger that asks for it, even one that's a no-op for the visual (the same camera/message
     *  already showing just renews its timer, but a second doorbell press should still audibly
     *  chime). [soundUrl] takes priority if given; otherwise plays the bundled default chime.
     *  Self-releases on completion/error — nothing to track or tear down later. */
    fun playSound(soundUrl: String?) {
        runCatching {
            if (soundUrl != null) {
                val player = MediaPlayer()
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                player.setDataSource(soundUrl)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { it.release() }
                player.setOnErrorListener { mp, _, _ -> mp.release(); true }
                player.prepareAsync()
            } else {
                val player = MediaPlayer.create(context, R.raw.chime) ?: return@runCatching
                player.setOnCompletionListener { it.release() }
                player.start()
            }
        }.onFailure { Log.e(TAG, "playSound: failed", it) }
    }

    /** Tears down only [slot]'s own window — every other slot's window (if any) is left
     *  completely untouched, which is exactly what lets a live camera stream, a separate message
     *  notification, and any number of independent widget slots coexist. A no-op if [slot] was
     *  never shown in the first place. The window's removeView() itself is deferred until the
     *  brief exit animation finishes (see [animateExit]) — [views.rootView] is cleared to null
     *  immediately regardless, so a new [show] for this slot right away (a fast content swap)
     *  never mistakes the still-fading-out old window for anything it needs to wait on. */
    fun teardown(slot: String) {
        val views = viewsFor(slot)
        views.imageJob?.cancel()
        views.imageJob = null
        views.iconJob?.cancel()
        views.iconJob = null
        ImageFetcher.stopIfAnimated(views.iconView?.drawable)
        views.iconView = null
        views.player?.release()
        views.player = null
        val view = views.rootView
        views.rootView = null
        if (view != null) {
            animateExit(view, views.position) {
                runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
            }
        }
    }

    fun teardownAll() {
        slotViews.keys.toList().forEach { teardown(it) }
    }

    /** The window's starting transform for its entrance animation — offset outward from its own
     *  anchored corner/edge (so it slides "in" toward its resting position) and invisible; CENTER
     *  has no edge to slide from, so it scales in slightly instead. Must be applied before the
     *  view is ever added to the WindowManager, or the fully-shown state would flash for one frame
     *  first. */
    private fun applyEnterState(view: View, position: OverlayPosition, density: Float) {
        view.alpha = 0f
        val offset = SLIDE_DISTANCE_DP * density
        when (position) {
            OverlayPosition.TOP_START -> { view.translationX = -offset; view.translationY = -offset }
            OverlayPosition.TOP_END -> { view.translationX = offset; view.translationY = -offset }
            OverlayPosition.BOTTOM_START -> { view.translationX = -offset; view.translationY = offset }
            OverlayPosition.BOTTOM_END -> { view.translationX = offset; view.translationY = offset }
            OverlayPosition.CENTER -> { view.scaleX = ENTER_EXIT_SCALE; view.scaleY = ENTER_EXIT_SCALE }
        }
    }

    /** Settles the window into place: fades/slides/scales from whatever [applyEnterState] set up
     *  back to fully shown, at rest. A gentle ease-out — noticeable without feeling bouncy or
     *  gimmicky on something that might be a permanent, frequently-updating widget. */
    private fun animateEnter(view: View) {
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(null)
            .start()
    }

    /** The mirror image of [applyEnterState]/[animateEnter] — fades/slides/scales back out toward
     *  the edge this window is anchored to, quicker than the entrance (an ease-in exit reads as
     *  "leaving" more than a matching ease-out would), then invokes [onEnd] (the actual
     *  removeView()) once the animation completes. */
    private fun animateExit(view: View, position: OverlayPosition, onEnd: () -> Unit) {
        val density = context.resources.displayMetrics.density
        val offset = SLIDE_DISTANCE_DP * density
        val animator = view.animate()
            .alpha(0f)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
        when (position) {
            OverlayPosition.TOP_START -> animator.translationX(-offset).translationY(-offset)
            OverlayPosition.TOP_END -> animator.translationX(offset).translationY(-offset)
            OverlayPosition.BOTTOM_START -> animator.translationX(-offset).translationY(offset)
            OverlayPosition.BOTTOM_END -> animator.translationX(offset).translationY(offset)
            OverlayPosition.CENTER -> animator.scaleX(ENTER_EXIT_SCALE).scaleY(ENTER_EXIT_SCALE)
        }
        animator.withEndAction { onEnd() }.start()
    }

    private fun OverlayPosition.toGravity(): Int = when (this) {
        OverlayPosition.TOP_START -> Gravity.TOP or Gravity.START
        OverlayPosition.TOP_END -> Gravity.TOP or Gravity.END
        OverlayPosition.BOTTOM_START -> Gravity.BOTTOM or Gravity.START
        OverlayPosition.BOTTOM_END -> Gravity.BOTTOM or Gravity.END
        OverlayPosition.CENTER -> Gravity.CENTER
    }

    /** [percent] 0-100, user/HA-configurable — 0 is fully transparent, 100 is solid black. */
    private fun loadingColor(percent: Int): Int = blackWithAlpha(percent)

    /** [percent] 0-100, user/HA-configurable — 0 is fully transparent, 100 is solid black. */
    private fun textBackgroundColor(percent: Int): Int = blackWithAlpha(percent)

    private fun blackWithAlpha(percent: Int): Int {
        val alpha = (percent.coerceIn(0, 100) * 255 / 100)
        return Color.argb(alpha, 0, 0, 0)
    }

    private companion object {
        const val TAG = "OverlayWindowController"
        const val CORNER_RADIUS_DP = 12f
        const val SLIDE_DISTANCE_DP = 24f
        const val ENTER_EXIT_SCALE = 0.92f
        const val ENTER_DURATION_MS = 280L
        const val EXIT_DURATION_MS = 200L
    }
}
