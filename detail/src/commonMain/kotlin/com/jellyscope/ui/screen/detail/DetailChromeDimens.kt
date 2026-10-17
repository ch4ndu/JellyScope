// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.jellyscope.ui.component.DetailBodyStyle
import com.jellyscope.ui.component.DetailHeadlineStyle
import com.jellyscope.ui.component.DetailSecondaryStyle
import com.jellyscope.ui.component.DetailTitleStyle
import com.jellyscope.ui.theme.Dimensions

internal val DetailActionLabelStyle
    @Composable get() = DetailBodyStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

internal val DetailActionSublabelStyle
    @Composable get() = DetailSecondaryStyle.copy(fontSize = 10.sp)

internal val DetailCaptionStyle
    @Composable get() = DetailBodyStyle.copy(fontSize = 12.sp)

internal val DetailPickerTitleStyle
    @Composable get() = DetailBodyStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)

internal val DetailMediaInfoTitleStyle
    @Composable get() = DetailTitleStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)

internal val DetailSeasonHeaderStyle
    @Composable get() = DetailHeadlineStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold)

internal val DetailSecondaryBodyStyle
    @Composable get() = DetailSecondaryStyle.copy(fontSize = 13.sp)

@Composable
internal fun detailAdaptiveHeroTopPadding(): Dp =
    if (isDetailDpadMode()) {
        DetailDimens.detailHeroTopPadding
    } else {
        detailOverlayBackButtonTopPadding()
    }

@Composable
internal fun detailOverlayBackButtonTopPadding(): Dp =
    WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() +
        Dimensions.detailHeroOverlayBackButtonTopPadding
