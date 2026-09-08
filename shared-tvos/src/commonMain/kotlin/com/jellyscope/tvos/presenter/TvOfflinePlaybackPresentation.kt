// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OfflineTrackKind

internal fun OfflineMediaSnapshot.offlineAudioChoices(selectedStreamIndex: Int?): List<TvTrackChoice> =
    embeddedTracks
        .filter { track -> track.kind == OfflineTrackKind.Audio && !track.isExternal }
        .mapIndexedNotNull { ordinal, track ->
            track.streamIndex?.let { streamIndex ->
                TvTrackChoice(
                    streamIndex = streamIndex,
                    languageCode = track.language,
                    displayLabel = track.label,
                    ordinal = ordinal,
                    selected = streamIndex == selectedStreamIndex,
                )
            }
        }

internal fun OfflineMediaSnapshot.offlineSubtitleChoices(
    selectedChoiceKey: Int?,
    localSubtitleChoiceKey: Int?,
): List<TvTrackChoice> =
    embeddedTracks
        .filter { track -> track.kind == OfflineTrackKind.Subtitle && !track.isExternal }
        .plus(selectedSubtitleTrack?.takeIf { track -> track.isExternal }.let(::listOfNotNull))
        .mapIndexedNotNull { ordinal, track ->
            val choiceKey =
                track.streamIndex
                    ?: localSubtitleChoiceKey?.takeIf { track.isExternal }
                    ?: return@mapIndexedNotNull null
            TvTrackChoice(
                streamIndex = choiceKey,
                languageCode = track.language,
                displayLabel = track.label,
                ordinal = ordinal,
                selected = choiceKey == selectedChoiceKey,
            )
        }
