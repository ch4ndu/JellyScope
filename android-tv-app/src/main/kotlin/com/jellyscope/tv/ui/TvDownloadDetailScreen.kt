// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
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
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.TvFocusTrapEffect
import com.jellyscope.tv.ui.focus.rememberChildRequester
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.component.ChromeDimens
import com.jellyscope.ui.component.DetailHeadlineStyle
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.downloads.DownloadDetailUi
import com.jellyscope.ui.screen.downloads.DownloadsUiError
import com.jellyscope.ui.screen.downloads.DownloadsViewModel
import com.jellyscope.ui.screen.downloads.detailUi
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.launch
import com.jellyscope.ui.component.AdaptiveProgressBar as TvProgressBar
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.DetailTitleStyle as TvTitleStyle
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

internal fun tvDownloadDetailFocusKey(
    downloadId: DownloadId,
    action: String,
): String = "download-detail:${downloadId.value}:$action"

@Composable
internal fun TvDownloadDetailScreen(
    session: Session,
    downloadId: DownloadId,
    onBack: () -> Unit,
    onPlayOffline: (DownloadRecord, restart: Boolean) -> Unit,
    viewModel: DownloadsViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detailUi(downloadId)
    var removal by remember(downloadId) { mutableStateOf<TvDetailRemoval?>(null) }

    OnResumeEffect {
        viewModel.refresh()
        viewModel.wakeQueueOnScreenResume()
    }
    LaunchedEffect(downloadId, state.isLoading, detail) {
        if (!state.isLoading && detail == null) onBack()
    }
    if (detail == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TvSpinner(modifier = Modifier.size(TvDimens.settingsRowIconSize))
        }
        return
    }

    val record = detail.record
    TvDownloadDetailContent(
        session = session,
        detail = detail,
        busy = state.isBulkResumeInFlight || state.inFlightDownloadId == record.downloadId.value,
        artifactLeased = record.downloadId.value in state.leasedDownloadIds,
        commandError = state.error,
        viewModel = viewModel,
        onBack = onBack,
        onPlayOffline = onPlayOffline,
        onRequestCancel = { removal = TvDetailRemoval.Cancel },
        onRequestDelete = { removal = TvDetailRemoval.Delete },
    )
    removal?.let { pending ->
        TvDownloadDetailRemovalDialog(
            record = record,
            removal = pending,
            onDismiss = { removal = null },
            onConfirm = {
                removal = null
                when (pending) {
                    TvDetailRemoval.Cancel -> viewModel.cancel(record.downloadId.value)
                    TvDetailRemoval.Delete -> viewModel.delete(record.downloadId.value)
                }
            },
        )
    }
}

@Composable
private fun TvDownloadDetailContent(
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
) {
    val record = detail.record
    val hostedRail = LocalTvHostedRailController.current
    val scope = rememberTvFocusScopeNode(listOf("downloads", "detail", record.downloadId.value))
    val actions = tvDownloadDetailActions(record, busy, artifactLeased)
    val actionFocusKeys = actions.map { option -> tvDownloadDetailFocusKey(record.downloadId, option.action.name) }
    val semanticFocusKeys = actionFocusKeys
    val semanticIndexByFocusKey =
        remember(actionFocusKeys) {
            actionFocusKeys.withIndex().associate { (index, key) -> key to index }
        }
    val listState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    val currentActionFocusKeys by rememberUpdatedState(actionFocusKeys)
    val restoreRequest = scope.restoreRequest()
    var focusedKey by rememberSaveable(record.downloadId.value) { mutableStateOf<String?>(null) }
    var initialContentFocusCompleted by rememberSaveable(record.downloadId.value) { mutableStateOf(false) }

    LaunchedEffect(hostedRail.visible, hostedRail.contentRegistrationKey, scope) {
        hostedRail.setContentRightFocusRequester(hostedRail.contentRegistrationKey, null)
        hostedRail.setContentRightFocusAction(hostedRail.contentRegistrationKey) {
            scope.requestEntry(currentActionFocusKeys)
        }
    }
    LaunchedEffect(
        restoreRequest,
        semanticFocusKeys,
        hostedRail.contentAutofocusSuppressed,
        initialContentFocusCompleted,
    ) {
        when {
            restoreRequest != null ->
                scope.restoreFocus(
                    request = restoreRequest,
                    semanticKeys = semanticFocusKeys,
                    lazySlotIndex = { 0 },
                    revealCentered = { listState.scrollTo(0) },
                )
            !hostedRail.contentAutofocusSuppressed && !initialContentFocusCompleted -> {
                initialContentFocusCompleted = scope.requestEntry(actionFocusKeys)
            }
        }
    }
    LaunchedEffect(focusedKey, semanticFocusKeys, hostedRail.railHasFocus) {
        val previousKey = focusedKey ?: return@LaunchedEffect
        if (!hostedRail.railHasFocus && previousKey !in semanticFocusKeys) {
            scope.requestEntry(actionFocusKeys)
        }
    }
    BackHandler {
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvDownloadArtworkImage(
            session = session,
            record = record,
            role = OfflineArtworkRole.Backdrop,
            viewModel = viewModel,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(TvDimens.detailBackdropWidthFraction)
                    .height(TvDimens.detailBackdropHeight)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(brush = downloadBackdropHorizontalFade, blendMode = BlendMode.DstIn)
                        drawRect(brush = downloadBackdropVerticalFade, blendMode = BlendMode.DstIn)
                    },
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .tvFocusScope(scope) { currentActionFocusKeys }
                    .onPreviewKeyEvent { event ->
                        val direction =
                            when (event.key) {
                                Key.DirectionDown -> 1
                                Key.DirectionUp -> -1
                                else -> 0
                            }
                        val canScroll = if (direction > 0) listState.canScrollForward else listState.canScrollBackward
                        if (direction != 0) {
                            if (canScroll && event.type == KeyEventType.KeyDown) {
                                scrollScope.launch {
                                    listState.scrollBy(direction * listState.viewportSize / 3f)
                                }
                            }
                            true
                        } else {
                            false
                        }
                    }.verticalScroll(listState)
                    .padding(
                        start = if (hostedRail.visible) TvDimens.drawerContentStartPadding else TvDimens.overscanHorizontal,
                        top = TvDimens.overscanVertical + TvDimens.heroTopInset,
                        end = TvDimens.overscanHorizontal,
                        bottom = TvDimens.overscanVertical,
                    ),
            verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TvDimens.detailSectionGap),
            ) {
                TvDownloadDetailHero(session, detail, viewModel)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TvDimens.detailMetaGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEach { option ->
                        val focusKey = tvDownloadDetailFocusKey(record.downloadId, option.action.name)
                        val requester = scope.rememberChildRequester(focusKey)
                        TvFocusableBox(
                            onClick = {
                                when (option.action) {
                                    TvDownloadDetailAction.Play -> onPlayOffline(record, false)
                                    TvDownloadDetailAction.Restart -> onPlayOffline(record, true)
                                    TvDownloadDetailAction.Pause -> viewModel.pause(record.downloadId.value)
                                    TvDownloadDetailAction.ResumeTransfer -> viewModel.resume(record.downloadId.value)
                                    TvDownloadDetailAction.Retry -> viewModel.retry(record.downloadId.value)
                                    TvDownloadDetailAction.Cancel -> onRequestCancel()
                                    TvDownloadDetailAction.Delete -> onRequestDelete()
                                }
                            },
                            enabled = option.enabled,
                            modifier =
                                Modifier
                                    .height(ChromeDimens.detailActionHeight)
                                    .focusRequester(requester)
                                    .onFocusChanged { focus ->
                                        if (focus.isFocused) {
                                            focusedKey = focusKey
                                            initialContentFocusCompleted = true
                                            scope.onChildFocused(
                                                key = focusKey,
                                                kind = TvFocusTargetKind.Action,
                                                semanticIndex = semanticIndexByFocusKey.getValue(focusKey),
                                            )
                                        }
                                    },
                            contentDescription = stringResource(tvDownloadDetailActionResource(option.action, record)),
                            contentAlignment = Alignment.Center,
                            focusedScale = 1f,
                            backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
                            focusedBackgroundColor = Color.White,
                            focusedBorderColor = Color.White,
                            shape = RoundedCornerShape(percent = 50),
                            contentPadding = PaddingValues(ChromeDimens.buttonHorizontalPadding, ChromeDimens.buttonVerticalPadding),
                        ) { focused ->
                            val foreground =
                                if (focused) LocalJellyfinPalette.current.onFocusedLight else LocalJellyfinPalette.current.textPrimary
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(TvDimens.detailIconLabelGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector =
                                        when (option.action) {
                                            TvDownloadDetailAction.Play, TvDownloadDetailAction.ResumeTransfer -> TvIcons.Play
                                            TvDownloadDetailAction.Restart -> TvIcons.Restart
                                            TvDownloadDetailAction.Pause -> TvIcons.Pause
                                            TvDownloadDetailAction.Retry -> Icons.Default.Refresh
                                            TvDownloadDetailAction.Cancel -> Icons.Default.Close
                                            TvDownloadDetailAction.Delete -> Icons.Default.Delete
                                        },
                                    contentDescription = null,
                                    tint = foreground,
                                    modifier = Modifier.size(ChromeDimens.detailActionIconSize),
                                )
                                TvText(
                                    text = stringResource(tvDownloadDetailActionResource(option.action, record)),
                                    style = TvBodyStyle,
                                    color = foreground,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                TvDownloadDetailState(detail, commandError)
            }
            TvDownloadDetailMetadata(detail)
            if (detail.hasDownloadTrackFacts()) {
                TvDownloadTrackFacts(detail)
            }
            if (detail.chapters.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap)) {
                    TvText(stringResource(R.string.tv_downloads_detail_chapters), style = TvTitleStyle, maxLines = 1)
                    detail.chapters.forEachIndexed { index, chapter ->
                        TvDetailFact(
                            label = null,
                            value = "${chapter.name.ifBlank { (index + 1).toString() }} · ${formatDuration(chapter.startMs)}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvDownloadDetailHero(
    session: Session,
    detail: DownloadDetailUi,
    viewModel: DownloadsViewModel,
) {
    val record = detail.record
    val snapshot = record.request.snapshot
    val saved = snapshot.detail
    Column(
        modifier =
            Modifier
                .fillMaxWidth(TvDimens.detailHeroTextWidthFraction)
                .padding(top = TvDimens.detailHeroTopPadding, bottom = TvDimens.detailHeroBottomPadding),
        verticalArrangement = Arrangement.spacedBy(TvDimens.detailHeroTextGap),
    ) {
        TvDownloadArtworkImage(
            session = session,
            record = record,
            role = OfflineArtworkRole.Logo,
            viewModel = viewModel,
            modifier = Modifier.fillMaxWidth().height(ChromeDimens.detailHeroLogoMaxHeight),
            contentScale = ContentScale.Fit,
            emptyContent = { TvText(snapshot.title, style = DetailHeadlineStyle.copy(fontWeight = FontWeight.Bold), maxLines = 2) },
        )
        TvText(
            listOfNotNull(saved?.productionYear?.toString(), tvDownloadDetailIdentity(record), saved?.officialRating).joinToString(" · "),
            style = TvSecondaryStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 2,
        )
        listOfNotNull(snapshot.seriesName, snapshot.seasonLabel, snapshot.episodeLabel)
            .takeIf { it.isNotEmpty() }
            ?.let { labels ->
                TvText(labels.joinToString(" · "), style = TvBodyStyle, maxLines = 1)
            }
        saved?.tagline?.let { TvText(it, style = TvSecondaryStyle, maxLines = 2) }
        saved?.genres?.takeIf { it.isNotEmpty() }?.let {
            TvText(it.joinToString(" · "), style = TvSecondaryStyle, maxLines = 1)
        }
        TvText(
            saved?.overview ?: stringResource(R.string.tv_overview_fallback),
            style = TvBodyStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
    }
}

private val downloadBackdropHorizontalFade = Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black)
private val downloadBackdropVerticalFade =
    Brush.verticalGradient(0f to Color.Black, 0.62f to Color.Black, 1f to Color.Transparent)

@Composable
private fun TvDownloadDetailState(
    detail: DownloadDetailUi,
    commandError: DownloadsUiError?,
) {
    val record = detail.record
    val progress = detail.progressFraction
    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap)) {
        TvText(stringResource(tvDownloadStateResource(record.state)), style = TvSecondaryStyle, maxLines = 1)
        if (progress != null && record.state != DownloadState.Completed) {
            TvProgressBar(progress = progress)
            TvText(
                text =
                    stringResource(
                        R.string.tv_downloads_progress,
                        tvDownloadBytes(record.physicalBytes),
                        tvDownloadBytes(detail.expectedBytes),
                    ),
                style = TvSecondaryStyle,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        } else {
            TvText(
                text = tvDownloadBytes(record.physicalBytes + record.presentationBytes),
                style = TvSecondaryStyle,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        }
        record.localResumePositionMs.takeIf { position -> position > 0L }?.let { position ->
            TvText(
                text = stringResource(R.string.tv_downloads_resume_at, formatDuration(position)),
                style = TvSecondaryStyle,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
            )
        }
        record.failure?.let { failure ->
            TvText(
                text = stringResource(tvDownloadFailureResource(failure)),
                style = TvBodyStyle,
                color = LocalJellyfinPalette.current.error,
                maxLines = 2,
            )
        }
        commandError?.let { error ->
            TvText(
                text = stringResource(tvDownloadsErrorResource(error)),
                style = TvBodyStyle,
                color = LocalJellyfinPalette.current.error,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TvDownloadDetailMetadata(detail: DownloadDetailUi) {
    val snapshot = detail.record.request.snapshot
    val saved = snapshot.detail
    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap)) {
        if (saved == null) {
            TvDetailFact(
                label = null,
                value = stringResource(R.string.tv_downloads_detail_no_saved_metadata),
            )
            return@Column
        }
        TvText(stringResource(R.string.tv_downloads_detail_metadata), style = TvTitleStyle, maxLines = 1)
        saved.tagline?.let { tagline ->
            TvDetailFact(
                label = null,
                value = tagline,
            )
        }
        saved.overview?.let { overview ->
            TvDetailFact(
                label = stringResource(R.string.tv_downloads_detail_overview),
                value = overview,
            )
        }
        listOfNotNull(
            saved.officialRating,
            saved.communityRating?.toString(),
            saved.criticRating?.toString(),
            saved.productionYear?.toString(),
        ).takeIf { values -> values.isNotEmpty() }?.let { values ->
            TvDetailFact(
                label = stringResource(R.string.tv_downloads_detail_metadata),
                value = values.joinToString(" · "),
            )
        }
        saved.genres.takeIf { values -> values.isNotEmpty() }?.let { values ->
            values.forEach { genre ->
                TvDetailFact(
                    label = stringResource(R.string.tv_downloads_detail_genres),
                    value = genre,
                )
            }
        }
        saved.studios.takeIf { values -> values.isNotEmpty() }?.let { values ->
            values.forEach { studio ->
                TvDetailFact(
                    label = stringResource(R.string.tv_downloads_detail_studios),
                    value = studio,
                )
            }
        }
        TvPeopleFacts(
            label = stringResource(R.string.tv_downloads_detail_cast),
            people = detail.cast,
        )
        TvPeopleFacts(
            label = stringResource(R.string.tv_downloads_detail_crew),
            people = detail.crew,
        )
        TvPeopleFacts(
            label = stringResource(R.string.tv_downloads_detail_other_credits),
            people = detail.otherPeople,
        )
        saved.externalProviderIds.imdbId?.let { id ->
            TvDetailFact(
                label = stringResource(R.string.tv_downloads_detail_external_ids),
                value = "IMDb $id",
            )
        }
        saved.externalProviderIds.tmdbId?.let { id ->
            TvDetailFact(
                label = stringResource(R.string.tv_downloads_detail_external_ids),
                value = listOfNotNull("TMDb", saved.externalProviderIds.tmdbItemType, id).joinToString(" "),
            )
        }
    }
}

@Composable
private fun TvDetailFact(
    label: String?,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap)) {
        label?.let { TvText(it, style = TvSecondaryStyle, color = LocalJellyfinPalette.current.textSecondary) }
        TvText(value, style = TvBodyStyle)
    }
}

@Composable
private fun TvPeopleFacts(
    label: String,
    people: List<OfflinePersonSnapshot>,
) {
    people.forEach { person ->
        TvDetailFact(
            label = label,
            value = listOfNotNull(person.name, person.role).joinToString(" — "),
        )
    }
}

@Composable
private fun TvDownloadTrackFacts(detail: DownloadDetailUi) {
    val snapshot = detail.record.request.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap)) {
        snapshot.sourcePresentation?.let { source ->
            TvDetailFact(
                label = stringResource(R.string.tv_downloads_detail_source),
                value = source,
            )
        }
        detail.backend.let { backend ->
            listOfNotNull(
                backend.container,
                backend.videoCodec,
                backend.audioCodec,
                backend.videoWidth?.let { width -> backend.videoHeight?.let { height -> "$width×$height" } },
                stringResource(R.string.tv_downloads_detail_hdr).takeIf { backend.isHdrOrDolbyVision },
            ).takeIf { values -> values.isNotEmpty() }?.let { values ->
                TvDetailFact(
                    label = stringResource(R.string.tv_downloads_detail_backend),
                    value = values.joinToString(" · "),
                )
            }
            backend.frameRateLabel?.let { frameRate ->
                TvDetailFact(
                    label = stringResource(R.string.tv_downloads_detail_frame_rate),
                    value = stringResource(R.string.tv_downloads_detail_frame_rate_value, frameRate),
                )
            }
        }
        TvTrackFacts(
            label = stringResource(R.string.tv_downloads_detail_audio),
            tracks = detail.audioTracks,
            selected = snapshot.selectedAudioTrack,
        )
        TvTrackFacts(
            label = stringResource(R.string.tv_downloads_detail_subtitles),
            tracks = detail.subtitleTracks,
            selected = snapshot.selectedSubtitleTrack,
        )
        TvDetailFact(
            label = null,
            value = tvDownloadDetailQuality(detail.record),
        )
    }
}

@Composable
private fun TvTrackFacts(
    label: String,
    tracks: List<OfflineTrackSnapshot>,
    selected: OfflineTrackSnapshot?,
) {
    val visible = visibleDownloadTracks(tracks, selected)
    visible.forEach { track ->
        val trackLabel =
            track.label
                ?: track.language
                ?: track.codec
                ?: stringResource(R.string.tv_downloads_detail_unknown_track)
        val value =
            if (track == selected || (selected == null && track.isDefault)) {
                stringResource(R.string.tv_downloads_detail_track_default, trackLabel)
            } else {
                trackLabel
            }
        TvDetailFact(
            label = label,
            value = value,
        )
    }
}

private fun DownloadDetailUi.hasDownloadTrackFacts(): Boolean {
    val snapshot = record.request.snapshot
    val backend = backend
    return audioTracks.isNotEmpty() ||
        subtitleTracks.isNotEmpty() ||
        snapshot.selectedAudioTrack != null ||
        snapshot.selectedSubtitleTrack != null ||
        snapshot.sourcePresentation != null ||
        backend.container != null ||
        backend.videoCodec != null ||
        backend.audioCodec != null ||
        (backend.videoWidth != null && backend.videoHeight != null) ||
        backend.frameRateLabel != null ||
        backend.isHdrOrDolbyVision
}

private fun visibleDownloadTracks(
    tracks: List<OfflineTrackSnapshot>,
    selected: OfflineTrackSnapshot?,
): List<OfflineTrackSnapshot> = if (tracks.isEmpty()) listOfNotNull(selected) else tracks

@Composable
private fun tvDownloadDetailQuality(record: DownloadRecord): String =
    when (val quality = record.request.quality) {
        DownloadQuality.Original -> stringResource(R.string.tv_downloads_quality_original)
        is DownloadQuality.Fixed ->
            stringResource(R.string.tv_downloads_quality_fixed, quality.rung.height, formatBitrateMbps(quality.maxBitrateBps))
    }

@Composable
internal fun TvDownloadArtworkImage(
    session: Session,
    record: DownloadRecord,
    role: OfflineArtworkRole,
    viewModel: DownloadsViewModel,
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
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TvIcons.Download,
                contentDescription = null,
                tint = LocalJellyfinPalette.current.textSecondary,
                modifier = Modifier.size(TvDimens.settingsRowIconSize),
            )
        }
    } else {
        val context = LocalPlatformContext.current
        val cacheKey =
            remember(
                session.serverId,
                session.userId,
                record.downloadId,
                record.attemptGeneration,
                record.presentationBytes,
                role,
            ) {
                val account = session.accountIdentity()
                "tv-download-artwork:${account.serverId.length}:${account.serverId}:${account.userId.length}:${account.userId}:" +
                    "${record.downloadId.value}:${record.attemptGeneration}:${record.presentationBytes}:${role.name}"
            }
        val request =
            remember(context, imageBytes, cacheKey) {
                ImageRequest
                    .Builder(context)
                    .data(imageBytes)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .build()
            }
        AsyncImage(model = request, contentDescription = null, modifier = modifier, contentScale = contentScale)
    }
}

@Composable
private fun TvDownloadDetailRemovalDialog(
    record: DownloadRecord,
    removal: TvDetailRemoval,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val deleting = removal == TvDetailRemoval.Delete
    val requester = remember(removal) { FocusRequester() }
    TvFocusTrapEffect()
    LaunchedEffect(requester) { requestTvFocusWithRetry { requester.requestFocusSafely() } }
    TvSettingsDialogFrame(
        title = stringResource(if (deleting) R.string.tv_downloads_delete_title else R.string.tv_downloads_cancel_title),
        onDismiss = onDismiss,
    ) {
        TvText(
            text =
                stringResource(
                    if (deleting) R.string.tv_downloads_delete_message else R.string.tv_downloads_cancel_message,
                    record.request.snapshot.title,
                ),
            style = TvBodyStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 3,
        )
        TvSettingsDialogAction(
            text = stringResource(if (deleting) R.string.tv_downloads_delete_confirm else R.string.tv_downloads_cancel_confirm),
            focusRequester = requester,
            onClick = onConfirm,
            destructive = true,
        )
    }
}

private enum class TvDetailRemoval { Cancel, Delete }

private enum class TvDownloadDetailAction { Play, Restart, Pause, ResumeTransfer, Retry, Cancel, Delete }

private data class TvDownloadDetailActionOption(
    val action: TvDownloadDetailAction,
    val enabled: Boolean,
)

private fun tvDownloadDetailActions(
    record: DownloadRecord,
    busy: Boolean,
    artifactLeased: Boolean,
): List<TvDownloadDetailActionOption> =
    buildList {
        when (record.state) {
            DownloadState.Completed -> {
                add(TvDownloadDetailActionOption(TvDownloadDetailAction.Play, enabled = true))
                if (record.localResumePositionMs > 0L) add(TvDownloadDetailActionOption(TvDownloadDetailAction.Restart, enabled = true))
                add(TvDownloadDetailActionOption(TvDownloadDetailAction.Delete, enabled = !busy && !artifactLeased))
            }
            DownloadState.Downloading -> add(TvDownloadDetailActionOption(TvDownloadDetailAction.Pause, enabled = !busy))
            DownloadState.Paused,
            DownloadState.BlockedByQuota,
            -> add(TvDownloadDetailActionOption(TvDownloadDetailAction.ResumeTransfer, enabled = !busy))
            DownloadState.Failed -> add(TvDownloadDetailActionOption(TvDownloadDetailAction.Retry, enabled = !busy))
            DownloadState.Queued,
            DownloadState.Finalizing,
            DownloadState.NotDownloaded,
            -> Unit
        }
        if (record.state != DownloadState.Completed) add(TvDownloadDetailActionOption(TvDownloadDetailAction.Cancel, enabled = !busy))
    }

@Composable
private fun tvDownloadDetailIdentity(record: DownloadRecord): String {
    val snapshot = record.request.snapshot
    val kind =
        stringResource(
            when (snapshot.itemKind) {
                com.jellyscope.core.domain.model.MediaKind.Movie -> R.string.tv_downloads_kind_movie
                com.jellyscope.core.domain.model.MediaKind.Episode -> R.string.tv_downloads_kind_episode
                else -> R.string.tv_downloads_kind_video
            },
        )
    val duration =
        snapshot.durationMs?.let { durationMs ->
            stringResource(R.string.tv_downloads_duration_minutes, durationMs / 60_000L)
        } ?: stringResource(R.string.tv_downloads_duration_unknown)
    return stringResource(R.string.tv_downloads_item_details, kind, duration, tvDownloadDetailQuality(record))
}

private fun tvDownloadDetailActionResource(
    action: TvDownloadDetailAction,
    record: DownloadRecord,
): Int =
    when (action) {
        TvDownloadDetailAction.Play ->
            if (record.localResumePositionMs > 0L) {
                R.string.tv_downloads_action_resume_playback
            } else {
                R.string.tv_downloads_action_play
            }
        TvDownloadDetailAction.Restart -> R.string.tv_downloads_detail_restart
        TvDownloadDetailAction.Pause -> R.string.tv_downloads_action_pause
        TvDownloadDetailAction.ResumeTransfer -> R.string.tv_downloads_action_resume
        TvDownloadDetailAction.Retry -> R.string.tv_downloads_action_retry
        TvDownloadDetailAction.Cancel -> R.string.tv_downloads_action_cancel
        TvDownloadDetailAction.Delete -> R.string.tv_downloads_action_delete
    }
