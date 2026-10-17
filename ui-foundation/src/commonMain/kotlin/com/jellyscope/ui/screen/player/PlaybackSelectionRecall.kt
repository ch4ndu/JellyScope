// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

// Durable selection wins, followed by source-keyed then legacy item memory.
fun resolveRememberedSelection(
    durable: PlaybackSelection?,
    sourceMemory: PlaybackSelection?,
    legacyItemMemory: PlaybackSelection?,
    durableStoreAvailable: Boolean,
): PlaybackSelection? = durable ?: if (durableStoreAvailable) sourceMemory else legacyItemMemory
