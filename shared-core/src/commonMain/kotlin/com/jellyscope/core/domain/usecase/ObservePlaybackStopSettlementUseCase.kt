// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.playback.PlaybackStopSettlement
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import com.jellyscope.core.playback.SettlementKey
import kotlinx.coroutines.flow.Flow

class ObservePlaybackStopSettlementUseCase(
    private val registry: PlaybackStopSettlementRegistry,
) {
    operator fun invoke(key: SettlementKey): Flow<PlaybackStopSettlement> = registry.observe(key)

    operator fun invoke(
        serverId: String,
        userId: String,
    ): Flow<List<PlaybackStopSettlement>> = registry.observe(serverId, userId)
}
