// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component.launch

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.launch_mark_solid
import com.jellyscope.ui.theme.AmbientLaunchDimens
import com.jellyscope.ui.theme.AmbientLaunchTokens
import org.jetbrains.compose.resources.painterResource

/** The fixed JellyScope mark and wordmark used by Ambient launch surfaces. */
@Composable
fun JellyScopeBrandMark(
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    landscape: Boolean = LocalWindowWidthTier.current != WindowWidthTier.Compact,
    horizontalAlignment: Alignment.Horizontal =
        if (landscape) {
            Alignment.Start
        } else {
            Alignment.CenterHorizontally
        },
) {
    // Both compact and landscape use the glossy app-icon mark.
    val mark = Res.drawable.launch_mark_solid
    val markSize =
        if (landscape) {
            AmbientLaunchDimens.brandMarkLandscape
        } else {
            AmbientLaunchDimens.brandMarkCompact
        }
    val wordmarkSize =
        if (landscape) {
            AmbientLaunchDimens.brandWordmarkLandscapeSize
        } else {
            AmbientLaunchDimens.brandWordmarkSize
        }

    val markImage: @Composable () -> Unit = {
        Image(
            painter = painterResource(mark),
            contentDescription = null,
            modifier = Modifier.size(markSize),
            contentScale = ContentScale.Fit,
        )
    }
    val wordmark: @Composable () -> Unit = {
        Row {
            Text(
                text = "Jelly",
                color = AmbientLaunchTokens.textPrimary,
                style = TextStyle(fontSize = wordmarkSize, fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = "Scope",
                color = AmbientLaunchTokens.accent,
                style = TextStyle(fontSize = wordmarkSize, fontWeight = FontWeight.SemiBold),
            )
        }
    }
    val subtitleText: @Composable () -> Unit = {
        subtitle?.let {
            Text(
                text = it,
                color = AmbientLaunchTokens.textSecondary,
                style = TextStyle(fontSize = AmbientLaunchDimens.brandSubtitleSize),
            )
        }
    }

    if (landscape) {
        // Landscape (TV / desktop): mark and wordmark share a single line.
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.brandMarkLandscapeGap),
        ) {
            markImage()
            Column(verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.brandMarkGap)) {
                wordmark()
                subtitleText()
            }
        }
    } else {
        Column(
            modifier = modifier,
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.brandMarkGap),
        ) {
            markImage()
            wordmark()
            subtitleText()
        }
    }
}
