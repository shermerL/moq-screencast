package com.example.moqandroid.publish.screen

import com.example.moqandroid.publish.VideoPublishConfig

data class ScreenPublishConfig(
    val video: ScreenVideoConfig,
    val audio: SystemAudioConfig = SystemAudioConfig.Disabled,
)

data class ScreenVideoConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val bitrate: Int = 4_000_000,
    val frameRate: Int = 30,
    val iFrameIntervalSeconds: Int = 1,
)

fun ScreenVideoConfig.encoderConfig(): VideoPublishConfig {
    return VideoPublishConfig(
        width = width,
        height = height,
        bitrate = bitrate,
        frameRate = frameRate,
        iFrameIntervalSeconds = iFrameIntervalSeconds,
    )
}

sealed class SystemAudioConfig {
    data object Disabled : SystemAudioConfig()

    data class Enabled(
        val sampleRate: Int = 48_000,
        val channelCount: Int = 2,
        val bitrate: Int = 96_000,
        val frameDurationMs: Int = 20,
    ) : SystemAudioConfig()
}
