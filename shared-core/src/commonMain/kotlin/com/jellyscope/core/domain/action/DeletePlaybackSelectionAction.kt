// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.domain.model.PlaybackSelectionKey

class DeletePlaybackSelectionAction(
    private val store: PlaybackSelectionStore,
) {
    suspend operator fun invoke(key: PlaybackSelectionKey) = store.delete(key)
}
