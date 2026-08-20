// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.media_info_section_audio
import com.jellyscope.ui.generated.resources.media_info_section_file
import com.jellyscope.ui.generated.resources.media_info_section_subtitles
import com.jellyscope.ui.generated.resources.media_info_section_video
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(
    title: String,
    mediaInfo: MediaInfoUi,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = Dimensions.screenPadding,
                        top = Dimensions.contentSpacing,
                        end = Dimensions.screenPadding,
                        bottom = Dimensions.screenPadding,
                    ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            mediaInfo.fileLine?.let { fileLine ->
                MediaInfoSection(
                    title = stringResource(Res.string.media_info_section_file),
                    lines = listOf(fileLine),
                )
            }
            MediaInfoSection(
                title = stringResource(Res.string.media_info_section_video),
                lines = mediaInfo.videoLines,
            )
            MediaInfoSection(
                title = stringResource(Res.string.media_info_section_audio),
                lines = mediaInfo.audioLines,
            )
            MediaInfoSection(
                title = stringResource(Res.string.media_info_section_subtitles),
                lines = mediaInfo.subtitleLines,
            )
        }
    }
}

@Composable
private fun MediaInfoSection(
    title: String,
    lines: List<String>,
) {
    if (lines.isEmpty()) {
        return
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        lines.forEach { line ->
            MediaInfoLine(line)
        }
    }
}

@Composable
private fun MediaInfoLine(line: String) {
    Text(
        text = line,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}
