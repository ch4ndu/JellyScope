// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jellyscope.ui.component.ChromeDimens

// C5: constant backdrop fade gradients, shared by the adaptive detail/series
// backdrops. Hoisted out of drawWithContent so they aren't re-allocated on every
// draw frame during the backdrop crossfade.
val detailBackdropHorizontalFade =
    Brush.horizontalGradient(
        0f to Color.Transparent,
        0.45f to Color.Black,
    )
val detailBackdropVerticalFade =
    Brush.verticalGradient(
        0f to Color.Black,
        0.62f to Color.Black,
        1f to Color.Transparent,
    )

object DetailDimens {
    val overscanHorizontal = ChromeDimens.overscanHorizontal
    val overscanVertical = ChromeDimens.overscanVertical
    val focusBorder = ChromeDimens.focusBorder
    val cardRadius = ChromeDimens.cardRadius
    val panelRadius = ChromeDimens.panelRadius
    val rowGap = ChromeDimens.rowGap
    val itemGap = ChromeDimens.itemGap
    val buttonHorizontalPadding = ChromeDimens.buttonHorizontalPadding
    val buttonVerticalPadding = ChromeDimens.buttonVerticalPadding
    val posterWidth = ChromeDimens.posterWidth
    val posterHeight = ChromeDimens.posterHeight
    val detailBackdropHeight = ChromeDimens.detailBackdropHeight
    val detailBackdropWidthFraction = ChromeDimens.detailBackdropWidthFraction
    val detailHeroTextWidthFraction = ChromeDimens.detailHeroTextWidthFraction
    val heroTopInset = ChromeDimens.heroTopInset
    val detailHeroTopPadding = ChromeDimens.detailHeroTopPadding
    val detailHeroBottomPadding = ChromeDimens.detailHeroBottomPadding
    val detailHeroLogoMaxHeight = ChromeDimens.detailHeroLogoMaxHeight
    val detailHeroTextGap = ChromeDimens.detailHeroTextGap
    val detailSectionGap = ChromeDimens.detailSectionGap
    val detailMetaGap = ChromeDimens.detailMetaGap
    val detailIconLabelGap = ChromeDimens.detailIconLabelGap
    val detailActionHeight = ChromeDimens.detailActionHeight
    val detailActionIconSize = ChromeDimens.detailActionIconSize
    val detailActionOutlineWidth = 1.dp
    val detailTrackPickerButtonWidth = ChromeDimens.detailTrackPickerButtonWidth
    val detailTrackPickerArrowSize = ChromeDimens.detailTrackPickerArrowSize
    val detailBadgeGap = ChromeDimens.detailBadgeGap
    val detailBadgeRadius = ChromeDimens.detailBadgeRadius
    val detailBadgeIconSize = 12.dp
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
    val progressHeight = ChromeDimens.progressHeight
    val playerIconSize = ChromeDimens.playerIconSize
    val playerPickerRowHeight = ChromeDimens.playerPickerRowHeight
    val playerPanelBorder = ChromeDimens.playerPanelBorder
    val detailTrackPickerWidth = ChromeDimens.detailTrackPickerWidth
    val detailTrackPickerMaxHeight = ChromeDimens.detailTrackPickerMaxHeight
    val detailTrackPickerListMaxHeight = ChromeDimens.detailTrackPickerListMaxHeight
    val detailTrackPickerRowVerticalPadding = ChromeDimens.detailTrackPickerRowVerticalPadding
    val detailTrackPickerCheckSize = ChromeDimens.detailTrackPickerCheckSize
    val detailTrackPickerDividerVerticalPadding = ChromeDimens.detailTrackPickerDividerVerticalPadding
    val detailTrackPickerDividerHeight = ChromeDimens.detailTrackPickerDividerHeight
    val mediaInfoDialogWidth = ChromeDimens.mediaInfoDialogWidth
    val mediaInfoDialogMaxHeight = ChromeDimens.mediaInfoDialogMaxHeight
    val mediaInfoDialogSectionGap = ChromeDimens.mediaInfoDialogSectionGap
    val mediaInfoDialogLineGap = ChromeDimens.mediaInfoDialogLineGap
    val playerPickerPadding = ChromeDimens.playerPickerPadding
    val spinnerSize = ChromeDimens.spinnerSize
    val spinnerStroke = ChromeDimens.spinnerStroke
}
