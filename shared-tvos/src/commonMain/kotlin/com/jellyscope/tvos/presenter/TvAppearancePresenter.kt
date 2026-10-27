// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SetAppThemeAction
import com.jellyscope.core.domain.action.SetImageQualityAction
import com.jellyscope.core.domain.action.SetTileSizeAction
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.ImageQuality
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.usecase.ObserveAppThemeUseCase
import com.jellyscope.core.domain.usecase.ObserveImageQualityUseCase
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
    observeImageQuality: ObserveImageQualityUseCase,
    private val setImageQualityAction: SetImageQualityAction,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val observedAppTheme = observeAppTheme()
    private val observedTileSize = observeTileSize()
    private val observedImageQuality = observeImageQuality()
    private val _state =
        MutableStateFlow(
            TvAppearanceState(
                appTheme = observedAppTheme.value,
                tileSize = observedTileSize.value,
                imageQuality = observedImageQuality.value,
            ),
        )
    val state: StateFlow<TvAppearanceState> = _state.asStateFlow()

    private val appThemeWrites = Channel<AppThemeWrite>(Channel.CONFLATED)
    private val tileSizeWrites = Channel<TileSizeWrite>(Channel.CONFLATED)
    private val imageQualityWrites = Channel<ImageQualityWrite>(Channel.CONFLATED)
    private var appThemeGeneration = 0L
    private var tileSizeGeneration = 0L
    private var imageQualityGeneration = 0L
    private var appThemeIntent: AppColorThemeId? = null
    private var tileSizeIntent: TileSizeId? = null
    private var imageQualityIntent: ImageQuality? = null
    private var failedAppTheme: AppColorThemeId? = null
    private var failedTileSize: TileSizeId? = null
    private var failedImageQuality: ImageQuality? = null

    init {
        scope.launch {
            observedAppTheme.collect { appTheme ->
                if (appThemeIntent == null || appThemeIntent == appTheme) {
                    reduce(TvAppearanceChange.ThemeObserved(appTheme))
                }
            }
        }
        scope.launch {
            observedTileSize.collect { tileSize ->
                if (tileSizeIntent == null || tileSizeIntent == tileSize) {
                    reduce(TvAppearanceChange.TileObserved(tileSize))
                }
            }
        }
        scope.launch {
            observedImageQuality.collect { imageQuality ->
                if (imageQualityIntent == null || imageQualityIntent == imageQuality) {
                    reduce(TvAppearanceChange.ImageObserved(imageQuality))
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
        scope.launch {
            for (request in imageQualityWrites) {
                writeImageQuality(request)
            }
        }
    }

    private fun reduce(change: TvAppearanceChange) = reduce { change }

    // Evaluate observed store values inside each atomic update attempt.
    private fun reduce(change: () -> TvAppearanceChange) {
        _state.update { current -> reduceTvAppearance(current, change()) }
    }

    fun accept(intent: TvAppearanceIntent) {
        when (intent) {
            is TvAppearanceIntent.SetAppTheme -> setAppTheme(intent.appTheme)
            is TvAppearanceIntent.SetTileSize -> setTileSize(intent.tileSize)
            is TvAppearanceIntent.SetImageQuality -> setImageQuality(intent.imageQuality)
            TvAppearanceIntent.RetryAppTheme -> retryAppTheme()
            TvAppearanceIntent.RetryTileSize -> retryTileSize()
            TvAppearanceIntent.RetryImageQuality -> retryImageQuality()
        }
    }

    fun watchState(onChange: (TvAppearanceState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun setAppTheme(appTheme: AppColorThemeId) {
        if (!state.value.isSavingAppTheme && appTheme == state.value.appTheme) return
        appThemeGeneration += 1
        appThemeIntent = appTheme
        failedAppTheme = null
        reduce(TvAppearanceChange.ThemeStarted)
        appThemeWrites.trySend(AppThemeWrite(appTheme, appThemeGeneration))
    }

    private fun setTileSize(tileSize: TileSizeId) {
        if (!state.value.isSavingTileSize && tileSize == state.value.tileSize) return
        tileSizeGeneration += 1
        tileSizeIntent = tileSize
        failedTileSize = null
        reduce(TvAppearanceChange.TileStarted)
        tileSizeWrites.trySend(TileSizeWrite(tileSize, tileSizeGeneration))
    }

    private fun setImageQuality(imageQuality: ImageQuality) {
        if (!state.value.isSavingImageQuality && imageQuality == state.value.imageQuality) return
        imageQualityGeneration += 1
        imageQualityIntent = imageQuality
        failedImageQuality = null
        reduce(TvAppearanceChange.ImageStarted)
        imageQualityWrites.trySend(ImageQualityWrite(imageQuality, imageQualityGeneration))
    }

    private fun retryAppTheme() {
        failedAppTheme?.let(::setAppTheme)
    }

    private fun retryTileSize() {
        failedTileSize?.let(::setTileSize)
    }

    private fun retryImageQuality() {
        failedImageQuality?.let(::setImageQuality)
    }

    private suspend fun writeAppTheme(request: AppThemeWrite) {
        try {
            withContext(workDispatcher) { setAppThemeAction(request.value) }
            if (request.generation == appThemeGeneration) {
                appThemeIntent = null
                failedAppTheme = null
                reduce { TvAppearanceChange.ThemeSaved(observedAppTheme.value) }
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
                reduce { TvAppearanceChange.ThemeFailed(observedAppTheme.value) }
            }
        }
    }

    private suspend fun writeTileSize(request: TileSizeWrite) {
        try {
            withContext(workDispatcher) { setTileSizeAction(request.value) }
            if (request.generation == tileSizeGeneration) {
                tileSizeIntent = null
                failedTileSize = null
                reduce { TvAppearanceChange.TileSaved(observedTileSize.value) }
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
                reduce { TvAppearanceChange.TileFailed(observedTileSize.value) }
            }
        }
    }

    private suspend fun writeImageQuality(request: ImageQualityWrite) {
        try {
            withContext(workDispatcher) { setImageQualityAction(request.value) }
            if (request.generation == imageQualityGeneration) {
                imageQualityIntent = null
                failedImageQuality = null
                reduce { TvAppearanceChange.ImageSaved(observedImageQuality.value) }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            appearanceLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "appearance-write",
                    event = "failed",
                    operation = DiagnosticOperation.SetImageQuality,
                    throwable = exception,
                )
            }
            if (request.generation == imageQualityGeneration) {
                while (imageQualityWrites.tryReceive().isSuccess) {
                    // Recover from the observed store value before accepting another intent.
                }
                imageQualityIntent = null
                failedImageQuality = request.value
                reduce { TvAppearanceChange.ImageFailed(observedImageQuality.value) }
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

private data class ImageQualityWrite(
    val value: ImageQuality,
    val generation: Long,
)

private val appearanceLogger = diagnosticLogger(DiagnosticTag.TvSettingsPresenter)
