// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui.focus

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvFocusCoordinatorTest {
    @Test
    fun siblingMemorySurvivesChildRouteRoundTrip() {
        val coordinator = TvFocusCoordinator()
        coordinator.recordFocused(path("favorites", "item-5"))
        coordinator.recordFocused(path("next-up", "item-3"))
        val parentId = coordinator.activeRouteEntryId
        val memory = coordinator.activeMemory
        val active = coordinator.activePath

        coordinator.allocateChildEntry()
        coordinator.restoreParent(parentId, active, memory)

        assertEquals("item-5", coordinator.activeMemory.childFor("home/favorites"))
        assertEquals("item-3", coordinator.activeMemory.childFor("home/next-up"))
        assertNotNull(coordinator.pendingRestore)
    }

    @Test
    fun staleTokenCannotConsumeRestore() {
        val coordinator = TvFocusCoordinator()
        val parentId = coordinator.activeRouteEntryId
        val target = path("favorites", "item-5")
        coordinator.restoreParent(parentId, target, TvFocusMemoryState())
        val pending = assertNotNull(coordinator.pendingRestore)

        assertTrue(!coordinator.resolveRestore(pending.token + 1, target))
        assertNotNull(coordinator.pendingRestore)
        coordinator.updateRestoreStatus(TvFocusRestoreStatus.PendingGroup)
        assertTrue(coordinator.resolveRestore(pending.token, target))
        assertNull(coordinator.pendingRestore)
    }

    @Test
    fun resolveRestoreRejectsUnsettledTransactionStatuses() {
        val coordinator = TvFocusCoordinator()
        val target = path("favorites", "item-5")
        coordinator.restoreParent(coordinator.activeRouteEntryId, target, TvFocusMemoryState())
        val pending = assertNotNull(coordinator.pendingRestore)

        assertTrue(!coordinator.resolveRestore(pending.token, target))
        assertNotNull(coordinator.pendingRestore)
        coordinator.updateRestoreStatus(TvFocusRestoreStatus.PendingTransition)
        assertTrue(!coordinator.resolveRestore(pending.token, target))
        assertNotNull(coordinator.pendingRestore)
        coordinator.updateRestoreStatus(TvFocusRestoreStatus.PendingGroup)
        assertTrue(coordinator.resolveRestore(pending.token, target))
        assertNull(coordinator.pendingRestore)
    }

    @Test
    fun resolveRestoreAcceptsReadyTransactionStatuses() {
        listOf(
            TvFocusRestoreStatus.Resolving,
            TvFocusRestoreStatus.DeferredContent,
        ).forEach { status ->
            val coordinator = TvFocusCoordinator()
            val target = path("favorites", "item-5")
            coordinator.restoreParent(coordinator.activeRouteEntryId, target, TvFocusMemoryState())
            val pending = assertNotNull(coordinator.pendingRestore)

            coordinator.updateRestoreStatus(status)
            assertTrue(coordinator.resolveRestore(pending.token, target))
            assertNull(coordinator.pendingRestore)
        }
    }

    @Test
    fun restoreIsNotReadyUntilTheRouteTransitionSettles() {
        val coordinator = TvFocusCoordinator()
        val target = path("favorites", "item-5")
        coordinator.restoreParent(coordinator.activeRouteEntryId, target, TvFocusMemoryState())

        assertNull(coordinator.readyRestore)
        coordinator.updateRestoreStatus(TvFocusRestoreStatus.PendingTransition)
        assertNull(coordinator.readyRestore)
        coordinator.updateRestoreStatus(TvFocusRestoreStatus.PendingGroup)
        assertNotNull(coordinator.readyRestore)
    }

    @Test
    fun restoreAcceptsOnlyTheExactKeyOrApprovedSemanticFallback() {
        val transaction =
            TvFocusRestoreTransaction(
                routeEntryId = TvRouteEntryId(1),
                path = path("favorites", "removed").copy(fallbackIndex = 2),
                token = 1,
            )

        assertTrue(
            !transaction.acceptsFocusedChild(listOf("home", "favorites"), "other", 1, TvFocusTargetKind.Item),
        )
        assertTrue(
            transaction.acceptsFocusedChild(listOf("home", "favorites"), "removed", 0, TvFocusTargetKind.Item),
        )
        assertTrue(
            transaction.acceptsFocusedChild(listOf("home", "favorites"), "replacement", 2, TvFocusTargetKind.Item),
        )
        assertTrue(
            !transaction.acceptsFocusedChild(listOf("home", "next-up"), "replacement", 2, TvFocusTargetKind.Item),
        )
        assertTrue(
            !transaction.acceptsFocusedChild(listOf("home", "favorites"), "view-all", 2, TvFocusTargetKind.ViewAll),
        )
        assertTrue(
            transaction.acceptsFocusedChild(listOf("home", "favorites"), "removed", 2, TvFocusTargetKind.Item),
        )
        assertTrue(
            !transaction.acceptsFocusedChild(listOf("home", "favorites"), "removed", 2, TvFocusTargetKind.ViewAll),
        )
    }

    @Test
    fun deliberateDirectionalKeyCancelsOnlyAfterInteractivity() {
        assertTrue(
            !shouldCancelTvFocusRestore(
                key = Key.DirectionRight,
                type = KeyEventType.KeyDown,
                interactive = false,
                hasPendingRestore = true,
            ),
        )
        assertTrue(
            shouldCancelTvFocusRestore(
                key = Key.DirectionRight,
                type = KeyEventType.KeyDown,
                interactive = true,
                hasPendingRestore = true,
            ),
        )
        assertTrue(
            !shouldCancelTvFocusRestore(
                key = Key.MediaPlay,
                type = KeyEventType.KeyDown,
                interactive = true,
                hasPendingRestore = true,
            ),
        )
    }

    @Test
    fun focusTrapCountIsBalancedAndNeverNegative() {
        val coordinator = TvFocusCoordinator()
        assertTrue(!coordinator.focusTrapActive)

        coordinator.pushFocusTrap()
        coordinator.pushFocusTrap()
        assertTrue(coordinator.focusTrapActive)

        coordinator.popFocusTrap()
        assertTrue(coordinator.focusTrapActive)
        coordinator.popFocusTrap()
        assertTrue(!coordinator.focusTrapActive)

        coordinator.popFocusTrap()
        assertEquals(0, coordinator.focusTrapCount)
    }

    private fun path(
        row: String,
        item: String,
    ): TvFocusPath =
        TvFocusPath(
            scopes = listOf("home", row),
            targetKind = TvFocusTargetKind.Item,
            targetKey = item,
        )
}
