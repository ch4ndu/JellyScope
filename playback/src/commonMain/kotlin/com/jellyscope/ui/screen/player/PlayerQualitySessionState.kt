// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy

internal data class PlayerQualitySessionState(
    val policy: PlaybackQualityPolicy = PlaybackQualityPolicy.Auto,
    val maximumBitrateBps: Long? = null,
    val capOrigin: PlaybackQualityCapOrigin? = null,
    val isExplicitSessionChoice: Boolean = false,
) {
    fun inheritLaunchOrDefault(policy: PlaybackQualityPolicy): PlayerQualitySessionState {
        val normalized = policy.normalized()
        return PlayerQualitySessionState(
            policy = normalized,
            maximumBitrateBps = normalized.maxBitrateBps,
            capOrigin = PlaybackQualityCapOrigin.SettingsDefault.takeIf { normalized.maxBitrateBps != null },
        )
    }

    fun selectFixedOrOriginal(maximumBitrateBps: Long?): PlayerQualitySessionState {
        val selectedPolicy =
            maximumBitrateBps
                ?.let(PlaybackQualityPolicy::fixed)
                ?: PlaybackQualityPolicy.Original
        return PlayerQualitySessionState(
            policy = selectedPolicy,
            maximumBitrateBps = selectedPolicy.maxBitrateBps,
            capOrigin = PlaybackQualityCapOrigin.ExplicitSessionChoice.takeIf { maximumBitrateBps != null },
            isExplicitSessionChoice = true,
        )
    }

    fun selectAuto(): PlayerQualitySessionState =
        PlayerQualitySessionState(
            policy = PlaybackQualityPolicy.Auto,
            isExplicitSessionChoice = true,
        )

    fun retainRecoveredQuality(maximumBitrateBps: Long): PlayerQualitySessionState =
        PlayerQualitySessionState(
            policy = PlaybackQualityPolicy.fixed(maximumBitrateBps),
            maximumBitrateBps = maximumBitrateBps,
            capOrigin = PlaybackQualityCapOrigin.ExplicitSessionChoice,
            isExplicitSessionChoice = true,
        )

    fun recoverWithAutoCap(maximumBitrateBps: Long): PlayerQualitySessionState =
        copy(
            policy = PlaybackQualityPolicy.Auto,
            maximumBitrateBps = maximumBitrateBps,
            capOrigin = PlaybackQualityCapOrigin.AutoSessionRecovery,
        )

    fun retryUncappedAuto(): PlayerQualitySessionState = selectAuto()

    fun forOfflineSession(policy: PlaybackQualityPolicy): PlayerQualitySessionState =
        PlayerQualitySessionState(policy = policy.normalized())

    fun preserveExplicitBackendSwitch(
        policy: PlaybackQualityPolicy,
        capOrigin: PlaybackQualityCapOrigin?,
    ): PlayerQualitySessionState =
        PlayerQualitySessionState(
            policy = policy,
            maximumBitrateBps = policy.maxBitrateBps,
            capOrigin = capOrigin,
            isExplicitSessionChoice = true,
        )

    fun adoptInheritedBackendSwitch(
        policy: PlaybackQualityPolicy,
        capOrigin: PlaybackQualityCapOrigin?,
    ): PlayerQualitySessionState =
        PlayerQualitySessionState(
            policy = policy,
            maximumBitrateBps = policy.maxBitrateBps,
            capOrigin = capOrigin,
        )
}
