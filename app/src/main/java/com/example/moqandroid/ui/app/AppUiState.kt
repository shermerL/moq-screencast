package com.example.moqandroid.ui.app

import com.example.moqandroid.config.AppLanguage
import com.example.moqandroid.publish.PublishSourceType
import com.example.moqandroid.publish.encoder.H264ProfilePreference

data class RelayConfigUiState(
    val relayUrl: String,
    val status: String,
)

data class SettingsUiState(
    val relayUrl: String,
    val status: String,
    val language: AppLanguage,
    val languageOptions: List<AppLanguage>,
    val publishCompatibilityMode: Boolean,
    val h264ProfilePreference: H264ProfilePreference,
    val h264ProfileOptions: List<H264ProfilePreference>,
    val showPlaybackStats: Boolean,
)

data class RelayConfigActions(
    val onRelayUrlChange: (String) -> Unit,
    val onContinue: () -> Unit,
)

enum class PublishPanelMode {
    Ready,
    Preparing,
    Publishing,
    Stopping,
    Error,
}

data class PublishPanelState(
    val relayUrl: String,
    val broadcast: String,
    val source: PublishSourceType,
    val includeSystemAudio: Boolean,
    val includeMicrophone: Boolean,
    val status: String,
    val mode: PublishPanelMode,
)

data class PublishPanelActions(
    val onRelayUrlChange: (String) -> Unit,
    val onBroadcastChange: (String) -> Unit,
    val onSourceChange: (PublishSourceType) -> Unit,
    val onIncludeSystemAudioChange: (Boolean) -> Unit,
    val onIncludeMicrophoneChange: (Boolean) -> Unit,
    val onPublish: () -> Unit,
    val onStopPublish: () -> Unit,
)

data class SubscribePanelState(
    val relayUrl: String,
    val broadcast: String,
    val status: String,
)

data class SubscribePanelActions(
    val onRelayUrlChange: (String) -> Unit,
    val onBroadcastChange: (String) -> Unit,
    val onSubscribe: () -> Unit,
)

data class MainTabsState(
    val publish: PublishPanelState,
    val subscribe: SubscribePanelState,
    val settings: SettingsUiState,
)

data class MainTabsActions(
    val publish: PublishPanelActions,
    val subscribe: SubscribePanelActions,
    val settings: SettingsActions,
)

data class SettingsActions(
    val onRelayUrlChange: (String) -> Unit,
    val onLanguageChange: (AppLanguage) -> Unit,
    val onPublishCompatibilityModeChange: (Boolean) -> Unit,
    val onH264ProfilePreferenceChange: (H264ProfilePreference) -> Unit,
    val onShowPlaybackStatsChange: (Boolean) -> Unit,
    val onSave: () -> Unit,
)
