// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.image

import coil3.ImageLoader

// No JVM-specific tuning for the browse shell; desktop image cache/runtime
// behavior is left at Coil defaults until the desktop track owns persistence.
actual fun ImageLoader.Builder.applyPlatformImageTuning(): ImageLoader.Builder = this
