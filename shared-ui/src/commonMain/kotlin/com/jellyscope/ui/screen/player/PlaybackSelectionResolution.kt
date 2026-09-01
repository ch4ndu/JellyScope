// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.AudioTrackOption

internal data class LaunchAudioSelectionResolution(
    val explicitLaunchAudioStreamIndex: Int?,
    val explicitAudioStreamIndex: Int?,
    val requestedAudioStreamIndex: Int?,
)

internal fun resolvePlaybackSelection(
    durableSelection: PlaybackSelection?,
    sourceSelection: PlaybackSelection?,
    legacyItemSelection: PlaybackSelection?,
    durableStoreAvailable: Boolean,
): PlaybackSelection? =
    durableSelection
        ?: sourceSelection
        ?: legacyItemSelection.takeUnless { durableStoreAvailable }

// Durable selection wins, followed by source-keyed then legacy item memory.
internal fun resolveRememberedSelection(
    durable: PlaybackSelection?,
    sourceMemory: PlaybackSelection?,
    legacyItemMemory: PlaybackSelection?,
    durableStoreAvailable: Boolean,
): PlaybackSelection? = durable ?: if (durableStoreAvailable) sourceMemory else legacyItemMemory

internal fun resolveLaunchAudioSelection(
    explicitLaunchAudioStreamIndex: Int?,
    durableSelection: PlaybackSelection?,
    rememberedSelection: PlaybackSelection?,
    audioTrackOptions: List<AudioTrackOption>,
    preferredAudioStreamIndex: Int?,
): LaunchAudioSelectionResolution {
    fun validStreamIndex(streamIndex: Int?): Int? =
        streamIndex?.takeIf { candidate -> audioTrackOptions.any { option -> option.streamIndex == candidate } }

    val explicitLaunchAudioPick = validStreamIndex(explicitLaunchAudioStreamIndex)
    val durableExplicitAudioStreamIndex = validStreamIndex(durableSelection?.audioStreamIndex)
    val rememberedAudioStreamIndex = validStreamIndex(rememberedSelection?.audioStreamIndex)
    return LaunchAudioSelectionResolution(
        explicitLaunchAudioStreamIndex = explicitLaunchAudioPick,
        explicitAudioStreamIndex = explicitLaunchAudioPick ?: durableExplicitAudioStreamIndex,
        requestedAudioStreamIndex =
            explicitLaunchAudioPick
                ?: rememberedAudioStreamIndex
                ?: validStreamIndex(preferredAudioStreamIndex)
                ?: audioTrackOptions.firstOrNull { option -> option.isDefault }?.streamIndex
                ?: audioTrackOptions.firstOrNull()?.streamIndex,
    )
}
