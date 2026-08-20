// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.player.PlayerMediaMetadata
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

@Composable
internal fun TvSkipSegmentButton(
    action: SkipSegmentAction,
    requestInitialFocus: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requester = remember { FocusRequester() }

    LaunchedEffect(action, requestInitialFocus) {
        if (requestInitialFocus) {
            withFrameNanos { }
            requester.requestFocusSafely()
        }
    }

    TvPillButton(
        text = stringResource(action.labelRes),
        contentDescription = stringResource(action.labelRes),
        focusRequester = requester,
        onClick = onClick,
        modifier = modifier.width(TvDimens.playerSkipButtonWidth),
    )
}

@Composable
internal fun TvPillButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    TvFocusableBox(
        onClick = onClick,
        modifier =
            modifier
                .defaultMinSize(minHeight = TvDimens.minButtonHeight)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        contentDescription = contentDescription,
        focusedScale = 1.04f,
        backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
        focusedBackgroundColor = Color.White,
        contentPadding =
            PaddingValues(
                horizontal = TvDimens.buttonHorizontalPadding,
                vertical = TvDimens.buttonVerticalPadding,
            ),
        contentAlignment = Alignment.Center,
        shape = RoundedCornerShape(percent = 50),
    ) { focused ->
        TvText(
            text = text,
            style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color =
                if (focused) {
                    LocalJellyfinPalette.current.onFocusedLight
                } else {
                    LocalJellyfinPalette.current.textPrimary
                },
            maxLines = 1,
        )
    }
}

@Composable
internal fun OverlayMetadataRow(
    metadata: PlayerMediaMetadata,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.playerOverlayGap),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Title takes the flexible space and ellipsizes; the badge keeps its full
        // intrinsic width so codec details (e.g. "4K AV1 SDR") are never clipped.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TvDimens.playerBadgeVerticalPadding),
        ) {
            TvText(
                text = metadata.title,
                style = TvPlayerNowPlayingTitleStyle,
                maxLines = 1,
            )
            TvText(
                text = metadataLine(metadata),
                style = TvPlayerNowPlayingMetadataStyle,
                maxLines = 1,
            )
        }
        metadata.qualityBadge?.let { badge ->
            TvText(
                text = badge,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(TvDimens.panelRadius))
                        .background(LocalJellyfinPalette.current.surfaceRaised)
                        .padding(
                            horizontal = TvDimens.playerBadgeHorizontalPadding,
                            vertical = TvDimens.playerBadgeVerticalPadding,
                        ),
                style = TvSecondaryStyle.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}
