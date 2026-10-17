// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.image

import coil3.ImageLoader

// No iOS-specific tuning: RGB_565 is an Android bitmap config, and Skia's
// image cache is managed differently.
actual fun ImageLoader.Builder.applyPlatformImageTuning(): ImageLoader.Builder = this
