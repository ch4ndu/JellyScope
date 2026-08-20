// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
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
import com.jellyscope.ui.generated.resources.detail_trailer
import com.jellyscope.ui.generated.resources.media_info_action
import com.jellyscope.ui.generated.resources.media_info_open
import com.jellyscope.ui.generated.resources.tv_add_favorite
import com.jellyscope.ui.generated.resources.tv_cast_and_crew
import com.jellyscope.ui.generated.resources.tv_critic_rating
import com.jellyscope.ui.generated.resources.tv_mark_unwatched
import com.jellyscope.ui.generated.resources.tv_mark_watched
import com.jellyscope.ui.generated.resources.tv_metadata_separator
import com.jellyscope.ui.generated.resources.tv_overview_fallback
import com.jellyscope.ui.generated.resources.tv_remove_favorite
import com.jellyscope.ui.generated.resources.tv_restart
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DetailBackdrop(
    session: Session,
    detail: DetailUi,
    onPosterLoaded: (Image) -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = detail.backdropUrl ?: detail.posterUrl,
        animationSpec = tween(durationMillis = 500),
        label = "adaptive-detail-backdrop",
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
    ) { imageUrl ->
        if (imageUrl != null) {
            AsyncImage(
                model = authenticatedImageRequest(imageUrl, session, ImageDecode.Backdrop),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { state -> onPosterLoaded(state.result.image) },
            )
        }
    }
}

@Composable
internal fun DetailHero(
    session: Session,
    detail: DetailUi,
) {
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
        if (detail.logoUrl != null) {
            AsyncImage(
                model = authenticatedImageRequest(detail.logoUrl, session, ImageDecode.Backdrop),
                contentDescription = detail.title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .heightIn(max = DetailDimens.detailHeroLogoMaxHeight)
                        .fillMaxWidth(0.72f),
            )
        } else {
            DetailText(
                text = detail.title,
                style = DetailHeadlineStyle.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
            )
        }
        detail.tagline?.let { tagline ->
            DetailText(
                text = tagline,
                style = DetailSecondaryBodyStyle.copy(fontStyle = FontStyle.Italic),
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 2,
            )
        }
        DetailMetaRow(detail = detail)
        detail.genresLine?.let { genres ->
            DetailText(
                text = genres,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        }
        RatingsRow(
            communityRating = detail.communityRating,
            criticRatingText = detail.criticRatingText,
        )
        DetailText(
            text = detail.overview ?: stringResource(Res.string.tv_overview_fallback),
            style = DetailBodyStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
        detail.directedByLine?.let { directedBy ->
            DetailText(
                text = directedBy,
                style = DetailSecondaryBodyStyle,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        }
        detail.studioLine?.let { studios ->
            DetailText(
                text = studios,
                style = DetailSecondaryBodyStyle,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun RatingsRow(
    communityRating: String?,
    criticRatingText: String?,
) {
    if (communityRating == null && criticRatingText == null) {
        return
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(DetailDimens.detailBadgeGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        communityRating?.let { rating ->
            DetailText(
                text = rating,
                color = LocalJellyfinPalette.current.cyan,
                style = DetailBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
        criticRatingText?.let { rating ->
            InfoChip(text = stringResource(Res.string.tv_critic_rating, rating))
        }
    }
}

@Composable
private fun DetailMetaRow(detail: DetailUi) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(DetailDimens.detailMetaGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        detailMetadataLine(detail)?.let { meta ->
            DetailText(
                text = meta,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        }
        detail.officialRating?.let { rating ->
            InfoChip(text = rating)
        }
    }
}

@Composable
private fun detailMetadataLine(detail: DetailUi): String? {
    val headerLine = detail.headerLine
    val metadataLine = detail.metadataLine
    return when {
        headerLine != null && metadataLine != null ->
            stringResource(Res.string.tv_metadata_separator, headerLine, metadataLine)
        headerLine != null -> headerLine
        metadataLine != null -> metadataLine
        else -> null
    }
}

@Composable
internal fun DetailActionRow(
    detail: DetailUi,
    trackSelectionState: DetailTrackSelectionState,
    playRequester: FocusRequester,
    mediaInfoRequester: FocusRequester,
    onPlay: (String, Long, String?, Int?, SubtitleSelectionIntent) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    subtitleActions: DetailSubtitlePickerActions?,
    onMediaInfoClick: () -> Unit,
    versionTrackRequester: FocusRequester,
    audioTrackRequester: FocusRequester,
    subtitleTrackRequester: FocusRequester,
    onTrackPickerClick: (DetailTrackPickerType) -> Unit,
) {
    val dpad = isDetailDpadMode()
    val actionRowHeightModifier =
        if (dpad) {
            Modifier.height(DetailDimens.detailActionHeight)
        } else {
            Modifier
        }
    val actionButtonHeightModifier =
        if (dpad) {
            Modifier.fillMaxHeight()
        } else {
            Modifier
        }
    val playButtonRequester = remember { FocusRequester() }
    val uriHandler = LocalUriHandler.current
    // Shared button set. In D-pad mode these lay out in a single focus-group Row;
    // in touch/desktop mode they flow across lines so nothing clips on narrow or
    // resized windows.
    val actionButtons: @Composable () -> Unit = {
        PrimaryDetailPlayButton(
            label = detailTvPlayLabel(detail.playAction.label),
            timeLeftText = detail.timeLeftText,
            onClick = {
                onPlay(
                    detail.itemId,
                    detail.playAction.startPositionTicks,
                    detail.playAction.mediaSourceId,
                    trackSelectionState.audioStreamIndexForPlay(detail.trackSelection),
                    trackSelectionState.subtitleSelectionForPlay(detail.trackSelection),
                )
            },
            modifier =
                actionButtonHeightModifier
                    .detailFocusRequester(playButtonRequester),
        )
        detail.restartAction?.let { restart ->
            ExpandingActionButton(
                label = stringResource(Res.string.tv_restart),
                icon = DetailActionIcon.Restart,
                onClick = {
                    onPlay(
                        detail.itemId,
                        restart.startPositionTicks,
                        restart.mediaSourceId,
                        trackSelectionState.audioStreamIndexForPlay(detail.trackSelection),
                        trackSelectionState.subtitleSelectionForPlay(detail.trackSelection),
                    )
                },
                modifier = actionButtonHeightModifier,
            )
        }
        ExpandingActionButton(
            label =
                if (detail.isWatched) {
                    stringResource(Res.string.tv_mark_unwatched)
                } else {
                    stringResource(Res.string.tv_mark_watched)
                },
            icon = if (detail.isWatched) DetailActionIcon.Watched else DetailActionIcon.Unwatched,
            active = detail.isWatched,
            enabled = !detail.watchedToggleInFlight,
            onClick = onToggleWatched,
            modifier = actionButtonHeightModifier,
        )
        ExpandingActionButton(
            label =
                if (detail.isFavorite) {
                    stringResource(Res.string.tv_remove_favorite)
                } else {
                    stringResource(Res.string.tv_add_favorite)
                },
            icon = if (detail.isFavorite) DetailActionIcon.Favorite else DetailActionIcon.FavoriteOutline,
            active = detail.isFavorite,
            enabled = !detail.favoriteToggleInFlight,
            onClick = onToggleFavorite,
            modifier = actionButtonHeightModifier,
        )
        if (detail.mediaInfo?.hasAnything == true) {
            ExpandingActionButton(
                label = stringResource(Res.string.media_info_action),
                icon = DetailActionIcon.Info,
                onClick = onMediaInfoClick,
                modifier = actionButtonHeightModifier.detailFocusRequester(mediaInfoRequester),
                contentDescription = stringResource(Res.string.media_info_open),
            )
        }
        // No trailer or IMDb/TMDb buttons on TV: both hand off to a browser, which
        // is moot on a 10-foot device (trailers are YouTube links). Both stay on
        // mobile/tablet/desktop (compact + the !dpad external-links item below).
        if (!dpad) {
            detail.trailerUrl?.let { url ->
                ExpandingActionButton(
                    label = stringResource(Res.string.detail_trailer),
                    icon = DetailActionIcon.Trailer,
                    onClick = { runCatching { uriHandler.openUri(url) } },
                    modifier = actionButtonHeightModifier,
                )
            }
        }
        DpadTrackSelectionButtons(
            trackSelection = detail.trackSelection,
            selectionState = trackSelectionState,
            versions = detail.versions,
            selectedMediaSourceId = detail.selectedMediaSourceId,
            subtitleActions = subtitleActions,
            versionRequester = versionTrackRequester,
            audioRequester = audioTrackRequester,
            subtitleRequester = subtitleTrackRequester,
            onOpenPicker = onTrackPickerClick,
            modifier = actionButtonHeightModifier,
        )
    }
    val streamBadges: @Composable () -> Unit = {
        detail.streamBadges.forEach { badge -> InfoChip(text = badge) }
    }

    if (dpad) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(actionRowHeightModifier)
                    // Entry lands on the group; the restorer returns focus to the
                    // last-focused action button, falling back to Play on first entry.
                    .detailFocusRequester(playRequester)
                    .detailFocusRestorer(playButtonRequester)
                    .detailFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(DetailDimens.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actionButtons()
            Spacer(modifier = Modifier.weight(1f))
            if (detail.streamBadges.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DetailDimens.detailBadgeGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    streamBadges()
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(DetailDimens.itemGap)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DetailDimens.itemGap),
                verticalArrangement = Arrangement.spacedBy(DetailDimens.itemGap),
            ) {
                actionButtons()
            }
            if (detail.streamBadges.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DetailDimens.detailBadgeGap),
                    verticalArrangement = Arrangement.spacedBy(DetailDimens.detailBadgeGap),
                ) {
                    streamBadges()
                }
            }
        }
    }
}

@Composable
internal fun DetailCastAndCrewShelf(
    session: Session,
    people: List<CastAndCrewUi>,
    horizontalBringIntoViewSpec: androidx.compose.foundation.gestures.BringIntoViewSpec,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    castFocus: DetailFocusContainer? = null,
    onPersonSelected: (String) -> Unit = {},
) {
    FocusableRibbon(
        title = stringResource(Res.string.tv_cast_and_crew),
        items = people,
        key = { person -> person.id },
        horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
        modifier = modifier,
        rowModifier =
            if (upTarget != null) {
                Modifier.detailFocusProperties { up = upTarget }
            } else {
                Modifier
            },
        focusRequester = focusRequester,
        focusContainer = castFocus,
    ) { person, focusModifier ->
        AdaptivePersonCard(
            session = session,
            person = person,
            onClick = { onPersonSelected(person.id) },
            focusModifier = focusModifier,
        )
    }
}
