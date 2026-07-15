package com.example.moqandroid.playback

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.example.moqandroid.catalog.PlayableVideoInfo
import com.example.moqandroid.media.AvcConfig
import com.example.moqandroid.media.payloadForDecoder
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import uniffi.moq.MoqMediaConsumer
import kotlin.coroutines.coroutineContext
import kotlin.math.min

internal class VideoDecoder(private val status: (PlayerState) -> Unit) {
    suspend fun decodeLoop(
        config: VideoDecodeConfig,
        callbacks: VideoDecodeCallbacks,
    ): VideoDecodeResult = coroutineScope {
        val info = MediaCodec.BufferInfo()
        val stats = PlaybackStats(config.videoInfo)
        val transitionState = VideoTrackTransitionState(
            initialVideoInfo = config.videoInfo,
            initialFrameTransitionId = config.initialFrameTransitionId,
            allowAdaptiveSizeChanges = config.allowAdaptiveSizeChanges,
        )
        val transitionTrace = DecoderTransitionTrace()
        val runtime = VideoDecodeRuntime(config, callbacks, info, stats, transitionState, transitionTrace)

        while (coroutineContext.isActive) {
            transitionState.consumeCatalogUpdates(config.videoTrackUpdates, callbacks.nextTransitionId, transitionTrace)
                ?.let { return@coroutineScope it }
            val frame = config.media.next() ?: break
            val payload = frame.payloadForDecoder(config.avcConfig)
            transitionTrace.onInputFrame(frame.timestampUs.toLong(), frame.keyframe, payload.size)
            stats.onFrameReceived(payload.size)

            var queued = false
            while (!queued) {
                transitionState.consumeCatalogUpdates(config.videoTrackUpdates, callbacks.nextTransitionId, transitionTrace)
                    ?.let { return@coroutineScope it }
                val inputIndex = config.codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    config.codec.getInputBuffer(inputIndex)?.apply {
                        clear()
                        put(payload)
                    }
                    config.codec.queueInputBuffer(
                        inputIndex,
                        0,
                        payload.size,
                        frame.timestampUs.toLong(),
                        if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                    queued = true
                }
                val drainResult = drainDecoder(runtime)
                stats.renderedFrames += drainResult.renderedFrames
            }

            transitionState.consumeCatalogUpdates(config.videoTrackUpdates, callbacks.nextTransitionId, transitionTrace)
                ?.let { return@coroutineScope it }
            val drainResult = drainDecoder(runtime)
            stats.renderedFrames += drainResult.renderedFrames
            stats.flushIfDue { status(PlayerState.Stats(it)) }
        }

        VideoDecodeResult.StreamEnded
    }

    private suspend fun drainDecoder(runtime: VideoDecodeRuntime): VideoDrainResult {
        var rendered = 0
        while (true) {
            when (val outputIndex = runtime.config.codec.dequeueOutputBuffer(runtime.info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    runtime.transitionState.finishDiscardingOutputBacklogIfDrained()
                    return VideoDrainResult(rendered)
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = runtime.config.codec.outputFormat
                    outputFormat.videoSize()?.let { (width, height) ->
                        Log.i(
                            LOG_TAG,
                            "rotationTrace=${runtime.transitionTrace.transitionId} decoder output format " +
                                "size=${width}x$height raw=${outputFormat.describeVideoSize()}",
                        )
                        runtime.transitionState.onOutputFormat(
                            width,
                            height,
                            runtime.callbacks.nextTransitionId,
                            runtime.transitionTrace,
                        )?.let { transition ->
                            runtime.stats.updateVideoInfo(transition.videoInfo)
                            val discardBacklog = runtime.callbacks.onVideoFormatTransition(
                                transition.videoInfo,
                                transition.transitionId,
                            )
                            if (discardBacklog) {
                                runtime.transitionState.beginDiscardingOutputBacklog(transition.transitionId)
                            }
                            runtime.transitionState.armFirstFrame(transition.transitionId)
                        }
                    }
                    return VideoDrainResult(rendered)
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> return VideoDrainResult(rendered)
                else -> if (outputIndex >= 0) {
                    val discardBacklog = runtime.transitionState.isDiscardingOutputBacklog()
                    val decision = if (discardBacklog) {
                        VideoRenderDecision(render = false)
                    } else {
                        videoRenderDecision(runtime.info.presentationTimeUs, runtime.config.audioClock)
                    }
                    runtime.transitionTrace.onOutputFrame(
                        presentationTimeUs = runtime.info.presentationTimeUs,
                        size = runtime.info.size,
                        renderDecision = decision.render,
                    )
                    if (runtime.info.size > 0 && decision.render) {
                        runtime.transitionState.takeFirstFrameTransition()?.let { transitionId ->
                            runtime.callbacks.onTransitionFrameQueued(transitionId, runtime.info.presentationTimeUs)
                        }
                        if (decision.renderTimeNs != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            runtime.config.codec.releaseOutputBuffer(outputIndex, decision.renderTimeNs)
                        } else {
                            runtime.config.codec.releaseOutputBuffer(outputIndex, true)
                        }
                        rendered += 1
                    } else {
                        runtime.config.codec.releaseOutputBuffer(outputIndex, false)
                        if (discardBacklog) runtime.transitionState.onBacklogOutputDiscarded()
                    }
                }
            }
        }
    }

    private fun MediaFormat.videoSize(): Pair<Int, Int>? {
        cropSize()?.let { return it }
        val width = integerOrNull(MediaFormat.KEY_WIDTH)?.takeIf { it > 0 } ?: return null
        val height = integerOrNull(MediaFormat.KEY_HEIGHT)?.takeIf { it > 0 } ?: return null
        return width to height
    }

    private fun MediaFormat.cropSize(): Pair<Int, Int>? {
        val left = integerOrNull("crop-left") ?: return null
        val right = integerOrNull("crop-right") ?: return null
        val top = integerOrNull("crop-top") ?: return null
        val bottom = integerOrNull("crop-bottom") ?: return null
        val width = (right - left + 1).takeIf { it > 0 } ?: return null
        val height = (bottom - top + 1).takeIf { it > 0 } ?: return null
        return width to height
    }

    private fun MediaFormat.describeVideoSize(): String {
        val width = integerOrNull(MediaFormat.KEY_WIDTH)
        val height = integerOrNull(MediaFormat.KEY_HEIGHT)
        val cropLeft = integerOrNull("crop-left")
        val cropRight = integerOrNull("crop-right")
        val cropTop = integerOrNull("crop-top")
        val cropBottom = integerOrNull("crop-bottom")
        return "${width ?: "unknown"}x${height ?: "unknown"} " +
            "crop=[$cropLeft,$cropTop,$cropRight,$cropBottom]"
    }

    private fun MediaFormat.integerOrNull(key: String): Int? {
        return if (containsKey(key)) getInteger(key) else null
    }

    private fun videoRenderDecision(presentationTimeUs: Long, audioClock: AudioPlaybackClock?): VideoRenderDecision {
        val audioTimeUs = audioClock?.positionUs() ?: return VideoRenderDecision(render = true)
        val deltaUs = presentationTimeUs - audioTimeUs
        if (deltaUs < -VIDEO_DROP_LATE_US) return VideoRenderDecision(render = false)

        if (deltaUs > VIDEO_RENDER_EARLY_US) {
            Thread.sleep(min((deltaUs - VIDEO_RENDER_EARLY_US) / 1_000, VIDEO_MAX_SLEEP_MS))
        }

        val refreshedAudioTimeUs = audioClock.positionUs() ?: audioTimeUs
        val refreshedDeltaUs = presentationTimeUs - refreshedAudioTimeUs
        if (refreshedDeltaUs < -VIDEO_DROP_LATE_US) return VideoRenderDecision(render = false)

        val renderTimeNs = audioClock.nanoTimeFor(presentationTimeUs)
        return VideoRenderDecision(render = true, renderTimeNs = renderTimeNs)
    }
}

internal data class VideoDecodeConfig(
    val codec: MediaCodec,
    val media: MoqMediaConsumer,
    val avcConfig: AvcConfig?,
    val videoInfo: PlayableVideoInfo,
    val videoTrackUpdates: ReceiveChannel<PlaybackVideoTrackUpdate>,
    val audioClock: AudioPlaybackClock?,
    val allowAdaptiveSizeChanges: Boolean,
    val initialFrameTransitionId: Int?,
)

internal data class VideoDecodeCallbacks(
    val nextTransitionId: () -> Int,
    val onVideoFormatTransition: suspend (PlayableVideoInfo, Int) -> Boolean,
    val onTransitionFrameQueued: (Int, Long) -> Unit,
)

private data class VideoDecodeRuntime(
    val config: VideoDecodeConfig,
    val callbacks: VideoDecodeCallbacks,
    val info: MediaCodec.BufferInfo,
    val stats: PlaybackStats,
    val transitionState: VideoTrackTransitionState,
    val transitionTrace: DecoderTransitionTrace,
)

private data class VideoRenderDecision(
    val render: Boolean,
    val renderTimeNs: Long? = null,
)

private data class VideoDrainResult(
    val renderedFrames: Int,
)

private class VideoTrackTransitionState(
    initialVideoInfo: PlayableVideoInfo,
    initialFrameTransitionId: Int?,
    private val allowAdaptiveSizeChanges: Boolean,
) {
    private var activeVideoInfo = initialVideoInfo
    private var pendingTransition: PendingVideoTransition? = null
    private var outputSize: Pair<Int, Int>? = null
    private var firstFrameTransitionId = initialFrameTransitionId
    private var discardingOutputBacklog: OutputBacklogDiscard? = null

    fun beginDiscardingOutputBacklog(transitionId: Int) {
        discardingOutputBacklog = OutputBacklogDiscard(transitionId)
    }

    fun isDiscardingOutputBacklog(): Boolean = discardingOutputBacklog != null

    fun onBacklogOutputDiscarded() {
        discardingOutputBacklog?.let { it.discardedFrames += 1 }
    }

    fun finishDiscardingOutputBacklogIfDrained() {
        val discard = discardingOutputBacklog ?: return
        if (discard.discardedFrames == 0) return
        discardingOutputBacklog = null
        Log.i(
            LOG_TAG,
            "rotationTrace=${discard.transitionId} decoder output backlog drained " +
                "discardedFrames=${discard.discardedFrames}",
        )
    }

    fun armFirstFrame(transitionId: Int) {
        firstFrameTransitionId = transitionId
    }

    fun takeFirstFrameTransition(): Int? {
        val transitionId = firstFrameTransitionId
        firstFrameTransitionId = null
        return transitionId
    }

    fun consumeCatalogUpdates(
        videoTrackUpdates: ReceiveChannel<PlaybackVideoTrackUpdate>,
        nextTransitionId: () -> Int,
        transitionTrace: DecoderTransitionTrace,
    ): VideoDecodeResult.RestartDecoder? {
        var latest: PlaybackVideoTrackUpdate? = null
        while (true) {
            latest = videoTrackUpdates.tryReceive().getOrNull() ?: break
        }
        val update = latest ?: return null
        val nextVideoInfo = update.videoInfo

        if (activeVideoInfo.mime != nextVideoInfo.mime) {
            Log.i(
                LOG_TAG,
                "restarting decoder for codec change ${activeVideoInfo.mime} -> ${nextVideoInfo.mime}",
            )
            return VideoDecodeResult.RestartDecoder(update, nextVideoInfo)
        }

        if (activeVideoInfo.hasSameDisplaySize(nextVideoInfo)) {
            activeVideoInfo = nextVideoInfo
            pendingTransition = null
            return null
        }

        if (!allowAdaptiveSizeChanges) {
            Log.i(
                LOG_TAG,
                "restarting decoder because adaptive playback is unavailable " +
                    "${activeVideoInfo.displayWidth ?: "unknown"}x${activeVideoInfo.displayHeight ?: "unknown"} -> " +
                    "${nextVideoInfo.displayWidth ?: "unknown"}x${nextVideoInfo.displayHeight ?: "unknown"}",
            )
            return VideoDecodeResult.RestartDecoder(update, nextVideoInfo)
        }

        val transitionId = pendingTransition
            ?.takeIf { it.update.videoInfo.hasSameDisplaySize(nextVideoInfo) }
            ?.transitionId
            ?: nextTransitionId()
        pendingTransition = PendingVideoTransition(update, transitionId)
        transitionTrace.begin(transitionId)
        Log.i(
            LOG_TAG,
            "rotationTrace=$transitionId catalog target pending " +
                "${activeVideoInfo.displayWidth ?: "unknown"}x${activeVideoInfo.displayHeight ?: "unknown"} -> " +
                "${nextVideoInfo.displayWidth ?: "unknown"}x${nextVideoInfo.displayHeight ?: "unknown"}",
        )
        return null
    }

    fun onOutputFormat(
        width: Int,
        height: Int,
        nextTransitionId: () -> Int,
        transitionTrace: DecoderTransitionTrace,
    ): VideoFormatTransition? {
        val previousOutputSize = outputSize
        outputSize = width to height
        if (previousOutputSize == null) {
            val pending = pendingTransition ?: return null
            if (!pending.update.matchesOutput(width, height)) return null
            return applyTransition(pending.update.videoInfo, pending.transitionId)
        }
        if (previousOutputSize == outputSize) return null

        val pending = pendingTransition
        if (
            pending != null &&
            (
                pending.update.matchesOutput(width, height) ||
                    pending.update.videoInfo.matchesOutputOrientation(width, height)
            )
        ) {
            return applyTransition(pending.update.videoInfo, pending.transitionId)
        }

        val transitionId = nextTransitionId()
        transitionTrace.begin(transitionId)
        Log.w(
            LOG_TAG,
            "rotationTrace=$transitionId decoder format changed before matching catalog " +
                "actual=${width}x$height",
        )
        return applyTransition(
            activeVideoInfo.copy(displayWidth = width, displayHeight = height),
            transitionId,
            clearPending = false,
        )
    }

    private fun applyTransition(
        nextVideoInfo: PlayableVideoInfo,
        transitionId: Int,
        clearPending: Boolean = true,
    ): VideoFormatTransition {
        activeVideoInfo = nextVideoInfo
        if (clearPending) pendingTransition = null
        Log.i(
            LOG_TAG,
            "rotationTrace=$transitionId decoder generation ready " +
                "display=${nextVideoInfo.displayWidth ?: "unknown"}x${nextVideoInfo.displayHeight ?: "unknown"}",
        )
        return VideoFormatTransition(nextVideoInfo, transitionId)
    }
}

private data class PendingVideoTransition(
    val update: PlaybackVideoTrackUpdate,
    val transitionId: Int,
)

private data class OutputBacklogDiscard(
    val transitionId: Int,
    var discardedFrames: Int = 0,
)

private data class VideoFormatTransition(
    val videoInfo: PlayableVideoInfo,
    val transitionId: Int,
)

private class DecoderTransitionTrace {
    var transitionId: Int = 0
        private set
    private var inputFrames = 0
    private var outputFrames = 0

    fun begin(transitionId: Int) {
        if (this.transitionId == transitionId) return
        this.transitionId = transitionId
        inputFrames = 0
        outputFrames = 0
    }

    fun onInputFrame(timestampUs: Long, keyframe: Boolean, size: Int) {
        if (transitionId == 0 || inputFrames >= TRACE_FRAME_LIMIT) return
        Log.i(
            LOG_TAG,
            "rotationTrace=$transitionId decoder input index=$inputFrames " +
                "timestampUs=$timestampUs keyframe=$keyframe bytes=$size " +
                "elapsedMs=${SystemClock.elapsedRealtime()}",
        )
        inputFrames += 1
    }

    fun onOutputFrame(
        presentationTimeUs: Long,
        size: Int,
        renderDecision: Boolean,
    ) {
        if (transitionId == 0 || outputFrames >= TRACE_FRAME_LIMIT) return
        Log.i(
            LOG_TAG,
            "rotationTrace=$transitionId decoder output index=$outputFrames " +
                "presentationTimeUs=$presentationTimeUs bytes=$size " +
                "renderDecision=$renderDecision " +
                "elapsedMs=${SystemClock.elapsedRealtime()}",
        )
        outputFrames += 1
    }
}

private fun PlayableVideoInfo.hasSameDisplaySize(other: PlayableVideoInfo): Boolean {
    return displayWidth == other.displayWidth && displayHeight == other.displayHeight
}

private fun PlaybackVideoTrackUpdate.matchesOutput(width: Int, height: Int): Boolean {
    val codedWidth = video.video.coded?.width?.toInt()?.takeIf { it > 0 }
    val codedHeight = video.video.coded?.height?.toInt()?.takeIf { it > 0 }
    if (codedWidth != null && codedHeight != null) {
        return codedWidth == width && codedHeight == height
    }

    val displayWidth = videoInfo.displayWidth?.takeIf { it > 0 }
    val displayHeight = videoInfo.displayHeight?.takeIf { it > 0 }
    if (displayWidth != null && displayHeight != null) {
        return displayWidth == width && displayHeight == height
    }

    return videoInfo.outputLandscape() == (width > height)
}

private fun PlayableVideoInfo.matchesOutputOrientation(width: Int, height: Int): Boolean {
    return outputLandscape() == (width > height)
}

private fun PlayableVideoInfo.outputLandscape(): Boolean? {
    val width = displayWidth ?: return null
    val height = displayHeight ?: return null
    if (width <= 0 || height <= 0 || width == height) return null
    return width > height
}

sealed interface VideoDecodeResult {
    data object StreamEnded : VideoDecodeResult
    data class RestartDecoder(
        val update: PlaybackVideoTrackUpdate? = null,
        val videoInfo: PlayableVideoInfo,
    ) : VideoDecodeResult
}

private const val LOG_TAG = "MoqAndroid"
private const val TRACE_FRAME_LIMIT = 32
