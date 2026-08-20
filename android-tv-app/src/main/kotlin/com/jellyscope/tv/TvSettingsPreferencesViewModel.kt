// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

internal class TvSettingsPreferencesViewModel(
    observeFocusedCardZoom: ObserveTvFocusedCardZoomUseCase,
    observeLibraryGridHero: ObserveTvLibraryGridHeroUseCase,
    private val setFocusedCardZoom: SetTvFocusedCardZoomAction,
    private val setLibraryGridHero: SetTvLibraryGridHeroAction,
) : ViewModel() {
    val focusedCardZoomEnabled = observeFocusedCardZoom()
    val showLibraryGridHero = observeLibraryGridHero()

    fun setFocusedCardZoomEnabled(enabled: Boolean) {
        viewModelScope.launch { setFocusedCardZoom(enabled) }
    }

    fun setShowLibraryGridHero(enabled: Boolean) {
        viewModelScope.launch { setLibraryGridHero(enabled) }
    }
}
