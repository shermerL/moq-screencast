package com.example.moqandroid.publish.screen

import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.audio.AudioPublishConfig
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy

data class ScreenPublishConfig(
    val video: ScreenVideoConfig,
    val audio: AudioPublishConfig? = null,
)

data class ScreenVideoConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val bitrate: Int = 4_000_000,
    val frameRate: Int = 30,
    val iFrameIntervalSeconds: Int = 1,
    val encoderPolicy: VideoEncoderPolicy = VideoEncoderPolicy.Default,
    val h264ProfilePreference: H264ProfilePreference = H264ProfilePreference.High,
)

fun ScreenVideoConfig.encoderConfig(): VideoPublishConfig {
    return VideoPublishConfig(
        width = width,
        height = height,
        bitrate = bitrate,
        frameRate = frameRate,
        iFrameIntervalSeconds = iFrameIntervalSeconds,
        encoderPolicy = encoderPolicy,
        h264ProfilePreference = h264ProfilePreference,
    )
}

fun VideoPublishConfig.withScreenSize(sourceWidth: Int, sourceHeight: Int): VideoPublishConfig {
    val longestEdge = maxOf(sourceWidth, sourceHeight)
    val scale = minOf(MAX_PUBLISH_LONG_EDGE.toFloat() / longestEdge, 1f)
    val alignment = encoderPolicy.dimensionAlignment
    val width = (sourceWidth * scale).toInt().roundDownTo(alignment).coerceAtLeast(alignment)
    val height = (sourceHeight * scale).toInt().roundDownTo(alignment).coerceAtLeast(alignment)
    return copy(width = width, height = height)
}

private fun Int.roundDownTo(alignment: Int): Int = this - (this % alignment)

private const val MAX_PUBLISH_LONG_EDGE = 1080
