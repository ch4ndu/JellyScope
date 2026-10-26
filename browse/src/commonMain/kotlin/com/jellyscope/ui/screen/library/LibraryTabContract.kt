// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import com.jellyscope.core.domain.model.Library

sealed interface LibraryTabUiState {
    data object Loading : LibraryTabUiState

    data class Loaded(
        val libraries: List<Library>,
        val selectedLibraryId: String?,
    ) : LibraryTabUiState

    data object Error : LibraryTabUiState
}

sealed interface LibraryTabIntent {
    data object Retry : LibraryTabIntent

    data class SelectLibrary(
        val libraryId: String,
    ) : LibraryTabIntent
}
