package com.example.moqandroid.playback

import com.example.moqandroid.catalog.PlayableVideoInfo
import com.example.moqandroid.protocol.VideoLayoutEvent

sealed class PlayerState {
    data object SurfaceWaiting : PlayerState()
    data class Connecting(val relayUrl: String) : PlayerState()
    data object WaitingBroadcast : PlayerState()
    data object ReadingCatalog : PlayerState()
    data class Subscribing(val trackName: String, val codec: String) : PlayerState()
    data class Playing(val videoInfo: PlayableVideoInfo) : PlayerState()
    data class VideoSizeChanged(
        val videoInfo: PlayableVideoInfo,
        val transitionId: Int,
        val coverVideo: Boolean = false,
        val onLayoutReady: (() -> Unit)? = null,
    ) : PlayerState()
    data class VideoFrameRendered(val transitionId: Int) : PlayerState()
    data class VideoLayoutPreparing(val event: VideoLayoutEvent) : PlayerState()
    data class VideoLayoutReady(val event: VideoLayoutEvent) : PlayerState()
    data class VideoLayoutCancelled(val event: VideoLayoutEvent) : PlayerState()
    data class Stats(val message: String) : PlayerState()
    data object StreamEnded : PlayerState()
    data object Disconnecting : PlayerState()
    data object Disconnected : PlayerState()
    data class Failed(val reason: String) : PlayerState()

    fun message(broadcastName: String): String = when (this) {
        SurfaceWaiting -> "Preparing player for $broadcastName ..."
        is Connecting -> "Connecting to $relayUrl ...\nbroadcast=$broadcastName"
        WaitingBroadcast -> "Connected. Waiting for $broadcastName ..."
        ReadingCatalog -> "Broadcast found. Reading catalog ...\nbroadcast=$broadcastName"
        is Subscribing -> "Subscribing $trackName ...\ncodec=$codec"
        is Playing -> "Playing $broadcastName\n${videoInfo.describe()}"
        is VideoSizeChanged -> "Video size changed for $broadcastName\n${videoInfo.describe()}"
        is VideoFrameRendered -> "Playing $broadcastName"
        is VideoLayoutPreparing -> "Preparing video layout for $broadcastName"
        is VideoLayoutReady -> "Video layout ready for $broadcastName"
        is VideoLayoutCancelled -> "Video layout change cancelled for $broadcastName"
        is Stats -> message
        StreamEnded -> "Stream ended. Press Back to return.\nbroadcast=$broadcastName"
        Disconnecting -> "Disconnecting ...\nbroadcast=$broadcastName"
        Disconnected -> "Disconnected. Press Back to return.\nbroadcast=$broadcastName"
        is Failed -> "MoQ playback failed:\n$reason\nPress Back to return."
    }
}
