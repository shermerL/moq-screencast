package com.example.moqandroid.publish.screen

import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.example.moqandroid.publish.audio.AudioPublishConfig
import com.example.moqandroid.publish.audio.AudioPublishSource
import com.example.moqandroid.publish.audio.PcmAudioCapture
import uniffi.moq.MoqAudioProducer

internal class SystemAudioCapture(
    private val projection: MediaProjection,
    override val config: AudioPublishConfig,
    private val logTag: String,
) : AudioPublishSource {
    override suspend fun capture(producer: MoqAudioProducer) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "System audio capture requires Android 10+."
        }
        PcmAudioCapture(
            producer = producer,
            config = config,
            sourceLabel = "system audio",
            logTag = logTag,
            createRecord = { audioFormat, bufferSize ->
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(playbackCaptureConfig())
                    .build()
            },
        ).run()
    }

    private fun playbackCaptureConfig(): AudioPlaybackCaptureConfiguration {
        Log.i(logTag, "system audio capture usages=MEDIA,GAME,UNKNOWN")
        return AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
    }

}
