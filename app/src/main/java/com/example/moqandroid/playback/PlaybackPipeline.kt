package com.example.moqandroid.playback

import android.util.Log
import android.os.SystemClock
import android.view.Surface
import com.example.moqandroid.protocol.VideoLayoutEvent
import com.example.moqandroid.protocol.VideoLayoutPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import uniffi.moq.MoqBroadcastConsumer
import uniffi.moq.MoqCatalogConsumer

class PlaybackPipeline(
    private val logTag: String,
    private val status: (PlayerState) -> Unit,
) {
    private val subscriptionManager = PlaybackSubscriptionManager(logTag)
    private val audioRunner = AudioPlaybackRunner(logTag)
    private val videoRenderer = VideoPlaybackRenderer(status)

    suspend fun play(
        broadcast: MoqBroadcastConsumer,
        surface: Surface,
        trackInfo: PlaybackTrackInfo,
        catalogConsumer: MoqCatalogConsumer,
    ) {
        val video = trackInfo.video

        status(PlayerState.Subscribing(video.name, video.video.codec))

        val subscriptions = subscriptionManager.subscribe(broadcast, trackInfo)
        val videoTrackUpdates = Channel<PlaybackVideoTrackUpdate>(Channel.CONFLATED)

        coroutineScope {
            val audioJob = audioRunner.launchIn(this, trackInfo, subscriptions)
            val catalogJob = launch {
                monitorCatalogUpdates(catalogConsumer, trackInfo, videoTrackUpdates)
            }
            val videoLayoutJob = subscriptions.videoLayoutConsumer?.let { consumer ->
                launch { monitorVideoLayout(consumer) }
            }

            try {
                videoRenderer.play(surface, trackInfo, subscriptions, videoTrackUpdates)
            } finally {
                status(PlayerState.Disconnecting)
                catalogConsumer.cancel()
                catalogJob.cancel()
                videoLayoutJob?.cancel()
                audioJob?.cancel()
                videoTrackUpdates.close()
                subscriptions.cancel()
            }
        }
    }

    private suspend fun monitorVideoLayout(consumer: uniffi.moq.MoqTrackConsumer) {
        var latestGeneration = 0L
        try {
            while (true) {
                val payload = consumer.readFrame() ?: break
                val event = VideoLayoutEvent.decode(payload)
                if (event == null) {
                    Log.w(logTag, "ignoring invalid video layout control event bytes=${payload.size}")
                    continue
                }
                if (event.generation < latestGeneration) {
                    Log.i(
                        logTag,
                        "ignoring stale video layout event generation=${event.generation} " +
                            "latest=$latestGeneration phase=${event.phase.wireValue}",
                    )
                    continue
                }
                latestGeneration = event.generation
                Log.i(
                    logTag,
                    "video layout ${event.phase.wireValue} generation=${event.generation} " +
                        "target=${event.width}x${event.height} rotation=${event.rotation ?: "none"} " +
                        "elapsedMs=${SystemClock.elapsedRealtime()}",
                )
                status(
                    when (event.phase) {
                        VideoLayoutPhase.Preparing -> PlayerState.VideoLayoutPreparing(event)
                        VideoLayoutPhase.Ready -> PlayerState.VideoLayoutReady(event)
                        VideoLayoutPhase.Cancelled -> PlayerState.VideoLayoutCancelled(event)
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(logTag, "video layout control monitor failed", error)
        }
    }

    private suspend fun monitorCatalogUpdates(
        catalogConsumer: MoqCatalogConsumer,
        trackInfo: PlaybackTrackInfo,
        videoTrackUpdates: Channel<PlaybackVideoTrackUpdate>,
    ) {
        try {
            var activeVideoInfo = trackInfo.videoInfo
            while (true) {
                val catalog = catalogConsumer.next() ?: break
                val nextVideoTrack = trackInfo.updatedVideoTrack(catalog) ?: continue
                val nextVideoInfo = nextVideoTrack.videoInfo
                if (
                    activeVideoInfo.displayWidth == nextVideoInfo.displayWidth &&
                    activeVideoInfo.displayHeight == nextVideoInfo.displayHeight
                ) {
                    continue
                }
                activeVideoInfo = nextVideoInfo
                Log.i(
                    logTag,
                    "catalog video update track=${nextVideoInfo.trackName} " +
                        "display=${nextVideoInfo.displayWidth ?: "unknown"}x${nextVideoInfo.displayHeight ?: "unknown"} " +
                        "codec=${nextVideoTrack.video.video.codec} " +
                        "descriptionBytes=${nextVideoTrack.video.video.description?.size ?: 0} " +
                        "descriptionHash=${nextVideoTrack.video.video.description?.contentHashCode() ?: 0} " +
                        "elapsedMs=${SystemClock.elapsedRealtime()}",
                )
                videoTrackUpdates.trySend(nextVideoTrack)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(logTag, "catalog monitor failed", error)
        }
    }
}
