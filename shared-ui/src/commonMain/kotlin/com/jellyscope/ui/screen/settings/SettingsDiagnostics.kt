// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.util.LogBufferSize
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_diagnostics
import com.jellyscope.ui.generated.resources.settings_diagnostics_buffer_size
import com.jellyscope.ui.generated.resources.settings_diagnostics_collect
import com.jellyscope.ui.generated.resources.settings_diagnostics_collect_detail
import com.jellyscope.ui.generated.resources.settings_diagnostics_collect_error
import com.jellyscope.ui.generated.resources.settings_diagnostics_failure
import com.jellyscope.ui.generated.resources.settings_diagnostics_playback_info_at_start
import com.jellyscope.ui.generated.resources.settings_diagnostics_playback_info_at_start_detail
import com.jellyscope.ui.generated.resources.settings_diagnostics_send
import com.jellyscope.ui.generated.resources.settings_diagnostics_send_detail
import com.jellyscope.ui.generated.resources.settings_diagnostics_upload_disallowed
import com.jellyscope.ui.generated.resources.settings_diagnostics_uploaded
import com.jellyscope.ui.generated.resources.settings_diagnostics_verbose_logcat
import com.jellyscope.ui.generated.resources.settings_diagnostics_verbose_logcat_detail
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DiagnosticsSettingsSection(
    collectLogs: Boolean,
    verboseLogcatEnabled: Boolean,
    playbackInfoAtStartEnabled: Boolean,
    bufferSize: LogBufferSize,
    isSending: Boolean,
    logCollectionPreferenceError: Boolean,
    feedback: SendClientLogsResult?,
    onCollectLogsChange: (Boolean) -> Unit,
    onVerboseLogcatChange: (Boolean) -> Unit,
    onPlaybackInfoAtStartChange: (Boolean) -> Unit,
    onSendLogs: () -> Unit,
) {
    SettingsSectionCard(
        title = stringResource(Res.string.settings_diagnostics),
        rows =
            listOf(
                {
                    SettingsRow(
                        icon = SettingsRowId.CollectLogs.icon(),
                        iconRole = SettingsRowId.CollectLogs.iconRole(),
                        title = stringResource(Res.string.settings_diagnostics_collect),
                        value = settingsBooleanValue(collectLogs),
                        description = stringResource(Res.string.settings_diagnostics_collect_detail),
                        trailing =
                            SettingsRowTrailing.Switch(
                                checked = collectLogs,
                                onCheckedChange = onCollectLogsChange,
                            ),
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.SystemLogs.icon(),
                        iconRole = SettingsRowId.SystemLogs.iconRole(),
                        title = stringResource(Res.string.settings_diagnostics_verbose_logcat),
                        value = settingsBooleanValue(verboseLogcatEnabled),
                        description = stringResource(Res.string.settings_diagnostics_verbose_logcat_detail),
                        trailing =
                            SettingsRowTrailing.Switch(
                                checked = verboseLogcatEnabled,
                                onCheckedChange = onVerboseLogcatChange,
                            ),
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.PlaybackInfoAtStart.icon(),
                        iconRole = SettingsRowId.PlaybackInfoAtStart.iconRole(),
                        title = stringResource(Res.string.settings_diagnostics_playback_info_at_start),
                        value = settingsBooleanValue(playbackInfoAtStartEnabled),
                        description = stringResource(Res.string.settings_diagnostics_playback_info_at_start_detail),
                        trailing =
                            SettingsRowTrailing.Switch(
                                checked = playbackInfoAtStartEnabled,
                                onCheckedChange = onPlaybackInfoAtStartChange,
                            ),
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.SendClientLogs.icon(),
                        iconRole = SettingsRowId.SendClientLogs.iconRole(),
                        title = stringResource(Res.string.settings_diagnostics_send),
                        value =
                            stringResource(
                                Res.string.settings_diagnostics_buffer_size,
                                bufferSize.entryCount,
                                bufferSize.byteCount / 1_024,
                            ),
                        description = stringResource(Res.string.settings_diagnostics_send_detail),
                        trailing = SettingsRowTrailing.Progress(running = isSending),
                        onClick = onSendLogs,
                    )
                },
            ),
        footer =
            if (logCollectionPreferenceError || feedback != null) {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                        if (logCollectionPreferenceError) {
                            Text(text = stringResource(Res.string.settings_diagnostics_collect_error))
                        }
                        feedback?.let { result ->
                            Text(text = diagnosticsFeedback(result))
                        }
                    }
                }
            } else {
                null
            },
    )
}

@Composable
private fun diagnosticsFeedback(result: SendClientLogsResult): String =
    when (result) {
        is SendClientLogsResult.Success ->
            stringResource(Res.string.settings_diagnostics_uploaded, result.filename)
        SendClientLogsResult.UploadDisallowed ->
            stringResource(Res.string.settings_diagnostics_upload_disallowed)
        SendClientLogsResult.Failure -> stringResource(Res.string.settings_diagnostics_failure)
    }
