// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import com.jellyscope.tv.ui.focus.TvFocusCoordinator
import com.jellyscope.tv.ui.focus.TvFocusMemoryState
import com.jellyscope.tv.ui.focus.TvFocusPath
import com.jellyscope.tv.ui.focus.TvFocusRestoreStatus
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvRouteEntryDetailFocusBridgeTest {
    @Test
    fun outgoingEntryCannotObserveOrConsumeParentRestore() {
        val coordinator = TvFocusCoordinator()
        val parentId = coordinator.allocateChildEntry()
        val savedPath =
            TvFocusPath(
                scopes = listOf("detail", "related:0"),
                targetKind = TvFocusTargetKind.Item,
                targetKey = "saved-item",
                fallbackIndex = 2,
            )
        coordinator.recordFocused(savedPath)
        val memory = coordinator.activeMemory
        val childId = coordinator.allocateChildEntry()
        coordinator.restoreParent(parentId, savedPath, memory)
        val outgoingBridge = TvRouteEntryDetailFocusBridge(coordinator, childId)

        assertNull(outgoingBridge.restoredChild(savedPath.scopeKey))
        assertFalse(outgoingBridge.restorePending(savedPath.scopeKey))
        assertNull(outgoingBridge.restoreRequest(savedPath.scopeKey))

        outgoingBridge.onChildFocused(savedPath.scopeKey, "other-item", semanticIndex = 2)

        assertEquals(savedPath, coordinator.activePath)
        assertTrue(coordinator.pendingRestore != null)
    }

    @Test
    fun activeEntryConsumesOnlyConfirmedRestore() {
        val coordinator = TvFocusCoordinator()
        val parentId = coordinator.allocateChildEntry()
        val savedPath =
            TvFocusPath(
                scopes = listOf("detail", "related:1"),
                targetKind = TvFocusTargetKind.Item,
                targetKey = "saved-item",
                fallbackIndex = 3,
            )
        val memory = TvFocusMemoryState().record(savedPath.scopeKey, savedPath.targetKey)
        coordinator.allocateChildEntry()
        coordinator.restoreParent(parentId, savedPath, memory)
        coordinator.updateRestoreStatus(TvFocusRestoreStatus.PendingGroup)
        val bridge = TvRouteEntryDetailFocusBridge(coordinator, parentId)

        assertEquals("saved-item", bridge.restoredChild(savedPath.scopeKey))
        assertTrue(bridge.restorePending(savedPath.scopeKey))
        assertEquals("saved-item", bridge.restoreRequest(savedPath.scopeKey)?.key)

        bridge.onChildFocused(savedPath.scopeKey, "fallback-item", semanticIndex = 3)

        assertNull(coordinator.pendingRestore)
        assertEquals("fallback-item", coordinator.activePath?.targetKey)
    }

    @Test
    fun seasonRestoreRequestsAreIsolatedBySeasonScope() {
        val coordinator = TvFocusCoordinator()
        val routeEntryId = coordinator.activeRouteEntryId
        val savedPath =
            TvFocusPath(
                scopes = listOf("season", "s1", "episodes"),
                targetKind = TvFocusTargetKind.Item,
                targetKey = "episode-1",
                fallbackIndex = 0,
            )
        coordinator.restoreParent(routeEntryId, savedPath, TvFocusMemoryState())
        coordinator.updateRestoreStatus(TvFocusRestoreStatus.PendingGroup)
        val bridge = TvRouteEntryDetailFocusBridge(coordinator, routeEntryId)

        assertNotNull(bridge.restoreRequest("season/s1/episodes"))
        assertNull(bridge.restoreRequest("season/s2/episodes"))
    }
}
