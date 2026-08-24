// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Icon
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.CardImageAspect
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.rememberCardImageDecode
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.player.AutoplayPolicySnapshot
import com.jellyscope.ui.screen.player.PlaylistUi
import com.jellyscope.ui.screen.player.QueueItemUi
import com.jellyscope.ui.screen.player.UpNextInfo
import com.jellyscope.ui.screen.player.episodeNumberLabel
import com.jellyscope.ui.screen.player.rememberAutoplayCountdownMillis
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

@Composable
internal fun TvUpNextCard(
    session: Session,
    upNext: UpNextInfo,
    autoplayPolicy: AutoplayPolicySnapshot,
    stillWatchingPrompt: Boolean,
    countdownStarted: Boolean,
    onPlayNext: (Boolean, Long?) -> Boolean,
    onConfirmStillWatching: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryRequester = remember(upNext.itemId, upNext.index, stillWatchingPrompt) { FocusRequester() }
    LaunchedEffect(autoplayPolicy.countdownKey, stillWatchingPrompt, countdownStarted) {
        withFrameNanos { }
        primaryRequester.requestFocusSafely()
    }
    val remainingMs =
        rememberAutoplayCountdownMillis(
            policy = autoplayPolicy,
            countdownStarted = countdownStarted,
            active = !stillWatchingPrompt,
            tickMs = UP_NEXT_COUNTDOWN_TICK_MS,
            onAutoAdvance = { onPlayNext(true, autoplayPolicy.countdownKey.playbackGeneration) },
        ) ?: 0L

    Row(
        modifier =
            modifier
                .width(TvDimens.playerFloatingPanelWidth)
                .clip(RoundedCornerShape(TvDimens.panelRadius))
                .background(LocalJellyfinPalette.current.gradientBottom.copy(alpha = 0.96f))
                .border(
                    width = TvDimens.playerPanelBorder,
                    color = Color.White.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(TvDimens.panelRadius),
                ).padding(TvDimens.playerFloatingPanelPadding),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.playerFloatingPanelGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(
                        width = TvDimens.playerFloatingPanelPosterWidth,
                        height = TvDimens.playerFloatingPanelPosterHeight,
                    ).clip(RoundedCornerShape(TvDimens.cardRadius))
                    .background(LocalJellyfinPalette.current.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            val imageUrl = upNext.imageUrl
            if (imageUrl != null) {
                AsyncImage(
                    model = authenticatedImageRequest(imageUrl, session),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = TvIcons.Play,
                    contentDescription = null,
                    tint = LocalJellyfinPalette.current.textSecondary,
                    modifier = Modifier.size(TvDimens.playerIconSize),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TvDimens.playerFloatingPanelGap),
        ) {
            TvText(
                text =
                    if (stillWatchingPrompt) {
                        stringResource(R.string.tv_still_watching_title)
                    } else {
                        stringResource(R.string.tv_up_next_title, upNext.title)
                    },
                style = TvPlayerQueueTitleStyle,
                maxLines = 2,
            )
            if (!stillWatchingPrompt) {
                TvText(
                    text =
                        if (countdownStarted) {
                            if (autoplayPolicy.enabled) {
                                stringResource(R.string.tv_up_next_countdown, remainingMs.countdownSeconds())
                            } else {
                                stringResource(R.string.tv_up_next_autoplay_disabled)
                            }
                        } else {
                            stringResource(R.string.tv_up_next_waiting)
                        },
                    style = TvSecondaryStyle,
                    maxLines = 1,
                )
            } else {
                TvText(
                    text = stringResource(R.string.tv_up_next_title, upNext.title),
                    style = TvSecondaryStyle,
                    maxLines = 2,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.playerFloatingPanelGap)) {
                TvPillButton(
                    text =
                        if (stillWatchingPrompt) {
                            stringResource(R.string.tv_still_watching_continue)
                        } else {
                            stringResource(R.string.tv_up_next_play_now)
                        },
                    contentDescription =
                        if (stillWatchingPrompt) {
                            stringResource(R.string.tv_still_watching_continue)
                        } else {
                            stringResource(R.string.tv_up_next_play_now)
                        },
                    focusRequester = primaryRequester,
                    onClick = {
                        performTvUpNextPrimaryAction(
                            stillWatchingPrompt = stillWatchingPrompt,
                            onPlayNext = onPlayNext,
                            onConfirmStillWatching = onConfirmStillWatching,
                        )
                    },
                    modifier = Modifier.width(TvDimens.playerFloatingPanelButtonWidth),
                )
                TvPillButton(
                    text = stringResource(R.string.tv_up_next_dismiss),
                    contentDescription = stringResource(R.string.tv_up_next_dismiss),
                    onClick = onDismiss,
                    modifier = Modifier.width(TvDimens.playerFloatingPanelButtonWidth),
                )
            }
        }
    }
}

internal fun performTvUpNextPrimaryAction(
    stillWatchingPrompt: Boolean,
    onPlayNext: (Boolean, Long?) -> Boolean,
    onConfirmStillWatching: () -> Unit,
) {
    if (stillWatchingPrompt) {
        onConfirmStillWatching()
    } else {
        onPlayNext(false, null)
    }
}

@Composable
internal fun TvQueueRibbon(
    session: Session,
    playlist: PlaylistUi,
    ribbonRequester: FocusRequester,
    onPlayQueueItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowState = rememberLazyListState()

    LaunchedEffect(playlist.currentIndex) {
        if (playlist.currentIndex in playlist.items.indices) {
            rowState.scrollToItem(playlist.currentIndex)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TvDimens.detailShelfTitleGap),
    ) {
        TvText(
            text = stringResource(R.string.tv_up_next),
            style = TvPlayerSmallTitleStyle,
            maxLines = 1,
        )
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
            // The cards scale to 1.06x on focus, so the first and last grow ~3% of
            // their width past their layout box. focusBorder (2dp) was not enough
            // for a 172dp card and the leading card's edge clipped against the
            // row's bounds. Per project rule this stays contentPadding, never an
            // outer margin, so scrolled items still reach the row edges.
            contentPadding =
                PaddingValues(
                    start = TvDimens.itemGap,
                    top = TvDimens.focusBorder,
                    end = TvDimens.rowGap,
                    bottom = TvDimens.focusBorder,
                ),
        ) {
            itemsIndexed(
                items = playlist.items,
                key = { _, item -> item.id },
                contentType = { _, _ -> "queue-card" },
            ) { index, item ->
                TvQueueEpisodeCard(
                    session = session,
                    item = item,
                    current = index == playlist.currentIndex,
                    focusRequester = ribbonRequester.takeIf { index == playlist.currentIndex },
                    onClick = { onPlayQueueItem(index) },
                )
            }
        }
    }
}

@Composable
private fun TvQueueEpisodeCard(
    session: Session,
    item: QueueItemUi,
    current: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageDecode =
        rememberCardImageDecode(
            width = TvDimens.seasonEpisodeCardWidth.tileScaled(),
            aspect = CardImageAspect.Wide,
        )
    TvFocusableBox(
        onClick = onClick,
        modifier =
            modifier
                .width(TvDimens.seasonEpisodeCardWidth.tileScaled())
                .height(TvDimens.seasonEpisodeCardHeight.tileScaled())
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        contentDescription = item.title,
        focusedScale = 1.06f,
        backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
        focusedBackgroundColor = LocalJellyfinPalette.current.surfaceRaised,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val imageUrl = item.imageUrl
            if (imageUrl != null) {
                AsyncImage(
                    model = authenticatedImageRequest(imageUrl, session, imageDecode),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        episodeNumberLabel(item.seasonNumber, item.episodeNumber)?.let { episodeBadge ->
            TvQueueEpisodeBadge(
                text = episodeBadge,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(TvDimens.seasonEpisodeBadgePadding),
            )
        }
        if (current) {
            TvQueueEpisodeBadge(
                text = stringResource(R.string.tv_now_playing),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(TvDimens.seasonEpisodeBadgePadding),
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(TvDimens.seasonTabUnderlineHeight)
                        .background(LocalJellyfinPalette.current.cyan),
            )
        }
    }
}

@Composable
private fun TvQueueEpisodeBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(
                    LocalJellyfinPalette.current.cyan,
                    shape = RoundedCornerShape(TvDimens.detailBadgeRadius),
                ).padding(
                    horizontal = TvDimens.detailBadgeHorizontalPadding,
                    vertical = TvDimens.detailBadgeVerticalPadding,
                ),
    ) {
        TvText(
            text = text,
            color = LocalJellyfinPalette.current.navy,
            style = TvSecondaryStyle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}
