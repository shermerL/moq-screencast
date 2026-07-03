package com.example.moqandroid.publish.screen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.moq.MoqAudioCodec
import uniffi.moq.MoqAudioEncoderInput
import uniffi.moq.MoqAudioEncoderOutput
import uniffi.moq.MoqAudioFormat
import uniffi.moq.MoqAudioFrame
import uniffi.moq.MoqAudioProducer
import kotlin.coroutines.coroutineContext

class SystemAudioCapture(
    private val projection: MediaProjection,
    private val producer: MoqAudioProducer,
    private val config: SystemAudioConfig.Enabled,
    private val logTag: String,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "System audio capture requires Android 10+."
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(config.sampleRate)
            .setChannelMask(config.channelInMask())
            .build()
        Log.i(
            logTag,
            "system audio capture config sampleRate=${config.sampleRate} channels=${config.channelCount} " +
                "frameDurationMs=${config.frameDurationMs} bitrate=${config.bitrate}",
        )
        Log.i(
            logTag,
            "system audio pipeline capture=AudioRecord output=pcm_s16 encoder=moq-native codec=opus",
        )
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelInMask(),
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "Android cannot create system audio buffer." }
        val bufferSize = maxOf(minBuffer * 2, config.bytesPerFrame() * 4)
        Log.i(logTag, "system audio capture minBuffer=$minBuffer bufferSize=$bufferSize")

        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(playbackCaptureConfig())
            .build()

        val buffer = ByteArray(config.bytesPerFrame())
        var submittedSamples = 0L

        try {
            Log.i(
                logTag,
                "system audio capture created state=${record.state} recordingState=${record.recordingState} sessionId=${record.audioSessionId}",
            )
            record.startRecording()
            Log.i(logTag, "system audio capture started recordingState=${record.recordingState}")
            while (coroutineContext.isActive) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    Log.w(logTag, "system audio capture read returned $read")
                    continue
                }

                val data = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                val timestampUs = submittedSamples * 1_000_000L / config.sampleRate
                producer.write(
                    MoqAudioFrame(
                        timestampUs = timestampUs.toULong(),
                        data = data,
                    ),
                )
                stats.onFrame(data)
                submittedSamples += read / config.bytesPerSampleFrame()
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun playbackCaptureConfig(): AudioPlaybackCaptureConfiguration {
        Log.i(logTag, "system audio capture usages=MEDIA,GAME,UNKNOWN")
        return AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
    }

    private val stats = AudioCaptureStats(logTag)
}

private class AudioCaptureStats(private val logTag: String) {
    private var frames = 0
    private var bytes = 0L
    private var nonZeroBytes = 0L
    private var peak = 0
    private var silentSeconds = 0
    private var lastUpdateMs = SystemClock.elapsedRealtime()

    fun onFrame(data: ByteArray) {
        frames += 1
        bytes += data.size

        var index = 0
        while (index + 1 < data.size) {
            val low = data[index].toInt() and 0xff
            val sample = (data[index + 1].toInt() shl 8) or low
            val normalized = sample.toShort().toInt()
            if (normalized != 0) nonZeroBytes += 2
            peak = maxOf(peak, kotlin.math.abs(normalized))
            index += 2
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateMs < 1_000) return

        val isSilent = nonZeroBytes == 0L || peak == 0
        silentSeconds = if (isSilent) silentSeconds + 1 else 0
        Log.i(
            logTag,
            "system audio capture frames=$frames bytes=$bytes nonZeroBytes=$nonZeroBytes peak=$peak silentSeconds=$silentSeconds",
        )

        frames = 0
        bytes = 0
        nonZeroBytes = 0
        peak = 0
        lastUpdateMs = now
    }
}

fun SystemAudioConfig.Enabled.encoderInput(): MoqAudioEncoderInput {
    return MoqAudioEncoderInput(
        format = MoqAudioFormat.S16,
        sampleRate = sampleRate.toUInt(),
        channels = channelCount.toUInt(),
    )
}

fun SystemAudioConfig.Enabled.encoderOutput(): MoqAudioEncoderOutput {
    return MoqAudioEncoderOutput(
        codec = MoqAudioCodec.OPUS,
        sampleRate = sampleRate.toUInt(),
        channels = channelCount.toUInt(),
        bitrate = bitrate.toUInt(),
        frameDurationMs = frameDurationMs.toUInt(),
    )
}

private fun SystemAudioConfig.Enabled.channelInMask(): Int {
    return when (channelCount) {
        1 -> AudioFormat.CHANNEL_IN_MONO
        2 -> AudioFormat.CHANNEL_IN_STEREO
        else -> error("Only mono or stereo system audio is supported.")
    }
}

private fun SystemAudioConfig.Enabled.bytesPerSampleFrame(): Int = channelCount * 2

private fun SystemAudioConfig.Enabled.bytesPerFrame(): Int {
    return sampleRate * frameDurationMs / 1_000 * bytesPerSampleFrame()
}
