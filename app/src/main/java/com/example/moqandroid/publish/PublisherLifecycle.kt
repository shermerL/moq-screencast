package com.example.moqandroid.publish

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class PublisherState {
    data object Idle : PublisherState()
    data object Preparing : PublisherState()
    data class Connecting(val relayUrl: String, val broadcastName: String) : PublisherState()
    data class Publishing(
        val relayUrl: String,
        val broadcastName: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val frameRate: Int,
        val audioEnabled: Boolean,
    ) : PublisherState()

    data object Stopping : PublisherState()
    data object Stopped : PublisherState()
    data class Error(val reason: String) : PublisherState()
}

sealed class PublisherEvent {
    data class TrackStarted(val name: String) : PublisherEvent()
    data class TrackStopped(val name: String) : PublisherEvent()
    data class TrackError(val name: String, val reason: String) : PublisherEvent()
    data class StatsUpdated(
        val relayUrl: String,
        val broadcastName: String,
        val frames: Int,
        val bytes: Long,
        val bitrateKbps: Double,
    ) : PublisherEvent()
}

class PublishStatusFacade {
    private val mutableUiState = MutableStateFlow<PublishState>(PublishState.Stopped)
    private var publisherState: PublisherState = PublisherState.Idle

    val uiState: StateFlow<PublishState> = mutableUiState.asStateFlow()

    val isActive: Boolean
        get() = when (publisherState) {
            PublisherState.Idle,
            PublisherState.Stopped,
            is PublisherState.Error,
            -> false

            PublisherState.Preparing,
            is PublisherState.Connecting,
            is PublisherState.Publishing,
            PublisherState.Stopping,
            -> true
        }

    val isError: Boolean
        get() = publisherState is PublisherState.Error

    fun updateState(state: PublisherState) {
        publisherState = state
        mutableUiState.value = state.toPublishState()
    }

    fun stopIfActive() {
        if (isActive) updateState(PublisherState.Stopping)
    }

    fun updateEvent(event: PublisherEvent) {
        mutableUiState.value = event.toPublishState() ?: return
    }

    private fun PublisherState.toPublishState(): PublishState = when (this) {
        PublisherState.Idle -> PublishState.Stopped
        PublisherState.Preparing -> PublishState.Preparing
        is PublisherState.Connecting -> PublishState.Connecting(relayUrl, broadcastName)
        is PublisherState.Publishing -> PublishState.Publishing(
            relayUrl = relayUrl,
            broadcastName = broadcastName,
            width = width,
            height = height,
            bitrate = bitrate,
            frameRate = frameRate,
            audioEnabled = audioEnabled,
        )
        PublisherState.Stopping -> PublishState.Stopping
        PublisherState.Stopped -> PublishState.Stopped
        is PublisherState.Error -> PublishState.Failed(reason)
    }

    private fun PublisherEvent.toPublishState(): PublishState? = when (this) {
        is PublisherEvent.StatsUpdated -> PublishState.Stats(relayUrl, broadcastName, frames, bytes, bitrateKbps)
        is PublisherEvent.TrackError -> if (name == AUDIO_TRACK_NAME) {
            PublishState.AudioFailed(reason)
        } else {
            PublishState.Failed(reason)
        }
        is PublisherEvent.TrackStarted,
        is PublisherEvent.TrackStopped,
        -> null
    }

    private companion object {
        private const val AUDIO_TRACK_NAME = "audio"
    }
}
