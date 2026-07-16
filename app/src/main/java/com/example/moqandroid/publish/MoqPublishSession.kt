package com.example.moqandroid.publish

import android.util.Log
import com.example.moqandroid.publish.audio.AudioPublishSource
import com.example.moqandroid.publish.encoder.SurfaceVideoEncoder
import com.example.moqandroid.protocol.MOQCAST_CATALOG_SECTION_NAME
import com.example.moqandroid.protocol.VIDEO_LAYOUT_TRACK_NAME
import com.example.moqandroid.protocol.videoLayoutCatalogSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import uniffi.moq.MoqBroadcastProducer
import uniffi.moq.MoqClient
import uniffi.moq.MoqOriginProducer

class MoqPublishSession(
    private val relayUrl: String,
    private val lifecycle: PublisherLifecycleEventSink,
) {
    suspend fun publish(
        source: VideoPublishSource,
        broadcastName: String,
        config: PublishSessionConfig,
        audioSource: AudioPublishSource? = null,
    ) {
        lifecycle.update(PublisherState.Preparing)

        try {
            MoqBroadcastProducer().use { broadcast ->
                Log.i(LOG_TAG, "publishing video format=avc3 catalogRotation=unset")
                val media = broadcast.publishMediaStream("avc3")
                val videoLayout = source.layoutTransitions?.let {
                    broadcast.publishTrack(VIDEO_LAYOUT_TRACK_NAME).also {
                        broadcast.setCatalogSection(MOQCAST_CATALOG_SECTION_NAME, videoLayoutCatalogSection())
                    }
                }
                val audio = audioSource?.config?.let { audioConfig ->
                    Log.i(
                        LOG_TAG,
                        "publishing audio track=0 codec=opus encoder=moq-native input=s16 " +
                            "sampleRate=${audioConfig.sampleRate} channels=${audioConfig.channelCount} " +
                            "bitrate=${audioConfig.bitrate} frameDurationMs=${audioConfig.frameDurationMs}",
                    )
                    broadcast.publishAudio("0", audioConfig.encoderInput(), audioConfig.encoderOutput())
                }
                MoqOriginProducer().use { origin ->
                    MoqClient().use { client ->
                        client.setPublish(origin)
                        lifecycle.update(PublisherState.Connecting(relayUrl, broadcastName))
                        client.connect(relayUrl).use { session ->
                            try {
                                origin.publish(broadcastName, broadcast)
                                coroutineScope {
                                    val audioJob = audio?.let { producer ->
                                        launch {
                                            runCatching {
                                                audioSource.capture(producer)
                                            }.onFailure { error ->
                                                if (error !is CancellationException) {
                                                    Log.w(LOG_TAG, "audio capture failed", error)
                                                    lifecycle.emit(
                                                        PublisherEvent.TrackError(
                                                            name = AUDIO_TRACK_NAME,
                                                            reason = error.message ?: error::class.java.name,
                                                        ),
                                                    )
                                                }
                                            }.also {
                                                runCatching { producer.finish() }
                                            }
                                        }
                                    }

                                    try {
                                        SurfaceVideoEncoder(
                                            source = source,
                                            media = media,
                                            videoLayout = videoLayout,
                                            relayUrl = relayUrl,
                                            lifecycle = lifecycle,
                                        ).run(config.video, broadcastName, audioSource?.config)
                                    } finally {
                                        audioJob?.cancel()
                                    }
                                }
                            } finally {
                                videoLayout?.let { runCatching { it.finish() } }
                                runCatching { broadcast.finish() }
                                session.shutdown()
                            }
                        }
                    }
                }
            }
        } finally {
            source.close()
        }

        lifecycle.update(PublisherState.Stopped)
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
        private const val AUDIO_TRACK_NAME = "audio"
    }
}

data class PublishSessionConfig(
    val video: VideoPublishConfig,
)
