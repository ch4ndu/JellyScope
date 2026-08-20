// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * Gives a TV route its own ViewModel scope, keyed by route identity. The
 * custom `when(route)` navigation otherwise attaches every koinViewModel to
 * the Activity store, so ViewModels (and their released players) would be
 * reused forever across visits. Leaving the route (or changing [key]) clears
 * the store, driving onCleared -> dispose -> player release exactly once.
 */
@Composable
fun TvRouteScope(
    key: Any?,
    content: @Composable () -> Unit,
) {
    val owner = remember(key) { TvRouteStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        content()
    }
}

private class TvRouteStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}
