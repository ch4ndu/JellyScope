// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.OfflineArtifactRef

data class PlaybackMediaStream(
    val index: Int?,
    val type: String?,
    val displayTitle: String?,
    val title: String?,
    val language: String?,
    val codec: String?,
    val channelLayout: String?,
    val bitRate: Long?,
    val height: Int?,
    val isDefault: Boolean?,
    val isExternal: Boolean?,
    val deliveryMethod: String?,
    val deliveryUrl: String?,
    val width: Int? = null,
    val realFrameRate: Double? = null,
    val videoRangeType: String? = null,
    val bitDepth: Int? = null,
)

data class AudioTrackOption(
    val streamIndex: Int,
    val ordinal: Int,
    val displayName: String?,
    val language: String?,
    val isDefault: Boolean,
)

data class SubtitleTrackOption(
    val streamIndex: Int,
    val ordinal: Int,
    val displayName: String?,
    val language: String?,
    val isDefault: Boolean,
    val isExternal: Boolean,
)

enum class SubtitleRenderMode {
    None,
    LocalExternalText,
    LocalEmbeddedText,
    LocalEmbeddedBitmap,
    LocalHlsText,
    ServerBurnedIn,
    Unknown,
}

enum class SubtitleRenderStatus {
    Off,
    Pending,
    Active,
    Unavailable,
}

enum class LocalSubtitleKind {
    EmbeddedText,
    EmbeddedBitmap,
    ExternalText,
    HlsText,
}

sealed interface SubtitleActivationIdentity {
    data class JellyfinTrack(
        val streamIndex: Int,
    ) : SubtitleActivationIdentity

    data class LocalAsset(
        val assetId: String,
    ) : SubtitleActivationIdentity

    data class OfflineSidecar(
        val artifactRef: OfflineArtifactRef,
    ) : SubtitleActivationIdentity
}

data class SubtitleActivationTarget(
    val requestId: Long,
    val itemId: String,
    val identity: SubtitleActivationIdentity,
    val kind: LocalSubtitleKind,
) {
    val streamIndex: Int?
        get() = (identity as? SubtitleActivationIdentity.JellyfinTrack)?.streamIndex

    val assetId: String?
        get() = (identity as? SubtitleActivationIdentity.LocalAsset)?.assetId
}

sealed interface SubtitleActivationState {
    data object None : SubtitleActivationState

    data class Pending(
        val target: SubtitleActivationTarget,
    ) : SubtitleActivationState

    data class Active(
        val target: SubtitleActivationTarget,
    ) : SubtitleActivationState

    data class Unavailable(
        val target: SubtitleActivationTarget,
    ) : SubtitleActivationState
}

data class AudioActivationTarget(
    val requestId: Long,
    val itemId: String,
    val streamIndex: Int,
)

sealed interface AudioActivationState {
    data object None : AudioActivationState

    data class Pending(
        val target: AudioActivationTarget,
    ) : AudioActivationState

    data class Active(
        val target: AudioActivationTarget,
    ) : AudioActivationState

    data class Unavailable(
        val target: AudioActivationTarget,
    ) : AudioActivationState
}

data class EmbeddedAudioSelection(
    val target: AudioActivationTarget,
    val descriptor: PlannedEmbeddedTrack,
)

data class EmbeddedSubtitleSelection(
    val target: SubtitleActivationTarget,
    val descriptor: PlannedEmbeddedTrack,
)

data class SubtitleRenderInfo(
    val status: SubtitleRenderStatus,
    val mode: SubtitleRenderMode,
    val streamIndex: Int? = null,
    val label: String? = null,
    val language: String? = null,
    val reason: String,
) {
    val styleable: Boolean
        get() =
            status == SubtitleRenderStatus.Active &&
                (
                    mode == SubtitleRenderMode.LocalExternalText ||
                        mode == SubtitleRenderMode.LocalEmbeddedText
                )

    val activeStreamIndex: Int?
        get() = streamIndex.takeIf { status == SubtitleRenderStatus.Active }
}

data class QualityOption(
    val maxBitrateBps: Long?,
    val tier: QualityTier?,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val isCustom: Boolean = false,
    val inheritsDefault: Boolean = false,
    val mode: PlaybackQualityMode =
        if (maxBitrateBps == null) PlaybackQualityMode.Auto else PlaybackQualityMode.Fixed,
) {
    // There is deliberately no legacy string field here; UI derives its label
    // from the structured resolution when present.
    val displayResolution: String?
        get() = resolutionHeight?.let { height -> "${height}p" }
}

/** Player ladder with distinct Auto and Original rows; null is no longer the mode discriminator. */
fun playerQualityOptions(
    sourceBitrateBps: Long?,
    selectedPolicy: PlaybackQualityPolicy? = null,
): List<QualityOption> {
    val normalized = selectedPolicy?.normalized()
    val custom =
        normalized
            ?.maxBitrateBps
            ?.takeIf { bitrate -> qualityRungForBitrate(bitrate) == null }
            ?.let { bitrate -> QualityOption(bitrate, null, isCustom = true, mode = PlaybackQualityMode.Fixed) }
    val rungs = qualityOptions(sourceBitrateBps).drop(1)
    return buildList {
        add(QualityOption(null, null, inheritsDefault = true))
        add(QualityOption(null, null, mode = PlaybackQualityMode.Auto))
        add(QualityOption(null, null, mode = PlaybackQualityMode.Original))
        custom?.let(::add)
        addAll(rungs.map { option -> option.copy(mode = PlaybackQualityMode.Fixed) })
    }
}

/**
 * A quality choice used by Settings, where the complete canonical ladder must
 * remain visible regardless of the current media source. A custom choice is
 * intentionally bitrate-only and carries no inferred resolution.
 */
data class PlaybackQualityChoice(
    val maxBitrateBps: Long?,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val isCustom: Boolean = false,
    val mode: PlaybackQualityMode =
        if (maxBitrateBps == null) PlaybackQualityMode.Auto else PlaybackQualityMode.Fixed,
)

/** Typed settings/player projection, including explicit Original. */
fun playbackQualityChoices(selectedPolicy: PlaybackQualityPolicy? = null): List<PlaybackQualityChoice> {
    val selectedFixedBitrate = selectedPolicy?.normalized()?.maxBitrateBps
    val customChoice =
        selectedFixedBitrate
            ?.takeIf { bitrate -> qualityRungForBitrate(bitrate) == null }
            ?.let { bitrate -> PlaybackQualityChoice(maxBitrateBps = bitrate, isCustom = true) }
    return buildList {
        add(PlaybackQualityChoice(maxBitrateBps = null, mode = PlaybackQualityMode.Auto))
        add(PlaybackQualityChoice(maxBitrateBps = null, mode = PlaybackQualityMode.Original))
        customChoice?.let(::add)
        addAll(
            qualityRungs.map { rung ->
                PlaybackQualityChoice(
                    maxBitrateBps = rung.maxBitrateBps,
                    resolutionWidth = rung.width,
                    resolutionHeight = rung.height,
                    mode = PlaybackQualityMode.Fixed,
                )
            },
        )
    }
}

enum class QualityTier {
    High,
    Medium,
}

val externalSubtitleMimeTypesByExtension =
    mapOf(
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "srt" to "application/x-subrip",
        "subrip" to "application/x-subrip",
        "vtt" to "text/vtt",
        "webvtt" to "text/vtt",
        "ttml" to "application/ttml+xml",
    )

private val bitmapSubtitleFormats = setOf("pgs", "pgssub", "dvdsub", "vobsub", "dvb", "dvbsub", "xsub")

private val subtitleCodecFamilies =
    mapOf(
        "srt" to "srt",
        "subrip" to "srt",
        "application/x-subrip" to "srt",
        "vtt" to "vtt",
        "webvtt" to "vtt",
        "text/vtt" to "vtt",
        "text/webvtt" to "vtt",
        "ass" to "ass",
        "ssa" to "ass",
        "text/x-ssa" to "ass",
        "application/x-ass" to "ass",
        "ttml" to "ttml",
        "application/ttml+xml" to "ttml",
        "pgs" to "pgs",
        "pgssub" to "pgs",
        "application/pgs" to "pgs",
        "hdmv_pgs_subtitle" to "pgs",
        "vobsub" to "vobsub",
        "dvdsub" to "vobsub",
        "application/vobsub" to "vobsub",
        "dvd_subtitle" to "vobsub",
        "dvbsub" to "dvbsub",
        "dvb" to "dvbsub",
        "application/dvbsubs" to "dvbsub",
        "dvb_subtitle" to "dvbsub",
        "xsub" to "xsub",
    )

private val audioCodecFamilies =
    mapOf(
        "eac3" to "eac3",
        "eac-3" to "eac3",
        "eac3-joc" to "eac3",
        "ec-3" to "eac3",
        "ec+3" to "eac3",
        "ac3" to "ac3",
        "ac-3" to "ac3",
        "dts" to "dts",
        "dca" to "dts",
        "vnd.dts" to "dts",
        "dtshd" to "dtshd",
        "dts-hd" to "dtshd",
        "vnd.dts.hd" to "dtshd",
        "truehd" to "truehd",
        "true-hd" to "truehd",
        "aac" to "aac",
        "mp4a-latm" to "aac",
        "mpeg" to "mp3",
        "mp3" to "mp3",
    )

fun audioOptions(streams: List<PlaybackMediaStream>): List<AudioTrackOption> =
    streams
        .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
        .mapIndexed { ordinal, stream ->
            AudioTrackOption(
                streamIndex = stream.index ?: ordinal,
                ordinal = ordinal,
                displayName = stream.readableLabel(),
                language = stream.language?.takeIf { language -> language.isNotBlank() },
                isDefault = stream.isDefault == true,
            )
        }

fun embeddedAudioTracks(streams: List<PlaybackMediaStream>): List<PlannedEmbeddedTrack> =
    buildEmbeddedAudioTracks(
        streams = streams,
        capabilities = null,
        responseAuthoritative = true,
    )

/**
 * Same descriptors, with each one stamped with whether [capabilities] can decode
 * it as a DirectPlay audio switch. The list length and ordinals are identical to
 * the single-argument overload — consumers rely on both.
 */
fun embeddedAudioTracks(
    streams: List<PlaybackMediaStream>,
    capabilities: DeviceDecodingCapabilities?,
): List<PlannedEmbeddedTrack> =
    buildEmbeddedAudioTracks(
        streams = streams,
        capabilities = capabilities,
        responseAuthoritative = true,
    )

internal fun detailEmbeddedAudioTracks(
    streams: List<PlaybackMediaStream>,
    capabilities: DeviceDecodingCapabilities?,
): List<PlannedEmbeddedTrack> =
    buildEmbeddedAudioTracks(
        streams = streams,
        capabilities = capabilities,
        responseAuthoritative = false,
    )

private fun buildEmbeddedAudioTracks(
    streams: List<PlaybackMediaStream>,
    capabilities: DeviceDecodingCapabilities?,
    responseAuthoritative: Boolean,
): List<PlannedEmbeddedTrack> =
    streams
        .filter { stream ->
            stream.type.equals("Audio", ignoreCase = true) && stream.isExternal != true
        }.let { embeddedStreams ->
            embeddedStreams.mapIndexedNotNull { ordinal, stream ->
                stream.index?.let { streamIndex ->
                    stream.toPlannedEmbeddedTrack(
                        streamIndex = streamIndex,
                        filteredContainerOrdinal = ordinal,
                        responseAuthoritativeCohortSize =
                            embeddedStreams.size.takeIf { responseAuthoritative },
                        trackKind = EmbeddedTrackKind.Audio,
                    )
                }
            }
        }.map { track ->
            track.copy(
                directPlayAdmissible = capabilities?.admitsDirectPlayAudioCodec(track.codec) ?: true,
            )
        }

fun responseEmbeddedSubtitleTracks(streams: List<PlaybackMediaStream>): List<PlannedEmbeddedTrack> {
    val responseApprovedStreams =
        streams.filter { stream ->
            stream.type.equals("Subtitle", ignoreCase = true) &&
                (
                    stream.deliveryMethod.equals("Embed", ignoreCase = true) ||
                        stream.deliveryMethod.equals("Hls", ignoreCase = true)
                )
        }
    return responseApprovedStreams.mapIndexedNotNull { ordinal, stream ->
        stream.index?.let { streamIndex ->
            stream.toPlannedEmbeddedTrack(
                streamIndex = streamIndex,
                filteredContainerOrdinal = ordinal,
                responseAuthoritativeCohortSize = responseApprovedStreams.size,
                trackKind = EmbeddedTrackKind.Subtitle,
            )
        }
    }
}

internal fun PlaybackMediaStream.toPlannedEmbeddedTrack(
    streamIndex: Int,
    filteredContainerOrdinal: Int,
    responseAuthoritativeCohortSize: Int? = null,
    trackKind: EmbeddedTrackKind,
): PlannedEmbeddedTrack =
    PlannedEmbeddedTrack(
        jellyfinStreamIndex = streamIndex,
        filteredContainerOrdinal = filteredContainerOrdinal,
        responseAuthoritativeCohortSize = responseAuthoritativeCohortSize,
        codec =
            when (trackKind) {
                EmbeddedTrackKind.Audio -> codec.normalizedTrackMetadata()
                EmbeddedTrackKind.Subtitle -> normalizeSubtitleFormat(codec)
            },
        normalizedLanguage = normalizeTrackLanguage(language),
        // Comparable identity must be the raw source Title for BOTH kinds:
        // native candidates carry the container track title, while Jellyfin's
        // DisplayTitle is a server-synthesized string ("British - English -
        // Dolby Digital+ - 5.1 - Default") that can never equal it — comparing
        // the two forced TitleConflict and a needless transcode on any audio
        // track whose container carries a title. Readable display labels stay
        // UI-only (AudioTrackOption.displayName).
        label = title.normalizedTrackMetadata(),
    )

fun subtitleOptions(streams: List<PlaybackMediaStream>): List<SubtitleTrackOption> =
    streams
        .filter { stream -> stream.type.equals("Subtitle", ignoreCase = true) }
        .mapIndexed { ordinal, stream ->
            val isExternal = stream.isExternalSubtitle()
            SubtitleTrackOption(
                streamIndex = stream.index ?: ordinal,
                ordinal = ordinal,
                displayName = stream.readableLabel(),
                language = stream.language?.takeIf { language -> language.isNotBlank() },
                isDefault = stream.isDefault == true,
                isExternal = isExternal,
            )
        }

fun List<SubtitleTrackOption>.hasSelectableSubtitleChoice(): Boolean = isNotEmpty()

fun subtitleRenderInfo(
    options: List<SubtitleTrackOption>,
    plannedSubtitle: PlannedSubtitle,
    activationState: SubtitleActivationState,
): SubtitleRenderInfo {
    if (plannedSubtitle is PlannedSubtitle.Off) {
        return SubtitleRenderInfo(
            status = SubtitleRenderStatus.Off,
            mode = SubtitleRenderMode.None,
            reason = "Off",
        )
    }

    if (plannedSubtitle is PlannedSubtitle.Unavailable) {
        val option = options.firstOrNull { it.streamIndex == plannedSubtitle.streamIndex }
        return SubtitleRenderInfo(
            status = SubtitleRenderStatus.Unavailable,
            mode = SubtitleRenderMode.Unknown,
            streamIndex = plannedSubtitle.streamIndex,
            label = option?.displayName,
            language = option?.language,
            reason = plannedSubtitle.reason,
        )
    }

    if (plannedSubtitle is PlannedSubtitle.LocalAsset) {
        val target = plannedSubtitle.activationTarget
        val active = target != null && (activationState as? SubtitleActivationState.Active)?.target == target
        val unavailable = target != null && (activationState as? SubtitleActivationState.Unavailable)?.target == target
        return SubtitleRenderInfo(
            status =
                when {
                    active -> SubtitleRenderStatus.Active
                    unavailable -> SubtitleRenderStatus.Unavailable
                    else -> SubtitleRenderStatus.Pending
                },
            mode = SubtitleRenderMode.LocalExternalText,
            reason =
                if (active) {
                    "Local subtitle active"
                } else if (unavailable) {
                    "Local subtitle unavailable"
                } else {
                    "Local subtitle pending"
                },
        )
    }

    if (plannedSubtitle is PlannedSubtitle.OfflineSidecar) {
        val target = plannedSubtitle.activationTarget
        val active = (activationState as? SubtitleActivationState.Active)?.target == target
        val unavailable = (activationState as? SubtitleActivationState.Unavailable)?.target == target
        return SubtitleRenderInfo(
            status =
                when {
                    active -> SubtitleRenderStatus.Active
                    unavailable -> SubtitleRenderStatus.Unavailable
                    else -> SubtitleRenderStatus.Pending
                },
            mode = SubtitleRenderMode.LocalExternalText,
            label = plannedSubtitle.label,
            language = plannedSubtitle.language,
            reason =
                when {
                    active -> "Offline subtitle active"
                    unavailable -> "Offline subtitle unavailable"
                    else -> "Offline subtitle pending"
                },
        )
    }

    plannedSubtitle as PlannedSubtitle.Track
    val requestedStreamIndex = plannedSubtitle.streamIndex

    val option =
        options.firstOrNull { candidate -> candidate.streamIndex == requestedStreamIndex }
            ?: return SubtitleRenderInfo(
                status = SubtitleRenderStatus.Unavailable,
                mode = SubtitleRenderMode.Unknown,
                streamIndex = requestedStreamIndex,
                reason = "Selected stream missing",
            )

    return when (plannedSubtitle.deliveryMethod) {
        SubtitleDeliveryMethod.Encode ->
            option.toSubtitleRenderInfo(
                status = SubtitleRenderStatus.Active,
                mode = SubtitleRenderMode.ServerBurnedIn,
                reason = "Server rendered",
            )
        SubtitleDeliveryMethod.Drop, SubtitleDeliveryMethod.Unavailable ->
            option.toSubtitleRenderInfo(
                status = SubtitleRenderStatus.Unavailable,
                mode = SubtitleRenderMode.Unknown,
                reason = "Subtitle delivery unavailable",
            )
        SubtitleDeliveryMethod.Embed,
        SubtitleDeliveryMethod.External,
        SubtitleDeliveryMethod.Hls,
        -> option.localSubtitleRenderInfo(plannedSubtitle, activationState)
        SubtitleDeliveryMethod.Off ->
            SubtitleRenderInfo(
                status = SubtitleRenderStatus.Off,
                mode = SubtitleRenderMode.None,
                reason = "Off",
            )
    }
}

fun qualityOptions(sourceBitrateBps: Long?): List<QualityOption> {
    val rungs =
        qualityRungs
            .filter { rung -> sourceBitrateBps == null || rung.maxBitrateBps < sourceBitrateBps }
            .ifEmpty { listOf(qualityRungs.last()) }

    return listOf(
        QualityOption(
            maxBitrateBps = null,
            tier = null,
        ),
    ) +
        rungs.map { rung ->
            QualityOption(
                maxBitrateBps = rung.maxBitrateBps,
                tier = rung.tier,
                resolutionWidth = rung.width,
                resolutionHeight = rung.height,
            )
        }
}

/**
 * Returns the unfiltered Settings ladder and preserves an exact stored value
 * that is not one of the canonical rungs as a selected bitrate-only choice.
 */
fun settingsQualityChoices(selectedMaxBitrateBps: Long?): List<PlaybackQualityChoice> {
    val customChoice =
        selectedMaxBitrateBps
            ?.takeIf { bitrate -> bitrate > 0L }
            ?.takeIf { bitrate -> qualityRungForBitrate(bitrate) == null }
            ?.let { bitrate -> PlaybackQualityChoice(maxBitrateBps = bitrate, isCustom = true) }

    return buildList {
        add(PlaybackQualityChoice(maxBitrateBps = null))
        customChoice?.let(::add)
        addAll(
            qualityRungs.map { rung ->
                PlaybackQualityChoice(
                    maxBitrateBps = rung.maxBitrateBps,
                    resolutionWidth = rung.width,
                    resolutionHeight = rung.height,
                )
            },
        )
    }
}

/** Compatibility projection for existing shared settings consumers. */
fun qualitySettingsOptions(selectedBitrateBps: Long?): List<QualityOption> =
    settingsQualityChoices(selectedBitrateBps).map { choice ->
        QualityOption(
            maxBitrateBps = choice.maxBitrateBps,
            tier = qualityRungForBitrate(choice.maxBitrateBps)?.tier,
            resolutionWidth = choice.resolutionWidth,
            resolutionHeight = choice.resolutionHeight,
            isCustom = choice.isCustom,
        )
    }

fun List<AudioTrackOption>.preferredAudioStreamIndex(preferredLanguage: String?): Int? =
    firstOrNull { option ->
        matchesPreferredLanguage(
            language = option.language,
            displayName = option.displayName,
            preferredLanguage = preferredLanguage,
        )
    }?.streamIndex

fun List<SubtitleTrackOption>.preferredSubtitleStreamIndex(preferredLanguage: String?): Int? =
    firstOrNull { option ->
        matchesPreferredLanguage(
            language = option.language,
            displayName = option.displayName,
            preferredLanguage = preferredLanguage,
        )
    }?.streamIndex

fun List<SubtitleTrackOption>.defaultSubtitleStreamIndex(): Int? = firstOrNull { option -> option.isDefault }?.streamIndex

fun estimatedGbPerHour(bitrateBps: Long): Double = bitrateBps.toDouble() * SECONDS_PER_HOUR / BITS_PER_BYTE / BYTES_PER_GB

fun externalSubtitleMimeType(stream: PlaybackMediaStream): String? {
    val codec = stream.codec?.lowercase()?.takeIf { value -> value.isNotBlank() }
    val extension =
        stream.deliveryUrl
            ?.substringBefore('?')
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
    return listOfNotNull(codec, extension)
        .firstNotNullOfOrNull { key -> externalSubtitleMimeTypesByExtension[key] }
}

fun resolveExternalSubtitleUrl(
    serverUrl: String,
    deliveryUrl: String,
): String = resolveServerRelativeUrl(serverUrl = serverUrl, url = deliveryUrl)

fun PlaybackMediaStream.toExternalSubtitle(serverUrl: String): SubtitleAsset.JellyfinRemote? {
    val deliveryUrl = deliveryUrl?.takeIf { url -> url.isNotBlank() } ?: return null
    val mimeType = externalSubtitleMimeType(this) ?: return null
    return SubtitleAsset.JellyfinRemote(
        url = resolveExternalSubtitleUrl(serverUrl = serverUrl, deliveryUrl = deliveryUrl),
        mimeType = mimeType,
        label = readableLabel() ?: "Subtitle",
        language = language?.takeIf { value -> value.isNotBlank() },
    )
}

fun PlaybackMediaStream.isExternalSubtitle(): Boolean = isExternal == true || deliveryMethod.equals("External", ignoreCase = true)

fun PlaybackMediaStream.normalizedSubtitleFormat(): String? = normalizeSubtitleFormat(codec)

fun normalizeSubtitleFormat(value: String?): String? =
    value.normalizedTrackMetadata()?.let { format -> subtitleCodecFamilies[format] ?: format }

fun normalizeAudioCodecFamily(value: String?): String? =
    value
        .normalizedTrackMetadata()
        ?.removePrefix("audio/")
        ?.takeIf { codec -> codec.isNotBlank() }
        ?.let { codec ->
            when {
                codec.startsWith("mp4a.40.") -> "aac"
                else -> audioCodecFamilies[codec] ?: codec
            }
        }

internal fun knownSubtitleFormat(value: String?): String? = value.normalizedTrackMetadata()?.let(subtitleCodecFamilies::get)

fun subtitleFormatsEquivalent(
    first: String?,
    second: String?,
): Boolean {
    val canonicalFirst = normalizeSubtitleFormat(first) ?: return false
    return canonicalFirst == normalizeSubtitleFormat(second)
}

fun subtitleKind(format: String?): SubtitleKind =
    if (normalizeSubtitleFormat(format) in bitmapSubtitleFormats) SubtitleKind.Bitmap else SubtitleKind.Text

fun PlaybackMediaStream.readableLabel(): String? =
    displayTitle?.takeIf { value -> value.isNotBlank() }
        ?: title?.takeIf { value -> value.isNotBlank() }
        ?: language?.takeIf { value -> value.isNotBlank() }
        ?: codec?.takeIf { value -> value.isNotBlank() }

private fun SubtitleTrackOption.toSubtitleRenderInfo(
    status: SubtitleRenderStatus,
    mode: SubtitleRenderMode,
    reason: String,
): SubtitleRenderInfo =
    SubtitleRenderInfo(
        status = status,
        mode = mode,
        streamIndex = streamIndex,
        label = displayName,
        language = language,
        reason = reason,
    )

private fun SubtitleTrackOption.localSubtitleRenderInfo(
    plannedSubtitle: PlannedSubtitle.Track,
    activationState: SubtitleActivationState,
): SubtitleRenderInfo {
    val expectedKind = plannedSubtitle.activationTarget?.kind
    val localMode =
        when (expectedKind) {
            LocalSubtitleKind.ExternalText -> SubtitleRenderMode.LocalExternalText
            LocalSubtitleKind.EmbeddedBitmap -> SubtitleRenderMode.LocalEmbeddedBitmap
            LocalSubtitleKind.HlsText -> SubtitleRenderMode.LocalHlsText
            LocalSubtitleKind.EmbeddedText -> SubtitleRenderMode.LocalEmbeddedText
            null -> SubtitleRenderMode.Unknown
        }
    val activationTarget = plannedSubtitle.activationTarget
    if (activationTarget == null) {
        return toSubtitleRenderInfo(
            status = SubtitleRenderStatus.Unavailable,
            mode = SubtitleRenderMode.Unknown,
            reason = "No local subtitle activation target",
        )
    }
    val expectedTarget =
        activationTarget.takeIf { target ->
            target.streamIndex == streamIndex
        }

    return when (activationState) {
        is SubtitleActivationState.Active ->
            if (activationState.target == expectedTarget) {
                toSubtitleRenderInfo(
                    status = SubtitleRenderStatus.Active,
                    mode = localMode,
                    reason = "${plannedSubtitle.deliveryMethod.name} active",
                )
            } else {
                pendingSubtitleRenderInfo(localMode)
            }
        is SubtitleActivationState.Unavailable ->
            if (activationState.target == expectedTarget) {
                toSubtitleRenderInfo(
                    status = SubtitleRenderStatus.Unavailable,
                    mode = SubtitleRenderMode.Unknown,
                    reason = "${plannedSubtitle.deliveryMethod.name} subtitle unavailable",
                )
            } else {
                pendingSubtitleRenderInfo(localMode)
            }
        is SubtitleActivationState.Pending,
        SubtitleActivationState.None,
        -> pendingSubtitleRenderInfo(localMode)
    }
}

private fun SubtitleTrackOption.pendingSubtitleRenderInfo(localMode: SubtitleRenderMode): SubtitleRenderInfo =
    toSubtitleRenderInfo(
        status = SubtitleRenderStatus.Pending,
        mode = localMode,
        reason = "Awaiting platform activation",
    )

fun Long.mbpsLabel(): String {
    val mbps = this.toDouble() / BITS_PER_MEGABIT
    val rounded = (mbps * 10).toLong() / 10.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

private fun matchesPreferredLanguage(
    language: String?,
    displayName: String?,
    preferredLanguage: String?,
): Boolean {
    val rawPreferred = preferredLanguage?.trim()?.takeIf { value -> value.isNotBlank() } ?: return false
    val preferred = rawPreferred.normalizedLanguage() ?: return false
    val candidate = language.normalizedLanguage()
    // Canonicalize common ISO-639-1 / display names to their 639-2 code so a
    // preference like "es" or "spanish" matches a track tagged "spa".
    val preferredCanonical = LANGUAGE_ALIASES[preferred] ?: preferred
    val candidateCanonical = candidate?.let { code -> LANGUAGE_ALIASES[code] ?: code }
    return candidateCanonical == preferredCanonical ||
        candidate?.take(LANGUAGE_PREFIX_LENGTH) == preferred.take(LANGUAGE_PREFIX_LENGTH) ||
        displayName?.contains(rawPreferred, ignoreCase = true) == true
}

// Complete ISO 639-1 (2-letter) -> ISO 639-2/T (3-letter) table. Jellyfin
// tags streams with 639-2 codes while Media3 normalizes container tags to
// BCP-47 2-letter codes, so native-track identity comparison must canonicalize
// both sides or every non-covered language becomes a false LanguageConflict
// (e.g. Telugu: server "tel" vs Media3 "te" forced a needless transcode).
private val ISO_639_1_TO_2 =
    mapOf(
        "aa" to "aar",
        "ab" to "abk",
        "ae" to "ave",
        "af" to "afr",
        "ak" to "aka",
        "am" to "amh",
        "an" to "arg",
        "ar" to "ara",
        "as" to "asm",
        "av" to "ava",
        "ay" to "aym",
        "az" to "aze",
        "ba" to "bak",
        "be" to "bel",
        "bg" to "bul",
        "bh" to "bih",
        "bi" to "bis",
        "bm" to "bam",
        "bn" to "ben",
        "bo" to "bod",
        "br" to "bre",
        "bs" to "bos",
        "ca" to "cat",
        "ce" to "che",
        "ch" to "cha",
        "co" to "cos",
        "cr" to "cre",
        "cs" to "ces",
        "cu" to "chu",
        "cv" to "chv",
        "cy" to "cym",
        "da" to "dan",
        "de" to "deu",
        "dv" to "div",
        "dz" to "dzo",
        "ee" to "ewe",
        "el" to "ell",
        "en" to "eng",
        "eo" to "epo",
        "es" to "spa",
        "et" to "est",
        "eu" to "eus",
        "fa" to "fas",
        "ff" to "ful",
        "fi" to "fin",
        "fj" to "fij",
        "fo" to "fao",
        "fr" to "fra",
        "fy" to "fry",
        "ga" to "gle",
        "gd" to "gla",
        "gl" to "glg",
        "gn" to "grn",
        "gu" to "guj",
        "gv" to "glv",
        "ha" to "hau",
        "he" to "heb",
        "hi" to "hin",
        "ho" to "hmo",
        "hr" to "hrv",
        "ht" to "hat",
        "hu" to "hun",
        "hy" to "hye",
        "hz" to "her",
        "ia" to "ina",
        "id" to "ind",
        "ie" to "ile",
        "ig" to "ibo",
        "ii" to "iii",
        "ik" to "ipk",
        "io" to "ido",
        "is" to "isl",
        "it" to "ita",
        "iu" to "iku",
        "ja" to "jpn",
        "jv" to "jav",
        "ka" to "kat",
        "kg" to "kon",
        "ki" to "kik",
        "kj" to "kua",
        "kk" to "kaz",
        "kl" to "kal",
        "km" to "khm",
        "kn" to "kan",
        "ko" to "kor",
        "kr" to "kau",
        "ks" to "kas",
        "ku" to "kur",
        "kv" to "kom",
        "kw" to "cor",
        "ky" to "kir",
        "la" to "lat",
        "lb" to "ltz",
        "lg" to "lug",
        "li" to "lim",
        "ln" to "lin",
        "lo" to "lao",
        "lt" to "lit",
        "lu" to "lub",
        "lv" to "lav",
        "mg" to "mlg",
        "mh" to "mah",
        "mi" to "mri",
        "mk" to "mkd",
        "ml" to "mal",
        "mn" to "mon",
        "mr" to "mar",
        "ms" to "msa",
        "mt" to "mlt",
        "my" to "mya",
        "na" to "nau",
        "nb" to "nob",
        "nd" to "nde",
        "ne" to "nep",
        "ng" to "ndo",
        "nl" to "nld",
        "nn" to "nno",
        "no" to "nor",
        "nr" to "nbl",
        "nv" to "nav",
        "ny" to "nya",
        "oc" to "oci",
        "oj" to "oji",
        "om" to "orm",
        "or" to "ori",
        "os" to "oss",
        "pa" to "pan",
        "pi" to "pli",
        "pl" to "pol",
        "ps" to "pus",
        "pt" to "por",
        "qu" to "que",
        "rm" to "roh",
        "rn" to "run",
        "ro" to "ron",
        "ru" to "rus",
        "rw" to "kin",
        "sa" to "san",
        "sc" to "srd",
        "sd" to "snd",
        "se" to "sme",
        "sg" to "sag",
        "si" to "sin",
        "sk" to "slk",
        "sl" to "slv",
        "sm" to "smo",
        "sn" to "sna",
        "so" to "som",
        "sq" to "sqi",
        "sr" to "srp",
        "ss" to "ssw",
        "st" to "sot",
        "su" to "sun",
        "sv" to "swe",
        "sw" to "swa",
        "ta" to "tam",
        "te" to "tel",
        "tg" to "tgk",
        "th" to "tha",
        "ti" to "tir",
        "tk" to "tuk",
        "tl" to "tgl",
        "tn" to "tsn",
        "to" to "ton",
        "tr" to "tur",
        "ts" to "tso",
        "tt" to "tat",
        "tw" to "twi",
        "ty" to "tah",
        "ug" to "uig",
        "uk" to "ukr",
        "ur" to "urd",
        "uz" to "uzb",
        "ve" to "ven",
        "vi" to "vie",
        "vo" to "vol",
        "wa" to "wln",
        "wo" to "wol",
        "xh" to "xho",
        "yi" to "yid",
        "yo" to "yor",
        "za" to "zha",
        "zh" to "zho",
        "zu" to "zul",
    )

// ISO 639-2 bibliographic (B) codes mapped to their terminological (T)
// counterparts — both appear in the wild depending on the tagging tool.
private val ISO_639_2_B_TO_T =
    mapOf(
        "alb" to "sqi",
        "arm" to "hye",
        "baq" to "eus",
        "bur" to "mya",
        "chi" to "zho",
        "cze" to "ces",
        "dut" to "nld",
        "fre" to "fra",
        "geo" to "kat",
        "ger" to "deu",
        "gre" to "ell",
        "ice" to "isl",
        "mac" to "mkd",
        "mao" to "mri",
        "may" to "msa",
        "per" to "fas",
        "rum" to "ron",
        "slo" to "slk",
        "tib" to "bod",
        "wel" to "cym",
    )

// English display names for user-facing language preferences (settings store
// names, not codes) plus the code tables above; all resolve to 639-2/T.
private val LANGUAGE_ALIASES =
    ISO_639_1_TO_2 + ISO_639_2_B_TO_T +
        mapOf(
            "english" to "eng",
            "spanish" to "spa",
            "french" to "fra",
            "german" to "deu",
            "italian" to "ita",
            "portuguese" to "por",
            "russian" to "rus",
            "japanese" to "jpn",
            "korean" to "kor",
            "chinese" to "zho",
            "hindi" to "hin",
            "arabic" to "ara",
            "telugu" to "tel",
            "tamil" to "tam",
        )

fun normalizeTrackLanguage(value: String?): String? =
    value
        .normalizedLanguage()
        ?.let { language -> LANGUAGE_ALIASES[language] ?: language }
        ?.takeUnless { language -> language in UNKNOWN_LANGUAGE_CODES }

private val UNKNOWN_LANGUAGE_CODES = setOf("und", "unknown", "undetermined", "mul", "zxx")

internal fun String?.normalizedTrackMetadata(): String? =
    this
        ?.trim()
        ?.lowercase()
        ?.takeIf { value -> value.isNotBlank() }

private fun String?.normalizedLanguage(): String? =
    this
        ?.trim()
        ?.lowercase()
        ?.replace('_', '-')
        ?.substringBefore('-')
        ?.takeIf { value -> value.isNotBlank() }

// Jellyfin exposes no named quality profiles; the transcoder honors
// MaxStreamingBitrate and its encode ladder resolves each bitrate to a
// matching resolution. These tier names follow the official clients'
// convention of pairing the conventional resolution with each rung.
data class QualityRung(
    val maxBitrateBps: Long,
    val height: Int,
    val tier: QualityTier? = null,
) {
    val width: Int
        get() = ((height * 16 + 8) / 9).let { width -> if (width % 2 == 0) width else width + 1 }

    val resolutionCap: VideoCodecResolution
        get() = VideoCodecResolution(maxWidth = width, maxHeight = height)
}

val qualityRungs =
    listOf(
        QualityRung(80_000_000L, 2_160, QualityTier.High),
        QualityRung(40_000_000L, 2_160, QualityTier.Medium),
        QualityRung(20_000_000L, 1_080, QualityTier.High),
        QualityRung(12_000_000L, 1_080, QualityTier.Medium),
        QualityRung(8_000_000L, 1_080),
        QualityRung(4_000_000L, 720, QualityTier.High),
        QualityRung(2_000_000L, 720),
        QualityRung(1_500_000L, 480),
    )

fun qualityRungForBitrate(maxBitrateBps: Long?): QualityRung? =
    maxBitrateBps?.let { bitrate -> qualityRungs.firstOrNull { rung -> rung.maxBitrateBps == bitrate } }

fun formatBitrateMbps(bitrateBps: Long): String {
    val wholeMbps = bitrateBps / BITS_PER_MEGABIT_LONG
    val tenths = (bitrateBps % BITS_PER_MEGABIT_LONG) / 100_000L
    return if (tenths == 0L) "$wholeMbps" else "$wholeMbps.$tenths"
}

private const val SECONDS_PER_HOUR = 3_600
private const val BITS_PER_BYTE = 8
private const val BYTES_PER_GB = 1_000_000_000.0
private const val BITS_PER_MEGABIT = 1_000_000.0
private const val BITS_PER_MEGABIT_LONG = 1_000_000L
private const val LANGUAGE_PREFIX_LENGTH = 2
