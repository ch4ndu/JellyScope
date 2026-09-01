// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackFirstVideoOutputState
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities

internal data class PlayerFirstVideoOutputState(
    val expectedPrepareEpoch: Long? = null,
    val debug: PlayerFirstVideoOutputDebug = PlayerFirstVideoOutputDebug(),
) {
    fun armForPrepare(
        prepareEpoch: Long?,
        capability: VideoOutputMeasurementCapabilities,
    ): PlayerFirstVideoOutputState =
        PlayerFirstVideoOutputState(
            expectedPrepareEpoch = prepareEpoch,
            debug =
                PlayerFirstVideoOutputDebug(
                    state =
                        if (capability.isSupported) {
                            PlaybackFirstVideoOutputState.Awaiting
                        } else {
                            PlaybackFirstVideoOutputState.Unsupported
                        },
                    evidence = capability.evidence,
                ),
        )

    fun acceptReliablePositiveObservation(
        observationPrepareEpoch: Long,
        isPresented: Boolean,
        observedAtMs: Long,
    ): PlayerFirstVideoOutputState {
        if (
            !isPresented ||
            observedAtMs < 0L ||
            debug.state == PlaybackFirstVideoOutputState.Unsupported ||
            observationPrepareEpoch != expectedPrepareEpoch
        ) {
            return this
        }
        return copy(debug = debug.copy(state = PlaybackFirstVideoOutputState.Observed))
    }

    fun markTimedOut(): PlayerFirstVideoOutputState =
        if (debug.state == PlaybackFirstVideoOutputState.Unsupported) {
            this
        } else {
            copy(debug = debug.copy(state = PlaybackFirstVideoOutputState.TimedOut))
        }
}
