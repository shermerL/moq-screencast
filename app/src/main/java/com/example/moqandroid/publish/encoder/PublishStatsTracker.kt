package com.example.moqandroid.publish.encoder

import android.os.SystemClock
import com.example.moqandroid.publish.PublishState

class PublishStatsTracker(
    private val relayUrl: String,
    private val broadcastName: String,
) {
    private var frames = 0
    private var bytes = 0L
    private var lastFrames = 0
    private var lastBytes = 0L
    private var lastUpdateMs = SystemClock.elapsedRealtime()

    fun onFrame(size: Int, emit: (PublishState) -> Unit) {
        frames += 1
        bytes += size

        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastUpdateMs
        if (elapsedMs < 1_000) return

        val seconds = elapsedMs / 1_000.0
        val kbps = ((bytes - lastBytes) * 8.0 / 1_000.0) / seconds
        emit(PublishState.Stats(relayUrl, broadcastName, frames - lastFrames, bytes, kbps))

        lastUpdateMs = now
        lastFrames = frames
        lastBytes = bytes
    }
}
