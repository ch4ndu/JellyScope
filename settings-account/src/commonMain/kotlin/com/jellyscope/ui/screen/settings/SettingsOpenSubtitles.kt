// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_clear_subtitles
import com.jellyscope.ui.generated.resources.settings_local_subtitles_clear_error
import com.jellyscope.ui.generated.resources.settings_local_subtitles_clear_success
import com.jellyscope.ui.generated.resources.settings_local_subtitles_clearing
import com.jellyscope.ui.generated.resources.settings_opensubtitles
import com.jellyscope.ui.generated.resources.settings_opensubtitles_api_key
import com.jellyscope.ui.generated.resources.settings_opensubtitles_key
import com.jellyscope.ui.generated.resources.settings_opensubtitles_key_absent
import com.jellyscope.ui.generated.resources.settings_opensubtitles_key_dialog_subtitle
import com.jellyscope.ui.generated.resources.settings_opensubtitles_key_error
import com.jellyscope.ui.generated.resources.settings_opensubtitles_key_load_error
import com.jellyscope.ui.generated.resources.settings_opensubtitles_key_present
import com.jellyscope.ui.generated.resources.settings_opensubtitles_preference
import com.jellyscope.ui.generated.resources.settings_opensubtitles_preference_error
import com.jellyscope.ui.generated.resources.settings_opensubtitles_preference_forced
import com.jellyscope.ui.generated.resources.settings_opensubtitles_preference_hearing_impaired
import com.jellyscope.ui.generated.resources.settings_opensubtitles_preference_load_error
import com.jellyscope.ui.generated.resources.settings_opensubtitles_preference_none
import com.jellyscope.ui.generated.resources.settings_opensubtitles_preference_retry
import com.jellyscope.ui.generated.resources.settings_opensubtitles_preference_supporting
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OpenSubtitlesSettingsSection(
    apiKey: String,
    isApiKeyLoaded: Boolean,
    isLoadingApiKey: Boolean,
    isSavingApiKey: Boolean,
    apiKeyLoadError: Boolean,
    apiKeyError: Boolean,
    onApiKeyChange: (String, () -> Unit) -> Unit,
    onRetryApiKeyLoad: () -> Unit,
    resultPreference: OpenSubtitleResultPreference,
    isResultPreferenceLoaded: Boolean,
    isLoadingResultPreference: Boolean,
    resultPreferenceLoadError: Boolean,
    isSavingResultPreference: Boolean,
    resultPreferenceError: Boolean,
    onResultPreferenceChange: (OpenSubtitleResultPreference, () -> Unit) -> Unit,
    onRetryResultPreferenceLoad: () -> Unit,
    isClearingLocalSubtitles: Boolean,
    localSubtitlesClearCompletion: Int,
    localSubtitlesClearError: Boolean,
    onClearLocalSubtitles: () -> Unit,
    openRow: SettingsRowId?,
    onOpenRow: (SettingsRowId) -> Unit,
    onDismiss: () -> Unit,
) {
    val footer: (@Composable () -> Unit)? =
        if (
            localSubtitlesClearError ||
            localSubtitlesClearCompletion > 0 ||
            apiKeyLoadError ||
            apiKeyError ||
            resultPreferenceError ||
            resultPreferenceLoadError
        ) {
            {
                Column {
                    if (apiKeyLoadError) {
                        androidx.compose.material3.Text(
                            text = stringResource(Res.string.settings_opensubtitles_key_load_error),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    }
                    if (apiKeyError) {
                        androidx.compose.material3.Text(
                            text = stringResource(Res.string.settings_opensubtitles_key_error),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    }
                    if (resultPreferenceLoadError) {
                        androidx.compose.material3.Text(
                            text = stringResource(Res.string.settings_opensubtitles_preference_load_error),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    }
                    if (resultPreferenceError) {
                        androidx.compose.material3.Text(
                            text = stringResource(Res.string.settings_opensubtitles_preference_error),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    }
                    if (localSubtitlesClearError) {
                        androidx.compose.material3.Text(
                            text = stringResource(Res.string.settings_local_subtitles_clear_error),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    }
                    if (localSubtitlesClearCompletion > 0 && !localSubtitlesClearError) {
                        androidx.compose.material3.Text(
                            text = stringResource(Res.string.settings_local_subtitles_clear_success),
                        )
                    }
                }
            }
        } else {
            null
        }

    SettingsSectionCard(
        title = stringResource(Res.string.settings_opensubtitles),
        rows =
            listOf(
                {
                    SettingsRow(
                        icon = SettingsRowId.OpenSubtitlesKey.icon(),
                        iconRole = SettingsRowId.OpenSubtitlesKey.iconRole(),
                        title = stringResource(Res.string.settings_opensubtitles_key),
                        // Never expose the key in visible or accessible text.
                        value =
                            stringResource(
                                when {
                                    isApiKeyLoaded && apiKey.isNotBlank() -> Res.string.settings_opensubtitles_key_present
                                    apiKeyLoadError -> Res.string.settings_opensubtitles_preference_retry
                                    else -> Res.string.settings_opensubtitles_key_absent
                                },
                            ),
                        trailing =
                            if (isLoadingApiKey) {
                                SettingsRowTrailing.Progress(running = true)
                            } else {
                                SettingsRowTrailing.Chevron
                            },
                        onClick = {
                            if (isApiKeyLoaded && !isSavingApiKey) {
                                onOpenRow(SettingsRowId.OpenSubtitlesKey)
                            } else if (apiKeyLoadError) {
                                onRetryApiKeyLoad()
                            }
                        },
                        enabled = (isApiKeyLoaded && !isSavingApiKey) || apiKeyLoadError,
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.SubtitleResultPreference.icon(),
                        iconRole = SettingsRowId.SubtitleResultPreference.iconRole(),
                        title = stringResource(Res.string.settings_opensubtitles_preference),
                        value =
                            when {
                                isResultPreferenceLoaded -> resultPreference.label()
                                resultPreferenceLoadError -> stringResource(Res.string.settings_opensubtitles_preference_retry)
                                else -> null
                            },
                        description = stringResource(Res.string.settings_opensubtitles_preference_supporting),
                        trailing =
                            if (isLoadingResultPreference) {
                                SettingsRowTrailing.Progress(running = true)
                            } else {
                                SettingsRowTrailing.Chevron
                            },
                        onClick = {
                            if (isResultPreferenceLoaded) {
                                onOpenRow(SettingsRowId.SubtitleResultPreference)
                            } else {
                                onRetryResultPreferenceLoad()
                            }
                        },
                        enabled = isResultPreferenceLoaded || resultPreferenceLoadError,
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.ClearSubtitles.icon(),
                        iconRole = SettingsRowId.ClearSubtitles.iconRole(),
                        title = stringResource(Res.string.settings_clear_subtitles),
                        value =
                            if (isClearingLocalSubtitles) {
                                stringResource(Res.string.settings_local_subtitles_clearing)
                            } else {
                                null
                            },
                        trailing = SettingsRowTrailing.Progress(running = isClearingLocalSubtitles),
                        onClick = onClearLocalSubtitles,
                    )
                },
            ),
        footer = footer,
    )

    if (openRow == SettingsRowId.OpenSubtitlesKey && isApiKeyLoaded) {
        SettingsTextDialog(
            title = stringResource(Res.string.settings_opensubtitles_key),
            value = apiKey,
            subtitle = stringResource(Res.string.settings_opensubtitles_key_dialog_subtitle),
            label = stringResource(Res.string.settings_opensubtitles_api_key),
            visualTransformation = PasswordVisualTransformation(),
            onSave = { value, dismiss ->
                onApiKeyChange(value, dismiss)
            },
            onDismiss = onDismiss,
        )
    }

    if (openRow == SettingsRowId.SubtitleResultPreference && isResultPreferenceLoaded) {
        SettingsOptionPicker(
            title = stringResource(Res.string.settings_opensubtitles_preference),
            subtitle =
                stringResource(
                    if (resultPreferenceError) {
                        Res.string.settings_opensubtitles_preference_error
                    } else {
                        Res.string.settings_opensubtitles_preference_supporting
                    },
                ),
            options =
                OpenSubtitleResultPreference.entries.map { preference ->
                    SettingsPickerOption(
                        value = preference,
                        label = preference.label(),
                    )
                },
            selectedValue = resultPreference,
            onOptionSelected = { preference ->
                onResultPreferenceChange(preference, onDismiss)
            },
            onDismiss = onDismiss,
            selectionInProgress = isSavingResultPreference,
            dismissOnSelection = false,
        )
    }
}

@Composable
private fun OpenSubtitleResultPreference.label(): String =
    stringResource(
        when (this) {
            OpenSubtitleResultPreference.NoPreference -> Res.string.settings_opensubtitles_preference_none
            OpenSubtitleResultPreference.PreferHearingImpaired -> Res.string.settings_opensubtitles_preference_hearing_impaired
            OpenSubtitleResultPreference.PreferForced -> Res.string.settings_opensubtitles_preference_forced
        },
    )
