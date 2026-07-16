package com.example.moqandroid

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import com.example.moqandroid.config.withAppLanguage
import com.example.moqandroid.playback.PlayerState
import com.example.moqandroid.playback.PlaybackLayoutCoordinator
import com.example.moqandroid.publish.PublishRequest
import com.example.moqandroid.ui.PlayerScreen
import com.example.moqandroid.ui.app.FirstRunConfig
import com.example.moqandroid.ui.app.MainTabs
import com.example.moqandroid.ui.app.MainTabsActions
import com.example.moqandroid.ui.app.MainTabsState
import com.example.moqandroid.ui.app.PublishPanelActions
import com.example.moqandroid.ui.app.PublishPanelState
import com.example.moqandroid.ui.app.RelayConfigActions
import com.example.moqandroid.ui.app.RelayConfigUiState
import com.example.moqandroid.ui.app.SettingsActions
import com.example.moqandroid.ui.app.SettingsUiState
import com.example.moqandroid.ui.app.SubscribePanelActions
import com.example.moqandroid.ui.app.SubscribePanelState

class MainActivity : ComponentActivity(), SurfaceHolder.Callback2 {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var viewModel: AppViewModel
    private var playerScreen: PlayerScreen? = null
    private val playbackLayoutCoordinator = PlaybackLayoutCoordinator(
        view = { playerScreen },
        applyOrientation = ::applyPlaybackOrientation,
    )
    private var defaultRotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        defaultRotationAnimation = window.attributes.rotationAnimation
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        setComposeContent()
    }

    private fun setComposeContent() {
        setContent {
            val language = viewModel.settingsLanguage
            val localizedContext = remember(language) { this.withAppLanguage(language) }
            CompositionLocalProvider(LocalContext provides localizedContext) {
                when (viewModel.currentScreen) {
                    AppScreen.Config -> FirstRunConfig(
                        state = RelayConfigUiState(
                            relayUrl = viewModel.configRelayUrl,
                            status = viewModel.configStatusMessage,
                        ),
                        actions = RelayConfigActions(
                            onRelayUrlChange = viewModel::updateConfigRelayUrl,
                            onContinue = {
                                if (viewModel.saveConfigFromInput()) exitFullscreen()
                            },
                        ),
                    )

                    AppScreen.Home -> MainTabs(
                        state = MainTabsState(
                            publish = PublishPanelState(
                                relayUrl = viewModel.homeRelayUrl,
                                broadcast = viewModel.publishBroadcastName,
                                source = viewModel.publishSource,
                                includeSystemAudio = viewModel.includeSystemAudio,
                                includeMicrophone = viewModel.includeMicrophone,
                                status = viewModel.publishStatusMessage,
                                mode = viewModel.publishPanelMode,
                            ),
                            subscribe = SubscribePanelState(
                                relayUrl = viewModel.homeRelayUrl,
                                broadcast = viewModel.subscribeBroadcastName,
                                status = viewModel.subscribeStatusMessage,
                            ),
                            settings = SettingsUiState(
                                relayUrl = viewModel.settingsRelayUrl,
                                status = viewModel.settingsStatusMessage,
                                language = viewModel.settingsLanguage,
                                languageOptions = viewModel.languageOptions,
                                publishCompatibilityMode = viewModel.settingsPublishCompatibilityMode,
                                h264ProfilePreference = viewModel.settingsH264ProfilePreference,
                                h264ProfileOptions = viewModel.h264ProfileOptions,
                                showPlaybackStats = viewModel.settingsShowPlaybackStats,
                            ),
                        ),
                        actions = MainTabsActions(
                            publish = PublishPanelActions(
                                onRelayUrlChange = viewModel::updateHomeRelayUrl,
                                onBroadcastChange = viewModel::updatePublishBroadcast,
                                onSourceChange = viewModel::updatePublishSource,
                                onIncludeSystemAudioChange = viewModel::updateIncludeSystemAudio,
                                onIncludeMicrophoneChange = viewModel::updateIncludeMicrophone,
                                onPublish = ::requestPublish,
                                onStopPublish = { viewModel.stopPublish(localizedText(R.string.publish_stopped_by_user)) },
                            ),
                            subscribe = SubscribePanelActions(
                                onRelayUrlChange = viewModel::updateHomeRelayUrl,
                                onBroadcastChange = viewModel::updateSubscribeBroadcast,
                                onSubscribe = ::showPlayerUi,
                            ),
                            settings = SettingsActions(
                                onRelayUrlChange = viewModel::updateSettingsRelayUrl,
                                onLanguageChange = viewModel::updateSettingsLanguage,
                                onPublishCompatibilityModeChange = viewModel::updateSettingsPublishCompatibilityMode,
                                onH264ProfilePreferenceChange = viewModel::updateSettingsH264ProfilePreference,
                                onShowPlaybackStatsChange = viewModel::updateSettingsShowPlaybackStats,
                                onSave = {
                                    if (viewModel.saveSettingsFromInput()) exitFullscreen()
                                },
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun requestPublish() {
        when (
            viewModel.preparePublish(
                hasCameraPermission = hasCameraPermission(),
                hasRecordAudioPermission = hasRecordAudioPermission(),
                hasNotificationPermission = hasNotificationPermission(),
            )
        ) {
            PublishRequest.None -> Unit
            PublishRequest.RequestCamera -> requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            PublishRequest.RequestRecordAudio -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            PublishRequest.RequestNotifications -> requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            PublishRequest.RequestScreenCapture -> startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_SCREEN_CAPTURE,
            )
            PublishRequest.StartCamera -> viewModel.startCameraPublish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATIONS,
            REQUEST_RECORD_AUDIO,
            REQUEST_CAMERA,
            -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    requestPublish()
                } else {
                    val message = when (requestCode) {
                        REQUEST_CAMERA -> localizedText(R.string.camera_permission_denied)
                        REQUEST_RECORD_AUDIO -> localizedText(R.string.audio_permission_denied)
                        else -> localizedText(R.string.screen_capture_permission_denied)
                    }
                    viewModel.failPublish(message)
                }
            }
        }
    }

    @Deprecated("Deprecated in Android framework. Kept until Activity Result API migration.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            viewModel.failPublish(localizedText(R.string.screen_capture_permission_denied))
            return
        }

        viewModel.startScreenPublish(resultCode, data, resources.displayMetrics)
    }

    private fun showPlayerUi() {
        val nextBroadcast = viewModel.prepareSubscribe() ?: return
        enterFullscreen()

        playerScreen?.release()
        val screen = PlayerScreen(
            activity = this,
            broadcastName = nextBroadcast,
            surfaceCallback = this,
        )
        playbackLayoutCoordinator.reset()
        playerScreen = screen
        setContentView(screen.root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        playerScreen?.traceSurfaceEvent("surface created")
        val surface = holder.surface
        if (!surface.isValid) {
            updatePlayerView(PlayerState.SurfaceWaiting, PlayerState.SurfaceWaiting.message(viewModel.playerBroadcast.orEmpty()))
            return
        }

        viewModel.startPlayback(surface, ::updatePlayerView)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        playerScreen?.onSurfaceChanged(format, width, height)
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        playerScreen?.traceSurfaceEvent("surface redraw needed")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        playerScreen?.traceSurfaceEvent("surface destroyed")
        viewModel.stopPlayback("Disconnected from ${viewModel.playerBroadcast ?: viewModel.subscribeBroadcastName}.")
    }

    override fun onPause() {
        Log.i(LOG_TAG, "MainActivity paused")
        super.onPause()
    }

    override fun onStop() {
        Log.i(LOG_TAG, "MainActivity stopped")
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(
            LOG_TAG,
            "playback configuration changed orientation=${newConfig.orientation} " +
                "screen=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp",
        )
        playerScreen?.traceSnapshot("configuration changed requestedOrientation=$requestedOrientation")
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && viewModel.playerBroadcast != null) {
            viewModel.showMainUi()
            playerScreen?.release()
            playerScreen = null
            playbackLayoutCoordinator.reset()
            exitFullscreen()
            setComposeContent()
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "MainActivity destroyed")
        playerScreen?.release()
        super.onDestroy()
    }

    private fun updatePlayerView(state: PlayerState, message: String) {
        if (playbackLayoutCoordinator.handle(state)) return
        playerScreen?.setStatus(
            message = message,
            visible = state !is PlayerState.Stats || viewModel.settingsShowPlaybackStats,
        )
    }

    private fun hasRecordAudioPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun enterFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        updateRotationAnimation(WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun exitFullscreen() {
        restoreDefaultOrientation()
        updateRotationAnimation(defaultRotationAnimation)
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun updateRotationAnimation(rotationAnimation: Int) {
        val attributes = window.attributes
        if (attributes.rotationAnimation == rotationAnimation) return
        attributes.rotationAnimation = rotationAnimation
        window.attributes = attributes
        Log.i(LOG_TAG, "playback rotation animation=$rotationAnimation")
    }

    private fun applyPlaybackOrientation(width: Int?, height: Int?) {
        if (width == null || height == null || width <= 0 || height <= 0) return
        val nextOrientation = if (width > height) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (requestedOrientation == nextOrientation) return

        Log.i(LOG_TAG, "playback orientation request width=${width} height=$height orientation=$nextOrientation")
        requestedOrientation = nextOrientation
    }

    private fun restoreDefaultOrientation() {
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED) return
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    private fun localizedText(resId: Int): String {
        return withAppLanguage(viewModel.settingsLanguage).getString(resId)
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val REQUEST_RECORD_AUDIO = 1003
        private const val REQUEST_CAMERA = 1004
        private const val LOG_TAG = "MoqAndroid"
    }
}
