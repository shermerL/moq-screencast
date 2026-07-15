package com.example.moqandroid.publish.screen

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishSource
import com.example.moqandroid.publish.VideoPublishTransition
import com.example.moqandroid.protocol.VideoLayoutEvent
import com.example.moqandroid.protocol.VideoLayoutPhase
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

class ScreenPublishSource(
    context: Context,
    private val projection: MediaProjection,
    initialConfig: ScreenVideoConfig,
) : VideoPublishSource {
    override val label: String = "screen"

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val densityDpi = initialConfig.densityDpi
    private val baseConfig = initialConfig.encoderConfig()
    private val pendingConfig = AtomicReference<VideoPublishTransition?>()
    private val layoutEvents = ConcurrentLinkedQueue<VideoLayoutEvent>()
    private var virtualDisplay: VirtualDisplay? = null
    private var virtualDisplayWidth = initialConfig.width
    private var virtualDisplayHeight = initialConfig.height
    private var lastRequestedConfig = baseConfig
    private var stableDisplayRotation = displayManager
        .getDisplay(Display.DEFAULT_DISPLAY)
        ?.rotation
    private var layoutGeneration = 0L
    private var activeLayoutGeneration: Long? = null
    private var lastPreparingConfig: VideoPublishConfig? = null
    private var committedLayoutGeneration: Long? = null
    private var committedLayoutConfig: VideoPublishConfig? = null
    private var geometryCandidateConfig: VideoPublishConfig? = null
    private var geometryCandidateRotation: Int? = null
    private var stableGeometrySamples = 0
    @Volatile
    private var outputSuspended = false
    @Volatile
    private var closed = false
    private val resizeRunnable = Runnable(::sampleDisplayGeometry)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || closed) return
            suspendOutputForGeometryChange()
            handler.removeCallbacks(resizeRunnable)
            handler.post(resizeRunnable)
        }
    }

    init {
        displayManager.registerDisplayListener(displayListener, handler)
        handler.post(resizeRunnable)
    }

    override fun attachEncoderSurface(surface: Surface, config: VideoPublishConfig) {
        detachEncoderSurface()
        Log.i(
            LOG_TAG,
            "attaching screen source virtualDisplay=${config.width}x${config.height} " +
                "densityDpi=$densityDpi encoderInput=${config.width}x${config.height}",
        )
        val currentDisplay = virtualDisplay
        if (currentDisplay == null) {
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
        } else {
            currentDisplay.resize(config.width, config.height, densityDpi)
            currentDisplay.surface = surface
        }
        virtualDisplayWidth = config.width
        virtualDisplayHeight = config.height
        resetGeometryCandidate()
        outputSuspended = false
    }

    override fun detachEncoderSurface() {
        if (virtualDisplay != null) {
            Log.i(LOG_TAG, "detaching screen source encoder surface")
        }
        virtualDisplay?.surface = null
    }

    override fun isOutputSuspended(): Boolean = outputSuspended

    override fun pollConfigChange(): VideoPublishTransition? {
        return pendingConfig.getAndSet(null)
    }

    override fun pollLayoutEvent(): VideoLayoutEvent? = layoutEvents.poll()

    override fun onLayoutReady(generation: Long) {
        handler.post {
            if (activeLayoutGeneration != generation) return@post
            activeLayoutGeneration = null
            lastPreparingConfig = null
            if (committedLayoutGeneration == generation) {
                committedLayoutGeneration = null
                committedLayoutConfig = null
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(resizeRunnable)
        displayManager.unregisterDisplayListener(displayListener)
        virtualDisplay?.release()
        virtualDisplay = null
    }

    @Suppress("DEPRECATION")
    private fun sampleDisplayGeometry() {
        if (closed) return
        val geometry = currentDisplayGeometry() ?: return
        val nextConfig = baseConfig.withScreenSize(
            geometry.metrics.widthPixels,
            geometry.metrics.heightPixels,
        )
        if (!outputSuspended) {
            applyDisplayGeometry(geometry, nextConfig)
            return
        }

        val sameCandidate =
            geometryCandidateConfig?.sameSizeAs(nextConfig) == true &&
                geometryCandidateRotation == geometry.display.rotation
        if (sameCandidate) {
            stableGeometrySamples += 1
        } else {
            geometryCandidateConfig = nextConfig
            geometryCandidateRotation = geometry.display.rotation
            stableGeometrySamples = 1
        }
        Log.i(
            LOG_TAG,
            "screen geometry candidate rotation=${geometry.display.rotation.rotationName()} " +
                "target=${nextConfig.width}x${nextConfig.height} " +
                "sample=$stableGeometrySamples/$REQUIRED_STABLE_GEOMETRY_SAMPLES",
        )
        if (stableGeometrySamples < REQUIRED_STABLE_GEOMETRY_SAMPLES) {
            handler.postDelayed(resizeRunnable, GEOMETRY_SAMPLE_INTERVAL_MS)
            return
        }

        resetGeometryCandidate()
        applyDisplayGeometry(geometry, nextConfig)
    }

    private fun applyDisplayGeometry(
        geometry: DisplayGeometry,
        nextConfig: VideoPublishConfig,
    ) {
        val display = geometry.display
        val metrics = geometry.metrics
        stableDisplayRotation = display.rotation
        Log.i(
            LOG_TAG,
            "screen capture geometry displayRotation=${display?.rotation.rotationName()} " +
                "screen=${metrics.widthPixels}x${metrics.heightPixels} densityDpi=${metrics.densityDpi} " +
                "virtualDisplay=${virtualDisplayWidth}x$virtualDisplayHeight " +
                "targetEncoder=${nextConfig.width}x${nextConfig.height}",
        )
        if (nextConfig.width == lastRequestedConfig.width && nextConfig.height == lastRequestedConfig.height) {
            if (nextConfig.width == virtualDisplayWidth && nextConfig.height == virtualDisplayHeight) {
                resumeOutputAfterCancelledResize()
            }
            return
        }

        lastRequestedConfig = nextConfig
        val generation = activeLayoutGeneration ?: beginLayoutTransition(nextConfig, display.rotation)
        committedLayoutGeneration = generation
        committedLayoutConfig = nextConfig
        pendingConfig.set(VideoPublishTransition(nextConfig, generation))
        Log.i(
            LOG_TAG,
            "screen encoder resize requested generation=$generation " +
                "target=${nextConfig.width}x${nextConfig.height}",
        )
    }

    @Suppress("DEPRECATION")
    private fun suspendOutputForGeometryChange() {
        val geometry = currentDisplayGeometry() ?: return
        val nextConfig = baseConfig.withScreenSize(
            geometry.metrics.widthPixels,
            geometry.metrics.heightPixels,
        )
        val axisChanged = geometry.display.rotation.swapsAxes() != stableDisplayRotation.swapsAxes()
        val sizeChanged = nextConfig.width != virtualDisplayWidth || nextConfig.height != virtualDisplayHeight
        if (!axisChanged && !sizeChanged) return
        val activeGeneration = activeLayoutGeneration
        val committedTargetChanged =
            activeGeneration != null &&
                committedLayoutGeneration == activeGeneration &&
                committedLayoutConfig?.sameSizeAs(nextConfig) == false
        val generation = if (committedTargetChanged) {
            beginLayoutTransition(nextConfig, geometry.display.rotation)
        } else {
            activeGeneration ?: beginLayoutTransition(nextConfig, geometry.display.rotation)
        }
        if (lastPreparingConfig?.let { it.width != nextConfig.width || it.height != nextConfig.height } == true) {
            enqueueLayoutEvent(VideoLayoutPhase.Preparing, generation, nextConfig, geometry.display.rotation)
            lastPreparingConfig = nextConfig
        }
        if (!outputSuspended) {
            Log.i(
                LOG_TAG,
                "screen source transition pending generation=$generation " +
                    "current=${virtualDisplayWidth}x$virtualDisplayHeight " +
                    "observed=${nextConfig.width}x${nextConfig.height}",
            )
        }
        outputSuspended = true
    }

    private fun resumeOutputAfterCancelledResize() {
        if (!outputSuspended) return
        activeLayoutGeneration?.let { generation ->
            enqueueLayoutEvent(
                phase = VideoLayoutPhase.Cancelled,
                generation = generation,
                config = lastRequestedConfig.copy(width = virtualDisplayWidth, height = virtualDisplayHeight),
                rotation = stableDisplayRotation,
            )
        }
        activeLayoutGeneration = null
        lastPreparingConfig = null
        committedLayoutGeneration = null
        committedLayoutConfig = null
        resetGeometryCandidate()
        outputSuspended = false
        Log.i(
            LOG_TAG,
            "screen source transition cancelled, resuming current encoder " +
                "size=${virtualDisplayWidth}x$virtualDisplayHeight",
        )
    }

    private fun beginLayoutTransition(config: VideoPublishConfig, rotation: Int): Long {
        val generation = ++layoutGeneration
        activeLayoutGeneration = generation
        lastPreparingConfig = config
        enqueueLayoutEvent(VideoLayoutPhase.Preparing, generation, config, rotation)
        return generation
    }

    private fun enqueueLayoutEvent(
        phase: VideoLayoutPhase,
        generation: Long,
        config: VideoPublishConfig,
        rotation: Int?,
    ) {
        layoutEvents.add(
            VideoLayoutEvent(
                phase = phase,
                generation = generation,
                width = config.width,
                height = config.height,
                rotation = rotation.rotationDegrees(),
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayGeometry(): DisplayGeometry? {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
        return DisplayGeometry(display, metrics)
    }

    private fun Int?.rotationName(): String = when (this) {
        Surface.ROTATION_0 -> "0"
        Surface.ROTATION_90 -> "90"
        Surface.ROTATION_180 -> "180"
        Surface.ROTATION_270 -> "270"
        else -> "unknown"
    }

    private fun Int?.rotationDegrees(): Int? = when (this) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> null
    }

    private fun Int?.swapsAxes(): Boolean {
        return this == Surface.ROTATION_90 || this == Surface.ROTATION_270
    }

    private fun VideoPublishConfig.sameSizeAs(other: VideoPublishConfig): Boolean {
        return width == other.width && height == other.height
    }

    private fun resetGeometryCandidate() {
        geometryCandidateConfig = null
        geometryCandidateRotation = null
        stableGeometrySamples = 0
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
        private const val GEOMETRY_SAMPLE_INTERVAL_MS = 32L
        private const val REQUIRED_STABLE_GEOMETRY_SAMPLES = 2
    }
}

private data class DisplayGeometry(
    val display: Display,
    val metrics: DisplayMetrics,
)
