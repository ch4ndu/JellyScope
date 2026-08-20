// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.android

import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.ui.AppInfo
import org.koin.dsl.module

val androidAppModule =
    module {
        single { ClientInfo(versionName = BuildConfig.VERSION_NAME) }
        single { AppInfo(versionName = get<ClientInfo>().versionName) }
        single { PlaybackHealthGuidancePolicy.Actionable }
    }
