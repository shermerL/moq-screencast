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
import com.example.moqandroid.publish.PublishState
import com.example.moqandroid.publish.PublishStatusFacade
import com.example.moqandroid.publish.PublisherState
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
        Log.i(LOG_TAG, "screen capture service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(LOG_TAG, "screen capture service start action=${intent?.action ?: "null"} startId=$startId")
        if (intent?.action == ACTION_STOP) {
            startForegroundService(
                relayUrl = intent.getStringExtra(EXTRA_RELAY_URL).orEmpty(),
                broadcastName = intent.getStringExtra(EXTRA_BROADCAST_NAME).orEmpty(),
            )
            stopPublishing(updateStopped = true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundService(
            relayUrl = intent?.getStringExtra(EXTRA_RELAY_URL).orEmpty(),
            broadcastName = intent?.getStringExtra(EXTRA_BROADCAST_NAME).orEmpty(),
        )

        if (intent?.action == ACTION_START_PUBLISH) {
            startPublishing(intent)
        } else if (intent == null) {
            updateState(PublisherState.Error(getString(R.string.screen_publish_service_restarted)))
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(LOG_TAG, "screen capture service destroyed")
        stopPublishing(updateStopped = !statusFacade.isError)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPublishing(intent: Intent) {
        stopPublishing(updateStopped = false)
        val generation = ++publishGeneration

        val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL).orEmpty()
        val broadcastName = intent.getStringExtra(EXTRA_BROADCAST_NAME).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.projectionResultData()
        val config = ScreenPublishConfig(
            video = ScreenVideoConfig(
                width = intent.getIntExtra(EXTRA_VIDEO_WIDTH, 1280),
                height = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 720),
                densityDpi = intent.getIntExtra(EXTRA_DENSITY_DPI, resources.displayMetrics.densityDpi),
                compatibilityMode = intent.getBooleanExtra(EXTRA_COMPATIBILITY_MODE, false),
            ),
            audio = if (intent.getBooleanExtra(EXTRA_SYSTEM_AUDIO, false)) {
                SystemAudioConfig.Enabled()
            } else {
                SystemAudioConfig.Disabled
            },
        )

        if (relayUrl.isBlank() || broadcastName.isBlank() || resultData == null) {
            updateState(PublisherState.Error(getString(R.string.screen_publish_service_missing_args)))
            stopSelf()
            return
        }

        publishJob = serviceScope.launch {
            runCatching {
                val manager = getSystemService(MediaProjectionManager::class.java)
                Log.i(LOG_TAG, "creating MediaProjection after foreground service started")
                val projection = manager.getMediaProjection(resultCode, resultData)
                    ?: error("Android did not return a MediaProjection.")
                val projectionCallback = projection.registerStopCallback(currentCoroutineContext()[Job])
                try {
                    MoqPublishSession(
                        relayUrl = relayUrl,
                        updateState = ::updateState,
                        emitEvent = statusFacade::updateEvent,
                    ).publish(
                        source = ScreenPublishSource(projection, config.video.densityDpi),
                        broadcastName = broadcastName,
                        config = PublishSessionConfig(
                            video = config.video.encoderConfig(),
                            audio = config.audio,
                        ),
                    ) { producer, audioConfig ->
                        SystemAudioCapture(projection, producer, audioConfig, LOG_TAG).run()
                    }
                } finally {
                    projection.unregisterCallbackSafe(projectionCallback)
                    projection.stopSafe()
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    Log.i(LOG_TAG, "screen publish cancelled: ${error.message}")
                    if (generation == publishGeneration) updateState(PublisherState.Stopped)
                } else {
                    Log.w(LOG_TAG, "screen publish failed", error)
                    if (generation == publishGeneration) {
                        updateState(PublisherState.Error(error.message ?: error::class.java.name))
                    }
                }
            }.also {
                if (generation == publishGeneration) stopSelf()
            }
        }
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
        statusFacade.stopIfActive()
        publishJob?.cancel(CancellationException("Screen publish stopped."))
        publishJob = null
        if (updateStopped) updateState(PublisherState.Stopped)
    }

    private fun startForegroundService(relayUrl: String, broadcastName: String) {
        val text = buildString {
            append("broadcast=")
            append(broadcastName.ifEmpty { "unknown" })
            if (relayUrl.isNotEmpty()) {
                append("\nrelay=")
                append(relayUrl)
            }
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_publish_notification_title))
            .setContentText("broadcast=${broadcastName.ifEmpty { "unknown" }}")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_moq)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOngoing(true)
            .addAction(stopAction(relayUrl, broadcastName))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopAction(relayUrl: String, broadcastName: String): Notification.Action {
        val stopIntent = Intent(this, ScreenCaptureService::class.java)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_RELAY_URL, relayUrl)
            .putExtra(EXTRA_BROADCAST_NAME, broadcastName)
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
            getString(R.string.screen_publish_channel_name),
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
        private const val EXTRA_COMPATIBILITY_MODE = "compatibility_mode"
        private const val NOTIFICATION_ID = 1002

        private val statusFacade = PublishStatusFacade()
        val status: StateFlow<PublishState> = statusFacade.uiState

        fun start(
            context: Context,
            relayUrl: String,
            broadcastName: String,
            resultCode: Int,
            resultData: Intent,
            config: ScreenPublishConfig,
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START_PUBLISH)
                .putExtra(EXTRA_RELAY_URL, relayUrl)
                .putExtra(EXTRA_BROADCAST_NAME, broadcastName)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_VIDEO_WIDTH, config.video.width)
                .putExtra(EXTRA_VIDEO_HEIGHT, config.video.height)
                .putExtra(EXTRA_DENSITY_DPI, config.video.densityDpi)
                .putExtra(EXTRA_SYSTEM_AUDIO, config.audio is SystemAudioConfig.Enabled)
                .putExtra(EXTRA_COMPATIBILITY_MODE, config.video.compatibilityMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!statusFacade.isActive) {
                statusFacade.updateState(PublisherState.Stopped)
                return
            }
            statusFacade.updateState(PublisherState.Stopping)
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun updateState(state: PublisherState) {
            statusFacade.updateState(state)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.projectionResultData(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }
}
