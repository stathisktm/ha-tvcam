package com.pestpoint.radioauto.tvcam.ha

import kotlinx.serialization.Serializable

/** Outgoing: answers HA's "auth_required" frame with the long-lived access token. */
@Serializable
internal data class HaAuthMessage(
    val type: String = "auth",
    val access_token: String,
)

/** Outgoing: asks HA to start (or reuse) an HLS stream for a camera entity. */
@Serializable
internal data class HaStreamRequest(
    val id: Int,
    val type: String = "camera/stream",
    val entity_id: String,
    val format: String = "hls",
)

/** Incoming: one permissive shape that every frame HA sends during this handshake fits —
 *  "auth_required"/"auth_ok"/"auth_invalid" have no extra fields we need, "result" carries the
 *  stream URL. Decoded with ignoreUnknownKeys so any other fields HA includes are simply dropped. */
@Serializable
internal data class HaEnvelope(
    val type: String? = null,
    val id: Int? = null,
    val success: Boolean? = null,
    val result: HaStreamResult? = null,
)

@Serializable
internal data class HaStreamResult(
    val url: String? = null,
)
