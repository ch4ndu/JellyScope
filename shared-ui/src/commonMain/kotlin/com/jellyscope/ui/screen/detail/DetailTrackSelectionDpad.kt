// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.hasSelectableSubtitleChoice
import com.jellyscope.ui.component.DetailSecondaryStyle
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_audio_tracks
import com.jellyscope.ui.generated.resources.detail_subtitle_tracks
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DpadTrackSelectionControls(
    trackSelection: DetailTrackSelectionUi,
    selectionState: DetailTrackSelectionState,
    modifier: Modifier = Modifier,
) {
    val showAudioText = trackSelection.audioOptions.size <= 1
    val showSubtitleText = !trackSelection.subtitleOptions.hasSelectableSubtitleChoice()
    if (!showAudioText && !showSubtitleText) {
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(DetailDimens.detailHeroTextWidthFraction),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailHeroTextGap),
    ) {
        if (showAudioText) {
            DpadAudioControl(
                options = trackSelection.audioOptions,
                selectedStreamIndex = selectionState.selectedAudioStreamIndex,
            )
        }
        if (showSubtitleText) {
            DpadSubtitleControl(
                options = trackSelection.subtitleOptions,
                selectedStreamIndex = selectionState.selectedSubtitleStreamIndex,
            )
        }
    }
}

@Composable
internal fun DpadAudioControl(
    options: List<AudioTrackOption>,
    selectedStreamIndex: Int?,
) {
    val selectedLabel = selectedAudioLabel(options = options, selectedStreamIndex = selectedStreamIndex)
    StaticDpadTrackText(text = stringResource(Res.string.detail_audio_tracks, selectedLabel))
}

@Composable
internal fun DpadSubtitleControl(
    options: List<SubtitleTrackOption>,
    selectedStreamIndex: Int?,
) {
    val selectedLabel = selectedSubtitleLabel(options = options, selectedStreamIndex = selectedStreamIndex)
    StaticDpadTrackText(text = stringResource(Res.string.detail_subtitle_tracks, selectedLabel))
}

@Composable
internal fun StaticDpadTrackText(text: String) {
    DetailText(
        text = text,
        style = DetailSecondaryStyle,
        color = LocalJellyfinPalette.current.textSecondary,
        maxLines = 1,
    )
}
