// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.desktop

import com.jellyscope.core.domain.model.AccountIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDetailDeepLinkOwnerTest {
    @Test
    fun acknowledgementAllowsTheSameItemToBeDeliveredAsANewEvent() {
        val owner = DesktopDetailDeepLinkOwner()

        owner.publish("item-1")
        val first = checkNotNull(owner.event.value)
        owner.acknowledge(first.eventId)
        owner.publish("item-1")
        val second = checkNotNull(owner.event.value)

        assertEquals("item-1", second.itemId)
        assertEquals(first.eventId + 1L, second.eventId)
    }

    @Test
    fun preLoginEventRemainsEligibleForTheFirstAccountBoundary() {
        val owner = DesktopDetailDeepLinkOwner("item-1")

        owner.updateBoundary(AccountIdentity("server-a", "user-a"), boundaryEpoch = 1L)

        assertEquals("item-1", owner.event.value?.itemId)
    }

    @Test
    fun logoutAndAccountSwitchDiscardIneligibleBoundEvents() {
        val accountA = AccountIdentity("server-a", "user-a")
        val accountB = AccountIdentity("server-b", "user-b")
        val initialOwner = DesktopDetailDeepLinkOwner("pre-login-item")

        initialOwner.updateBoundary(accountA, boundaryEpoch = 1L)
        assertEquals(accountA, initialOwner.event.value?.accountIdentity)
        assertEquals(1L, initialOwner.event.value?.boundaryEpoch)
        initialOwner.updateBoundary(null, null)
        initialOwner.updateBoundary(accountB, boundaryEpoch = 2L)
        assertNull(initialOwner.event.value)

        val owner = DesktopDetailDeepLinkOwner()

        owner.updateBoundary(accountA, boundaryEpoch = 1L)
        owner.publish("logout-item")
        owner.updateBoundary(null, null)
        assertNull(owner.event.value)

        owner.updateBoundary(accountA, boundaryEpoch = 2L)
        owner.publish("switch-item")
        owner.updateBoundary(accountB, boundaryEpoch = 3L)
        assertNull(owner.event.value)
    }
}
