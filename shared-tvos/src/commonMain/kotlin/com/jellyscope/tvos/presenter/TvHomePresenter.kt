// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryRecommendationRequest
import com.jellyscope.core.domain.model.LibraryRecommendationSection
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetContinueWatchingUseCase
import com.jellyscope.core.domain.usecase.GetFavoritesUseCase
import com.jellyscope.core.domain.usecase.GetLibraryRecommendationSectionUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetRecentlyAddedUseCase
import com.jellyscope.core.domain.usecase.GetUserLibrariesUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TvHomeRowKind {
    ContinueWatching,
    NextUp,
    RecentlyAdded,
    Favorites,
    LatestInLibrary,
}

data class TvMediaRow(
    val kind: TvHomeRowKind,
    val items: List<TvMediaCard>,
    // Stable SwiftUI identity: per-library Latest rows share a kind, so lists
    // must never key on kind alone.
    val stableId: String = kind.name,
    val libraryName: String? = null,
)

data class TvHeroRow(
    val kind: TvHomeRowKind,
    val items: List<TvMediaCard>,
)

data class TvHomeState(
    val isLoading: Boolean = true,
    // Cinematic hero: Continue Watching when non-empty, else Recently Added
    // when no dedicated hero candidate exists. The hero's row is removed from
    // [rows] so the same items never appear twice.
    val hero: TvHeroRow? = null,
    // Rows load independently; empty/failed rows are simply absent so the
    // screen never shows a stranded empty shelf.
    val rows: List<TvMediaRow> = emptyList(),
    val error: TvErrorKind? = null,
)

class TvHomePresenter(
    private val session: Session,
    private val getContinueWatching: GetContinueWatchingUseCase,
    private val getNextUp: GetNextUpUseCase,
    private val getRecentlyAdded: GetRecentlyAddedUseCase,
    private val getFavorites: GetFavoritesUseCase,
    private val getUserLibraries: GetUserLibrariesUseCase,
    private val getLibraryRecommendationSection: GetLibraryRecommendationSectionUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvHomeState())
    val state: StateFlow<TvHomeState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun watchState(onChange: (TvHomeState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
        // A stale in-flight load must never overwrite a newer one's rows.
        loadJob?.cancel()
        _state.update { current -> current.copy(isLoading = true, error = null) }
        loadJob =
            scope.launch {
                val continueWatching = async { getContinueWatching() }
                val nextUp = async { getNextUp(includeResumable = false) }
                val recentlyAdded = async { getRecentlyAdded() }
                val favorites = async { getFavorites() }
                val latestRows = async { loadLatestRows() }

                // "Everything failed" is the error signal; a successful-but-
                // empty server is a valid empty home, not a failure.
                val allFailed =
                    listOf(
                        continueWatching.await(),
                        nextUp.await(),
                        recentlyAdded.await(),
                        favorites.await(),
                    ).all { result -> result.isFailure }

                val continueWatchingItems =
                    continueWatching
                        .await()
                        .getOrNull()
                        .orEmpty()
                        .take(HOME_ROW_CAP)
                val recentlyAddedItems =
                    recentlyAdded
                        .await()
                        .getOrNull()
                        .orEmpty()
                        .take(HOME_ROW_CAP)
                val heroKind =
                    when {
                        continueWatchingItems.isNotEmpty() -> TvHomeRowKind.ContinueWatching
                        recentlyAddedItems.isNotEmpty() -> TvHomeRowKind.RecentlyAdded
                        else -> null
                    }
                val hero =
                    heroKind?.let { kind ->
                        val heroItems =
                            withContext(dispatchers.work) {
                                (if (kind == TvHomeRowKind.ContinueWatching) continueWatchingItems else recentlyAddedItems)
                                    .map { item -> item.toTvMediaCard(session, imageUrlBuilder) }
                            }
                        TvHeroRow(
                            kind = kind,
                            items = heroItems,
                        )
                    }

                val shelfCandidates =
                    buildList {
                        if (heroKind != TvHomeRowKind.ContinueWatching) {
                            add(TvHomeRowKind.ContinueWatching to continueWatchingItems)
                        }
                        add(
                            TvHomeRowKind.NextUp to
                                nextUp
                                    .await()
                                    .getOrNull()
                                    .orEmpty()
                                    .take(HOME_ROW_CAP),
                        )
                        if (heroKind != TvHomeRowKind.RecentlyAdded) {
                            add(TvHomeRowKind.RecentlyAdded to recentlyAddedItems)
                        }
                        add(
                            TvHomeRowKind.Favorites to
                                favorites
                                    .await()
                                    .getOrNull()
                                    .orEmpty()
                                    .take(HOME_ROW_CAP),
                        )
                    }
                val shelfRows =
                    withContext(dispatchers.work) {
                        shelfCandidates.mapNotNull { (kind, items) ->
                            items
                                .takeIf { list -> list.isNotEmpty() }
                                ?.let { list ->
                                    TvMediaRow(
                                        kind = kind,
                                        items = list.map { item -> item.toTvMediaCard(session, imageUrlBuilder) },
                                    )
                                }
                        }
                    }
                val rows = shelfRows + latestRows.await()

                _state.update {
                    TvHomeState(
                        isLoading = false,
                        hero = hero,
                        rows = rows,
                        error = if (allFailed && hero == null && rows.isEmpty()) TvErrorKind.Network else null,
                    )
                }
            }
    }

    // Per-library Latest rows ride the RecentlyAdded recommendation path
    // (/Items/Latest), which returns new episodes for TV libraries — a plain
    // browse query restricted to Movies/Series would not.
    private suspend fun loadLatestRows(): List<TvMediaRow> {
        val libraries =
            getUserLibraries()
                .getOrNull()
                .orEmpty()
                .filter { library ->
                    library.collectionType == LibraryCollectionType.Movies ||
                        library.collectionType == LibraryCollectionType.TvShows
                }
        return libraries.mapNotNull { library ->
            val section =
                getLibraryRecommendationSection(
                    LibraryRecommendationRequest(
                        parentId = library.id,
                        collectionType = library.collectionType,
                        section = LibraryRecommendationSection.RecentlyAdded,
                        limit = HOME_ROW_CAP,
                    ),
                )
            withContext(dispatchers.work) {
                val items: List<MediaItem> =
                    section
                        .getOrNull()
                        .orEmpty()
                        .flatMap { row -> row.items }
                        .take(HOME_ROW_CAP)
                items
                    .takeIf { list -> list.isNotEmpty() }
                    ?.let { list ->
                        TvMediaRow(
                            kind = TvHomeRowKind.LatestInLibrary,
                            items = list.map { item -> item.toTvMediaCard(session, imageUrlBuilder) },
                            stableId = "latest:${library.id}",
                            libraryName = library.name,
                        )
                    }
            }
        }
    }
}

private const val HOME_ROW_CAP = 20
