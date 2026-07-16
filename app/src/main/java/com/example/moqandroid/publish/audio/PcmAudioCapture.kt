package com.example.moqandroid.publish.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.moq.MoqAudioFrame
import uniffi.moq.MoqAudioProducer
import kotlin.coroutines.coroutineContext

internal class PcmAudioCapture(
    private val producer: MoqAudioProducer,
    private val config: AudioPublishConfig,
    private val sourceLabel: String,
    private val logTag: String,
    private val createRecord: (AudioFormat, Int) -> AudioRecord,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(config.sampleRate)
            .setChannelMask(config.channelInMask())
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelInMask(),
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "Android cannot create $sourceLabel audio buffer." }
        val bufferSize = maxOf(minBuffer * 2, config.bytesPerFrame() * 4)
        Log.i(
            logTag,
            "$sourceLabel audio config sampleRate=${config.sampleRate} channels=${config.channelCount} " +
                "frameDurationMs=${config.frameDurationMs} bitrate=${config.bitrate} " +
                "minBuffer=$minBuffer bufferSize=$bufferSize",
        )
        Log.i(
            logTag,
            "$sourceLabel audio pipeline capture=AudioRecord output=pcm_s16 encoder=moq-native codec=opus",
        )

        val record = createRecord(audioFormat, bufferSize)
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "Android could not initialize $sourceLabel audio capture."
        }
        val buffer = ByteArray(config.bytesPerFrame())
        val stats = AudioCaptureStats(sourceLabel, logTag)
        var submittedSamples = 0L

        try {
            Log.i(
                logTag,
                "$sourceLabel audio capture created state=${record.state} " +
                    "recordingState=${record.recordingState} sessionId=${record.audioSessionId}",
            )
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android did not start $sourceLabel audio capture."
            }
            Log.i(logTag, "$sourceLabel audio capture started recordingState=${record.recordingState}")
            while (coroutineContext.isActive) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read < 0) error("$sourceLabel audio capture read failed with code $read.")
                if (read == 0) continue

                val data = buffer.copyOf(read)
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
}

private class AudioCaptureStats(
    private val sourceLabel: String,
    private val logTag: String,
) {
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
            "$sourceLabel audio capture frames=$frames bytes=$bytes nonZeroBytes=$nonZeroBytes " +
                "peak=$peak silentSeconds=$silentSeconds",
        )

        frames = 0
        bytes = 0
        nonZeroBytes = 0
        peak = 0
        lastUpdateMs = now
    }
}
