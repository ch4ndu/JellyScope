// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.ui.component.BackButton
import com.jellyscope.ui.component.CardImageAspect
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.rememberCardImageDecode
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.player_continue_watching
import com.jellyscope.ui.generated.resources.player_dismiss
import com.jellyscope.ui.generated.resources.player_play_now
import com.jellyscope.ui.generated.resources.player_queue_now_playing
import com.jellyscope.ui.generated.resources.player_shuffle_queue
import com.jellyscope.ui.generated.resources.player_still_watching
import com.jellyscope.ui.generated.resources.player_still_watching_message
import com.jellyscope.ui.generated.resources.player_up_next
import com.jellyscope.ui.generated.resources.player_up_next_autoplay_disabled
import com.jellyscope.ui.generated.resources.player_up_next_countdown
import com.jellyscope.ui.generated.resources.player_up_next_waiting
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun QueueSection(
    playlist: PlaylistUi,
    onPlayQueueItem: (Int) -> Unit,
    onShuffleQueue: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        PickerRow(
            selected = false,
            title = stringResource(Res.string.player_shuffle_queue),
            onClick = onShuffleQueue,
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            itemsIndexed(
                items = playlist.items,
                key = { _, item -> item.id },
            ) { index, item ->
                val selected = index == playlist.currentIndex
                val nowPlaying = stringResource(Res.string.player_queue_now_playing)
                PickerRow(
                    selected = selected,
                    title = item.title,
                    secondary =
                        queueSecondaryLabel(
                            seasonNumber = item.seasonNumber,
                            episodeNumber = item.episodeNumber,
                            nowPlayingLabel = nowPlaying,
                            selected = selected,
                        ),
                    onClick = { onPlayQueueItem(index) },
                )
            }
        }
    }
}

internal fun queueSecondaryLabel(
    seasonNumber: Int?,
    episodeNumber: Int?,
    nowPlayingLabel: String,
    selected: Boolean,
): String? =
    listOfNotNull(
        episodeNumberLabel(seasonNumber, episodeNumber),
        nowPlayingLabel.takeIf { selected },
    ).joinToString(" · ").ifEmpty { null }

@Composable
internal fun SkipSegmentButton(
    segment: MediaSegment,
    onSkipCurrentSegment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skipLabel = stringResource(skipSegmentLabel(segment.type))
    Button(
        onClick = onSkipCurrentSegment,
        modifier =
            modifier
                .heightIn(min = Dimensions.minTouchTarget)
                .semantics { contentDescription = skipLabel },
    ) {
        Text(skipLabel)
    }
}

@Composable
internal fun UpNextCard(
    upNext: UpNextInfo,
    session: Session,
    autoplayPolicy: AutoplayPolicySnapshot,
    countdownStarted: Boolean,
    onPlayNext: (Boolean, Long?) -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remainingMs =
        rememberAutoplayCountdownMillis(
            policy = autoplayPolicy,
            countdownStarted = countdownStarted,
            active = true,
            tickMs = 1_000L,
            onAutoAdvance = { onPlayNext(true, autoplayPolicy.countdownKey.playbackGeneration) },
        )
    val remainingSeconds = ((remainingMs ?: 0L) / 1_000L).coerceAtLeast(1L)

    Surface(
        modifier = modifier.width(Dimensions.playerOverlayCardWidth),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.formSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            Text(
                text = stringResource(Res.string.player_up_next),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            upNext.imageUrl?.let { imageUrl ->
                AsyncImage(
                    model =
                        authenticatedImageRequest(
                            imageUrl,
                            session,
                            rememberCardImageDecode(Dimensions.playerOverlayCardWidth, CardImageAspect.Wide),
                        ),
                    contentDescription = upNext.title,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(Dimensions.listThumbnailAspectRatio)
                            .clip(MaterialTheme.shapes.small),
                )
            }
            Text(
                text = upNext.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
            episodeNumberLabel(upNext.seasonNumber, upNext.episodeNumber)?.let { episodeLabel ->
                Text(
                    text = episodeLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            Text(
                text =
                    if (countdownStarted) {
                        if (autoplayPolicy.enabled) {
                            stringResource(Res.string.player_up_next_countdown, remainingSeconds)
                        } else {
                            stringResource(Res.string.player_up_next_autoplay_disabled)
                        }
                    } else {
                        stringResource(Res.string.player_up_next_waiting)
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                Button(
                    onClick = {
                        // Keep the card when the switch is refused.
                        if (onPlayNext(false, null)) {
                            onDismiss()
                        }
                    },
                    modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                ) {
                    Text(stringResource(Res.string.player_play_now))
                }
                OutlinedButton(
                    // Dismiss only the card and countdown.
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                ) {
                    Text(stringResource(Res.string.player_dismiss))
                }
            }
        }
    }
}

@Composable
internal fun StillWatchingCard(
    onConfirmStillWatching: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(Dimensions.playerOverlayCardWidth),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.formSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            Text(
                text = stringResource(Res.string.player_still_watching),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.player_still_watching_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                Button(
                    onClick = onConfirmStillWatching,
                    modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                ) {
                    Text(stringResource(Res.string.player_continue_watching))
                }
                BackButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                )
            }
        }
    }
}
