// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.DesktopMpvPresentation
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.ImageQuality
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.rememberAutoHidingTopBarVisible
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_about
import com.jellyscope.ui.generated.resources.settings_account_download_removal_confirm
import com.jellyscope.ui.generated.resources.settings_account_download_removal_message
import com.jellyscope.ui.generated.resources.settings_account_download_removal_title
import com.jellyscope.ui.generated.resources.settings_app_version
import com.jellyscope.ui.generated.resources.settings_coming_later
import com.jellyscope.ui.generated.resources.settings_feature_chromecast
import com.jellyscope.ui.generated.resources.settings_feature_syncplay
import com.jellyscope.ui.generated.resources.settings_license
import com.jellyscope.ui.generated.resources.settings_logout_error
import com.jellyscope.ui.generated.resources.settings_picker_cancel
import com.jellyscope.ui.generated.resources.settings_source_notices
import com.jellyscope.ui.generated.resources.settings_title
import com.jellyscope.ui.screen.account.AccountIntent
import com.jellyscope.ui.screen.account.AccountUiState
import com.jellyscope.ui.screen.account.AccountViewModel
import com.jellyscope.ui.screen.downloads.formatIntegerBytes
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SettingsScreen(
    session: Session,
    onBack: () -> Unit,
    onLogoutComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel =
        koinViewModel(
            parameters = { parametersOf(session) },
        ),
    accountViewModel: AccountViewModel = koinViewModel(),
    onOpenDownloads: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    var logFeedback by remember { mutableStateOf<SendClientLogsResult?>(null) }
    var playbackTimelineFeedback by remember { mutableStateOf<PlaybackTimelineFeedback?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val logoutErrorMessage = stringResource(Res.string.settings_logout_error)
    val playbackTimelineExporter = rememberPlaybackTimelineExporter(viewModel)

    OnResumeEffect({ viewModel.accept(SettingsIntent.RefreshDownloadUsage) })

    LaunchedEffect(viewModel, logoutErrorMessage, playbackTimelineExporter) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ClientLogsSent -> logFeedback = event.result
                SettingsEvent.LogoutFailed -> snackbarHostState.showSnackbar(logoutErrorMessage)
                is SettingsEvent.SavePlaybackTimeline -> playbackTimelineExporter.launch(event.operationId)
                is SettingsEvent.PlaybackTimelineDelivered -> playbackTimelineFeedback = event.feedback
            }
        }
    }

    SettingsContent(
        state = state,
        accountState = accountState,
        onBack = onBack,
        onLogout = { viewModel.accept(SettingsIntent.Logout(onLogoutComplete)) },
        onReloadPlaybackPreferences = { viewModel.accept(SettingsIntent.ReloadPlaybackPreferences) },
        onSetDefaultMaxBitrateBps = { defaultMaxBitrateBps ->
            viewModel.accept(SettingsIntent.SetDefaultMaxBitrateBps(defaultMaxBitrateBps))
        },
        onSetDefaultQualityPolicy = { policy -> viewModel.accept(SettingsIntent.SetDefaultQualityPolicy(policy)) },
        onSetVlcTranscodeMaxBitrateBps = { maxBitrateBps -> viewModel.accept(SettingsIntent.SetVlcTranscodeMaxBitrateBps(maxBitrateBps)) },
        onSetDefaultPlayerBackend = { backend -> viewModel.accept(SettingsIntent.SetDefaultPlayerBackend(backend)) },
        onSetPreferredAudioLanguage = { language -> viewModel.accept(SettingsIntent.SetPreferredAudioLanguage(language)) },
        onSetPreferredSubtitleLanguage = { language -> viewModel.accept(SettingsIntent.SetPreferredSubtitleLanguage(language)) },
        onSetAutoPlayNext = { enabled -> viewModel.accept(SettingsIntent.SetAutoPlayNext(enabled)) },
        onSetStillWatchingPrompt = { enabled -> viewModel.accept(SettingsIntent.SetStillWatchingPrompt(enabled)) },
        onSetPlaybackWarningsEnabled = { enabled -> viewModel.accept(SettingsIntent.SetPlaybackWarningsEnabled(enabled)) },
        onSetPlayerClockEnabled = { enabled -> viewModel.accept(SettingsIntent.SetPlayerClockEnabled(enabled)) },
        onSetAllowInsecureDesktopTls = { enabled -> viewModel.accept(SettingsIntent.SetAllowInsecureDesktopTls(enabled)) },
        onSetAutoPlayNextDelaySeconds = { delaySeconds -> viewModel.accept(SettingsIntent.SetAutoPlayNextDelaySeconds(delaySeconds)) },
        onSetDesktopMpvPresentation = { presentation -> viewModel.accept(SettingsIntent.SetDesktopMpvPresentation(presentation)) },
        onSetSegmentSkipPolicy = { type, policy -> viewModel.accept(SettingsIntent.SetSegmentSkipPolicy(type, policy)) },
        onSetPlayerAudioMode = { audioMode -> viewModel.accept(SettingsIntent.SetPlayerAudioMode(audioMode)) },
        onSetPlayerHdrMode = { hdrMode -> viewModel.accept(SettingsIntent.SetPlayerHdrMode(hdrMode)) },
        onSetMaxVideoResolution = { limit -> viewModel.accept(SettingsIntent.SetMaxVideoResolution(limit)) },
        onSetIosPlaybackCompatibilityMode = { mode -> viewModel.accept(SettingsIntent.SetIosPlaybackCompatibilityMode(mode)) },
        onRefreshPlayerDevicePolicy = { viewModel.accept(SettingsIntent.RefreshPlayerDevicePolicy) },
        onSetAppTheme = { theme -> viewModel.accept(SettingsIntent.SetAppTheme(theme)) },
        onSetTileSize = { tileSize -> viewModel.accept(SettingsIntent.SetTileSize(tileSize)) },
        onSetImageQuality = { imageQuality -> viewModel.accept(SettingsIntent.SetImageQuality(imageQuality)) },
        onSetPictureInPictureEnabled = { enabled -> viewModel.accept(SettingsIntent.SetPictureInPictureEnabled(enabled)) },
        onSetRememberLastLibraryView = { enabled -> viewModel.accept(SettingsIntent.SetRememberLastLibraryView(enabled)) },
        onSetOpenSubtitlesApiKey = { value, onSuccess -> viewModel.accept(SettingsIntent.SetOpenSubtitlesApiKey(value, onSuccess)) },
        onRetryOpenSubtitlesApiKeyLoad = { viewModel.accept(SettingsIntent.RetryOpenSubtitlesApiKeyLoad) },
        onSetOpenSubtitleResultPreference = {
            preference,
            onSuccess,
            ->
            viewModel.accept(SettingsIntent.SetOpenSubtitleResultPreference(preference, onSuccess))
        },
        onRetryOpenSubtitleResultPreferenceLoad = { viewModel.accept(SettingsIntent.RetryOpenSubtitleResultPreferenceLoad) },
        onClearLocalSubtitles = { viewModel.accept(SettingsIntent.ClearLocalSubtitles) },
        logFeedback = logFeedback,
        snackbarHostState = snackbarHostState,
        onSetLogCollectionEnabled = { enabled -> viewModel.accept(SettingsIntent.SetLogCollectionEnabled(enabled)) },
        onSetVerboseLogcatEnabled = { enabled -> viewModel.accept(SettingsIntent.SetVerboseLogcatEnabled(enabled)) },
        onSetPlaybackInfoAtStartEnabled = { enabled -> viewModel.accept(SettingsIntent.SetPlaybackInfoAtStartEnabled(enabled)) },
        onSendClientLogs = { viewModel.accept(SettingsIntent.SendClientLogs) },
        playbackTimelineFeedback = playbackTimelineFeedback,
        onSavePlaybackTimeline = {
            playbackTimelineFeedback = null
            viewModel.accept(SettingsIntent.SavePlaybackTimeline)
        },
        onAddAccount = {
            serverUrl,
            username,
            password,
            ->
            accountViewModel.accept(AccountIntent.AddAccount(serverUrl, username, password))
        },
        onSwitchAccount = { accountId -> accountViewModel.accept(AccountIntent.SwitchTo(accountId)) },
        onSignOutAccount = { accountId -> accountViewModel.accept(AccountIntent.SignOut(accountId)) },
        onConfirmAccountRemoval = { accountViewModel.accept(AccountIntent.ConfirmPendingRemoval) },
        onDismissAccountRemoval = { accountViewModel.accept(AccountIntent.DismissPendingRemoval) },
        onOpenDownloads = onOpenDownloads,
        enableContentDownloading = session.enableContentDownloading,
        modifier = modifier,
    )
    state.logoutRemovalPreview?.let { preview ->
        LogoutDownloadRemovalConfirmationDialog(
            preview = preview,
            onConfirm = { viewModel.accept(SettingsIntent.ConfirmLogoutRemoval(onLogoutComplete)) },
            onDismiss = { viewModel.accept(SettingsIntent.DismissLogoutRemoval) },
        )
    }
}

@Composable
private fun LogoutDownloadRemovalConfirmationDialog(
    preview: DownloadRemovalPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val displayedBytes = preview.confirmations.sumOf { confirmation -> confirmation.displayedBytes }
    val recordCount = preview.confirmations.sumOf { confirmation -> confirmation.recordCount }
    val displayedSize = formatIntegerBytes(displayedBytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_account_download_removal_title)) },
        text = {
            Text(
                stringResource(
                    Res.string.settings_account_download_removal_message,
                    recordCount,
                    displayedSize,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.settings_account_download_removal_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.settings_picker_cancel))
            }
        },
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    accountState: AccountUiState,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onReloadPlaybackPreferences: () -> Unit,
    onSetDefaultMaxBitrateBps: (Long?) -> Unit,
    onSetDefaultQualityPolicy: (PlaybackQualityPolicy) -> Unit = { policy -> onSetDefaultMaxBitrateBps(policy.maxBitrateBps) },
    onSetVlcTranscodeMaxBitrateBps: (Long?) -> Unit = {},
    onSetDefaultPlayerBackend: (PlayerBackend) -> Unit,
    onSetPreferredAudioLanguage: (String?) -> Unit,
    onSetPreferredSubtitleLanguage: (String?) -> Unit,
    onSetAutoPlayNext: (Boolean) -> Unit = {},
    onSetStillWatchingPrompt: (Boolean) -> Unit = {},
    onSetPlaybackWarningsEnabled: (Boolean) -> Unit = {},
    onSetPlayerClockEnabled: (Boolean) -> Unit = {},
    onSetAllowInsecureDesktopTls: (Boolean) -> Unit = {},
    onSetAutoPlayNextDelaySeconds: (Int) -> Unit = {},
    onSetDesktopMpvPresentation: (DesktopMpvPresentation) -> Unit = {},
    onSetSegmentSkipPolicy: (MediaSegmentType, SegmentSkipPolicy) -> Unit,
    onSetPlayerAudioMode: (PlayerAudioMode) -> Unit,
    onSetPlayerHdrMode: (PlayerHdrMode) -> Unit,
    onSetMaxVideoResolution: (PlayerVideoResolutionLimit) -> Unit = {},
    onSetIosPlaybackCompatibilityMode: (IosPlaybackCompatibilityMode) -> Unit = {},
    onRefreshPlayerDevicePolicy: () -> Unit,
    onSetAppTheme: (AppColorThemeId) -> Unit,
    onSetTileSize: (TileSizeId) -> Unit,
    onSetImageQuality: (ImageQuality) -> Unit = {},
    onSetPictureInPictureEnabled: (Boolean) -> Unit,
    onSetRememberLastLibraryView: (Boolean) -> Unit,
    onSetOpenSubtitlesApiKey: (String, () -> Unit) -> Unit = { _, _ -> },
    onRetryOpenSubtitlesApiKeyLoad: () -> Unit = {},
    onSetOpenSubtitleResultPreference: (OpenSubtitleResultPreference, () -> Unit) -> Unit = { _, _ -> },
    onRetryOpenSubtitleResultPreferenceLoad: () -> Unit = {},
    onClearLocalSubtitles: () -> Unit = {},
    logFeedback: SendClientLogsResult? = null,
    onSetLogCollectionEnabled: (Boolean) -> Unit = {},
    onSetVerboseLogcatEnabled: (Boolean) -> Unit = {},
    onSetPlaybackInfoAtStartEnabled: (Boolean) -> Unit = {},
    onSendClientLogs: () -> Unit = {},
    playbackTimelineFeedback: PlaybackTimelineFeedback? = null,
    onSavePlaybackTimeline: () -> Unit = {},
    onAddAccount: (String, String, String) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onSignOutAccount: (String) -> Unit,
    onConfirmAccountRemoval: () -> Unit = {},
    onDismissAccountRemoval: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    enableContentDownloading: Boolean = true,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    var openRow by remember { mutableStateOf<SettingsRowId?>(null) }
    val title = stringResource(Res.string.settings_title)
    val onDismiss = { openRow = null }
    val gridState = rememberLazyStaggeredGridState()
    val topBarVisible = rememberAutoHidingTopBarVisible(gridState)

    Box(modifier = modifier.fillMaxSize()) {
        SettingsBackdrop()
        // Pane width controls columns; the host width tier still controls insets and pickers.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val horizontalContentPadding = adaptiveHorizontalContentPadding()
            val horizontalStart = horizontalContentPadding.start
            val horizontalEnd = horizontalContentPadding.end
            val contentWidth = maxWidth - horizontalStart - horizontalEnd
            val columnCount =
                settingsColumnCount(
                    availableWidth = contentWidth,
                    minColumnWidth = Dimensions.settingsSectionMinWidth,
                    gap = Dimensions.settingsColumnGap,
                    maxColumns = 4,
                )
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columnCount),
                state = gridState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .desktopScrollInput(gridState, DesktopScrollOrientation.Vertical),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.settingsColumnGap),
                verticalItemSpacing = Dimensions.detailSectionSpacing,
                contentPadding =
                    PaddingValues(
                        start = horizontalStart,
                        top = appTopBarContentPadding(),
                        end = horizontalEnd,
                        bottom = appNavigationBarContentPadding(),
                    ),
            ) {
                item(key = SettingsSectionId.Account) {
                    AccountSettingsSection(
                        userName = state.userName,
                        serverName = state.serverName,
                        serverUrl = state.serverUrl,
                        isLoggingOut = state.isLoggingOut,
                        accountState = accountState,
                        onLogout = onLogout,
                        onAddAccount = onAddAccount,
                        onSwitchAccount = onSwitchAccount,
                        onSignOutAccount = onSignOutAccount,
                        onConfirmAccountRemoval = onConfirmAccountRemoval,
                        onDismissAccountRemoval = onDismissAccountRemoval,
                        openRow = openRow,
                        onOpenRow = { openRow = it },
                        onDismiss = onDismiss,
                    )
                }
                item(key = SettingsSectionId.Appearance) {
                    AppearanceSettingsSection(
                        selectedTheme = state.appTheme,
                        onSetAppTheme = onSetAppTheme,
                        selectedTileSize = state.tileSize,
                        onSetTileSize = onSetTileSize,
                        selectedImageQuality = state.imageQuality,
                        onSetImageQuality = onSetImageQuality,
                        rememberLastLibraryView = state.rememberLastLibraryView,
                        onRememberLastLibraryViewChange = onSetRememberLastLibraryView,
                        openRow = openRow,
                        onOpenRow = { openRow = it },
                        onDismiss = onDismiss,
                    )
                }
                item(key = SettingsSectionId.Playback) {
                    PlaybackSettingsSection(
                        state = state,
                        onReload = onReloadPlaybackPreferences,
                        onSetDefaultMaxBitrateBps = onSetDefaultMaxBitrateBps,
                        onSetDefaultQualityPolicy = onSetDefaultQualityPolicy,
                        onSetVlcTranscodeMaxBitrateBps = onSetVlcTranscodeMaxBitrateBps,
                        onSetDefaultPlayerBackend = onSetDefaultPlayerBackend,
                        onSetPreferredAudioLanguage = onSetPreferredAudioLanguage,
                        onSetPreferredSubtitleLanguage = onSetPreferredSubtitleLanguage,
                        onSetAutoPlayNext = onSetAutoPlayNext,
                        onSetStillWatchingPrompt = onSetStillWatchingPrompt,
                        onSetPlaybackWarningsEnabled = onSetPlaybackWarningsEnabled,
                        onSetPlayerClockEnabled = onSetPlayerClockEnabled,
                        onSetAllowInsecureDesktopTls = onSetAllowInsecureDesktopTls,
                        onSetAutoPlayNextDelaySeconds = onSetAutoPlayNextDelaySeconds,
                        openRow = openRow,
                        onOpenRow = { openRow = it },
                        onDismiss = onDismiss,
                        onSetDesktopMpvPresentation = onSetDesktopMpvPresentation,
                    )
                }
                item(key = SettingsSectionId.SkipSegments) {
                    SkipSegmentsSettingsSection(
                        preferences = state.playbackPreferences,
                        onSetSegmentSkipPolicy = onSetSegmentSkipPolicy,
                        openRow = openRow,
                        onOpenRow = { openRow = it },
                        onDismiss = onDismiss,
                    )
                }
                item(key = SettingsSectionId.AdvancedPlayback) {
                    AdvancedPlaybackSettingsSection(
                        state = state,
                        onSetPlayerAudioMode = onSetPlayerAudioMode,
                        onSetPlayerHdrMode = onSetPlayerHdrMode,
                        onSetMaxVideoResolution = onSetMaxVideoResolution,
                        onSetIosPlaybackCompatibilityMode = onSetIosPlaybackCompatibilityMode,
                        onRefreshPlayerDevicePolicy = onRefreshPlayerDevicePolicy,
                        onSetPictureInPictureEnabled = onSetPictureInPictureEnabled,
                        openRow = openRow,
                        onOpenRow = { openRow = it },
                        onDismiss = onDismiss,
                    )
                }
                if (enableContentDownloading) {
                    item(key = SettingsSectionId.Downloads) {
                        DownloadsSettingsSection(
                            usage = state.downloadUsage,
                            onOpenDownloads = onOpenDownloads,
                        )
                    }
                }
                item(key = SettingsSectionId.Services) {
                    OpenSubtitlesSettingsSection(
                        apiKey = state.openSubtitlesApiKey,
                        isApiKeyLoaded = state.isOpenSubtitlesApiKeyLoaded,
                        isLoadingApiKey = state.isLoadingOpenSubtitlesApiKey,
                        isSavingApiKey = state.isSavingOpenSubtitlesApiKey,
                        apiKeyLoadError = state.openSubtitlesApiKeyLoadError,
                        apiKeyError = state.openSubtitlesApiKeyError,
                        onApiKeyChange = onSetOpenSubtitlesApiKey,
                        onRetryApiKeyLoad = onRetryOpenSubtitlesApiKeyLoad,
                        resultPreference = state.openSubtitleResultPreference,
                        isResultPreferenceLoaded = state.isOpenSubtitleResultPreferenceLoaded,
                        isLoadingResultPreference = state.isLoadingOpenSubtitleResultPreference,
                        resultPreferenceLoadError = state.openSubtitleResultPreferenceLoadError,
                        isSavingResultPreference = state.isSavingOpenSubtitleResultPreference,
                        resultPreferenceError = state.openSubtitleResultPreferenceError,
                        onResultPreferenceChange = onSetOpenSubtitleResultPreference,
                        onRetryResultPreferenceLoad = onRetryOpenSubtitleResultPreferenceLoad,
                        isClearingLocalSubtitles = state.isClearingLocalSubtitles,
                        localSubtitlesClearCompletion = state.localSubtitlesClearCompletion,
                        localSubtitlesClearError = state.localSubtitlesClearError,
                        onClearLocalSubtitles = onClearLocalSubtitles,
                        openRow = openRow,
                        onOpenRow = { openRow = it },
                        onDismiss = onDismiss,
                    )
                }
                item(key = SettingsSectionId.Diagnostics) {
                    DiagnosticsSettingsSection(
                        collectLogs = state.collectLogs,
                        verboseLogcatEnabled = state.verboseLogcatEnabled,
                        playbackInfoAtStartEnabled = state.playbackInfoAtStartEnabled,
                        bufferSize = state.logBufferSize,
                        isSending = state.isSendingClientLogs,
                        logCollectionPreferenceError = state.logCollectionPreferenceError,
                        feedback = logFeedback,
                        onCollectLogsChange = onSetLogCollectionEnabled,
                        onVerboseLogcatChange = onSetVerboseLogcatEnabled,
                        onPlaybackInfoAtStartChange = onSetPlaybackInfoAtStartEnabled,
                        onSendLogs = onSendClientLogs,
                        playbackTimelineAvailable = state.playbackTimelineAvailable,
                        playbackTimelineActivity = state.playbackTimelineActivity,
                        playbackTimelineFeedback = playbackTimelineFeedback,
                        onSavePlaybackTimeline = onSavePlaybackTimeline,
                    )
                }
                item(key = SettingsSectionId.About) {
                    SettingsSectionCard(
                        title = stringResource(Res.string.settings_about),
                        rows =
                            listOf(
                                {
                                    SettingsRow(
                                        icon = SettingsRowId.AppVersion.icon(),
                                        iconRole = SettingsRowId.AppVersion.iconRole(),
                                        title = stringResource(Res.string.settings_app_version),
                                        value = state.versionName,
                                        trailing = SettingsRowTrailing.None,
                                    )
                                },
                                {
                                    SettingsRow(
                                        icon = SettingsRowId.License.icon(),
                                        iconRole = SettingsRowId.License.iconRole(),
                                        title = stringResource(Res.string.settings_source_notices),
                                        value = stringResource(Res.string.settings_license),
                                        trailing = SettingsRowTrailing.Chevron,
                                        onClick = { openRow = SettingsRowId.License },
                                    )
                                    if (openRow == SettingsRowId.License) {
                                        OpenSourceNoticesDialog(
                                            sourceRevision = state.sourceRevision,
                                            onDismiss = onDismiss,
                                        )
                                    }
                                },
                            ),
                    )
                }
                item(key = SettingsSectionId.ComingLater) {
                    SettingsSectionCard(
                        title = stringResource(Res.string.settings_coming_later),
                        rows =
                            listOf(
                                Res.string.settings_feature_syncplay,
                                Res.string.settings_feature_chromecast,
                            ).map { label ->
                                { PlannedFeatureRow(stringResource(label)) }
                            },
                    )
                }
            }
        }
        AppTopBar(
            title = title,
            visible = topBarVisible,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        snackbarHostState?.let { hostState ->
            SnackbarHost(
                hostState = hostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
