package com.example.moqandroid.publish.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.example.moqandroid.publish.PublisherEvent
import com.example.moqandroid.publish.PublisherLifecycleEventSink
import com.example.moqandroid.publish.PublisherState
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishSource
import com.example.moqandroid.publish.VideoPublishTransition
import com.example.moqandroid.publish.screen.SystemAudioConfig
import com.example.moqandroid.protocol.VideoLayoutEvent
import com.example.moqandroid.protocol.VideoLayoutPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.moq.MoqMediaStreamProducer
import uniffi.moq.MoqTrackProducer
import kotlin.coroutines.coroutineContext

class SurfaceVideoEncoder(
    private val source: VideoPublishSource,
    private val media: MoqMediaStreamProducer,
    private val videoLayout: MoqTrackProducer,
    private val relayUrl: String,
    private val lifecycle: PublisherLifecycleEventSink,
) {
    private val attemptPlanner = H264EncoderAttemptPlanner()

    suspend fun run(
        config: VideoPublishConfig,
        broadcastName: String,
        audioConfig: SystemAudioConfig,
    ) = withContext(Dispatchers.Default) {
        val stats = PublishStatsTracker(relayUrl, broadcastName)
        var activeConfig = config
        var activeGeneration: Long? = null
        var trackStarted = false

        try {
            while (coroutineContext.isActive) {
                val nextConfig = runAttempts(
                    config = activeConfig,
                    broadcastName = broadcastName,
                    audioConfig = audioConfig,
                    stats = stats,
                    generation = activeGeneration,
                    onEncodingStarted = {
                        if (!trackStarted) {
                            lifecycle.emit(PublisherEvent.TrackStarted(VIDEO_TRACK_NAME))
                            trackStarted = true
                        }
                    },
                ) ?: break
                Log.i(
                    LOG_TAG,
                    "restarting H.264 encoder for screen resize " +
                        "from=${activeConfig.width}x${activeConfig.height} " +
                        "to=${nextConfig.config.width}x${nextConfig.config.height} " +
                        "generation=${nextConfig.generation} track=reused",
                )
                activeConfig = nextConfig.config
                activeGeneration = nextConfig.generation
            }
        } finally {
            if (trackStarted) lifecycle.emit(PublisherEvent.TrackStopped(VIDEO_TRACK_NAME))
            runCatching { media.finish() }
        }
    }

    private suspend fun runAttempts(
        config: VideoPublishConfig,
        broadcastName: String,
        audioConfig: SystemAudioConfig,
        stats: PublishStatsTracker,
        generation: Long?,
        onEncodingStarted: () -> Unit,
    ): VideoPublishTransition? {
        var lastError: Throwable? = null
        val attempts = attemptPlanner.attempts(config)
        for ((index, attempt) in attempts.withIndex()) {
            val isFallbackAvailable = index < attempts.lastIndex
            var codec: MediaCodec? = null
            var inputSurface: Surface? = null
            var codecStarted = false
            var sourceAttached = false
            var encodingStarted = false

            try {
                Log.i(
                    LOG_TAG,
                    "Starting H.264 encoder attempt ${index + 1}/${attempts.size} " +
                        "encoder=${attempt.encoderName} profile=${attempt.profileName} " +
                        "width=${attempt.config.width} height=${attempt.config.height} " +
                        "fps=${attempt.config.frameRate} bitrate=${attempt.config.bitrate} " +
                        "policy=${attempt.config.encoderPolicy.storageValue} " +
                        "supportsHigh=${attempt.capability.supportsHigh} " +
                        "supportsBaseline=${attempt.capability.supportsBaseline} " +
                        "supportsFormat=${attempt.capability.supportsRequestedFormat}",
                )
                codec = MediaCodec.createEncoderByType(MIME_AVC)
                codec.configure(attempt.mediaFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = codec.createInputSurface()
                codec.start()
                codecStarted = true

                source.attachEncoderSurface(inputSurface, attempt.config)
                sourceAttached = true
                codec.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })

                onEncodingStarted()
                lifecycle.update(
                    PublisherState.Publishing(
                        relayUrl = relayUrl,
                        broadcastName = broadcastName,
                        width = attempt.config.width,
                        height = attempt.config.height,
                        bitrate = attempt.config.bitrate,
                        frameRate = attempt.config.frameRate,
                        audioEnabled = audioConfig is SystemAudioConfig.Enabled,
                    ),
                )
                encodingStarted = true
                return drain(codec, media, stats, lifecycle, attempt.config, generation)
            } catch (error: Throwable) {
                if (encodingStarted && error !is CancellationException) {
                    lifecycle.emit(PublisherEvent.TrackError(VIDEO_TRACK_NAME, error.message ?: error::class.java.name))
                }
                if (error is CancellationException || encodingStarted || !isFallbackAvailable) throw error

                lastError = error

                Log.w(
                    LOG_TAG,
                    "H.264 encoder configure failed, retrying with fallback " +
                        "width=${attempt.config.width} height=${attempt.config.height} " +
                        "fps=${attempt.config.frameRate} bitrate=${attempt.config.bitrate} " +
                        "profile=${attempt.profileName} policy=${attempt.config.encoderPolicy.storageValue} " +
                        "encoder=${attempt.encoderName}",
                    error,
                )
            } finally {
                if (sourceAttached) source.detachEncoderSurface()
                if (codecStarted) runCatching { codec?.stop() }
                codec?.release()
                inputSurface?.release()
            }
        }

        throw lastError ?: IllegalStateException("H.264 encoder did not start.")
    }

    private fun EncoderAttempt.mediaFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
        }
    }

    private suspend fun drain(
        codec: MediaCodec,
        media: MoqMediaStreamProducer,
        stats: PublishStatsTracker,
        lifecycle: PublisherLifecycleEventSink,
        activeConfig: VideoPublishConfig,
        generation: Long?,
    ): VideoPublishTransition? {
        val info = MediaCodec.BufferInfo()
        var codecConfig: ByteArray? = null
        var awaitingKeyFrame = true
        var loggedDiscardedDelta = false
        var outputWasSuspended = false
        var layoutReadySent = generation == null
        while (coroutineContext.isActive) {
            publishPendingLayoutEvents()
            source.pollConfigChange()?.let { nextConfig ->
                if (
                    nextConfig.config.width != activeConfig.width ||
                    nextConfig.config.height != activeConfig.height
                ) {
                    return nextConfig
                }
            }
            when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    val spsBytes = outputFormat.getByteBuffer("csd-0")?.remaining() ?: 0
                    val ppsBytes = outputFormat.getByteBuffer("csd-1")?.remaining() ?: 0
                    codecConfig = outputFormat.h264CodecConfig()
                    Log.i(
                        LOG_TAG,
                        "H.264 encoder output format " +
                            "size=${outputFormat.integerOrUnknown(MediaFormat.KEY_WIDTH)}x" +
                            "${outputFormat.integerOrUnknown(MediaFormat.KEY_HEIGHT)} " +
                            "spsBytes=$spsBytes ppsBytes=$ppsBytes " +
                            "catalogFormat=avc3 catalogRotation=unset",
                    )
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

                        if (source.isOutputSuspended()) {
                            if (!outputWasSuspended) {
                                Log.i(
                                    LOG_TAG,
                                    "suspending H.264 output for screen resize " +
                                        "size=${activeConfig.width}x${activeConfig.height}",
                                )
                            }
                            outputWasSuspended = true
                            continue
                        }

                        if (outputWasSuspended) {
                            outputWasSuspended = false
                            awaitingKeyFrame = true
                            loggedDiscardedDelta = false
                            codec.setParameters(Bundle().apply {
                                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                            })
                            Log.i(
                                LOG_TAG,
                                "screen resize cancelled, waiting for a fresh H.264 IDR " +
                                    "size=${activeConfig.width}x${activeConfig.height}",
                            )
                        }

                        val keyFrame = flags.hasKeyFrame()
                        if (awaitingKeyFrame && !keyFrame) {
                            if (!loggedDiscardedDelta) {
                                Log.w(LOG_TAG, "discarding H.264 delta frames until the restarted encoder emits an IDR")
                                loggedDiscardedDelta = true
                            }
                            continue
                        }

                        val frame = if (keyFrame) annexB.withParameterSets(codecConfig) else annexB
                        if (awaitingKeyFrame) {
                            awaitingKeyFrame = false
                            Log.i(
                                LOG_TAG,
                                "H.264 encoder IDR ready size=${activeConfig.width}x${activeConfig.height} " +
                                    "parameterSetsBytes=${codecConfig?.size ?: 0} " +
                                    "presentationTimeUs=${info.presentationTimeUs}",
                            )
                        }
                        media.write(frame)
                        if (!layoutReadySent && generation != null) {
                            publishLayoutEvent(
                                VideoLayoutEvent(
                                    phase = VideoLayoutPhase.Ready,
                                    generation = generation,
                                    width = activeConfig.width,
                                    height = activeConfig.height,
                                    rotation = null,
                                ),
                            )
                            source.onLayoutReady(generation)
                            layoutReadySent = true
                        }
                        stats.onFrame(frame.size, lifecycle::emit)
                    }
                }
            }
        }
        return null
    }

    private fun publishPendingLayoutEvents() {
        while (true) {
            val event = source.pollLayoutEvent() ?: return
            publishLayoutEvent(event)
        }
    }

    private fun publishLayoutEvent(event: VideoLayoutEvent) {
        runCatching { videoLayout.writeFrame(event.encode()) }
            .onSuccess {
                Log.i(
                    LOG_TAG,
                    "video layout ${event.phase.wireValue} generation=${event.generation} " +
                        "target=${event.width}x${event.height} rotation=${event.rotation ?: "none"}",
                )
            }
            .onFailure { error ->
                Log.w(
                    LOG_TAG,
                    "video layout event failed phase=${event.phase.wireValue} " +
                        "generation=${event.generation}",
                    error,
                )
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

    private fun MediaFormat.integerOrUnknown(key: String): String {
        return if (containsKey(key)) getInteger(key).toString() else "unknown"
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
        private const val LOG_TAG = "MoqAndroid"
        private const val MIME_AVC = "video/avc"
        private const val VIDEO_TRACK_NAME = "video"
    }
}
