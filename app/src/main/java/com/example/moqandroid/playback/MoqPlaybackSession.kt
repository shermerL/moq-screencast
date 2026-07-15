package com.example.moqandroid.playback

import android.view.Surface
import com.example.moqandroid.catalog.CodecPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.moq.MoqClient
import uniffi.moq.MoqOriginProducer

class MoqPlaybackSession(
    private val relayUrl: String,
    private val logTag: String,
    private val status: (PlayerState) -> Unit,
) {
    private val catalogReader = PlaybackCatalogReader(logTag)
    private val trackSelector = PlaybackTrackSelector(logTag)
    private val pipeline = PlaybackPipeline(logTag, status)

    suspend fun play(
        surface: Surface,
        broadcastName: String,
        codecPreference: CodecPreference,
    ) = withContext(Dispatchers.IO) {
        status(PlayerState.Connecting(relayUrl))

        MoqOriginProducer().use { originProducer ->
            MoqClient().use { client ->
                client.setConsume(originProducer)

                client.connect(relayUrl).use { session ->
                    status(PlayerState.WaitingBroadcast)

                    val originConsumer = originProducer.consume()
                    val broadcast = originConsumer.announcedBroadcast(broadcastName).available()

                    status(PlayerState.ReadingCatalog)
                    broadcast.subscribeCatalog().use { catalogConsumer ->
                        val catalog = catalogReader.readFirst(catalogConsumer, broadcastName)
                        val trackInfo = trackSelector.select(catalog, broadcastName, codecPreference)

                        try {
                            pipeline.play(broadcast, surface, trackInfo, catalogConsumer)
                        } finally {
                            session.shutdown()
                        }
                    }
                }
            }
        }
    }
}
