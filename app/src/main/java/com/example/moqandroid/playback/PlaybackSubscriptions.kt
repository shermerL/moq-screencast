package com.example.moqandroid.playback

import android.util.Log
import uniffi.moq.MoqAudioConsumer
import uniffi.moq.MoqBroadcastConsumer
import uniffi.moq.MoqMediaConsumer
import uniffi.moq.MoqTrackConsumer

class PlaybackSubscriptionManager(private val logTag: String) {
    fun subscribe(
        broadcast: MoqBroadcastConsumer,
        trackInfo: PlaybackTrackInfo,
    ): PlaybackSubscriptions {
        val video = trackInfo.video
        val audio = trackInfo.audio
        val media = broadcast.subscribeMedia(video.name, video.video.container, 250uL)
        val audioClock = audio?.let { AudioPlaybackClock(it.sampleRate) }
        val audioConsumer = audio?.let { track ->
            val output = trackInfo.audioDecoderOutput
                ?: error("audio decoder output unavailable for ${track.name}")
            broadcast.subscribeAudio(track.name, track.audio, output)
        }
        val videoLayoutConsumer = trackInfo.videoLayoutTrackName?.let { trackName ->
            runCatching { broadcast.subscribeTrack(trackName) }
                .onFailure { error -> Log.w(logTag, "video layout control subscription failed", error) }
                .getOrNull()
        }

        return PlaybackSubscriptions(
            media = media,
            audioConsumer = audioConsumer,
            audioClock = audioClock,
            videoLayoutConsumer = videoLayoutConsumer,
        )
    }
}

class PlaybackSubscriptions(
    val media: MoqMediaConsumer,
    val audioConsumer: MoqAudioConsumer?,
    val audioClock: AudioPlaybackClock?,
    val videoLayoutConsumer: MoqTrackConsumer?,
) {
    fun cancel() {
        videoLayoutConsumer?.cancel()
        audioConsumer?.cancel()
        media.cancel()
    }
}
