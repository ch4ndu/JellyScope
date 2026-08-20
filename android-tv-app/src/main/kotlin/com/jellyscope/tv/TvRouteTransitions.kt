// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.koin.android.ext.android.get

// Drill-down TV routes (detail family + player) slide up over the parent on
// open and slide back down on dismiss; top-level route changes are instant.
// Tween-based, so subject to the system animator scale (instant when the user
// has TV animations off).
private val TV_DRILL_ROUTES =
    setOf(
        TvRoute.Detail.name,
        TvRoute.Series.name,
        TvRoute.Season.name,
        TvRoute.Collection.name,
        TvRoute.Person.name,
        TvRoute.Grid.name,
        TvRoute.Player.name,
    )

internal const val TV_NAV_TRANSITION_MS = 300

internal fun AnimatedContentTransitionScope<TvRouteRenderKey>.tvRouteTransitionSpec(): ContentTransform {
    val toDrill = targetState.route in TV_DRILL_ROUTES
    val fromDrill = initialState.route in TV_DRILL_ROUTES
    return when {
        toDrill && !fromDrill ->
            slideInVertically(tween(TV_NAV_TRANSITION_MS)) { height -> height } togetherWith
                fadeOut(tween(TV_NAV_TRANSITION_MS))
        fromDrill && !toDrill ->
            (
                fadeIn(tween(TV_NAV_TRANSITION_MS)) togetherWith
                    slideOutVertically(tween(TV_NAV_TRANSITION_MS)) { height -> height }
            ).apply { targetContentZIndex = -1f }
        else -> EnterTransition.None togetherWith ExitTransition.None
    }
}
