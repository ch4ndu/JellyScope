// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.PlaybackTimingStore
import com.jellyscope.core.domain.model.PlaybackTimingKey
import com.jellyscope.core.domain.model.PlaybackTimingOffset

class GetPlaybackTimingOffsetUseCase(
    private val store: PlaybackTimingStore,
) {
    suspend operator fun invoke(key: PlaybackTimingKey): PlaybackTimingOffset? = store.get(key)
}
