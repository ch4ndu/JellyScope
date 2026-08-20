// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.jellyscope.tv.ui.focus.TvRouteEntryId

/**
 * Retains Detail ViewModels while their route entries remain on the TV stack.
 * Render leases keep outgoing AnimatedContent alive until Compose disposes it,
 * independently of transition duration.
 */
internal class TvDetailEntryStore(
    private val ownerFactory: () -> ViewModelStoreOwner = { TvDetailViewModelStoreOwner() },
) {
    private val owners = mutableMapOf<TvRouteEntryId, ViewModelStoreOwner>()
    private val renderedEntryCounts = mutableMapOf<TvRouteEntryId, Int>()
    private var retainedEntryIds = emptySet<TvRouteEntryId>()

    fun ownerFor(entryId: TvRouteEntryId): ViewModelStoreOwner = owners.getOrPut(entryId, ownerFactory)

    fun updateRetainedEntries(entryIds: Set<TvRouteEntryId>) {
        retainedEntryIds = entryIds
        pruneUnownedEntries()
    }

    fun onEntryRendered(entryId: TvRouteEntryId) {
        renderedEntryCounts[entryId] = renderedEntryCounts.getOrElse(entryId) { 0 } + 1
    }

    fun onEntryDisposed(entryId: TvRouteEntryId) {
        val remaining = renderedEntryCounts.getOrElse(entryId) { 0 } - 1
        if (remaining > 0) {
            renderedEntryCounts[entryId] = remaining
        } else {
            renderedEntryCounts.remove(entryId)
        }
        pruneUnownedEntries()
    }

    fun clear() {
        owners.values.forEach { owner -> owner.viewModelStore.clear() }
        owners.clear()
        renderedEntryCounts.clear()
        retainedEntryIds = emptySet()
    }

    internal fun contains(entryId: TvRouteEntryId): Boolean = entryId in owners

    private fun pruneUnownedEntries() {
        val removable =
            owners.keys.filter { entryId ->
                entryId !in retainedEntryIds && renderedEntryCounts.getOrElse(entryId) { 0 } == 0
            }
        removable.forEach { entryId -> owners.remove(entryId)?.viewModelStore?.clear() }
    }
}

private class TvDetailViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}
