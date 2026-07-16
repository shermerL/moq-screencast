package com.example.moqandroid.publish.audio

import uniffi.moq.MoqAudioProducer

interface AudioPublishSource {
    val config: AudioPublishConfig

    suspend fun capture(producer: MoqAudioProducer)
}
