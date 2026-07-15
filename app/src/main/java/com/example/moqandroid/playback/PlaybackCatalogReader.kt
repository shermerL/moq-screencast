package com.example.moqandroid.playback

import android.util.Log
import com.example.moqandroid.catalog.describe
import uniffi.moq.MoqBroadcastConsumer
import uniffi.moq.MoqCatalog
import uniffi.moq.MoqCatalogConsumer

class PlaybackCatalogReader(private val logTag: String) {
    suspend fun readFirst(
        broadcast: MoqBroadcastConsumer,
        broadcastName: String,
    ): MoqCatalog {
        return broadcast.subscribeCatalog().use { catalogConsumer ->
            readFirst(catalogConsumer, broadcastName)
        }
    }

    suspend fun readFirst(
        catalogConsumer: MoqCatalogConsumer,
        broadcastName: String,
    ): MoqCatalog {
        val catalog = catalogConsumer.next() ?: error("catalog ended before first update")

        Log.i(logTag, catalog.describe(broadcastName))
        return catalog
    }
}
