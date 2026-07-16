package com.example.moqandroid.playback

import com.example.moqandroid.catalog.CodecPreference
import com.example.moqandroid.catalog.PlayableVideoInfo
import com.example.moqandroid.protocol.VideoLayoutEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLayoutCoordinatorTest {
    @Test
    fun playingAppliesInitialLayoutWithoutConsumingStatus() {
        val view = FakePlaybackLayoutView()
        val orientations = mutableListOf<Pair<Int?, Int?>>()
        val coordinator = PlaybackLayoutCoordinator({ view }) { width, height ->
            orientations += width to height
        }

        val consumed = coordinator.handle(PlayerState.Playing(videoInfo(720, 1280)))

        assertFalse(consumed)
        assertEquals(listOf(720 to 1280), orientations)
        assertEquals(listOf(VideoSize(720, 1280, null)), view.videoSizes)
    }

    @Test
    fun deferredTransitionKeepsDimensionsFromItsOwnEvent() {
        val view = FakePlaybackLayoutView().apply { deferLayout = true }
        val orientations = mutableListOf<Pair<Int?, Int?>>()
        val coordinator = PlaybackLayoutCoordinator({ view }) { width, height ->
            orientations += width to height
        }

        coordinator.handle(PlayerState.VideoSizeChanged(videoInfo(1280, 720), transitionId = 1))
        coordinator.handle(PlayerState.VideoSizeChanged(videoInfo(720, 1280), transitionId = 2))
        view.deferredActions.forEach { it() }

        assertEquals(listOf(1280 to 720, 720 to 1280), orientations)
        assertEquals(
            listOf(VideoSize(1280, 720, 1), VideoSize(720, 1280, 2)),
            view.videoSizes,
        )
    }

    @Test
    fun matchingPrepareDoesNotFreezeConfirmedLayout() {
        val view = FakePlaybackLayoutView()
        val coordinator = PlaybackLayoutCoordinator({ view }) { _, _ -> }
        coordinator.handle(PlayerState.Playing(videoInfo(1280, 720)))

        val consumed = coordinator.handle(
            PlayerState.VideoLayoutPreparing(
                VideoLayoutEvent(
                    phase = com.example.moqandroid.protocol.VideoLayoutPhase.Preparing,
                    generation = 3,
                    width = 1280,
                    height = 720,
                    rotation = 90,
                ),
            ),
        )

        assertTrue(consumed)
        assertEquals(0, view.prepareCount)
        assertTrue(view.snapshots.single().contains("already applied"))
    }

    private fun videoInfo(width: Int, height: Int): PlayableVideoInfo {
        return PlayableVideoInfo(
            broadcastName = "test.hang",
            trackName = "0.avc3",
            codec = "avc3.64001f",
            mime = "video/avc",
            preference = CodecPreference.Auto,
            audioTrackName = null,
            audioDescription = null,
            displayWidth = width,
            displayHeight = height,
        )
    }
}

private data class VideoSize(val width: Int?, val height: Int?, val transitionId: Int?)

private class FakePlaybackLayoutView : PlaybackLayoutView {
    val snapshots = mutableListOf<String>()
    val videoSizes = mutableListOf<VideoSize>()
    val deferredActions = mutableListOf<() -> Unit>()
    var deferLayout = false
    var prepareCount = 0

    override fun traceSnapshot(event: String) {
        snapshots += event
    }

    override fun prepareVideoLayout(
        event: VideoLayoutEvent,
        onFrozen: () -> Unit,
        onTimeout: () -> Unit,
    ) {
        prepareCount += 1
    }

    override fun markVideoLayoutReady(event: VideoLayoutEvent, onReady: () -> Unit) {
        onReady()
    }

    override fun deferUntilVideoLayoutPrepared(width: Int?, height: Int?, action: () -> Unit): Boolean {
        if (deferLayout) deferredActions += action
        return deferLayout
    }

    override fun cancelVideoLayout(event: VideoLayoutEvent) = Unit

    override fun coverVideo(transitionId: Int, onCovered: () -> Unit) {
        onCovered()
    }

    override fun showVideoFrame(transitionId: Int) = Unit

    override fun setVideoSize(
        width: Int?,
        height: Int?,
        transitionId: Int?,
        onLayoutReady: (() -> Unit)?,
    ) {
        videoSizes += VideoSize(width, height, transitionId)
    }
}
