package com.example.moqandroid.publish

import android.content.Context
import com.example.moqandroid.R

class PublishStatusFormatter(private val context: Context) {
    fun format(state: PublishState): String = when (state) {
        PublishState.Preparing -> context.getString(R.string.publish_status_preparing)
        is PublishState.Connecting -> context.getString(
            R.string.publish_status_connecting,
            state.relayUrl,
            state.broadcastName,
        )
        is PublishState.Publishing -> context.getString(
            R.string.publish_status_publishing,
            state.relayUrl,
            state.broadcastName,
            state.width,
            state.height,
            state.frameRate,
            state.bitrate / 1_000,
            context.getString(if (state.audioEnabled) R.string.system_audio_on else R.string.system_audio_off),
        )
        is PublishState.Stats -> context.getString(
            R.string.publish_status_stats,
            state.relayUrl,
            state.broadcastName,
            state.frames,
            state.bytes,
            state.bitrateKbps,
        )
        PublishState.Stopping -> context.getString(R.string.publish_status_stopping)
        PublishState.Stopped -> context.getString(R.string.publish_status_stopped)
        is PublishState.AudioFailed -> context.getString(
            R.string.publish_status_audio_failed,
            state.reason,
        )
        is PublishState.Failed -> context.getString(
            R.string.publish_status_failed,
            state.reason,
        )
    }
}
