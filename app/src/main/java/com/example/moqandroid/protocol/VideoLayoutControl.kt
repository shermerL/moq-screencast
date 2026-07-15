package com.example.moqandroid.protocol

import org.json.JSONObject
import uniffi.moq.MoqCatalog

const val VIDEO_LAYOUT_TRACK_NAME = "moqcast.video-layout"

private const val MOQCAST_CATALOG_SECTION = "moqcast"
private const val VIDEO_LAYOUT_PROTOCOL_VERSION = 1
private const val MAX_VIDEO_LAYOUT_DIMENSION = 16_384

enum class VideoLayoutPhase(val wireValue: String) {
    Preparing("preparing"),
    Ready("ready"),
    Cancelled("cancelled");

    companion object {
        fun fromWireValue(value: String): VideoLayoutPhase? = entries.firstOrNull { it.wireValue == value }
    }
}

data class VideoLayoutEvent(
    val phase: VideoLayoutPhase,
    val generation: Long,
    val width: Int,
    val height: Int,
    val rotation: Int?,
) {
    fun encode(): ByteArray {
        return JSONObject()
            .put("version", VIDEO_LAYOUT_PROTOCOL_VERSION)
            .put("phase", phase.wireValue)
            .put("generation", generation)
            .put("width", width)
            .put("height", height)
            .apply { rotation?.let { put("rotation", it) } }
            .toString()
            .encodeToByteArray()
    }

    companion object {
        fun decode(payload: ByteArray): VideoLayoutEvent? = runCatching {
            val json = JSONObject(payload.decodeToString())
            if (json.getInt("version") != VIDEO_LAYOUT_PROTOCOL_VERSION) return null
            val phase = VideoLayoutPhase.fromWireValue(json.getString("phase")) ?: return null
            val generation = json.getLong("generation")
            val width = json.getInt("width")
            val height = json.getInt("height")
            val rotation = if (json.has("rotation")) json.getInt("rotation") else null
            if (
                generation <= 0 ||
                width !in 1..MAX_VIDEO_LAYOUT_DIMENSION ||
                height !in 1..MAX_VIDEO_LAYOUT_DIMENSION ||
                rotation !in setOf(null, 0, 90, 180, 270)
            ) {
                return null
            }
            VideoLayoutEvent(
                phase = phase,
                generation = generation,
                width = width,
                height = height,
                rotation = rotation,
            )
        }.getOrNull()
    }
}

fun videoLayoutCatalogSection(): String {
    return JSONObject()
        .put(
            "videoLayout",
            JSONObject()
                .put("version", VIDEO_LAYOUT_PROTOCOL_VERSION)
                .put("track", VIDEO_LAYOUT_TRACK_NAME),
        )
        .toString()
}

fun MoqCatalog.videoLayoutTrackName(): String? = runCatching {
    val section = extra[MOQCAST_CATALOG_SECTION] ?: return null
    val videoLayout = JSONObject(section).getJSONObject("videoLayout")
    if (videoLayout.getInt("version") != VIDEO_LAYOUT_PROTOCOL_VERSION) return null
    videoLayout.getString("track").takeIf { it.isNotBlank() }
}.getOrNull()

const val MOQCAST_CATALOG_SECTION_NAME = MOQCAST_CATALOG_SECTION
