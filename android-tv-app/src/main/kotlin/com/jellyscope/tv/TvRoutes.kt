// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.ui.focus.TvFocusMemoryEntry
import com.jellyscope.tv.ui.focus.TvFocusPath
import com.jellyscope.tv.ui.focus.TvRouteEntryId
import com.jellyscope.tv.watchnext.EXTRA_ACCOUNT_PAYLOAD
import com.jellyscope.tv.watchnext.WatchNextContract
import com.jellyscope.ui.screen.player.PlayerLaunchOptions
import org.koin.android.ext.android.get
import java.io.Serializable

internal enum class TvRoute {
    Home,
    Settings,
    Detail,
    Grid,
    Library,
    FilteredLibrary,
    Find,
    Discover,
    Favorites,
    Downloads,
    DownloadDetail,
    Collection,
    Person,
    Series,
    Season,
    Player,
}

internal fun tvTopLevelViewModelKey(
    session: Session,
    routeKey: String,
): String = "tv-top-level-${session.serverId}-${session.userId}-$routeKey"

internal fun tvSeriesSeasonViewModelKey(
    session: Session,
    seriesId: String,
): String = "tv-series-season-${session.serverId}-${session.userId}-$seriesId"

internal fun tvDetailViewModelKey(
    session: Session,
    entryId: TvRouteEntryId,
    itemId: String,
): String = "tv-detail-${session.serverId}-${session.userId}-${entryId.value}-$itemId"

internal fun shouldRetainTvSeriesSeasonViewModel(
    route: String,
    history: TvRouteHistory,
): Boolean = route.isTvSeriesSeasonRoute() || history.entries.any { snapshot -> snapshot.route.isTvSeriesSeasonRoute() }

internal fun String.isTvSeriesSeasonRoute(): Boolean = this == TvRoute.Series.name || this == TvRoute.Season.name

internal data class TvRouteSnapshot(
    val route: String,
    val routeEntryId: TvRouteEntryId = TvRouteEntryId(0L),
    val focusPath: TvFocusPath? = null,
    val focusMemoryEntries: List<TvFocusMemoryEntry> = emptyList(),
    val detailItemId: String = "",
    val downloadDetailId: String = "",
    val collectionItemId: String = "",
    val collectionTitle: String? = null,
    val collectionOriginLibraryId: String? = null,
    val personItemId: String = "",
    val seriesItemId: String = "",
    val seasonItemId: String = "",
    val gridRow: String = "",
    val libraryParentId: String? = null,
    val libraryTitle: String? = null,
    val libraryCollectionType: String = "Other",
    val libraryFilterGenreId: String? = null,
    val libraryFilterStudioId: String? = null,
    val playerItemId: String = "",
    val playerMediaSourceId: String? = null,
    val playerInitialAudioStreamIndex: Int? = null,
    val playerInitialSubtitleStreamIndex: Int? = null,
    val playerInitialSubtitleAssetId: String? = null,
    val playerStartTicks: Long = 0L,
    val playerRouteKey: Int = 0,
    val playerQueueIds: List<String> = emptyList(),
    val playerOfflineDownloadId: String? = null,
    val playerOfflineRestartFromBeginning: Boolean = false,
) : Serializable

internal fun String?.toTvOfflineDownloadIdOrNull(): DownloadId? =
    this
        ?.takeIf { value -> value.isNotBlank() }
        ?.let { value -> runCatching { DownloadId(value) }.getOrNull() }

/** Immutable launch input retained while its player entry animates out. */
internal data class TvPlayerRoutePayload(
    val itemId: String,
    val options: PlayerLaunchOptions,
)

internal data class TvRouteRenderKey(
    val route: String,
    val entryId: TvRouteEntryId,
    val detailItemId: String = "",
    val downloadDetailId: String = "",
    val seriesItemId: String = "",
    val seasonItemId: String = "",
    val player: TvPlayerRoutePayload? = null,
) {
    /**
     * Identity for AnimatedContent's contentKey. seasonItemId is EXCLUDED — a
     * season tab switch mutates the payload of the SAME route entry and must
     * update the existing composition in place, never start a route transition.
     */
    fun contentIdentity(): TvRouteRenderKey = copy(seasonItemId = "")
}

internal fun retainedDetailEntryIds(
    route: String,
    activeEntryId: TvRouteEntryId,
    history: TvRouteHistory,
): Set<TvRouteEntryId> =
    buildSet {
        if (route == TvRoute.Detail.name) {
            add(activeEntryId)
        }
        history.entries
            .filter { snapshot -> snapshot.route == TvRoute.Detail.name }
            .forEach { snapshot -> add(snapshot.routeEntryId) }
    }

internal fun tvPresentationStateKey(
    baseKey: String,
    renderKey: TvRouteRenderKey,
    presentationEpoch: Long,
): String = "$baseKey-entry-${renderKey.entryId.value}-presentation-$presentationEpoch"

internal data class TvRouteHistory(
    val entries: List<TvRouteSnapshot> = emptyList(),
) : Serializable {
    fun push(snapshot: TvRouteSnapshot): TvRouteHistory = copy(entries = entries + snapshot)

    fun clear(): TvRouteHistory = TvRouteHistory()

    fun pop(): TvRoutePop? {
        val snapshot = entries.lastOrNull() ?: return null
        return TvRoutePop(
            snapshot = snapshot,
            history = copy(entries = entries.dropLast(1)),
        )
    }
}

internal data class TvRoutePop(
    val snapshot: TvRouteSnapshot,
    val history: TvRouteHistory,
)

internal class TvTopLevelViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

internal class TvSeriesSeasonViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

internal fun stringListSaver(): Saver<List<String>, ArrayList<String>> =
    Saver(
        save = { value -> ArrayList(value) },
        restore = { value -> value.toList() },
    )

internal fun Intent.watchNextPayload(): String? =
    getStringExtra(EXTRA_ACCOUNT_PAYLOAD)
        ?.let(WatchNextContract::parseAccountPayload)
        ?.let { payload ->
            WatchNextContract.accountPayload(
                accountIdentity = AccountIdentity(payload.serverId, payload.userId),
                itemId = payload.itemId,
            )
        }
