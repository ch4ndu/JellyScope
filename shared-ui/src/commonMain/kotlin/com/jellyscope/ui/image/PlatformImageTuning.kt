// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.image

import coil3.ImageLoader

// Platform-specific ImageLoader tuning applied to the shared loader builder.
// Android trades a little color fidelity for roughly half the bitmap memory
// (posters/backdrops are opaque) and shrinks the memory cache while the app
// is backgrounded; other targets keep defaults.
expect fun ImageLoader.Builder.applyPlatformImageTuning(): ImageLoader.Builder
