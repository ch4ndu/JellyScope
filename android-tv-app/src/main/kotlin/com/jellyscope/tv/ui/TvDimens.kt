// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jellyscope.ui.component.ChromeDimens

object TvDimens {
    // Geometry validated on a 1080p/320dpi Fire TV panel (2px per dp): content
    // draws to roughly 15px of the screen edge with roughly 120dp poster cards.
    val overscanHorizontal = ChromeDimens.overscanHorizontal
    val overscanVertical = ChromeDimens.overscanVertical
    val focusBorder = ChromeDimens.focusBorder
    val cardRadius = ChromeDimens.cardRadius
    val panelRadius = ChromeDimens.panelRadius
    val rowGap = ChromeDimens.rowGap
    val itemGap = ChromeDimens.itemGap
    val formGap = 18.dp
    val inputFieldHeight = 58.dp
    val inputFieldOutlineRadius = panelRadius
    val inputFieldUnfocusedBorder = 1.dp
    val inputFieldHorizontalPadding = 18.dp
    val inputFieldVerticalPadding = 10.dp
    val inputFieldFloatingLabelTextSize = 11.sp
    val inputFieldFloatingLabelOffset = 7.dp
    val inputFieldFloatingLabelStartPadding = 14.dp
    val inputFieldFloatingLabelHorizontalPadding = 4.dp
    val inputFieldCaptionHeight = 22.dp
    val inputFieldCaptionTopPadding = 4.dp
    val inputFieldCaptionStartPadding = 18.dp
    val inputFieldCaptionTextSize = 11.sp
    val inputFieldTotalHeight = inputFieldHeight + inputFieldCaptionHeight
    val loginFormEndPadding = 360.dp
    val retryButtonWidth = 140.dp
    val settingsContentTopPadding = 36.dp
    val settingsValueGap = 6.dp
    val settingsWideRowHorizontalPadding = 18.dp
    val settingsWideRowVerticalPadding = 10.dp
    val settingsPickerScrimAlpha = 0.42f
    val settingsPickerPanelAlpha = 0.95f
    val settingsPickerOutlineAlpha = 0.16f
    val settingsPanelFocusGlow = 4.dp
    const val SETTINGS_FOCUS_GLOW_ALPHA = 0.14f
    val settingsGridRowGap = 18.dp
    val settingsGridSectionLabelGap = 8.dp
    val settingsGridTileGap = 48.dp
    val settingsGridCircleDiameter = 111.dp
    val settingsGridTitleGap = 14.dp
    val settingsGridTileFocusReserve = 12.dp
    val settingsGridTilePadding = 14.dp
    val settingsGridTileIconSize = 36.dp
    val settingsGridIndicatorWidth = 4.dp
    val settingsGridIndicatorHeight = 180.dp
    val settingsGridIndicatorMinThumbHeight = 32.dp
    val settingsGridIndicatorEndPadding = 8.dp
    val settingsThemeSwatchSize = 14.dp
    val settingsThemeSwatchGap = 5.dp
    val settingsThemeSwatchBorder = 1.dp
    val settingsDialogWidth = 560.dp
    val settingsDialogMaxHeight = 600.dp
    val settingsDialogContentPadding = 24.dp
    val settingsDialogActionGap = 12.dp
    val settingsDialogOptionIndicatorGap = 10.dp
    val settingsDialogOptionIndicatorSize = 18.dp
    val settingsDialogOptionIndicatorInset = 3.dp
    val settingsDialogCurrentLabelReserve = 54.dp
    val settingsBackdropDimAlpha = 0.42f
    val settingsBackdropContentScrimStartAlpha = 0.90f
    val settingsBackdropContentScrimMiddleAlpha = 0.64f
    val settingsBackdropContentScrimEndAlpha = 0.30f
    val settingsRowIconSize = 20.dp
    val settingsRowIconGap = 12.dp
    val settingsChevronSize = 18.dp
    val settingsSegmentHeight = 42.dp
    val settingsSegmentGap = 1.dp
    val settingsAboutMarkSize = 52.dp
    val settingsCardOutlineWidth = 1.dp
    val settingsPickerRowPadding =
        PaddingValues(
            horizontal = settingsWideRowHorizontalPadding,
            vertical = settingsWideRowVerticalPadding,
        )
    val minButtonHeight = 36.dp
    val buttonHorizontalPadding = ChromeDimens.buttonHorizontalPadding
    val buttonVerticalPadding = ChromeDimens.buttonVerticalPadding
    val posterWidth = ChromeDimens.posterWidth
    val drawerExpandedWidth = 180.dp
    val drawerCollapsedWidth = 48.dp
    val drawerItemHeight = 44.dp
    val drawerSectionGap = 12.dp
    val drawerUserExpandedStartPadding = 10.dp
    val drawerUserExpandedAvatarSize = 34.dp
    val drawerUserCollapsedAvatarSize = 26.dp
    val drawerUserNameGap = 12.dp
    val drawerItemRadius = 12.dp
    val drawerItemExpandedStartPadding = 18.dp
    val drawerItemExpandedEndPadding = 10.dp
    val drawerIconSize = 24.dp
    val drawerLabelGap = 14.dp
    val drawerContentStartPadding = 20.dp
    val drawerVerticalPadding = 16.dp
    val heroHeight = 200.dp

    // Home consumes its overscan top inset before the 200dp hero. Library heroes
    // start at the physical top so they include that inset in their own height.
    val libraryHeroHeight = heroHeight + overscanVertical
    val libraryHeroChromeContentInset = 48.dp

    // At the 960dp-wide TV reference viewport this is approximately 16:9 with
    // the 62% backdrop width. It extends behind the first foreground shelf/grid
    // row before its bottom alpha fade, without changing hero content geometry.
    val heroBackdropHeight = 334.dp
    val heroBackdropWidthFraction = 0.62f
    val heroTitleWidthFraction = 0.61f
    val heroContentWidthFraction = 0.62f
    val heroContentTopPadding = 16.dp
    val heroContentGap = 6.dp
    val heroBackdropCrossfadeDurationMs = 650
    val heroContentCrossfadeDurationMs = 600
    val detailBackdropHeight = ChromeDimens.detailBackdropHeight
    val detailBackdropWidthFraction = ChromeDimens.detailBackdropWidthFraction
    val detailHeroTextWidthFraction = ChromeDimens.detailHeroTextWidthFraction
    val heroTopInset = ChromeDimens.heroTopInset
    val detailHeroTopPadding = ChromeDimens.detailHeroTopPadding
    val detailHeroBottomPadding = ChromeDimens.detailHeroBottomPadding
    val detailHeroTextGap = ChromeDimens.detailHeroTextGap
    val detailSectionGap = ChromeDimens.detailSectionGap
    val detailMetaGap = ChromeDimens.detailMetaGap
    val detailIconLabelGap = ChromeDimens.detailIconLabelGap
    val detailActionMinHeight = ChromeDimens.detailActionMinHeight
    val detailBadgeGap = ChromeDimens.detailBadgeGap
    val detailBadgeRadius = ChromeDimens.detailBadgeRadius
    val detailBadgeHorizontalPadding = ChromeDimens.detailBadgeHorizontalPadding
    val detailBadgeVerticalPadding = ChromeDimens.detailBadgeVerticalPadding
    val detailShelfTitleGap = ChromeDimens.detailShelfTitleGap
    val detailShelfBottomPadding = ChromeDimens.detailShelfBottomPadding
    val detailPersonImageHeight = ChromeDimens.detailPersonImageHeight
    val detailPersonTextGap = ChromeDimens.detailPersonTextGap
    val detailBackButtonWidth = ChromeDimens.detailBackButtonWidth
    val seasonTabsGap = ChromeDimens.seasonTabsGap
    val seasonTabHorizontalPadding = ChromeDimens.seasonTabHorizontalPadding
    val seasonTabVerticalPadding = ChromeDimens.seasonTabVerticalPadding
    val seasonTabUnderlineHeight = ChromeDimens.seasonTabUnderlineHeight
    val seasonTabRadius = ChromeDimens.seasonTabRadius
    val seasonMetadataTextWidthFraction = ChromeDimens.seasonMetadataTextWidthFraction
    val seasonMetadataGap = ChromeDimens.seasonMetadataGap
    val seasonEpisodeCardWidth = ChromeDimens.seasonEpisodeCardWidth
    val seasonEpisodeCardHeight = ChromeDimens.seasonEpisodeCardHeight
    val seasonEpisodeTextGap = ChromeDimens.seasonEpisodeTextGap
    val seasonEpisodeBadgePadding = ChromeDimens.seasonEpisodeBadgePadding
    val seasonEpisodeCardBottomPadding = ChromeDimens.seasonEpisodeCardBottomPadding
    val libraryWidth = 156.dp
    val posterHeight = ChromeDimens.posterHeight
    val libraryHeight = 88.dp
    val viewAllIconSize = 36.dp
    val metadataHeight = 92.dp

    // Title + subtitle extent below a poster's focusable image, including the
    // focused poster's trailing scale overflow. Library-grid scrolling includes
    // this so centering the image never clips its labels.
    val cardMetadataHeight = 60.dp
    val progressHeight = ChromeDimens.progressHeight
    val watchedBadgePadding = 6.dp
    val cardBadgePadding = 8.dp
    val cardBadgeRadius = 4.dp
    val playerOverlayHorizontalPadding = 48.dp
    val playerOverlayVerticalPadding = 28.dp
    val playerOverlayGap = 12.dp
    val playerButtonSize = 44.dp
    val playerPlayButtonSize = 54.dp
    val playerOptionButtonSize = 40.dp
    val playerIconSize = ChromeDimens.playerIconSize
    val playerBadgeHorizontalPadding = 8.dp
    val playerBadgeVerticalPadding = 3.dp
    val playerPickerWidth = 640.dp
    val playerPickerRowHeight = ChromeDimens.playerPickerRowHeight
    val playerPickerPadding = ChromeDimens.playerPickerPadding
    val playerPickerDividerVerticalPadding = 8.dp
    val playerPickerDividerHeight = 1.dp
    val playerPickerRowVerticalPadding = 8.dp
    val playerPickerCheckSize = 18.dp
    val playerDebugOverlayMaxHeight = 360.dp
    val playerDebugRowGap = 1.dp
    val playerPanelBorder = ChromeDimens.playerPanelBorder
    val playerSeekFocusHeight = 20.dp
    val playerSeekFocusedTrack = 7.dp
    val playerSeekThumbRadius = 8.dp
    val playerTransportControlGap = 12.dp
    val playerZoomLabelWidth = 54.dp
    val playerSkipButtonWidth = 150.dp
    val playerFloatingPanelWidth = 360.dp
    val playerFloatingPanelPosterWidth = 78.dp
    val playerFloatingPanelPosterHeight = 116.dp
    val playerFloatingPanelPadding = 18.dp
    val playerFloatingPanelGap = 10.dp
    val playerFloatingPanelBottomPadding = 196.dp
    val playerFloatingPanelButtonWidth = 128.dp
    val playerMenuMaxHeight = 520.dp
    val playerMenuSectionTopPadding = 8.dp
    val mediaInfoDialogWidth = ChromeDimens.mediaInfoDialogWidth
    val mediaInfoDialogMaxHeight = ChromeDimens.mediaInfoDialogMaxHeight
    val mediaInfoDialogSectionGap = ChromeDimens.mediaInfoDialogSectionGap
    val mediaInfoDialogLineGap = ChromeDimens.mediaInfoDialogLineGap
    val playerTrickplayThumbnailWidth = 192.dp
    val playerTrickplayThumbnailHeight = 108.dp
    val playerTrickplayPointerWidth = playerOptionButtonSize * 0.75f
    val playerTrickplayPointerHeight = 12.dp
    val playerChapterTickHeight = 12.dp
    val playerChapterTickWidth = 2.dp

    // Reserve for 1.1x focused grid cards when lazy grids clamp at viewport edges.
    val gridContentTopPadding = 12.dp

    // Larger top reserve for grids that sit directly under a control header (library
    // browse): the first row's focused (1.1x) card must clear the header above it.
    val gridContentTopReserve = 28.dp
    val libraryGridContentTopReserve = 40.dp
    val gridContentHorizontalPadding = 12.dp
    val browseControlsGap = 10.dp
    val browseChipMinWidth = 116.dp
    val browsePickerMaxHeight = 720.dp
    val browsePickerGenreListMaxHeight = 300.dp
    val findKeyboardWidth = 560.dp
    val findKeyboardKeyWidth = 64.dp
    val findKeyboardKeyHeight = 44.dp
    val findResultsHeaderGap = 10.dp
    val findPersonChipWidth = 150.dp
    val discoverFacetWidth = 156.dp
    val discoverFacetHeight = 88.dp
    val quickConnectCodePanelWidth = 420.dp
    val quickConnectCodePanelHeight = 142.dp

    // Shared screen-frame inset for both logged-out TV screens (server entry + login).
    val launchScreenPadding = PaddingValues(start = 30.dp, top = 36.dp, end = 30.dp, bottom = 20.dp)
    val launchColumnGap = 42.dp
    val launchSectionGap = 14.dp
    val launchTitleUnderlineGap = 10.dp
    val launchSectionIconSize = 14.dp
    val launchPanelPadding = 20.dp

    // The following intentionally differ from the shared AmbientLaunchDimens values
    // (TV form-factor tuning); do not "unify" them with the mobile/desktop tokens:
    //   launchCardPadding 18 vs cardPadding 14, launchButtonMinHeight 48 vs 52,
    //   launchButtonVerticalPadding 12 vs 14, launchSectionTitleSize 14 vs 18sp,
    //   launchQuickConnectCodeSize 42 vs 32sp.
    val launchCardPadding = 18.dp
    val launchButtonMinHeight = 48.dp
    val launchButtonHorizontalPadding = 20.dp
    val launchButtonVerticalPadding = 12.dp
    val launchUnderlineWidth = 56.dp
    val launchUnderlineHeight = 2.dp
    val launchFocusGlowElevation = 4.dp

    // Horizontal reserve for the discovered-card LazyRow: focus scale overflow
    // (~9dp) + glow spread so the first/last card's glow isn't clipped.
    val launchListHorizontalReserve = 20.dp
    val launchSpinnerSize = 20.dp
    const val LAUNCH_FOCUSED_SCALE = 1.05f
    const val SETTINGS_TILE_FOCUSED_SCALE = 1.1f
    val launchHeadlineSize = 26.sp
    val launchSectionTitleSize = 14.sp
    val launchBodyTextSize = 14.sp
    val launchQuickConnectCodeSize = 42.sp
    val personHeaderPosterWidth = 135.dp
    val personHeaderPosterHeight = 202.dp
    val spinnerSize = ChromeDimens.spinnerSize
    val spinnerStroke = ChromeDimens.spinnerStroke
    val ribbonStartPeek = 18.dp
    val ribbonEndPeek = 32.dp
    val ribbonContentTopPadding = 10.dp
    val ribbonContentBottomPadding = 12.dp
    val cardTitleGap = 8.dp
}
