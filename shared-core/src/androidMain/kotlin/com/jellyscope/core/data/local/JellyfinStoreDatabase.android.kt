// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.room.Room
import java.io.File

internal fun createJellyfinStoreDatabase(context: Context): JellyfinStoreDatabase =
    Room
        .databaseBuilder<JellyfinStoreDatabase>(
            context = context.applicationContext,
            name = File(context.applicationContext.filesDir, JELLYFIN_STORE_DATABASE_NAME).absolutePath,
        ).buildJellyfinStore(seedVlcDefaultBps = ANDROID_VLC_DEFAULT_TRANSCODE_BITRATE_BPS)

/** Android-only VLC crash-safety default (8 Mbps rung). */
internal const val ANDROID_VLC_DEFAULT_TRANSCODE_BITRATE_BPS = 8_000_000L
