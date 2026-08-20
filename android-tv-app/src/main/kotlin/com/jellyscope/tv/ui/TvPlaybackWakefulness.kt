// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

@Composable
internal fun TvPlaybackWakefulnessEffect(activity: Activity?) {
    DisposableEffect(activity) {
        setTvPlaybackWakefulness(activity = activity, enabled = true)
        onDispose {
            setTvPlaybackWakefulness(activity = activity, enabled = false)
        }
    }
}

internal fun setTvPlaybackWakefulness(
    activity: Activity?,
    enabled: Boolean,
) {
    val window = activity?.window ?: return
    if (enabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
