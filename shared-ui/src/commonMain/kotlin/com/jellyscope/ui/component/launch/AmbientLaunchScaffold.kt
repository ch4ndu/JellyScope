// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component.launch

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.launch_backdrop_landscape
import com.jellyscope.ui.generated.resources.launch_backdrop_portrait
import com.jellyscope.ui.theme.AmbientLaunchTokens
import org.jetbrains.compose.resources.painterResource

/**
 * Edge-to-edge root for the logged-out Ambient launch flow.
 *
 * The bundled backdrop is intentionally painted before safe-area/overscan
 * padding. Interactive content receives the supplied insets and IME padding;
 * screens can therefore own their vertical scroll state without leaving themed
 * strips around the backdrop. The width tier is measured here because logged-out
 * routes are outside the logged-in navigation host.
 */
@Composable
fun AmbientLaunchScaffold(
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    imeAware: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthTier = WindowWidthTier.fromAvailableWidth(maxWidth)
        val useLandscapeBackdrop = widthTier != WindowWidthTier.Compact || maxWidth >= maxHeight
        val contentModifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentWindowInsets)
                .then(if (imeAware) Modifier.imePadding() else Modifier)

        CompositionLocalProvider(LocalWindowWidthTier provides widthTier) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(AmbientLaunchTokens.groundTop, AmbientLaunchTokens.groundBottom),
                            ),
                        ),
            ) {
                Image(
                    painter =
                        painterResource(
                            if (useLandscapeBackdrop) {
                                Res.drawable.launch_backdrop_landscape
                            } else {
                                Res.drawable.launch_backdrop_portrait
                            },
                        ),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // Uniform dim so the poster wall stays subtle behind the UI, then
                // a directional scrim for text legibility on the content side.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(AmbientLaunchTokens.scrim.copy(alpha = 0.42f)),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(ambientLaunchScrim(useLandscapeBackdrop)),
                )
                Box(
                    modifier = contentModifier,
                    content = content,
                )
            }
        }
    }
}

private fun ambientLaunchScrim(useLandscapeBackdrop: Boolean): Brush =
    if (useLandscapeBackdrop) {
        Brush.horizontalGradient(
            colorStops =
                arrayOf(
                    0f to AmbientLaunchTokens.scrim.copy(alpha = 0.96f),
                    0.55f to AmbientLaunchTokens.scrim.copy(alpha = 0.7f),
                    1f to AmbientLaunchTokens.scrim.copy(alpha = 0.42f),
                ),
        )
    } else {
        Brush.verticalGradient(
            colorStops =
                arrayOf(
                    0f to AmbientLaunchTokens.scrim.copy(alpha = 0.5f),
                    0.48f to AmbientLaunchTokens.scrim.copy(alpha = 0.66f),
                    1f to AmbientLaunchTokens.scrim.copy(alpha = 0.96f),
                ),
        )
    }
