// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.repository.MediaRepository

class SetItemPlayedAction(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        itemId: String,
        played: Boolean,
    ) = mediaRepository.setPlayed(itemId = itemId, played = played)
}
