// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi

internal fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

internal fun NavHostController.navigateToMediaItem(item: MediaCardUi) {
    when (item.kind) {
        MediaCardKind.Series -> navigate(Routes.series(item.id))
        MediaCardKind.Library ->
            navigate(
                Routes.library(
                    parentId = item.id,
                    title = item.title,
                    collectionType = item.libraryCollectionType ?: LibraryCollectionType.Other,
                ),
            )
        MediaCardKind.Movie,
        MediaCardKind.Episode,
        MediaCardKind.Other,
        -> navigate(Routes.detail(item.id))
    }
}

internal fun selectedTopLevelRoute(currentRoute: String?): String? =
    when (currentRoute) {
        Routes.Detail,
        Routes.Series,
        Routes.Season,
        Routes.Grid,
        -> Routes.Home
        Routes.Library,
        Routes.LibraryRoot,
        -> Routes.LibraryRoot
        Routes.Collection,
        Routes.Person,
        Routes.FilteredLibrary,
        -> Routes.Discover
        Routes.DownloadDetail -> Routes.Downloads
        Routes.Home,
        Routes.Discover,
        Routes.Find,
        Routes.Downloads,
        -> currentRoute
        else -> null
    }
