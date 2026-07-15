package com.example.moqandroid.playback

import android.util.Log
import com.example.moqandroid.catalog.CodecPreference
import com.example.moqandroid.catalog.PlayableAudioTrack
import com.example.moqandroid.catalog.PlayableTrack
import com.example.moqandroid.catalog.PlayableVideoInfo
import com.example.moqandroid.catalog.decoderOutput
import com.example.moqandroid.catalog.describe
import com.example.moqandroid.catalog.displayHeightFor
import com.example.moqandroid.catalog.displayWidthFor
import com.example.moqandroid.catalog.preferenceRank
import com.example.moqandroid.catalog.toPlayableTrack
import com.example.moqandroid.protocol.videoLayoutTrackName
import uniffi.moq.MoqCatalog

data class PlaybackTrackInfo(
    val broadcastName: String,
    val video: PlayableTrack,
    val audio: PlayableAudioTrack?,
    val videoInfo: PlayableVideoInfo,
    val videoLayoutTrackName: String?,
) {
    val audioDecoderOutput = audio?.decoderOutput()
}

data class PlaybackVideoTrackUpdate(
    val video: PlayableTrack,
    val videoInfo: PlayableVideoInfo,
)

class PlaybackTrackSelector(private val logTag: String) {
    fun select(
        catalog: MoqCatalog,
        broadcastName: String,
        codecPreference: CodecPreference,
    ): PlaybackTrackInfo {
        val playableTracks = catalog.video.entries
            .mapNotNull { (name, video) -> video.toPlayableTrack(name) }
        val selectedVideo = playableTracks
            .minByOrNull { it.preferenceRank(codecPreference) }
            ?: error("catalog has no playable video tracks")
        val audioTrack = catalog.audio.entries
            .mapNotNull { (name, audio) -> audio.toPlayableTrack(name) }
            .firstOrNull()

        Log.i(
            logTag,
            "selected video=${selectedVideo.name} codec=${selectedVideo.video.codec} " +
                "mime=${selectedVideo.mime} audio=${audioTrack?.name ?: "none"}",
        )

        return PlaybackTrackInfo(
            broadcastName = broadcastName,
            video = selectedVideo,
            audio = audioTrack,
            videoLayoutTrackName = catalog.videoLayoutTrackName(),
            videoInfo = PlayableVideoInfo(
                broadcastName = broadcastName,
                trackName = selectedVideo.name,
                codec = selectedVideo.video.codec,
                mime = selectedVideo.mime,
                preference = codecPreference,
                audioTrackName = audioTrack?.name,
                audioDescription = audioTrack?.describe(),
                displayWidth = catalog.displayWidthFor(selectedVideo.video),
                displayHeight = catalog.displayHeightFor(selectedVideo.video),
            ),
        )
    }
}

fun PlaybackTrackInfo.updatedVideoTrack(catalog: MoqCatalog): PlaybackVideoTrackUpdate? {
    val updatedVideo = catalog.video[video.name] ?: return null
    val playableVideo = updatedVideo.toPlayableTrack(video.name) ?: return null
    return videoInfo.copy(
        codec = updatedVideo.codec,
        mime = playableVideo.mime,
        displayWidth = catalog.displayWidthFor(updatedVideo),
        displayHeight = catalog.displayHeightFor(updatedVideo),
    ).let { PlaybackVideoTrackUpdate(playableVideo, it) }
}
