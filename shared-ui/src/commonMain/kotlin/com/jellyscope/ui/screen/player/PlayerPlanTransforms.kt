// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.SubtitleRenderInfo
import com.jellyscope.core.domain.playback.isExternalSubtitle
import com.jellyscope.core.domain.playback.subtitleOptions
import com.jellyscope.core.domain.playback.subtitleRenderInfo

internal fun projectSubtitleRenderInfo(
    mediaStreams: List<PlaybackMediaStream>,
    plannedSubtitle: PlannedSubtitle,
    activationState: SubtitleActivationState,
    requestedLocalSubtitleAsset: LocalSubtitleAsset?,
): SubtitleRenderInfo {
    val info =
        subtitleRenderInfo(
            options = subtitleOptions(mediaStreams),
            plannedSubtitle = plannedSubtitle,
            activationState = activationState,
        )
    return if (requestedLocalSubtitleAsset != null && plannedSubtitle is PlannedSubtitle.LocalAsset) {
        info.copy(label = requestedLocalSubtitleAsset.label, language = requestedLocalSubtitleAsset.language)
    } else {
        info
    }
}

internal fun resolveSelectedSubtitleMediaStream(
    mediaStreams: List<PlaybackMediaStream>,
    requestedSubtitleStreamIndex: Int?,
): PlaybackMediaStream? {
    val streamIndex = requestedSubtitleStreamIndex ?: return null
    val option =
        subtitleOptions(mediaStreams)
            .firstOrNull { track -> track.streamIndex == streamIndex }
            ?: return null
    val stream =
        mediaStreams
            .filter { mediaStream -> mediaStream.type.equals("Subtitle", ignoreCase = true) }
            .getOrNull(option.ordinal)
            ?: return null
    return if (stream.index == option.streamIndex) {
        stream
    } else {
        stream.copy(index = option.streamIndex)
    }
}

internal fun audioActivationTarget(
    requestId: Long,
    itemId: String,
    streamIndex: Int,
): AudioActivationTarget =
    AudioActivationTarget(
        requestId = requestId,
        itemId = itemId,
        streamIndex = streamIndex,
    )

internal fun subtitleActivationTarget(
    requestId: Long,
    itemId: String,
    streamIndex: Int,
    kind: LocalSubtitleKind,
): SubtitleActivationTarget =
    SubtitleActivationTarget(
        requestId = requestId,
        itemId = itemId,
        identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex),
        kind = kind,
    )

internal fun PlaybackPlan.withAudioActivationTarget(target: AudioActivationTarget?): PlaybackPlan = copy(audioActivationTarget = target)

internal fun PlaybackPlan.withSubtitleActivationTarget(
    requestId: Long,
    itemId: String,
    selectedSubtitle: PlaybackMediaStream?,
): PlaybackPlan {
    val plannedTrack = plannedSubtitle as? PlannedSubtitle.Track
    val plannedLocalAsset = plannedSubtitle as? PlannedSubtitle.LocalAsset
    val unavailable = plannedSubtitle as? PlannedSubtitle.Unavailable
    val streamIndex = plannedTrack?.streamIndex ?: unavailable?.streamIndex
    val kind =
        plannedTrack?.localKind()
            ?: unavailable
                ?.takeIf { it.allowEncodeFallback }
                ?.let {
                    when {
                        selectedSubtitle?.isExternalSubtitle() == true -> LocalSubtitleKind.ExternalText
                        it.kind == SubtitleKind.Bitmap -> LocalSubtitleKind.EmbeddedBitmap
                        else -> LocalSubtitleKind.EmbeddedText
                    }
                }
    val target =
        if (plannedLocalAsset != null) {
            SubtitleActivationTarget(
                requestId = requestId,
                itemId = itemId,
                identity = SubtitleActivationIdentity.LocalAsset(plannedLocalAsset.assetId),
                kind = LocalSubtitleKind.ExternalText,
            )
        } else if (streamIndex != null && kind != null) {
            SubtitleActivationTarget(
                requestId = requestId,
                itemId = itemId,
                identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex),
                kind = kind,
            )
        } else {
            null
        }
    return copy(
        selectedSubtitleStreamIndex = streamIndex,
        subtitleAsset =
            plannedTrack?.externalResource
                ?: subtitleAsset.takeIf { plannedLocalAsset != null },
        subtitleActivationTarget = target,
        plannedSubtitle =
            when {
                plannedTrack != null -> plannedTrack.copy(activationTarget = target)
                plannedLocalAsset != null -> plannedLocalAsset.copy(activationTarget = target)
                unavailable != null -> unavailable.copy(activationTarget = target)
                else -> plannedSubtitle
            },
    )
}

internal fun PlannedSubtitle.Track.localKind(): LocalSubtitleKind? =
    when (deliveryMethod) {
        SubtitleDeliveryMethod.External -> LocalSubtitleKind.ExternalText
        SubtitleDeliveryMethod.Hls -> LocalSubtitleKind.HlsText
        SubtitleDeliveryMethod.Embed ->
            if (kind == SubtitleKind.Bitmap) LocalSubtitleKind.EmbeddedBitmap else LocalSubtitleKind.EmbeddedText
        else -> null
    }
