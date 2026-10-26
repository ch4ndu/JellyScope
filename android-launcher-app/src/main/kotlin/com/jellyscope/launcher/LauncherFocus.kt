// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.util.Log
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo

/** Attached controls only; a rejected request remains pending until attachment. */
internal class LauncherFocus {
    private val attached = mutableMapOf<String, FocusRequester>()
    private var pending by mutableStateOf<String?>(null)
    private var focusedKey by mutableStateOf<String?>(null)
    private var remembered by mutableStateOf<String?>(null)
    private var header: LauncherFocus? = null
    private var headerKeys: Set<String> = emptySet()
    var enabled by mutableStateOf(true)
    val entryRequester = FocusRequester()
    val hasFocus: Boolean get() = focusedKey != null
    val parentFocused: Boolean get() = header?.hasFocus == true

    // Only Home content shares header restoration. Modal owners remain isolated.
    val current: String? get() = if (parentFocused) header?.current else remembered
    var homeRow by mutableStateOf("apps")
    var homeRowIndex by mutableStateOf(0)
    private val rows = mutableMapOf<String, Pair<String, Int>>()

    fun bindHeader(
        owner: LauncherFocus,
        keys: Set<String>,
    ) {
        header = owner
        headerKeys = keys
    }

    fun isParentTarget(key: String?): Boolean = key != null && key in headerKeys

    fun pendingIn(keys: List<String>): String? = pending?.takeIf { it in keys }

    fun awaitingTarget(keys: List<String>): Boolean = pendingIn(keys) != null

    fun clearPending() {
        pending = null
    }

    fun retainPending(keys: List<String>): String? {
        val removed = pending?.takeIf { it !in keys } ?: return null
        clearPending()
        return removed
    }

    fun retryPending() {
        pending?.let(::attempt)
    }

    fun request(key: String) {
        if (!enabled) return
        if (isParentTarget(key)) {
            pending = null
            header?.request(key)
            return
        }
        pending = key
        Log.i("Launcher", "stage=focus-handoff event=requested attached=${attached.containsKey(key)}")
        attempt(key)
    }

    fun requestEntry(fallbackKeys: List<String>): Boolean {
        if (!enabled) return false
        val candidates = (listOfNotNull(pending, remembered).filter { it in fallbackKeys } + fallbackKeys).distinct()
        val accepted = candidates.firstOrNull { key -> runCatching { attached[key]?.requestFocus() ?: false }.getOrDefault(false) }
        if (accepted != null && accepted == pending) pending = null
        return accepted != null
    }

    fun enter(): Boolean = enabled && runCatching { entryRequester.requestFocus() }.getOrDefault(false) && hasFocus

    fun rememberRow(
        row: String,
        id: String,
        index: Int,
        verticalIndex: Int? = null,
    ) {
        homeRow = row
        if (verticalIndex != null) homeRowIndex = verticalIndex
        rows[row] = id to index
    }

    fun rowTarget(
        row: String,
        ids: List<String>,
    ): String? {
        if (ids.isEmpty()) return null
        val previous = rows[row]
        return previous?.first?.takeIf { it in ids } ?: ids[(previous?.second ?: 0).coerceIn(ids.indices)]
    }

    fun retainRows(validRows: Set<String>) {
        rows.keys.retainAll(validRows)
    }

    fun attach(
        key: String,
        requester: FocusRequester,
    ) {
        if (attached[key] !== requester) attached[key] = requester
        attempt(key)
    }

    fun detach(key: String) {
        attached.remove(key)
        blurred(key)
    }

    fun blurred(key: String) {
        if (focusedKey == key) focusedKey = null
    }

    fun reset() {
        attached.clear()
        pending = null
        focusedKey = null
        remembered = null
    }

    fun focused(key: String) {
        focusedKey = key
        remembered = key
        if (pending == key) {
            pending = null
            Log.i("Launcher", "stage=focus-handoff event=applied")
        }
    }

    private fun attempt(key: String) {
        if (enabled && pending == key) {
            val accepted = runCatching { attached[key]?.requestFocus() ?: false }.getOrDefault(false)
            // A container's onEnter may redirect and clear this request itself.
            // Do not reinstate it just because the outer request was redirected.
            if (accepted && focusedKey == key) {
                pending = null
            }
        }
    }

    companion object {
        val saver =
            listSaver<LauncherFocus, String>(
                save = { focus ->
                    buildList {
                        add(focus.current.orEmpty())
                        add(focus.homeRow)
                        add("v2:${focus.homeRowIndex}")
                        focus.rows.forEach { (row, position) ->
                            add(row)
                            add(position.first)
                            add(position.second.toString())
                        }
                    }
                },
                restore = { saved ->
                    LauncherFocus().apply {
                        remembered = saved.getOrNull(0)?.takeIf(String::isNotEmpty)
                        homeRow = saved.getOrNull(1) ?: "apps"
                        val versioned = saved.getOrNull(2)?.startsWith("v2:") == true
                        homeRowIndex = if (versioned) saved[2].substringAfter(':').toIntOrNull()?.coerceAtLeast(0) ?: 0 else 0
                        saved.drop(if (versioned) 3 else 2).chunked(3).filter { it.size == 3 }.forEach { entry ->
                            rows[entry[0]] = entry[1] to (entry[2].toIntOrNull() ?: 0)
                        }
                    }
                },
            )
    }
}

internal fun Modifier.launcherFocusScope(
    focus: LauncherFocus,
    fallbackKeys: () -> List<String>,
): Modifier =
    focusRequester(focus.entryRequester)
        .focusProperties {
            onEnter = { if (!focus.requestEntry(fallbackKeys())) cancelFocusChange() }
        }.focusGroup()

internal fun Modifier.launcherFocus(
    focus: LauncherFocus,
    key: String,
    onFocused: () -> Unit = {},
): Modifier =
    composed {
        val windowInfo = LocalWindowInfo.current
        val requester = remember(focus, key) { FocusRequester() }
        DisposableEffect(focus, key) { onDispose { focus.detach(key) } }
        focusProperties { if (!focus.enabled) canFocus = false }
            .focusRequester(requester)
            .onGloballyPositioned { focus.attach(key, requester) }
            .onFocusChanged {
                if (it.isFocused && windowInfo.isWindowFocused) {
                    focus.focused(key)
                    onFocused()
                } else {
                    focus.blurred(key)
                }
            }
    }
