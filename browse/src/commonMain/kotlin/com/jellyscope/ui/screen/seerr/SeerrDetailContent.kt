// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.jellyscope.core.domain.model.SeerrAvailability
import com.jellyscope.core.domain.model.SeerrMediaCard
import com.jellyscope.core.domain.model.SeerrMediaType
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuota
import com.jellyscope.core.domain.model.SeerrQuotaKind
import com.jellyscope.core.domain.model.SeerrRequestStatus
import com.jellyscope.core.domain.model.SeerrRequestSummary
import com.jellyscope.core.domain.model.SeerrSeason
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.MeasuredAsyncImage
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
import com.jellyscope.ui.generated.resources.seerr_choose_season
import com.jellyscope.ui.generated.resources.seerr_confirm
import com.jellyscope.ui.generated.resources.seerr_confirmation_summary
import com.jellyscope.ui.generated.resources.seerr_confirmation_title
import com.jellyscope.ui.generated.resources.seerr_four_k
import com.jellyscope.ui.generated.resources.seerr_loading
import com.jellyscope.ui.generated.resources.seerr_movie
import com.jellyscope.ui.generated.resources.seerr_no_requests
import com.jellyscope.ui.generated.resources.seerr_open_library
import com.jellyscope.ui.generated.resources.seerr_overview_missing
import com.jellyscope.ui.generated.resources.seerr_poster
import com.jellyscope.ui.generated.resources.seerr_quota_exceeded
import com.jellyscope.ui.generated.resources.seerr_quota_remaining
import com.jellyscope.ui.generated.resources.seerr_quota_unavailable
import com.jellyscope.ui.generated.resources.seerr_quota_unlimited
import com.jellyscope.ui.generated.resources.seerr_reconcile
import com.jellyscope.ui.generated.resources.seerr_reconciled
import com.jellyscope.ui.generated.resources.seerr_refresh
import com.jellyscope.ui.generated.resources.seerr_request
import com.jellyscope.ui.generated.resources.seerr_request_created
import com.jellyscope.ui.generated.resources.seerr_request_status
import com.jellyscope.ui.generated.resources.seerr_season
import com.jellyscope.ui.generated.resources.seerr_season_named
import com.jellyscope.ui.generated.resources.seerr_season_selection
import com.jellyscope.ui.generated.resources.seerr_seasons
import com.jellyscope.ui.generated.resources.seerr_show
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
) {
    val scroll = rememberLazyListState(state.scroll.index, state.scroll.offset)
    LaunchedEffect(scroll) {
        snapshotFlow {
            if (scroll.isScrollInProgress) null else SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { onIntent(SeerrIntent.RememberScroll(true, it)) }
    }
    val content = Modifier.widthIn(max = Dimensions.adaptiveContentMaxWidth).fillMaxWidth()
    val detail = state.detail
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = scroll,
        contentPadding = PaddingValues(Dimensions.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
    ) {
        item {
            FlowRow(modifier = content, horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                TextButton(
                    enabled = !state.submitting,
                    onClick = { onIntent(SeerrIntent.Back) },
                ) { Text(stringResource(Res.string.seerr_back)) }
                TextButton(enabled = !state.loading && !state.submitting && !state.reconciling, onClick = {
                    onIntent(SeerrIntent.Refresh)
                }) { Text(stringResource(Res.string.seerr_refresh)) }
            }
        }
        if (state.loading) item { Text(stringResource(Res.string.seerr_loading), modifier = content) }
        state.error?.let { error -> item { Text(seerrErrorText(error), color = MaterialTheme.colorScheme.error, modifier = content) } }
        state.notice?.let { notice ->
            item {
                Text(
                    stringResource(
                        if (notice ==
                            SeerrNotice.Created
                        ) {
                            Res.string.seerr_request_created
                        } else {
                            Res.string.seerr_reconciled
                        },
                    ),
                    modifier = content,
                )
            }
        }
        if (detail != null) {
            item {
                FlowRow(
                    modifier = content,
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.detailSectionSpacing),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
                ) {
                    SeerrPoster(detail.media, Modifier.width(Dimensions.detailPosterWidth.tileScaled()))
                    Column(
                        Modifier.widthIn(max = Dimensions.formControlMaxWidth),
                        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
                    ) {
                        Text(
                            detail.media.title ?: stringResource(Res.string.seerr_untitled),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(seerrMediaTypeText(detail.media.key.type))
                        detail.media.year?.let { Text(it.toString()) }
                        Text(detail.overview ?: stringResource(Res.string.seerr_overview_missing))
                    }
                }
            }
            item {
                Column(content, verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                    Text(seerrAvailabilityLine(SeerrQuality.Standard, detail.media.standardAvailability))
                    Text(seerrAvailabilityLine(SeerrQuality.FourK, detail.media.fourKAvailability))
                    Text(seerrQuotaText(detail.quota))
                }
            }
            if (detail.requests.isEmpty()) item { Text(stringResource(Res.string.seerr_no_requests), modifier = content) }
            items(detail.requests, key = { "request:${it.id}" }) { request ->
                Column(content) {
                    Text(seerrRequestLine(request))
                    if (request.seasons.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.seerr_season_selection, seerrSeasonNumbersText(request.seasons)),
                        )
                    }
                }
            }
            item {
                FlowRow(modifier = content, horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                    FilterChip(selected = state.quality == SeerrQuality.Standard, enabled = state.selectionEnabled, onClick = {
                        onIntent(SeerrIntent.SelectQuality(SeerrQuality.Standard))
                    }, label = { Text(seerrQualityText(SeerrQuality.Standard)) })
                    if (state.showFourK) {
                        FilterChip(selected = state.quality == SeerrQuality.FourK, enabled = state.selectionEnabled, onClick = {
                            onIntent(SeerrIntent.SelectQuality(SeerrQuality.FourK))
                        }, label = { Text(seerrQualityText(SeerrQuality.FourK)) })
                    }
                }
            }
            state.option?.blockedBy?.let { error -> item { Text(seerrErrorText(error), modifier = content) } }
            if (detail.media.key.type == SeerrMediaType.Tv) {
                item { Text(stringResource(Res.string.seerr_seasons), style = MaterialTheme.typography.titleMedium, modifier = content) }
                items(state.seasons, key = { "season:${it.season.number}" }) { season ->
                    Column(content) {
                        FilterChip(selected = season.selected, enabled = season.enabled, onClick = {
                            onIntent(SeerrIntent.ToggleSeason(season.season.number))
                        }, label = { Text(seerrSeasonText(season.season)) })
                        Text(seerrAvailabilityLine(state.quality, season.availability))
                        season.blockedBy?.let { Text(seerrErrorText(it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
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
            item {
                FlowRow(modifier = content, horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                    Button(
                        enabled = state.canSubmit,
                        onClick = { onIntent(SeerrIntent.ConfirmRequest) },
                    ) { Text(stringResource(Res.string.seerr_request)) }
                    Button(enabled = !state.resolvingLibrary && !state.submitting && !state.loading && !state.reconciling, onClick = {
                        onIntent(
                            SeerrIntent.RememberScroll(
                                true,
                                SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset),
                            ),
                        )
                        onIntent(SeerrIntent.OpenLibrary)
                    }) { Text(stringResource(Res.string.seerr_open_library)) }
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
