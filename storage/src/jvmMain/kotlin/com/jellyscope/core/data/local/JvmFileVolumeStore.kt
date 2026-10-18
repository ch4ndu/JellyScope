// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.playback.DEFAULT_VOLUME_PERCENT
import com.jellyscope.core.domain.playback.MAX_VOLUME_PERCENT
import com.jellyscope.core.domain.playback.MIN_VOLUME_PERCENT
import com.jellyscope.core.domain.playback.PlayerVolumeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal class JvmFileVolumeStore(
    private val preferences: JvmFilePreferencesStore,
    writerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    monotonicTimeNanos: () -> Long = System::nanoTime,
    private val writeState: suspend (PlayerVolumeState) -> Unit = { state ->
        preferences.writeStrings(
            mapOf(
                VOLUME_PERCENT_KEY to state.volumePercent.toString(),
                VOLUME_MUTED_KEY to state.muted.toString(),
            ),
        )
    },
) : DesktopPlayerVolumeStore {
    private val writer: SerializedLatestValueWriter<PlayerVolumeState> =
        SerializedLatestValueWriter(
            scope = writerScope,
            monotonicTimeNanos = monotonicTimeNanos,
            write = { state ->
                try {
                    writeState(state)
                } catch (throwable: Throwable) {
                    lastWriteFailed = true
                    throw throwable
                }
            },
        )

    // The latest submitted value, so a later controller in the same process
    // restores what the user just set even while the serialized disk write is
    // still waiting for quiet or in flight (disk is only the cold-start source
    // of truth).
    @Volatile
    private var latestState: PlayerVolumeState? = null

    // Re-arms one retry after a failed write. Duplicate suppression is what stops
    // a held key at the 0/100 clamp from starving the write, but on its own it
    // would also discard the *retry* of a value whose write just failed, leaving
    // the older value on disk across a cold start. Suppression therefore means
    // "this value is already accounted for", which a failure revokes.
    // Cleared on hand-off rather than on success, so repeats arriving while a
    // failed value is still pending stay suppressed instead of restarting the
    // interval on every keystroke.
    @Volatile
    private var lastWriteFailed: Boolean = false

    override val restoredState: PlayerVolumeState
        get() =
            latestState ?: PlayerVolumeState(
                volumePercent =
                    preferences
                        .readString(VOLUME_PERCENT_KEY)
                        ?.toIntOrNull()
                        ?.coerceIn(MIN_VOLUME_PERCENT, MAX_VOLUME_PERCENT)
                        ?: DEFAULT_VOLUME_PERCENT,
                muted = preferences.readString(VOLUME_MUTED_KEY)?.toBooleanStrictOrNull() ?: false,
            )

    override fun submit(state: PlayerVolumeState) {
        val shouldWrite = latestState != state || lastWriteFailed
        latestState = state
        if (shouldWrite) {
            lastWriteFailed = false
            writer.submit(state)
        }
    }
}

private const val VOLUME_PERCENT_KEY = "player_volume.percent"
private const val VOLUME_MUTED_KEY = "player_volume.muted"
