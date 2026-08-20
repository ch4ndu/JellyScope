// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.navigation

private const val NAV_TRANSITION_MS = 300
private const val SAME_ROUTE_FADE_MS = 200

// Drill-down screens slide up from non-drill parents, while same-route nested
// pushes crossfade so one detail page does not look like it dismissed another.
// Tab switches stay instant (NavHost defaults are None). Tween-based, so subject
// to the system animator duration scale (instant when the user has animations off).
internal val drillDownEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isSameRouteTransition()) {
        fadeIn(animationSpec = tween(SAME_ROUTE_FADE_MS))
    } else {
        slideInVertically(animationSpec = tween(NAV_TRANSITION_MS)) { fullHeight -> fullHeight }
    }
}
internal val drillDownExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isSameRouteTransition()) {
        fadeOut(animationSpec = tween(SAME_ROUTE_FADE_MS))
    } else {
        ExitTransition.None
    }
}
internal val drillDownPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isSameRouteTransition()) {
        fadeOut(animationSpec = tween(SAME_ROUTE_FADE_MS))
    } else {
        slideOutVertically(animationSpec = tween(NAV_TRANSITION_MS)) { fullHeight -> fullHeight }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isSameRouteTransition(): Boolean =
    initialState.destination.route == targetState.destination.route
