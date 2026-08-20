// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.audioOptions
import com.jellyscope.core.domain.playback.defaultSubtitleStreamIndex
import com.jellyscope.core.domain.playback.preferredAudioStreamIndex
import com.jellyscope.core.domain.playback.preferredSubtitleStreamIndex
import com.jellyscope.core.domain.playback.subtitleOptions
import com.jellyscope.ui.screen.player.PlaybackSelection

data class DetailTrackSelectionUi(
    val mediaSourceId: String? = null,
    val audioOptions: List<AudioTrackOption> = emptyList(),
    val subtitleOptions: List<SubtitleTrackOption> = emptyList(),
    val localSubtitleOptions: List<LocalSubtitleAsset> = emptyList(),
    val defaultAudioStreamIndex: Int? = null,
    val defaultSubtitleStreamIndex: Int? = null,
    val initialSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
    val defaultLocalSubtitleAssetId: String? = null,
)

internal fun List<MediaVersion>.selectedPlaybackVersion(): MediaVersion? = validMediaVersions().firstOrNull()

internal fun List<MediaVersion>.selectedPlaybackVersion(mediaSourceId: String?): MediaVersion? {
    val versions = validMediaVersions()
    return versions.firstOrNull { version -> version.id == mediaSourceId }
        ?: versions.firstOrNull()
}

internal fun List<MediaVersion>.validMediaVersions(): List<MediaVersion> =
    filter { version -> version.id.isNotBlank() }
        .distinctBy { version -> version.id }

internal fun List<MediaVersion>.toMediaVersionUis(strings: DetailFormatterStrings): List<MediaVersionUi> =
    validMediaVersions().mapIndexed { index, version ->
        MediaVersionUi(
            id = version.id,
            name = mediaVersionLabel(version.name, index, strings),
            releaseBasename = version.releaseBasename?.takeIf(String::isNotBlank),
            streamBadges = streamBadges(version),
            mediaInfo = buildMediaInfo(version, version.sizeBytes, strings.externalSubtitleLabel),
            trackSelection =
                version.toDetailTrackSelectionUi(
                    playbackPreferences = PlaybackPreferences().normalized(),
                    rememberedSelection = null,
                ),
            audioTracks = version.audioTracks,
            subtitleTracks = version.subtitleTracks,
            mediaStreams = version.mediaStreams,
            container = version.container,
            runtimeMs = version.runtime?.inWholeMilliseconds,
        )
    }

internal fun List<MediaVersionUi>.selectedMediaVersion(mediaSourceId: String?): MediaVersionUi? =
    firstOrNull { version -> version.id == mediaSourceId }
        ?: firstOrNull()

internal fun MediaVersion?.toDetailTrackSelectionUi(
    playbackPreferences: PlaybackPreferences,
    rememberedSelection: PlaybackSelection?,
    storedSubtitleSelection: SubtitleSelectionIntent? = null,
): DetailTrackSelectionUi {
    val streams = this?.mediaStreams.orEmpty()
    return DetailTrackSelectionUi(
        mediaSourceId = this?.id?.takeIf(String::isNotBlank),
        audioOptions = audioOptions(streams),
        subtitleOptions = subtitleOptions(streams),
    ).resolveLaunchDefaults(
        playbackPreferences = playbackPreferences,
        rememberedSelection = rememberedSelection,
        storedSubtitleSelection = storedSubtitleSelection,
    )
}

internal fun MediaVersionUi.resolveLaunchDefaults(
    playbackPreferences: PlaybackPreferences,
    rememberedSelection: PlaybackSelection?,
    storedSubtitleSelection: SubtitleSelectionIntent?,
): MediaVersionUi =
    copy(
        trackSelection =
            trackSelection.resolveLaunchDefaults(
                playbackPreferences = playbackPreferences,
                rememberedSelection = rememberedSelection,
                storedSubtitleSelection = storedSubtitleSelection,
            ),
    )

internal fun DetailTrackSelectionUi.resolveLaunchDefaults(
    playbackPreferences: PlaybackPreferences,
    rememberedSelection: PlaybackSelection?,
    storedSubtitleSelection: SubtitleSelectionIntent?,
): DetailTrackSelectionUi {
    val audio = audioOptions
    val subtitles = subtitleOptions
    val preferredAudioStreamIndex =
        rememberedSelection?.audioStreamIndex
            ?: audio.preferredAudioStreamIndex(playbackPreferences.preferredAudioLanguage)
    val subtitleDefaults = subtitles.resolveSubtitleDefaults(playbackPreferences, storedSubtitleSelection)

    return copy(
        defaultAudioStreamIndex =
            preferredAudioStreamIndex
                ?: audio.firstOrNull { option -> option.isDefault }?.streamIndex
                ?: audio.firstOrNull()?.streamIndex,
        defaultSubtitleStreamIndex = subtitleDefaults.streamIndex,
        initialSubtitleSelection = subtitleDefaults.initialSelection,
        defaultLocalSubtitleAssetId = subtitleDefaults.localAssetId,
    )
}

/**
 * Recomputes the subtitle defaults from the options already held in state.
 *
 * A durable selection can be dropped locally — deleting the selected local asset
 * clears it — and the rendered default must follow immediately. Reprojecting from
 * the retained options keeps the picker and the play projection agreeing without
 * waiting on the server round trip a full reload needs, so a failed refresh can
 * no longer leave the pre-deletion selection on screen.
 */
internal fun DetailTrackSelectionUi.reprojectSubtitleDefaults(
    playbackPreferences: PlaybackPreferences,
    storedSubtitleSelection: SubtitleSelectionIntent?,
): DetailTrackSelectionUi {
    val defaults = subtitleOptions.resolveSubtitleDefaults(playbackPreferences, storedSubtitleSelection)
    return copy(
        defaultSubtitleStreamIndex = defaults.streamIndex,
        initialSubtitleSelection = defaults.initialSelection,
        defaultLocalSubtitleAssetId = defaults.localAssetId,
    )
}

private data class ResolvedSubtitleDefaults(
    val streamIndex: Int?,
    val initialSelection: SubtitleSelectionIntent,
    val localAssetId: String?,
)

private fun List<SubtitleTrackOption>.resolveSubtitleDefaults(
    playbackPreferences: PlaybackPreferences,
    storedSubtitleSelection: SubtitleSelectionIntent?,
): ResolvedSubtitleDefaults {
    val streamIndex =
        when (storedSubtitleSelection) {
            SubtitleSelectionIntent.Off -> null
            is SubtitleSelectionIntent.Track ->
                storedSubtitleSelection.streamIndex.takeIf { streamIndex ->
                    any { option -> option.streamIndex == streamIndex }
                } ?: preferredSubtitleStreamIndex(playbackPreferences.preferredSubtitleLanguage)
                    ?: defaultSubtitleStreamIndex()
            is SubtitleSelectionIntent.LocalAsset -> null
            SubtitleSelectionIntent.Unspecified,
            null,
            ->
                preferredSubtitleStreamIndex(playbackPreferences.preferredSubtitleLanguage)
                    ?: defaultSubtitleStreamIndex()
        }
    return ResolvedSubtitleDefaults(
        streamIndex = streamIndex,
        initialSelection =
            when (storedSubtitleSelection) {
                SubtitleSelectionIntent.Off -> SubtitleSelectionIntent.Off
                is SubtitleSelectionIntent.LocalAsset -> storedSubtitleSelection
                else ->
                    streamIndex?.let(SubtitleSelectionIntent::Track)
                        ?: SubtitleSelectionIntent.Unspecified
            },
        localAssetId = (storedSubtitleSelection as? SubtitleSelectionIntent.LocalAsset)?.assetId,
    )
}
