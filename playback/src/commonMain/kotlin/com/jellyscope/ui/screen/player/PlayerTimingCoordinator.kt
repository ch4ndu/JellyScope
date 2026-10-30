// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.action.SavePlaybackTimingOffsetAction
import com.jellyscope.core.domain.model.PLAYBACK_TIMING_OFFSET_LIMIT_MS
import com.jellyscope.core.domain.model.PlaybackTimingKey
import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.model.PlaybackTimingOffset
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerTimingCommandResult
import com.jellyscope.core.domain.playback.PlayerTimingController
import com.jellyscope.core.domain.playback.PlayerTimingState
import com.jellyscope.core.domain.usecase.GetPlaybackTimingOffsetUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads, applies, and persists player timing offsets for the installed controller. */
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
    private var loadGeneration = 0L

    // A manual command supersedes only its own kind's pending restore.
    private val commandRevisions = LongArray(PlaybackTimingKind.entries.size)

    fun observe() {
        timingStateJob?.cancel()
        val owner = controllerProvider()
        val controller = owner?.timingController
        timingState = controller?.timingState?.value?.normalized() ?: PlayerTimingState.Unsupported
        timingStateJob =
            controller?.let { timingController ->
                scope.launch {
                    timingController.timingState.collect { state ->
                        // Ignore an outgoing controller after replacement or release.
                        if (!ownsTiming(owner, timingController)) return@collect
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
        commandRevisions[kind.ordinal] += 1L
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

    /**
     * Restores stored offsets through the installed controller. Returns false when no controller is
     * installed or the request became obsolete; an installed backend without timing support succeeds.
     */
    suspend fun load(): Boolean {
        timingLoadJob?.cancel()
        timingLoadJob = null
        return restore(++loadGeneration)
    }

    private suspend fun restore(generation: Long): Boolean {
        val owner = controllerProvider() ?: return false
        val timingController = owner.timingController
        val keys = PlaybackTimingKind.entries.associateWith(keyProvider)
        val revisions = commandRevisions.copyOf()

        // Storage reads can outlive their request, track keys, or installed controller.
        fun isCurrent(): Boolean =
            generation == loadGeneration &&
                ownsTiming(owner, timingController) &&
                PlaybackTimingKind.entries.all { kind -> keyProvider(kind) == keys[kind] }
        if (!isCurrent()) return false
        if (timingController == null) return true
        // Load before prepare so the initial native configuration is correct.
        val restored = mutableMapOf<PlaybackTimingKind, Long>()
        PlaybackTimingKind.entries.forEach { kind ->
            val key = keys[kind] ?: return@forEach
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
            if (!isCurrent()) return false
            restored[kind] = stored ?: 0L
        }
        // Finish every read first: applying Audio may schedule a native reconfiguration.
        PlaybackTimingKind.entries.forEach { kind ->
            val offset = restored[kind] ?: return@forEach
            if (!isCurrent()) return false
            if (
                revisions[kind.ordinal] != commandRevisions[kind.ordinal] ||
                !timingController.timingState.value
                    .value(kind)
                    .isSupported
            ) {
                return@forEach
            }
            timingController.setOffset(kind, offset)
        }
        return isCurrent()
    }

    fun reloadForCurrentTracks() {
        timingLoadJob?.cancel()
        // Allocate before launch so a delayed start cannot outrank a newer direct load.
        val generation = ++loadGeneration
        timingLoadJob =
            scope.launch {
                if (restore(generation)) onTimingStateChanged()
            }
    }

    fun cancelLoad() {
        loadGeneration += 1L
        timingLoadJob?.cancel()
        timingLoadJob = null
    }

    fun dispose() {
        cancelLoad()
        timingStateJob?.cancel()
        saveAction?.drainLatest()
    }

    private fun ownsTiming(
        owner: PlayerController?,
        timingController: PlayerTimingController?,
    ): Boolean = owner != null && controllerProvider() === owner && owner.timingController === timingController
}
