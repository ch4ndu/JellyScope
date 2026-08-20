// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jellyscope.tv.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.jellyscope.tv.R
import com.jellyscope.ui.screen.settings.SettingsIcons
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

@Composable
internal fun TvSettingsGridTile(
    id: TvSettingsTileId,
    title: String,
    value: String,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = com.jellyscope.ui.theme.LocalJellyfinPalette.current
    val iconColor = if (id.usesCoralIcon()) palette.accentCoral else palette.cyan
    val focusedScale = if (LocalTvFocusZoomEnabled.current) TvDimens.SETTINGS_TILE_FOCUSED_SCALE else 1f
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvFocusableBox(
            onClick = onClick,
            enabled = enabled,
            focusableWhenDisabled = true,
            modifier =
                Modifier
                    .size(TvDimens.settingsGridCircleDiameter)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                        if (focusState.isFocused) onFocused()
                    },
            contentDescription = stringResource(R.string.tv_settings_tile_content_description, title, value),
            focusedScale = focusedScale,
            backgroundColor = palette.surfaceRaised.copy(alpha = 0.78f),
            focusedBackgroundColor = palette.surfaceRaised,
            focusedBorderColor = palette.cyan,
            focusGlowColor = palette.cyan.copy(alpha = TvDimens.SETTINGS_FOCUS_GLOW_ALPHA),
            focusGlowElevation = TvDimens.settingsPanelFocusGlow,
            shape = CircleShape,
            contentPadding = PaddingValues(TvDimens.settingsGridTilePadding),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                imageVector = settingsTileIcon(id),
                contentDescription = null,
                colorFilter = ColorFilter.tint(iconColor),
                modifier = Modifier.size(TvDimens.settingsGridTileIconSize).alpha(if (enabled) 1f else 0.62f),
            )
        }
        TvText(
            text = title,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = TvDimens.settingsGridTitleGap)
                    .then(if (isFocused) Modifier.basicMarquee() else Modifier)
                    .alpha(if (enabled) 1f else 0.62f),
            style = TvCardLabelStyle.copy(color = palette.textPrimary, textAlign = TextAlign.Center),
            maxLines = 1,
        )
        TvText(
            text = value,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = TvDimens.settingsValueGap)
                    .alpha(if (enabled) 1f else 0.62f),
            style = TvSecondaryStyle.copy(color = palette.textSecondary, textAlign = TextAlign.Center),
            maxLines = 1,
        )
    }
}

private fun TvSettingsTileId.usesCoralIcon(): Boolean =
    when (this) {
        TvSettingsTileId.Logout,
        TvSettingsTileId.Intros,
        TvSettingsTileId.Credits,
        TvSettingsTileId.Recaps,
        TvSettingsTileId.Previews,
        TvSettingsTileId.Commercials,
        TvSettingsTileId.ClearSubtitles,
        TvSettingsTileId.AppVersion,
        TvSettingsTileId.ServerUrl,
        -> true
        else -> false
    }

@Composable
internal fun tvSettingsToggleValue(enabled: Boolean): String =
    stringResource(if (enabled) R.string.tv_settings_toggle_on else R.string.tv_settings_toggle_off)

internal fun settingsTileIcon(id: TvSettingsTileId): ImageVector =
    when (id) {
        TvSettingsTileId.Server -> SettingsIcons.Server
        TvSettingsTileId.SignedInUser -> SettingsIcons.SignedInUser
        TvSettingsTileId.ActiveAccount -> SettingsIcons.ActiveAccount
        TvSettingsTileId.AddAccount -> SettingsIcons.AddAccount
        TvSettingsTileId.Logout -> SettingsIcons.SignOut
        TvSettingsTileId.Theme -> SettingsIcons.Theme
        TvSettingsTileId.TileSize -> SettingsIcons.TileSize
        TvSettingsTileId.FocusedCardZoom -> SettingsIcons.FocusedCardZoom
        TvSettingsTileId.RememberLastLibrary -> SettingsIcons.RememberLibrary
        TvSettingsTileId.LibraryGridHero -> SettingsIcons.LibraryGridHero
        TvSettingsTileId.AudioLanguage -> SettingsIcons.AudioLanguage
        TvSettingsTileId.SubtitleLanguage -> SettingsIcons.SubtitleLanguage
        TvSettingsTileId.MaxBitrate -> SettingsIcons.MaxBitrate
        TvSettingsTileId.StillWatching -> SettingsIcons.ResumeBehavior
        TvSettingsTileId.PlaybackWarnings -> SettingsIcons.ResumeBehavior
        TvSettingsTileId.AudioOutput -> SettingsIcons.AudioOutput
        TvSettingsTileId.PlayerBackend -> SettingsIcons.VideoFormats
        TvSettingsTileId.VlcTranscodeLimit -> SettingsIcons.MaxBitrate
        TvSettingsTileId.AutoPlayNext,
        TvSettingsTileId.AutoPlayNextDelay,
        -> SettingsIcons.ResumeBehavior
        TvSettingsTileId.Intros -> SettingsIcons.Intros
        TvSettingsTileId.Credits -> SettingsIcons.Credits
        TvSettingsTileId.Recaps -> SettingsIcons.Recaps
        TvSettingsTileId.Previews -> SettingsIcons.Previews
        TvSettingsTileId.Commercials -> SettingsIcons.Commercials
        TvSettingsTileId.VideoFormats -> SettingsIcons.VideoFormats
        TvSettingsTileId.AudioFormats -> SettingsIcons.AudioFormats
        TvSettingsTileId.HdrHandling -> SettingsIcons.HdrHandling
        TvSettingsTileId.MatchRefreshRate -> SettingsIcons.MatchRefreshRate
        TvSettingsTileId.RefreshCapabilities -> SettingsIcons.RefreshCapabilities
        TvSettingsTileId.OpenSubtitlesKey -> SettingsIcons.OpenSubtitlesKey
        TvSettingsTileId.ClearSubtitles -> SettingsIcons.ClearSubtitles
        TvSettingsTileId.AppVersion -> SettingsIcons.AppVersion
        TvSettingsTileId.AboutJellyScope -> SettingsIcons.AboutJellyScope
        TvSettingsTileId.ServerUrl -> SettingsIcons.ServerUrl
        TvSettingsTileId.CollectLogs,
        TvSettingsTileId.SendClientLogs,
        TvSettingsTileId.VerboseLogcat,
        TvSettingsTileId.PlaybackInfoAtStart,
        -> SettingsIcons.AboutJellyScope
    }
