// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.SubtitleActivationTarget

internal data class PlayerInstallationAttempt(
    val launchGeneration: Long,
    val itemId: String,
    val operation: PlayerInstallationOperation,
)

internal enum class PlayerInstallationOperation {
    TimingRestore,
    Controller,
    BackendSwitch,
    Replan,
}

// Each nullable component has independent reset sites, including a held target without a queued seek.
internal data class PlayerSeekReplacementState(
    val targetMs: Long? = null,
    val stopPositionMs: Long? = null,
    val queuedTargetMs: Long? = null,
) {
    fun queueLatest(targetMs: Long): PlayerSeekReplacementState = copy(targetMs = targetMs, queuedTargetMs = targetMs)

    fun clearHold(): PlayerSeekReplacementState = copy(targetMs = null, stopPositionMs = null)
}

// A partial lineage reset retains both exact roots for the existing completion checks.
internal data class PlayerDisplacedRecoveryLineage(
    val isDisplacing: Boolean = false,
    val audioTarget: AudioActivationTarget? = null,
    val subtitleTarget: SubtitleActivationTarget? = null,
)
