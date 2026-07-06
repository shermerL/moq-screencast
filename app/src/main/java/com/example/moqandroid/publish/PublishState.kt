package com.example.moqandroid.publish

sealed class PublishState {
    data object Preparing : PublishState()
    data class Connecting(val relayUrl: String, val broadcastName: String) : PublishState()
    data class Publishing(
        val relayUrl: String,
        val broadcastName: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val frameRate: Int,
        val audioEnabled: Boolean,
    ) : PublishState()

    data class Stats(
        val relayUrl: String,
        val broadcastName: String,
        val frames: Int,
        val bytes: Long,
        val bitrateKbps: Double,
    ) : PublishState()

    data object Stopping : PublishState()
    data object Stopped : PublishState()
    data class AudioFailed(val reason: String) : PublishState()
    data class Failed(val reason: String) : PublishState()
}
