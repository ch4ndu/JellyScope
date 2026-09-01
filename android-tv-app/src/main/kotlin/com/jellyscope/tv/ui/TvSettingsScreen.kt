// SPDX-License-Identifier: MPL-2.0
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.DOWNLOAD_BYTES_PER_GB
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.playbackQualityChoices
import com.jellyscope.core.domain.playback.vlcTranscodeBudgetOptions
import com.jellyscope.tv.R
import com.jellyscope.tv.TvSettingsPreferencesViewModel
import com.jellyscope.ui.screen.account.AccountUiError
import com.jellyscope.ui.screen.account.AccountUiState
import com.jellyscope.ui.screen.account.AccountViewModel
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.settings.SettingsUiState
import com.jellyscope.ui.screen.settings.SettingsViewModel
import com.jellyscope.ui.screen.settings.autoplayDelayOptions
import com.jellyscope.ui.theme.themeSwatchColors
import kotlinx.coroutines.flow.collect
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner
import com.jellyscope.ui.component.DetailText as TvText

@Composable
fun TvSettingsScreen(
    session: Session,
    onLogoutComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(parameters = { parametersOf(session) }),
    accountViewModel: AccountViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val preferenceViewModel: TvSettingsPreferencesViewModel = koinViewModel()
    val focusedCardZoomEnabled by preferenceViewModel.focusedCardZoomEnabled.collectAsStateWithLifecycle()
    val showLibraryGridHero by preferenceViewModel.showLibraryGridHero.collectAsStateWithLifecycle()
    var logFeedback by remember { mutableStateOf<SendClientLogsResult?>(null) }
    var logoutFailed by remember { mutableStateOf(false) }
    val hostedRail = LocalTvHostedRailController.current
    val serverRequester = remember { FocusRequester() }
    val contentEntryRequests = remember { mutableIntStateOf(0) }
    val registrationKey = remember { hostedRail.contentRegistrationKey }
    val requestContentFocus =
        remember {
            {
                contentEntryRequests.intValue += 1
                true
            }
        }

    LaunchedEffect(hostedRail.visible, registrationKey, serverRequester) {
        if (hostedRail.visible) {
            hostedRail.setContentRightFocusRequester(registrationKey, serverRequester)
            hostedRail.setContentRightFocusAction(registrationKey, requestContentFocus)
        }
    }

    fun requestRailFocus(): Boolean = if (hostedRail.enabled) hostedRail.requestRailFocus() else false

    BackHandler(enabled = !hostedRail.railHasFocus) { requestRailFocus() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is com.jellyscope.ui.screen.settings.SettingsEvent.ClientLogsSent -> logFeedback = event.result
                com.jellyscope.ui.screen.settings.SettingsEvent.LogoutFailed -> logoutFailed = true
            }
        }
    }

    TvSettingsContent(
        session = session,
        state = state,
        accountState = accountState,
        serverFocusRequester = serverRequester,
        requestInitialFocus = !hostedRail.contentAutofocusSuppressed,
        entryFocusRequests = contentEntryRequests.intValue,
        contentStartPadding = if (hostedRail.visible) TvDimens.drawerContentStartPadding else TvDimens.overscanHorizontal,
        onRequestRailFocus = ::requestRailFocus,
        onLogout = {
            logoutFailed = false
            viewModel.logout(onLogoutComplete)
        },
        onConfirmLogoutRemoval = { viewModel.confirmLogoutRemoval(onLogoutComplete) },
        onDismissLogoutRemoval = viewModel::dismissLogoutRemoval,
        onSwitchAccount = accountViewModel::switchTo,
        onAddAccount = accountViewModel::addAccount,
        onDefaultQualityPolicySelected = viewModel::setDefaultQualityPolicy,
        onVlcTranscodeMaxBitrateSelected = viewModel::setVlcTranscodeMaxBitrateBps,
        onDefaultPlayerBackendSelected = viewModel::setDefaultPlayerBackend,
        onRetryPlaybackPreferences = viewModel::reloadPlaybackPreferences,
        onPreferredAudioLanguageChange = viewModel::setPreferredAudioLanguage,
        onPreferredSubtitleLanguageChange = viewModel::setPreferredSubtitleLanguage,
        onAutoPlayNextChange = viewModel::setAutoPlayNext,
        onAutoPlayNextDelaySelected = viewModel::setAutoPlayNextDelaySeconds,
        onStillWatchingPromptChange = viewModel::setStillWatchingPrompt,
        onPlaybackWarningsEnabledChange = viewModel::setPlaybackWarningsEnabled,
        onPlayerAudioModeSelected = viewModel::setPlayerAudioMode,
        onPlayerHdrModeSelected = viewModel::setPlayerHdrMode,
        onMatchDisplayRefreshRateChange = viewModel::setMatchDisplayRefreshRate,
        onSegmentSkipPolicySelected = viewModel::setSegmentSkipPolicy,
        onRefreshPlayerDevicePolicy = viewModel::refreshPlayerDevicePolicy,
        onThemeSelected = viewModel::setAppTheme,
        onTileSizeSelected = viewModel::setTileSize,
        focusedCardZoomEnabled = focusedCardZoomEnabled,
        showLibraryGridHero = showLibraryGridHero,
        onFocusedCardZoomEnabledChange = preferenceViewModel::setFocusedCardZoomEnabled,
        onShowLibraryGridHeroChange = preferenceViewModel::setShowLibraryGridHero,
        onRememberLastLibraryViewChange = viewModel::setRememberLastLibraryView,
        onOpenSubtitlesApiKeyChange = viewModel::setOpenSubtitlesApiKey,
        onRetryOpenSubtitlesApiKeyLoad = viewModel::retryOpenSubtitlesApiKeyLoad,
        onClearLocalSubtitles = viewModel::clearLocalSubtitles,
        logFeedback = logFeedback,
        logoutFailed = logoutFailed,
        onCollectLogsChange = viewModel::setLogCollectionEnabled,
        onVerboseLogcatChange = viewModel::setVerboseLogcatEnabled,
        onPlaybackInfoAtStartChange = viewModel::setPlaybackInfoAtStartEnabled,
        onSendClientLogs = viewModel::sendClientLogs,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
internal fun TvSettingsContent(
    session: Session,
    state: SettingsUiState,
    accountState: AccountUiState,
    serverFocusRequester: FocusRequester = remember { FocusRequester() },
    requestInitialFocus: Boolean,
    entryFocusRequests: Int = 0,
    initialScrollFraction: Float = 0f,
    initialDialogTile: TvSettingsTileId? = null,
    contentStartPadding: Dp = TvDimens.overscanHorizontal,
    onRequestRailFocus: () -> Boolean = { false },
    onLogout: () -> Unit,
    onConfirmLogoutRemoval: () -> Unit = {},
    onDismissLogoutRemoval: () -> Unit = {},
    onSwitchAccount: (String) -> Unit,
    onAddAccount: (String, String, String) -> Unit,
    onDefaultQualityPolicySelected: (PlaybackQualityPolicy) -> Unit,
    onVlcTranscodeMaxBitrateSelected: (Long?) -> Unit = {},
    onDefaultPlayerBackendSelected: (PlayerBackend) -> Unit = {},
    onRetryPlaybackPreferences: () -> Unit = {},
    onPreferredAudioLanguageChange: (String?) -> Unit,
    onPreferredSubtitleLanguageChange: (String?) -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit = {},
    onAutoPlayNextDelaySelected: (Int) -> Unit = {},
    onStillWatchingPromptChange: (Boolean) -> Unit,
    onPlaybackWarningsEnabledChange: (Boolean) -> Unit = {},
    onPlayerAudioModeSelected: (PlayerAudioMode) -> Unit,
    onPlayerHdrModeSelected: (PlayerHdrMode) -> Unit,
    onMatchDisplayRefreshRateChange: (Boolean) -> Unit,
    onSegmentSkipPolicySelected: (MediaSegmentType, SegmentSkipPolicy) -> Unit,
    onRefreshPlayerDevicePolicy: () -> Unit,
    onThemeSelected: (AppColorThemeId) -> Unit,
    onTileSizeSelected: (TileSizeId) -> Unit,
    focusedCardZoomEnabled: Boolean,
    showLibraryGridHero: Boolean,
    onFocusedCardZoomEnabledChange: (Boolean) -> Unit,
    onShowLibraryGridHeroChange: (Boolean) -> Unit,
    onRememberLastLibraryViewChange: (Boolean) -> Unit,
    onOpenSubtitlesApiKeyChange: (String, () -> Unit) -> Unit = { _, _ -> },
    onRetryOpenSubtitlesApiKeyLoad: () -> Unit = {},
    onClearLocalSubtitles: () -> Unit = {},
    logFeedback: SendClientLogsResult? = null,
    logoutFailed: Boolean = false,
    onCollectLogsChange: (Boolean) -> Unit = {},
    onVerboseLogcatChange: (Boolean) -> Unit = {},
    onPlaybackInfoAtStartChange: (Boolean) -> Unit = {},
    onSendClientLogs: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val rowAwareMarioSpec = rememberRowAwareMarioBringIntoViewSpec()
    val horizontalMarioSpec = rememberMarioBringIntoViewSpec()
    val rowScrollStates = tvSettingsGridSections.map { rememberScrollState() }
    val contentEndPadding =
        TvDimens.overscanHorizontal +
            TvDimens.settingsGridIndicatorWidth +
            TvDimens.settingsGridIndicatorEndPadding
    val tileRequesters = remember { List(TvSettingsTileId.entries.size) { FocusRequester() } }
    var focusedTile by remember { mutableStateOf(tvSettingsEntryTile) }
    var openTile by rememberSaveable { mutableStateOf(initialDialogTile) }
    var restoreTile by remember { mutableStateOf<TvSettingsTileId?>(null) }
    var restoreRequests by remember { mutableIntStateOf(0) }
    var logoutRemovalDismissRequested by remember { mutableStateOf(false) }

    fun requesterFor(tile: TvSettingsTileId): FocusRequester =
        if (tile == tvSettingsEntryTile) serverFocusRequester else tileRequesters[tile.ordinal]

    fun dismissDialog() {
        openTile = null
        restoreRequests++
    }

    fun openDialog(tile: TvSettingsTileId) {
        restoreTile = tvSettingsDialogRestoreTarget(tile)
        openTile = tile
    }

    fun dispatchLogoutRemoval(action: TvLogoutRemovalDialogAction) {
        logoutRemovalDismissRequested =
            dispatchTvLogoutRemovalDialogAction(
                action = action,
                dismissalRequested = logoutRemovalDismissRequested,
                onConfirm = onConfirmLogoutRemoval,
                onDismiss = onDismissLogoutRemoval,
            )
    }

    fun move(direction: FocusDirection): Boolean {
        when (val destination = tvSettingsGridDestination(focusedTile, direction)) {
            TvSettingsGridDestination.Rail -> onRequestRailFocus()
            TvSettingsGridDestination.Consume -> Unit
            is TvSettingsGridDestination.Tile -> {
                // Every horizontal row stays composed even when some tiles are
                // outside its viewport. Requesting synchronously therefore
                // keeps logical and actual focus aligned under held-key repeats;
                // the row's bring-into-view policy owns horizontal scrolling.
                requesterFor(destination.id).requestFocusSafely()
            }
        }
        return true
    }

    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) requestTvFocusWithRetry { serverFocusRequester.requestFocusSafely() }
    }
    LaunchedEffect(entryFocusRequests) {
        if (entryFocusRequests > 0) requestTvFocusWithRetry { serverFocusRequester.requestFocusSafely() }
    }
    LaunchedEffect(restoreRequests) {
        val tile = restoreTile ?: return@LaunchedEffect
        if (restoreRequests > 0) requestTvFocusWithRetry { requesterFor(tile).requestFocusSafely() }
    }
    LaunchedEffect(state.logoutRemovalPreview, logoutRemovalDismissRequested) {
        val completed =
            completeTvLogoutRemovalDismissalIfReady(
                dismissalRequested = logoutRemovalDismissRequested,
                previewPresent = state.logoutRemovalPreview != null,
                onComplete = {
                    restoreTile = tvSettingsDialogRestoreTarget(TvSettingsTileId.Logout)
                    dismissDialog()
                },
            )
        if (completed) logoutRemovalDismissRequested = false
    }
    LaunchedEffect(initialScrollFraction, scrollState.maxValue) {
        scrollState.scrollTo((scrollState.maxValue * initialScrollFraction.coerceIn(0f, 1f)).toInt())
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides rowAwareMarioSpec) {
        Box(
            modifier =
                modifier
                    .onPreviewKeyEvent { event ->
                        if (openTile != null || event.type != KeyEventType.KeyDown) {
                            false
                        } else {
                            when (event.key) {
                                Key.DirectionLeft -> move(FocusDirection.Left)
                                Key.DirectionRight -> move(FocusDirection.Right)
                                Key.DirectionUp -> move(FocusDirection.Up)
                                Key.DirectionDown -> move(FocusDirection.Down)
                                else -> false
                            }
                        }
                    },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(
                            top = TvDimens.overscanVertical + TvDimens.settingsContentTopPadding,
                            bottom = TvDimens.overscanVertical,
                        ),
                verticalArrangement = Arrangement.spacedBy(TvDimens.settingsGridRowGap),
            ) {
                tvSettingsGridSections.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.settingsGridSectionLabelGap)) {
                        TvText(
                            text = tvSettingsSectionTitle(section),
                            modifier = Modifier.padding(start = contentStartPadding, end = contentEndPadding),
                            color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
                            maxLines = 1,
                        )
                        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalMarioSpec) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rowScrollStates[section.row])
                                        .padding(
                                            start = contentStartPadding + TvDimens.settingsGridTileFocusReserve,
                                            end = contentEndPadding + TvDimens.settingsGridTileFocusReserve,
                                            top = TvDimens.settingsGridTileFocusReserve,
                                            bottom = TvDimens.settingsGridTileFocusReserve,
                                        ),
                                horizontalArrangement = Arrangement.spacedBy(TvDimens.settingsGridTileGap),
                            ) {
                                tvSettingsGridRows[section.row].forEach { tile ->
                                    val presentation =
                                        tvSettingsTilePresentation(tile, state, accountState, focusedCardZoomEnabled, showLibraryGridHero)
                                    TvSettingsGridTile(
                                        id = tile,
                                        title = presentation.title,
                                        value = presentation.value,
                                        focusRequester = requesterFor(tile),
                                        onFocused = { focusedTile = tile },
                                        onClick = { openDialog(tile) },
                                        modifier = Modifier.width(TvDimens.settingsGridCircleDiameter),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            TvSettingsScrollIndicator(
                progress = {
                    if (scrollState.maxValue == 0) 0f else scrollState.value.toFloat() / scrollState.maxValue
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = TvDimens.settingsGridIndicatorEndPadding),
            )
        }
    }

    val logoutRemovalPreview = state.logoutRemovalPreview
    if (logoutRemovalPreview != null) {
        TvLogoutRemovalDialog(
            preview = logoutRemovalPreview,
            dismissalRequested = logoutRemovalDismissRequested,
            onConfirm = { dispatchLogoutRemoval(TvLogoutRemovalDialogAction.Confirm) },
            onDismiss = { dispatchLogoutRemoval(TvLogoutRemovalDialogAction.Dismiss) },
        )
    } else if (!logoutRemovalDismissRequested) {
        openTile?.let { tile ->
            TvSettingsTileDialog(
                tile = tile,
                session = session,
                state = state,
                accountState = accountState,
                focusedCardZoomEnabled = focusedCardZoomEnabled,
                showLibraryGridHero = showLibraryGridHero,
                onDismiss = ::dismissDialog,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onAddAccount = onAddAccount,
                onDefaultQualityPolicySelected = onDefaultQualityPolicySelected,
                onVlcTranscodeMaxBitrateSelected = onVlcTranscodeMaxBitrateSelected,
                onDefaultPlayerBackendSelected = onDefaultPlayerBackendSelected,
                onRetryPlaybackPreferences = onRetryPlaybackPreferences,
                onPreferredAudioLanguageChange = onPreferredAudioLanguageChange,
                onPreferredSubtitleLanguageChange = onPreferredSubtitleLanguageChange,
                onAutoPlayNextChange = onAutoPlayNextChange,
                onAutoPlayNextDelaySelected = onAutoPlayNextDelaySelected,
                onStillWatchingPromptChange = onStillWatchingPromptChange,
                onPlaybackWarningsEnabledChange = onPlaybackWarningsEnabledChange,
                onPlayerAudioModeSelected = onPlayerAudioModeSelected,
                onPlayerHdrModeSelected = onPlayerHdrModeSelected,
                onMatchDisplayRefreshRateChange = onMatchDisplayRefreshRateChange,
                onSegmentSkipPolicySelected = onSegmentSkipPolicySelected,
                onRefreshPlayerDevicePolicy = onRefreshPlayerDevicePolicy,
                onThemeSelected = onThemeSelected,
                onTileSizeSelected = onTileSizeSelected,
                onFocusedCardZoomEnabledChange = onFocusedCardZoomEnabledChange,
                onShowLibraryGridHeroChange = onShowLibraryGridHeroChange,
                onRememberLastLibraryViewChange = onRememberLastLibraryViewChange,
                onOpenSubtitlesApiKeyChange = onOpenSubtitlesApiKeyChange,
                onRetryOpenSubtitlesApiKeyLoad = onRetryOpenSubtitlesApiKeyLoad,
                onClearLocalSubtitles = onClearLocalSubtitles,
                logFeedback = logFeedback,
                logoutFailed = logoutFailed,
                onCollectLogsChange = onCollectLogsChange,
                onVerboseLogcatChange = onVerboseLogcatChange,
                onPlaybackInfoAtStartChange = onPlaybackInfoAtStartChange,
                onSendClientLogs = onSendClientLogs,
            )
        }
    }
}

internal enum class TvLogoutRemovalDialogAction {
    Confirm,
    Dismiss,
}

internal data class TvLogoutRemovalSummary(
    val recordCount: Long,
    val displayedBytes: Long,
)

internal fun tvLogoutRemovalSummary(preview: DownloadRemovalPreview): TvLogoutRemovalSummary =
    preview.confirmations.fold(TvLogoutRemovalSummary(recordCount = 0L, displayedBytes = 0L)) { summary, confirmation ->
        TvLogoutRemovalSummary(
            recordCount = tvLogoutRemovalSaturatingAdd(summary.recordCount, confirmation.recordCount),
            displayedBytes = tvLogoutRemovalSaturatingAdd(summary.displayedBytes, confirmation.displayedBytes),
        )
    }

internal fun dispatchTvLogoutRemovalDialogAction(
    action: TvLogoutRemovalDialogAction,
    dismissalRequested: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    if (dismissalRequested) return true
    return when (action) {
        TvLogoutRemovalDialogAction.Confirm -> {
            onConfirm()
            false
        }
        TvLogoutRemovalDialogAction.Dismiss -> {
            onDismiss()
            true
        }
    }
}

internal fun completeTvLogoutRemovalDismissalIfReady(
    dismissalRequested: Boolean,
    previewPresent: Boolean,
    onComplete: () -> Unit,
): Boolean {
    if (!dismissalRequested || previewPresent) return false
    onComplete()
    return true
}

private fun tvLogoutRemovalSaturatingAdd(
    left: Long,
    right: Long,
): Long = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private data class TvSettingsTilePresentation(
    val title: String,
    val value: String,
)

@Composable
private fun tvSettingsSectionTitle(section: TvSettingsSectionId): String =
    when (section) {
        TvSettingsSectionId.Account -> stringResource(R.string.tv_settings_account)
        TvSettingsSectionId.Appearance -> stringResource(R.string.tv_settings_appearance)
        TvSettingsSectionId.Playback -> stringResource(R.string.tv_settings_playback)
        TvSettingsSectionId.SkipSegments -> stringResource(R.string.tv_settings_skip_segments)
        TvSettingsSectionId.DevicePlayback -> stringResource(R.string.tv_settings_device_playback)
        TvSettingsSectionId.ServicesAndAbout -> stringResource(R.string.tv_settings_services_about)
        TvSettingsSectionId.Diagnostics -> stringResource(R.string.tv_settings_diagnostics)
    }

@Composable
private fun tvSettingsTilePresentation(
    tile: TvSettingsTileId,
    state: SettingsUiState,
    accountState: AccountUiState,
    focusedCardZoomEnabled: Boolean,
    showLibraryGridHero: Boolean,
): TvSettingsTilePresentation {
    val auto = stringResource(R.string.tv_settings_language_auto)
    val unknown = stringResource(R.string.tv_settings_player_unknown)
    val presentation =
        when (tile) {
            TvSettingsTileId.Server -> TvSettingsTilePresentation(stringResource(R.string.tv_settings_server_name), state.serverName)
            TvSettingsTileId.SignedInUser ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_signed_in_user),
                    state.userName,
                )
            TvSettingsTileId.ActiveAccount ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_active_account),
                    state.userName,
                )
            TvSettingsTileId.AddAccount -> TvSettingsTilePresentation(stringResource(R.string.tv_settings_add_account), state.serverName)
            TvSettingsTileId.Logout -> TvSettingsTilePresentation(stringResource(R.string.tv_settings_logout), state.userName)
            TvSettingsTileId.Theme -> TvSettingsTilePresentation(stringResource(R.string.tv_settings_theme), tvThemeLabel(state.appTheme))
            TvSettingsTileId.TileSize ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_tile_size),
                    tvTileSizeLabel(state.tileSize),
                )
            TvSettingsTileId.FocusedCardZoom ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_focused_card_zoom),
                    tvSettingsToggleValue(focusedCardZoomEnabled),
                )
            TvSettingsTileId.RememberLastLibrary ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_remember_library_view),
                    tvSettingsToggleValue(state.rememberLastLibraryView),
                )
            TvSettingsTileId.LibraryGridHero ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_library_grid_hero),
                    tvSettingsToggleValue(showLibraryGridHero),
                )
            TvSettingsTileId.AudioLanguage ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_audio_language),
                    state.playbackPreferences.preferredAudioLanguage ?: auto,
                )
            TvSettingsTileId.SubtitleLanguage ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_subtitle_language),
                    state.playbackPreferences.preferredSubtitleLanguage ?: auto,
                )
            TvSettingsTileId.MaxBitrate ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_max_bitrate),
                    settingsQualityLabel(
                        settingsQualityChoice(state.playbackPreferences.effectiveDefaultQualityPolicy()),
                    ),
                )
            TvSettingsTileId.VlcTranscodeLimit ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_vlc_transcode_limit),
                    vlcBudgetLabel(state.playbackPreferences.vlcTranscodeMaxBitrateBps),
                )
            TvSettingsTileId.StillWatching ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_still_watching),
                    tvSettingsToggleValue(state.playbackPreferences.stillWatchingPrompt),
                )
            TvSettingsTileId.PlaybackWarnings ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_playback_warnings),
                    tvSettingsToggleValue(state.playbackPreferences.playbackWarningsEnabled),
                )
            TvSettingsTileId.AudioOutput ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_audio_output),
                    tvPlayerAudioModeLabel(state.playerDeviceSettings.audioMode),
                )
            TvSettingsTileId.PlayerBackend ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_player_backend),
                    tvPlayerBackendLabel(state.selectedPlayerBackend),
                )
            TvSettingsTileId.AutoPlayNext ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_autoplay_next),
                    tvSettingsToggleValue(state.playbackPreferences.autoPlayNext),
                )
            TvSettingsTileId.AutoPlayNextDelay ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_autoplay_delay),
                    tvAutoplayDelayLabel(state.playbackPreferences.autoPlayNextDelaySeconds),
                )
            TvSettingsTileId.Intros -> tvSegmentPresentation(MediaSegmentType.Intro, state)
            TvSettingsTileId.Credits -> tvSegmentPresentation(MediaSegmentType.Outro, state)
            TvSettingsTileId.Recaps -> tvSegmentPresentation(MediaSegmentType.Recap, state)
            TvSettingsTileId.Previews -> tvSegmentPresentation(MediaSegmentType.Preview, state)
            TvSettingsTileId.Commercials -> tvSegmentPresentation(MediaSegmentType.Commercial, state)
            TvSettingsTileId.VideoFormats ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_video_formats),
                    state.playerDevicePolicy
                        ?.capabilities
                        ?.videoCodecs
                        ?.formatCapabilityValue(unknown) ?: unknown,
                )
            TvSettingsTileId.AudioFormats ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_audio_formats),
                    state.playerDevicePolicy?.audioCodecs?.formatCapabilityValue(unknown) ?: unknown,
                )
            TvSettingsTileId.HdrHandling ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_hdr_output),
                    tvPlayerHdrModeLabel(state.playerDeviceSettings.hdrMode),
                )
            TvSettingsTileId.MatchRefreshRate ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_match_display_refresh_rate),
                    tvSettingsToggleValue(state.playerDeviceSettings.matchDisplayRefreshRate),
                )
            TvSettingsTileId.RefreshCapabilities ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_refresh_capabilities),
                    if (state.playerDevicePolicy ==
                        null
                    ) {
                        unknown
                    } else {
                        stringResource(R.string.tv_settings_capabilities_available)
                    },
                )
            TvSettingsTileId.OpenSubtitlesKey ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_opensubtitles_api_key),
                    when {
                        state.openSubtitlesApiKeyLoadError -> stringResource(R.string.tv_retry)
                        !state.isOpenSubtitlesApiKeyLoaded -> stringResource(R.string.tv_settings_opensubtitles_key_loading)
                        else -> state.openSubtitlesApiKey.maskedSettingValue()
                    },
                )
            TvSettingsTileId.ClearSubtitles ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_local_subtitles_clear),
                    if (state.isClearingLocalSubtitles) {
                        stringResource(
                            R.string.tv_settings_local_subtitles_clearing,
                        )
                    } else {
                        stringResource(R.string.tv_settings_dialog_ready)
                    },
                )
            TvSettingsTileId.AppVersion -> TvSettingsTilePresentation(stringResource(R.string.tv_settings_app_version), state.versionName)
            TvSettingsTileId.AboutJellyScope ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_about),
                    stringResource(R.string.app_name),
                )
            TvSettingsTileId.ServerUrl ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_server_url_label),
                    state.serverUrl,
                )
            TvSettingsTileId.CollectLogs ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_collect_logs),
                    if (state.logCollectionPreferenceError) {
                        stringResource(R.string.tv_settings_collect_logs_error)
                    } else {
                        tvSettingsToggleValue(state.collectLogs)
                    },
                )
            TvSettingsTileId.SendClientLogs ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_send_logs),
                    if (state.isSendingClientLogs) {
                        stringResource(R.string.tv_settings_log_sending)
                    } else if (!state.collectLogs) {
                        stringResource(R.string.tv_settings_device_capabilities_only)
                    } else {
                        stringResource(
                            R.string.tv_settings_log_buffer,
                            state.logBufferSize.entryCount,
                            state.logBufferSize.byteCount / 1_024,
                        )
                    },
                )
            TvSettingsTileId.VerboseLogcat ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_verbose_logcat),
                    tvSettingsToggleValue(state.verboseLogcatEnabled),
                )
            TvSettingsTileId.PlaybackInfoAtStart ->
                TvSettingsTilePresentation(
                    stringResource(R.string.tv_settings_playback_info_at_start),
                    tvSettingsToggleValue(state.playbackInfoAtStartEnabled),
                )
        }
    val playbackStatus =
        when {
            state.isLoadingPlaybackPreferences -> stringResource(R.string.tv_loading)
            state.playbackPreferencesError -> stringResource(R.string.tv_settings_unavailable)
            else -> null
        }
    return if (tile.usesPlaybackPreferences() && playbackStatus != null) {
        presentation.copy(value = playbackStatus)
    } else {
        presentation
    }
}

@Composable
private fun tvSegmentPresentation(
    type: MediaSegmentType,
    state: SettingsUiState,
): TvSettingsTilePresentation =
    TvSettingsTilePresentation(
        title = segmentTypeLabel(type),
        value = segmentSkipPolicyLabel(state.playbackPreferences.policyFor(type)),
    )

@Composable
private fun tvPlayerBackendLabel(backend: PlayerBackend): String =
    when (backend) {
        PlayerBackend.Auto,
        PlayerBackend.AVPlayer,
        PlayerBackend.VlcKit,
        PlayerBackend.ExoPlayer,
        -> stringResource(R.string.tv_settings_player_backend_exoplayer)
        PlayerBackend.Mpv -> stringResource(R.string.tv_settings_player_backend_mpv)
        PlayerBackend.LibVlc -> stringResource(R.string.tv_settings_player_backend_libvlc)
    }

@Composable
private fun tvAutoplayDelayLabel(delaySeconds: Int): String =
    if (delaySeconds == 0) {
        stringResource(R.string.tv_settings_autoplay_delay_immediate)
    } else {
        stringResource(R.string.tv_settings_autoplay_delay_seconds, delaySeconds)
    }

@Composable
private fun String.maskedSettingValue(): String =
    if (isBlank()) {
        stringResource(R.string.tv_settings_not_set)
    } else {
        stringResource(R.string.tv_settings_concealed_value)
    }

private fun List<String>.formatCapabilityValue(unknown: String): String = takeIf(List<String>::isNotEmpty)?.joinToString() ?: unknown

@Composable
private fun TvSettingsScrollIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val palette = com.jellyscope.ui.theme.LocalJellyfinPalette.current
    androidx.compose.foundation.Canvas(
        modifier =
            modifier.size(
                width = TvDimens.settingsGridIndicatorWidth,
                height = TvDimens.settingsGridIndicatorHeight,
            ),
    ) {
        val thumbHeight = TvDimens.settingsGridIndicatorMinThumbHeight.toPx().coerceAtMost(size.height)
        val thumbTop = (size.height - thumbHeight) * progress().coerceIn(0f, 1f)
        drawRoundRect(
            color = palette.outline.copy(alpha = 0.48f),
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(size.width / 2f),
        )
        drawRoundRect(
            color = palette.cyan,
            topLeft =
                androidx.compose.ui.geometry
                    .Offset(0f, thumbTop),
            size =
                androidx.compose.ui.geometry
                    .Size(size.width, thumbHeight),
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(size.width / 2f),
        )
    }
}

@Composable
private fun TvSettingsTileDialog(
    tile: TvSettingsTileId,
    session: Session,
    state: SettingsUiState,
    accountState: AccountUiState,
    focusedCardZoomEnabled: Boolean,
    showLibraryGridHero: Boolean,
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: (String, String, String) -> Unit,
    onDefaultQualityPolicySelected: (PlaybackQualityPolicy) -> Unit,
    onVlcTranscodeMaxBitrateSelected: (Long?) -> Unit,
    onDefaultPlayerBackendSelected: (PlayerBackend) -> Unit,
    onRetryPlaybackPreferences: () -> Unit,
    onPreferredAudioLanguageChange: (String?) -> Unit,
    onPreferredSubtitleLanguageChange: (String?) -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit,
    onAutoPlayNextDelaySelected: (Int) -> Unit,
    onStillWatchingPromptChange: (Boolean) -> Unit,
    onPlaybackWarningsEnabledChange: (Boolean) -> Unit,
    onPlayerAudioModeSelected: (PlayerAudioMode) -> Unit,
    onPlayerHdrModeSelected: (PlayerHdrMode) -> Unit,
    onMatchDisplayRefreshRateChange: (Boolean) -> Unit,
    onSegmentSkipPolicySelected: (MediaSegmentType, SegmentSkipPolicy) -> Unit,
    onRefreshPlayerDevicePolicy: () -> Unit,
    onThemeSelected: (AppColorThemeId) -> Unit,
    onTileSizeSelected: (TileSizeId) -> Unit,
    onFocusedCardZoomEnabledChange: (Boolean) -> Unit,
    onShowLibraryGridHeroChange: (Boolean) -> Unit,
    onRememberLastLibraryViewChange: (Boolean) -> Unit,
    onOpenSubtitlesApiKeyChange: (String, () -> Unit) -> Unit,
    onRetryOpenSubtitlesApiKeyLoad: () -> Unit,
    onClearLocalSubtitles: () -> Unit,
    logFeedback: SendClientLogsResult?,
    onCollectLogsChange: (Boolean) -> Unit,
    onVerboseLogcatChange: (Boolean) -> Unit,
    onPlaybackInfoAtStartChange: (Boolean) -> Unit,
    onSendClientLogs: () -> Unit,
    logoutFailed: Boolean,
) {
    if (tile.usesPlaybackPreferences() && (state.isLoadingPlaybackPreferences || state.playbackPreferencesError)) {
        TvPlaybackPreferencesStatusDialog(
            loading = state.isLoadingPlaybackPreferences,
            onRetry = onRetryPlaybackPreferences,
            onDismiss = onDismiss,
        )
        return
    }
    when (tile.action()) {
        TvSettingsTileAction.Detail -> {
            val presentation = tvSettingsTilePresentation(tile, state, accountState, focusedCardZoomEnabled, showLibraryGridHero)
            val detail =
                if (tile == TvSettingsTileId.AboutJellyScope) {
                    stringResource(
                        R.string.tv_settings_about_detail,
                        state.versionName,
                        state.sourceRevision,
                        state.sourceRevision,
                    )
                } else {
                    presentation.value
                }
            TvSettingsDetailDialog(
                title = presentation.title,
                detail = detail,
                onDismiss = onDismiss,
                maxLines = if (tile == TvSettingsTileId.AboutJellyScope) 16 else 8,
            )
        }
        TvSettingsTileAction.SwitchAccount -> TvSwitchAccountDialog(accountState, onSwitchAccount, onDismiss)
        TvSettingsTileAction.AddAccount -> TvAddAccountDialog(session, accountState, onAddAccount, onDismiss)
        TvSettingsTileAction.Logout -> TvLogoutDialog(state.isLoggingOut, logoutFailed, onLogout, onDismiss)
        TvSettingsTileAction.Theme ->
            TvSettingsChoiceDialog(
                title = stringResource(R.string.tv_settings_theme),
                description = stringResource(R.string.tv_settings_theme_description),
                selected = state.appTheme,
                options =
                    AppColorThemeId.entries.map { theme ->
                        TvSettingsDialogOption(
                            value = theme,
                            label = tvThemeLabel(theme),
                            swatchColors = themeSwatchColors(theme),
                        )
                    },
                onSelected = { theme ->
                    onThemeSelected(theme)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.TileSize ->
            TvSettingsChoiceDialog(
                title = stringResource(R.string.tv_settings_tile_size),
                description = stringResource(R.string.tv_settings_tile_size_description),
                selected = state.tileSize,
                options = TileSizeId.entries.map { TvSettingsDialogOption(it, tvTileSizeLabel(it)) },
                onSelected = { size ->
                    onTileSizeSelected(size)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.FocusedCardZoom ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_focused_card_zoom),
                description = stringResource(R.string.tv_settings_focused_card_zoom_description),
                value = focusedCardZoomEnabled,
                onSelected = { enabled ->
                    onFocusedCardZoomEnabledChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.RememberLastLibrary ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_remember_library_view),
                description = stringResource(R.string.tv_settings_remember_library_description),
                value = state.rememberLastLibraryView,
                onSelected = { enabled ->
                    onRememberLastLibraryViewChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.LibraryGridHero ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_library_grid_hero),
                description = stringResource(R.string.tv_settings_library_grid_hero_description),
                value = showLibraryGridHero,
                onSelected = { enabled ->
                    onShowLibraryGridHeroChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.AudioLanguage ->
            TvSettingsTextDialog(
                title = stringResource(R.string.tv_settings_audio_language),
                label = stringResource(R.string.tv_settings_audio_language),
                initialValue = state.playbackPreferences.preferredAudioLanguage.orEmpty(),
                onApply = { value, dismiss ->
                    onPreferredAudioLanguageChange(value)
                    dismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.SubtitleLanguage ->
            TvSettingsTextDialog(
                title = stringResource(R.string.tv_settings_subtitle_language),
                label = stringResource(R.string.tv_settings_subtitle_language),
                initialValue = state.playbackPreferences.preferredSubtitleLanguage.orEmpty(),
                onApply = { value, dismiss ->
                    onPreferredSubtitleLanguageChange(value)
                    dismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.MaxBitrate ->
            TvSettingsChoiceDialog(
                title = stringResource(R.string.tv_settings_max_bitrate),
                description = stringResource(R.string.tv_settings_max_bitrate_description),
                selected = state.playbackPreferences.effectiveDefaultQualityPolicy(),
                options =
                    playbackQualityChoices(state.playbackPreferences.effectiveDefaultQualityPolicy()).map { option ->
                        TvSettingsDialogOption(
                            value = option.toPlaybackQualityPolicy(),
                            label = settingsQualityLabel(option),
                        )
                    },
                onSelected = { policy ->
                    onDefaultQualityPolicySelected(policy)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.VlcTranscodeLimit ->
            TvSettingsChoiceDialog(
                title = stringResource(R.string.tv_settings_vlc_transcode_limit),
                description = stringResource(R.string.tv_settings_vlc_transcode_limit_description),
                selected = state.playbackPreferences.vlcTranscodeMaxBitrateBps,
                options =
                    vlcTranscodeBudgetOptions.map { bitrate ->
                        TvSettingsDialogOption(
                            value = bitrate,
                            label = vlcBudgetLabel(bitrate),
                        )
                    },
                onSelected = { bitrate ->
                    onVlcTranscodeMaxBitrateSelected(bitrate)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.PlayerBackend ->
            TvSettingsChoiceDialog(
                title = stringResource(R.string.tv_settings_player_backend),
                description = stringResource(R.string.tv_settings_player_backend_description),
                selected = state.selectedPlayerBackend,
                options =
                    run {
                        val unavailable = stringResource(R.string.tv_settings_player_backend_unavailable)
                        state.playerBackendChoices.map { choice ->
                            TvSettingsDialogOption(
                                value = choice.backend,
                                label = tvPlayerBackendLabel(choice.backend),
                                enabled = choice.available,
                                unavailableReason = unavailable.takeIf { !choice.available },
                            )
                        }
                    },
                onSelected = { backend ->
                    onDefaultPlayerBackendSelected(backend)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.AutoPlayNext ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_autoplay_next),
                description = stringResource(R.string.tv_settings_autoplay_next_description),
                value = state.playbackPreferences.autoPlayNext,
                onSelected = { enabled ->
                    onAutoPlayNextChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.AutoPlayNextDelay ->
            TvSettingsChoiceDialog(
                title = stringResource(R.string.tv_settings_autoplay_delay),
                description = stringResource(R.string.tv_settings_autoplay_delay_description),
                selected = state.playbackPreferences.autoPlayNextDelaySeconds,
                options =
                    autoplayDelayOptions.map { delaySeconds ->
                        TvSettingsDialogOption(delaySeconds, tvAutoplayDelayLabel(delaySeconds))
                    },
                onSelected = { delaySeconds ->
                    onAutoPlayNextDelaySelected(delaySeconds)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.StillWatching ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_still_watching),
                description = stringResource(R.string.tv_settings_still_watching_description),
                value = state.playbackPreferences.stillWatchingPrompt,
                onSelected = { enabled ->
                    onStillWatchingPromptChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.PlaybackWarnings ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_playback_warnings),
                description = stringResource(R.string.tv_settings_playback_warnings_description),
                value = state.playbackPreferences.playbackWarningsEnabled,
                onSelected = { enabled ->
                    onPlaybackWarningsEnabledChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.AudioOutput -> TvAudioOutputDialog(state, onPlayerAudioModeSelected, onDismiss)
        TvSettingsTileAction.SegmentPolicy -> TvSegmentPolicyDialog(tile.segmentType(), state, onSegmentSkipPolicySelected, onDismiss)
        TvSettingsTileAction.HdrHandling -> TvHdrDialog(state, onPlayerHdrModeSelected, onDismiss)
        TvSettingsTileAction.MatchRefreshRate ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_match_display_refresh_rate),
                description = stringResource(R.string.tv_settings_refresh_rate_description),
                value = state.playerDeviceSettings.matchDisplayRefreshRate,
                onSelected = { enabled ->
                    onMatchDisplayRefreshRateChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.RefreshCapabilities -> TvRefreshCapabilitiesDialog(state, onRefreshPlayerDevicePolicy, onDismiss)
        TvSettingsTileAction.OpenSubtitlesKey ->
            if (!state.isOpenSubtitlesApiKeyLoaded) {
                TvOpenSubtitlesKeyStatusDialog(
                    loading = state.isLoadingOpenSubtitlesApiKey,
                    loadError = state.openSubtitlesApiKeyLoadError,
                    onRetry = onRetryOpenSubtitlesApiKeyLoad,
                    onDismiss = onDismiss,
                )
            } else {
                TvSettingsTextDialog(
                    title = stringResource(R.string.tv_settings_opensubtitles_api_key),
                    label = stringResource(R.string.tv_settings_opensubtitles_api_key),
                    initialValue = state.openSubtitlesApiKey,
                    concealed = true,
                    onApply = { value, dismiss -> onOpenSubtitlesApiKeyChange(value, dismiss) },
                    onDismiss = onDismiss,
                )
            }
        TvSettingsTileAction.ClearSubtitles -> TvClearSubtitlesDialog(state, onClearLocalSubtitles, onDismiss)
        TvSettingsTileAction.CollectLogs ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_collect_logs),
                description = stringResource(R.string.tv_settings_collect_logs_guidance),
                value = state.collectLogs,
                onSelected = { enabled ->
                    onCollectLogsChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.SendClientLogs ->
            TvClientLogsDialog(
                state = state,
                feedback = logFeedback,
                onSend = onSendClientLogs,
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.VerboseLogcat ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_verbose_logcat),
                description = stringResource(R.string.tv_settings_verbose_logcat_description),
                value = state.verboseLogcatEnabled,
                onSelected = { enabled ->
                    onVerboseLogcatChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        TvSettingsTileAction.PlaybackInfoAtStart ->
            TvBooleanDialog(
                title = stringResource(R.string.tv_settings_playback_info_at_start),
                description = stringResource(R.string.tv_settings_playback_info_at_start_description),
                value = state.playbackInfoAtStartEnabled,
                onSelected = { enabled ->
                    onPlaybackInfoAtStartChange(enabled)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
    }
}

@Composable
private fun TvClientLogsDialog(
    state: SettingsUiState,
    feedback: SendClientLogsResult?,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sendRequester = remember { FocusRequester() }
    LaunchedEffect(sendRequester) {
        requestTvFocusWithRetry { sendRequester.requestFocusSafely() }
    }
    TvSettingsDialogFrame(title = stringResource(R.string.tv_settings_diagnostics), onDismiss = onDismiss) {
        TvText(
            text =
                stringResource(
                    R.string.tv_settings_log_buffer,
                    state.logBufferSize.entryCount,
                    state.logBufferSize.byteCount / 1_024,
                ),
            color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
            maxLines = 1,
        )
        TvText(
            text = stringResource(R.string.tv_settings_device_capabilities_guidance),
            color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
            maxLines = TV_DIAGNOSTICS_DISCLOSURE_MAX_LINES,
        )
        feedback?.let { result ->
            TvText(
                text =
                    when (result) {
                        is SendClientLogsResult.Success -> stringResource(R.string.tv_settings_log_uploaded, result.filename)
                        SendClientLogsResult.UploadDisallowed -> stringResource(R.string.tv_settings_log_upload_disallowed)
                        SendClientLogsResult.Failure -> stringResource(R.string.tv_settings_log_upload_failure)
                    },
                color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
                maxLines = 5,
            )
        }
        TvSettingsDialogAction(
            text = stringResource(if (state.isSendingClientLogs) R.string.tv_settings_log_sending else R.string.tv_settings_send_logs),
            focusRequester = sendRequester,
            onClick = onSend,
            enabled = tvClientLogsSendEnabled(state.isSendingClientLogs),
        )
    }
}

internal fun tvClientLogsSendEnabled(isSending: Boolean): Boolean = !isSending

// Leave headroom for larger font scales and translated disclosure text.
private const val TV_DIAGNOSTICS_DISCLOSURE_MAX_LINES = 6

@Composable
private fun TvPlaybackPreferencesStatusDialog(
    loading: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primaryRequester = remember { FocusRequester() }
    val dismissFocusRequester = remember { FocusRequester() }
    LaunchedEffect(primaryRequester, dismissFocusRequester, loading) {
        requestTvFocusWithRetry {
            if (loading) {
                dismissFocusRequester.requestFocusSafely()
            } else {
                primaryRequester.requestFocusSafely()
            }
        }
    }
    TvSettingsDialogFrame(
        title = stringResource(R.string.tv_settings_playback),
        onDismiss = onDismiss,
        dismissFocusRequester = dismissFocusRequester.takeIf { loading },
    ) {
        TvText(
            text =
                stringResource(
                    if (loading) {
                        R.string.tv_loading
                    } else {
                        R.string.tv_settings_playback_error
                    },
                ),
            color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
        if (loading) {
            TvSpinner(size = TvDimens.spinnerSize)
        } else {
            TvSettingsDialogAction(
                text = stringResource(R.string.tv_retry),
                focusRequester = primaryRequester,
                onClick = onRetry,
            )
        }
    }
}

@Composable
private fun TvOpenSubtitlesKeyStatusDialog(
    loading: Boolean,
    loadError: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primaryRequester = remember { FocusRequester() }
    val dismissFocusRequester = remember { FocusRequester() }
    LaunchedEffect(primaryRequester, dismissFocusRequester, loading, loadError) {
        requestTvFocusWithRetry {
            if (loadError) {
                primaryRequester.requestFocusSafely()
            } else {
                dismissFocusRequester.requestFocusSafely()
            }
        }
    }
    TvSettingsDialogFrame(
        title = stringResource(R.string.tv_settings_opensubtitles_api_key),
        onDismiss = onDismiss,
        dismissFocusRequester = dismissFocusRequester.takeUnless { loadError },
    ) {
        TvText(
            text =
                stringResource(
                    if (loadError) {
                        R.string.tv_settings_opensubtitles_key_load_error
                    } else {
                        R.string.tv_settings_opensubtitles_key_loading
                    },
                ),
            color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
        if (loading) {
            TvSpinner(size = TvDimens.spinnerSize)
        } else if (loadError) {
            TvSettingsDialogAction(
                text = stringResource(R.string.tv_retry),
                focusRequester = primaryRequester,
                onClick = onRetry,
            )
        }
    }
}

private fun TvSettingsTileId.segmentType(): MediaSegmentType =
    when (this) {
        TvSettingsTileId.Intros -> MediaSegmentType.Intro
        TvSettingsTileId.Credits -> MediaSegmentType.Outro
        TvSettingsTileId.Recaps -> MediaSegmentType.Recap
        TvSettingsTileId.Previews -> MediaSegmentType.Preview
        TvSettingsTileId.Commercials -> MediaSegmentType.Commercial
        else -> MediaSegmentType.Unknown
    }

@Composable
private fun TvBooleanDialog(
    title: String,
    description: String,
    value: Boolean,
    onSelected: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) = TvSettingsChoiceDialog(
    title = title,
    description = description,
    selected = value,
    options =
        listOf(
            TvSettingsDialogOption(true, tvSettingsToggleValue(true)),
            TvSettingsDialogOption(false, tvSettingsToggleValue(false)),
        ),
    onSelected = onSelected,
    onDismiss = onDismiss,
)

@Composable
private fun TvSegmentPolicyDialog(
    type: MediaSegmentType,
    state: SettingsUiState,
    onSelected: (MediaSegmentType, SegmentSkipPolicy) -> Unit,
    onDismiss: () -> Unit,
) = TvSettingsChoiceDialog(
    title = segmentTypeLabel(type),
    description = stringResource(R.string.tv_settings_segment_policy_description),
    selected = state.playbackPreferences.policyFor(type),
    options = SegmentSkipPolicy.entries.map { TvSettingsDialogOption(it, segmentSkipPolicyLabel(it)) },
    onSelected = { policy ->
        onSelected(type, policy)
        onDismiss()
    },
    onDismiss = onDismiss,
)

@Composable
private fun TvAudioOutputDialog(
    state: SettingsUiState,
    onSelected: (PlayerAudioMode) -> Unit,
    onDismiss: () -> Unit,
) = TvSettingsChoiceDialog(
    title = stringResource(R.string.tv_settings_audio_output),
    description = stringResource(R.string.tv_settings_audio_output_description),
    selected = state.playerDeviceSettings.audioMode,
    options =
        PlayerAudioMode.entries.map { mode ->
            TvSettingsDialogOption(
                value = mode,
                label = tvPlayerAudioModeLabel(mode),
                enabled = tvPlayerAudioModeAvailable(mode, state.playerDevicePolicy?.capabilities),
                unavailableReason =
                    stringResource(R.string.tv_settings_audio_passthrough_unsupported).takeIf {
                        mode ==
                            PlayerAudioMode.PassthroughWhenSupported
                    },
            )
        },
    onSelected = { mode ->
        onSelected(mode)
        onDismiss()
    },
    onDismiss = onDismiss,
)

@Composable
private fun TvHdrDialog(
    state: SettingsUiState,
    onSelected: (PlayerHdrMode) -> Unit,
    onDismiss: () -> Unit,
) = TvSettingsChoiceDialog(
    title = stringResource(R.string.tv_settings_hdr_output),
    description = stringResource(R.string.tv_settings_hdr_description),
    selected = state.playerDeviceSettings.hdrMode,
    options =
        PlayerHdrMode.entries.map { mode ->
            TvSettingsDialogOption(
                value = mode,
                label = tvPlayerHdrModeLabel(mode),
                enabled = tvPlayerHdrModeAvailable(mode, state.playerDevicePolicy?.capabilities),
                unavailableReason = stringResource(R.string.tv_settings_hdr_unsupported).takeIf { mode == PlayerHdrMode.Auto },
            )
        },
    onSelected = { mode ->
        onSelected(mode)
        onDismiss()
    },
    onDismiss = onDismiss,
)

@Composable
private fun TvSettingsTextDialog(
    title: String,
    label: String,
    initialValue: String,
    onApply: (String, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
    concealed: Boolean = false,
) {
    var value by rememberSaveable(title) { mutableStateOf(initialValue) }
    var editing by remember { mutableStateOf(false) }
    var exitEditingRequests by remember { mutableIntStateOf(0) }
    val fieldRequester = remember { FocusRequester() }
    val applyRequester = remember { FocusRequester() }
    val visualTransformation =
        if (concealed) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        }
    LaunchedEffect(fieldRequester) { requestTvFocusWithRetry { fieldRequester.requestFocusSafely() } }
    TvSettingsDialogFrame(
        title = title,
        onDismiss = {
            if (editing) {
                exitEditingRequests++
            } else {
                onDismiss()
            }
        },
    ) {
        TvInputField(
            value = value,
            onValueChange = { value = it },
            label = label,
            modifier = Modifier.focusRequester(fieldRequester),
            visualTransformation = visualTransformation,
            onEditingChange = { editing = it },
            exitEditingRequests = exitEditingRequests,
        )
        TvSettingsDialogAction(
            text = stringResource(R.string.tv_settings_dialog_apply),
            focusRequester = applyRequester,
            labelAlignment = TextAlign.Center,
            onClick = { onApply(value, onDismiss) },
        )
    }
}

@Composable
private fun TvLogoutRemovalDialog(
    preview: DownloadRemovalPreview,
    dismissalRequested: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val summary = remember(preview) { tvLogoutRemovalSummary(preview) }
    val confirmRequester = remember { FocusRequester() }
    val message =
        pluralStringResource(
            id = R.plurals.tv_settings_logout_download_removal_message,
            count = summary.recordCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            summary.recordCount,
            tvLogoutRemovalBytes(summary.displayedBytes),
        )
    LaunchedEffect(confirmRequester) { requestTvFocusWithRetry { confirmRequester.requestFocusSafely() } }
    TvSettingsDialogFrame(
        title = stringResource(R.string.tv_settings_logout_download_removal_title),
        onDismiss = onDismiss,
    ) {
        TvText(
            text = message,
            color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
            maxLines = 4,
        )
        if (dismissalRequested) TvSpinner(size = TvDimens.spinnerSize)
        TvSettingsDialogAction(
            text = stringResource(R.string.tv_settings_logout_download_removal_confirm),
            focusRequester = confirmRequester,
            enabled = !dismissalRequested,
            destructive = true,
            onClick = onConfirm,
        )
    }
}

@Composable
private fun tvLogoutRemovalBytes(bytes: Long): String =
    when {
        bytes >= DOWNLOAD_BYTES_PER_GB ->
            stringResource(R.string.tv_downloads_bytes_gb, bytes.toDouble() / DOWNLOAD_BYTES_PER_GB)
        bytes >= TV_LOGOUT_BYTES_PER_MB ->
            stringResource(R.string.tv_downloads_bytes_mb, bytes.toDouble() / TV_LOGOUT_BYTES_PER_MB)
        bytes >= TV_LOGOUT_BYTES_PER_KB ->
            stringResource(R.string.tv_downloads_bytes_kb, bytes.toDouble() / TV_LOGOUT_BYTES_PER_KB)
        else -> stringResource(R.string.tv_downloads_bytes_b, bytes)
    }

@Composable
private fun TvLogoutDialog(
    isLoggingOut: Boolean,
    logoutFailed: Boolean,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmRequester = remember { FocusRequester() }
    LaunchedEffect(confirmRequester) { requestTvFocusWithRetry { confirmRequester.requestFocusSafely() } }
    TvSettingsDialogFrame(title = stringResource(R.string.tv_settings_logout), onDismiss = { if (!isLoggingOut) onDismiss() }) {
        TvText(
            text = stringResource(R.string.tv_settings_logout_confirmation),
            color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
        if (isLoggingOut) {
            TvSpinner(size = TvDimens.spinnerSize)
        }
        if (logoutFailed) {
            TvText(
                text = stringResource(R.string.tv_settings_logout_error),
                color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.error,
                maxLines = 2,
            )
        }
        TvSettingsDialogAction(
            text = stringResource(R.string.tv_settings_dialog_confirm),
            focusRequester = confirmRequester,
            enabled = !isLoggingOut,
            destructive = true,
            onClick = onLogout,
        )
    }
}

private const val TV_LOGOUT_BYTES_PER_KB = 1_000L
private const val TV_LOGOUT_BYTES_PER_MB = TV_LOGOUT_BYTES_PER_KB * TV_LOGOUT_BYTES_PER_KB

@Composable
private fun TvClearSubtitlesDialog(
    state: SettingsUiState,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmRequester = remember { FocusRequester() }
    var submitted by remember { mutableStateOf(false) }
    var completionAtSubmit by remember { mutableIntStateOf(state.localSubtitlesClearCompletion) }
    LaunchedEffect(confirmRequester) { requestTvFocusWithRetry { confirmRequester.requestFocusSafely() } }
    LaunchedEffect(state.localSubtitlesClearCompletion, state.localSubtitlesClearError) {
        if (submitted && state.localSubtitlesClearCompletion != completionAtSubmit) {
            if (state.localSubtitlesClearError) {
                submitted = false
            } else {
                onDismiss()
            }
        }
    }
    TvSettingsDialogFrame(
        title = stringResource(R.string.tv_settings_local_subtitles_clear),
        onDismiss = {
            if (!submitted && !state.isClearingLocalSubtitles) onDismiss()
        },
    ) {
        TvText(
            text = stringResource(R.string.tv_settings_clear_subtitles_confirmation),
            color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
        if (state.isClearingLocalSubtitles) TvSpinner(size = TvDimens.spinnerSize)
        if (state.localSubtitlesClearError) {
            TvText(
                text = stringResource(R.string.tv_settings_clear_subtitles_error),
                color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.error,
                maxLines = 2,
            )
        }
        TvSettingsDialogAction(
            text =
                stringResource(
                    if (state.localSubtitlesClearError) R.string.tv_retry else R.string.tv_settings_dialog_confirm,
                ),
            focusRequester = confirmRequester,
            enabled = !submitted && !state.isClearingLocalSubtitles,
            destructive = true,
            onClick = {
                completionAtSubmit = state.localSubtitlesClearCompletion
                submitted = true
                onClear()
            },
        )
    }
}

@Composable
private fun TvRefreshCapabilitiesDialog(
    state: SettingsUiState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val refreshRequester = remember { FocusRequester() }
    var submitted by remember { mutableStateOf(false) }
    var completionAtSubmit by remember { mutableIntStateOf(state.playerDevicePolicyRefreshCompletion) }
    LaunchedEffect(refreshRequester) { requestTvFocusWithRetry { refreshRequester.requestFocusSafely() } }
    LaunchedEffect(state.playerDevicePolicyRefreshCompletion, state.playerDevicePolicyError) {
        if (submitted && state.playerDevicePolicyRefreshCompletion != completionAtSubmit) {
            if (state.playerDevicePolicyError) {
                submitted = false
            } else {
                onDismiss()
            }
        }
    }
    TvSettingsDialogFrame(
        title = stringResource(R.string.tv_settings_refresh_capabilities),
        onDismiss = {
            if (!submitted && !state.isRefreshingPlayerDevicePolicy) onDismiss()
        },
    ) {
        TvText(
            text =
                if (state.playerDevicePolicy ==
                    null
                ) {
                    stringResource(R.string.tv_settings_capabilities_unknown)
                } else {
                    stringResource(R.string.tv_settings_capabilities_available)
                },
            color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
        if (state.playerDevicePolicyError) {
            TvText(
                text = stringResource(R.string.tv_settings_refresh_capabilities_error),
                color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.error,
                maxLines = 2,
            )
        }
        if (state.isRefreshingPlayerDevicePolicy) TvSpinner(size = TvDimens.spinnerSize)
        TvSettingsDialogAction(
            text =
                stringResource(
                    if (state.playerDevicePolicyError) R.string.tv_retry else R.string.tv_settings_refresh_capabilities,
                ),
            focusRequester = refreshRequester,
            enabled = !submitted && !state.isRefreshingPlayerDevicePolicy,
            onClick = {
                completionAtSubmit = state.playerDevicePolicyRefreshCompletion
                submitted = true
                onRefresh()
            },
        )
    }
}

@Composable
private fun TvSwitchAccountDialog(
    state: AccountUiState,
    onSwitch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val accounts = state.accounts
    val hasOtherAccount = accounts.any { account -> !account.isActive }
    val requesters = remember(accounts.map { account -> account.accountId }) { List(accounts.size) { FocusRequester() } }
    val dismissFocusRequester = remember { FocusRequester() }
    var submittedAccountId by remember { mutableStateOf<String?>(null) }
    var observedRunning by remember { mutableStateOf(false) }
    LaunchedEffect(accounts, state.switchingAccountId, state.error) {
        val submittedId = submittedAccountId
        if (submittedId != null && state.switchingAccountId == submittedId) {
            observedRunning = true
        }
        if (submittedId != null && observedRunning && state.switchingAccountId == null) {
            if (state.error == null) {
                onDismiss()
                return@LaunchedEffect
            }
            submittedAccountId = null
            observedRunning = false
        }
        val activeIndex = accounts.indexOfFirst { account -> account.isActive }.takeIf { index -> index >= 0 }
        val primaryRequester =
            if (!hasOtherAccount) {
                dismissFocusRequester
            } else {
                activeIndex?.let(requesters::get) ?: requesters.firstOrNull() ?: dismissFocusRequester
            }
        requestTvFocusWithRetry { primaryRequester.requestFocusSafely() }
    }
    TvSettingsDialogFrame(
        title = stringResource(R.string.tv_settings_switch_account),
        onDismiss = { if (submittedAccountId == null) onDismiss() },
        dismissFocusRequester = dismissFocusRequester.takeIf { !hasOtherAccount },
    ) {
        if (!hasOtherAccount) {
            TvText(
                text = stringResource(R.string.tv_settings_add_account_before_switching),
                color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.textSecondary,
            )
        } else {
            accounts.forEachIndexed { index, account ->
                TvSettingsDialogAction(
                    text = stringResource(R.string.tv_settings_account_cd, account.userName, account.serverName),
                    focusRequester = requesters[index],
                    enabled = submittedAccountId == null && !account.isActive,
                    selected = account.isActive,
                    onClick = {
                        submittedAccountId = account.accountId
                        onSwitch(account.accountId)
                    },
                )
            }
        }
        if (submittedAccountId != null) TvSpinner(size = TvDimens.spinnerSize)
        state.error?.let { error ->
            TvText(
                text = stringResource(error.messageResource()),
                color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.error,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TvAddAccountDialog(
    session: Session,
    state: AccountUiState,
    onAdd: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var serverUrl by rememberSaveable { mutableStateOf(session.serverUrl) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var exitEditingRequests by remember { mutableIntStateOf(0) }
    val serverRequester = remember { FocusRequester() }
    val addRequester = remember { FocusRequester() }
    var submitted by remember { mutableStateOf(false) }
    var observedRunning by remember { mutableStateOf(false) }
    LaunchedEffect(serverRequester) { requestTvFocusWithRetry { serverRequester.requestFocusSafely() } }
    LaunchedEffect(state.isAddingAccount, state.error) {
        if (submitted && state.isAddingAccount) observedRunning = true
        if (submitted && observedRunning && !state.isAddingAccount) {
            if (state.error == null) {
                onDismiss()
            } else {
                submitted = false
                observedRunning = false
            }
        }
    }
    TvSettingsDialogFrame(
        title = stringResource(R.string.tv_settings_add_account),
        onDismiss = {
            when {
                submitted -> Unit
                editing -> exitEditingRequests++
                else -> onDismiss()
            }
        },
    ) {
        TvInputField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = stringResource(R.string.tv_settings_server_url_label),
            modifier = Modifier.focusRequester(serverRequester),
            onEditingChange = { editing = it },
            exitEditingRequests = exitEditingRequests,
        )
        TvInputField(
            value = username,
            onValueChange = { username = it },
            label = stringResource(R.string.tv_username_label),
            onEditingChange = { editing = it },
            exitEditingRequests = exitEditingRequests,
        )
        TvInputField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.tv_password_label),
            visualTransformation = PasswordVisualTransformation(),
            onEditingChange = { editing = it },
            exitEditingRequests = exitEditingRequests,
        )
        state.error?.let { error ->
            TvText(
                text = stringResource(error.messageResource()),
                color = com.jellyscope.ui.theme.LocalJellyfinPalette.current.error,
                maxLines = 2,
            )
        }
        if (state.isAddingAccount) TvSpinner(size = TvDimens.spinnerSize)
        TvSettingsDialogAction(
            text = stringResource(if (submitted) R.string.tv_settings_adding_account else R.string.tv_settings_add_account),
            focusRequester = addRequester,
            enabled = !submitted,
            onClick = {
                submitted = true
                onAdd(serverUrl, username, password)
            },
        )
    }
}

@Composable
private fun AccountUiError.messageResource(): Int =
    when (this) {
        AccountUiError.InvalidCredentials -> R.string.tv_error_invalid_credentials
        AccountUiError.NotReachable -> R.string.tv_error_not_reachable
        AccountUiError.AccountNotFound -> R.string.tv_settings_account_not_found
        AccountUiError.ServerError -> R.string.tv_error_server
        AccountUiError.RemovalFailed -> R.string.tv_settings_account_removal_error
    }
