// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.LibraryCollectionType

data class TvLibraryTile(
    val id: String,
    val name: String,
    val collectionType: LibraryCollectionType,
    val imageUrl: String?,
)

data class TvLibrariesState(
    val isLoading: Boolean = true,
    val libraries: List<TvLibraryTile> = emptyList(),
    val error: TvErrorKind? = null,
) {
    val isEmpty: Boolean
        get() = !isLoading && error == null && libraries.isEmpty()
}
