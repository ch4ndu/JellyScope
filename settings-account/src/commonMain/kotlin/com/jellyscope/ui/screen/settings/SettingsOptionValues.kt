// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.playback.MediaSegmentType

val appThemeOptions: List<AppColorThemeId> = AppColorThemeId.entries.toList()

val segmentSkipPolicyOptions: List<SegmentSkipPolicy> = SegmentSkipPolicy.entries.toList()

// Unknown is deliberately absent: unknown segment types are always ignored.
val segmentSkipTypes: List<MediaSegmentType> =
    listOf(
        MediaSegmentType.Intro,
        MediaSegmentType.Outro,
        MediaSegmentType.Recap,
        MediaSegmentType.Preview,
        MediaSegmentType.Commercial,
    )
