// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.subtitles_downloads
import com.jellyscope.ui.generated.resources.subtitles_forced
import com.jellyscope.ui.generated.resources.subtitles_fps
import com.jellyscope.ui.generated.resources.subtitles_hearing_impaired
import com.jellyscope.ui.generated.resources.subtitles_quota_remaining
import com.jellyscope.ui.generated.resources.subtitles_quota_reset
import com.jellyscope.ui.generated.resources.subtitles_rating
import com.jellyscope.ui.generated.resources.subtitles_search_close
import com.jellyscope.ui.generated.resources.subtitles_search_empty
import com.jellyscope.ui.generated.resources.subtitles_search_retry
import com.jellyscope.ui.generated.resources.subtitles_search_title
import com.jellyscope.ui.generated.resources.subtitles_trusted
import com.jellyscope.ui.generated.resources.subtitles_unknown_format
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun OpenSubtitleSearchDialog(
    request: OpenSubtitleSearchRequest,
    onInstalled: (LocalSubtitleAsset) -> Unit,
    onDismiss: () -> Unit,
    viewModel: OpenSubtitleSearchViewModel =
        koinViewModel(
            key = openSubtitleSearchViewModelKey(request),
            parameters = { parametersOf(request) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dpad = isDetailDpadMode()
    val rowLabels =
        OpenSubtitleRowLabels(
            unknownFormat = stringResource(Res.string.subtitles_unknown_format),
            hearingImpaired = stringResource(Res.string.subtitles_hearing_impaired),
            forced = stringResource(Res.string.subtitles_forced),
            trusted = stringResource(Res.string.subtitles_trusted),
            rating = { rating -> rating },
            downloads = { downloads -> downloads },
            fps = { fps -> fps },
        )
    LaunchedEffect(viewModel) { viewModel.installed.collect { asset -> onInstalled(asset) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.subtitles_search_title)) },
        text = {
            when {
                state.loading -> CircularProgressIndicator()
                state.error ->
                    OpenSubtitleDialogButton(
                        label = stringResource(Res.string.subtitles_search_retry),
                        dpad = dpad,
                        onClick = viewModel::search,
                    )
                state.results.isEmpty() -> Text(stringResource(Res.string.subtitles_search_empty))
                else ->
                    LazyColumn {
                        state.quotaRemaining?.let { remaining ->
                            item {
                                Text(
                                    stringResource(Res.string.subtitles_quota_remaining, remaining),
                                    modifier = Modifier.padding(bottom = Dimensions.contentSpacing),
                                )
                            }
                        }
                        state.quotaResetTime?.let { resetTime ->
                            item {
                                Text(
                                    stringResource(Res.string.subtitles_quota_reset, resetTime),
                                    modifier = Modifier.padding(bottom = Dimensions.contentSpacing),
                                )
                            }
                        }
                        items(state.results, key = OpenSubtitleSearchResult::fileId) { result ->
                            val row = localizedOpenSubtitleRow(result, state.downloadingFileId, rowLabels)
                            var focused by remember(result.fileId) { mutableStateOf(false) }
                            Column(
                                Modifier
                                    .then(
                                        if (dpad) {
                                            Modifier
                                                .clip(MaterialTheme.shapes.small)
                                                .background(
                                                    if (focused) {
                                                        Color.White.copy(alpha = 0.14f)
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                )
                                        } else {
                                            Modifier
                                        },
                                    ).detailOnFocusChanged { focusState -> focused = focusState.isFocused }
                                    .fillMaxWidth()
                                    .clickable(enabled = result.selectable) { viewModel.install(result) }
                                    .detailFocusable(enabled = result.selectable)
                                    .padding(Dimensions.contentSpacing),
                            ) {
                                Text(row.title)
                                Row { Text(row.metadata) }
                                if (row.downloading) CircularProgressIndicator()
                            }
                        }
                    }
            }
        },
        confirmButton = {
            OpenSubtitleDialogButton(
                label = stringResource(Res.string.subtitles_search_close),
                dpad = dpad,
                onClick = onDismiss,
            )
        },
    )
}

internal data class OpenSubtitleRowLabels(
    val unknownFormat: String,
    val hearingImpaired: String,
    val forced: String,
    val trusted: String,
    val rating: (String) -> String,
    val downloads: (String) -> String,
    val fps: (String) -> String,
)

internal data class OpenSubtitleRowUi(
    val result: OpenSubtitleSearchResult,
    val title: String,
    val metadata: String,
    val downloading: Boolean,
)

@Composable
private fun localizedOpenSubtitleRow(
    result: OpenSubtitleSearchResult,
    downloadingFileId: String?,
    labels: OpenSubtitleRowLabels,
): OpenSubtitleRowUi {
    val rating = result.rating?.let { value -> stringResource(Res.string.subtitles_rating, value) }
    val downloads = result.downloadCount?.let { value -> stringResource(Res.string.subtitles_downloads, value) }
    val fps = result.fps?.let { value -> stringResource(Res.string.subtitles_fps, value) }
    return result.toOpenSubtitleRowUi(
        labels.copy(
            rating = { rating.orEmpty() },
            downloads = { downloads.orEmpty() },
            fps = { fps.orEmpty() },
        ),
        downloadingFileId,
    )
}

internal fun OpenSubtitleSearchResult.toOpenSubtitleRowUi(
    labels: OpenSubtitleRowLabels,
    downloadingFileId: String?,
): OpenSubtitleRowUi =
    OpenSubtitleRowUi(
        result = this,
        title = releaseName ?: fileName,
        metadata =
            listOfNotNull(
                language,
                format ?: labels.unknownFormat,
                labels.hearingImpaired.takeIf { hearingImpaired },
                labels.forced.takeIf { forced },
                labels.trusted.takeIf { trusted },
                rating?.let { value -> labels.rating(value.toString()) },
                downloadCount?.let { value -> labels.downloads(value.toString()) },
                fps?.let { value -> labels.fps(value.toString()) },
                unavailableReason.takeUnless { selectable },
            ).joinToString(" · "),
        downloading = downloadingFileId == fileId,
    )

internal fun openSubtitleSearchViewModelKey(request: OpenSubtitleSearchRequest): String =
    request.context.run {
        "open-subtitle-search:$serverId:$userId:$itemId:$mediaSourceId"
    }

@Composable
private fun OpenSubtitleDialogButton(
    label: String,
    dpad: Boolean,
    onClick: () -> Unit,
) {
    if (!dpad) {
        Button(onClick = onClick) {
            Text(label)
        }
        return
    }

    var focused by remember { mutableStateOf(false) }
    val palette = LocalJellyfinPalette.current
    Button(
        onClick = onClick,
        modifier = Modifier.detailOnFocusChanged { focusState -> focused = focusState.isFocused },
        shape = RoundedCornerShape(percent = 50),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (focused) Color.White else palette.surfaceRaised,
                contentColor = if (focused) palette.onFocusedLight else palette.textPrimary,
            ),
        border =
            BorderStroke(
                width = if (focused) DetailDimens.focusBorder else DetailDimens.detailActionOutlineWidth,
                color = if (focused) Color.White else palette.outline,
            ),
    ) {
        Text(label)
    }
}
