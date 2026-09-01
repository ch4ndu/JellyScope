// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

internal data class PlayerStillWatchingState(
    val consecutiveAutoplayCount: Int = 0,
    val isPromptVisible: Boolean = false,
    val pendingQueueIndex: Int? = null,
) {
    fun automaticAdvance(nextQueueIndex: Int): PlayerStillWatchingAutomaticAdvance {
        if (isPromptVisible) {
            return PlayerStillWatchingAutomaticAdvance.PromptBlocked(
                copy(pendingQueueIndex = nextQueueIndex),
            )
        }

        val advanced = copy(consecutiveAutoplayCount = consecutiveAutoplayCount + 1)
        return if (advanced.consecutiveAutoplayCount >= PLAYER_STILL_WATCHING_AUTOPLAY_THRESHOLD) {
            PlayerStillWatchingAutomaticAdvance.PromptBlocked(
                advanced.copy(
                    isPromptVisible = true,
                    pendingQueueIndex = nextQueueIndex,
                ),
            )
        } else {
            PlayerStillWatchingAutomaticAdvance.Allowed(advanced)
        }
    }

    fun resetForManualNavigation(): PlayerStillWatchingState = PlayerStillWatchingState()

    fun confirm(): PlayerStillWatchingState = PlayerStillWatchingState()

    fun resetForDisabledPreference(): PlayerStillWatchingState = copy(consecutiveAutoplayCount = 0)
}

internal sealed interface PlayerStillWatchingAutomaticAdvance {
    val state: PlayerStillWatchingState

    data class Allowed(
        override val state: PlayerStillWatchingState,
    ) : PlayerStillWatchingAutomaticAdvance

    data class PromptBlocked(
        override val state: PlayerStillWatchingState,
    ) : PlayerStillWatchingAutomaticAdvance
}

private const val PLAYER_STILL_WATCHING_AUTOPLAY_THRESHOLD = 3
