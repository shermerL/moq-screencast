package com.example.moqandroid.publish.screen

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishSource

class ScreenPublishSource(
    private val projection: MediaProjection,
    private val densityDpi: Int,
) : VideoPublishSource {
    override val label: String = "screen"

    private var virtualDisplay: VirtualDisplay? = null

    override fun attachEncoderSurface(surface: Surface, config: VideoPublishConfig) {
        detachEncoderSurface()
        virtualDisplay = projection.createVirtualDisplay(
            "MoqScreenPublish",
            config.width,
            config.height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
    }

    override fun detachEncoderSurface() {
        virtualDisplay?.release()
        virtualDisplay = null
    }

    override fun close() {
        detachEncoderSurface()
    }
}
