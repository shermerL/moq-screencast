package com.example.moqandroid.publish.audio

import android.media.AudioFormat
import uniffi.moq.MoqAudioCodec
import uniffi.moq.MoqAudioEncoderInput
import uniffi.moq.MoqAudioEncoderOutput
import uniffi.moq.MoqAudioFormat

data class AudioPublishConfig(
    val sampleRate: Int = 48_000,
    val channelCount: Int = 2,
    val bitrate: Int = 96_000,
    val frameDurationMs: Int = 20,
) {
    fun encoderInput(): MoqAudioEncoderInput {
        return MoqAudioEncoderInput(
            format = MoqAudioFormat.S16,
            sampleRate = sampleRate.toUInt(),
            channels = channelCount.toUInt(),
        )
    }

    fun encoderOutput(): MoqAudioEncoderOutput {
        return MoqAudioEncoderOutput(
            codec = MoqAudioCodec.OPUS,
            sampleRate = sampleRate.toUInt(),
            channels = channelCount.toUInt(),
            bitrate = bitrate.toUInt(),
            frameDurationMs = frameDurationMs.toUInt(),
        )
    }

    fun channelInMask(): Int {
        return when (channelCount) {
            1 -> AudioFormat.CHANNEL_IN_MONO
            2 -> AudioFormat.CHANNEL_IN_STEREO
            else -> error("Only mono or stereo audio is supported.")
        }
    }

    fun bytesPerSampleFrame(): Int = channelCount * PCM_16_BYTES_PER_SAMPLE

    fun bytesPerFrame(): Int {
        return sampleRate * frameDurationMs / 1_000 * bytesPerSampleFrame()
    }

    companion object {
        private const val PCM_16_BYTES_PER_SAMPLE = 2

        fun systemAudio(): AudioPublishConfig = AudioPublishConfig()

        fun microphone(): AudioPublishConfig = AudioPublishConfig(
            channelCount = 1,
            bitrate = 64_000,
        )
    }
}
