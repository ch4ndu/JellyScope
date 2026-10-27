// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

internal sealed interface TvSettingsPreferencesIntent {
    data class SetFocusedCardZoomEnabled(
        val enabled: Boolean,
    ) : TvSettingsPreferencesIntent

    data class SetShowLibraryGridHero(
        val enabled: Boolean,
    ) : TvSettingsPreferencesIntent
}

internal class TvSettingsPreferencesViewModel(
    observeFocusedCardZoom: ObserveTvFocusedCardZoomUseCase,
    observeLibraryGridHero: ObserveTvLibraryGridHeroUseCase,
    private val setFocusedCardZoom: SetTvFocusedCardZoomAction,
    private val setLibraryGridHero: SetTvLibraryGridHeroAction,
) : ViewModel() {
    val focusedCardZoomEnabled = observeFocusedCardZoom()
    val showLibraryGridHero = observeLibraryGridHero()

    fun accept(intent: TvSettingsPreferencesIntent) {
        viewModelScope.launch {
            when (intent) {
                is TvSettingsPreferencesIntent.SetFocusedCardZoomEnabled -> setFocusedCardZoom(intent.enabled)
                is TvSettingsPreferencesIntent.SetShowLibraryGridHero -> setLibraryGridHero(intent.enabled)
            }
        }
    }
}
