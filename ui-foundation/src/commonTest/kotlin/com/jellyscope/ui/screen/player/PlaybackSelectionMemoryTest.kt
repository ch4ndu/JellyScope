// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSelectionMemoryTest {
    @Test
    fun identicalItemIdsAreIsolatedByAccount() {
        val first = AccountIdentity("server-1", "user-1")
        val second = AccountIdentity("server-1", "user-2")
        val memory = PlaybackSelectionMemory()
        memory.remember(first, "item-1", PlaybackSelection(audioStreamIndex = 1))
        memory.remember(second, "item-1", PlaybackSelection(audioStreamIndex = 2))

        assertEquals(1, memory.selectionFor(first, "item-1")?.audioStreamIndex)
        assertEquals(2, memory.selectionFor(second, "item-1")?.audioStreamIndex)
    }

    @Test
    fun accountClearPreservesSiblingUser() =
        runTest {
            val first = AccountIdentity("server-1", "user-1")
            val second = AccountIdentity("server-1", "user-2")
            val memory = PlaybackSelectionMemory()
            memory.remember(first, "item-1", PlaybackSelection(audioStreamIndex = 1))
            memory.remember(second, "item-1", PlaybackSelection(audioStreamIndex = 2))

            memory.clearAccount(first)

            assertNull(memory.selectionFor(first, "item-1"))
            assertEquals(2, memory.selectionFor(second, "item-1")?.audioStreamIndex)
        }

    @Test
    fun snapshotForCopiesOnlyRequestedAccountAndItems() {
        val first = AccountIdentity("server-1", "user-1")
        val second = AccountIdentity("server-1", "user-2")
        val memory = PlaybackSelectionMemory()
        val itemSelection = PlaybackSelection(audioStreamIndex = 1)
        val sourceSelection = PlaybackSelection(audioStreamIndex = 2)
        memory.remember(first, "item-1", itemSelection)
        memory.remember(first, "item-1", "source-1", sourceSelection)
        memory.remember(first, "item-2", PlaybackSelection(audioStreamIndex = 3))
        memory.remember(second, "item-1", PlaybackSelection(audioStreamIndex = 4))

        val snapshot = memory.snapshotFor(first, listOf("item-1"))

        assertEquals(mapOf("item-1" to itemSelection), snapshot.itemSelections)
        assertEquals(mapOf(("item-1" to "source-1") to sourceSelection), snapshot.sourceSelections)
    }
}
