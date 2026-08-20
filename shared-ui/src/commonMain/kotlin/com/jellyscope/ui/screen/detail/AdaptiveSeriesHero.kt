// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import coil3.Image
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.component.DetailBodyStyle
import com.jellyscope.ui.component.DetailHeadlineStyle
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.ImageDecode
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.tv_add_favorite
import com.jellyscope.ui.generated.resources.tv_loading
import com.jellyscope.ui.generated.resources.tv_overview_fallback
import com.jellyscope.ui.generated.resources.tv_remove_favorite
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SeriesBackdrop(
    session: Session,
    itemId: String,
    imageUrl: String?,
    onPosterLoaded: (Image) -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = imageUrl,
        animationSpec = tween(durationMillis = 500),
        label = "adaptive-series-backdrop-$itemId",
        modifier =
            modifier
                .fillMaxWidth(DetailDimens.detailBackdropWidthFraction)
                .height(DetailDimens.detailBackdropHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = detailBackdropHorizontalFade, blendMode = BlendMode.DstIn)
                    drawRect(brush = detailBackdropVerticalFade, blendMode = BlendMode.DstIn)
                },
    ) { targetUrl ->
        if (targetUrl != null) {
            AsyncImage(
                model = authenticatedImageRequest(targetUrl, session, ImageDecode.Backdrop),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { state -> onPosterLoaded(state.result.image) },
            )
        }
    }
}

@Composable
internal fun SeriesHero(series: SeriesHeaderUi) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth(DetailDimens.detailHeroTextWidthFraction)
                .padding(
                    top = detailAdaptiveHeroTopPadding(),
                    bottom = DetailDimens.detailHeroBottomPadding,
                ),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailHeroTextGap, Alignment.Top),
    ) {
        DetailText(
            text = series.title,
            style = DetailHeadlineStyle.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
        )
        SeriesMetaRow(series = series)
        series.genresLine?.let { genres ->
            DetailText(
                text = genres,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        }
        RatingsRow(
            communityRating = series.communityRating,
            criticRatingText = series.criticRatingText,
        )
        DetailText(
            text = series.overview ?: stringResource(Res.string.tv_overview_fallback),
            style = DetailBodyStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
    }
}

@Composable
private fun SeriesMetaRow(series: SeriesHeaderUi) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(DetailDimens.detailMetaGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        series.metadataLine?.let { metadata ->
            DetailText(
                text = metadata,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        }
        series.officialRating?.let { rating ->
            InfoChip(text = rating)
        }
    }
}

@Composable
internal fun SeriesActionRow(
    series: SeriesHeaderUi,
    episode: EpisodeUi?,
    trackSelectionState: DetailTrackSelectionState?,
    playRequester: FocusRequester,
    onPlay: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onToggleFavorite: () -> Unit,
    versionTrackRequester: FocusRequester,
    audioTrackRequester: FocusRequester,
    subtitleTrackRequester: FocusRequester,
    onTrackPickerClick: (DetailTrackPickerType) -> Unit,
) {
    val action = episode?.playAction
    val playButtonRequester = remember { FocusRequester() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // Entry lands on the group; the restorer restores the last-focused
                // action button, falling back to Play on the first entry.
                .detailFocusRequester(playRequester)
                .detailFocusRestorer(playButtonRequester)
                .detailFocusGroup(),
        horizontalArrangement = Arrangement.spacedBy(DetailDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryDetailPlayButton(
            label =
                if (action == null) {
                    stringResource(Res.string.tv_loading)
                } else {
                    detailTvPlayLabel(action.label)
                },
            timeLeftText = episode?.timeLeftText,
            enabled = action != null,
            onClick = {
                if (episode != null && action != null) {
                    onPlay(
                        episode.itemId,
                        action.startPositionTicks,
                        action.mediaSourceId,
                        trackSelectionState?.audioStreamIndexForPlay(episode.trackSelection),
                        trackSelectionState?.subtitleSelectionForPlay(episode.trackSelection)
                            ?: episode.trackSelection.initialSubtitleSelection,
                    )
                }
            },
            modifier = Modifier.detailFocusRequester(playButtonRequester),
        )
        ExpandingActionButton(
            label =
                if (series.isFavorite) {
                    stringResource(Res.string.tv_remove_favorite)
                } else {
                    stringResource(Res.string.tv_add_favorite)
                },
            icon = if (series.isFavorite) DetailActionIcon.Favorite else DetailActionIcon.FavoriteOutline,
            active = series.isFavorite,
            enabled = !series.favoriteToggleInFlight,
            onClick = onToggleFavorite,
        )
        if (episode != null && trackSelectionState != null) {
            DpadTrackSelectionButtons(
                trackSelection = episode.trackSelection,
                selectionState = trackSelectionState,
                versions = episode.versions,
                selectedMediaSourceId = episode.selectedMediaSourceId,
                versionRequester = versionTrackRequester,
                audioRequester = audioTrackRequester,
                subtitleRequester = subtitleTrackRequester,
                onOpenPicker = onTrackPickerClick,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
