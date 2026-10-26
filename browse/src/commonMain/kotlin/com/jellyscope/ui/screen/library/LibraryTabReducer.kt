// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import com.jellyscope.core.domain.model.Library

internal sealed interface LibraryTabChange {
    data object LoadStarted : LibraryTabChange

    data object LoadFailed : LibraryTabChange

    data class Loaded(
        val libraries: List<Library>,
        val selectedLibraryId: String?,
    ) : LibraryTabChange

    data class LibrarySelected(
        val libraryId: String,
    ) : LibraryTabChange
}

internal fun reduceLibraryTab(
    current: LibraryTabUiState,
    change: LibraryTabChange,
): LibraryTabUiState =
    when (change) {
        LibraryTabChange.LoadStarted -> LibraryTabUiState.Loading
        LibraryTabChange.LoadFailed -> LibraryTabUiState.Error
        is LibraryTabChange.Loaded -> LibraryTabUiState.Loaded(change.libraries, change.selectedLibraryId)
        is LibraryTabChange.LibrarySelected ->
            (current as? LibraryTabUiState.Loaded)?.copy(selectedLibraryId = change.libraryId) ?: current
    }
