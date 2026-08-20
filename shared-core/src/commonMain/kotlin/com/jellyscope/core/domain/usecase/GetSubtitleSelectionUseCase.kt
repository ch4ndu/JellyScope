// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.SubtitleSelectionKey
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent

class GetSubtitleSelectionUseCase(
    private val store: SubtitleSelectionStore,
) {
    suspend operator fun invoke(key: SubtitleSelectionKey): SubtitleSelectionIntent? = store.get(key)
}
