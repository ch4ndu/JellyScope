// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jellyscope.ui.screen.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.OfflineArtworkRole
import com.jellyscope.core.domain.model.OfflinePersonSnapshot
import com.jellyscope.core.domain.model.OfflineTrackSnapshot
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.formatBitrateMbps
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.download_action_cancel
import com.jellyscope.ui.generated.resources.download_action_delete
import com.jellyscope.ui.generated.resources.download_action_pause
import com.jellyscope.ui.generated.resources.download_action_play
import com.jellyscope.ui.generated.resources.download_action_resume
import com.jellyscope.ui.generated.resources.download_action_retry
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_cancel
import com.jellyscope.ui.generated.resources.downloads_artifact_in_use
import com.jellyscope.ui.generated.resources.downloads_cancel_confirm
import com.jellyscope.ui.generated.resources.downloads_cancel_message
import com.jellyscope.ui.generated.resources.downloads_cancel_title
import com.jellyscope.ui.generated.resources.downloads_delete_message
import com.jellyscope.ui.generated.resources.downloads_delete_title
import com.jellyscope.ui.generated.resources.downloads_detail_audio
import com.jellyscope.ui.generated.resources.downloads_detail_backend
import com.jellyscope.ui.generated.resources.downloads_detail_cast
import com.jellyscope.ui.generated.resources.downloads_detail_chapters
import com.jellyscope.ui.generated.resources.downloads_detail_crew
import com.jellyscope.ui.generated.resources.downloads_detail_external_ids
import com.jellyscope.ui.generated.resources.downloads_detail_frame_rate
import com.jellyscope.ui.generated.resources.downloads_detail_genres
import com.jellyscope.ui.generated.resources.downloads_detail_hdr
import com.jellyscope.ui.generated.resources.downloads_detail_metadata
import com.jellyscope.ui.generated.resources.downloads_detail_no_saved_metadata
import com.jellyscope.ui.generated.resources.downloads_detail_other_credits
import com.jellyscope.ui.generated.resources.downloads_detail_overview
import com.jellyscope.ui.generated.resources.downloads_detail_restart
import com.jellyscope.ui.generated.resources.downloads_detail_source
import com.jellyscope.ui.generated.resources.downloads_detail_studios
import com.jellyscope.ui.generated.resources.downloads_detail_subtitles
import com.jellyscope.ui.generated.resources.downloads_detail_track_default
import com.jellyscope.ui.generated.resources.downloads_detail_unknown_track
import com.jellyscope.ui.generated.resources.downloads_error
import com.jellyscope.ui.generated.resources.downloads_failure_missing
import com.jellyscope.ui.generated.resources.downloads_failure_network
import com.jellyscope.ui.generated.resources.downloads_failure_permission
import com.jellyscope.ui.generated.resources.downloads_failure_quota
import com.jellyscope.ui.generated.resources.downloads_failure_size
import com.jellyscope.ui.generated.resources.downloads_failure_source
import com.jellyscope.ui.generated.resources.downloads_failure_storage
import com.jellyscope.ui.generated.resources.downloads_failure_unsupported
import com.jellyscope.ui.generated.resources.downloads_item_details
import com.jellyscope.ui.generated.resources.downloads_item_duration
import com.jellyscope.ui.generated.resources.downloads_item_episode
import com.jellyscope.ui.generated.resources.downloads_item_movie
import com.jellyscope.ui.generated.resources.downloads_quality_fixed
import com.jellyscope.ui.generated.resources.downloads_quality_original
import com.jellyscope.ui.generated.resources.downloads_title
import com.jellyscope.ui.generated.resources.downloads_usage_error
import com.jellyscope.ui.generated.resources.subtitles_fps
import com.jellyscope.ui.screen.detail.DetailDimens
import com.jellyscope.ui.screen.detail.detailBackdropHorizontalFade
import com.jellyscope.ui.screen.detail.detailBackdropVerticalFade
import com.jellyscope.ui.screen.player.formatDuration
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Full local-only detail page for one account-qualified download. */
@Composable
fun DownloadDetailScreen(
    session: Session,
    downloadId: DownloadId,
    onBack: () -> Unit,
    onPlayOffline: (DownloadRecord, restart: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
    viewModel: DownloadsViewModel =
        koinViewModel(
            parameters = { parametersOf(session) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detailUi(downloadId)
    var removal by remember(downloadId) { mutableStateOf<DownloadDetailRemoval?>(null) }

    OnResumeEffect {
        viewModel.refresh()
        viewModel.wakeQueueOnScreenResume()
    }

    LaunchedEffect(downloadId, state.isLoading, detail) {
        if (!state.isLoading && detail == null) onBack()
    }

    if (detail == null) {
        DownloadDetailMissing(
            onBack = onBack,
            modifier = modifier,
            bottomContentPadding = bottomContentPadding,
        )
        return
    }

    DownloadDetailContent(
        session = session,
        detail = detail,
        busy = state.isBulkResumeInFlight || state.inFlightDownloadId == detail.record.downloadId.value,
        artifactLeased = detail.record.downloadId.value in state.leasedDownloadIds,
        commandError = state.error,
        viewModel = viewModel,
        onBack = onBack,
        onPlayOffline = onPlayOffline,
        onRequestCancel = { removal = DownloadDetailRemoval.Cancel },
        onRequestDelete = { removal = DownloadDetailRemoval.Delete },
        bottomContentPadding = bottomContentPadding,
        modifier = modifier,
    )
    removal?.let { pending ->
        DownloadDetailRemovalDialog(
            record = detail.record,
            removal = pending,
            onDismiss = { removal = null },
            onConfirm = {
                removal = null
                when (pending) {
                    DownloadDetailRemoval.Cancel -> viewModel.cancel(detail.record.downloadId.value)
                    DownloadDetailRemoval.Delete -> viewModel.delete(detail.record.downloadId.value)
                }
            },
        )
    }
}

@Composable
private fun DownloadDetailMissing(
    onBack: () -> Unit,
    modifier: Modifier,
    bottomContentPadding: Dp,
) {
    Box(modifier = modifier.fillMaxSize()) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        AppTopBar(
            title = stringResource(Res.string.downloads_title),
            visible = true,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun DownloadDetailContent(
    session: Session,
    detail: DownloadDetailUi,
    busy: Boolean,
    artifactLeased: Boolean,
    commandError: DownloadsUiError?,
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    onPlayOffline: (DownloadRecord, Boolean) -> Unit,
    onRequestCancel: () -> Unit,
    onRequestDelete: () -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier,
) {
    val horizontalPadding = adaptiveHorizontalContentPadding()
    val record = detail.record
    val snapshot = record.request.snapshot
    val compact = LocalWindowWidthTier.current == WindowWidthTier.Compact
    Box(modifier = modifier.fillMaxSize()) {
        if (!compact) {
            DownloadArtworkImage(
                session = session,
                record = record,
                role = OfflineArtworkRole.Backdrop,
                viewModel = viewModel,
                contentDescription = null,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(DetailDimens.detailBackdropWidthFraction)
                        .height(DetailDimens.detailBackdropHeight)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(brush = detailBackdropHorizontalFade, blendMode = BlendMode.DstIn)
                            drawRect(brush = detailBackdropVerticalFade, blendMode = BlendMode.DstIn)
                        },
            )
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = horizontalPadding.start,
                    top = appTopBarContentPadding(),
                    end = horizontalPadding.end,
                    bottom = maxOf(appNavigationBarContentPadding(), Dimensions.screenPadding + bottomContentPadding),
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.detailSectionSpacing),
        ) {
            item(key = "download-detail:hero") {
                DownloadDetailHero(
                    session = session,
                    detail = detail,
                    viewModel = viewModel,
                )
            }
            item(key = "download-detail:actions") {
                DownloadDetailActions(
                    record = record,
                    busy = busy,
                    artifactLeased = artifactLeased,
                    onPlayOffline = onPlayOffline,
                    onPause = { viewModel.pause(record.downloadId.value) },
                    onResume = { viewModel.resume(record.downloadId.value) },
                    onRetry = { viewModel.retry(record.downloadId.value) },
                    onRequestCancel = onRequestCancel,
                    onRequestDelete = onRequestDelete,
                )
            }
            item(key = "download-detail:local-state") {
                DownloadLocalState(
                    detail = detail,
                    artifactLeased = artifactLeased,
                    commandError = commandError,
                )
            }
            snapshot.detail?.let {
                item(key = "download-detail:metadata") {
                    DownloadSavedMetadata(detail = detail)
                }
            } ?: item(key = "download-detail:sparse-metadata") {
                Text(
                    text = stringResource(Res.string.downloads_detail_no_saved_metadata),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (
                detail.audioTracks.isNotEmpty() ||
                detail.subtitleTracks.isNotEmpty() ||
                snapshot.selectedAudioTrack != null ||
                snapshot.selectedSubtitleTrack != null ||
                snapshot.sourcePresentation != null
            ) {
                item(key = "download-detail:tracks") {
                    DownloadTrackFacts(detail = detail)
                }
            }
            if (detail.chapters.isNotEmpty()) {
                item(key = "download-detail:chapters") {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                        Text(stringResource(Res.string.downloads_detail_chapters), style = MaterialTheme.typography.titleMedium)
                        detail.chapters.forEachIndexed { index, chapter ->
                            Text(
                                text = "${chapter.name.ifBlank { (index + 1).toString() }} · ${formatDuration(chapter.startMs)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
        AppTopBar(
            title = snapshot.title,
            visible = true,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun DownloadDetailHero(
    session: Session,
    detail: DownloadDetailUi,
    viewModel: DownloadsViewModel,
) {
    val record = detail.record
    val snapshot = record.request.snapshot
    val compact = LocalWindowWidthTier.current == WindowWidthTier.Compact
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing)) {
        if (compact) {
            DownloadArtworkImage(
                session = session,
                record = record,
                role = OfflineArtworkRole.Backdrop,
                viewModel = viewModel,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(Dimensions.libraryCardAspectRatio),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(if (compact) 1f else DetailDimens.detailHeroTextWidthFraction),
            verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        ) {
            DownloadArtworkImage(
                session = session,
                record = record,
                role = OfflineArtworkRole.Logo,
                viewModel = viewModel,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(DetailDimens.detailHeroLogoMaxHeight),
                contentScale = ContentScale.Fit,
                emptyContent = { Text(snapshot.title, style = MaterialTheme.typography.headlineMedium) },
            )
            Text(
                downloadIdentityText(record),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            snapshot.detail?.tagline?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!compact) {
                snapshot.detail?.overview?.let { Text(it, maxLines = 3, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun DownloadDetailActions(
    record: DownloadRecord,
    busy: Boolean,
    artifactLeased: Boolean,
    onPlayOffline: (DownloadRecord, Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRequestCancel: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
    ) {
        when (record.state) {
            DownloadState.Completed -> {
                if (record.localResumePositionMs > 0L) {
                    Button(onClick = { onPlayOffline(record, false) }, modifier = Modifier) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = Dimensions.inlineSpacing))
                        Text(stringResource(Res.string.download_action_resume))
                    }
                    OutlinedButton(onClick = { onPlayOffline(record, true) }, modifier = Modifier) {
                        Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.padding(end = Dimensions.inlineSpacing))
                        Text(stringResource(Res.string.downloads_detail_restart))
                    }
                } else {
                    Button(onClick = { onPlayOffline(record, false) }, modifier = Modifier) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = Dimensions.inlineSpacing))
                        Text(stringResource(Res.string.download_action_play))
                    }
                }
                OutlinedButton(
                    onClick = onRequestDelete,
                    enabled = !busy && !artifactLeased,
                    modifier = Modifier,
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = Dimensions.inlineSpacing))
                    Text(stringResource(Res.string.download_action_delete))
                }
            }
            DownloadState.Downloading -> DownloadDetailCommand(Icons.Filled.Pause, Res.string.download_action_pause, !busy, onPause)
            DownloadState.Paused,
            DownloadState.BlockedByQuota,
            -> DownloadDetailCommand(Icons.Filled.PlayArrow, Res.string.download_action_resume, !busy, onResume)
            DownloadState.Failed -> DownloadDetailCommand(Icons.Filled.Replay, Res.string.download_action_retry, !busy, onRetry)
            DownloadState.Queued,
            DownloadState.Finalizing,
            DownloadState.NotDownloaded,
            -> Unit
        }
        if (record.state != DownloadState.Completed) {
            OutlinedButton(onClick = onRequestCancel, enabled = !busy, modifier = Modifier) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = Dimensions.inlineSpacing))
                Text(stringResource(Res.string.download_action_cancel))
            }
        }
    }
}

@Composable
private fun DownloadDetailCommand(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: org.jetbrains.compose.resources.StringResource,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = Dimensions.inlineSpacing))
        Text(stringResource(label))
    }
}

@Composable
private fun DownloadLocalState(
    detail: DownloadDetailUi,
    artifactLeased: Boolean,
    commandError: DownloadsUiError?,
) {
    val record = detail.record
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
    ) {
        Text(stringResource(record.state.labelResource()), style = MaterialTheme.typography.titleMedium)
        Text(
            text = downloadIdentityText(record),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (detail.progressFraction != null && record.state != DownloadState.Completed) {
            LinearProgressIndicator(progress = { detail.progressFraction }, modifier = Modifier.fillMaxWidth())
            Text(
                text = "${formatIntegerBytes(record.physicalBytes)} / ${formatIntegerBytes(detail.expectedBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                text = formatIntegerBytes(record.physicalBytes + record.presentationBytes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        record.localResumePositionMs.takeIf { position -> position > 0L }?.let { position ->
            Text(
                text = formatDuration(position),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        record.failure?.let { failure ->
            Text(
                text = stringResource(downloadFailureGuidance(failure)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (artifactLeased) {
            Text(
                text = stringResource(Res.string.downloads_artifact_in_use),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        commandError?.let { error ->
            Text(
                text = stringResource(downloadsDetailErrorResource(error)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DownloadSavedMetadata(detail: DownloadDetailUi) {
    val saved = requireNotNull(detail.record.request.snapshot.detail)
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        Text(stringResource(Res.string.downloads_detail_metadata), style = MaterialTheme.typography.titleMedium)
        saved.tagline?.let { tagline -> Text(tagline, style = MaterialTheme.typography.titleSmall) }
        saved.overview?.let { overview ->
            DownloadDetailLabeledText(stringResource(Res.string.downloads_detail_overview), overview)
        }
        val ratings =
            listOfNotNull(
                saved.officialRating,
                saved.communityRating?.toString(),
                saved.criticRating?.toString(),
                saved.productionYear?.toString(),
            )
        if (ratings.isNotEmpty()) {
            DownloadDetailLabeledText(
                stringResource(Res.string.downloads_detail_metadata),
                ratings.joinToString(" · "),
            )
        }
        saved.genres.takeIf { values -> values.isNotEmpty() }?.let { values ->
            DownloadDetailLabeledText(stringResource(Res.string.downloads_detail_genres), values.joinToString(" · "))
        }
        saved.studios.takeIf { values -> values.isNotEmpty() }?.let { values ->
            DownloadDetailLabeledText(stringResource(Res.string.downloads_detail_studios), values.joinToString(" · "))
        }
        DownloadPeopleSection(stringResource(Res.string.downloads_detail_cast), detail.cast)
        DownloadPeopleSection(stringResource(Res.string.downloads_detail_crew), detail.crew)
        DownloadPeopleSection(stringResource(Res.string.downloads_detail_other_credits), detail.otherPeople)
        saved.externalProviderIds.let { providers ->
            listOfNotNull(
                providers.imdbId?.let { id -> "IMDb $id" },
                providers.tmdbId?.let { id -> listOfNotNull("TMDb", providers.tmdbItemType, id).joinToString(" ") },
            ).takeIf { values -> values.isNotEmpty() }?.let { values ->
                DownloadDetailLabeledText(stringResource(Res.string.downloads_detail_external_ids), values.joinToString(" · "))
            }
        }
    }
}

@Composable
private fun DownloadPeopleSection(
    title: String,
    people: List<OfflinePersonSnapshot>,
) {
    if (people.isEmpty()) return
    DownloadDetailLabeledText(
        label = title,
        value = people.joinToString(" · ") { person -> listOfNotNull(person.name, person.role).joinToString(" — ") },
    )
}

@Composable
private fun DownloadDetailLabeledText(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.zero)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DownloadTrackFacts(detail: DownloadDetailUi) {
    val snapshot = detail.record.request.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
        snapshot.sourcePresentation?.let { presentation ->
            DownloadDetailLabeledText(stringResource(Res.string.downloads_detail_source), presentation)
        }
        detail.backend.let { backend ->
            listOfNotNull(
                backend.container,
                backend.videoCodec,
                backend.audioCodec,
                backend.videoWidth?.let { width -> backend.videoHeight?.let { height -> "$width×$height" } },
                stringResource(Res.string.downloads_detail_hdr).takeIf { backend.isHdrOrDolbyVision },
            ).takeIf { values -> values.isNotEmpty() }?.let { values ->
                DownloadDetailLabeledText(stringResource(Res.string.downloads_detail_backend), values.joinToString(" · "))
            }
            backend.frameRateLabel?.let { frameRate ->
                DownloadDetailLabeledText(
                    label = stringResource(Res.string.downloads_detail_frame_rate),
                    value = stringResource(Res.string.subtitles_fps, frameRate),
                )
            }
        }
        DownloadTrackGroup(
            label = stringResource(Res.string.downloads_detail_audio),
            tracks = detail.audioTracks,
            selected = snapshot.selectedAudioTrack,
        )
        DownloadTrackGroup(
            label = stringResource(Res.string.downloads_detail_subtitles),
            tracks = detail.subtitleTracks,
            selected = snapshot.selectedSubtitleTrack,
        )
        Text(
            text = downloadQualityLabel(detail.record),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DownloadTrackGroup(
    label: String,
    tracks: List<OfflineTrackSnapshot>,
    selected: OfflineTrackSnapshot?,
) {
    val visible = if (tracks.isEmpty()) listOfNotNull(selected) else tracks
    if (visible.isEmpty()) return
    DownloadDetailLabeledText(
        label = label,
        value =
            visible
                .map { track ->
                    val trackLabel =
                        track.label ?: track.language ?: track.codec ?: stringResource(Res.string.downloads_detail_unknown_track)
                    if (track == selected || (selected == null && track.isDefault)) {
                        stringResource(Res.string.downloads_detail_track_default, trackLabel)
                    } else {
                        trackLabel
                    }
                }.joinToString(" · "),
    )
}

@Composable
private fun downloadQualityLabel(record: DownloadRecord): String =
    when (val quality = record.request.quality) {
        DownloadQuality.Original -> stringResource(Res.string.downloads_quality_original)
        is DownloadQuality.Fixed -> stringResource(Res.string.downloads_quality_fixed, formatBitrateMbps(quality.maxBitrateBps))
    }

@Composable
private fun downloadIdentityText(record: DownloadRecord): String {
    val snapshot = record.request.snapshot
    val kind =
        stringResource(
            when (snapshot.itemKind) {
                com.jellyscope.core.domain.model.MediaKind.Movie -> Res.string.downloads_item_movie
                com.jellyscope.core.domain.model.MediaKind.Episode -> Res.string.downloads_item_episode
                else -> Res.string.downloads_item_movie
            },
        )
    val duration =
        snapshot.durationMs?.let { durationMs ->
            stringResource(Res.string.downloads_item_duration, durationMs / 60_000L)
        } ?: formatDuration(null)
    return stringResource(Res.string.downloads_item_details, kind, duration, downloadQualityLabel(record))
}

@Composable
internal fun DownloadArtworkImage(
    session: Session,
    record: DownloadRecord,
    role: OfflineArtworkRole,
    viewModel: DownloadsViewModel,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    emptyContent: (@Composable () -> Unit)? = null,
) {
    val bytes by produceState<ByteArray?>(
        initialValue = null,
        session.serverId,
        session.userId,
        record.downloadId,
        record.attemptGeneration,
        record.state,
        record.presentationBytes,
        role,
    ) {
        value = null
        value = viewModel.readArtwork(record.downloadId, role)
    }
    val imageBytes = bytes
    if (imageBytes == null) {
        if (role != OfflineArtworkRole.Poster) {
            emptyContent?.invoke()
            return
        }
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = contentDescription,
                modifier = Modifier.padding(Dimensions.formSpacing),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        val platformContext = LocalPlatformContext.current
        val cacheKey =
            remember(
                session.serverId,
                session.userId,
                record.downloadId,
                record.attemptGeneration,
                record.presentationBytes,
                role,
            ) {
                downloadArtworkCacheKey(session, record, role)
            }
        val request =
            remember(platformContext, imageBytes, cacheKey) {
                ImageRequest
                    .Builder(platformContext)
                    .data(imageBytes)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .build()
            }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

private fun downloadArtworkCacheKey(
    session: Session,
    record: DownloadRecord,
    role: OfflineArtworkRole,
): String {
    val account = session.accountIdentity()
    return "download-artwork:${account.serverId.length}:${account.serverId}:" +
        "${account.userId.length}:${account.userId}:${record.downloadId.value}:${record.attemptGeneration}:" +
        "${record.presentationBytes}:${role.name}"
}

@Composable
private fun DownloadDetailRemovalDialog(
    record: DownloadRecord,
    removal: DownloadDetailRemoval,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val deleting = removal == DownloadDetailRemoval.Delete
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (deleting) Res.string.downloads_delete_title else Res.string.downloads_cancel_title,
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    if (deleting) Res.string.downloads_delete_message else Res.string.downloads_cancel_message,
                    record.request.snapshot.title,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (deleting) Res.string.download_action_delete else Res.string.downloads_cancel_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.downloads_allocation_dialog_cancel)) } },
    )
}

private enum class DownloadDetailRemoval {
    Cancel,
    Delete,
}

private fun downloadFailureGuidance(failure: DownloadFailure): org.jetbrains.compose.resources.StringResource =
    when (failure) {
        DownloadFailure.PermissionDenied -> Res.string.downloads_failure_permission
        DownloadFailure.SizeUnavailable -> Res.string.downloads_failure_size
        DownloadFailure.Network,
        DownloadFailure.ServerUnavailable,
        -> Res.string.downloads_failure_network
        DownloadFailure.SourceChanged -> Res.string.downloads_failure_source
        DownloadFailure.QuotaExceeded -> Res.string.downloads_failure_quota
        DownloadFailure.DeviceStorageLow -> Res.string.downloads_failure_storage
        DownloadFailure.MissingArtifact -> Res.string.downloads_failure_missing
        DownloadFailure.UnsupportedArtifact -> Res.string.downloads_failure_unsupported
        DownloadFailure.ArtifactInUse -> Res.string.downloads_artifact_in_use
    }

private fun downloadsDetailErrorResource(error: DownloadsUiError): org.jetbrains.compose.resources.StringResource =
    when (error) {
        DownloadsUiError.LoadFailed -> Res.string.downloads_error
        DownloadsUiError.CommandRejected,
        DownloadsUiError.QuotaRejected,
        -> Res.string.downloads_usage_error
        DownloadsUiError.ArtifactInUse -> Res.string.downloads_artifact_in_use
    }
