// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.repository.MediaRepository

class SetItemFavoriteAction(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        itemId: String,
        favorite: Boolean,
    ) = mediaRepository.setFavorite(itemId = itemId, favorite = favorite)
}
