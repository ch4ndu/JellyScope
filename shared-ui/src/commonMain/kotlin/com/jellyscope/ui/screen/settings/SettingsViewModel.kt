// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.action.ClearLocalSubtitlesAction
import com.jellyscope.core.domain.action.LogoutAction
import com.jellyscope.core.domain.action.SavePlaybackPreferencesAction
import com.jellyscope.core.domain.action.SavePlayerDeviceSettingsAction
import com.jellyscope.core.domain.action.SendClientLogsAction
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalError
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.action.SetAppThemeAction
import com.jellyscope.core.domain.action.SetLogCollectionEnabledAction
import com.jellyscope.core.domain.action.SetOpenSubtitleResultPreferenceAction
import com.jellyscope.core.domain.action.SetOpenSubtitlesApiKeyAction
import com.jellyscope.core.domain.action.SetPictureInPictureEnabledAction
import com.jellyscope.core.domain.action.SetPlaybackInfoAtStartEnabledAction
import com.jellyscope.core.domain.action.SetRememberLastLibraryViewAction
import com.jellyscope.core.domain.action.SetTileSizeAction
import com.jellyscope.core.domain.action.SetVerboseLogcatEnabledAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
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
import com.jellyscope.core.domain.usecase.ObservePictureInPictureEnabledUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerDeviceSettingsUseCase
import com.jellyscope.core.domain.usecase.ObserveRememberLastLibraryViewUseCase
import com.jellyscope.core.domain.usecase.ObserveTileSizeUseCase
import com.jellyscope.core.domain.usecase.RefreshPlayerDevicePolicyUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogBufferSize
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.ui.AppInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

data class SettingsUiState(
    val serverName: String,
    val serverUrl: String,
    val userName: String,
    val versionName: String,
    val sourceRevision: String = "unknown",
    val playerBackendChoices: List<PlayerBackendChoice>,
    val selectedPlayerBackend: PlayerBackend,
    val isLoggingOut: Boolean = false,
    val isLoadingLogoutRemovalPreview: Boolean = false,
    val logoutRemovalPreview: DownloadRemovalPreview? = null,
    val downloadUsage: DownloadUsage? = null,
    val downloadSettings: DownloadSettings? = null,
    val isLoadingDownloadUsage: Boolean = false,
    val appTheme: AppColorThemeId = AppColorThemeId.Ember,
    val tileSize: TileSizeId = TileSizeId.Medium,
    val pictureInPictureEnabled: Boolean = true,
    val rememberLastLibraryView: Boolean = true,
    val playbackPreferences: PlaybackPreferences = PlaybackPreferences(),
    val isLoadingPlaybackPreferences: Boolean = false,
    val playbackPreferencesError: Boolean = false,
    val playerDeviceSettings: PlayerDeviceSettings = PlayerDeviceSettings(),
    val playerDevicePolicy: EffectivePlayerDevicePolicy? = null,
    val isRefreshingPlayerDevicePolicy: Boolean = false,
    val playerDevicePolicyRefreshCompletion: Int = 0,
    val playerDevicePolicyError: Boolean = false,
    val openSubtitlesApiKey: String = "",
    val isOpenSubtitlesApiKeyLoaded: Boolean = false,
    val isLoadingOpenSubtitlesApiKey: Boolean = false,
    val isSavingOpenSubtitlesApiKey: Boolean = false,
    val openSubtitlesApiKeyLoadError: Boolean = false,
    val openSubtitlesApiKeyError: Boolean = false,
    val openSubtitleResultPreference: OpenSubtitleResultPreference = OpenSubtitleResultPreference.NoPreference,
    val isOpenSubtitleResultPreferenceLoaded: Boolean = false,
    val isLoadingOpenSubtitleResultPreference: Boolean = false,
    val openSubtitleResultPreferenceLoadError: Boolean = false,
    val isSavingOpenSubtitleResultPreference: Boolean = false,
    val openSubtitleResultPreferenceError: Boolean = false,
    val isClearingLocalSubtitles: Boolean = false,
    val localSubtitlesClearCompletion: Int = 0,
    val localSubtitlesClearError: Boolean = false,
    val collectLogs: Boolean = false,
    val verboseLogcatEnabled: Boolean = false,
    val playbackInfoAtStartEnabled: Boolean = false,
    val logBufferSize: LogBufferSize = LogBufferSize(),
    val isSendingClientLogs: Boolean = false,
    val logCollectionPreferenceError: Boolean = false,
)

sealed interface SettingsEvent {
    data class ClientLogsSent(
        val result: SendClientLogsResult,
    ) : SettingsEvent

    data object LogoutFailed : SettingsEvent
}

class SettingsViewModel(
    private val session: Session,
    appInfo: AppInfo,
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
    observeRememberLastLibraryViewUseCase: ObserveRememberLastLibraryViewUseCase,
    private val setRememberLastLibraryViewAction: SetRememberLastLibraryViewAction,
    observeTileSizeUseCase: ObserveTileSizeUseCase,
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
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel() {
    private val appTheme = observeAppThemeUseCase()
    private val tileSize = observeTileSizeUseCase()
    private val pictureInPictureEnabled = observePictureInPictureEnabledUseCase()
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
                versionName = appInfo.versionName,
                sourceRevision = appInfo.sourceRevision,
                playerBackendChoices = optimisticPlayerBackendChoices,
                selectedPlayerBackend =
                    effectivePlayerBackendSelection(
                        persistedBackend = PlaybackPreferences().defaultPlayerBackend,
                        choices = optimisticPlayerBackendChoices,
                    ),
                appTheme = appTheme.value,
                tileSize = tileSize.value,
                pictureInPictureEnabled = pictureInPictureEnabled.value,
                rememberLastLibraryView = rememberLastLibraryView.value,
                playerDeviceSettings = playerDeviceSettings.value,
                playerDevicePolicy = null,
                isLoadingPlaybackPreferences = true,
                collectLogs = logCollectionState?.value ?: false,
                verboseLogcatEnabled = verboseLogcatState?.value ?: false,
                playbackInfoAtStartEnabled = playbackInfoAtStartState?.value ?: false,
                logBufferSize = logBufferSize?.value ?: LogBufferSize(),
            ),
        )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private val _events = Channel<SettingsEvent>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var logoutRemovalScope: SessionRemovalScope.FullLogout? = null
    private var openSubtitlesApiKeyLoadGeneration = 0L
    private var downloadUsageGeneration = 0L
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
    private val tileSizeWriter =
        LatestValueWriter(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = setTileSizeAction::invoke,
            operation = DiagnosticOperation.SetTileSize,
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
                    serverId = session.serverId,
                    preferences = preferences,
                )
            },
            operation = DiagnosticOperation.SavePlaybackPreferences,
            onFailure = {
                _state.update { current -> current.copy(playbackPreferencesError = true) }
            },
        )
    private val playerDeviceSettingsWriter =
        LatestValueWriter<PlayerDeviceSettings>(
            scope = viewModelScope,
            dispatcher = workDispatcher,
            write = savePlayerDeviceSettingsAction::invoke,
            operation = DiagnosticOperation.SavePlayerDeviceSettings,
            onFailure = {
                _state.update { current -> current.copy(playerDevicePolicyError = true) }
            },
        )

    init {
        observeAppTheme()
        observeTileSize()
        observePictureInPicture()
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

    fun refreshDownloadUsage() {
        loadDownloadUsage()
    }

    private fun loadDownloadUsage() {
        val getUsage = getDownloadUsageUseCase ?: return
        val getSettings = getDownloadSettingsUseCase ?: return
        val requestGeneration = ++downloadUsageGeneration
        viewModelScope.launch {
            _state.update { current -> current.copy(isLoadingDownloadUsage = true) }
            val usage =
                try {
                    withContext(workDispatcher) { getUsage(session.accountIdentity()) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    logSettingsFailure("download-usage", DiagnosticOperation.GetDownloadUsage, exception)
                    _state.update { current ->
                        if (requestGeneration == downloadUsageGeneration) current.copy(isLoadingDownloadUsage = false) else current
                    }
                    return@launch
                }
            val settings =
                try {
                    withContext(workDispatcher) { getSettings() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    logSettingsFailure("download-settings", DiagnosticOperation.GetDownloadSettings, exception)
                    _state.update { current ->
                        if (requestGeneration == downloadUsageGeneration) current.copy(isLoadingDownloadUsage = false) else current
                    }
                    return@launch
                }
            _state.update { current ->
                if (requestGeneration != downloadUsageGeneration) {
                    current
                } else {
                    current.copy(
                        downloadUsage = usage,
                        downloadSettings = settings,
                        isLoadingDownloadUsage = false,
                    )
                }
            }
        }
    }

    fun logout(onComplete: () -> Unit = {}) {
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
            _state.update { current ->
                current.copy(isLoggingOut = true, isLoadingLogoutRemovalPreview = true)
            }
            val scope = fullLogoutScope()
            logoutRemovalScope = scope
            val preview = loadLogoutRemovalPreview(scope)
            if (preview == null) {
                _state.update { current -> current.copy(isLoggingOut = false, isLoadingLogoutRemovalPreview = false) }
                _events.send(SettingsEvent.LogoutFailed)
            } else if (preview.confirmations.any { confirmation -> confirmation.recordCount > 0L }) {
                _state.update { current ->
                    current.copy(
                        isLoggingOut = false,
                        isLoadingLogoutRemovalPreview = false,
                        logoutRemovalPreview = preview,
                    )
                }
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
                    _state.update { current -> current.copy(isLoggingOut = false) }
                    _events.send(SettingsEvent.LogoutFailed)
                } else {
                    performLogout(authorization, onComplete, scope)
                }
            }
        }
    }

    fun confirmLogoutRemoval(onComplete: () -> Unit = {}) {
        val issueAuthorization = issueDownloadRemovalAuthorizationUseCase ?: return
        val preview = _state.value.logoutRemovalPreview ?: return
        val scope = logoutRemovalScope ?: return
        if (_state.value.isLoggingOut) return
        viewModelScope.launch {
            _state.update { current -> current.copy(isLoggingOut = true, logoutRemovalPreview = null) }
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
                _state.update { current -> current.copy(isLoggingOut = false) }
                _events.send(SettingsEvent.LogoutFailed)
            } else {
                performLogout(authorization, onComplete, scope)
            }
        }
    }

    fun dismissLogoutRemoval() {
        val preview = _state.value.logoutRemovalPreview
        val scope = logoutRemovalScope
        val release = releaseDownloadRemovalPreviewUseCase
        if (preview == null || scope == null || release == null) {
            logoutRemovalScope = null
            _state.update { current ->
                current.copy(
                    isLoggingOut = false,
                    isLoadingLogoutRemovalPreview = false,
                    logoutRemovalPreview = null,
                )
            }
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
                _state.update { current ->
                    current.copy(
                        isLoggingOut = false,
                        isLoadingLogoutRemovalPreview = false,
                        logoutRemovalPreview = null,
                    )
                }
            }
        }
    }

    private suspend fun performLogout(
        authorization: SessionRemovalAuthorization,
        onComplete: () -> Unit,
        scope: SessionRemovalScope.FullLogout?,
    ) {
        _state.update { current -> current.copy(isLoggingOut = true) }
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
            _state.update { current -> current.copy(isLoggingOut = false) }
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
            _state.update { current -> current.copy(isLoggingOut = false, isLoadingLogoutRemovalPreview = false) }
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
        _state.update { current ->
            current.copy(
                isLoggingOut = false,
                isLoadingLogoutRemovalPreview = false,
                logoutRemovalPreview = preview,
            )
        }
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

    fun reloadPlaybackPreferences() {
        loadPlaybackPreferences()
    }

    fun setDefaultMaxBitrateBps(defaultMaxBitrateBps: Long?) {
        val policy = defaultMaxBitrateBps?.let { bitrate -> PlaybackQualityPolicy.fixed(bitrate) } ?: PlaybackQualityPolicy.Auto
        savePlaybackPreferences { current ->
            current.copy(
                defaultQualityPolicy = policy,
                defaultMaxBitrateBps = policy.maxBitrateBps,
            )
        }
    }

    fun setDefaultQualityPolicy(policy: PlaybackQualityPolicy) {
        val normalized = policy.normalized()
        savePlaybackPreferences { current ->
            current.copy(
                defaultQualityPolicy = normalized,
                defaultMaxBitrateBps = normalized.maxBitrateBps,
            )
        }
    }

    fun setVlcTranscodeMaxBitrateBps(maxBitrateBps: Long?) {
        savePlaybackPreferences { current ->
            current.copy(vlcTranscodeMaxBitrateBps = maxBitrateBps).normalized()
        }
    }

    fun setDefaultPlayerBackend(backend: PlayerBackend) {
        savePlaybackPreferences { current -> current.copy(defaultPlayerBackend = backend) }
    }

    fun setPreferredAudioLanguage(language: String?) {
        savePlaybackPreferences { current ->
            current.copy(preferredAudioLanguage = language.normalizedPreferenceText())
        }
    }

    fun setPreferredSubtitleLanguage(language: String?) {
        savePlaybackPreferences { current ->
            current.copy(preferredSubtitleLanguage = language.normalizedPreferenceText())
        }
    }

    fun setAutoPlayNext(enabled: Boolean) {
        savePlaybackPreferences { current -> current.copy(autoPlayNext = enabled) }
    }

    fun setAutoPlayNextDelaySeconds(delaySeconds: Int) {
        savePlaybackPreferences { current ->
            current.copy(autoPlayNextDelaySeconds = delaySeconds).normalized()
        }
    }

    fun setStillWatchingPrompt(enabled: Boolean) {
        savePlaybackPreferences { current -> current.copy(stillWatchingPrompt = enabled) }
    }

    fun setPlaybackWarningsEnabled(enabled: Boolean) {
        savePlaybackPreferences { current -> current.copy(playbackWarningsEnabled = enabled) }
    }

    fun setSegmentSkipPolicy(
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

    fun setPlayerAudioMode(audioMode: PlayerAudioMode) {
        savePlayerDeviceSettings { current -> current.copy(audioMode = audioMode) }
    }

    fun setPlayerHdrMode(hdrMode: PlayerHdrMode) {
        savePlayerDeviceSettings { current -> current.copy(hdrMode = hdrMode) }
    }

    fun setMaxVideoResolution(limit: PlayerVideoResolutionLimit) {
        savePlayerDeviceSettings { current -> current.copy(maxVideoResolution = limit) }
    }

    fun setIosPlaybackCompatibilityMode(mode: IosPlaybackCompatibilityMode) {
        savePlayerDeviceSettings { current -> current.copy(iosPlaybackCompatibilityMode = mode) }
    }

    fun setMatchDisplayRefreshRate(enabled: Boolean) {
        savePlayerDeviceSettings { current -> current.copy(matchDisplayRefreshRate = enabled) }
    }

    fun refreshPlayerDevicePolicy() {
        if (_state.value.isRefreshingPlayerDevicePolicy) return
        _state.update { current ->
            current.copy(
                isRefreshingPlayerDevicePolicy = true,
                playerDevicePolicyError = false,
            )
        }
        policyRefreshRequests.trySend(Unit)
    }

    fun setAppTheme(theme: AppColorThemeId) {
        appThemeWriter.submit(theme)
    }

    fun setTileSize(tileSize: TileSizeId) {
        tileSizeWriter.submit(tileSize)
    }

    fun setPictureInPictureEnabled(enabled: Boolean) {
        pictureInPictureWriter.submit(enabled)
    }

    fun setRememberLastLibraryView(enabled: Boolean) {
        rememberLastLibraryViewWriter.submit(enabled)
    }

    fun setOpenSubtitlesApiKey(
        value: String,
        onSuccess: () -> Unit = {},
    ) {
        val setKey = setOpenSubtitlesApiKeyAction ?: return
        val current = _state.value
        if (!current.isOpenSubtitlesApiKeyLoaded || current.isSavingOpenSubtitlesApiKey) return
        _state.update { state -> state.copy(isSavingOpenSubtitlesApiKey = true, openSubtitlesApiKeyError = false) }
        viewModelScope.launch {
            try {
                withContext(workDispatcher) { setKey(value) }
                _state.update { state ->
                    state.copy(
                        openSubtitlesApiKey = value,
                        isSavingOpenSubtitlesApiKey = false,
                        openSubtitlesApiKeyError = false,
                    )
                }
                onSuccess()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure("open-subtitles-api-key-save", DiagnosticOperation.SetOpenSubtitlesApiKey, exception)
                _state.update { state ->
                    state.copy(
                        isSavingOpenSubtitlesApiKey = false,
                        openSubtitlesApiKeyError = true,
                    )
                }
            }
        }
    }

    fun retryOpenSubtitlesApiKeyLoad() {
        loadOpenSubtitlesApiKey()
    }

    fun setOpenSubtitleResultPreference(
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
        _state.update { current ->
            current.copy(
                isSavingOpenSubtitleResultPreference = true,
                openSubtitleResultPreferenceError = false,
            )
        }
        viewModelScope.launch {
            try {
                withContext(workDispatcher) { setPreference(preference) }
                _state.update { current ->
                    current.copy(
                        openSubtitleResultPreference = preference,
                        openSubtitleResultPreferenceError = false,
                    )
                }
                onSuccess()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure(
                    "subtitle-result-preference-save",
                    DiagnosticOperation.SetOpenSubtitleResultPreference,
                    exception,
                )
                _state.update { current -> current.copy(openSubtitleResultPreferenceError = true) }
            } finally {
                _state.update { current -> current.copy(isSavingOpenSubtitleResultPreference = false) }
            }
        }
    }

    fun retryOpenSubtitleResultPreferenceLoad() {
        loadOpenSubtitleResultPreference()
    }

    fun clearLocalSubtitles() {
        val clear = clearLocalSubtitlesAction ?: return
        if (_state.value.isClearingLocalSubtitles) return
        _state.update { current ->
            current.copy(
                isClearingLocalSubtitles = true,
                localSubtitlesClearError = false,
            )
        }
        viewModelScope.launch {
            try {
                withContext(workDispatcher) { clear() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure("local-subtitle-clear", DiagnosticOperation.ClearLocalSubtitles, exception)
                _state.update { current -> current.copy(localSubtitlesClearError = true) }
            } finally {
                _state.update { current ->
                    current.copy(
                        isClearingLocalSubtitles = false,
                        localSubtitlesClearCompletion = current.localSubtitlesClearCompletion + 1,
                    )
                }
            }
        }
    }

    fun setLogCollectionEnabled(enabled: Boolean) {
        val setEnabled = setLogCollectionEnabledAction ?: return
        viewModelScope.launch {
            try {
                withContext(workDispatcher) {
                    setEnabled(enabled)
                }
                _state.update { current -> current.copy(logCollectionPreferenceError = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Throwable) {
                logSettingsFailure("log-collection", DiagnosticOperation.SetLogCollectionEnabled, exception)
                _state.update { current -> current.copy(logCollectionPreferenceError = true) }
            }
        }
    }

    fun setVerboseLogcatEnabled(enabled: Boolean) {
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

    fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
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

    fun sendClientLogs() {
        val sendLogs = sendClientLogsAction ?: return
        val currentState = _state.value
        if (currentState.isSendingClientLogs) return
        val preferredBackend = currentState.playbackPreferences.defaultPlayerBackend
        _state.update { current -> current.copy(isSendingClientLogs = true) }
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
                _state.update { current -> current.copy(isSendingClientLogs = false) }
            }
        }
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
            _state.update { current ->
                current.copy(
                    playerBackendChoices = choices,
                    selectedPlayerBackend =
                        effectivePlayerBackendSelection(
                            persistedBackend = current.playbackPreferences.defaultPlayerBackend,
                            choices = choices,
                        ),
                )
            }
        }
    }

    private fun loadOpenSubtitlesApiKey() {
        val getApiKey = getOpenSubtitlesApiKeyUseCase ?: return
        val current = _state.value
        if (current.isOpenSubtitlesApiKeyLoaded || current.isLoadingOpenSubtitlesApiKey) return
        val loadGeneration = ++openSubtitlesApiKeyLoadGeneration
        _state.update { state ->
            state.copy(
                isLoadingOpenSubtitlesApiKey = true,
                openSubtitlesApiKeyLoadError = false,
            )
        }
        viewModelScope.launch {
            val value =
                try {
                    withContext(workDispatcher) { getApiKey().orEmpty() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Throwable) {
                    logSettingsFailure("open-subtitles-api-key", DiagnosticOperation.GetOpenSubtitlesApiKey, exception)
                    _state.update { current ->
                        if (loadGeneration == openSubtitlesApiKeyLoadGeneration) {
                            current.copy(
                                isLoadingOpenSubtitlesApiKey = false,
                                openSubtitlesApiKeyLoadError = true,
                            )
                        } else {
                            current
                        }
                    }
                    return@launch
                }
            _state.update { current ->
                if (loadGeneration != openSubtitlesApiKeyLoadGeneration) {
                    current
                } else if (current.isSavingOpenSubtitlesApiKey) {
                    current.copy(isLoadingOpenSubtitlesApiKey = false)
                } else {
                    current.copy(
                        openSubtitlesApiKey = value,
                        isOpenSubtitlesApiKeyLoaded = true,
                        isLoadingOpenSubtitlesApiKey = false,
                        openSubtitlesApiKeyLoadError = false,
                    )
                }
            }
        }
    }

    private fun loadOpenSubtitleResultPreference() {
        val getPreference = getOpenSubtitleResultPreferenceUseCase ?: return
        val current = _state.value
        if (current.isOpenSubtitleResultPreferenceLoaded || current.isLoadingOpenSubtitleResultPreference) return
        _state.update { state ->
            state.copy(
                isLoadingOpenSubtitleResultPreference = true,
                openSubtitleResultPreferenceLoadError = false,
            )
        }
        viewModelScope.launch {
            try {
                val value = withContext(workDispatcher) { getPreference() }
                _state.update { state ->
                    state.copy(
                        openSubtitleResultPreference = value,
                        isOpenSubtitleResultPreferenceLoaded = true,
                        isLoadingOpenSubtitleResultPreference = false,
                        openSubtitleResultPreferenceLoadError = false,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure(
                    "subtitle-result-preference-load",
                    DiagnosticOperation.GetOpenSubtitleResultPreference,
                    exception,
                )
                _state.update { state ->
                    state.copy(
                        isOpenSubtitleResultPreferenceLoaded = false,
                        isLoadingOpenSubtitleResultPreference = false,
                        openSubtitleResultPreferenceLoadError = true,
                    )
                }
            }
        }
    }

    private fun observeAppTheme() {
        viewModelScope.launch {
            appTheme.collect { theme ->
                _state.update { current -> current.copy(appTheme = theme) }
            }
        }
    }

    private fun observeTileSize() {
        viewModelScope.launch {
            tileSize.collect { value ->
                _state.update { current -> current.copy(tileSize = value) }
            }
        }
    }

    private fun observePictureInPicture() {
        viewModelScope.launch {
            pictureInPictureEnabled.collect { enabled ->
                _state.update { current -> current.copy(pictureInPictureEnabled = enabled) }
            }
        }
    }

    private fun observeRememberLastLibraryView() {
        viewModelScope.launch {
            rememberLastLibraryView.collect { enabled ->
                _state.update { current -> current.copy(rememberLastLibraryView = enabled) }
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
                        _state.update { current ->
                            current.copy(
                                playerDeviceSettings = request.deviceSettings,
                                playerDevicePolicyError = true,
                                isRefreshingPlayerDevicePolicy = false,
                                playerDevicePolicyRefreshCompletion =
                                    if (request.forceRefresh) {
                                        current.playerDevicePolicyRefreshCompletion + 1
                                    } else {
                                        current.playerDevicePolicyRefreshCompletion
                                    },
                            )
                        }
                        return@collectLatest
                    }
                if (playerDeviceSettings.value != request.deviceSettings) {
                    return@collectLatest
                }
                _state.update { current ->
                    current.copy(
                        playerDeviceSettings = request.deviceSettings,
                        playerDevicePolicy = policy,
                        playerDevicePolicyError = false,
                        isRefreshingPlayerDevicePolicy = false,
                        playerDevicePolicyRefreshCompletion =
                            if (request.forceRefresh) {
                                current.playerDevicePolicyRefreshCompletion + 1
                            } else {
                                current.playerDevicePolicyRefreshCompletion
                            },
                    )
                }
            }
        }
    }

    private fun observeLogCollection() {
        logCollectionState?.let { collection ->
            viewModelScope.launch {
                collection.collect { enabled ->
                    _state.update { current -> current.copy(collectLogs = enabled) }
                }
            }
        }
        logBufferSize?.let { size ->
            viewModelScope.launch {
                size.collect { value ->
                    _state.update { current -> current.copy(logBufferSize = value) }
                }
            }
        }
    }

    private fun observeVerboseLogcat() {
        val verboseLogcat = verboseLogcatState ?: return
        viewModelScope.launch {
            verboseLogcat.collect { enabled ->
                _state.update { current -> current.copy(verboseLogcatEnabled = enabled) }
            }
        }
    }

    private fun observePlaybackInfoAtStart() {
        val playbackInfoAtStart = playbackInfoAtStartState ?: return
        viewModelScope.launch {
            playbackInfoAtStart.collect { enabled ->
                _state.update { current -> current.copy(playbackInfoAtStartEnabled = enabled) }
            }
        }
    }

    private fun loadPlaybackPreferences() {
        _state.update { current ->
            current.copy(
                isLoadingPlaybackPreferences = true,
                playbackPreferencesError = false,
            )
        }
        viewModelScope.launch {
            try {
                val preferences =
                    withContext(workDispatcher) {
                        getPlaybackPreferencesUseCase(session.serverId)
                    }.normalized()
                _state.update { current ->
                    current.copy(
                        playbackPreferences = preferences,
                        selectedPlayerBackend =
                            effectivePlayerBackendSelection(
                                persistedBackend = preferences.defaultPlayerBackend,
                                choices = current.playerBackendChoices,
                            ),
                        isLoadingPlaybackPreferences = false,
                        playbackPreferencesError = false,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logSettingsFailure("playback-preferences-load", DiagnosticOperation.GetPlaybackPreferences, exception)
                _state.update { current ->
                    current.copy(
                        isLoadingPlaybackPreferences = false,
                        playbackPreferencesError = true,
                    )
                }
            }
        }
    }

    private fun savePlaybackPreferences(transform: (PlaybackPreferences) -> PlaybackPreferences) {
        _state.update { current ->
            val preferences = transform(current.playbackPreferences)
            current.copy(
                playbackPreferences = preferences,
                selectedPlayerBackend =
                    effectivePlayerBackendSelection(
                        persistedBackend = preferences.defaultPlayerBackend,
                        choices = current.playerBackendChoices,
                    ),
                playbackPreferencesError = false,
            )
        }
        val nextPreferences = _state.value.playbackPreferences

        playbackPreferencesWriter.submit(nextPreferences)
    }

    private fun effectivePlayerBackendSelection(
        persistedBackend: PlayerBackend?,
        choices: List<PlayerBackendChoice>,
    ): PlayerBackend {
        val normalizedBackend = playerBackendPolicy.normalizePersisted(persistedBackend)
        return normalizedBackend.takeIf { backend ->
            choices.any { choice -> choice.backend == backend && choice.available }
        } ?: choices.firstOrNull { choice -> choice.available }?.backend
            ?: choices.firstOrNull()?.backend
            ?: playerBackendPolicy.defaultBackend
    }

    private fun savePlayerDeviceSettings(transform: (PlayerDeviceSettings) -> PlayerDeviceSettings) {
        _state.update { current ->
            val nextSettings = transform(current.playerDeviceSettings)
            current.copy(
                playerDeviceSettings = nextSettings,
                playerDevicePolicy =
                    current.playerDevicePolicy?.let { policy ->
                        policy.copy(settings = nextSettings)
                    },
                playerDevicePolicyError = false,
            )
        }
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

private val settingsViewModelLogger = diagnosticLogger(DiagnosticTag.SettingsViewModel)
