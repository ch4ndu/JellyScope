// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.SubtitleActivationTarget

internal data class TvInstallationAttempt(
    val launchGeneration: Long,
    val itemId: String,
    val operation: TvInstallationOperation,
)

internal enum class TvInstallationOperation {
    BackendSwitch,
    Replan,
}

// Nullable components remain independent: clearing a hold need not consume a queued seek.
internal data class TvSeekReplacementState(
    val targetMs: Long? = null,
    val stopPositionMs: Long? = null,
    val queuedTargetMs: Long? = null,
) {
    fun queueLatest(targetMs: Long): TvSeekReplacementState = copy(targetMs = targetMs, queuedTargetMs = targetMs)

    fun clearHold(): TvSeekReplacementState = copy(targetMs = null, stopPositionMs = null)
}

// Audio and SubtitleEncode roots can coexist and are qualified by their original request owners.
internal data class TvDisplacedRecoveryLineage(
    val isDisplacing: Boolean = false,
    val audioTarget: AudioActivationTarget? = null,
    val subtitleTarget: SubtitleActivationTarget? = null,
)
