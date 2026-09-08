// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaPerson
import com.jellyscope.core.domain.model.MediaPersonType
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackLaunchContext
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.ResumeDecision
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.audioOptions
import com.jellyscope.core.domain.playback.defaultSubtitleStreamIndex
import com.jellyscope.core.domain.playback.preferredAudioStreamIndex
import com.jellyscope.core.domain.playback.preferredSubtitleStreamIndex
import com.jellyscope.core.domain.playback.resumeDecision
import com.jellyscope.core.domain.playback.subtitleOptions
import kotlin.math.roundToInt

internal data class TvDetailProjection(
    val content: TvItemDetailContent,
    val versions: List<TvDetailVersionChoice>,
    val selectedMediaSourceId: String?,
    val audioTracks: List<TvDetailTrackChoice>,
    val subtitleTracks: List<TvDetailTrackChoice>,
    val subtitleMode: TvPlaybackSubtitleMode,
    val selectedSubtitleStreamIndex: Int?,
    val selectedSubtitleAssetId: String?,
    val mediaInfo: List<TvDetailMediaInfoRow>,
    val cast: List<TvDetailPerson>,
    val crew: List<TvDetailPerson>,
)

internal fun MediaItemDetail.tvDetailVersions(): List<MediaVersion> =
    versions
        .ifEmpty { item.versions }
        .filter { version -> version.id.isNotBlank() }
        .distinctBy(MediaVersion::id)

internal fun MediaItemDetail.toTvDetailProjection(
    session: Session,
    imageUrlBuilder: JellyfinImageUrlBuilder,
    requestedMediaSourceId: String?,
    explicitAudioStreamIndex: Int?,
    explicitSubtitleMode: TvPlaybackSubtitleMode?,
    explicitSubtitleStreamIndex: Int?,
    validatedLocalSubtitleAssetId: String?,
    launchContext: PlaybackLaunchContext,
): TvDetailProjection {
    val availableVersions = tvDetailVersions()
    val selectedVersion =
        requestedMediaSourceId
            ?.let { id -> availableVersions.firstOrNull { version -> version.id == id } }
            ?: availableVersions.firstOrNull()
    val streams = selectedVersion?.mediaStreams.orEmpty()
    val audioOptions = audioOptions(streams)
    val resolvedAudio =
        explicitAudioStreamIndex?.takeIf { index -> audioOptions.any { option -> option.streamIndex == index } }
            ?: launchContext.playbackSelection?.audioStreamIndex?.takeIf { index ->
                audioOptions.any { option -> option.streamIndex == index }
            }
            ?: audioOptions.preferredAudioStreamIndex(launchContext.playbackPreferences.preferredAudioLanguage)
            ?: audioOptions.firstOrNull { option -> option.isDefault }?.streamIndex
            ?: audioOptions.firstOrNull()?.streamIndex
    val subtitles = subtitleOptions(streams)
    val subtitleSelection =
        resolveDetailSubtitleSelection(
            options = subtitles,
            launchContext = launchContext,
            explicitMode = explicitSubtitleMode,
            explicitStreamIndex = explicitSubtitleStreamIndex,
            validatedLocalAssetId = validatedLocalSubtitleAssetId,
        )
    val resume = resumeDecision(item.playbackPositionTicks, item.runtime, item.played)
    val rawPosition = item.playbackPositionTicks ?: 0L
    val playMode =
        when (resume) {
            ResumeDecision.Start -> TvDetailPlayMode.Play
            is ResumeDecision.Resume -> TvDetailPlayMode.Resume
            ResumeDecision.StartOver -> TvDetailPlayMode.Restart
        }
    val people = people.ifEmpty { item.people }

    return TvDetailProjection(
        content =
            TvItemDetailContent(
                id = item.id,
                title = item.name,
                kind = item.kind.toTvCardKind(),
                overview = overview ?: item.overview,
                tagline = taglines.firstOrNull { tagline -> tagline.isNotBlank() },
                productionYear = productionYear ?: item.productionYear,
                runtimeMinutes = item.runtime?.inWholeMinutes,
                officialRating = officialRating ?: item.officialRating,
                communityRating = communityRating ?: item.communityRating,
                criticRating = criticRating,
                genres = genres.ifEmpty { item.genres },
                studios = studios.map { studio -> studio.name }.filter(String::isNotBlank),
                backdropUrl =
                    item.imageRefs.backdropTag?.let { tag ->
                        imageUrlBuilder.build(
                            serverUrl = session.serverUrl,
                            itemId = item.id,
                            type = JellyfinImageType.Backdrop,
                            tag = tag,
                            maxWidth = BACKDROP_IMAGE_MAX_WIDTH,
                        )
                    },
                logoUrl =
                    item.imageRefs.logoTag?.let { tag ->
                        imageUrlBuilder.build(
                            serverUrl = session.serverUrl,
                            itemId = item.id,
                            type = JellyfinImageType.Logo,
                            tag = tag,
                            maxWidth = LOGO_IMAGE_MAX_WIDTH,
                        )
                    },
                posterUrl =
                    item.imageRefs.primaryTag?.let { tag ->
                        imageUrlBuilder.build(
                            serverUrl = session.serverUrl,
                            itemId = item.id,
                            type = JellyfinImageType.Primary,
                            tag = tag,
                            maxWidth = CARD_IMAGE_MAX_WIDTH,
                        )
                    },
                playedPercentage = item.playedPercentage,
                resumePositionTicks = rawPosition,
                playStartPositionTicks = if (resume is ResumeDecision.Resume) rawPosition else 0L,
                playMode = playMode,
                canRestart = resume is ResumeDecision.Resume,
                isPlayable =
                    selectedVersion != null &&
                        (item.kind == MediaKind.Movie || item.kind == MediaKind.Episode),
                seriesId = item.seriesId,
                seasonId = item.seasonId,
                seriesName = item.seriesName,
                episodeLabel = item.episodeLabel,
                played = item.played,
                isFavorite = item.isFavorite,
                badges = badges(selectedVersion),
            ),
        versions =
            availableVersions.mapIndexed { index, version ->
                TvDetailVersionChoice(
                    id = version.id,
                    label =
                        version.releaseBasename?.takeIf(
                            String::isNotBlank,
                        ) ?: version.name.takeIf(String::isNotBlank) ?: "${index + 1}",
                    selected = version.id == selectedVersion?.id,
                )
            },
        selectedMediaSourceId = selectedVersion?.id,
        audioTracks =
            audioOptions.map { option ->
                TvDetailTrackChoice(
                    streamIndex = option.streamIndex,
                    label = option.displayName?.takeIf(String::isNotBlank) ?: option.language ?: "${option.ordinal + 1}",
                    language = option.language,
                    selected = option.streamIndex == resolvedAudio,
                )
            },
        subtitleTracks =
            subtitles.map { option ->
                TvDetailTrackChoice(
                    streamIndex = option.streamIndex,
                    label = option.displayName?.takeIf(String::isNotBlank) ?: option.language ?: "${option.ordinal + 1}",
                    language = option.language,
                    selected = option.streamIndex == subtitleSelection.streamIndex,
                )
            },
        subtitleMode = subtitleSelection.mode,
        selectedSubtitleStreamIndex = subtitleSelection.streamIndex,
        selectedSubtitleAssetId = subtitleSelection.assetId,
        mediaInfo = selectedVersion?.mediaInfoRows().orEmpty(),
        cast =
            people
                .filter { person -> person.type == MediaPersonType.Actor }
                .toTvDetailPeople(session, imageUrlBuilder),
        crew =
            people
                .filter { person -> person.type != MediaPersonType.Actor }
                .toTvDetailPeople(session, imageUrlBuilder),
    )
}

internal fun TvItemDetailContent.withPlayedStatus(
    played: Boolean,
    item: MediaItem?,
): TvItemDetailContent {
    val sourceItem = item ?: return copy(played = played)
    val resume = resumeDecision(sourceItem.playbackPositionTicks, sourceItem.runtime, played)
    val rawPosition = sourceItem.playbackPositionTicks ?: 0L
    return copy(
        played = played,
        playedPercentage = sourceItem.playedPercentage,
        resumePositionTicks = rawPosition,
        playStartPositionTicks =
            if (resume is ResumeDecision.Resume) rawPosition else 0L,
        playMode =
            when (resume) {
                ResumeDecision.Start -> TvDetailPlayMode.Play
                is ResumeDecision.Resume -> TvDetailPlayMode.Resume
                ResumeDecision.StartOver -> TvDetailPlayMode.Restart
            },
        canRestart = resume is ResumeDecision.Resume,
    )
}

private data class DetailSubtitleSelection(
    val mode: TvPlaybackSubtitleMode,
    val streamIndex: Int?,
    val assetId: String?,
)

private fun resolveDetailSubtitleSelection(
    options: List<com.jellyscope.core.domain.playback.SubtitleTrackOption>,
    launchContext: PlaybackLaunchContext,
    explicitMode: TvPlaybackSubtitleMode?,
    explicitStreamIndex: Int?,
    validatedLocalAssetId: String?,
): DetailSubtitleSelection {
    if (validatedLocalAssetId != null) {
        return DetailSubtitleSelection(TvPlaybackSubtitleMode.LocalAsset, null, validatedLocalAssetId)
    }
    if (explicitMode == TvPlaybackSubtitleMode.Off) {
        return DetailSubtitleSelection(TvPlaybackSubtitleMode.Off, null, null)
    }
    if (explicitMode == TvPlaybackSubtitleMode.Track) {
        val valid = explicitSubtitleIndex(options, explicitStreamIndex)
        if (valid != null) return DetailSubtitleSelection(TvPlaybackSubtitleMode.Track, valid, null)
    }
    val stored = launchContext.subtitleSelection
    if (stored == SubtitleSelectionIntent.Off || stored is SubtitleSelectionIntent.LocalAsset) {
        return DetailSubtitleSelection(TvPlaybackSubtitleMode.Off, null, null)
    }
    val storedIndex = (stored as? SubtitleSelectionIntent.Track)?.streamIndex
    val selectedIndex =
        explicitSubtitleIndex(options, storedIndex)
            ?: options.preferredSubtitleStreamIndex(launchContext.playbackPreferences.preferredSubtitleLanguage)
            ?: options.defaultSubtitleStreamIndex()
    return if (selectedIndex == null) {
        DetailSubtitleSelection(TvPlaybackSubtitleMode.Off, null, null)
    } else {
        DetailSubtitleSelection(TvPlaybackSubtitleMode.Track, selectedIndex, null)
    }
}

private fun explicitSubtitleIndex(
    options: List<com.jellyscope.core.domain.playback.SubtitleTrackOption>,
    index: Int?,
): Int? = index?.takeIf { candidate -> options.any { option -> option.streamIndex == candidate } }

private fun MediaItemDetail.badges(version: MediaVersion?): TvItemBadges {
    val streams = version?.mediaStreams.orEmpty()
    val videoHeight =
        streams
            .filter { stream -> stream.type.equals("Video", ignoreCase = true) }
            .mapNotNull(PlaybackMediaStream::height)
            .maxOrNull()
    val audioLayout =
        streams
            .firstOrNull { stream -> stream.type.equals("Audio", ignoreCase = true) }
            ?.channelLayout
            ?.takeIf(String::isNotBlank)
    return TvItemBadges(
        resolution =
            when {
                videoHeight == null -> null
                videoHeight >= 2000 -> "4K"
                videoHeight >= 1000 -> "HD"
                else -> null
            },
        audioLayout = audioLayout,
        hasSubtitles = streams.any { stream -> stream.type.equals("Subtitle", ignoreCase = true) },
        officialRating = officialRating ?: item.officialRating,
        communityRating = communityRating ?: item.communityRating,
    )
}

private fun MediaPerson.toTvDetailPerson(
    session: Session,
    imageUrlBuilder: JellyfinImageUrlBuilder,
    credits: List<TvDetailPersonCredit>,
): TvDetailPerson =
    TvDetailPerson(
        id = id,
        name = name,
        credits = credits,
        imageUrl =
            primaryImageTag?.let { tag ->
                imageUrlBuilder.personPrimary(
                    serverUrl = session.serverUrl,
                    personId = id,
                    tag = tag,
                )
            },
    )

private fun List<MediaPerson>.toTvDetailPeople(
    session: Session,
    imageUrlBuilder: JellyfinImageUrlBuilder,
): List<TvDetailPerson> {
    val peopleById = linkedMapOf<String, MutableList<MediaPerson>>()
    forEach { person -> peopleById.getOrPut(person.id) { mutableListOf() }.add(person) }
    return peopleById.values.map { entries ->
        val seenRoles = mutableSetOf<String>()
        val seenTypes = mutableSetOf<MediaPersonType>()
        val credits =
            entries.mapNotNull { person ->
                val role = person.role?.trim()?.takeIf(String::isNotBlank)
                when {
                    role != null && seenRoles.add(role) -> TvDetailPersonCredit(role = role)
                    role == null && seenTypes.add(person.type) -> TvDetailPersonCredit(type = person.type)
                    else -> null
                }
            }
        entries.first().toTvDetailPerson(session, imageUrlBuilder, credits)
    }
}

private fun MediaVersion.mediaInfoRows(): List<TvDetailMediaInfoRow> =
    buildList {
        (releaseBasename ?: name).takeIf(String::isNotBlank)?.let { value ->
            add(TvDetailMediaInfoRow(TvDetailMediaInfoKind.Source, TvDetailMediaInfoField.Name, value))
        }
        container?.takeIf(String::isNotBlank)?.let { value ->
            add(TvDetailMediaInfoRow(TvDetailMediaInfoKind.Source, TvDetailMediaInfoField.Container, value.uppercase()))
        }
        runtime?.inWholeMinutes?.takeIf { minutes -> minutes > 0 }?.let { minutes ->
            add(TvDetailMediaInfoRow(TvDetailMediaInfoKind.Source, TvDetailMediaInfoField.Runtime, minutes.toString()))
        }
        sizeBytes?.takeIf { bytes -> bytes > 0L }?.let { bytes ->
            add(TvDetailMediaInfoRow(TvDetailMediaInfoKind.Source, TvDetailMediaInfoField.Size, formatSize(bytes)))
        }
        mediaStreams.forEachIndexed { ordinal, stream ->
            val kind = stream.tvInfoKind() ?: return@forEachIndexed
            val streamLabel = stream.displayTitle?.takeIf(String::isNotBlank) ?: "${ordinal + 1}"
            if (kind == TvDetailMediaInfoKind.Video) {
                val resolution =
                    when {
                        stream.width != null && stream.height != null -> "${stream.width} × ${stream.height}"
                        stream.height != null -> "${stream.height}p"
                        else -> null
                    }
                resolution?.let { value ->
                    add(TvDetailMediaInfoRow(kind, TvDetailMediaInfoField.Resolution, value, streamLabel))
                }
                stream.realFrameRate?.takeIf { rate -> rate > 0.0 }?.let { rate ->
                    add(TvDetailMediaInfoRow(kind, TvDetailMediaInfoField.FrameRate, decimal(rate), streamLabel))
                }
            }
            stream.codec?.takeIf(String::isNotBlank)?.let { value ->
                add(TvDetailMediaInfoRow(kind, TvDetailMediaInfoField.Codec, value.uppercase(), streamLabel))
            }
            stream.language?.takeIf(String::isNotBlank)?.let { value ->
                add(TvDetailMediaInfoRow(kind, TvDetailMediaInfoField.Language, value, streamLabel))
            }
            stream.channelLayout?.takeIf(String::isNotBlank)?.let { value ->
                add(TvDetailMediaInfoRow(kind, TvDetailMediaInfoField.Channels, value, streamLabel))
            }
            stream.bitRate?.takeIf { bitrate -> bitrate > 0L }?.let { bitrate ->
                add(TvDetailMediaInfoRow(kind, TvDetailMediaInfoField.Bitrate, decimal(bitrate / 1_000_000.0), streamLabel))
            }
        }
    }

private fun PlaybackMediaStream.tvInfoKind(): TvDetailMediaInfoKind? =
    when {
        type.equals("Video", ignoreCase = true) -> TvDetailMediaInfoKind.Video
        type.equals("Audio", ignoreCase = true) -> TvDetailMediaInfoKind.Audio
        type.equals("Subtitle", ignoreCase = true) -> TvDetailMediaInfoKind.Subtitle
        else -> null
    }

private fun formatSize(bytes: Long): String = decimal(bytes / 1_000_000_000.0)

private fun decimal(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
