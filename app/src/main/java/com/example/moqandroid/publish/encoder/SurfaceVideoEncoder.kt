package com.example.moqandroid.publish.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import com.example.moqandroid.publish.PublishState
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishSource
import com.example.moqandroid.publish.screen.SystemAudioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.moq.MoqMediaStreamProducer
import kotlin.coroutines.coroutineContext

class SurfaceVideoEncoder(
    private val source: VideoPublishSource,
    private val media: MoqMediaStreamProducer,
    private val relayUrl: String,
    private val status: (PublishState) -> Unit,
) {
    suspend fun run(
        config: VideoPublishConfig,
        broadcastName: String,
        audioConfig: SystemAudioConfig,
    ) = withContext(Dispatchers.Default) {
        val codec = MediaCodec.createEncoderByType(MIME_AVC)
        var codecStarted = false
        val stats = PublishStatsTracker(relayUrl, broadcastName)

        try {
            val format = MediaFormat.createVideoFormat(MIME_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()
            codecStarted = true

            source.attachEncoderSurface(inputSurface, config)
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })

            status(
                PublishState.Publishing(
                    relayUrl = relayUrl,
                    broadcastName = broadcastName,
                    width = config.width,
                    height = config.height,
                    bitrate = config.bitrate,
                    frameRate = config.frameRate,
                    audioEnabled = audioConfig is SystemAudioConfig.Enabled,
                ),
            )
            drain(codec, media, stats, status)
        } finally {
            status(PublishState.Stopping)
            source.detachEncoderSurface()
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            runCatching { media.finish() }
        }
    }

    private suspend fun drain(
        codec: MediaCodec,
        media: MoqMediaStreamProducer,
        stats: PublishStatsTracker,
        status: (PublishState) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var codecConfig: ByteArray? = null
        while (coroutineContext.isActive) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    codecConfig = codec.outputFormat.h264CodecConfig()
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val payload = codec.getOutputBuffer(outputIndex)?.readBytes(info) ?: ByteArray(0)
                    val flags = info.flags
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (payload.isNotEmpty()) {
                        val annexB = payload.toAnnexB()
                        if (flags.hasCodecConfig()) {
                            codecConfig = annexB
                            continue
                        }

                        val frame = if (flags.hasKeyFrame()) annexB.withParameterSets(codecConfig) else annexB
                        media.write(frame)
                        stats.onFrame(frame.size) { status(it) }
                    }
                }
            }
        }
    }

    private fun java.nio.ByteBuffer.readBytes(info: MediaCodec.BufferInfo): ByteArray {
        position(info.offset)
        limit(info.offset + info.size)
        return ByteArray(info.size).also { get(it) }
    }

    private fun MediaFormat.h264CodecConfig(): ByteArray? {
        val sps = getByteBuffer("csd-0")?.readRemainingBytes()?.toAnnexB()
        val pps = getByteBuffer("csd-1")?.readRemainingBytes()?.toAnnexB()
        return when {
            sps != null && pps != null -> sps + pps
            sps != null -> sps
            pps != null -> pps
            else -> null
        }
    }

    private fun java.nio.ByteBuffer.readRemainingBytes(): ByteArray {
        val duplicate = duplicate()
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    private fun Int.hasCodecConfig(): Boolean {
        return this and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
    }

    private fun Int.hasKeyFrame(): Boolean {
        return this and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    }

    companion object {
        private const val MIME_AVC = "video/avc"
    }
}
