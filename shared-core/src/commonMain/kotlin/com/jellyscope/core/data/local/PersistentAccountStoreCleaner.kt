// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity

/**
 * Explicitly resolved persistent cleanup membership. Runtime caches remain in
 * [ServerScopedStoreRegistry]; this aggregate owns durable account/server
 * state so cleanup does not depend on feature-store resolution order.
 */
class PersistentAccountStoreCleaner(
    private val sessionStore: SessionStore,
    private val playbackPreferencesStore: PlaybackPreferencesStore,
    private val playerBackendOverrideStore: PlayerBackendOverrideStore,
    private val recentSearchStore: RecentSearchStore,
    private val watchNextSyncStore: WatchNextSyncStore,
    private val subtitleSelectionStore: SubtitleSelectionStore,
    private val librarySortStore: LibrarySortStore,
    private val libraryViewPreferencesStore: LibraryViewPreferencesStore,
    private val gridSortStore: GridSortStore,
    private val playbackSelectionStore: PlaybackSelectionStore? = null,
    private val playbackTimingStore: PlaybackTimingStore? = null,
) {
    suspend fun clearAll() {
        val failures = mutableListOf<Throwable>()
        var credentialsCleared = false

        try {
            sessionStore.clearSession()
            credentialsCleared = true
        } catch (throwable: Throwable) {
            failures += throwable
        }

        if (credentialsCleared) {
            try {
                sessionStore.clearLogoutPending()
            } catch (throwable: Throwable) {
                failures += throwable
            }
        }

        val storeClears: List<suspend () -> Unit> =
            listOf(
                { playbackPreferencesStore.clearServerScoped() },
                { playerBackendOverrideStore.clearServerScoped() },
                { recentSearchStore.clearServerScoped() },
                { watchNextSyncStore.clearServerScoped() },
                { subtitleSelectionStore.clearServerScoped() },
                { librarySortStore.clearServerScoped() },
                { libraryViewPreferencesStore.clearServerScoped() },
                { gridSortStore.clearServerScoped() },
                { playbackSelectionStore?.clearServerScoped() },
                { playbackTimingStore?.clearServerScoped() },
            )
        storeClears.forEach { clearStore ->
            try {
                clearStore()
            } catch (throwable: Throwable) {
                failures += throwable
            }
        }

        throwIfCleanupFailed(failures)
    }

    suspend fun clearServer(serverId: String) {
        clearOperations(
            listOf(
                { sessionStore.clearServerScoped(serverId) },
                { playbackPreferencesStore.clearServerScoped(serverId) },
                { playerBackendOverrideStore.clearServerScoped(serverId) },
                { recentSearchStore.clearServerScoped(serverId) },
                { watchNextSyncStore.clearServerScoped(serverId) },
                { subtitleSelectionStore.clearServerScoped(serverId) },
                { librarySortStore.clearServerScoped(serverId) },
                { libraryViewPreferencesStore.clearServerScoped(serverId) },
                { gridSortStore.clearServerScoped(serverId) },
                { playbackSelectionStore?.clearServerScoped(serverId) },
                { playbackTimingStore?.clearServerScoped(serverId) },
            ),
        )
    }

    suspend fun clearAccount(accountIdentity: AccountIdentity) {
        clearOperations(
            listOf(
                { recentSearchStore.clearAccount(accountIdentity) },
                { watchNextSyncStore.clearAccount(accountIdentity) },
                { subtitleSelectionStore.clearAccount(accountIdentity) },
                { sessionStore.clearAccount(accountIdentity) },
                { playbackSelectionStore?.clearAccount(accountIdentity) },
                { playbackTimingStore?.clearAccount(accountIdentity) },
            ),
        )
    }

    private suspend fun clearOperations(operations: List<suspend () -> Unit>) {
        val failures = mutableListOf<Throwable>()
        operations.forEach { operation ->
            try {
                operation()
            } catch (throwable: Throwable) {
                appendCleanupFailures(failures, throwable)
            }
        }
        throwIfCleanupFailed(failures)
    }
}
