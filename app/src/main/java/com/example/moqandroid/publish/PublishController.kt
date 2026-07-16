package com.example.moqandroid.publish

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import com.example.moqandroid.config.RelayConfig
import com.example.moqandroid.media.codec.CodecSupport
import com.example.moqandroid.publish.audio.AudioPublishConfig
import com.example.moqandroid.publish.camera.CameraPublishCapabilityResolver
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy
import com.example.moqandroid.publish.screen.ScreenCaptureService
import com.example.moqandroid.publish.screen.ScreenPublishConfig
import com.example.moqandroid.publish.screen.ScreenVideoConfig
import com.example.moqandroid.publish.screen.withScreenSize
import kotlinx.coroutines.flow.StateFlow

class PublishController(private val context: Context) {
    val status: StateFlow<PublishState> = ScreenCaptureService.status

    fun prepare(input: PublishPreparationInput): PublishPreparation {
        val broadcastName = input.broadcastInput.trim().trim('/')
        if (broadcastName.isEmpty()) {
            return PublishPreparation(PublishRequest.None, null, "Broadcast name cannot be empty.")
        }

        if (!CodecSupport.hasEncoderFor(MIME_AVC)) {
            return PublishPreparation(
                PublishRequest.None,
                broadcastName,
                "This device has no H.264 encoder.\n${CodecSupport.describeVideoEncoders()}",
            )
        }
        if (input.source == PublishSourceType.File) {
            return PublishPreparation(PublishRequest.None, broadcastName, "File publishing is not implemented.")
        }
        if (
            input.source == PublishSourceType.Screen &&
            input.includeSystemAudio &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        ) {
            return PublishPreparation(
                PublishRequest.None,
                broadcastName,
                "System audio capture requires Android 10+.\nbroadcast=$broadcastName",
            )
        }
        if (
            input.source == PublishSourceType.Screen &&
            input.includeSystemAudio &&
            !input.permissions.recordAudio
        ) {
            ScreenCaptureService.prepare()
            return PublishPreparation(
                PublishRequest.RequestRecordAudio,
                broadcastName,
                "Audio permission is required before publishing system audio.\nbroadcast=$broadcastName",
            )
        }
        if (input.source == PublishSourceType.Camera && !input.permissions.camera) {
            ScreenCaptureService.prepare()
            return PublishPreparation(
                PublishRequest.RequestCamera,
                broadcastName,
                "Camera permission is required before publishing video.\nbroadcast=$broadcastName",
            )
        }
        if (
            input.source == PublishSourceType.Camera &&
            input.includeMicrophone &&
            !input.permissions.recordAudio
        ) {
            ScreenCaptureService.prepare()
            return PublishPreparation(
                PublishRequest.RequestRecordAudio,
                broadcastName,
                "Microphone permission is required before publishing audio.\nbroadcast=$broadcastName",
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !input.permissions.notifications) {
            ScreenCaptureService.prepare()
            return PublishPreparation(
                PublishRequest.RequestNotifications,
                broadcastName,
                "Notification permission is required before publishing.\nbroadcast=$broadcastName",
            )
        }

        ScreenCaptureService.prepare()
        return when (input.source) {
            PublishSourceType.Camera -> runCatching {
                CameraPublishCapabilityResolver.resolve(context)
            }.fold(
                onSuccess = { camera ->
                    PublishPreparation(
                        PublishRequest.StartCamera,
                        broadcastName,
                        "Starting rear camera ${camera.width}x${camera.height} ...\nbroadcast=$broadcastName",
                    )
                },
                onFailure = { error ->
                    ScreenCaptureService.fail(error.message ?: error::class.java.name)
                    PublishPreparation(
                        PublishRequest.None,
                        broadcastName,
                        "Camera publish is unavailable: ${error.message ?: error::class.java.simpleName}",
                    )
                },
            )
            PublishSourceType.Screen -> PublishPreparation(
                PublishRequest.RequestScreenCapture,
                broadcastName,
                "Requesting screen capture permission ...\nbroadcast=$broadcastName",
            )
            PublishSourceType.File -> error("File source was handled before publish preparation.")
        }
    }

    fun startScreen(request: ScreenPublishStartRequest) {
        ScreenCaptureService.startScreen(
            context = context,
            relayUrl = request.relayConfig.relayUrl,
            broadcastName = request.broadcastName,
            resultCode = request.resultCode,
            resultData = request.resultData,
            config = screenPublishConfig(
                request.metrics,
                request.includeSystemAudio,
                request.encoderPolicy,
                request.h264ProfilePreference,
            ),
        )
    }

    fun startCamera(request: CameraPublishStartRequest) {
        ScreenCaptureService.startCamera(
            context = context,
            relayUrl = request.relayConfig.relayUrl,
            broadcastName = request.broadcastName,
            encoderPolicy = request.encoderPolicy,
            h264ProfilePreference = request.h264ProfilePreference,
            includeMicrophone = request.includeMicrophone,
        )
    }

    fun stop() {
        ScreenCaptureService.stop(context)
    }

    fun fail(reason: String) {
        ScreenCaptureService.fail(reason)
    }

    private fun screenPublishConfig(
        metrics: DisplayMetrics,
        includeSystemAudio: Boolean,
        encoderPolicy: VideoEncoderPolicy,
        h264ProfilePreference: H264ProfilePreference,
    ): ScreenPublishConfig {
        val video = VideoPublishConfig(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            encoderPolicy = encoderPolicy,
            h264ProfilePreference = h264ProfilePreference,
        ).withScreenSize(metrics.widthPixels, metrics.heightPixels)
        return ScreenPublishConfig(
            video = ScreenVideoConfig(
                width = video.width,
                height = video.height,
                densityDpi = metrics.densityDpi,
                encoderPolicy = encoderPolicy,
                h264ProfilePreference = h264ProfilePreference,
            ),
            audio = AudioPublishConfig.systemAudio().takeIf { includeSystemAudio },
        )
    }

    private companion object {
        private const val MIME_AVC = "video/avc"
    }
}

data class PublishPreparation(
    val request: PublishRequest,
    val broadcastName: String?,
    val message: String,
)

data class PublishPreparationInput(
    val source: PublishSourceType,
    val broadcastInput: String,
    val includeSystemAudio: Boolean,
    val includeMicrophone: Boolean,
    val permissions: PublishPermissions,
)

data class PublishPermissions(
    val camera: Boolean,
    val notifications: Boolean,
    val recordAudio: Boolean,
)

data class ScreenPublishStartRequest(
    val relayConfig: RelayConfig,
    val broadcastName: String,
    val resultCode: Int,
    val resultData: Intent,
    val metrics: DisplayMetrics,
    val includeSystemAudio: Boolean,
    val encoderPolicy: VideoEncoderPolicy,
    val h264ProfilePreference: H264ProfilePreference,
)

data class CameraPublishStartRequest(
    val relayConfig: RelayConfig,
    val broadcastName: String,
    val encoderPolicy: VideoEncoderPolicy,
    val h264ProfilePreference: H264ProfilePreference,
    val includeMicrophone: Boolean,
)

sealed interface PublishRequest {
    data object None : PublishRequest
    data object RequestCamera : PublishRequest
    data object RequestRecordAudio : PublishRequest
    data object RequestNotifications : PublishRequest
    data object RequestScreenCapture : PublishRequest
    data object StartCamera : PublishRequest
}
