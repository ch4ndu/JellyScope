// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalFoundationApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.DetailBodyStyle
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.FocusableBox
import com.jellyscope.ui.component.SmallBadge
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.tv_open_season
import com.jellyscope.ui.generated.resources.tv_seasons
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SeasonsShelf(
    session: Session,
    seasons: List<SeasonUi>,
    selectedSeasonId: String?,
    horizontalBringIntoViewSpec: androidx.compose.foundation.gestures.BringIntoViewSpec,
    onSeasonSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    seasonsFocus: DetailFocusContainer? = null,
) {
    FocusableRibbon(
        title = stringResource(Res.string.tv_seasons),
        items = seasons,
        key = { season -> season.id },
        horizontalBringIntoViewSpec = horizontalBringIntoViewSpec,
        modifier = modifier,
        focusContainer = seasonsFocus,
        focusFallback = { listOfNotNull(selectedSeasonId, seasons.firstOrNull()?.id) },
    ) { season, focusModifier ->
        SeasonPosterCard(
            session = session,
            season = season,
            selected = season.id == selectedSeasonId,
            onClick = { onSeasonSelected(season.id) },
            focusModifier = focusModifier,
        )
    }
}

@Composable
private fun SeasonPosterCard(
    session: Session,
    season: SeasonUi,
    selected: Boolean,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
) {
    Column(
        modifier = Modifier.width(DetailDimens.posterWidth.tileScaled()),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.seasonEpisodeTextGap),
    ) {
        FocusableBox(
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(DetailDimens.posterHeight.tileScaled())
                    .then(focusModifier),
            contentDescription = stringResource(Res.string.tv_open_season, season.title),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val posterUrl = season.posterUrl
                if (posterUrl != null) {
                    AsyncImage(
                        model = authenticatedImageRequest(posterUrl, session),
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
                season.unplayedCount?.let { count ->
                    SmallBadge(
                        text = count.toString(),
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(DetailDimens.seasonEpisodeBadgePadding),
                    )
                }
                if (selected) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(DetailDimens.seasonTabUnderlineHeight)
                                .background(LocalJellyfinPalette.current.cyan),
                    )
                }
            }
        }
        DetailText(
            text = season.title,
            style = DetailCaptionStyle,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SeasonTabs(
    seasons: List<SeasonUi>,
    selectedSeasonId: String?,
    selectedTabRequester: FocusRequester?,
    episodeEntryRequester: FocusRequester,
    horizontalBringIntoViewSpec: androidx.compose.foundation.gestures.BringIntoViewSpec,
    onSeasonSelected: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val selectedSeasonIndex = seasons.indexOfFirst { season -> season.id == selectedSeasonId }

    if (isDetailDpadMode()) {
        LaunchedEffect(selectedSeasonId) {
            if (selectedSeasonIndex >= 0) {
                listState.scrollToItem(selectedSeasonIndex.coerceAtLeast(0))
            }
        }
    }

    DetailBringIntoViewProvider(horizontalBringIntoViewSpec) {
        LazyRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .detailOnPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionDown &&
                            episodeEntryRequester.requestFocusSafely()
                    }.then(
                        selectedTabRequester?.let { requester ->
                            Modifier.detailFocusRestorer(requester)
                        } ?: Modifier.detailFocusRestorer(),
                    ).detailFocusGroup()
                    .then(
                        if (isDetailDpadMode()) {
                            Modifier
                        } else {
                            Modifier.desktopScrollInput(listState, DesktopScrollOrientation.Horizontal)
                        },
                    ),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(DetailDimens.seasonTabsGap),
            contentPadding =
                PaddingValues(
                    start = detailHorizontalInset() + DetailDimens.focusBorder,
                    top = DetailDimens.focusBorder,
                    end = detailHorizontalInset() + DetailDimens.rowGap,
                    bottom = DetailDimens.focusBorder,
                ),
        ) {
            items(
                items = seasons,
                key = { season -> season.id },
            ) { season ->
                SeasonTab(
                    season = season,
                    selected = season.id == selectedSeasonId,
                    focusRequester = selectedTabRequester.takeIf { season.id == selectedSeasonId },
                    onClick = { onSeasonSelected(season.id) },
                )
            }
        }
    }
}

@Composable
private fun SeasonTab(
    season: SeasonUi,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val dpad = isDetailDpadMode()
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(DetailDimens.seasonTabRadius)
    val contentColor =
        when {
            dpad && focused -> LocalJellyfinPalette.current.navy
            selected -> LocalJellyfinPalette.current.cyan
            else -> LocalJellyfinPalette.current.textSecondary
        }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .detailFocusRequester(focusRequester)
                    .then(
                        if (dpad) {
                            Modifier
                        } else {
                            Modifier.heightIn(min = Dimensions.minTouchTarget)
                        },
                    ).clip(shape)
                    .background(if (dpad && focused) Color.White else Color.Transparent)
                    .border(
                        width = if (dpad && focused) DetailDimens.focusBorder else 1.dp,
                        color = if (dpad && focused) Color.White else Color.Transparent,
                        shape = shape,
                    ).detailOnFocusChanged { state -> focused = state.isFocused }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = if (dpad) null else LocalIndication.current,
                        role = Role.Button,
                        onClick = onClick,
                    ).detailFocusable()
                    .semantics {
                        contentDescription = season.title
                        role = Role.Button
                        this.selected = selected
                    }.padding(
                        horizontal = DetailDimens.seasonTabHorizontalPadding,
                        vertical = DetailDimens.seasonTabVerticalPadding,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            DetailText(
                text = season.title,
                style = DetailBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                maxLines = 1,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(DetailDimens.seasonTabUnderlineHeight)
                    .background(if (selected) LocalJellyfinPalette.current.cyan else Color.Transparent),
        )
    }
}
