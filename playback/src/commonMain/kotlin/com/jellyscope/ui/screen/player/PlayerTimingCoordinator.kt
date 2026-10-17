// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.action.SavePlaybackTimingOffsetAction
import com.jellyscope.core.domain.model.PLAYBACK_TIMING_OFFSET_LIMIT_MS
import com.jellyscope.core.domain.model.PlaybackTimingKey
import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.model.PlaybackTimingOffset
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerTimingCommandResult
import com.jellyscope.core.domain.playback.PlayerTimingState
import com.jellyscope.core.domain.usecase.GetPlaybackTimingOffsetUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads, applies, and persists player timing offsets. */
internal class PlayerTimingCoordinator(
    private val controllerProvider: () -> PlayerController?,
    private val saveAction: SavePlaybackTimingOffsetAction?,
    private val getUseCase: GetPlaybackTimingOffsetUseCase?,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher,
    private val keyProvider: (PlaybackTimingKind) -> PlaybackTimingKey?,
    private val onTimingStateChanged: () -> Unit,
) {
    var timingState: PlayerTimingState = PlayerTimingState.Unsupported
        private set

    private var timingStateJob: Job? = null
    private var timingLoadJob: Job? = null

    fun observe() {
        timingStateJob?.cancel()
        val controller = controllerProvider()?.timingController
        timingState = controller?.timingState?.value?.normalized() ?: PlayerTimingState.Unsupported
        timingStateJob =
            controller?.let { timingController ->
                scope.launch {
                    timingController.timingState.collect { state ->
                        timingState = state.normalized()
                        onTimingStateChanged()
                    }
                }
            }
    }

    fun adjust(
        kind: PlaybackTimingKind,
        deltaMs: Long,
    ) {
        // Use the live value so rapid nudges accumulate.
        val timingController = controllerProvider()?.timingController ?: return
        val current = timingController.timingState.value.value(kind)
        if (!current.isSupported) return
        set(kind, current.offsetMs + deltaMs)
    }

    fun set(
        kind: PlaybackTimingKind,
        offsetMs: Long,
    ) {
        val timingController = controllerProvider()?.timingController ?: return
        if (!timingController.timingState.value
                .value(kind)
                .isSupported
        ) {
            return
        }
        // Persist the controller-applied value after capability clamping.
        val normalizedOffset =
            offsetMs.coerceIn(-PLAYBACK_TIMING_OFFSET_LIMIT_MS, PLAYBACK_TIMING_OFFSET_LIMIT_MS)
        if (timingController.setOffset(kind, normalizedOffset) != PlayerTimingCommandResult.Applied) return
        val appliedOffset =
            timingController.timingState.value
                .value(kind)
                .offsetMs
        keyProvider(kind)?.let { key ->
            saveAction?.save(PlaybackTimingOffset(key = key, offsetMs = appliedOffset))
        }
    }

    suspend fun load() {
        val timingController = controllerProvider()?.timingController ?: return
        // Load before prepare so the initial native configuration is correct.
        PlaybackTimingKind.entries.forEach { kind ->
            val key = keyProvider(kind) ?: return@forEach
            if (!timingController.timingState.value
                    .value(kind)
                    .isSupported
            ) {
                return@forEach
            }
            val stored =
                try {
                    withContext(workDispatcher) {
                        getUseCase?.invoke(key)?.normalized()?.offsetMs
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Throwable) {
                    null
                }
            timingController.setOffset(kind, stored ?: 0L)
        }
    }

    fun reloadForCurrentTracks() {
        timingLoadJob?.cancel()
        timingLoadJob =
            scope.launch {
                load()
                onTimingStateChanged()
            }
    }

    fun cancelLoad() {
        timingLoadJob?.cancel()
        timingLoadJob = null
    }

    fun dispose() {
        timingStateJob?.cancel()
        timingLoadJob?.cancel()
        saveAction?.drainLatest()
    }
}
