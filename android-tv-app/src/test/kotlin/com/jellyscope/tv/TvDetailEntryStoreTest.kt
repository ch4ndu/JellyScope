// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.jellyscope.tv.ui.focus.TvRouteEntryId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TvDetailEntryStoreTest {
    @Test
    fun retainedParentSurvivesDisposalAndIsReused() {
        val store = TvDetailEntryStore()
        val parentId = TvRouteEntryId(1)
        val parentOwner = store.ownerFor(parentId)

        store.onEntryRendered(parentId)
        store.updateRetainedEntries(setOf(parentId))
        store.onEntryDisposed(parentId)

        assertTrue(store.contains(parentId))
        assertSame(parentOwner, store.ownerFor(parentId))
    }

    @Test
    fun outgoingEntryClearsOnlyAfterNavigationAndRenderRelease() {
        val owners = mutableListOf<TrackingOwner>()
        val store = TvDetailEntryStore { TrackingOwner().also(owners::add) }
        val childId = TvRouteEntryId(2)
        val owner = store.ownerFor(childId)
        ViewModelProvider(owner, trackingViewModelFactory(owners.single()))[TrackingViewModel::class.java]

        store.onEntryRendered(childId)
        store.updateRetainedEntries(emptySet())

        assertTrue(store.contains(childId))
        assertEquals(0, owners.single().clearedCount)

        store.onEntryDisposed(childId)

        assertFalse(store.contains(childId))
        assertEquals(1, owners.single().clearedCount)
    }

    @Test
    fun historicalOwnersPruneAndSessionClearDoesNotDoubleClear() {
        val owners = mutableListOf<TrackingOwner>()
        val store = TvDetailEntryStore { TrackingOwner().also(owners::add) }
        val parentId = TvRouteEntryId(3)
        val childId = TvRouteEntryId(4)
        store.ownerFor(parentId)
        store.ownerFor(childId)
        owners.forEach { owner -> ViewModelProvider(owner, trackingViewModelFactory(owner))[TrackingViewModel::class.java] }

        store.updateRetainedEntries(setOf(parentId, childId))
        store.updateRetainedEntries(setOf(parentId))
        store.clear()
        store.clear()

        assertEquals(listOf(1, 1), owners.map { owner -> owner.clearedCount })
    }
}

private class TrackingOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    var clearedCount = 0
}

private class TrackingViewModel(
    private val onClearedCallback: () -> Unit,
) : ViewModel() {
    override fun onCleared() {
        onClearedCallback()
    }
}

private fun trackingViewModelFactory(owner: TrackingOwner): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TrackingViewModel { owner.clearedCount += 1 } as T
    }
