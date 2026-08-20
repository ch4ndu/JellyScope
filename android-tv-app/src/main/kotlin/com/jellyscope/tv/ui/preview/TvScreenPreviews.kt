// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.tv.ui.TvLoginContent
import com.jellyscope.tv.ui.TvServerEntryContent
import com.jellyscope.tv.ui.TvSettingsContent
import com.jellyscope.ui.preview.JellyScopeTvPreviews
import com.jellyscope.ui.screen.login.LoginUiState
import com.jellyscope.ui.screen.login.QuickConnectUiState
import com.jellyscope.ui.screen.serverentry.DiscoveredServerUi
import com.jellyscope.ui.screen.serverentry.ServerEntryUiState
import com.jellyscope.ui.theme.AppColorTheme

@JellyScopeTvPreviews
@Composable
private fun TvServerEntryPreview() {
    TvPreviewSurface(safeArea = true) {
        TvServerEntryContent(
            state =
                ServerEntryUiState(
                    input = TvPreviewFixtures.session.serverUrl,
                    discoveredServers =
                        listOf(
                            DiscoveredServerUi("server-1", "Living Room Jellyfin", "http://192.168.1.15:8096"),
                            DiscoveredServerUi("server-2", "Studio Jellyfin", "http://192.168.1.28:8096"),
                        ),
                    isScanning = true,
                ),
            onServerUrlChange = {},
            onSubmit = {},
            onScanAgain = {},
            onDiscoveredServerClick = {},
        )
    }
}

@JellyScopeTvPreviews
@Composable
private fun TvLoginPreview() {
    TvPreviewSurface(safeArea = true) {
        TvLoginContent(
            state =
                LoginUiState(
                    serverInfo = TvPreviewFixtures.serverInfo,
                    username = TvPreviewFixtures.session.userName,
                    password = "preview-password",
                ),
            quickConnectState = QuickConnectUiState.Polling("secret", "123456", "123 456"),
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onQuickConnectRestart = {},
        )
    }
}

@JellyScopeTvPreviews
@Composable
private fun TvSettingsTopPreview() {
    TvSettingsPreviewContent(theme = AppColorTheme.Ocean, focusZoomEnabled = true, scrollFraction = 0f)
}

@JellyScopeTvPreviews
@Composable
private fun TvSettingsMiddlePreview() {
    TvSettingsPreviewContent(theme = AppColorTheme.Midnight, scrollFraction = 0.5f)
}

@JellyScopeTvPreviews
@Composable
private fun TvSettingsFinalPreview() {
    TvSettingsPreviewContent(theme = AppColorTheme.Ember, scrollFraction = 1f)
}

@JellyScopeTvPreviews
@Composable
private fun TvSettingsChoiceDialogPreview() {
    TvSettingsPreviewContent(theme = AppColorTheme.Ocean, dialogTile = com.jellyscope.tv.ui.TvSettingsTileId.MaxBitrate)
}

@JellyScopeTvPreviews
@Composable
private fun TvSettingsDetailDialogPreview() {
    TvSettingsPreviewContent(theme = AppColorTheme.Midnight, dialogTile = com.jellyscope.tv.ui.TvSettingsTileId.Server)
}

@JellyScopeTvPreviews
@Composable
private fun TvSettingsConfirmationDialogPreview() {
    TvSettingsPreviewContent(theme = AppColorTheme.Ember, dialogTile = com.jellyscope.tv.ui.TvSettingsTileId.Logout)
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "TV Settings narrow",
    group = "TV",
    widthDp = 640,
    heightDp = 540,
    showBackground = true,
    backgroundColor = 0xFF081420,
)
@Composable
private fun TvSettingsNarrowPreview() {
    TvSettingsPreviewContent(theme = AppColorTheme.Ocean)
}

@Composable
private fun TvSettingsPreviewContent(
    theme: AppColorTheme,
    focusZoomEnabled: Boolean = false,
    scrollFraction: Float = 0f,
    dialogTile: com.jellyscope.tv.ui.TvSettingsTileId? = null,
) {
    TvPreviewSurface(theme = theme, focusZoomEnabled = focusZoomEnabled) {
        TvSettingsContent(
            session = TvPreviewFixtures.session,
            state = TvPreviewFixtures.settingsState,
            accountState = TvPreviewFixtures.accountState,
            serverFocusRequester = remember { FocusRequester() },
            requestInitialFocus = false,
            initialScrollFraction = scrollFraction,
            initialDialogTile = dialogTile,
            onLogout = {},
            onSwitchAccount = {},
            onAddAccount = { _, _, _ -> },
            onDefaultQualityPolicySelected = {},
            onPreferredAudioLanguageChange = {},
            onPreferredSubtitleLanguageChange = {},
            onStillWatchingPromptChange = {},
            onPlayerAudioModeSelected = { _: PlayerAudioMode -> },
            onPlayerHdrModeSelected = { _: PlayerHdrMode -> },
            onMatchDisplayRefreshRateChange = {},
            onRefreshPlayerDevicePolicy = {},
            onThemeSelected = { _: AppColorThemeId -> },
            onTileSizeSelected = { _: TileSizeId -> },
            focusedCardZoomEnabled = true,
            showLibraryGridHero = true,
            onFocusedCardZoomEnabledChange = {},
            onShowLibraryGridHeroChange = {},
            onRememberLastLibraryViewChange = {},
            onSegmentSkipPolicySelected = { _, _ -> },
            onOpenSubtitlesApiKeyChange = { _, _ -> },
            onClearLocalSubtitles = {},
        )
    }
}
