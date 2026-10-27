// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.ClearLocalSubtitlesAction
import com.jellyscope.core.domain.action.SetOpenSubtitleResultPreferenceAction
import com.jellyscope.core.domain.action.SetOpenSubtitlesApiKeyAction
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.usecase.GetOpenSubtitleResultPreferenceUseCase
import com.jellyscope.core.domain.usecase.GetOpenSubtitlesApiKeyUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvSubtitleSettingsPresenter(
    private val getOpenSubtitlesApiKey: GetOpenSubtitlesApiKeyUseCase,
    private val setOpenSubtitlesApiKey: SetOpenSubtitlesApiKeyAction,
    private val getOpenSubtitleResultPreference: GetOpenSubtitleResultPreferenceUseCase,
    private val setOpenSubtitleResultPreference: SetOpenSubtitleResultPreferenceAction,
    private val clearLocalSubtitles: ClearLocalSubtitlesAction,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvSubtitleSettingsState())
    val state: StateFlow<TvSubtitleSettingsState> = _state.asStateFlow()

    private val apiKeyWrites = Channel<ApiKeyWrite>(Channel.CONFLATED)
    private val resultPreferenceWrites = Channel<ResultPreferenceWrite>(Channel.CONFLATED)
    private var apiKeyLoadGeneration = 0L
    private var apiKeyIntentGeneration = 0L
    private var resultPreferenceLoadGeneration = 0L
    private var resultPreferenceIntentGeneration = 0L

    init {
        scope.launch {
            for (write in apiKeyWrites) {
                persistApiKey(write)
            }
        }
        scope.launch {
            for (write in resultPreferenceWrites) {
                persistResultPreference(write)
            }
        }
        loadApiKey()
        loadResultPreference()
    }

    private fun reduce(change: TvSubtitleSettingsChange) = reduce { change }

    // Evaluate observed store values inside each atomic update attempt.
    private fun reduce(change: () -> TvSubtitleSettingsChange) {
        _state.update { current -> reduceTvSubtitleSettings(current, change()) }
    }

    fun accept(intent: TvSubtitleSettingsIntent) {
        when (intent) {
            is TvSubtitleSettingsIntent.SaveApiKey -> saveApiKey(intent.value)
            TvSubtitleSettingsIntent.ClearApiKey -> clearApiKey()
            TvSubtitleSettingsIntent.RetryApiKeyLoad -> retryApiKeyLoad()
            is TvSubtitleSettingsIntent.SetResultPreference -> setResultPreference(intent.preference)
            TvSubtitleSettingsIntent.RetryResultPreferenceLoad -> retryResultPreferenceLoad()
            TvSubtitleSettingsIntent.ClearDownloadedSubtitles -> clearDownloadedSubtitles()
        }
    }

    fun watchState(onChange: (TvSubtitleSettingsState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun saveApiKey(value: String) {
        if (!state.value.isApiKeyLoaded) return
        apiKeyLoadGeneration += 1
        val generation = ++apiKeyIntentGeneration
        reduce(TvSubtitleSettingsChange.ApiKeySaveStarted)
        apiKeyWrites.trySend(ApiKeyWrite(generation, value))
    }

    private fun clearApiKey() {
        saveApiKey("")
    }

    private fun retryApiKeyLoad() {
        if (state.value.isSavingApiKey) return
        loadApiKey(force = true)
    }

    private fun setResultPreference(preference: OpenSubtitleResultPreference) {
        if (!state.value.isResultPreferenceLoaded) return
        resultPreferenceLoadGeneration += 1
        val generation = ++resultPreferenceIntentGeneration
        reduce(TvSubtitleSettingsChange.PreferenceSaveStarted(preference))
        resultPreferenceWrites.trySend(ResultPreferenceWrite(generation, preference))
    }

    private fun retryResultPreferenceLoad() {
        if (state.value.isSavingResultPreference) return
        loadResultPreference(force = true)
    }

    private fun clearDownloadedSubtitles() {
        if (state.value.isClearingDownloadedSubtitles) return
        reduce(TvSubtitleSettingsChange.ClearStarted)
        scope.launch {
            try {
                withContext(workDispatcher) { clearLocalSubtitles() }
                reduce(TvSubtitleSettingsChange.ClearFinished)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logSettingsFailure(
                    stage = "local-subtitles",
                    event = "clear-failed",
                    operation = DiagnosticOperation.ClearLocalSubtitles,
                    error = error,
                )
                reduce(TvSubtitleSettingsChange.ClearFailed)
            }
        }
    }

    private fun loadApiKey(force: Boolean = false) {
        val current = state.value
        if (current.isLoadingApiKey || current.isSavingApiKey || (!force && current.isApiKeyLoaded)) return
        val generation = ++apiKeyLoadGeneration
        reduce(TvSubtitleSettingsChange.ApiKeyLoadStarted)
        scope.launch {
            val configured =
                try {
                    withContext(workDispatcher) { !getOpenSubtitlesApiKey().isNullOrBlank() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    logSettingsFailure(
                        stage = "api-key",
                        event = "load-failed",
                        operation = DiagnosticOperation.GetOpenSubtitlesApiKey,
                        error = error,
                    )
                    if (generation == apiKeyLoadGeneration) {
                        reduce(TvSubtitleSettingsChange.ApiKeyLoadFailed)
                    }
                    return@launch
                }
            if (generation != apiKeyLoadGeneration) return@launch
            reduce(TvSubtitleSettingsChange.ApiKeyLoaded(configured))
        }
    }

    private fun loadResultPreference(force: Boolean = false) {
        val current = state.value
        if (current.isLoadingResultPreference || current.isSavingResultPreference || (!force && current.isResultPreferenceLoaded)) return
        val generation = ++resultPreferenceLoadGeneration
        reduce(TvSubtitleSettingsChange.PreferenceLoadStarted)
        scope.launch {
            val preference =
                try {
                    withContext(workDispatcher) { getOpenSubtitleResultPreference() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    logSettingsFailure(
                        stage = "result-preference",
                        event = "load-failed",
                        operation = DiagnosticOperation.GetOpenSubtitleResultPreference,
                        error = error,
                    )
                    if (generation == resultPreferenceLoadGeneration) {
                        reduce(TvSubtitleSettingsChange.PreferenceLoadFailed)
                    }
                    return@launch
                }
            if (generation != resultPreferenceLoadGeneration) return@launch
            reduce(TvSubtitleSettingsChange.PreferenceLoaded(preference))
        }
    }

    private suspend fun persistApiKey(write: ApiKeyWrite) {
        val result =
            try {
                withContext(workDispatcher) {
                    setOpenSubtitlesApiKey(write.value)
                    ApiKeyWriteResult(configured = !getOpenSubtitlesApiKey().isNullOrBlank())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logSettingsFailure(
                    stage = "api-key",
                    event = "save-failed",
                    operation = DiagnosticOperation.SetOpenSubtitlesApiKey,
                    error = error,
                )
                recoverApiKey()
            }
        if (write.generation != apiKeyIntentGeneration) return
        reduce(TvSubtitleSettingsChange.ApiKeyWriteFinished(result))
    }

    private suspend fun recoverApiKey(): ApiKeyWriteResult =
        try {
            withContext(workDispatcher) {
                ApiKeyWriteResult(
                    configured = !getOpenSubtitlesApiKey().isNullOrBlank(),
                    saved = false,
                    recovered = true,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            ApiKeyWriteResult(configured = false, saved = false, recovered = false)
        }

    private suspend fun persistResultPreference(write: ResultPreferenceWrite) {
        val result =
            try {
                withContext(workDispatcher) {
                    setOpenSubtitleResultPreference(write.preference)
                    ResultPreferenceWriteResult(getOpenSubtitleResultPreference())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logSettingsFailure(
                    stage = "result-preference",
                    event = "save-failed",
                    operation = DiagnosticOperation.SetOpenSubtitleResultPreference,
                    error = error,
                )
                recoverResultPreference()
            }
        if (write.generation != resultPreferenceIntentGeneration) return
        reduce(TvSubtitleSettingsChange.PreferenceWriteFinished(result))
    }

    private suspend fun recoverResultPreference(): ResultPreferenceWriteResult =
        try {
            withContext(workDispatcher) {
                ResultPreferenceWriteResult(
                    preference = getOpenSubtitleResultPreference(),
                    saved = false,
                    recovered = true,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            ResultPreferenceWriteResult(
                preference = OpenSubtitleResultPreference.NoPreference,
                saved = false,
                recovered = false,
            )
        }
}

private data class ApiKeyWrite(
    val generation: Long,
    val value: String,
)

internal data class ApiKeyWriteResult(
    val configured: Boolean,
    val saved: Boolean = true,
    val recovered: Boolean = true,
)

private data class ResultPreferenceWrite(
    val generation: Long,
    val preference: OpenSubtitleResultPreference,
)

internal data class ResultPreferenceWriteResult(
    val preference: OpenSubtitleResultPreference,
    val saved: Boolean = true,
    val recovered: Boolean = true,
)

private fun logSettingsFailure(
    stage: String,
    event: String,
    operation: DiagnosticOperation,
    error: Throwable,
) {
    diagnosticLogger(DiagnosticTag.TvSettingsPresenter).w {
        formatSafeFailureDiagnostic(
            stage = stage,
            event = event,
            operation = operation,
            throwable = error,
        )
    }
}
