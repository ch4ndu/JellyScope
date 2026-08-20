// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.MediaPersonType
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.AdaptiveProgressBar
import com.jellyscope.ui.component.CardImageAspect
import com.jellyscope.ui.component.DetailBodyStyle
import com.jellyscope.ui.component.DetailHeadlineStyle
import com.jellyscope.ui.component.DetailSecondaryStyle
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.FocusableBox
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.SmallBadge
import com.jellyscope.ui.component.WatchedBadge
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.mediaCardKindLabel
import com.jellyscope.ui.component.rememberCardImageDecode
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_person_cd
import com.jellyscope.ui.generated.resources.tv_person_actor
import com.jellyscope.ui.generated.resources.tv_person_crew
import com.jellyscope.ui.generated.resources.tv_person_director
import com.jellyscope.ui.generated.resources.tv_person_producer
import com.jellyscope.ui.generated.resources.tv_person_writer
import com.jellyscope.ui.generated.resources.tv_unsupported
import com.jellyscope.ui.generated.resources.tv_watched
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AdaptiveMediaCard(
    session: Session,
    item: MediaCardUi,
    wide: Boolean,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
) {
    Column(
        modifier = Modifier.width((if (wide) DetailDimens.seasonEpisodeCardWidth else DetailDimens.posterWidth).tileScaled()),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailPersonTextGap),
    ) {
        FocusableBox(
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height((if (wide) DetailDimens.seasonEpisodeCardHeight else DetailDimens.posterHeight).tileScaled())
                    .then(focusModifier),
            contentDescription = item.title,
            backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            focusedBackgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            contentAlignment = Alignment.Center,
        ) { focused ->
            Box(modifier = Modifier.fillMaxSize()) {
                val imageUrl = if (wide) item.backdropUrl ?: item.imageUrl else item.imageUrl
                if (imageUrl != null) {
                    AsyncImage(
                        model = authenticatedImageRequest(imageUrl, session),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(LocalJellyfinPalette.current.surfaceRaised),
                    )
                }
                item.progressFraction?.let { progress ->
                    AdaptiveProgressBar(
                        progress = progress,
                        fillColor = LocalJellyfinPalette.current.accentAmber,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        focusedCardInset = focused,
                    )
                }
                when {
                    !item.supported ->
                        SmallBadge(
                            text = stringResource(Res.string.tv_unsupported),
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(DetailDimens.seasonEpisodeBadgePadding),
                        )
                    item.watched ->
                        WatchedBadge(
                            contentDescription = stringResource(Res.string.tv_watched),
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(DetailDimens.seasonEpisodeBadgePadding),
                        )
                    item.unplayedCount != null ->
                        SmallBadge(
                            text = item.unplayedCount.toString(),
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(DetailDimens.seasonEpisodeBadgePadding),
                        )
                }
            }
        }
        DetailText(
            text = item.title,
            style = DetailCaptionStyle,
            maxLines = 1,
        )
        DetailText(
            text = item.subtitle ?: mediaCardKindLabel(item.kind),
            style = DetailSecondaryStyle,
            maxLines = 1,
        )
    }
}

@Composable
internal fun AdaptivePersonCard(
    session: Session,
    person: CastAndCrewUi,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(Res.string.detail_person_cd, person.name)
    Column(
        modifier =
            Modifier
                .width(DetailDimens.posterWidth.tileScaled())
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                },
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailPersonTextGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FocusableBox(
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(DetailDimens.detailPersonImageHeight.tileScaled())
                    .then(focusModifier),
            contentDescription = person.name,
            focusedScale = 1.06f,
            backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            focusedBackgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            contentAlignment = Alignment.Center,
        ) {
            val imageUrl = person.imageUrl
            if (imageUrl != null) {
                AsyncImage(
                    model = authenticatedImageRequest(imageUrl, session),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                DetailText(
                    text = person.name.take(1),
                    style = DetailHeadlineStyle,
                    color = LocalJellyfinPalette.current.textSecondary,
                )
            }
        }
        DetailText(
            text = person.name,
            style = DetailBodyStyle,
            maxLines = 1,
        )
        val role = person.role ?: person.fallbackRoleType?.let { type -> personRoleLabel(type) }
        DetailText(
            text = role ?: " ",
            style = DetailSecondaryStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun AdaptiveEpisodeThumb(
    session: Session,
    episode: EpisodeUi,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    FocusableBox(
        onClick = onClick,
        modifier =
            modifier
                .width(DetailDimens.seasonEpisodeCardWidth.tileScaled())
                .height(DetailDimens.seasonEpisodeCardHeight.tileScaled())
                .detailOnFocusChanged { state ->
                    if (state.isFocused) {
                        onFocused()
                    }
                },
        contentDescription = episode.title,
        focusedScale = 1.06f,
        focusedBorderColor = LocalJellyfinPalette.current.cyan,
    ) { focused ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .alpha(if (episode.isWatched) 0.55f else 1f),
        ) {
            val imageUrl = episode.imageUrl
            if (imageUrl != null) {
                AsyncImage(
                    model =
                        authenticatedImageRequest(
                            imageUrl,
                            session,
                            rememberCardImageDecode(DetailDimens.seasonEpisodeCardWidth.tileScaled(), CardImageAspect.Wide),
                        ),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(LocalJellyfinPalette.current.surfaceRaised),
                )
            }
        }
        if (episode.episodeBadge != null || episode.isWatched) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(DetailDimens.seasonEpisodeBadgePadding),
                horizontalArrangement = Arrangement.spacedBy(DetailDimens.detailBadgeGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                episode.episodeBadge?.let { badge ->
                    SmallBadge(text = badge)
                }
                if (episode.isWatched) {
                    SmallIconBadge(
                        imageVector = DetailIcons.CheckBold,
                        contentDescription = stringResource(Res.string.tv_watched),
                    )
                }
            }
        }
        episode.progressFraction?.let { progress ->
            AdaptiveProgressBar(
                progress = progress,
                fillColor = LocalJellyfinPalette.current.accentAmber,
                modifier = Modifier.align(Alignment.BottomCenter),
                focusedCardInset = focused,
            )
        }
        if (selected && !isDetailDpadMode()) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(DetailDimens.seasonTabUnderlineHeight)
                        .background(LocalJellyfinPalette.current.cyan),
            )
        }
    }
}

@Composable
private fun personRoleLabel(type: MediaPersonType): String =
    when (type) {
        MediaPersonType.Actor -> stringResource(Res.string.tv_person_actor)
        MediaPersonType.Director -> stringResource(Res.string.tv_person_director)
        MediaPersonType.Writer -> stringResource(Res.string.tv_person_writer)
        MediaPersonType.Producer -> stringResource(Res.string.tv_person_producer)
        MediaPersonType.Other -> stringResource(Res.string.tv_person_crew)
    }
