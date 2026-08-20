// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.io.Serializable

data class TvFocusCoordinatorSavedState(
    val activeRouteEntryId: TvRouteEntryId,
    val activePath: TvFocusPath?,
    val activeMemory: TvFocusMemoryState,
    val pendingRestore: TvFocusRestoreTransaction?,
    val nextRouteEntryId: Long,
    val nextTransactionToken: Long,
    val presentationEpoch: Long,
) : Serializable

@Stable
class TvFocusCoordinator internal constructor(
    restored: TvFocusCoordinatorSavedState = initialTvFocusCoordinatorState(),
) {
    var activeRouteEntryId by mutableStateOf(restored.activeRouteEntryId)
        private set
    var activePath by mutableStateOf(restored.activePath)
        private set
    var activeMemory by mutableStateOf(restored.activeMemory)
        private set
    var pendingRestore by mutableStateOf(restored.pendingRestore)
        private set
    val readyRestore: TvFocusRestoreTransaction?
        get() =
            pendingRestore?.takeIf { restore ->
                restore.status != TvFocusRestoreStatus.PendingRoute &&
                    restore.status != TvFocusRestoreStatus.PendingTransition
            }
    var presentationEpoch by mutableLongStateOf(restored.presentationEpoch)
        private set
    private var nextRouteEntryId by mutableLongStateOf(restored.nextRouteEntryId)
    private var nextTransactionToken by mutableLongStateOf(restored.nextTransactionToken)

    fun recordFocused(path: TvFocusPath) {
        activePath = path
        var updatedMemory = activeMemory
        path.scopes.indices.drop(1).forEach { index ->
            val parentScope = path.scopes.take(index).joinToString("/")
            updatedMemory = updatedMemory.record(parentScope, path.scopes[index])
        }
        activeMemory = updatedMemory.record(path.scopeKey, path.targetKey)
    }

    fun clearScope(scopeKey: String) {
        activeMemory = activeMemory.clearScope(scopeKey)
    }

    fun allocateChildEntry(): TvRouteEntryId =
        TvRouteEntryId(nextRouteEntryId).also {
            nextRouteEntryId += 1
            activeRouteEntryId = it
            activePath = null
            activeMemory = TvFocusMemoryState()
            pendingRestore = null
        }

    fun restoreParent(
        routeEntryId: TvRouteEntryId,
        path: TvFocusPath?,
        memory: TvFocusMemoryState,
    ) {
        activeRouteEntryId = routeEntryId
        activePath = path
        activeMemory = memory
        pendingRestore =
            path?.let { target ->
                TvFocusRestoreTransaction(
                    routeEntryId = routeEntryId,
                    path = target,
                    token = nextTransactionToken++,
                )
            }
    }

    fun updateRestoreStatus(status: TvFocusRestoreStatus) {
        pendingRestore = pendingRestore?.copy(status = status)
    }

    fun resolveRestore(
        token: Long,
        focusedPath: TvFocusPath,
    ): Boolean {
        val pending = pendingRestore ?: return false
        if (pending.token != token || pending.routeEntryId != activeRouteEntryId) {
            return false
        }
        if (readyRestore == null) return false
        recordFocused(focusedPath)
        pendingRestore = null
        return true
    }

    fun cancelPendingRestore() {
        pendingRestore = null
    }

    // Transient (not saved): process recreation closes every overlay, so a
    // persisted count could only ever leak a stale trap.
    var focusTrapCount by mutableIntStateOf(0)
        private set
    val focusTrapActive: Boolean get() = focusTrapCount > 0

    fun pushFocusTrap() {
        focusTrapCount += 1
    }

    fun popFocusTrap() {
        focusTrapCount = (focusTrapCount - 1).coerceAtLeast(0)
    }

    fun cancelForUserInput(
        event: KeyEvent,
        interactive: Boolean,
    ): Boolean {
        if (!shouldCancelTvFocusRestore(
                key = event.key,
                type = event.type,
                interactive = interactive,
                hasPendingRestore = pendingRestore != null,
            )
        ) {
            return false
        }
        pendingRestore = null
        return true
    }

    fun resetTopLevel(): TvRouteEntryId {
        presentationEpoch += 1
        pendingRestore = null
        activePath = null
        activeMemory = TvFocusMemoryState()
        return allocateChildEntry()
    }

    internal fun save(): TvFocusCoordinatorSavedState =
        TvFocusCoordinatorSavedState(
            activeRouteEntryId = activeRouteEntryId,
            activePath = activePath,
            activeMemory = activeMemory,
            pendingRestore = pendingRestore,
            nextRouteEntryId = nextRouteEntryId,
            nextTransactionToken = nextTransactionToken,
            presentationEpoch = presentationEpoch,
        )
}

private val TV_FOCUS_RESTORE_CANCELLING_KEYS =
    setOf(
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionLeft,
        Key.DirectionRight,
        Key.DirectionCenter,
        Key.Enter,
        Key.Spacebar,
    )

internal fun shouldCancelTvFocusRestore(
    key: Key,
    type: KeyEventType,
    interactive: Boolean,
    hasPendingRestore: Boolean,
): Boolean =
    interactive &&
        hasPendingRestore &&
        type == KeyEventType.KeyDown &&
        key in TV_FOCUS_RESTORE_CANCELLING_KEYS

val LocalTvFocusCoordinator = staticCompositionLocalOf<TvFocusCoordinator?> { null }

/**
 * Marks the composition as a modal focus trap (picker/overlay) while present, so
 * the root key handler does not treat keys consumed by the trap as deliberate
 * user input that cancels a pending focus restore (ADR 0005 cancellation rule).
 */
@Composable
fun TvFocusTrapEffect() {
    val coordinator = LocalTvFocusCoordinator.current ?: return
    DisposableEffect(coordinator) {
        coordinator.pushFocusTrap()
        onDispose { coordinator.popFocusTrap() }
    }
}

@Composable
fun rememberTvFocusCoordinator(): TvFocusCoordinator = rememberSaveable(saver = TvFocusCoordinatorSaver) { TvFocusCoordinator() }

private val TvFocusCoordinatorSaver =
    Saver<TvFocusCoordinator, TvFocusCoordinatorSavedState>(
        save = { coordinator -> coordinator.save() },
        restore = ::TvFocusCoordinator,
    )

private fun initialTvFocusCoordinatorState(): TvFocusCoordinatorSavedState =
    TvFocusCoordinatorSavedState(
        activeRouteEntryId = TvRouteEntryId(0L),
        activePath = null,
        activeMemory = TvFocusMemoryState(),
        pendingRestore = null,
        nextRouteEntryId = 1L,
        nextTransactionToken = 1L,
        presentationEpoch = 0L,
    )
