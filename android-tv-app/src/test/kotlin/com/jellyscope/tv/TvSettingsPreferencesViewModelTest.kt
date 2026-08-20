// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals

class TvSettingsPreferencesViewModelTest {
    @Test
    fun preferenceChangesUseTheStateOwner() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val operations = FakeTvUiPreferenceOperations()
                val viewModel =
                    TvSettingsPreferencesViewModel(
                        observeFocusedCardZoom = ObserveTvFocusedCardZoomUseCase { operations.focusedCardZoomEnabled },
                        observeLibraryGridHero = ObserveTvLibraryGridHeroUseCase { operations.showLibraryGridHero },
                        setFocusedCardZoom = SetTvFocusedCardZoomAction { operations.focusedCardZoomEnabled.value = it },
                        setLibraryGridHero = SetTvLibraryGridHeroAction { operations.showLibraryGridHero.value = it },
                    )

                viewModel.setFocusedCardZoomEnabled(false)
                viewModel.setShowLibraryGridHero(false)
                advanceUntilIdle()

                assertEquals(false, operations.focusedCardZoomEnabled.value)
                assertEquals(false, operations.showLibraryGridHero.value)
                assertEquals(false, viewModel.focusedCardZoomEnabled.value)
                assertEquals(false, viewModel.showLibraryGridHero.value)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private class FakeTvUiPreferenceOperations {
    val focusedCardZoomEnabled = MutableStateFlow(true)
    val showLibraryGridHero = MutableStateFlow(true)
}
