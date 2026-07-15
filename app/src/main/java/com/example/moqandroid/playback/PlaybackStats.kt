package com.example.moqandroid.playback

import android.os.SystemClock
import com.example.moqandroid.catalog.PlayableVideoInfo
import java.util.Locale

class PlaybackStats(private var videoInfo: PlayableVideoInfo) {
    var renderedFrames = 0
    var receivedFrames = 0
        private set
    private var receivedBytes = 0L
    private var lastRenderedFrames = 0
    private var lastReceivedFrames = 0
    private var lastReceivedBytes = 0L
    private var lastUpdateMs = SystemClock.elapsedRealtime()

    fun onFrameReceived(payloadSize: Int) {
        receivedFrames += 1
        receivedBytes += payloadSize
    }

    fun updateVideoInfo(nextVideoInfo: PlayableVideoInfo) {
        videoInfo = nextVideoInfo
    }

    fun flushIfDue(updateStatus: (String) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastUpdateMs
        if (elapsedMs < 1_000) return

        val seconds = elapsedMs / 1_000.0
        val recvFps = (receivedFrames - lastReceivedFrames) / seconds
        val renderFps = (renderedFrames - lastRenderedFrames) / seconds
        val kbps = ((receivedBytes - lastReceivedBytes) * 8.0 / 1_000.0) / seconds

        updateStatus(
            "Playing ${videoInfo.broadcastName}\n" +
                "${videoInfo.describe()}\n" +
                "recv=${formatNumber(recvFps)} fps render=${formatNumber(renderFps)} fps bitrate=${formatNumber(kbps, 0)} kbps",
        )

        lastUpdateMs = now
        lastReceivedFrames = receivedFrames
        lastRenderedFrames = renderedFrames
        lastReceivedBytes = receivedBytes
    }

    private fun formatNumber(value: Double, decimals: Int = 1): String {
        return String.format(Locale.US, "%.${decimals}f", value)
    }
}
