package com.pestpoint.radioauto.tvcam.overlay

/** What a single SHOW call renders — all fields independently optional except at least one of
 *  [hlsUrl]/[imageUrl]/[title]/[message] must be non-null for there to be anything to show. */
data class OverlayContent(
    val hlsUrl: String? = null,
    /** Ignored when [hlsUrl] is also present — live video wins as the "main visual", [title] and
     *  [message] stay independently combinable with either. */
    val imageUrl: String? = null,
    val title: String? = null,
    val message: String? = null,
    val position: OverlayPosition = OverlayPosition.BOTTOM_END,
    val size: OverlaySize = OverlaySize.MEDIUM,
    /** 0-100 — how opaque the placeholder background is while a video/photo is still loading (0 =
     *  fully transparent, 100 = solid black). Defaults to 25, matching the original fixed value. */
    val loadingOpacityPercent: Int = 25,
    val textPosition: TextPosition = TextPosition.OVERLAY,
    /** 0-100 — how opaque the title/message's own background strip is. Defaults to 70, matching
     *  the original fixed value. */
    val textBackgroundOpacityPercent: Int = 70,
    val roundedCorners: Boolean = true,
    /** If set, this overlay becomes briefly interactive (see [OverlayWindowController.show]):
     *  it takes D-pad focus away from whatever's underneath for as long as it's showing, and
     *  pressing OK/Enter fires an HTTP POST to this URL (a Home Assistant webhook URL, typically)
     *  and dismisses the overlay; BACK dismisses without firing it. Null (the default) means the
     *  overlay stays purely passive/non-focusable, same as before this existed. */
    val okWebhookUrl: String? = null,
    /** If set, pressing OK (after firing [okWebhookUrl]) replaces this overlay with a brief,
     *  non-interactive confirmation showing this text instead of just dismissing silently — e.g.
     *  "Door opened". Ignored if [okWebhookUrl] isn't set (nothing to confirm). */
    val okResponseMessage: String? = null,
    /** Whether live/played video's own audio track is silenced. Only relevant when [hlsUrl] is
     *  set (a photo has no audio). Defaults to false (audible), matching the original, only
     *  behavior before this was configurable. */
    val muted: Boolean = false,
    /** Distance in dp from the screen edge(s) for a non-CENTER position — defaults to 16, matching
     *  the original fixed value. 0 (or close to it) hugs the very edge, e.g. for a small permanent
     *  status-style widget (a corner weather/temperature readout, ...) that should sit right at the
     *  top/side limit rather than visibly inset from it. Ignored for CENTER (never had margins). */
    val marginDp: Int = 16,
    /** How [title] and [message] sit relative to EACH OTHER within their own block — stacked
     *  (default, unchanged from before this existed) or side by side on one line, e.g. an emoji
     *  title next to a short message like a temperature reading. */
    val textLayout: TextLayout = TextLayout.VERTICAL,
    /** A small inline icon placed first in the title/message block (left of it in HORIZONTAL,
     *  above it in VERTICAL) — unlike [imageUrl] it's not a photo box, just an icon sized to sit
     *  next to text, e.g. an animated weather icon beside "22°C". Animated WebP/GIF keep playing. */
    val iconUrl: String? = null,
    val iconSizeDp: Int = 32,
) {
    val hasVisual: Boolean get() = hlsUrl != null || imageUrl != null
    val hasText: Boolean get() = !title.isNullOrBlank() || !message.isNullOrBlank() || iconUrl != null
    val isEmpty: Boolean get() = hlsUrl == null && imageUrl == null && !hasText
}

enum class OverlayPosition {
    TOP_START,
    TOP_END,
    BOTTOM_START,
    BOTTOM_END,
    CENTER,
    ;

    companion object {
        fun fromExtra(value: String?): OverlayPosition = when (value?.lowercase()) {
            "top_left" -> TOP_START
            "top_right" -> TOP_END
            "bottom_left" -> BOTTOM_START
            "center" -> CENTER
            "bottom_right" -> BOTTOM_END
            else -> BOTTOM_END
        }
    }
}

/** [MEDIUM] is today's only size (320x180dp) — kept as the default so nothing already using this
 *  changes size. [widthDp]/[heightDp] only apply to a visual (video/photo) box; a message-only
 *  overlay is always sized to its text regardless of this. */
enum class OverlaySize(val widthDp: Int, val heightDp: Int) {
    SMALL(240, 135),
    MEDIUM(320, 180),
    LARGE(480, 270),
    ;

    companion object {
        fun fromExtra(value: String?): OverlaySize = when (value?.lowercase()) {
            "small" -> SMALL
            "large" -> LARGE
            else -> MEDIUM
        }
    }
}

/** Where the title/message block sits relative to a video/photo box. Only matters when there's
 *  also a visual — a message/title-only overlay always just renders as its own standalone block
 *  regardless of this. */
enum class TextPosition {
    /** On top of the video/photo, as a strip along its bottom edge (the original, only behavior). */
    OVERLAY,

    /** A separate strip directly underneath the video/photo box, not overlapping it. */
    BELOW,
    ;

    companion object {
        fun fromExtra(value: String?): TextPosition = when (value?.lowercase()) {
            "below" -> BELOW
            else -> OVERLAY
        }
    }
}

/** How [OverlayContent.title] and [OverlayContent.message] sit relative to each other. */
enum class TextLayout {
    /** Title above message, each on its own line (the original, only behavior). */
    VERTICAL,

    /** Title and message side by side on one line — e.g. a weather emoji next to a temperature
     *  reading in a small permanent widget. */
    HORIZONTAL,
    ;

    companion object {
        fun fromExtra(value: String?): TextLayout = when (value?.lowercase()) {
            "horizontal" -> HORIZONTAL
            else -> VERTICAL
        }
    }
}
