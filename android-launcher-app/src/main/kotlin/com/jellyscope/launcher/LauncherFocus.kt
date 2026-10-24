// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

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

/** Attached controls only; a rejected request remains pending until attachment. */
internal class LauncherFocus {
    private val attached = mutableMapOf<String, FocusRequester>()
    private var pending: String? = null
    private var focusedKey by mutableStateOf<String?>(null)
    private var remembered by mutableStateOf<String?>(null)
    private var header: LauncherFocus? = null
    private var headerKeys: Set<String> = emptySet()
    val entryRequester = FocusRequester()
    val hasFocus: Boolean get() = focusedKey != null
    val parentFocused: Boolean get() = header?.hasFocus == true

    // Login's existing restore effect also sees the active header, so resume
    // cannot pull focus back into a field while the gear/account owns it.
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

    fun awaitingTarget(keys: List<String>): Boolean = pending?.let { it in keys } == true

    fun request(key: String) {
        if (isParentTarget(key)) {
            pending = null
            header?.request(key)
            return
        }
        pending = key
        attempt(key)
    }

    fun requestEntry(fallbackKeys: List<String>): Boolean {
        val candidates = (listOfNotNull(pending, remembered).filter { it in fallbackKeys } + fallbackKeys).distinct()
        val accepted = candidates.any { key -> runCatching { attached[key]?.requestFocus() ?: false }.getOrDefault(false) }
        if (accepted) pending = null
        return accepted
    }

    fun enter(): Boolean = runCatching { entryRequester.requestFocus() }.getOrDefault(false) && hasFocus

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
        attached[key] = requester
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
        if (pending == key) pending = null
    }

    private fun attempt(key: String) {
        if (pending == key) {
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
        val requester = remember(focus, key) { FocusRequester() }
        DisposableEffect(focus, key) { onDispose { focus.detach(key) } }
        focusRequester(requester)
            .onGloballyPositioned { focus.attach(key, requester) }
            .onFocusChanged {
                if (it.isFocused) {
                    focus.focused(key)
                    onFocused()
                } else {
                    focus.blurred(key)
                }
            }
    }
