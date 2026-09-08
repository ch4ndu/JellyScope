// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SetAppThemeAction
import com.jellyscope.core.domain.action.SetTileSizeAction
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.usecase.ObserveAppThemeUseCase
import com.jellyscope.core.domain.usecase.ObserveTileSizeUseCase
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

class TvAppearancePresenter(
    observeAppTheme: ObserveAppThemeUseCase,
    private val setAppThemeAction: SetAppThemeAction,
    observeTileSize: ObserveTileSizeUseCase,
    private val setTileSizeAction: SetTileSizeAction,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val observedAppTheme = observeAppTheme()
    private val observedTileSize = observeTileSize()
    private val _state =
        MutableStateFlow(
            TvAppearanceState(
                appTheme = observedAppTheme.value,
                tileSize = observedTileSize.value,
            ),
        )
    val state: StateFlow<TvAppearanceState> = _state.asStateFlow()

    private val appThemeWrites = Channel<AppThemeWrite>(Channel.CONFLATED)
    private val tileSizeWrites = Channel<TileSizeWrite>(Channel.CONFLATED)
    private var appThemeGeneration = 0L
    private var tileSizeGeneration = 0L
    private var appThemeIntent: AppColorThemeId? = null
    private var tileSizeIntent: TileSizeId? = null
    private var failedAppTheme: AppColorThemeId? = null
    private var failedTileSize: TileSizeId? = null

    init {
        scope.launch {
            observedAppTheme.collect { appTheme ->
                if (appThemeIntent == null || appThemeIntent == appTheme) {
                    _state.update { current -> current.copy(appTheme = appTheme) }
                }
            }
        }
        scope.launch {
            observedTileSize.collect { tileSize ->
                if (tileSizeIntent == null || tileSizeIntent == tileSize) {
                    _state.update { current -> current.copy(tileSize = tileSize) }
                }
            }
        }
        scope.launch {
            for (request in appThemeWrites) {
                writeAppTheme(request)
            }
        }
        scope.launch {
            for (request in tileSizeWrites) {
                writeTileSize(request)
            }
        }
    }

    fun watchState(onChange: (TvAppearanceState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun setAppTheme(appTheme: AppColorThemeId) {
        if (!state.value.isSavingAppTheme && appTheme == state.value.appTheme) return
        appThemeGeneration += 1
        appThemeIntent = appTheme
        failedAppTheme = null
        _state.update { current ->
            current.copy(
                isSavingAppTheme = true,
                appThemeSaveError = false,
            )
        }
        appThemeWrites.trySend(AppThemeWrite(appTheme, appThemeGeneration))
    }

    fun setTileSize(tileSize: TileSizeId) {
        if (!state.value.isSavingTileSize && tileSize == state.value.tileSize) return
        tileSizeGeneration += 1
        tileSizeIntent = tileSize
        failedTileSize = null
        _state.update { current ->
            current.copy(
                isSavingTileSize = true,
                tileSizeSaveError = false,
            )
        }
        tileSizeWrites.trySend(TileSizeWrite(tileSize, tileSizeGeneration))
    }

    fun retryAppTheme() {
        failedAppTheme?.let(::setAppTheme)
    }

    fun retryTileSize() {
        failedTileSize?.let(::setTileSize)
    }

    private suspend fun writeAppTheme(request: AppThemeWrite) {
        try {
            withContext(workDispatcher) { setAppThemeAction(request.value) }
            if (request.generation == appThemeGeneration) {
                appThemeIntent = null
                failedAppTheme = null
                _state.update { current ->
                    current.copy(
                        appTheme = observedAppTheme.value,
                        isSavingAppTheme = false,
                        appThemeSaveError = false,
                    )
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            appearanceLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "appearance-write",
                    event = "failed",
                    operation = DiagnosticOperation.SetAppTheme,
                    throwable = exception,
                )
            }
            if (request.generation == appThemeGeneration) {
                while (appThemeWrites.tryReceive().isSuccess) {
                    // Recover from the observed store value before accepting another intent.
                }
                appThemeIntent = null
                failedAppTheme = request.value
                _state.update { current ->
                    current.copy(
                        appTheme = observedAppTheme.value,
                        isSavingAppTheme = false,
                        appThemeSaveError = true,
                    )
                }
            }
        }
    }

    private suspend fun writeTileSize(request: TileSizeWrite) {
        try {
            withContext(workDispatcher) { setTileSizeAction(request.value) }
            if (request.generation == tileSizeGeneration) {
                tileSizeIntent = null
                failedTileSize = null
                _state.update { current ->
                    current.copy(
                        tileSize = observedTileSize.value,
                        isSavingTileSize = false,
                        tileSizeSaveError = false,
                    )
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            appearanceLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "appearance-write",
                    event = "failed",
                    operation = DiagnosticOperation.SetTileSize,
                    throwable = exception,
                )
            }
            if (request.generation == tileSizeGeneration) {
                while (tileSizeWrites.tryReceive().isSuccess) {
                    // Recover from the observed store value before accepting another intent.
                }
                tileSizeIntent = null
                failedTileSize = request.value
                _state.update { current ->
                    current.copy(
                        tileSize = observedTileSize.value,
                        isSavingTileSize = false,
                        tileSizeSaveError = true,
                    )
                }
            }
        }
    }
}

private data class AppThemeWrite(
    val value: AppColorThemeId,
    val generation: Long,
)

private data class TileSizeWrite(
    val value: TileSizeId,
    val generation: Long,
)

private val appearanceLogger = diagnosticLogger(DiagnosticTag.TvSettingsPresenter)
