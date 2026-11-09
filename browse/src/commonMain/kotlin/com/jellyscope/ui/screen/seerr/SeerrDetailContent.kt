// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.jellyscope.core.domain.model.SeerrAvailability
import com.jellyscope.core.domain.model.SeerrMediaCard
import com.jellyscope.core.domain.model.SeerrMediaType
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuota
import com.jellyscope.core.domain.model.SeerrQuotaKind
import com.jellyscope.core.domain.model.SeerrRequestStatus
import com.jellyscope.core.domain.model.SeerrRequestSummary
import com.jellyscope.core.domain.model.SeerrSeason
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.MeasuredAsyncImage
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.seerr_approval_required
import com.jellyscope.ui.generated.resources.seerr_auto_approval
import com.jellyscope.ui.generated.resources.seerr_availability
import com.jellyscope.ui.generated.resources.seerr_availability_available
import com.jellyscope.ui.generated.resources.seerr_availability_blocklisted
import com.jellyscope.ui.generated.resources.seerr_availability_deleted
import com.jellyscope.ui.generated.resources.seerr_availability_partial
import com.jellyscope.ui.generated.resources.seerr_availability_pending
import com.jellyscope.ui.generated.resources.seerr_availability_processing
import com.jellyscope.ui.generated.resources.seerr_availability_unknown
import com.jellyscope.ui.generated.resources.seerr_back
import com.jellyscope.ui.generated.resources.seerr_cancel
import com.jellyscope.ui.generated.resources.seerr_cast
import com.jellyscope.ui.generated.resources.seerr_choose_season
import com.jellyscope.ui.generated.resources.seerr_confirm
import com.jellyscope.ui.generated.resources.seerr_confirmation_summary
import com.jellyscope.ui.generated.resources.seerr_confirmation_title
import com.jellyscope.ui.generated.resources.seerr_episode_count
import com.jellyscope.ui.generated.resources.seerr_four_k
import com.jellyscope.ui.generated.resources.seerr_loading
import com.jellyscope.ui.generated.resources.seerr_movie
import com.jellyscope.ui.generated.resources.seerr_no_requests
import com.jellyscope.ui.generated.resources.seerr_open_library
import com.jellyscope.ui.generated.resources.seerr_poster
import com.jellyscope.ui.generated.resources.seerr_quota_exceeded
import com.jellyscope.ui.generated.resources.seerr_quota_remaining
import com.jellyscope.ui.generated.resources.seerr_quota_unavailable
import com.jellyscope.ui.generated.resources.seerr_quota_unlimited
import com.jellyscope.ui.generated.resources.seerr_reconcile
import com.jellyscope.ui.generated.resources.seerr_reconciled
import com.jellyscope.ui.generated.resources.seerr_related_error
import com.jellyscope.ui.generated.resources.seerr_related_interrupted
import com.jellyscope.ui.generated.resources.seerr_request
import com.jellyscope.ui.generated.resources.seerr_request_created
import com.jellyscope.ui.generated.resources.seerr_request_one_season
import com.jellyscope.ui.generated.resources.seerr_request_seasons
import com.jellyscope.ui.generated.resources.seerr_request_status
import com.jellyscope.ui.generated.resources.seerr_retry
import com.jellyscope.ui.generated.resources.seerr_season
import com.jellyscope.ui.generated.resources.seerr_season_named
import com.jellyscope.ui.generated.resources.seerr_season_selection
import com.jellyscope.ui.generated.resources.seerr_seasons
import com.jellyscope.ui.generated.resources.seerr_show
import com.jellyscope.ui.generated.resources.seerr_similar
import com.jellyscope.ui.generated.resources.seerr_specials
import com.jellyscope.ui.generated.resources.seerr_standard
import com.jellyscope.ui.generated.resources.seerr_status_approved
import com.jellyscope.ui.generated.resources.seerr_status_completed
import com.jellyscope.ui.generated.resources.seerr_status_declined
import com.jellyscope.ui.generated.resources.seerr_status_failed
import com.jellyscope.ui.generated.resources.seerr_status_pending
import com.jellyscope.ui.generated.resources.seerr_uncertain
import com.jellyscope.ui.generated.resources.seerr_unknown
import com.jellyscope.ui.generated.resources.seerr_untitled
import com.jellyscope.ui.generated.resources.seerr_working
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeerrDetailContent(
    state: SeerrDetailUi,
    onIntent: (SeerrIntent) -> Unit,
    modifier: Modifier = Modifier,
    session: Session,
) {
    val requestFocus = remember { FocusRequester() }
    val libraryFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }
    val fourKFocus = remember { FocusRequester() }
    val recoveryFocus = remember { FocusRequester() }
    val recoveryVisible = state.detail == null || state.error != null || (!state.admissionFresh && !state.loading)
    val recoveryEnabled = recoveryVisible && !state.loading && !state.submitting && !state.reconciling
    LaunchedEffect(state.focusedId, state.confirmation, state.loading, state.canSubmit, state.admissionFresh, recoveryEnabled) {
        if (state.confirmation == null && !state.loading) {
            when {
                state.focusedId == "recovery" && recoveryEnabled -> recoveryFocus.requestFocusSafely()
                state.focusedId == "library" && state.libraryAvailable && state.selectionEnabled && !state.resolvingLibrary ->
                    libraryFocus
                        .requestFocusSafely()
                state.focusedId == "standard" && state.selectionEnabled -> qualityFocus.requestFocusSafely()
                state.focusedId == "four-k" && state.selectionEnabled && state.showFourK -> fourKFocus.requestFocusSafely()
                state.focusedId in listOf(null, "request", "library", "standard", "four-k", "recovery") ->
                    when {
                        state.canSubmit -> requestFocus.requestFocusSafely()
                        state.libraryAvailable && state.selectionEnabled && !state.resolvingLibrary -> libraryFocus.requestFocusSafely()
                        state.selectionEnabled -> qualityFocus.requestFocusSafely()
                        recoveryEnabled -> recoveryFocus.requestFocusSafely()
                        else -> Unit
                    }
            }
        }
    }
    val scroll = rememberLazyListState(state.scroll.index, state.scroll.offset)
    LaunchedEffect(scroll) {
        snapshotFlow {
            if (scroll.isScrollInProgress) null else SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { onIntent(SeerrIntent.RememberScroll(true, it)) }
    }
    val content = Modifier.widthIn(max = Dimensions.adaptiveContentMaxWidth).fillMaxWidth().padding(horizontal = Dimensions.screenPadding)
    val detail = state.detail
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = scroll,
        contentPadding = PaddingValues(bottom = Dimensions.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
    ) {
        if (detail != null) {
            item(key = "hero", contentType = "hero") {
                SeerrDetailHero(detail, { if (!state.submitting) onIntent(SeerrIntent.Back) }) {
                    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                        Button(
                            enabled = state.canSubmit,
                            modifier =
                                Modifier
                                    .focusRequester(
                                        requestFocus,
                                    ).onFocusChanged { if (it.isFocused) onIntent(SeerrIntent.Focused("request")) },
                            onClick = { onIntent(SeerrIntent.ConfirmRequest) },
                        ) { Text(seerrRequestActionText(state)) }
                        if (state.libraryAvailable) {
                            Button(
                                enabled =
                                    state.admissionFresh &&
                                        !state.resolvingLibrary &&
                                        !state.submitting &&
                                        !state.loading &&
                                        !state.reconciling,
                                modifier =
                                    Modifier
                                        .focusRequester(
                                            libraryFocus,
                                        ).onFocusChanged { if (it.isFocused) onIntent(SeerrIntent.Focused("library")) },
                                onClick = {
                                    onIntent(SeerrIntent.Focused("library"))
                                    onIntent(
                                        SeerrIntent.RememberScroll(
                                            true,
                                            SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset),
                                        ),
                                    )
                                    onIntent(SeerrIntent.OpenLibrary)
                                },
                            ) { Text(stringResource(Res.string.seerr_open_library)) }
                        }
                        FilterChip(
                            modifier =
                                Modifier
                                    .focusRequester(
                                        qualityFocus,
                                    ).onFocusChanged { if (it.isFocused) onIntent(SeerrIntent.Focused("standard")) },
                            selected =
                                state.quality == SeerrQuality.Standard,
                            enabled = state.selectionEnabled,
                            onClick = {
                                onIntent(SeerrIntent.SelectQuality(SeerrQuality.Standard))
                            },
                            label = { Text(seerrQualityText(SeerrQuality.Standard)) },
                        )
                        if (state.showFourK) {
                            FilterChip(
                                modifier =
                                    Modifier
                                        .focusRequester(
                                            fourKFocus,
                                        ).onFocusChanged { if (it.isFocused) onIntent(SeerrIntent.Focused("four-k")) },
                                selected =
                                    state.quality == SeerrQuality.FourK,
                                enabled = state.selectionEnabled,
                                onClick = {
                                    onIntent(SeerrIntent.SelectQuality(SeerrQuality.FourK))
                                },
                                label = { Text(seerrQualityText(SeerrQuality.FourK)) },
                            )
                        }
                    }
                }
            }
        }
        if (recoveryVisible) {
            item(key = "recovery", contentType = "actions") {
                FlowRow(modifier = content, horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                    if (detail == null) TextButton(onClick = { onIntent(SeerrIntent.Back) }) { Text(stringResource(Res.string.seerr_back)) }
                    if (recoveryEnabled) {
                        TextButton(
                            modifier =
                                Modifier.focusRequester(recoveryFocus).onFocusChanged {
                                    if (it.isFocused) onIntent(SeerrIntent.Focused("recovery"))
                                },
                            onClick = { onIntent(SeerrIntent.Refresh) },
                        ) {
                            Text(stringResource(Res.string.seerr_retry))
                        }
                    }
                }
            }
        }
        item(key = "status", contentType = "status") {
            Column(content, verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                if (state.loading) Text(stringResource(Res.string.seerr_loading))
                state.error?.let { Text(seerrErrorText(it), color = MaterialTheme.colorScheme.error) }
                state.notice?.let {
                    Text(
                        stringResource(
                            if (it ==
                                SeerrNotice.Created
                            ) {
                                Res.string.seerr_request_created
                            } else {
                                Res.string.seerr_reconciled
                            },
                        ),
                    )
                }
            }
        }
        if (detail != null) {
            item {
                Column(content, verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                    Text(seerrAvailabilityLine(SeerrQuality.Standard, detail.media.standardAvailability))
                    Text(seerrAvailabilityLine(SeerrQuality.FourK, detail.media.fourKAvailability))
                    Text(seerrQuotaText(detail.quota))
                }
            }
            if (detail.requests.isEmpty()) item { Text(stringResource(Res.string.seerr_no_requests), modifier = content) }
            items(detail.requests, key = { "request:${it.id}" }, contentType = { "request" }) { request ->
                Column(content) {
                    Text(seerrRequestLine(request))
                    if (request.seasons.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.seerr_season_selection, seerrSeasonNumbersText(request.seasons)),
                        )
                    }
                }
            }
            state.option?.blockedBy?.let { error -> item { Text(seerrErrorText(error), modifier = content) } }
            if (detail.media.key.type == SeerrMediaType.Tv) {
                item { Text(stringResource(Res.string.seerr_seasons), style = MaterialTheme.typography.titleMedium, modifier = content) }
                item(key = "season-controls", contentType = "seasons") { SeerrSeasonsContent(state, onIntent) }
                if (state.selectedSeasons.isEmpty()) item { Text(stringResource(Res.string.seerr_choose_season), modifier = content) }
            }
            if (!detail.quota.allows(if (detail.media.key.type == SeerrMediaType.Movie) 1 else state.selectedSeasons.size) &&
                detail.quota.kind == SeerrQuotaKind.Limited &&
                state.selectedSeasons.isNotEmpty()
            ) {
                item { Text(stringResource(Res.string.seerr_quota_exceeded), modifier = content) }
            }
            if (state.uncertain) {
                item {
                    Column(content, verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                        Text(stringResource(Res.string.seerr_uncertain))
                        Button(enabled = !state.loading && !state.reconciling && !state.submitting, onClick = {
                            onIntent(SeerrIntent.Reconcile)
                        }) { Text(stringResource(Res.string.seerr_reconcile)) }
                    }
                }
            }
            if (detail.cast.isNotEmpty()) {
                item(key = "cast", contentType = "ribbon") {
                    Column(content, verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                        Text(stringResource(Res.string.seerr_cast), style = MaterialTheme.typography.titleLarge)
                        val castScroll = rememberLazyListState(state.castScroll.index, state.castScroll.offset)
                        LaunchedEffect(castScroll) {
                            snapshotFlow { SeerrScrollPosition(castScroll.firstVisibleItemIndex, castScroll.firstVisibleItemScrollOffset) }
                                .distinctUntilChanged()
                                .collect { onIntent(SeerrIntent.RememberCastScroll(it)) }
                        }
                        LazyRow(state = castScroll, horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing)) {
                            itemsIndexed(detail.cast, key = { index, member -> "cast:$index:${member.id}" }) { _, member ->
                                Column(Modifier.width(Dimensions.personCardWidth.tileScaled())) {
                                    SeerrCastPortrait(
                                        member.profileUrl,
                                        Modifier.fillMaxWidth().height(Dimensions.personCardImageHeight.tileScaled()),
                                    )
                                    Text(member.name, style = MaterialTheme.typography.bodyMedium)
                                    member.character?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
            if (state.related.phase != SeerrRelatedPhase.Ready ||
                state.related.rows.isNotEmpty()
            ) {
                item(key = "similar", contentType = "ribbon") {
                    SeerrSimilarContent(state, session, onIntent)
                }
            }
        }
    }
    state.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { if (!state.submitting) onIntent(SeerrIntent.Back) },
            title = {
                Text(
                    stringResource(
                        Res.string.seerr_confirmation_title,
                        detail?.media?.title ?: stringResource(Res.string.seerr_untitled),
                    ),
                )
            },
            text = {
                Column(
                    Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
                ) {
                    Text(
                        stringResource(
                            Res.string.seerr_confirmation_summary,
                            seerrMediaTypeText(confirmation.key.type),
                            seerrQualityText(confirmation.quality),
                        ),
                    )
                    if (confirmation.key.type ==
                        SeerrMediaType.Tv
                    ) {
                        Text(stringResource(Res.string.seerr_season_selection, seerrSeasonNumbersText(confirmation.seasons)))
                    }
                    Text(
                        stringResource(
                            if (state.option?.requiresApproval !=
                                false
                            ) {
                                Res.string.seerr_approval_required
                            } else {
                                Res.string.seerr_auto_approval
                            },
                        ),
                    )
                    if (state.submitting) Text(stringResource(Res.string.seerr_working))
                }
            },
            confirmButton = {
                Button(enabled = !state.submitting && state.canSubmit, onClick = {
                    onIntent(SeerrIntent.SubmitRequest)
                }) { Text(stringResource(Res.string.seerr_confirm)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.submitting,
                    onClick = { onIntent(SeerrIntent.Back) },
                ) { Text(stringResource(Res.string.seerr_cancel)) }
            },
        )
    }
}

@Composable
fun SeerrPoster(
    media: SeerrMediaCard,
    modifier: Modifier = Modifier,
) {
    MeasuredAsyncImage(
        model = seerrArtworkUrl(media.posterUrl),
        contentDescription = stringResource(Res.string.seerr_poster, media.title ?: stringResource(Res.string.seerr_untitled)),
        contentScale = ContentScale.Crop,
        modifier = modifier.aspectRatio(Dimensions.posterCardAspectRatio),
    )
}

@Composable
fun seerrMediaTypeText(type: SeerrMediaType): String =
    stringResource(
        if (type ==
            SeerrMediaType.Movie
        ) {
            Res.string.seerr_movie
        } else {
            Res.string.seerr_show
        },
    )

@Composable
fun seerrQualityText(quality: SeerrQuality): String =
    stringResource(
        if (quality ==
            SeerrQuality.Standard
        ) {
            Res.string.seerr_standard
        } else {
            Res.string.seerr_four_k
        },
    )

@Composable
fun seerrAvailabilityText(value: SeerrAvailability): String =
    stringResource(
        when (value) {
            SeerrAvailability.Unknown -> Res.string.seerr_availability_unknown
            SeerrAvailability.Pending -> Res.string.seerr_availability_pending
            SeerrAvailability.Processing -> Res.string.seerr_availability_processing
            SeerrAvailability.PartiallyAvailable -> Res.string.seerr_availability_partial
            SeerrAvailability.Available -> Res.string.seerr_availability_available
            SeerrAvailability.Blocklisted -> Res.string.seerr_availability_blocklisted
            SeerrAvailability.Deleted -> Res.string.seerr_availability_deleted
            SeerrAvailability.Unrecognized -> Res.string.seerr_unknown
        },
    )

@Composable
fun seerrAvailabilityLine(
    quality: SeerrQuality,
    value: SeerrAvailability,
): String = stringResource(Res.string.seerr_availability, seerrQualityText(quality), seerrAvailabilityText(value))

@Composable
fun seerrRequestLine(request: SeerrRequestSummary): String =
    stringResource(
        Res.string.seerr_request_status,
        seerrQualityText(request.quality),
        stringResource(
            when (request.status) {
                SeerrRequestStatus.Pending -> Res.string.seerr_status_pending
                SeerrRequestStatus.Approved -> Res.string.seerr_status_approved
                SeerrRequestStatus.Declined -> Res.string.seerr_status_declined
                SeerrRequestStatus.Failed -> Res.string.seerr_status_failed
                SeerrRequestStatus.Completed -> Res.string.seerr_status_completed
                SeerrRequestStatus.Unrecognized -> Res.string.seerr_unknown
            },
        ),
    )

@Composable
fun seerrQuotaText(quota: SeerrQuota): String =
    when (quota.kind) {
        SeerrQuotaKind.Unlimited -> stringResource(Res.string.seerr_quota_unlimited)
        SeerrQuotaKind.Limited -> stringResource(Res.string.seerr_quota_remaining, quota.remaining ?: 0)
        SeerrQuotaKind.Unavailable -> stringResource(Res.string.seerr_quota_unavailable)
    }

@Composable
fun seerrSeasonText(season: SeerrSeason): String =
    when {
        season.number == 0 -> stringResource(Res.string.seerr_specials)
        season.title.isNullOrBlank() -> stringResource(Res.string.seerr_season, season.number)
        else -> stringResource(Res.string.seerr_season_named, season.number, season.title.orEmpty())
    }

@Composable
fun seerrSeasonNumbersText(numbers: List<Int>): String {
    val specials = stringResource(Res.string.seerr_specials)
    return numbers.joinToString { if (it == 0) specials else it.toString() }
}

@Composable
fun seerrRequestActionText(state: SeerrDetailUi): String =
    when {
        state.key.type == SeerrMediaType.Movie -> stringResource(Res.string.seerr_request)
        state.selectedSeasons.isEmpty() -> stringResource(Res.string.seerr_choose_season)
        state.selectedSeasons.size == 1 -> stringResource(Res.string.seerr_request_one_season)
        else -> stringResource(Res.string.seerr_request_seasons, state.selectedSeasons.size)
    }

@Composable
private fun SeerrSimilarContent(
    state: SeerrDetailUi,
    session: Session,
    onIntent: (SeerrIntent) -> Unit,
) {
    val scroll = rememberLazyListState(state.related.scroll.index, state.related.scroll.offset)
    LaunchedEffect(scroll) {
        snapshotFlow { SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset) }
            .distinctUntilChanged()
            .collect { onIntent(SeerrIntent.RememberRelatedScroll(it)) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        Text(
            stringResource(Res.string.seerr_similar),
            Modifier.padding(horizontal = Dimensions.screenPadding),
            style = MaterialTheme.typography.titleLarge,
        )
        when (state.related.phase) {
            SeerrRelatedPhase.Loading, SeerrRelatedPhase.NotAttempted ->
                Text(
                    stringResource(Res.string.seerr_loading),
                    Modifier.padding(horizontal = Dimensions.screenPadding),
                )
            SeerrRelatedPhase.Error, SeerrRelatedPhase.Interrupted ->
                Column(Modifier.padding(horizontal = Dimensions.screenPadding)) {
                    Text(
                        stringResource(
                            if (state.related.phase ==
                                SeerrRelatedPhase.Interrupted
                            ) {
                                Res.string.seerr_related_interrupted
                            } else {
                                Res.string.seerr_related_error
                            },
                        ),
                    )
                    TextButton(enabled = state.relatedEnabled, onClick = {
                        onIntent(SeerrIntent.RetrySimilar)
                    }) { Text(stringResource(Res.string.seerr_retry)) }
                }
            SeerrRelatedPhase.Ready -> Unit
        }
        LazyRow(
            state = scroll,
            contentPadding = PaddingValues(horizontal = Dimensions.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        ) {
            items(state.related.rows, key = { it.id }, contentType = { "poster" }) { row ->
                val requester = remember(row.id) { FocusRequester() }
                val restoreId = remember { state.focusedId }
                LaunchedEffect(state.loading, restoreId) {
                    if (!state.loading && restoreId == "similar:${row.id}") requester.requestFocusSafely()
                }
                MediaCard(
                    item = row.card.copy(title = row.media.title ?: stringResource(Res.string.seerr_untitled)),
                    session = session,
                    modifier =
                        Modifier
                            .focusRequester(requester)
                            .focusProperties { canFocus = state.relatedEnabled }
                            .alpha(if (state.relatedEnabled) 1f else 0.5f)
                            .semantics { if (!state.relatedEnabled) disabled() }
                            .onFocusChanged { if (it.isFocused) onIntent(SeerrIntent.Focused("similar:${row.id}")) },
                    onClick = {
                        if (state.relatedEnabled) {
                            onIntent(SeerrIntent.Focused("similar:${row.id}"))
                            onIntent(SeerrIntent.OpenSimilar(row.id))
                        }
                    },
                    artwork = { SeerrPoster(row.media, Modifier.fillMaxWidth()) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeerrSeasonsContent(
    state: SeerrDetailUi,
    onIntent: (SeerrIntent) -> Unit,
) {
    val tile: @Composable (SeerrSeasonUi) -> Unit = { season ->
        Column(
            Modifier.widthIn(max = Dimensions.seerrSeasonWidth),
            verticalArrangement = Arrangement.spacedBy(Dimensions.labelGlyphSpacing),
        ) {
            FilterChip(
                modifier = Modifier.onFocusChanged { if (it.isFocused) onIntent(SeerrIntent.Focused("season:${season.season.number}")) },
                selected = season.selected,
                enabled = season.enabled,
                onClick = { onIntent(SeerrIntent.ToggleSeason(season.season.number)) },
                label = { Text(seerrSeasonText(season.season)) },
                leadingIcon = if (season.selected) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
            )
            Text(stringResource(Res.string.seerr_episode_count, season.season.episodeCount), style = MaterialTheme.typography.bodySmall)
            Text(seerrAvailabilityLine(state.quality, season.availability), style = MaterialTheme.typography.bodySmall)
            season.blockedBy?.let { Text(seerrErrorText(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (LocalWindowWidthTier.current == WindowWidthTier.Compact) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Dimensions.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            state.seasons.forEach { season -> androidx.compose.runtime.key(season.season.number) { tile(season) } }
        }
    } else {
        val scroll = rememberLazyListState(state.seasonScroll.index, state.seasonScroll.offset)
        LaunchedEffect(scroll) {
            snapshotFlow { SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset) }
                .distinctUntilChanged()
                .collect { onIntent(SeerrIntent.RememberSeasonScroll(it)) }
        }
        LazyRow(
            state = scroll,
            contentPadding = PaddingValues(horizontal = Dimensions.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        ) {
            items(state.seasons, key = { it.season.number }, contentType = { "season" }) { tile(it) }
        }
    }
}

@Composable
fun SeerrCastPortrait(
    profileUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(Dimensions.personAvatarSize.tileScaled()))
        MeasuredAsyncImage(
            model = seerrArtworkUrl(profileUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}
