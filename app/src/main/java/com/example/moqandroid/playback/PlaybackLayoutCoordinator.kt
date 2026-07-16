package com.example.moqandroid.playback

import com.example.moqandroid.catalog.PlayableVideoInfo
import com.example.moqandroid.protocol.VideoLayoutEvent

interface PlaybackLayoutView {
    fun traceSnapshot(event: String)

    fun prepareVideoLayout(
        event: VideoLayoutEvent,
        onFrozen: () -> Unit,
        onTimeout: () -> Unit,
    )

    fun markVideoLayoutReady(event: VideoLayoutEvent, onReady: () -> Unit)

    fun deferUntilVideoLayoutPrepared(width: Int?, height: Int?, action: () -> Unit): Boolean

    fun cancelVideoLayout(event: VideoLayoutEvent)

    fun coverVideo(transitionId: Int, onCovered: () -> Unit)

    fun showVideoFrame(transitionId: Int)

    fun setVideoSize(
        width: Int?,
        height: Int?,
        transitionId: Int? = null,
        onLayoutReady: (() -> Unit)? = null,
    )
}

class PlaybackLayoutCoordinator(
    private val view: () -> PlaybackLayoutView?,
    private val applyOrientation: (width: Int?, height: Int?) -> Unit,
) {
    private var confirmedVideoWidth: Int? = null
    private var confirmedVideoHeight: Int? = null

    fun reset() {
        confirmedVideoWidth = null
        confirmedVideoHeight = null
    }

    fun handle(state: PlayerState): Boolean {
        return when (state) {
            is PlayerState.VideoLayoutPreparing -> {
                prepareLayout(state.event)
                true
            }
            is PlayerState.VideoLayoutReady -> {
                markLayoutReady(state.event)
                true
            }
            is PlayerState.VideoLayoutCancelled -> {
                view()?.cancelVideoLayout(state.event)
                restoreConfirmedLayout()
                true
            }
            is PlayerState.VideoFrameRendered -> {
                view()?.showVideoFrame(state.transitionId)
                true
            }
            is PlayerState.VideoSizeChanged -> {
                updateVideoSize(state)
                true
            }
            is PlayerState.Playing -> {
                confirmVideoSize(state.videoInfo)
                applyConfirmedLayout()
                false
            }
            else -> false
        }
    }

    private fun prepareLayout(event: VideoLayoutEvent) {
        val activeView = view() ?: return
        if (confirmedVideoWidth == event.width && confirmedVideoHeight == event.height) {
            activeView.traceSnapshot("layout prepare already applied generation=${event.generation}")
            return
        }
        activeView.prepareVideoLayout(
            event = event,
            onFrozen = {
                if (view() !== activeView) return@prepareVideoLayout
                activeView.traceSnapshot(
                    "layout freeze armed generation=${event.generation}; awaiting decoder",
                )
            },
            onTimeout = {
                if (view() !== activeView) return@prepareVideoLayout
                activeView.traceSnapshot(
                    "layout freeze fallback generation=${event.generation}; media state unchanged",
                )
                restoreConfirmedLayout()
            },
        )
    }

    private fun markLayoutReady(event: VideoLayoutEvent) {
        val activeView = view() ?: return
        activeView.markVideoLayoutReady(event) {
            if (view() !== activeView) return@markVideoLayoutReady
            applyOrientation(event.width, event.height)
            activeView.setVideoSize(event.width, event.height)
        }
    }

    private fun updateVideoSize(state: PlayerState.VideoSizeChanged) {
        val activeView = view()
        val width = state.videoInfo.displayWidth
        val height = state.videoInfo.displayHeight
        confirmVideoSize(state.videoInfo)
        val updateVideoLayout: () -> Unit = {
            applyLayout(width, height, state.transitionId, state.onLayoutReady)
        }
        if (state.coverVideo) {
            activeView?.coverVideo(state.transitionId, updateVideoLayout) ?: updateVideoLayout()
            return
        }
        val deferred = activeView?.deferUntilVideoLayoutPrepared(
            width,
            height,
            updateVideoLayout,
        ) == true
        if (!deferred) updateVideoLayout()
    }

    private fun confirmVideoSize(videoInfo: PlayableVideoInfo) {
        confirmedVideoWidth = videoInfo.displayWidth
        confirmedVideoHeight = videoInfo.displayHeight
    }

    private fun applyConfirmedLayout(
        transitionId: Int? = null,
        onLayoutReady: (() -> Unit)? = null,
    ) {
        applyLayout(confirmedVideoWidth, confirmedVideoHeight, transitionId, onLayoutReady)
    }

    private fun applyLayout(
        width: Int?,
        height: Int?,
        transitionId: Int? = null,
        onLayoutReady: (() -> Unit)? = null,
    ) {
        applyOrientation(width, height)
        view()?.setVideoSize(
            width = width,
            height = height,
            transitionId = transitionId,
            onLayoutReady = onLayoutReady,
        )
    }

    private fun restoreConfirmedLayout() {
        applyConfirmedLayout()
    }
}
