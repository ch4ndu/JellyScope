// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui.focus

import java.io.Serializable

@JvmInline
value class TvRouteEntryId(
    val value: Long,
) : Serializable

enum class TvFocusTargetKind : Serializable {
    Item,
    ViewAll,
    Action,
    Tab,
    Loading,
    Retry,
    Slot,
}

data class TvFocusPath(
    val scopes: List<String>,
    val targetKind: TvFocusTargetKind,
    val targetKey: String,
    val fallbackIndex: Int = 0,
) : Serializable {
    val scopeKey: String
        get() = scopes.joinToString(SCOPE_SEPARATOR)

    companion object {
        private const val SCOPE_SEPARATOR = "/"
    }
}

data class TvFocusMemoryEntry(
    val scopeKey: String,
    val childKey: String,
) : Serializable

data class TvFocusMemoryState(
    val entries: List<TvFocusMemoryEntry> = emptyList(),
) : Serializable {
    fun childFor(scopeKey: String): String? = entries.lastOrNull { entry -> entry.scopeKey == scopeKey }?.childKey

    fun record(
        scopeKey: String,
        childKey: String,
    ): TvFocusMemoryState =
        copy(
            entries =
                entries.filterNot { entry -> entry.scopeKey == scopeKey } +
                    TvFocusMemoryEntry(scopeKey, childKey),
        )

    fun clearScope(scopeKey: String): TvFocusMemoryState = copy(entries = entries.filterNot { entry -> entry.scopeKey == scopeKey })
}

enum class TvFocusRestoreStatus : Serializable {
    PendingRoute,
    PendingTransition,
    PendingGroup,
    DeferredContent,
    Resolving,
}

data class TvFocusRestoreTransaction(
    val routeEntryId: TvRouteEntryId,
    val path: TvFocusPath,
    val token: Long,
    val status: TvFocusRestoreStatus = TvFocusRestoreStatus.PendingRoute,
) : Serializable

fun TvFocusRestoreTransaction.acceptsFocusedChild(
    scopes: List<String>,
    childKey: String,
    semanticIndex: Int,
    targetKind: TvFocusTargetKind,
): Boolean =
    path.scopes == scopes &&
        path.targetKind == targetKind &&
        (path.targetKey == childKey || path.fallbackIndex == semanticIndex)

enum class TvFocusContentReadiness {
    Loading,
    Ready,
    TerminalEmpty,
    TerminalError,
}

sealed interface TvFocusResolution {
    data class Exact(
        val index: Int,
    ) : TvFocusResolution

    data class Fallback(
        val index: Int,
    ) : TvFocusResolution

    data object Deferred : TvFocusResolution

    data object Unavailable : TvFocusResolution
}

object TvFocusResolver {
    fun resolveItem(
        targetKey: String,
        fallbackIndex: Int,
        itemKeys: List<String>,
        readiness: TvFocusContentReadiness,
        hasMore: Boolean,
    ): TvFocusResolution {
        val exactIndex = itemKeys.indexOf(targetKey)
        if (exactIndex >= 0) {
            return TvFocusResolution.Exact(exactIndex)
        }
        if (readiness == TvFocusContentReadiness.Loading) {
            return TvFocusResolution.Deferred
        }
        if (hasMore && fallbackIndex >= itemKeys.size) {
            return TvFocusResolution.Deferred
        }
        if (itemKeys.isEmpty()) {
            return TvFocusResolution.Unavailable
        }
        return TvFocusResolution.Fallback(fallbackIndex.coerceIn(itemKeys.indices))
    }
}
