// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailHeadlineStyle as TvHeadlineStyle
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailTitleStyle as TvTitleStyle

val TvScreenHeaderStyle
    @Composable get() = TvHeadlineStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold)

val TvPickerTitleStyle
    @Composable get() = TvTitleStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)

val TvHeroTitleStyle
    @Composable get() = TvHeadlineStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold)

val TvHeroMetadataStyle
    @Composable get() = TvBodyStyle.copy(fontSize = 14.sp)

val TvHeroOverviewStyle
    @Composable get() = TvSecondaryStyle.copy(fontSize = 13.sp)

val TvHomeRowTitleStyle
    @Composable get() = TvTitleStyle.copy(fontSize = 17.sp)

val TvCardLabelStyle
    @Composable get() = TvBodyStyle.copy(fontSize = 12.sp)

val TvDiscoverFacetTitleStyle
    @Composable get() = TvBodyStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

val TvPlayerSmallTitleStyle
    @Composable get() = TvTitleStyle.copy(fontSize = 14.sp)

val TvPlayerQueueTitleStyle
    @Composable get() = TvTitleStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)

val TvPlayerNowPlayingTitleStyle
    @Composable get() = TvTitleStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold)

val TvPlayerNowPlayingMetadataStyle
    @Composable get() = TvSecondaryStyle.copy(fontSize = 13.sp)

val TvPlayerSectionTitleStyle
    @Composable get() = TvTitleStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
