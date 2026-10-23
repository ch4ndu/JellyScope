// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

@Composable
internal fun LauncherShell(
    resumeGeneration: Int,
    settingsUnavailable: Boolean,
    onSystemSettings: () -> Unit,
    onDismissSettingsError: () -> Unit,
) {
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(resumeGeneration, settingsUnavailable) {
        if (!settingsUnavailable) settingsFocus.restoreLauncherFocus()
    }

    MaterialTheme(colorScheme = launcherColors) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(32.dp)
                    .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.launcher_setup_message),
                modifier = Modifier.widthIn(max = 560.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Button(
                onClick = onSystemSettings,
                modifier = Modifier.focusRequester(settingsFocus),
                colors =
                    ButtonDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.primary,
                        focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            ) {
                Text(stringResource(R.string.system_settings))
            }
        }

        if (settingsUnavailable) {
            SettingsUnavailableDialog(onDismiss = onDismissSettingsError)
        }
    }
}

@Composable
private fun SettingsUnavailableDialog(onDismiss: () -> Unit) {
    val dismissFocus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        LaunchedEffect(Unit) { dismissFocus.restoreLauncherFocus() }
        Column(
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(24.dp)
                    .focusGroup()
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_unavailable_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_unavailable_message),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .focusRequester(dismissFocus)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        },
                colors =
                    ButtonDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.primary,
                        focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            ) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

private fun FocusRequester.restoreLauncherFocus() {
    if (!runCatching { requestFocus() }.getOrDefault(false)) {
        Log.w("LauncherFocus", "stage=shell event=focus-rejected")
    }
}

private val launcherColors =
    darkColorScheme(
        primary = Color(0xFF22B8D4),
        onPrimary = Color(0xFF081420),
        background = Color(0xFF081420),
        onBackground = Color.White,
        surface = Color(0xFF12293D),
        onSurface = Color.White,
    )
