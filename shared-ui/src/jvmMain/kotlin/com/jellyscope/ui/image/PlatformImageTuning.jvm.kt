// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.image

import coil3.ImageLoader
import coil3.memory.MemoryCache

private const val JVM_IMAGE_MEMORY_CACHE_MAX_SIZE_BYTES = 80_530_636L

// Coil 3.6.2 reports the full JVM heap, so keep the desktop cache at its prior cap.
actual fun ImageLoader.Builder.applyPlatformImageTuning(): ImageLoader.Builder =
    memoryCache {
        MemoryCache
            .Builder()
            .maxSizeBytes(JVM_IMAGE_MEMORY_CACHE_MAX_SIZE_BYTES)
            .build()
    }
