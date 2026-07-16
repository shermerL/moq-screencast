package com.example.moqandroid.publish.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.moqandroid.R
import com.example.moqandroid.publish.MoqPublishSession
import com.example.moqandroid.publish.PublishSessionConfig
import com.example.moqandroid.publish.PublishSourceType
import com.example.moqandroid.publish.PublishState
import com.example.moqandroid.publish.PublishStatusFacade
import com.example.moqandroid.publish.audio.AudioPublishConfig
import com.example.moqandroid.publish.audio.MicrophoneAudioCapture
import com.example.moqandroid.publish.camera.CameraPublishCapabilityResolver
import com.example.moqandroid.publish.camera.CameraPublishSource
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var publishJob: Job? = null
    private var publishGeneration = 0

    override fun onCreate() {
        super.onCreate()
        Log.i(LOG_TAG, "publish foreground service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sourceType = intent.publishSourceType()
        val includeMicrophone = sourceType == PublishSourceType.Camera &&
            intent?.getBooleanExtra(EXTRA_MICROPHONE, false) == true
        Log.i(
            LOG_TAG,
            "publish foreground service start action=${intent?.action ?: "null"} " +
                "source=${sourceType.storageValue} startId=$startId",
        )
        if (intent?.action == ACTION_STOP) {
            startForegroundService(
                relayUrl = intent.getStringExtra(EXTRA_RELAY_URL).orEmpty(),
                broadcastName = intent.getStringExtra(EXTRA_BROADCAST_NAME).orEmpty(),
                sourceType = sourceType,
                includeMicrophone = false,
            )
            stopPublishing(updateStopped = true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundService(
            relayUrl = intent?.getStringExtra(EXTRA_RELAY_URL).orEmpty(),
            broadcastName = intent?.getStringExtra(EXTRA_BROADCAST_NAME).orEmpty(),
            sourceType = sourceType,
            includeMicrophone = includeMicrophone,
        )

        if (intent?.action == ACTION_START_PUBLISH) {
            activeSourceType = sourceType
            startPublishing(intent)
        } else if (intent == null) {
            statusFacade.fail(getString(R.string.screen_publish_service_restarted))
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(LOG_TAG, "publish foreground service destroyed")
        stopPublishing(updateStopped = statusFacade.canReportStopped)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPublishing(intent: Intent) {
        stopPublishing(updateStopped = false)
        val generation = ++publishGeneration

        val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL).orEmpty()
        val broadcastName = intent.getStringExtra(EXTRA_BROADCAST_NAME).orEmpty()
        val sourceType = intent.publishSourceType()

        if (relayUrl.isBlank() || broadcastName.isBlank()) {
            statusFacade.fail(getString(R.string.publish_service_missing_args))
            stopSelf()
            return
        }

        publishJob = serviceScope.launch {
            runCatching {
                when (sourceType) {
                    PublishSourceType.Camera -> publishCamera(intent, relayUrl, broadcastName)
                    PublishSourceType.Screen -> publishScreen(intent, relayUrl, broadcastName)
                    PublishSourceType.File -> error("File publishing is not implemented.")
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    Log.i(LOG_TAG, "publish cancelled source=${sourceType.storageValue}: ${error.message}")
                    if (generation == publishGeneration) statusFacade.markStopped()
                } else {
                    Log.w(LOG_TAG, "publish failed source=${sourceType.storageValue}", error)
                    if (generation == publishGeneration) {
                        statusFacade.fail(error.message ?: error::class.java.name)
                    }
                }
            }.also {
                if (generation == publishGeneration) stopSelf()
            }
        }
    }

    private suspend fun publishScreen(intent: Intent, relayUrl: String, broadcastName: String) {
        val resultData = intent.projectionResultData()
            ?: error(getString(R.string.screen_publish_service_missing_args))
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val config = ScreenPublishConfig(
            video = ScreenVideoConfig(
                width = intent.getIntExtra(EXTRA_VIDEO_WIDTH, 1280),
                height = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 720),
                densityDpi = intent.getIntExtra(EXTRA_DENSITY_DPI, resources.displayMetrics.densityDpi),
                encoderPolicy = intent.encoderPolicy(),
                h264ProfilePreference = intent.h264ProfilePreference(),
            ),
            audio = AudioPublishConfig.systemAudio().takeIf {
                intent.getBooleanExtra(EXTRA_SYSTEM_AUDIO, false)
            },
        )
        val manager = getSystemService(MediaProjectionManager::class.java)
        Log.i(LOG_TAG, "creating MediaProjection after foreground service started")
        val projection = manager.getMediaProjection(resultCode, resultData)
            ?: error("Android did not return a MediaProjection.")
        val projectionCallback = projection.registerStopCallback(currentCoroutineContext()[Job])
        try {
            val audioSource = config.audio?.let {
                SystemAudioCapture(projection, it, LOG_TAG)
            }
            MoqPublishSession(
                relayUrl = relayUrl,
                lifecycle = statusFacade.eventSink(),
            ).publish(
                source = ScreenPublishSource(
                    context = this,
                    projection = projection,
                    initialConfig = config.video,
                ),
                broadcastName = broadcastName,
                config = PublishSessionConfig(
                    video = config.video.encoderConfig(),
                ),
                audioSource = audioSource,
            )
        } finally {
            projection.unregisterCallbackSafe(projectionCallback)
            projection.stopSafe()
        }
    }

    private suspend fun publishCamera(intent: Intent, relayUrl: String, broadcastName: String) {
        val cameraConfig = CameraPublishCapabilityResolver.resolve(this)
        val videoConfig = cameraConfig.encoderConfig(
            encoderPolicy = intent.encoderPolicy(),
            h264ProfilePreference = intent.h264ProfilePreference(),
        )
        val audioSource = AudioPublishConfig.microphone().takeIf {
            intent.getBooleanExtra(EXTRA_MICROPHONE, false)
        }?.let {
            MicrophoneAudioCapture(it, LOG_TAG)
        }
        MoqPublishSession(
            relayUrl = relayUrl,
            lifecycle = statusFacade.eventSink(),
        ).publish(
            source = CameraPublishSource(this, cameraConfig),
            broadcastName = broadcastName,
            config = PublishSessionConfig(video = videoConfig),
            audioSource = audioSource,
        )
    }

    private fun MediaProjection.registerStopCallback(job: Job?): MediaProjection.Callback {
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(LOG_TAG, "media projection stopped")
                job?.cancel(CancellationException("MediaProjection stopped by Android."))
            }
        }
        registerCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    private fun MediaProjection.unregisterCallbackSafe(callback: MediaProjection.Callback) {
        runCatching { unregisterCallback(callback) }
            .onFailure { Log.w(LOG_TAG, "failed to unregister media projection callback", it) }
    }

    private fun MediaProjection.stopSafe() {
        runCatching { stop() }
            .onFailure { Log.w(LOG_TAG, "failed to stop media projection", it) }
    }

    private fun stopPublishing(updateStopped: Boolean) {
        publishGeneration += 1
        statusFacade.requestStop()
        publishJob?.cancel(CancellationException("Publish stopped."))
        publishJob = null
        if (updateStopped) statusFacade.markStopped()
    }

    private fun startForegroundService(
        relayUrl: String,
        broadcastName: String,
        sourceType: PublishSourceType,
        includeMicrophone: Boolean,
    ) {
        val text = buildString {
            append("broadcast=")
            append(broadcastName.ifEmpty { "unknown" })
            if (relayUrl.isNotEmpty()) {
                append("\nrelay=")
                append(relayUrl)
            }
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(
                    if (sourceType == PublishSourceType.Camera) {
                        R.string.camera_publish_notification_title
                    } else {
                        R.string.screen_publish_notification_title
                    },
                ),
            )
            .setContentText("broadcast=${broadcastName.ifEmpty { "unknown" }}")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_moq)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOngoing(true)
            .addAction(stopAction(relayUrl, broadcastName, sourceType))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServiceType = when (sourceType) {
                PublishSourceType.Camera -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    if (includeMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                PublishSourceType.Screen -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                PublishSourceType.File -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundServiceType,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopAction(
        relayUrl: String,
        broadcastName: String,
        sourceType: PublishSourceType,
    ): Notification.Action {
        val stopIntent = Intent(this, ScreenCaptureService::class.java)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_RELAY_URL, relayUrl)
            .putExtra(EXTRA_BROADCAST_NAME, broadcastName)
            .putExtra(EXTRA_SOURCE_TYPE, sourceType.storageValue)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getService(this, 0, stopIntent, flags)
        return Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.stop),
            pendingIntent,
        ).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.publish_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
        private const val CHANNEL_ID = "moq_screen_publish"
        private const val ACTION_START_PUBLISH = "com.example.moqandroid.action.START_PUBLISH"
        private const val ACTION_STOP = "com.example.moqandroid.action.STOP_PUBLISH"
        private const val EXTRA_RELAY_URL = "relay_url"
        private const val EXTRA_BROADCAST_NAME = "broadcast_name"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_VIDEO_WIDTH = "video_width"
        private const val EXTRA_VIDEO_HEIGHT = "video_height"
        private const val EXTRA_DENSITY_DPI = "density_dpi"
        private const val EXTRA_SYSTEM_AUDIO = "system_audio"
        private const val EXTRA_MICROPHONE = "microphone"
        private const val EXTRA_ENCODER_POLICY = "encoder_policy"
        private const val EXTRA_H264_PROFILE = "h264_profile"
        private const val EXTRA_SOURCE_TYPE = "source_type"
        private const val EXTRA_COMPATIBILITY_MODE = "compatibility_mode"
        private const val NOTIFICATION_ID = 1002

        private val statusFacade = PublishStatusFacade()
        val status: StateFlow<PublishState> = statusFacade.uiState

        fun startScreen(
            context: Context,
            relayUrl: String,
            broadcastName: String,
            resultCode: Int,
            resultData: Intent,
            config: ScreenPublishConfig,
        ) {
            activeSourceType = PublishSourceType.Screen
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START_PUBLISH)
                .putExtra(EXTRA_RELAY_URL, relayUrl)
                .putExtra(EXTRA_BROADCAST_NAME, broadcastName)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_VIDEO_WIDTH, config.video.width)
                .putExtra(EXTRA_VIDEO_HEIGHT, config.video.height)
                .putExtra(EXTRA_DENSITY_DPI, config.video.densityDpi)
                .putExtra(EXTRA_SYSTEM_AUDIO, config.audio != null)
                .putExtra(EXTRA_ENCODER_POLICY, config.video.encoderPolicy.storageValue)
                .putExtra(EXTRA_H264_PROFILE, config.video.h264ProfilePreference.storageValue)
                .putExtra(EXTRA_SOURCE_TYPE, PublishSourceType.Screen.storageValue)
            startService(context, intent)
        }

        fun startCamera(
            context: Context,
            relayUrl: String,
            broadcastName: String,
            encoderPolicy: VideoEncoderPolicy,
            h264ProfilePreference: H264ProfilePreference,
            includeMicrophone: Boolean,
        ) {
            activeSourceType = PublishSourceType.Camera
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START_PUBLISH)
                .putExtra(EXTRA_RELAY_URL, relayUrl)
                .putExtra(EXTRA_BROADCAST_NAME, broadcastName)
                .putExtra(EXTRA_ENCODER_POLICY, encoderPolicy.storageValue)
                .putExtra(EXTRA_H264_PROFILE, h264ProfilePreference.storageValue)
                .putExtra(EXTRA_MICROPHONE, includeMicrophone)
                .putExtra(EXTRA_SOURCE_TYPE, PublishSourceType.Camera.storageValue)
            startService(context, intent)
        }

        private fun startService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!statusFacade.requestStop()) {
                statusFacade.markStopped()
                return
            }
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SOURCE_TYPE, activeSourceType.storageValue)
            startService(context, intent)
        }

        fun prepare() {
            statusFacade.prepare()
        }

        fun fail(reason: String) {
            statusFacade.fail(reason)
        }

        @Volatile
        private var activeSourceType = PublishSourceType.Screen
    }

    @Suppress("DEPRECATION")
    private fun Intent.projectionResultData(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun Intent.encoderPolicy(): VideoEncoderPolicy {
        val storageValue = getStringExtra(EXTRA_ENCODER_POLICY)
        if (storageValue != null) return VideoEncoderPolicy.fromStorageValue(storageValue)

        return VideoEncoderPolicy.fromCompatibilityMode(getBooleanExtra(EXTRA_COMPATIBILITY_MODE, false))
    }

    private fun Intent.h264ProfilePreference(): H264ProfilePreference {
        return H264ProfilePreference.fromStorageValue(getStringExtra(EXTRA_H264_PROFILE))
    }

    private fun Intent?.publishSourceType(): PublishSourceType {
        return PublishSourceType.fromStorageValue(this?.getStringExtra(EXTRA_SOURCE_TYPE))
    }
}
