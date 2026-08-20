// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui.focus

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import com.jellyscope.ui.focus.FocusRestoreRequest
import com.jellyscope.ui.focus.FocusRestoreTarget
import com.jellyscope.ui.focus.resolveFocusRestoreTarget
import kotlinx.coroutines.delay

@Stable
class TvFocusScopeNode internal constructor(
    val scopes: List<String>,
    private val coordinator: TvFocusCoordinator,
) {
    private val children = LinkedHashMap<String, FocusRequester>()
    var restoreHandoffActive by mutableStateOf(false)
        private set
    val entryRequester = FocusRequester()
    val scopeKey: String = scopes.joinToString("/")

    fun register(
        key: String,
        requester: FocusRequester,
    ) {
        children[key] = requester
    }

    fun unregister(
        key: String,
        requester: FocusRequester,
    ) {
        if (children[key] === requester) {
            children.remove(key)
        }
    }

    fun requestEntry(fallbackKeys: List<String>): Boolean {
        val remembered = coordinator.activeMemory.childFor(scopeKey)
        val candidates =
            buildList {
                remembered?.let(::add)
                fallbackKeys.forEach { key -> if (key !in this) add(key) }
            }
        return candidates.any { key -> children[key]?.requestFocusSafely() == true }
    }

    fun restoreRequest(): FocusRestoreRequest? =
        coordinator.readyRestore
            ?.takeIf { restore -> restore.path.scopes == scopes }
            ?.let { restore ->
                FocusRestoreRequest(
                    key = restore.path.targetKey,
                    fallbackSemanticIndex = restore.path.fallbackIndex,
                )
            }

    suspend fun restoreFocus(
        request: FocusRestoreRequest,
        semanticKeys: List<String>,
        lazySlotIndex: (semanticIndex: Int) -> Int,
        revealCentered: suspend (lazySlotIndex: Int) -> Unit,
        retries: Int = 6,
    ): Boolean {
        val restoreKind =
            coordinator.readyRestore
                ?.takeIf { restore -> restore.path.scopes == scopes }
                ?.path
                ?.targetKind
                ?: TvFocusTargetKind.Item
        val target = resolveFocusRestoreTarget(request, semanticKeys)
        val targetKey =
            when (target) {
                is FocusRestoreTarget.Exact -> target.key
                is FocusRestoreTarget.Fallback -> target.key
                FocusRestoreTarget.Deferred, FocusRestoreTarget.Unavailable -> return false
            }
        val semanticIndex =
            when (target) {
                is FocusRestoreTarget.Exact -> target.semanticIndex
                is FocusRestoreTarget.Fallback -> target.semanticIndex
                FocusRestoreTarget.Deferred, FocusRestoreTarget.Unavailable -> return false
            }
        var focused = false
        restoreHandoffActive = true
        try {
            children[targetKey]?.let { requester ->
                if (requester.requestFocusSafely()) {
                    focused = true
                }
            }
            if (!focused) {
                revealCentered(lazySlotIndex(semanticIndex))
                repeat(retries) {
                    if (!focused) {
                        delay(RESTORE_RETRY_FRAME_MS)
                        children[targetKey]?.let { requester ->
                            if (requester.requestFocusSafely()) {
                                focused = true
                            }
                        }
                    }
                }
            }
            if (focused) {
                onChildFocused(targetKey, restoreKind, semanticIndex)
                delay(RESTORE_RETRY_FRAME_MS)
            }
            return focused
        } finally {
            restoreHandoffActive = false
        }
    }

    fun onChildFocused(
        key: String,
        kind: TvFocusTargetKind,
        semanticIndex: Int,
    ) {
        val path = path(key, kind, semanticIndex)
        coordinator.recordFocused(path)
        coordinator.readyRestore
            ?.takeIf { restore -> restore.acceptsFocusedChild(scopes, key, semanticIndex, kind) }
            ?.let { restore -> coordinator.resolveRestore(restore.token, path) }
    }

    fun path(
        key: String,
        kind: TvFocusTargetKind,
        fallbackIndex: Int,
    ): TvFocusPath =
        TvFocusPath(
            scopes = scopes,
            targetKind = kind,
            targetKey = key,
            fallbackIndex = fallbackIndex,
        )
}

// withFrameNanos stalls when the frame clock is idle, permanently wedging the retry loop.
private const val RESTORE_RETRY_FRAME_MS = 16L

@Composable
fun rememberTvFocusScopeNode(scopes: List<String>): TvFocusScopeNode {
    val coordinator = requireNotNull(LocalTvFocusCoordinator.current) { "TV focus coordinator is not installed" }
    return remember(coordinator, scopes) { TvFocusScopeNode(scopes, coordinator) }
}

@Composable
fun TvFocusScopeNode.rememberChildRequester(key: String): FocusRequester {
    val requester = remember(this, key) { FocusRequester() }
    DisposableEffect(this, key, requester) {
        register(key, requester)
        onDispose { unregister(key, requester) }
    }
    return requester
}

@Composable
fun TvFocusScopeNode.rememberChildRequester(
    key: String,
    requester: FocusRequester,
): FocusRequester {
    DisposableEffect(this, key, requester) {
        register(key, requester)
        onDispose { unregister(key, requester) }
    }
    return requester
}

fun Modifier.tvFocusScope(
    node: TvFocusScopeNode,
    fallbackKeys: () -> List<String>,
): Modifier =
    this
        .focusRequester(node.entryRequester)
        .focusProperties { onEnter = { node.requestEntry(fallbackKeys()) } }
        .focusGroup()

private fun FocusRequester.requestFocusSafely(): Boolean = runCatching { requestFocus() }.getOrDefault(false)
