// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Replays settled Stop reports so a detail route opened before server settlement
 * can refresh without depending on a player instance.
 */
data class SettlementKey(
    val serverId: String,
    val userId: String,
    val itemId: String,
)

data class PlaybackStopSettlement(
    val key: SettlementKey,
    val sequence: Long,
)

class PlaybackStopSettlementRegistry {
    private val mutex = Mutex()
    private val settlements = MutableStateFlow<Map<SettlementKey, PlaybackStopSettlement>>(emptyMap())
    private var nextSequence = 0L

    /** Uses an app-global sequence because each player queue starts its generation at zero. */
    suspend fun publish(key: SettlementKey): PlaybackStopSettlement =
        mutex.withLock {
            val settlement = PlaybackStopSettlement(key = key, sequence = ++nextSequence)
            settlements.value = settlements.value + (key to settlement)
            settlement
        }

    fun observe(key: SettlementKey): Flow<PlaybackStopSettlement> =
        settlements
            .mapNotNull { settlements -> settlements[key] }
            .distinctUntilChanged()

    /** Returns each account item's latest settlement in deterministic sequence order. */
    fun observe(
        serverId: String,
        userId: String,
    ): Flow<List<PlaybackStopSettlement>> =
        settlements
            .map { settlements ->
                settlements.values
                    .filter { settlement ->
                        settlement.key.serverId == serverId && settlement.key.userId == userId
                    }.sortedBy { settlement -> settlement.sequence }
            }.distinctUntilChanged()
}
