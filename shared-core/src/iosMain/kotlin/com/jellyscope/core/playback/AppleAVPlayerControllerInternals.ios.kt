// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.playback

import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.setPreferredTransform

internal actual fun AVMutableCompositionTrack.applyPreferredVideoTransform(track: AVAssetTrack) {
    setPreferredTransform(track.preferredTransform)
}
