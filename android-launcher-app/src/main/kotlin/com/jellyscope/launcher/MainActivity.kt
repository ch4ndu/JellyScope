// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var resumeGeneration by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var settingsUnavailable by rememberSaveable { mutableStateOf(false) }
            LauncherShell(
                resumeGeneration = resumeGeneration,
                settingsUnavailable = settingsUnavailable,
                onSystemSettings = { settingsUnavailable = !openSystemSettings() },
                onDismissSettingsError = { settingsUnavailable = false },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        resumeGeneration += 1
    }

    private fun openSystemSettings(): Boolean =
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
}
