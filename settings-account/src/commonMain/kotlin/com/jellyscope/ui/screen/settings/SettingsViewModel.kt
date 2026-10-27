// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.action.ClearLocalSubtitlesAction
import com.jellyscope.core.domain.action.LogoutAction
import com.jellyscope.core.domain.action.PreparePlaybackTimelineAction
import com.jellyscope.core.domain.action.SavePlaybackPreferencesAction
import com.jellyscope.core.domain.action.SavePlayerDeviceSettingsAction
import com.jellyscope.core.domain.action.SendClientLogsAction
import com.jellyscope.core.domain.action.SendPlaybackTimelineAction
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.action.SetAppThemeAction
import com.jellyscope.core.domain.action.SetDesktopMpvPresentationAction
import com.jellyscope.core.domain.action.SetImageQualityAction
import com.jellyscope.core.domain.action.SetLogCollectionEnabledAction
import com.jellyscope.core.domain.action.SetOpenSubtitleResultPreferenceAction
import com.jellyscope.core.domain.action.SetOpenSubtitlesApiKeyAction
import com.jellyscope.core.domain.action.SetPictureInPictureEnabledAction
import com.jellyscope.core.domain.action.SetPlaybackInfoAtStartEnabledAction
import com.jellyscope.core.domain.action.SetPlayerClockEnabledAction
import com.jellyscope.core.domain.action.SetRememberLastLibraryViewAction
import com.jellyscope.core.domain.action.SetTileSizeAction
import com.jellyscope.core.domain.action.SetVerboseLogcatEnabledAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.DesktopMpvPresentation
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.ImageQuality
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackTimeline
import com.jellyscope.core.domain.model.PreparePlaybackTimelineResult
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.SendPlaybackTimelineResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.AndroidTvMpvVideoOutput
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerBackendPolicy
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit
import com.jellyscope.core.domain.usecase.DownloadRemovalAuthorizationIssuer
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReader
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReleaser
import com.jellyscope.core.domain.usecase.GetAvailablePlayerBackendsUseCase
import com.jellyscope.core.domain.usecase.GetDownloadSettingsUseCase
import com.jellyscope.core.domain.usecase.GetDownloadUsageUseCase
import com.jellyscope.core.domain.usecase.GetLogCollectionStateUseCase
import com.jellyscope.core.domain.usecase.GetOpenSubtitleResultPreferenceUseCase
import com.jellyscope.core.domain.usecase.GetOpenSubtitlesApiKeyUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackInfoAtStartStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
import com.jellyscope.core.domain.usecase.GetPlayerDevicePolicyUseCase
import com.jellyscope.core.domain.usecase.GetVerboseLogcatStateUseCase
import com.jellyscope.core.domain.usecase.ObserveAccountsUseCase
import com.jellyscope.core.domain.usecase.ObserveAppThemeUseCase
import com.jellyscope.core.domain.usecase.ObserveDesktopMpvPresentationUseCase
import com.jellyscope.core.domain.usecase.ObserveImageQualityUseCase
import com.jellyscope.core.domain.usecase.ObservePictureInPictureEnabledUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerClockEnabledUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerDeviceSettingsUseCase
import com.jellyscope.core.domain.usecase.ObserveRememberLastLibraryViewUseCase
import com.jellyscope.core.domain.usecase.ObserveTileSizeUseCase
import com.jellyscope.core.domain.usecase.RefreshPlayerDevicePolicyUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogBufferSize
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class SettingsViewModel(
    private val session: Session,
    versionName: String,
    sourceRevision: String,
    private val playerBackendPolicy: PlayerBackendPolicy,
    private val logoutAction: LogoutAction,
    private val observeAccountsUseCase: ObserveAccountsUseCase? = null,
    private val getDownloadRemovalPreviewUseCase: DownloadRemovalPreviewReader? = null,
    private val issueDownloadRemovalAuthorizationUseCase: DownloadRemovalAuthorizationIssuer? = null,
    private val releaseDownloadRemovalPreviewUseCase: DownloadRemovalPreviewReleaser? = null,
    private val getDownloadUsageUseCase: GetDownloadUsageUseCase? = null,
    private val getDownloadSettingsUseCase: GetDownloadSettingsUseCase? = null,
    observeAppThemeUseCase: ObserveAppThemeUseCase,
    private val setAppThemeAction: SetAppThemeAction,
    observePictureInPictureEnabledUseCase: ObservePictureInPictureEnabledUseCase,
    private val setPictureInPictureEnabledAction: SetPictureInPictureEnabledAction,
    observePlayerClockEnabledUseCase: ObservePlayerClockEnabledUseCase,
    private val setPlayerClockEnabledAction: SetPlayerClockEnabledAction,
    observeDesktopMpvPresentationUseCase: ObserveDesktopMpvPresentationUseCase? = null,
    private val setDesktopMpvPresentationAction: SetDesktopMpvPresentationAction? = null,
    observeRememberLastLibraryViewUseCase: ObserveRememberLastLibraryViewUseCase,
    private val setRememberLastLibraryViewAction: SetRememberLastLibraryViewAction,
    observeTileSizeUseCase: ObserveTileSizeUseCase,
    observeImageQualityUseCase: ObserveImageQualityUseCase,
    private val setImageQualityAction: SetImageQualityAction,
    private val setTileSizeAction: SetTileSizeAction,
    observePlayerDeviceSettingsUseCase: ObservePlayerDeviceSettingsUseCase,
    private val savePlayerDeviceSettingsAction: SavePlayerDeviceSettingsAction,
    private val getPlayerDevicePolicyUseCase: GetPlayerDevicePolicyUseCase,
    private val refreshPlayerDevicePolicyUseCase: RefreshPlayerDevicePolicyUseCase,
    private val getPlaybackPreferencesUseCase: GetPlaybackPreferencesUseCase,
    private val savePlaybackPreferencesAction: SavePlaybackPreferencesAction,
    private val getAvailablePlayerBackendsUseCase: GetAvailablePlayerBackendsUseCase,
    private val getOpenSubtitlesApiKeyUseCase: GetOpenSubtitlesApiKeyUseCase? = null,
    private val setOpenSubtitlesApiKeyAction: SetOpenSubtitlesApiKeyAction? = null,
    private val getOpenSubtitleResultPreferenceUseCase: GetOpenSubtitleResultPreferenceUseCase? = null,
    private val setOpenSubtitleResultPreferenceAction: SetOpenSubtitleResultPreferenceAction? = null,
    private val clearLocalSubtitlesAction: ClearLocalSubtitlesAction? = null,
    getLogCollectionStateUseCase: GetLogCollectionStateUseCase? = null,
    private val setLogCollectionEnabledAction: SetLogCollectionEnabledAction? = null,
    getVerboseLogcatStateUseCase: GetVerboseLogcatStateUseCase? = null,
    private val setVerboseLogcatEnabledAction: SetVerboseLogcatEnabledAction? = null,
    getPlaybackInfoAtStartStateUseCase: GetPlaybackInfoAtStartStateUseCase? = null,
    private val setPlaybackInfoAtStartEnabledAction: SetPlaybackInfoAtStartEnabledAction? = null,
    private val sendClientLogsAction: SendClientLogsAction? = null,
    private val preparePlaybackTimelineAction: PreparePlaybackTimelineAction? = null,
    private val sendPlaybackTimelineAction: SendPlaybackTimelineAction? = null,
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel(),
    PlaybackTimelineSaveHost {
    private val appTheme = observeAppThemeUseCase()
    private val tileSize = observeTileSizeUseCase()
    private val imageQuality = observeImageQualityUseCase()
    private val pictureInPictureEnabled = observePictureInPictureEnabledUseCase()
    private val playerClockEnabled = observePlayerClockEnabledUseCase()
    private val desktopMpvPresentation = observeDesktopMpvPresentationUseCase?.invoke()
    private val rememberLastLibraryView = observeRememberLastLibraryViewUseCase()
    private val playerDeviceSettings = observePlayerDeviceSettingsUseCase()
    private val logCollectionState = getLogCollectionStateUseCase?.invoke()
    private val verboseLogcatState = getVerboseLogcatStateUseCase?.invoke()
    private val playbackInfoAtStartState = getPlaybackInfoAtStartStateUseCase?.invoke()
    private val logBufferSize = getLogCollectionStateUseCase?.bufferSize()
    private val optimisticPlayerBackendChoices = playerBackendChoices(playerBackendPolicy)
    private val _state =
        MutableStateFlow(
            SettingsUiState(
                serverName = session.serverName,
                serverUrl = session.serverUrl,
                userName = session.userName,
                versionName = versionName,
                sourceRevision = sourceRevision,
                playerBackendChoices = optimisticPlayerBackendChoices,
                selectedPlayerBackend =
                    playerBackendPolicy.effectivePlayerBackendSelection(
                        persistedBackend = PlaybackPreferences().defaultPlayerBackend,
                        choices = optimisticPlayerBackendChoices,
                    ),
                appTheme = appTheme.value,
                tileSize = tileSize.value,
                imageQuality = imageQuality.value,
                pictureInPictureEnabled = pictureInPictureEnabled.value,
                playerClockEnabled = playerClockEnabled.value,
                desktopMpvPresentation = desktopMpvPresentation?.value,
                rememberLastLibraryView = rememberLastLibraryView.value,
                playerDeviceSettings = playerDeviceSettings.value,
                playerDevicePolicy = null,
                isLoadingPlaybackPreferences = true,
                collectLogs = logCollectionState?.value ?: false,
                verboseLogcatEnabled = verboseLogcatState?.value ?: false,
                playbackInfoAtStartEnabled = playbackInfoAtStartState?.value ?: false,
                logBufferSize = logBufferSize?.value ?: LogBufferSize(),
                playbackTimelineAvailable = preparePlaybackTimelineAction != null,
            ),
        )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private val _events = Channel<SettingsEvent>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var logoutRemovalScope: SessionRemovalScope.FullLogout? = null
    private var openSubtitlesApiKeyLoadGeneration = 0L
    private var downloadUsageGeneration = 0L

    // One timeline operation at a time. The payload is in memory only and dropped on every end.
    private var activePlaybackTimelineOperation: Long? = null
    private var activePlaybackTimelineDelivery = PlaybackTimelineDelivery.Save
    private var pendingPlaybackTimeline: PlaybackTimeline? = null
    private var playbackTimelineJob: Job? = null
    private val policyRefreshRequests = Channel<Unit>(capacity = Channel.BUFFERED)
    private val appThemeWriter =
        LatestValueWriter(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = setAppThemeAction::invoke,
            operation = DiagnosticOperation.SetAppTheme,
        )
    private val pictureInPictureWriter =
        LatestValueWriter(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = setPictureInPictureEnabledAction::invoke,
            operation = DiagnosticOperation.SetPictureInPictureEnabled,
        )
    private val playerClockWriter =
        LatestValueWriter(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = setPlayerClockEnabledAction::invoke,
            operation = DiagnosticOperation.SetPlayerClockEnabled,
        )
    private val tileSizeWriter =
        LatestValueWriter(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = setTileSizeAction::invoke,
            operation = DiagnosticOperation.SetTileSize,
        )
    private val imageQualityWriter =
        LatestValueWriter(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = setImageQualityAction::invoke,
            operation = DiagnosticOperation.SetImageQuality,
        )
    private val rememberLastLibraryViewWriter =
        LatestValueWriter(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = setRememberLastLibraryViewAction::invoke,
            operation = DiagnosticOperation.SetRememberLastLibraryView,
        )
    private val playbackPreferencesWriter =
        LatestValueWriter<PlaybackPreferences>(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = { preferences ->
                savePlaybackPreferencesAction(
                    accountIdentity = session.accountIdentity(),
                    preferences = preferences,
                )
            },
            operation = DiagnosticOperation.SavePlaybackPreferences,
            onFailure = {
                reduce(SettingsChange.PlaybackWriteFailed)
            },
        )
    private val playerDeviceSettingsWriter =
        LatestValueWriter<PlayerDeviceSettings>(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = savePlayerDeviceSettingsAction::invoke,
            operation = DiagnosticOperation.SavePlayerDeviceSettings,
            onFailure = {
                reduce(SettingsChange.DeviceWriteFailed)
            },
        )

    private fun reduce(change: SettingsChange) {
        _state.update { current ->
            reduceSettings(current, change, downloadUsageGeneration, openSubtitlesApiKeyLoadGeneration, playerBackendPolicy)
        }
    }

    fun accept(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.RefreshDownloadUsage -> refreshDownloadUsage()
            is SettingsIntent.Logout -> logout(intent.onComplete)
            is SettingsIntent.ConfirmLogoutRemoval -> confirmLogoutRemoval(intent.onComplete)
            SettingsIntent.DismissLogoutRemoval -> dismissLogoutRemoval()
            SettingsIntent.ReloadPlaybackPreferences -> reloadPlaybackPreferences()
            is SettingsIntent.SetDefaultMaxBitrateBps -> setDefaultMaxBitrateBps(intent.defaultMaxBitrateBps)
            is SettingsIntent.SetDefaultQualityPolicy -> setDefaultQualityPolicy(intent.policy)
            is SettingsIntent.SetVlcTranscodeMaxBitrateBps -> setVlcTranscodeMaxBitrateBps(intent.maxBitrateBps)
            is SettingsIntent.SetDefaultPlayerBackend -> setDefaultPlayerBackend(intent.backend)
            is SettingsIntent.SetPreferredAudioLanguage -> setPreferredAudioLanguage(intent.language)
            is SettingsIntent.SetPreferredSubtitleLanguage -> setPreferredSubtitleLanguage(intent.language)
            is SettingsIntent.SetAutoPlayNext -> setAutoPlayNext(intent.enabled)
            is SettingsIntent.SetAutoPlayNextDelaySeconds -> setAutoPlayNextDelaySeconds(intent.delaySeconds)
            is SettingsIntent.SetStillWatchingPrompt -> setStillWatchingPrompt(intent.enabled)
            is SettingsIntent.SetPlaybackWarningsEnabled -> setPlaybackWarningsEnabled(intent.enabled)
            is SettingsIntent.SetAllowInsecureDesktopTls -> setAllowInsecureDesktopTls(intent.enabled)
            is SettingsIntent.SetSegmentSkipPolicy -> setSegmentSkipPolicy(intent.type, intent.policy)
            is SettingsIntent.SetAndroidTvMpvVideoOutput -> setAndroidTvMpvVideoOutput(intent.output)
            is SettingsIntent.SetPlayerAudioMode -> setPlayerAudioMode(intent.audioMode)
            is SettingsIntent.SetPlayerHdrMode -> setPlayerHdrMode(intent.hdrMode)
            is SettingsIntent.SetMaxVideoResolution -> setMaxVideoResolution(intent.limit)
            is SettingsIntent.SetIosPlaybackCompatibilityMode -> setIosPlaybackCompatibilityMode(intent.mode)
            is SettingsIntent.SetMatchDisplayRefreshRate -> setMatchDisplayRefreshRate(intent.enabled)
            SettingsIntent.RefreshPlayerDevicePolicy -> refreshPlayerDevicePolicy()
            is SettingsIntent.SetAppTheme -> setAppTheme(intent.theme)
            is SettingsIntent.SetTileSize -> setTileSize(intent.tileSize)
            is SettingsIntent.SetImageQuality -> setImageQuality(intent.imageQuality)
            is SettingsIntent.SetPictureInPictureEnabled -> setPictureInPictureEnabled(intent.enabled)
            is SettingsIntent.SetPlayerClockEnabled -> setPlayerClockEnabled(intent.enabled)
            is SettingsIntent.SetRememberLastLibraryView -> setRememberLastLibraryView(intent.enabled)
            is SettingsIntent.SetDesktopMpvPresentation -> setDesktopMpvPresentation(intent.presentation)
            is SettingsIntent.SetOpenSubtitlesApiKey -> setOpenSubtitlesApiKey(intent.value, intent.onSuccess)
            SettingsIntent.RetryOpenSubtitlesApiKeyLoad -> retryOpenSubtitlesApiKeyLoad()
            is SettingsIntent.SetOpenSubtitleResultPreference -> setOpenSubtitleResultPreference(intent.preference, intent.onSuccess)
            SettingsIntent.RetryOpenSubtitleResultPreferenceLoad -> retryOpenSubtitleResultPreferenceLoad()
            SettingsIntent.ClearLocalSubtitles -> clearLocalSubtitles()
            is SettingsIntent.SetLogCollectionEnabled -> setLogCollectionEnabled(intent.enabled)
            is SettingsIntent.SetVerboseLogcatEnabled -> setVerboseLogcatEnabled(intent.enabled)
            is SettingsIntent.SetPlaybackInfoAtStartEnabled -> setPlaybackInfoAtStartEnabled(intent.enabled)
            SettingsIntent.SendClientLogs -> sendClientLogs()
            SettingsIntent.SavePlaybackTimeline -> savePlaybackTimeline()
            SettingsIntent.SendPlaybackTimeline -> sendPlaybackTimeline()
        }
    }

    init {
        observeAppTheme()
        observeTileSize()
        observeImageQuality()
        observePictureInPicture()
        observePlayerClock()
        observeDesktopMpvPresentation()
        observeRememberLastLibraryView()
        observePlayerDeviceSettings()
        observeLogCollection()
        observeVerboseLogcat()
        observePlaybackInfoAtStart()
        loadPlaybackPreferences()
        loadAvailablePlayerBackends()
        loadOpenSubtitlesApiKey()
        loadOpenSubtitleResultPreference()
        loadDownloadUsage()
    }

    private fun refreshDownloadUsage() {
        loadDownloadUsage()
    }

    private fun loadDownloadUsage() {
        val getUsage = getDownloadUsageUseCase ?: return
        val getSettings = getDownloadSettingsUseCase ?: return
        val requestGeneration = ++downloadUsageGeneration
        viewModelScope.launch {
            reduce(SettingsChange.UsageStarted)
            val usage =
                try {
                    withContext(workDispatcher) { getUsage(session.accountIdentity()) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    logSettingsFailure("download-usage", DiagnosticOperation.GetDownloadUsage, exception)
                    reduce(SettingsChange.UsageFailed(requestGeneration))
                    return@launch
                }
            val settings =
                try {
                    withContext(workDispatcher) { getSettings() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    logSettingsFailure("download-settings", DiagnosticOperation.GetDownloadSettings, exception)
                    reduce(SettingsChange.UsageFailed(requestGeneration))
                    return@launch
                }
            reduce(SettingsChange.UsageLoaded(requestGeneration, usage, settings))
        }
    }

    private fun logout(onComplete: () -> Unit = {}) {
        if (_state.value.isLoggingOut || _state.value.logoutRemovalPreview != null) {
            return
        }

        viewModelScope.launch {
            val previewUseCase = getDownloadRemovalPreviewUseCase
            val issueAuthorization = issueDownloadRemovalAuthorizationUseCase
            if (previewUseCase == null || issueAuthorization == null) {
                performLogout(SessionRemovalAuthorization.None, onComplete, scope = null)
                return@launch
            }
            reduce(SettingsChange.LogoutPreviewStarted)
            val scope = fullLogoutScope()
            logoutRemovalScope = scope
            val preview = loadLogoutRemovalPreview(scope)
            if (preview == null) {
                reduce(SettingsChange.LogoutPreviewFailed)
                _events.send(SettingsEvent.LogoutFailed)
            } else if (preview.confirmations.any { confirmation -> confirmation.recordCount > 0L }) {
                reduce(SettingsChange.LogoutPreviewLoaded(preview))
            } else {
                val authorization =
                    try {
                        withContext(workDispatcher) { issueAuthorization(scope, preview) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (stale: SessionRemovalError.ConfirmationStale) {
                        showFreshLogoutRemovalPreview(scope, onComplete)
                        return@launch
                    } catch (exception: Throwable) {
                        logSettingsFailure("removal-authorization", DiagnosticOperation.AuthorizeDownloadRemoval, exception)
                        null
                    }
                if (authorization == null) {
                    reduce(SettingsChange.LogoutStopped)
                    _events.send(SettingsEvent.LogoutFailed)
                } else {
                    performLogout(authorization, onComplete, scope)
                }
            }
        }
    }

    private fun confirmLogoutRemoval(onComplete: () -> Unit = {}) {
        val issueAuthorization = issueDownloadRemovalAuthorizationUseCase ?: return
        val preview = _state.value.logoutRemovalPreview ?: return
        val scope = logoutRemovalScope ?: return
        if (_state.value.isLoggingOut) return
        viewModelScope.launch {
            reduce(SettingsChange.LogoutConfirmed)
            val authorization =
                try {
                    withContext(workDispatcher) { issueAuthorization(scope, preview) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (stale: SessionRemovalError.ConfirmationStale) {
                    showFreshLogoutRemovalPreview(scope, onComplete)
                    return@launch
                } catch (exception: Throwable) {
                    logSettingsFailure("removal-authorization", DiagnosticOperation.AuthorizeDownloadRemoval, exception)
                    null
                }
            if (authorization == null) {
                reduce(SettingsChange.LogoutStopped)
                _events.send(SettingsEvent.LogoutFailed)
            } else {
                performLogout(authorization, onComplete, scope)
            }
        }
    }

    private fun dismissLogoutRemoval() {
        val preview = _state.value.logoutRemovalPreview
        val scope = logoutRemovalScope
        val release = releaseDownloadRemovalPreviewUseCase
        if (preview == null || scope == null || release == null) {
            logoutRemovalScope = null
            reduce(SettingsChange.LogoutDismissed)
            return
        }

        // Capture the only scope and preview handles before cancellation can run.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable + workDispatcher) { release(scope, preview) }
            } catch (_: CancellationException) {
                // Guard removal completed; only the platform wake was cancelled.
            } catch (exception: Throwable) {
                logSettingsFailure(
                    "removal-preview-release",
                    DiagnosticOperation.ReleaseDownloadRemovalPreview,
                    exception,
                )
                // A later lifecycle wake can resume the durable queue.
            } finally {
                logoutRemovalScope = null
                reduce(SettingsChange.LogoutDismissed)
            }
        }
    }

    private suspend fun performLogout(
        authorization: SessionRemovalAuthorization,
        onComplete: () -> Unit,
        scope: SessionRemovalScope.FullLogout?,
    ) {
        reduce(SettingsChange.LogoutStarted)
        val result =
            try {
                withContext(workDispatcher) { logoutAction(authorization) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }
        if (result.isSuccess) {
            logoutRemovalScope = null
            onComplete()
        } else if (result.exceptionOrNull() is SessionRemovalError.ConfirmationStale && scope != null) {
            showFreshLogoutRemovalPreview(scope, onComplete)
        } else {
            result.exceptionOrNull()?.let { failure ->
                logSettingsFailure("logout", DiagnosticOperation.Logout, failure)
            }
            reduce(SettingsChange.LogoutStopped)
            _events.send(SettingsEvent.LogoutFailed)
        }
    }

    private suspend fun loadLogoutRemovalPreview(scope: SessionRemovalScope.FullLogout): DownloadRemovalPreview? {
        val previewUseCase = getDownloadRemovalPreviewUseCase ?: return null
        return try {
            withContext(workDispatcher) { previewUseCase(scope) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Throwable) {
            logSettingsFailure("removal-preview", DiagnosticOperation.GetDownloadRemovalPreview, exception)
            null
        }
    }

    private suspend fun showFreshLogoutRemovalPreview(
        scope: SessionRemovalScope.FullLogout,
        onComplete: () -> Unit,
    ) {
        val preview = loadLogoutRemovalPreview(scope)
        if (preview == null) {
            reduce(SettingsChange.LogoutPreviewFailed)
            _events.send(SettingsEvent.LogoutFailed)
            return
        }
        if (preview.confirmations.none { confirmation -> confirmation.recordCount > 0L }) {
            val issuer = issueDownloadRemovalAuthorizationUseCase
            val authorization =
                try {
                    issuer?.let { candidate -> withContext(workDispatcher) { candidate(scope, preview) } }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    logSettingsFailure("removal-authorization", DiagnosticOperation.AuthorizeDownloadRemoval, exception)
                    null
                }
            if (authorization != null) {
                performLogout(authorization, onComplete, scope)
                return
            }
        }
        reduce(SettingsChange.LogoutPreviewLoaded(preview))
    }

    private fun fullLogoutScope(): SessionRemovalScope.FullLogout {
        val identities =
            observeAccountsUseCase
                ?.invoke()
                ?.value
                ?.map { account -> AccountIdentity(account.serverId, account.userId) }
                ?.distinct()
                .orEmpty()
        return SessionRemovalScope.FullLogout(
            accountIdentities = identities.ifEmpty { listOf(AccountIdentity(session.serverId, session.userId)) },
        )
    }

    private fun reloadPlaybackPreferences() {
        loadPlaybackPreferences()
    }

    private fun setDefaultMaxBitrateBps(defaultMaxBitrateBps: Long?) {
        val policy = defaultMaxBitrateBps?.let { bitrate -> PlaybackQualityPolicy.fixed(bitrate) } ?: PlaybackQualityPolicy.Auto
        savePlaybackPreferences { current ->
            current.copy(
                defaultQualityPolicy = policy,
                defaultMaxBitrateBps = policy.maxBitrateBps,
            )
        }
    }

    private fun setDefaultQualityPolicy(policy: PlaybackQualityPolicy) {
        val normalized = policy.normalized()
        savePlaybackPreferences { current ->
            current.copy(
                defaultQualityPolicy = normalized,
                defaultMaxBitrateBps = normalized.maxBitrateBps,
            )
        }
    }

    private fun setVlcTranscodeMaxBitrateBps(maxBitrateBps: Long?) {
        savePlaybackPreferences { current ->
            current.copy(vlcTranscodeMaxBitrateBps = maxBitrateBps).normalized()
        }
    }

    private fun setDefaultPlayerBackend(backend: PlayerBackend) {
        savePlaybackPreferences { current -> current.copy(defaultPlayerBackend = backend) }
    }

    private fun setPreferredAudioLanguage(language: String?) {
        savePlaybackPreferences { current ->
            current.copy(preferredAudioLanguage = language.normalizedPreferenceText())
        }
    }

    private fun setPreferredSubtitleLanguage(language: String?) {
        savePlaybackPreferences { current ->
            current.copy(preferredSubtitleLanguage = language.normalizedPreferenceText())
        }
    }

    private fun setAutoPlayNext(enabled: Boolean) {
        savePlaybackPreferences { current -> current.copy(autoPlayNext = enabled) }
    }

    private fun setAutoPlayNextDelaySeconds(delaySeconds: Int) {
        savePlaybackPreferences { current ->
            current.copy(autoPlayNextDelaySeconds = delaySeconds).normalized()
        }
    }

    private fun setStillWatchingPrompt(enabled: Boolean) {
        savePlaybackPreferences { current -> current.copy(stillWatchingPrompt = enabled) }
    }

    private fun setPlaybackWarningsEnabled(enabled: Boolean) {
        savePlaybackPreferences { current -> current.copy(playbackWarningsEnabled = enabled) }
    }

    private fun setAllowInsecureDesktopTls(enabled: Boolean) {
        savePlaybackPreferences { current -> current.copy(allowInsecureDesktopTls = enabled) }
    }

    private fun setSegmentSkipPolicy(
        type: MediaSegmentType,
        policy: SegmentSkipPolicy,
    ) {
        savePlaybackPreferences { current ->
            when (type) {
                MediaSegmentType.Intro -> current.copy(introSkip = policy)
                MediaSegmentType.Outro -> current.copy(outroSkip = policy)
                MediaSegmentType.Recap -> current.copy(recapSkip = policy)
                MediaSegmentType.Preview -> current.copy(previewSkip = policy)
                MediaSegmentType.Commercial -> current.copy(commercialSkip = policy)
                // Unknown has no setting.
                MediaSegmentType.Unknown -> current
            }
        }
    }

    private fun setAndroidTvMpvVideoOutput(output: AndroidTvMpvVideoOutput) {
        savePlayerDeviceSettings { current -> current.copy(androidTvMpvVideoOutput = output) }
    }

    private fun setPlayerAudioMode(audioMode: PlayerAudioMode) {
        savePlayerDeviceSettings { current -> current.copy(audioMode = audioMode) }
    }

    private fun setPlayerHdrMode(hdrMode: PlayerHdrMode) {
        savePlayerDeviceSettings { current -> current.copy(hdrMode = hdrMode) }
    }

    private fun setMaxVideoResolution(limit: PlayerVideoResolutionLimit) {
        savePlayerDeviceSettings { current -> current.copy(maxVideoResolution = limit) }
    }

    private fun setIosPlaybackCompatibilityMode(mode: IosPlaybackCompatibilityMode) {
        savePlayerDeviceSettings { current -> current.copy(iosPlaybackCompatibilityMode = mode) }
    }

    private fun setMatchDisplayRefreshRate(enabled: Boolean) {
        savePlayerDeviceSettings { current -> current.copy(matchDisplayRefreshRate = enabled) }
    }

    private fun refreshPlayerDevicePolicy() {
        if (_state.value.isRefreshingPlayerDevicePolicy) return
        reduce(SettingsChange.DeviceRefreshStarted)
        policyRefreshRequests.trySend(Unit)
    }

    private fun setAppTheme(theme: AppColorThemeId) {
        appThemeWriter.submit(theme)
    }

    private fun setTileSize(tileSize: TileSizeId) {
        tileSizeWriter.submit(tileSize)
    }

    private fun setImageQuality(imageQuality: ImageQuality) {
        imageQualityWriter.submit(imageQuality)
    }

    private fun setPictureInPictureEnabled(enabled: Boolean) {
        pictureInPictureWriter.submit(enabled)
    }

    private fun setPlayerClockEnabled(enabled: Boolean) {
        playerClockWriter.submit(enabled)
    }

    private fun setRememberLastLibraryView(enabled: Boolean) {
        rememberLastLibraryViewWriter.submit(enabled)
    }

    /**
     * The store publishes the selection only after a durable write; nothing is shown optimistically.
     * Its pending save outlives this screen and admits no other selection, so a reopened Settings
     * stays disabled until that save settles.
     */
    private fun setDesktopMpvPresentation(presentation: DesktopMpvPresentation) {
        val select = setDesktopMpvPresentationAction ?: return
        // The store's current value, not this screen's collected copy, so a pending save is never missed.
        val presentationState = desktopMpvPresentation?.value ?: return
        if (presentationState.isSaving || !presentationState.isSelectorEnabled) return
        reduce(SettingsChange.DesktopSaveStarted)
        if (presentationState.selected == presentation) return
        viewModelScope.launch {
            try {
                withContext(workDispatcher) { select(presentation) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure("desktop-mpv-presentation-save", DiagnosticOperation.SetDesktopMpvPresentation, exception)
                reduce(SettingsChange.DesktopSaveFailed)
            }
        }
    }

    private fun setOpenSubtitlesApiKey(
        value: String,
        onSuccess: () -> Unit = {},
    ) {
        val setKey = setOpenSubtitlesApiKeyAction ?: return
        val current = _state.value
        if (!current.isOpenSubtitlesApiKeyLoaded || current.isSavingOpenSubtitlesApiKey) return
        reduce(SettingsChange.ApiKeySaveStarted)
        viewModelScope.launch {
            try {
                withContext(workDispatcher) { setKey(value) }
                reduce(SettingsChange.ApiKeySaved(value))
                onSuccess()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure("open-subtitles-api-key-save", DiagnosticOperation.SetOpenSubtitlesApiKey, exception)
                reduce(SettingsChange.ApiKeySaveFailed)
            }
        }
    }

    private fun retryOpenSubtitlesApiKeyLoad() {
        loadOpenSubtitlesApiKey()
    }

    private fun setOpenSubtitleResultPreference(
        preference: OpenSubtitleResultPreference,
        onSuccess: () -> Unit = {},
    ) {
        val setPreference = setOpenSubtitleResultPreferenceAction ?: return
        val current = _state.value
        if (!current.isOpenSubtitleResultPreferenceLoaded || current.isSavingOpenSubtitleResultPreference) return
        if (current.openSubtitleResultPreference == preference) {
            onSuccess()
            return
        }
        reduce(SettingsChange.SubtitlePreferenceSaveStarted)
        viewModelScope.launch {
            try {
                withContext(workDispatcher) { setPreference(preference) }
                reduce(SettingsChange.SubtitlePreferenceSaved(preference))
                onSuccess()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure(
                    "subtitle-result-preference-save",
                    DiagnosticOperation.SetOpenSubtitleResultPreference,
                    exception,
                )
                reduce(SettingsChange.SubtitlePreferenceSaveFailed)
            } finally {
                reduce(SettingsChange.SubtitlePreferenceSaveFinished)
            }
        }
    }

    private fun retryOpenSubtitleResultPreferenceLoad() {
        loadOpenSubtitleResultPreference()
    }

    private fun clearLocalSubtitles() {
        val clear = clearLocalSubtitlesAction ?: return
        if (_state.value.isClearingLocalSubtitles) return
        reduce(SettingsChange.SubtitleClearStarted)
        viewModelScope.launch {
            try {
                withContext(workDispatcher) { clear() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure("local-subtitle-clear", DiagnosticOperation.ClearLocalSubtitles, exception)
                reduce(SettingsChange.SubtitleClearFailed)
            } finally {
                reduce(SettingsChange.SubtitleClearFinished)
            }
        }
    }

    private fun setLogCollectionEnabled(enabled: Boolean) {
        val setEnabled = setLogCollectionEnabledAction ?: return
        viewModelScope.launch {
            try {
                withContext(workDispatcher) {
                    setEnabled(enabled)
                }
                reduce(SettingsChange.LogCollectionSaved)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Throwable) {
                logSettingsFailure("log-collection", DiagnosticOperation.SetLogCollectionEnabled, exception)
                reduce(SettingsChange.LogCollectionFailed)
            }
        }
    }

    private fun setVerboseLogcatEnabled(enabled: Boolean) {
        val setEnabled = setVerboseLogcatEnabledAction ?: return
        viewModelScope.launch {
            try {
                withContext(workDispatcher) {
                    setEnabled(enabled)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Throwable) {
                logSettingsFailure("verbose-logcat", DiagnosticOperation.SetVerboseLogcatEnabled, exception)
            }
        }
    }

    private fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        val setEnabled = setPlaybackInfoAtStartEnabledAction ?: return
        viewModelScope.launch {
            try {
                withContext(workDispatcher) {
                    setEnabled(enabled)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Throwable) {
                logSettingsFailure("playback-info-at-start", DiagnosticOperation.SetPlaybackInfoAtStartEnabled, exception)
            }
        }
    }

    private fun sendClientLogs() {
        val sendLogs = sendClientLogsAction ?: return
        val currentState = _state.value
        if (currentState.isSendingClientLogs) return
        val preferredBackend = currentState.playbackPreferences.defaultPlayerBackend
        reduce(SettingsChange.ClientLogsStarted)
        viewModelScope.launch {
            try {
                withContext(workDispatcher) { sendLogs(preferredBackend) }
                    ?.let { result -> _events.send(SettingsEvent.ClientLogsSent(result)) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure("client-log-send", DiagnosticOperation.SendClientLogs, exception)
                _events.send(SettingsEvent.ClientLogsSent(SendClientLogsResult.Failure))
            } finally {
                reduce(SettingsChange.ClientLogsFinished)
            }
        }
    }

    /** Prepares a timeline, then asks the screen's native exporter for a destination. */
    private fun savePlaybackTimeline() {
        startPlaybackTimelineOperation(send = null)
    }

    /** Prepares a timeline and explicitly uploads it; there is no save-to-upload fallback. */
    private fun sendPlaybackTimeline() {
        val send = sendPlaybackTimelineAction ?: return
        startPlaybackTimelineOperation(send = send)
    }

    override fun deliverablePlaybackTimeline(operationId: Long): PlaybackTimeline? {
        val prepare = preparePlaybackTimelineAction ?: return null
        if (operationId != activePlaybackTimelineOperation) return null
        return pendingPlaybackTimeline?.takeIf(prepare::isDeliverable)
    }

    override fun onPlaybackTimelineSaveResult(
        operationId: Long,
        result: PlaybackTimelineSaveResult,
    ) {
        val feedback = result.playbackTimelineFeedback()
        if (operationId == activePlaybackTimelineOperation) {
            finishPlaybackTimelineOperation(feedback)
            return
        }
        // Never ends or overwrites a newer operation. With none current (screen disposal, process
        // restoration), a platform fact is still reported: bytes written, a write that failed
        // partway, or an empty destination the provider created.
        logPlaybackTimelineOutcome(event = "save-stale-result", result = feedback.diagnosticResult())
        if (activePlaybackTimelineOperation == null && result in reportedStaleSaveResults) {
            emitPlaybackTimelineFeedback(PlaybackTimelineDelivery.Save, feedback)
        }
    }

    /**
     * Screen disposal cancels and ends the current operation quietly; a result its exporter reports
     * later is stale. This includes Sending: an upload still queued for the work dispatcher never
     * starts from a disposed screen. Cancelling a request the repository already issued is only
     * best effort; bytes that reached the server cannot be recalled, and no outcome is reported.
     */
    override fun releasePlaybackTimelineOperation() {
        if (activePlaybackTimelineOperation == null) return
        playbackTimelineJob?.cancel()
        logPlaybackTimelineOutcome(event = activePlaybackTimelineDelivery.finishedEvent(), result = "released")
        endPlaybackTimelineOperation()
    }

    private fun startPlaybackTimelineOperation(send: SendPlaybackTimelineAction?) {
        val prepare = preparePlaybackTimelineAction ?: return
        if (activePlaybackTimelineOperation != null) return
        val operationId = newPlaybackTimelineOperationId()
        activePlaybackTimelineOperation = operationId
        activePlaybackTimelineDelivery = if (send == null) PlaybackTimelineDelivery.Save else PlaybackTimelineDelivery.Send
        reduce(SettingsChange.TimelinePreparing)
        playbackTimelineJob =
            viewModelScope.launch {
                val prepared =
                    try {
                        withContext(workDispatcher) { prepare() }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        logPlaybackTimelineFailure("prepare-failed", exception)
                        PreparePlaybackTimelineResult.Failure
                    }
                if (operationId != activePlaybackTimelineOperation) return@launch
                val timeline =
                    when (prepared) {
                        is PreparePlaybackTimelineResult.Ready -> prepared.timeline
                        PreparePlaybackTimelineResult.CollectionDisabled ->
                            return@launch finishPlaybackTimelineOperation(PlaybackTimelineFeedback.CollectionDisabled)
                        PreparePlaybackTimelineResult.NoTimedEvents ->
                            return@launch finishPlaybackTimelineOperation(PlaybackTimelineFeedback.NoTimedEvents)
                        PreparePlaybackTimelineResult.Failure ->
                            return@launch finishPlaybackTimelineOperation(PlaybackTimelineFeedback.PrepareFailed)
                    }
                if (send == null) {
                    pendingPlaybackTimeline = timeline
                    reduce(SettingsChange.TimelineSaving)
                    _events.send(SettingsEvent.SavePlaybackTimeline(operationId))
                    return@launch
                }
                reduce(SettingsChange.TimelineSending)
                // The action makes the final collection check immediately before the repository call.
                val sent =
                    try {
                        withContext(workDispatcher) { send(timeline) }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        logPlaybackTimelineFailure("send-failed", exception)
                        SendPlaybackTimelineResult.Failure
                    }
                if (operationId != activePlaybackTimelineOperation) return@launch
                finishPlaybackTimelineOperation(
                    when (sent) {
                        is SendPlaybackTimelineResult.Success -> PlaybackTimelineFeedback.Sent(sent.filename)
                        SendPlaybackTimelineResult.CollectionDisabled -> PlaybackTimelineFeedback.CollectionDisabled
                        SendPlaybackTimelineResult.Expired -> PlaybackTimelineFeedback.Expired
                        SendPlaybackTimelineResult.UploadDisallowed -> PlaybackTimelineFeedback.UploadDisallowed
                        SendPlaybackTimelineResult.Failure -> PlaybackTimelineFeedback.SendFailed
                    },
                )
            }
    }

    private fun finishPlaybackTimelineOperation(feedback: PlaybackTimelineFeedback) {
        val delivery = activePlaybackTimelineDelivery
        logPlaybackTimelineOutcome(event = delivery.finishedEvent(), result = feedback.diagnosticResult())
        endPlaybackTimelineOperation()
        emitPlaybackTimelineFeedback(delivery, feedback)
    }

    /**
     * Disabling collection invalidates the pending timeline at once. A timeline still being prepared
     * ends here. Once the exporter was asked for a destination, the operation stays current until
     * the exporter reports, so a picker that cannot be dismissed keeps the screen honestly busy and
     * no later request goes unanswered. A send keeps running: the action re-checks collection
     * immediately before the repository call, and an upload already handed to the repository is
     * irreversible and reports its real outcome.
     */
    private fun stopPlaybackTimelineForDisabledCollection() {
        when (_state.value.playbackTimelineActivity) {
            PlaybackTimelineActivity.Preparing -> {
                playbackTimelineJob?.cancel()
                finishPlaybackTimelineOperation(PlaybackTimelineFeedback.CollectionDisabled)
            }
            PlaybackTimelineActivity.Saving -> pendingPlaybackTimeline = null
            PlaybackTimelineActivity.Sending, null -> Unit
        }
    }

    private fun endPlaybackTimelineOperation() {
        activePlaybackTimelineOperation = null
        pendingPlaybackTimeline = null
        playbackTimelineJob = null
        reduce(SettingsChange.TimelineFinished)
    }

    private fun emitPlaybackTimelineFeedback(
        delivery: PlaybackTimelineDelivery,
        feedback: PlaybackTimelineFeedback,
    ) {
        _events.trySend(SettingsEvent.PlaybackTimelineDelivered(delivery, feedback))
    }

    /** Android may lazily load LibVLC here. */
    private fun loadAvailablePlayerBackends() {
        viewModelScope.launch {
            val availableBackends =
                try {
                    withContext(workDispatcher) { getAvailablePlayerBackendsUseCase() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    logSettingsFailure("available-backends", DiagnosticOperation.GetAvailablePlayerBackends, exception)
                    return@launch
                }
            val choices = playerBackendChoices(playerBackendPolicy, availableBackends)
            reduce(SettingsChange.BackendChoicesUpdated(choices))
        }
    }

    private fun loadOpenSubtitlesApiKey() {
        val getApiKey = getOpenSubtitlesApiKeyUseCase ?: return
        val current = _state.value
        if (current.isOpenSubtitlesApiKeyLoaded || current.isLoadingOpenSubtitlesApiKey) return
        val loadGeneration = ++openSubtitlesApiKeyLoadGeneration
        reduce(SettingsChange.ApiKeyLoadStarted)
        viewModelScope.launch {
            val value =
                try {
                    withContext(workDispatcher) { getApiKey().orEmpty() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    logSettingsFailure("open-subtitles-api-key", DiagnosticOperation.GetOpenSubtitlesApiKey, exception)
                    reduce(SettingsChange.ApiKeyLoadFailed(loadGeneration))
                    return@launch
                }
            reduce(SettingsChange.ApiKeyLoaded(loadGeneration, value))
        }
    }

    private fun loadOpenSubtitleResultPreference() {
        val getPreference = getOpenSubtitleResultPreferenceUseCase ?: return
        val current = _state.value
        if (current.isOpenSubtitleResultPreferenceLoaded || current.isLoadingOpenSubtitleResultPreference) return
        reduce(SettingsChange.SubtitlePreferenceLoadStarted)
        viewModelScope.launch {
            try {
                val value = withContext(workDispatcher) { getPreference() }
                reduce(SettingsChange.SubtitlePreferenceLoaded(value))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure(
                    "subtitle-result-preference-load",
                    DiagnosticOperation.GetOpenSubtitleResultPreference,
                    exception,
                )
                reduce(SettingsChange.SubtitlePreferenceLoadFailed)
            }
        }
    }

    private fun observeAppTheme() {
        viewModelScope.launch {
            appTheme.collect { theme ->
                reduce(SettingsChange.ThemeObserved(theme))
            }
        }
    }

    private fun observeTileSize() {
        viewModelScope.launch {
            tileSize.collect { value ->
                reduce(SettingsChange.TileSizeObserved(value))
            }
        }
    }

    private fun observeImageQuality() {
        viewModelScope.launch {
            imageQuality.collect { value ->
                reduce(SettingsChange.ImageQualityObserved(value))
            }
        }
    }

    private fun observePictureInPicture() {
        viewModelScope.launch {
            pictureInPictureEnabled.collect { enabled ->
                reduce(SettingsChange.PipObserved(enabled))
            }
        }
    }

    private fun observePlayerClock() {
        viewModelScope.launch {
            playerClockEnabled.collect { enabled ->
                reduce(SettingsChange.ClockObserved(enabled))
            }
        }
    }

    private fun observeDesktopMpvPresentation() {
        val presentation = desktopMpvPresentation ?: return
        viewModelScope.launch {
            presentation.collect { value ->
                reduce(SettingsChange.DesktopObserved(value))
            }
        }
    }

    private fun observeRememberLastLibraryView() {
        viewModelScope.launch {
            rememberLastLibraryView.collect { enabled ->
                reduce(SettingsChange.RememberViewObserved(enabled))
            }
        }
    }

    private fun observePlayerDeviceSettings() {
        viewModelScope.launch {
            merge(
                playerDeviceSettings.map { deviceSettings ->
                    DevicePolicyProbeRequest(deviceSettings, forceRefresh = false)
                },
                policyRefreshRequests.receiveAsFlow().map {
                    DevicePolicyProbeRequest(
                        deviceSettings = playerDeviceSettings.value,
                        forceRefresh = true,
                    )
                },
            ).collectLatest { request ->
                val policy =
                    try {
                        withContext(workDispatcher) {
                            if (request.forceRefresh) {
                                refreshPlayerDevicePolicyUseCase()
                            } else {
                                getPlayerDevicePolicyUseCase()
                            }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        settingsViewModelLogger.w {
                            formatSafeFailureDiagnostic(
                                stage = "device-policy",
                                event = "failed",
                                operation =
                                    if (request.forceRefresh) {
                                        DiagnosticOperation.RefreshPlayerDevicePolicy
                                    } else {
                                        DiagnosticOperation.GetPlayerDevicePolicy
                                    },
                                throwable = exception,
                            )
                        }
                        reduce(SettingsChange.DevicePolicyFailed(request.deviceSettings, request.forceRefresh))
                        return@collectLatest
                    }
                if (playerDeviceSettings.value != request.deviceSettings) {
                    return@collectLatest
                }
                reduce(SettingsChange.DevicePolicyLoaded(request.deviceSettings, request.forceRefresh, policy))
            }
        }
    }

    private fun observeLogCollection() {
        logCollectionState?.let { collection ->
            viewModelScope.launch {
                collection.collect { enabled ->
                    reduce(SettingsChange.LogCollectionObserved(enabled))
                    if (!enabled) stopPlaybackTimelineForDisabledCollection()
                }
            }
        }
        logBufferSize?.let { size ->
            viewModelScope.launch {
                size.collect { value ->
                    reduce(SettingsChange.LogSizeObserved(value))
                }
            }
        }
    }

    private fun observeVerboseLogcat() {
        val verboseLogcat = verboseLogcatState ?: return
        viewModelScope.launch {
            verboseLogcat.collect { enabled ->
                reduce(SettingsChange.VerboseLogsObserved(enabled))
            }
        }
    }

    private fun observePlaybackInfoAtStart() {
        val playbackInfoAtStart = playbackInfoAtStartState ?: return
        viewModelScope.launch {
            playbackInfoAtStart.collect { enabled ->
                reduce(SettingsChange.PlaybackInfoObserved(enabled))
            }
        }
    }

    private fun loadPlaybackPreferences() {
        reduce(SettingsChange.PlaybackLoadStarted)
        viewModelScope.launch {
            try {
                val preferences =
                    withContext(workDispatcher) {
                        getPlaybackPreferencesUseCase(session.accountIdentity())
                    }.normalized()
                reduce(SettingsChange.PlaybackLoaded(preferences))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure("playback-preferences-load", DiagnosticOperation.GetPlaybackPreferences, exception)
                reduce(SettingsChange.PlaybackLoadFailed)
            }
        }
    }

    private fun savePlaybackPreferences(transform: (PlaybackPreferences) -> PlaybackPreferences) {
        reduce(SettingsChange.PlaybackChanged(transform))
        val nextPreferences = _state.value.playbackPreferences

        playbackPreferencesWriter.submit(nextPreferences)
    }

    private fun savePlayerDeviceSettings(transform: (PlayerDeviceSettings) -> PlayerDeviceSettings) {
        reduce(SettingsChange.DeviceSettingsChanged(transform))
        val nextSettings = _state.value.playerDeviceSettings

        playerDeviceSettingsWriter.submit(nextSettings)
    }
}

internal class LatestValueWriter<T>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val write: suspend (T) -> Unit,
    private val operation: DiagnosticOperation? = null,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val values = Channel<T>(capacity = Channel.CONFLATED)

    init {
        scope.launch(dispatcher) {
            for (value in values) {
                try {
                    write(value)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    operation?.let { diagnosticOperation ->
                        settingsViewModelLogger.w {
                            formatSafeFailureDiagnostic(
                                stage = "settings-write",
                                event = "failed",
                                operation = diagnosticOperation,
                                throwable = exception,
                            )
                        }
                    }
                    scope.launch { onFailure(exception) }
                }
            }
        }
    }

    fun submit(value: T) {
        values.trySend(value)
    }
}

private fun String?.normalizedPreferenceText(): String? = this?.trim()?.takeIf { value -> value.isNotBlank() }

private data class DevicePolicyProbeRequest(
    val deviceSettings: PlayerDeviceSettings,
    val forceRefresh: Boolean,
)

private fun logSettingsFailure(
    stage: String,
    operation: DiagnosticOperation,
    throwable: Throwable,
) {
    settingsViewModelLogger.w {
        formatSafeFailureDiagnostic(
            stage = stage,
            event = "failed",
            operation = operation,
            throwable = throwable,
        )
    }
}

// Fixed stage/event and sanitized exception type only; never the timeline, a destination, or a message.
private fun logPlaybackTimelineFailure(
    event: String,
    throwable: Throwable,
) {
    playbackTimelineLogger.w {
        formatSafeFailureDiagnostic(stage = "playback-timeline", event = event, throwable = throwable)
    }
}

// Closed stage/event/result literals only; never the timeline, a destination, or a server filename.
private fun logPlaybackTimelineOutcome(
    event: String,
    result: String,
) {
    playbackTimelineLogger.i { "stage=playback-timeline event=$event result=$result" }
}

/**
 * Random per operation, so an id the Android bridge restores into a new ViewModel or process never
 * matches bytes prepared later.
 */
private fun newPlaybackTimelineOperationId(): Long =
    generateSequence { Random.nextLong() }.first { id -> id != NO_PLAYBACK_TIMELINE_OPERATION }

/** Stale results still worth showing when nothing is current: each leaves something at the destination. */
private val reportedStaleSaveResults =
    setOf(
        PlaybackTimelineSaveResult.Saved,
        PlaybackTimelineSaveResult.Interrupted,
        PlaybackTimelineSaveResult.Failure,
    )

private fun PlaybackTimelineSaveResult.playbackTimelineFeedback(): PlaybackTimelineFeedback =
    when (this) {
        PlaybackTimelineSaveResult.Saved -> PlaybackTimelineFeedback.Saved
        PlaybackTimelineSaveResult.Cancelled -> PlaybackTimelineFeedback.SaveCancelled
        PlaybackTimelineSaveResult.Unavailable -> PlaybackTimelineFeedback.SaveUnavailable
        PlaybackTimelineSaveResult.Interrupted -> PlaybackTimelineFeedback.Interrupted
        PlaybackTimelineSaveResult.Expired -> PlaybackTimelineFeedback.Expired
        PlaybackTimelineSaveResult.Failure -> PlaybackTimelineFeedback.SaveFailed
    }

private fun PlaybackTimelineDelivery.finishedEvent(): String =
    when (this) {
        PlaybackTimelineDelivery.Save -> "save-finished"
        PlaybackTimelineDelivery.Send -> "send-finished"
    }

/** The sent filename is never a diagnostic value. */
private fun PlaybackTimelineFeedback.diagnosticResult(): String =
    when (this) {
        PlaybackTimelineFeedback.CollectionDisabled -> "collection-disabled"
        PlaybackTimelineFeedback.NoTimedEvents -> "no-timed-events"
        PlaybackTimelineFeedback.PrepareFailed -> "prepare-failed"
        PlaybackTimelineFeedback.Saved -> "saved"
        PlaybackTimelineFeedback.SaveCancelled -> "cancelled"
        PlaybackTimelineFeedback.SaveUnavailable -> "unavailable"
        PlaybackTimelineFeedback.Interrupted -> "interrupted"
        PlaybackTimelineFeedback.Expired -> "expired"
        PlaybackTimelineFeedback.SaveFailed -> "save-failed"
        is PlaybackTimelineFeedback.Sent -> "sent"
        PlaybackTimelineFeedback.UploadDisallowed -> "upload-disallowed"
        PlaybackTimelineFeedback.SendFailed -> "send-failed"
    }

private val settingsViewModelLogger = diagnosticLogger(DiagnosticTag.SettingsViewModel)
private val playbackTimelineLogger = diagnosticLogger(DiagnosticTag.PlaybackTimeline)
