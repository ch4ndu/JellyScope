// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

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
