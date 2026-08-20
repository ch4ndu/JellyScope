// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.jellyscope.ui.focus.FocusRestoreRequest
import com.jellyscope.ui.focus.FocusRestoreTarget
import com.jellyscope.ui.focus.resolveFocusRestoreTarget
import kotlinx.coroutines.delay

/**
 * `FocusRequester.requestFocus()` returns `false` when focus did NOT move and only
 * THROWS when the requester is unattached to any node. The long-standing
 * `runCatching { requestFocus() }.isSuccess` idiom is therefore wrong: it reports
 * success whenever the node merely exists, even if focus never landed. This returns
 * the REAL result — did focus actually move here?
 */
fun FocusRequester.requestFocusSafely(): Boolean = runCatching { requestFocus() }.getOrDefault(false)

/**
 * A focus "container": a region that remembers its last-focused child and can route
 * focus back to it deterministically, with a caller-supplied fallback order, so focus
 * is never lost. Children register a [FocusRequester] under an opaque key while they
 * are composed; the container tracks which key last held focus.
 *
 * Containers nest: a parent container's child key can itself be another container's
 * [entryRequester], so "screen -> region -> row -> tile" is just recursion. Pure and
 * platform-agnostic; the [Modifier.detailFocusContainer]/[Modifier.detailFocusChild]
 * wrappers apply it only in D-pad mode and no-op on touch.
 */
@Stable
class DetailFocusContainer internal constructor(
    private val routeScopeKey: String? = null,
    private val routeBridge: DetailFocusBridge? = null,
) {
    private val children = LinkedHashMap<Any, FocusRequester>()

    var restoreHandoffActive by mutableStateOf(false)
        private set

    /** The child that most recently held focus. Consumed imperatively (entry/effects). */
    var lastFocusedKey: Any? = null
        private set

    /** Requester on the container node itself, so parents/siblings can route INTO it. */
    val entryRequester: FocusRequester = FocusRequester()

    fun register(
        key: Any,
        requester: FocusRequester,
    ) {
        children[key] = requester
    }

    fun unregister(key: Any) {
        children.remove(key)
    }

    fun onChildFocused(
        key: Any,
        semanticIndex: Int,
    ) {
        lastFocusedKey = key
        routeScopeKey?.let { scopeKey -> routeBridge?.onChildFocused(scopeKey, key.toString(), semanticIndex) }
    }

    fun restoreRequest(): FocusRestoreRequest? = routeScopeKey?.let { routeBridge?.restoreRequest(it) }

    fun restorePending(): Boolean = routeScopeKey?.let { routeBridge?.restorePending(it) } == true

    /** Seed the remembered child once (e.g. from a saveable id / VM-tracked selection). */
    fun seedLastFocusedKey(key: Any?) {
        if (lastFocusedKey == null && key != null) {
            lastFocusedKey = key
        }
    }

    fun isComposed(key: Any): Boolean = children.containsKey(key)

    /**
     * SYNCHRONOUS deterministic entry: try the remembered child, then each key in
     * [fallbackOrder] (e.g. the first visible child), landing on the first that is
     * currently composed AND actually takes focus. Returns whether focus landed.
     * onEnter is synchronous, so this only targets already-composed children — an
     * off-screen remembered child is handled by [focusKeyRevealing] from an effect.
     */
    fun requestEntry(fallbackOrder: List<Any> = emptyList()): Boolean {
        val ordered =
            buildList {
                lastFocusedKey?.let { add(it) }
                fallbackOrder.forEach { if (it !in this) add(it) }
            }
        for (key in ordered) {
            val requester = children[key] ?: continue
            if (requester.requestFocusSafely()) {
                lastFocusedKey = key
                return true
            }
        }
        return false
    }

    /**
     * Reveal a possibly-scrolled-off child then focus it. Uses [delay] (NOT
     * withFrameNanos, which stalls when the screen goes idle) to retry across a few
     * frames while the target composes after a scroll. Call from a LaunchedEffect,
     * never from onEnter.
     */
    suspend fun restoreFocus(
        request: FocusRestoreRequest,
        semanticKeys: List<String>,
        lazySlotIndex: (semanticIndex: Int) -> Int,
        revealCentered: suspend (lazySlotIndex: Int) -> Unit,
        contentReady: Boolean = true,
        retries: Int = 6,
    ): Boolean {
        val target = resolveFocusRestoreTarget(request, semanticKeys, contentReady)
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
                    lastFocusedKey = targetKey
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
                                lastFocusedKey = targetKey
                                focused = true
                            }
                        }
                    }
                }
            }
            if (focused) {
                onChildFocused(targetKey, semanticIndex)
                delay(RESTORE_RETRY_FRAME_MS)
            }
            return focused
        } finally {
            restoreHandoffActive = false
        }
    }
}

// withFrameNanos stalls when the frame clock is idle, permanently wedging the retry loop.
private const val RESTORE_RETRY_FRAME_MS = 16L

@Composable
fun rememberDetailFocusContainer(vararg resetKeys: Any?): DetailFocusContainer = remember(*resetKeys) { DetailFocusContainer() }

interface DetailFocusBridge {
    fun restoredChild(scopeKey: String): String?

    fun restorePending(scopeKey: String): Boolean

    fun restoreRequest(scopeKey: String): FocusRestoreRequest?

    fun onChildFocused(
        scopeKey: String,
        childKey: String,
        semanticIndex: Int,
    )
}

val LocalDetailFocusBridge = staticCompositionLocalOf<DetailFocusBridge?> { null }

@Composable
fun rememberRouteDetailFocusContainer(
    scopeKey: String,
    vararg resetKeys: Any?,
): DetailFocusContainer {
    val bridge = LocalDetailFocusBridge.current
    return remember(bridge, scopeKey, *resetKeys) {
        DetailFocusContainer(routeScopeKey = scopeKey, routeBridge = bridge).also { container ->
            container.seedLastFocusedKey(bridge?.restoredChild(scopeKey))
        }
    }
}

/**
 * Ungated focus-container modifier for TV-only screens (android-tv-app), which are
 * always in D-pad mode and don't set LocalDetailInteractionMode. Same behavior as
 * [detailFocusContainer] but always active.
 */
@Composable
fun Modifier.focusContainer(
    container: DetailFocusContainer,
    onEnterFallback: () -> List<Any> = { emptyList() },
): Modifier =
    this
        .focusRequester(container.entryRequester)
        .focusProperties { onEnter = { container.requestEntry(onEnterFallback()) } }
        .focusGroup()

/** Ungated focus-child modifier for TV-only screens (always active). */
@Composable
fun Modifier.focusChild(
    container: DetailFocusContainer,
    key: Any,
    semanticIndex: Int = 0,
): Modifier {
    val requester = remember(container, key) { FocusRequester() }
    DisposableEffect(container, key) {
        container.register(key, requester)
        onDispose { container.unregister(key) }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) container.onChildFocused(key, semanticIndex) }
}

/**
 * Marks a region as a focus container (D-pad only; no-op on touch). Focus entering it
 * is routed SYNCHRONOUSLY to the remembered child (or [onEnterFallback], evaluated at
 * entry time), and the group keeps focus internal.
 */
@Composable
internal fun Modifier.detailFocusContainer(
    container: DetailFocusContainer,
    onEnterFallback: () -> List<Any> = { emptyList() },
): Modifier =
    if (isDetailDpadMode()) {
        this
            .focusRequester(container.entryRequester)
            .focusProperties { onEnter = { container.requestEntry(onEnterFallback()) } }
            .focusGroup()
    } else {
        this
    }

/** Marks a focusable child of [container] under [key] (D-pad only; no-op on touch). */
@Composable
internal fun Modifier.detailFocusChild(
    container: DetailFocusContainer,
    key: Any,
    semanticIndex: Int,
): Modifier =
    if (isDetailDpadMode()) {
        val requester = remember(container, key) { FocusRequester() }
        DisposableEffect(container, key) {
            container.register(key, requester)
            onDispose { container.unregister(key) }
        }
        this
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) container.onChildFocused(key, semanticIndex) }
    } else {
        this
    }
