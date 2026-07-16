package com.example.moqandroid.publish.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import uniffi.moq.MoqAudioProducer

internal class MicrophoneAudioCapture(
    override val config: AudioPublishConfig,
    private val logTag: String,
) : AudioPublishSource {
    @SuppressLint("MissingPermission")
    override suspend fun capture(producer: MoqAudioProducer) {
        PcmAudioCapture(
            producer = producer,
            config = config,
            sourceLabel = "microphone",
            logTag = logTag,
            createRecord = { audioFormat, bufferSize ->
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            },
        ).run()
    }
}
