// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.util.runCatchingCancellable
import com.jellyscope.ui.generated.resources.Res
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

// Cache the off-Main decode because first entry is expensive on TV.
private var landscapeBackdropCache: ImageBitmap? = null
private var portraitBackdropCache: ImageBitmap? = null

/** Decodes the orientation-specific backdrop off Main; null preserves the theme fallback. */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun rememberSettingsBackdropImage(useLandscape: Boolean): ImageBitmap? {
    var image by remember(useLandscape) {
        mutableStateOf(if (useLandscape) landscapeBackdropCache else portraitBackdropCache)
    }
    LaunchedEffect(useLandscape) {
        if (image == null) {
            image =
                runCatchingCancellable {
                    withContext(platformIoDispatcher()) {
                        val path =
                            if (useLandscape) {
                                "drawable/launch_backdrop_landscape.webp"
                            } else {
                                "drawable/launch_backdrop_portrait.webp"
                            }
                        Res.readBytes(path).decodeToImageBitmap()
                    }
                }.getOrNull()
                    ?.also { decoded ->
                        if (useLandscape) {
                            landscapeBackdropCache = decoded
                        } else {
                            portraitBackdropCache = decoded
                        }
                    }
        }
    }
    return image
}
