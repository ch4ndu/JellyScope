// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMutableCompositionTrack

internal actual fun AVMutableCompositionTrack.applyPreferredVideoTransform(track: AVAssetTrack) {
    // No-op: the tvOS SDK interop exposes no preferredTransform setter.
}
