// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.playbackQualityChoices

internal sealed interface TvSettingsChange {
    data class DiagnosticsObserved(
        val enabled: Boolean,
    ) : TvSettingsChange

    data class PlaybackInfoObserved(
        val enabled: Boolean,
    ) : TvSettingsChange

    data class LogSizeObserved(
        val entryCount: Int,
        val byteCount: Int,
    ) : TvSettingsChange

    data object SaveFailed : TvSettingsChange

    data class DiagnosticPreferenceChanged(
        val failed: Boolean,
    ) : TvSettingsChange

    data class PlaybackInfoSaveStarted(
        val enabled: Boolean,
    ) : TvSettingsChange

    data object LogsStarted : TvSettingsChange

    data class LogsFinished(
        val result: SendClientLogsResult?,
    ) : TvSettingsChange

    data class TimelineChanged(
        val result: TvPlaybackTimelineSendResult,
        val filename: String?,
    ) : TvSettingsChange

    data class PreferencesChanged(
        val updated: PlaybackPreferences,
    ) : TvSettingsChange

    data class LoadStarted(
        val serverName: String,
        val serverUrl: String,
        val userName: String,
    ) : TvSettingsChange

    data class LoadFailed(
        val confirmed: PlaybackPreferences?,
    ) : TvSettingsChange

    data class Loaded(
        val preferences: PlaybackPreferences,
    ) : TvSettingsChange

    data class PlaybackInfoSaved(
        val enabled: Boolean,
    ) : TvSettingsChange

    data class PlaybackInfoFailed(
        val enabled: Boolean,
    ) : TvSettingsChange

    data class ToggleObserved(
        val publish: (TvSettingsState, Boolean) -> TvSettingsState,
        val enabled: Boolean,
    ) : TvSettingsChange

    data class ToggleFailed(
        val publish: (TvSettingsState, Boolean) -> TvSettingsState,
        val enabled: Boolean,
    ) : TvSettingsChange

    data class ToggleChanged(
        val publish: (TvSettingsState, Boolean) -> TvSettingsState,
        val enabled: Boolean,
    ) : TvSettingsChange
}

internal fun reduceTvSettings(
    current: TvSettingsState,
    change: TvSettingsChange,
): TvSettingsState =
    when (change) {
        is TvSettingsChange.DiagnosticsObserved ->
            with(change) {
                current.copy(diagnosticCollectionEnabled = enabled)
            }
        is TvSettingsChange.PlaybackInfoObserved ->
            with(change) {
                current.copy(playbackInfoAtStartEnabled = enabled)
            }
        is TvSettingsChange.LogSizeObserved ->
            with(change) {
                current.copy(
                    diagnosticEntryCount = entryCount,
                    diagnosticByteCount = byteCount,
                )
            }
        TvSettingsChange.SaveFailed ->
            run {
                current.copy(saveError = true)
            }
        is TvSettingsChange.DiagnosticPreferenceChanged ->
            with(change) {
                current.copy(diagnosticPreferenceError = failed)
            }
        is TvSettingsChange.PlaybackInfoSaveStarted ->
            with(change) {
                current.copy(
                    playbackInfoAtStartEnabled = enabled,
                    isSavingPlaybackInfoAtStart = true,
                    playbackInfoAtStartSaveError = false,
                )
            }
        TvSettingsChange.LogsStarted ->
            run {
                current.copy(diagnosticSendResult = TvDiagnosticsSendResult.Sending)
            }
        is TvSettingsChange.LogsFinished ->
            with(change) {
                current.copy(
                    diagnosticSendResult =
                        when (result) {
                            is SendClientLogsResult.Success -> TvDiagnosticsSendResult.Success
                            SendClientLogsResult.UploadDisallowed -> TvDiagnosticsSendResult.UploadDisallowed
                            SendClientLogsResult.Failure, null -> TvDiagnosticsSendResult.Failure
                        },
                )
            }
        is TvSettingsChange.TimelineChanged ->
            with(change) {
                current.copy(playbackTimelineSendResult = result, playbackTimelineFilename = filename)
            }
        is TvSettingsChange.PreferencesChanged ->
            with(change) {
                current.withPlaybackPreferences(updated).copy(saveError = false)
            }
        is TvSettingsChange.LoadStarted ->
            with(change) {
                current.copy(
                    isLoading = true,
                    serverName = serverName,
                    serverUrl = serverUrl,
                    userName = userName,
                )
            }
        is TvSettingsChange.LoadFailed ->
            with(change) {
                val failed = current.copy(isLoading = false, loadError = true)
                if (confirmed == null) failed else failed.withPlaybackPreferences(confirmed)
            }
        is TvSettingsChange.Loaded ->
            with(change) {
                current.withPlaybackPreferences(preferences).copy(isLoading = false, loadError = false)
            }
        is TvSettingsChange.PlaybackInfoSaved ->
            with(change) {
                current.copy(
                    playbackInfoAtStartEnabled = enabled,
                    isSavingPlaybackInfoAtStart = false,
                    playbackInfoAtStartSaveError = false,
                )
            }
        is TvSettingsChange.PlaybackInfoFailed ->
            with(change) {
                current.copy(
                    playbackInfoAtStartEnabled = enabled,
                    isSavingPlaybackInfoAtStart = false,
                    playbackInfoAtStartSaveError = true,
                )
            }
        is TvSettingsChange.ToggleObserved ->
            with(change) {
                publish(current, enabled)
            }
        is TvSettingsChange.ToggleFailed ->
            with(change) {
                publish(current, enabled).copy(saveError = true)
            }
        is TvSettingsChange.ToggleChanged ->
            with(change) {
                publish(current, enabled).copy(saveError = false)
            }
    }

private fun TvSettingsState.withPlaybackPreferences(preferences: PlaybackPreferences): TvSettingsState =
    copy(
        preferences = preferences,
        stillWatchingPrompt = preferences.stillWatchingPrompt,
        autoPlayNextDelaySeconds = preferences.autoPlayNextDelaySeconds,
        bitrateChoices = bitrateChoices(preferences.effectiveDefaultQualityPolicy()),
    )

// Same shared quality ladder the in-player Quality menu renders, so a
// default picked here always matches a confirmed rung during playback.
private fun bitrateChoices(selected: PlaybackQualityPolicy): List<TvQualityChoice> =
    playbackQualityChoices(selected).map { option ->
        TvQualityChoice(
            maxBitrateBps = option.maxBitrateBps,
            resolutionHeight = option.resolutionHeight,
            isCustom = option.isCustom,
            selected = option.mode == selected.mode && option.maxBitrateBps == selected.maxBitrateBps,
            mode = option.mode,
        )
    }
