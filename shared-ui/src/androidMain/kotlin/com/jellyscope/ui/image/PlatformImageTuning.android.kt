// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.image

import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.memoryCacheMaxSizePercentWhileInBackground
import coil3.request.allowRgb565

// Posters and backdrops are opaque JPEGs, so RGB_565 halves their bitmap
// footprint with no visible change — the largest, safest memory lever on the
// 2GB Fire TV Cube. Backgrounding the app drops the memory cache to 20% so
// poster bitmaps are freed while the launcher is up (Coil already registers
// system trim-memory callbacks, so no manual hook is needed).
@OptIn(ExperimentalCoilApi::class)
actual fun ImageLoader.Builder.applyPlatformImageTuning(): ImageLoader.Builder =
    allowRgb565(true)
        .memoryCacheMaxSizePercentWhileInBackground(0.2)
