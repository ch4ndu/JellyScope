// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.ui.TvRailDestination
import org.koin.android.ext.android.get

internal fun isTvRailRoute(
    route: String,
    enableContentDownloading: Boolean = true,
): Boolean =
    route == TvRoute.Home.name ||
        route == TvRoute.Discover.name ||
        route == TvRoute.Find.name ||
        route == TvRoute.Favorites.name ||
        (route == TvRoute.Downloads.name && enableContentDownloading) ||
        route == TvRoute.Library.name ||
        route == TvRoute.Settings.name

internal fun selectedTvRailDestination(
    route: String,
    enableContentDownloading: Boolean = true,
): TvRailDestination? =
    when (route) {
        TvRoute.Home.name -> TvRailDestination.Home
        TvRoute.Discover.name -> TvRailDestination.Discover
        TvRoute.Find.name -> TvRailDestination.Find
        TvRoute.Favorites.name -> TvRailDestination.Favorites
        TvRoute.Downloads.name -> TvRailDestination.Downloads.takeIf { enableContentDownloading }
        TvRoute.Settings.name -> TvRailDestination.Settings
        else -> null
    }

internal fun isTvDownloadsVisible(session: Session): Boolean = session.enableContentDownloading
