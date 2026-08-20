// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import com.jellyscope.tv.ui.focus.TvFocusCoordinator
import com.jellyscope.tv.ui.focus.TvFocusPath
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.TvRouteEntryId
import com.jellyscope.tv.ui.focus.acceptsFocusedChild
import com.jellyscope.ui.focus.FocusRestoreRequest
import com.jellyscope.ui.screen.detail.DetailFocusBridge

internal class TvRouteEntryDetailFocusBridge(
    private val focusCoordinator: TvFocusCoordinator,
    private val routeEntryId: TvRouteEntryId,
) : DetailFocusBridge {
    override fun restoredChild(scopeKey: String): String? = if (isActiveEntry()) focusCoordinator.activeMemory.childFor(scopeKey) else null

    // Pending restore suppresses autofocus before its target is ready.
    override fun restorePending(scopeKey: String): Boolean =
        focusCoordinator.pendingRestore
            ?.takeIf { restore -> isActiveEntry() && restore.routeEntryId == routeEntryId }
            ?.path
            ?.scopeKey == scopeKey

    override fun restoreRequest(scopeKey: String): FocusRestoreRequest? =
        focusCoordinator.readyRestore
            ?.takeIf { restore -> isActiveEntry() && restore.routeEntryId == routeEntryId }
            ?.takeIf { restore -> restore.path.scopeKey == scopeKey }
            ?.let { restore ->
                FocusRestoreRequest(
                    key = restore.path.targetKey,
                    fallbackSemanticIndex = restore.path.fallbackIndex,
                )
            }

    override fun onChildFocused(
        scopeKey: String,
        childKey: String,
        semanticIndex: Int,
    ) {
        if (!isActiveEntry()) return
        val path =
            TvFocusPath(
                scopes = scopeKey.split('/'),
                targetKind = TvFocusTargetKind.Item,
                targetKey = childKey,
                fallbackIndex = semanticIndex,
            )
        focusCoordinator.recordFocused(path)
        focusCoordinator.readyRestore
            ?.takeIf { restore -> restore.routeEntryId == routeEntryId }
            ?.let { restore ->
                if (
                    restore.acceptsFocusedChild(
                        path.scopes,
                        childKey,
                        semanticIndex,
                        TvFocusTargetKind.Item,
                    )
                ) {
                    focusCoordinator.resolveRestore(restore.token, path)
                }
            }
    }

    private fun isActiveEntry(): Boolean = focusCoordinator.activeRouteEntryId == routeEntryId
}
