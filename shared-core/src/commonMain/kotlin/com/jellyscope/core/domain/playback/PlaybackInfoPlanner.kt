// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent.Fallback
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent.Resolved
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.toDiagnosticsSourceDescriptor
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger

class PlaybackInfoPlanner(
    private val mediaRepository: MediaRepository,
    private val directPlayPlanner: DirectPlayPlanner,
    private val deviceProfileProvider: DeviceProfileProvider? = null,
    private val playbackDiagnosticsContext: PlaybackDiagnosticsContext? = null,
    private val playerDeviceSettingsStore: PlayerDeviceSettingsStore? = null,
) {
    suspend fun plan(
        session: Session,
        itemId: String,
        mediaSourceId: String,
        startPositionTicks: Long,
        audioStreamIndex: Int? = null,
        detailMediaStreams: List<PlaybackMediaStream> = emptyList(),
        subtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
        localSubtitleAsset: SubtitleAsset.LocalFile? = null,
        maxStreamingBitrate: Long? = null,
        qualityPolicy: PlaybackQualityPolicy? = null,
        qualityCapOrigin: PlaybackQualityCapOrigin? = null,
        requestPolicy: PlaybackInfoRequestPolicy = PlaybackInfoRequestPolicy(),
        sourceContainer: String? = null,
    ): PlaybackPlan {
        val storedPlayerDeviceSettings = playerDeviceSettingsStore?.settings?.value
        val snapshottedUserResolutionCap =
            if (storedPlayerDeviceSettings != null) {
                storedPlayerDeviceSettings.maxVideoResolution.resolutionCap
            } else {
                requestPolicy.userVideoResolutionCap
            }
        val effectiveQualityPolicy =
            (
                qualityPolicy
                    ?: maxStreamingBitrate?.let { bitrate -> PlaybackQualityPolicy.fixed(bitrate) }
                    ?: PlaybackQualityPolicy.Auto
            ).normalized()
        val policyBitrateConstraint =
            requestPolicy.bitrateConstraint.takeUnless { constraint ->
                constraint == PlaybackBitrateConstraint.NoClientLimit
            } ?: effectiveQualityPolicy.toBitrateConstraint()
        val effectiveMaxStreamingBitrate =
            when (policyBitrateConstraint) {
                is PlaybackBitrateConstraint.ExactUserLimit,
                is PlaybackBitrateConstraint.AutoSessionLimit,
                -> policyBitrateConstraint.bitrateBps

                PlaybackBitrateConstraint.NoClientLimit,
                -> effectiveQualityPolicy.maxBitrateBps
            }
        val planningRequestPolicy =
            requestPolicy.copy(
                bitrateConstraint = policyBitrateConstraint,
                userVideoResolutionCap = snapshottedUserResolutionCap,
                userVideoResolutionCapIsResolved = storedPlayerDeviceSettings != null || requestPolicy.userVideoResolutionCap != null,
            )
        val deviceCapabilities =
            deviceProfileProvider
                ?.capabilities(planningRequestPolicy.backend)
                ?.let { capabilities ->
                    resolvePlayerDevicePolicy(
                        capabilities = capabilities,
                        settings = storedPlayerDeviceSettings ?: PlayerDeviceSettings(),
                    ).capabilities
                }
        // Capability facts are still sent in the device profile, but an initial
        // Auto/Original request must not turn a predictive VLC guess into a
        // hidden quality cap or forced stream change.
        val effectiveQualityCapOrigin = qualityCapOrigin.takeIf { effectiveMaxStreamingBitrate != null }
        val effectiveRequestPolicy =
            planningRequestPolicy.copy(
                qualityResolutionCap =
                    effectiveQualityCapOrigin
                        ?.takeIf(PlaybackQualityCapOrigin::isUserSelectedRung)
                        ?.let { qualityRungForBitrate(effectiveMaxStreamingBitrate)?.resolutionCap },
                qualityCapOrigin = effectiveQualityCapOrigin,
                clientTrigger = planningRequestPolicy.clientTrigger,
            )
        val playbackInfoSubtitleStreamIndex = subtitleSelection.wireIndexOrNull()
        val directPlan =
            directPlayPlanner
                .plan(
                    session = session,
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    startPositionTicks = startPositionTicks,
                    detailMediaStreams = detailMediaStreams,
                ).copy(
                    selectedAudioStreamIndex = audioStreamIndex,
                    embeddedAudioTracks =
                        detailEmbeddedAudioTracks(
                            streams = detailMediaStreams,
                            capabilities = deviceCapabilities,
                        ),
                    embeddedSubtitleTracks =
                        fallbackEmbeddedSubtitleTracks(
                            streams = detailMediaStreams,
                            deviceCapabilities = deviceCapabilities,
                        ),
                    selectedSubtitleStreamIndex = subtitleSelection.selectedIndexOrNull(),
                    plannedSubtitle =
                        fallbackPlannedSubtitle(
                            session.serverUrl,
                            detailMediaStreams,
                            subtitleSelection,
                            deviceCapabilities,
                        ),
                    maxStreamingBitrate = effectiveMaxStreamingBitrate,
                    qualityCapOrigin = effectiveQualityCapOrigin,
                    resolutionPolicy =
                        resolutionPolicyFor(
                            videoStream = detailMediaStreams.firstVideoStreamOrNull(),
                            deviceCapabilities = deviceCapabilities,
                            qualityRungCeiling = effectiveRequestPolicy.qualityResolutionCap,
                            userResolutionCeiling = effectiveRequestPolicy.userVideoResolutionCap,
                        ),
                    clientTrigger = effectiveRequestPolicy.clientTrigger,
                    qualityPolicy = effectiveQualityPolicy,
                    bitrateConstraint = effectiveRequestPolicy.bitrateConstraint,
                    recoveryIntent = effectiveRequestPolicy.recoveryIntent,
                    videoExpected = detailMediaStreams.any { stream -> stream.type.equals("Video", ignoreCase = true) },
                )

        val planned =
            requestPlaybackInfo(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                startTimeTicks = startPositionTicks,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = playbackInfoSubtitleStreamIndex,
                initialMaxStreamingBitrate = effectiveMaxStreamingBitrate,
                initialQualityCapOrigin = effectiveQualityCapOrigin,
                requestPolicy = effectiveRequestPolicy,
            ).fold(
                onSuccess = { attempt ->
                    val attemptedDirectPlan =
                        directPlan.copy(
                            maxStreamingBitrate = attempt.maxStreamingBitrate,
                            qualityCapOrigin = attempt.qualityCapOrigin,
                            qualityPolicy = effectiveQualityPolicy,
                            bitrateConstraint = attempt.requestPolicy.bitrateConstraint,
                            recoveryIntent = attempt.requestPolicy.recoveryIntent,
                        )
                    try {
                        decidePlan(
                            directPlan = attemptedDirectPlan,
                            playbackInfo = attempt.playbackInfo,
                            serverUrl = session.serverUrl,
                            subtitleSelection = subtitleSelection,
                            deviceCapabilities = deviceCapabilities,
                            detailMediaStreams = detailMediaStreams,
                            requestPolicy = attempt.requestPolicy,
                        )
                    } catch (_: PlaybackPlanningException.SourceVideoCopyRejected) {
                        if (effectiveQualityPolicy.mode == PlaybackQualityMode.Original) {
                            throw PlaybackPlanningException.SourceVideoCopyUnsupported()
                        }
                        recoverWithForcedTranscode(
                            directPlan = attemptedDirectPlan,
                            itemId = itemId,
                            mediaSourceId = mediaSourceId,
                            startPositionTicks = startPositionTicks,
                            audioStreamIndex = audioStreamIndex,
                            subtitleStreamIndex = playbackInfoSubtitleStreamIndex,
                            serverUrl = session.serverUrl,
                            subtitleSelection = subtitleSelection,
                            deviceCapabilities = deviceCapabilities,
                            detailMediaStreams = detailMediaStreams,
                            requestPolicy = attempt.requestPolicy,
                        )
                    } catch (_: PlaybackPlanningException.NoSupportedStream) {
                        throw PlaybackPlanningException.NoSupportedStream
                    }
                },
                onFailure = { throwable ->
                    // The local direct URL has no server-side cap. It is only a
                    // safe fallback for the uncapped inherited Auto request;
                    // every Fixed policy, including the VLC-family default,
                    // must fail closed rather than bypass its finite quality.
                    if (!effectiveRequestPolicy.isDefault || effectiveQualityPolicy.mode != PlaybackQualityMode.Auto) {
                        throw throwable
                    }
                    playbackInfoPlannerLogger.w {
                        formatPlaybackDiagnostic(
                            PlaybackDiagnostic(
                                stage = PlaybackDiagnosticStage.Planner,
                                event = Fallback,
                                platform = PlaybackDiagnosticPlatform.Shared,
                                backend = effectiveRequestPolicy.backend,
                                sessionSequence = effectiveRequestPolicy.diagnosticSessionSequence,
                                exceptionType = throwable.playbackExceptionType(),
                                requestPolicy = effectiveRequestPolicy.diagnosticClass(),
                                clientTrigger = effectiveRequestPolicy.clientTrigger,
                                qualityCapOrigin = effectiveQualityCapOrigin,
                                requestCapBitrateBps = effectiveMaxStreamingBitrate,
                                resolutionPolicy = directPlan.resolutionPolicy,
                                startPositionMs = ticksToMilliseconds(startPositionTicks),
                                qualityPolicyMode = effectiveQualityPolicy.mode,
                                bitrateConstraint = effectiveRequestPolicy.bitrateConstraint.diagnosticName(),
                                recoveryIntent = effectiveRequestPolicy.recoveryIntent,
                            ),
                        )
                    }
                    playbackDiagnosticsContext?.recordFailure(
                        backend = effectiveRequestPolicy.backend,
                        capabilities = deviceCapabilities,
                        source =
                            detailMediaStreams.toDiagnosticsSourceDescriptor(
                                container = sourceContainer,
                                selectedAudioStreamIndex = audioStreamIndex,
                            ),
                    )
                    val fallbackPreflight =
                        sourceVideoCopyPreflight(
                            videoStream = detailMediaStreams.firstVideoStreamOrNull(),
                            effectiveBound =
                                effectivePlaybackCeiling(
                                    videoStream = detailMediaStreams.firstVideoStreamOrNull(),
                                    deviceCapabilities = deviceCapabilities,
                                    qualityRungCeiling = effectiveRequestPolicy.qualityResolutionCap,
                                    userResolutionCeiling = effectiveRequestPolicy.userVideoResolutionCap,
                                ),
                            unsupportedRangeTypesByCodec =
                                deviceCapabilities?.unsupportedVideoRangeTypesByCodec.orEmpty(),
                        )
                    if (fallbackPreflight.allowsSourceCopy) {
                        directPlan.copy(
                            diagnosticSessionSequence = effectiveRequestPolicy.diagnosticSessionSequence,
                        )
                    } else {
                        recoverWithForcedTranscode(
                            directPlan = directPlan,
                            itemId = itemId,
                            mediaSourceId = mediaSourceId,
                            startPositionTicks = startPositionTicks,
                            audioStreamIndex = audioStreamIndex,
                            subtitleStreamIndex = playbackInfoSubtitleStreamIndex,
                            serverUrl = session.serverUrl,
                            subtitleSelection = subtitleSelection,
                            deviceCapabilities = deviceCapabilities,
                            detailMediaStreams = detailMediaStreams,
                            requestPolicy = effectiveRequestPolicy,
                        )
                    }
                },
            )
        return if (
            subtitleSelection is SubtitleSelectionIntent.LocalAsset &&
            localSubtitleAsset?.assetId == subtitleSelection.assetId
        ) {
            planned.copy(
                selectedSubtitleStreamIndex = null,
                subtitleAsset = localSubtitleAsset,
                plannedSubtitle = PlannedSubtitle.LocalAsset(localSubtitleAsset.assetId, SubtitleKind.Text),
            )
        } else {
            planned
        }
    }

    private suspend fun requestPlaybackInfo(
        itemId: String,
        mediaSourceId: String,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        initialMaxStreamingBitrate: Long?,
        initialQualityCapOrigin: PlaybackQualityCapOrigin?,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): Result<PlaybackInfoAttempt> {
        val playbackInfo =
            mediaRepository
                .getPlaybackInfo(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    startTimeTicks = startTimeTicks,
                    audioStreamIndex = audioStreamIndex,
                    subtitleStreamIndex = subtitleStreamIndex,
                    maxStreamingBitrate = initialMaxStreamingBitrate,
                    requestPolicy = requestPolicy,
                ).getOrElse { throwable -> return Result.failure(throwable) }

        return Result.success(
            PlaybackInfoAttempt(
                playbackInfo = playbackInfo,
                maxStreamingBitrate = initialMaxStreamingBitrate,
                qualityCapOrigin = initialQualityCapOrigin,
                requestPolicy = requestPolicy,
            ),
        )
    }

    /**
     * One bounded retry for a source that cannot be handed to the decoder
     * unchanged. The policy retains the current backend while denying both
     * source-copy paths, so a server that ignores the request cannot slip a
     * DirectPlay or DirectStream response back through [decidePlan].
     */
    private suspend fun recoverWithForcedTranscode(
        directPlan: PlaybackPlan,
        itemId: String,
        mediaSourceId: String,
        startPositionTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        serverUrl: String,
        subtitleSelection: SubtitleSelectionIntent,
        deviceCapabilities: DeviceDecodingCapabilities?,
        detailMediaStreams: List<PlaybackMediaStream>,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): PlaybackPlan {
        // Attributed so the debug overlay and diagnostics can say WHY this session
        // transcodes: the server reports no TranscodeReason for a forced re-encode,
        // so this is the only visible cause. An already-present trigger (this
        // recovery can be reached from another fallback's replan) is preserved —
        // it names the earlier cause the user should see first.
        val userResolutionTrigger =
            PlaybackClientTrigger.UserResolutionLimit.takeIf {
                userResolutionIsSoleCopyCause(
                    videoStream = detailMediaStreams.firstVideoStreamOrNull(),
                    deviceCapabilities = deviceCapabilities,
                    qualityRungCeiling = requestPolicy.qualityResolutionCap,
                    userResolutionCeiling = requestPolicy.userVideoResolutionCap,
                )
            }
        val recoveryTrigger = requestPolicy.clientTrigger ?: userResolutionTrigger ?: PlaybackClientTrigger.DecodeCapabilityCap
        val transcodeOnlyPolicy =
            requestPolicy.copy(
                enableDirectPlay = false,
                enableDirectStream = false,
                clientTrigger = recoveryTrigger,
                recoveryIntent = PlaybackRecoveryIntent.Compatibility,
                // AllowVideoStreamCopy=false is what actually forces the video
                // re-encode: with it true a "transcode" can remux the oversize
                // video verbatim, invisibly (docs/guides/data-playback.md).
                // This flag is also decidePlan's proof that the recovery response's
                // transcode is safe to accept for a copy-refused source.
                allowVideoStreamCopy = false,
                // This requests a bounded output for the server capability probe,
                // but the missing-source-rate path remains fail-closed until that
                // server behavior is recorded as verified.
                maxFramerate =
                    forcedTranscodeMaxFramerate(
                        effectivePlaybackCeiling(
                            videoStream = detailMediaStreams.firstVideoStreamOrNull(),
                            deviceCapabilities = deviceCapabilities,
                            qualityRungCeiling = requestPolicy.qualityResolutionCap,
                            userResolutionCeiling = requestPolicy.userVideoResolutionCap,
                        ),
                    ),
            )
        val recoveryPlaybackInfo =
            mediaRepository
                .getPlaybackInfo(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    startTimeTicks = startPositionTicks,
                    audioStreamIndex = audioStreamIndex,
                    subtitleStreamIndex = subtitleStreamIndex,
                    maxStreamingBitrate = directPlan.maxStreamingBitrate,
                    requestPolicy = transcodeOnlyPolicy,
                ).getOrElse { throwable ->
                    throw PlaybackPlanningException.SourceVideoCopyUnsupported(
                        attemptedRequestPolicy = transcodeOnlyPolicy,
                        attemptedMaxStreamingBitrate = directPlan.maxStreamingBitrate,
                        attemptedQualityCapOrigin = directPlan.qualityCapOrigin,
                        attemptedQualityPolicy = directPlan.qualityPolicy,
                        cause = throwable,
                    )
                }
        return try {
            decidePlan(
                // The plan is what the overlay reads (decidePlan carries
                // resolvedPlan.clientTrigger forward), so the policy alone is not
                // enough — the trigger must be on the plan too.
                directPlan = directPlan.copy(clientTrigger = recoveryTrigger),
                playbackInfo = recoveryPlaybackInfo,
                serverUrl = serverUrl,
                subtitleSelection = subtitleSelection,
                deviceCapabilities = deviceCapabilities,
                detailMediaStreams = detailMediaStreams,
                requestPolicy = transcodeOnlyPolicy,
            )
        } catch (exception: PlaybackPlanningException.SourceVideoCopyRejected) {
            throw PlaybackPlanningException.SourceVideoCopyUnsupported(
                attemptedRequestPolicy = transcodeOnlyPolicy,
                attemptedMaxStreamingBitrate = directPlan.maxStreamingBitrate,
                attemptedQualityCapOrigin = directPlan.qualityCapOrigin,
                attemptedQualityPolicy = directPlan.qualityPolicy,
                cause = exception,
            )
        } catch (exception: PlaybackPlanningException.NoSupportedStream) {
            throw PlaybackPlanningException.SourceVideoCopyUnsupported(
                attemptedRequestPolicy = transcodeOnlyPolicy,
                attemptedMaxStreamingBitrate = directPlan.maxStreamingBitrate,
                attemptedQualityCapOrigin = directPlan.qualityCapOrigin,
                attemptedQualityPolicy = directPlan.qualityPolicy,
                cause = exception,
            )
        }
    }

    private fun fallbackPlannedSubtitle(
        serverUrl: String,
        detailMediaStreams: List<PlaybackMediaStream>,
        subtitleSelection: SubtitleSelectionIntent,
        deviceCapabilities: DeviceDecodingCapabilities?,
    ): PlannedSubtitle =
        when (subtitleSelection) {
            SubtitleSelectionIntent.Off, SubtitleSelectionIntent.Unspecified -> PlannedSubtitle.Off
            is SubtitleSelectionIntent.LocalAsset -> PlannedSubtitle.Off
            is SubtitleSelectionIntent.Track -> {
                val stream = detailMediaStreams.firstOrNull { candidate -> candidate.index == subtitleSelection.streamIndex }
                if (stream == null) {
                    PlannedSubtitle.Unavailable(
                        streamIndex = subtitleSelection.streamIndex,
                        kind = SubtitleKind.Text,
                        reason = "Subtitle metadata unavailable",
                    )
                } else {
                    val format = stream.normalizedSubtitleFormat()
                    val kind = subtitleKind(format)
                    val capability =
                        deviceCapabilities
                            ?.subtitleProfiles
                            ?.firstOrNull { profile -> subtitleFormatsEquivalent(profile.format, format) }
                    val delivery = if (stream.isExternalSubtitle()) SubtitleDeliveryMethod.External else SubtitleDeliveryMethod.Embed
                    val external = stream.toExternalSubtitle(serverUrl)
                    if (
                        capability != null &&
                        delivery in capability.deliveryMethods &&
                        (delivery != SubtitleDeliveryMethod.External || external != null)
                    ) {
                        PlannedSubtitle.Track(
                            streamIndex = subtitleSelection.streamIndex,
                            embeddedTrack =
                                if (delivery == SubtitleDeliveryMethod.Embed) {
                                    fallbackEmbeddedSubtitleTracks(detailMediaStreams, deviceCapabilities)
                                        .firstOrNull { descriptor ->
                                            descriptor.jellyfinStreamIndex == subtitleSelection.streamIndex
                                        }
                                } else {
                                    null
                                },
                            deliveryMethod = delivery,
                            kind = kind,
                            externalResource = external,
                            normalizedFormat = format,
                        )
                    } else {
                        PlannedSubtitle.Unavailable(
                            streamIndex = subtitleSelection.streamIndex,
                            kind = kind,
                            normalizedFormat = format,
                            reason = "PlaybackInfo unavailable and local delivery is unverified",
                        )
                    }
                }
            }
        }

    private fun fallbackEmbeddedSubtitleTracks(
        streams: List<PlaybackMediaStream>,
        deviceCapabilities: DeviceDecodingCapabilities?,
    ): List<PlannedEmbeddedTrack> =
        streams
            .filter { stream ->
                stream.type.equals("Subtitle", ignoreCase = true) &&
                    !stream.isExternalSubtitle() &&
                    deviceCapabilities
                        ?.subtitleProfiles
                        ?.firstOrNull { profile -> subtitleFormatsEquivalent(profile.format, stream.normalizedSubtitleFormat()) }
                        ?.deliveryMethods
                        ?.contains(SubtitleDeliveryMethod.Embed) == true
            }.mapIndexedNotNull { ordinal, stream ->
                stream.index?.let { index ->
                    stream.toPlannedEmbeddedTrack(
                        streamIndex = index,
                        filteredContainerOrdinal = ordinal,
                        trackKind = EmbeddedTrackKind.Subtitle,
                    )
                }
            }
}

fun decidePlan(
    directPlan: PlaybackPlan,
    playbackInfo: PlaybackInfo,
    serverUrl: String,
    subtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
    deviceCapabilities: DeviceDecodingCapabilities? = null,
    detailMediaStreams: List<PlaybackMediaStream> = emptyList(),
    requestPolicy: PlaybackInfoRequestPolicy = PlaybackInfoRequestPolicy(),
): PlaybackPlan {
    val mediaSource =
        playbackInfo.mediaSources.firstOrNull { source -> source.id == directPlan.mediaSourceId }
            ?: playbackInfo.mediaSources.firstOrNull()
            ?: throw PlaybackPlanningException.NoSupportedStream
    val detailVideoStream =
        detailMediaStreams
            .firstVideoStreamOrNull()
            .takeIf { mediaSource.id == null || mediaSource.id == directPlan.mediaSourceId }
    val videoStream =
        mediaSource.mediaStreams
            .firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
            // The response and the item detail describe the same file but can
            // disagree on OPTIONAL metadata; a missing dimension or frame rate
            // fails closed, so borrow the field from detail rather than erroring
            // on a source whose metadata another endpoint already carries.
            //
            // ONLY when the resolved source IS the requested version: the
            // fallback above can select a different version when the requested
            // id is absent from the response, and the caller's detail streams
            // describe the REQUESTED version — borrowing across versions could
            // fill a 60fps file's missing rate with another file's 24fps and
            // permit an unsafe copy. A mismatched source keeps its own metadata
            // and fails closed on what it lacks.
            ?.withMetadataBorrowedFrom(detailVideoStream)
            ?: detailVideoStream
    val sourceCopyPreflight =
        if (
            (videoStream != null || directPlan.videoExpected) &&
            sourceVideoMetadataIsInsufficientForActiveCopyPolicy(
                videoStream = videoStream,
                deviceCapabilities = deviceCapabilities,
                requestPolicy = requestPolicy,
            )
        ) {
            SourceVideoCopyPreflight(
                allowsSourceCopy = false,
                allowsUnverifiedTranscode = true,
                hasFiniteCapabilityCap = true,
            )
        } else {
            sourceVideoCopyPreflight(
                videoStream = videoStream,
                effectiveBound =
                    effectivePlaybackCeiling(
                        videoStream = videoStream,
                        deviceCapabilities = deviceCapabilities,
                        qualityRungCeiling = requestPolicy.qualityResolutionCap,
                        userResolutionCeiling = requestPolicy.userVideoResolutionCap,
                    ),
                unsupportedRangeTypesByCodec =
                    deviceCapabilities?.unsupportedVideoRangeTypesByCodec.orEmpty(),
            )
        }
    val sourceBitrate = mediaSource.bitrate ?: videoStream?.bitRate
    val selectedAudioIndex = mediaSource.defaultAudioStreamIndex?.takeIf { it >= 0 } ?: directPlan.selectedAudioStreamIndex
    val requestedSubtitleIndex = subtitleSelection.selectedIndexOrNull()
    val plannedSubtitle =
        resolveResponseSubtitle(
            streams = mediaSource.mediaStreams,
            selectedStreamIndex = requestedSubtitleIndex,
            subtitleSelection = subtitleSelection,
            detailMediaStreams = detailMediaStreams,
            serverUrl = serverUrl,
        )
    val subtitleAsset = (plannedSubtitle as? PlannedSubtitle.Track)?.externalResource
    val subtitleMethod =
        when (plannedSubtitle) {
            PlannedSubtitle.Off -> SubtitleDeliveryMethod.Drop
            is PlannedSubtitle.Track -> plannedSubtitle.deliveryMethod
            is PlannedSubtitle.LocalAsset -> SubtitleDeliveryMethod.Drop
            is PlannedSubtitle.Unavailable -> SubtitleDeliveryMethod.Drop
        }
    val directStreamSubtitleIndex =
        if (subtitleSelection == SubtitleSelectionIntent.Off) OFF_SUBTITLE_STREAM_INDEX else requestedSubtitleIndex
    val selectedMediaSourceId = mediaSource.id ?: directPlan.mediaSourceId
    val deviceId = directPlan.streamUrl.substringAfter("deviceId=", "").substringBefore('&')
    val directStreamContainer = normalizedStreamContainer(mediaSource.transcodingContainer)
    val responseEmbeddedSubtitleTracks =
        responseEmbeddedSubtitleTracks(
            mediaSource.mediaStreams.filter { stream ->
                stream.deliveryMethod.equals("Embed", ignoreCase = true)
            },
        )
    val responseEmbeddedSubtitleIndexes =
        responseEmbeddedSubtitleTracks
            .mapTo(mutableSetOf()) { track -> track.jellyfinStreamIndex }
    val retainedEmbeddedSubtitleTracks =
        responseEmbeddedSubtitleTracks +
            directPlan.embeddedSubtitleTracks.filterNot { track ->
                track.jellyfinStreamIndex in responseEmbeddedSubtitleIndexes
            }

    val commonPlan =
        directPlan.copy(
            mediaSourceId = selectedMediaSourceId,
            playSessionId = playbackInfo.playSessionId,
            selectedAudioStreamIndex = selectedAudioIndex,
            embeddedAudioTracks = embeddedAudioTracks(mediaSource.mediaStreams, deviceCapabilities),
            embeddedSubtitleTracks = retainedEmbeddedSubtitleTracks,
            audioSelectionAuthoritative = true,
            selectedSubtitleStreamIndex = requestedSubtitleIndex,
            subtitleAsset = subtitleAsset,
            plannedSubtitle = plannedSubtitle,
            resolutionPolicy =
                resolutionPolicyFor(
                    videoStream = videoStream,
                    deviceCapabilities = deviceCapabilities,
                    qualityRungCeiling = requestPolicy.qualityResolutionCap,
                    userResolutionCeiling = requestPolicy.userVideoResolutionCap,
                ),
            sourceBitrateBps = sourceBitrate,
            videoStreamLabel = videoStream?.readableLabel(),
            videoPresentation =
                videoStream?.let { stream ->
                    PlannedVideoPresentation(
                        width = stream.width,
                        height = stream.height,
                        frameRate = stream.realFrameRate,
                        videoRangeType = stream.videoRangeType,
                        bitDepth = stream.bitDepth,
                    )
                },
            videoExpected = videoStream != null || directPlan.videoExpected,
            transcodeReasons = mediaSource.transcodeReasons,
            diagnosticSessionSequence = requestPolicy.diagnosticSessionSequence,
        )
    val resolvedPlan =
        when {
            mediaSource.supportsDirectPlay && requestPolicy.enableDirectPlay && sourceCopyPreflight.allowsSourceCopy ->
                commonPlan.copy(
                    streamUrl = directPlayStreamUrl(serverUrl, directPlan.itemId, selectedMediaSourceId, deviceId),
                    container = mediaSource.container,
                    streamMimeType = playbackStreamMimeType(mediaSource.container, null),
                )
            mediaSource.supportsDirectStream &&
                requestPolicy.enableDirectStream &&
                directStreamContainer != null &&
                sourceCopyPreflight.allowsSourceCopy ->
                commonPlan.copy(
                    streamMode = StreamMode.DirectStream,
                    streamUrl =
                        directStreamUrl(
                            serverUrl = serverUrl,
                            itemId = directPlan.itemId,
                            container = directStreamContainer,
                            mediaSourceId = selectedMediaSourceId,
                            deviceId = deviceId,
                            playSessionId = playbackInfo.playSessionId,
                            audioStreamIndex = selectedAudioIndex,
                            subtitleStreamIndex = directStreamSubtitleIndex,
                            subtitleMethod = subtitleMethod,
                        ),
                    container = directStreamContainer,
                    streamMimeType = playbackStreamMimeType(directStreamContainer, mediaSource.transcodingSubProtocol),
                )
            mediaSource.supportsTranscoding &&
                !mediaSource.transcodingUrl.isNullOrBlank() &&
                sourceCopyPreflight.allowsUnverifiedTranscode &&
                // A copy-refused source may only take a transcode whose request
                // provably forced a video re-encode: with AllowVideoStreamCopy
                // left true the server can remux the refused video verbatim and
                // nothing in the response reveals it (the TranscodeReasons stay
                // unchanged — server-behaviors item 12). The recovery re-request
                // sets the flag false, which is what makes its response safe.
                (sourceCopyPreflight.allowsSourceCopy || !requestPolicy.allowVideoStreamCopy) -> {
                val transcodeUrl =
                    resolveServerRelativeUrl(
                        serverUrl = serverUrl,
                        url = mediaSource.transcodingUrl,
                    )
                val cappedUrl =
                    transcodeResolutionCap(
                        transcodingUrl = transcodeUrl,
                        sourceWidth = videoStream?.width,
                        sourceHeight = videoStream?.height,
                        videoResolutionsByCodec = deviceCapabilities?.videoResolutionsByCodec.orEmpty(),
                        sourceFrameRate = videoStream?.realFrameRate,
                        qualityRungCeiling = requestPolicy.qualityResolutionCap,
                        userResolutionCeiling = requestPolicy.userVideoResolutionCap,
                        // Carried as a transcode-URL parameter: PlaybackInfoDto has
                        // no MaxFramerate member, so a request-body field would be
                        // silently ignored and unfalsifiable by the server probe.
                        maxFramerate = requestPolicy.maxFramerate,
                    )
                if (cappedUrl != null) {
                    val cappedWidth =
                        cappedUrl
                            .substringAfterLast("Width=", "")
                            .substringBefore('&')
                            .toIntOrNull()
                    val cappedHeight =
                        cappedUrl
                            .substringAfterLast("Height=", "")
                            .substringBefore('&')
                            .toIntOrNull()
                    // Log only the dimensions: the capped URL carries the ApiKey.
                    playbackInfoPlannerLogger.i {
                        formatPlaybackDiagnostic(
                            PlaybackDiagnostic(
                                stage = PlaybackDiagnosticStage.Planner,
                                event = PlaybackDiagnosticEvent.ResolutionCap,
                                platform = PlaybackDiagnosticPlatform.Shared,
                                sessionSequence = requestPolicy.diagnosticSessionSequence,
                                sourceWidth = videoStream?.width,
                                sourceHeight = videoStream?.height,
                                frameRate = videoStream?.realFrameRate,
                                cappedWidth = cappedWidth,
                                cappedHeight = cappedHeight,
                                resolutionPolicy = commonPlan.resolutionPolicy,
                            ),
                        )
                    }
                }
                val subtitleHonestUrl =
                    subtitleHonestTranscodingUrl(
                        transcodingUrl = cappedUrl ?: transcodeUrl,
                        selectedSubtitleStreamIndex = requestedSubtitleIndex,
                    )
                val mismatchedAttachmentStripped = subtitleHonestUrl != (cappedUrl ?: transcodeUrl)
                if (mismatchedAttachmentStripped) {
                    playbackInfoPlannerLogger.i {
                        formatPlaybackDiagnostic(
                            PlaybackDiagnostic(
                                stage = PlaybackDiagnosticStage.Planner,
                                event = PlaybackDiagnosticEvent.Rejected,
                                platform = PlaybackDiagnosticPlatform.Shared,
                                sessionSequence = requestPolicy.diagnosticSessionSequence,
                                trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                            ),
                        )
                    }
                }
                // An Encode-delivery track renders ONLY inside the transcode
                // video. When the server attached a DIFFERENT stream and the
                // strip removed it, nothing will burn the requested track —
                // publishing it as active would lie, so it demotes to
                // Unavailable and the existing forced-Encode fallback recovers
                // it. A URL that never carried subtitle params is left alone.
                val transcodePlannedSubtitle =
                    if (
                        mismatchedAttachmentStripped &&
                        plannedSubtitle is PlannedSubtitle.Track &&
                        plannedSubtitle.deliveryMethod == SubtitleDeliveryMethod.Encode
                    ) {
                        PlannedSubtitle.Unavailable(
                            streamIndex = plannedSubtitle.streamIndex,
                            kind = plannedSubtitle.kind,
                            normalizedFormat = plannedSubtitle.normalizedFormat,
                            reason = "Encode subtitle absent from transcode",
                            allowEncodeFallback = true,
                            activationTarget = plannedSubtitle.activationTarget,
                        )
                    } else {
                        plannedSubtitle
                    }
                commonPlan.copy(
                    streamMode = StreamMode.Transcode,
                    streamUrl = subtitleHonestUrl,
                    plannedSubtitle = transcodePlannedSubtitle,
                    container = mediaSource.transcodingContainer ?: mediaSource.container,
                    streamMimeType = playbackStreamMimeType(mediaSource.transcodingContainer, mediaSource.transcodingSubProtocol),
                    effectiveTranscodeMaxStreamingBitrate = commonPlan.maxStreamingBitrate,
                )
            }
            !sourceCopyPreflight.allowsSourceCopy -> throw PlaybackPlanningException.SourceVideoCopyRejected
            else -> throw PlaybackPlanningException.NoSupportedStream
        }
    playbackInfoPlannerLogger.i {
        formatPlaybackDiagnostic(
            PlaybackDiagnostic(
                stage = PlaybackDiagnosticStage.Planner,
                event = Resolved,
                platform = PlaybackDiagnosticPlatform.Shared,
                backend = requestPolicy.backend,
                sessionSequence = requestPolicy.diagnosticSessionSequence,
                streamMode = resolvedPlan.streamMode,
                requestPolicy = requestPolicy.diagnosticClass(),
                deliveryMethod = subtitleMethod,
                clientTrigger = resolvedPlan.clientTrigger,
                qualityCapOrigin = resolvedPlan.qualityCapOrigin,
                capabilityResult = sourceCopyPreflight.diagnosticResult(),
                resolutionPolicy = resolvedPlan.resolutionPolicy,
                transcodeReasons = resolvedPlan.transcodeReasons,
                startPositionMs = resolvedPlan.startPositionMs,
                sourceWidth = videoStream?.width,
                sourceHeight = videoStream?.height,
                frameRate = videoStream?.realFrameRate,
                sourceBitDepth = videoStream?.bitDepth,
                sourceVideoRangeType = videoStream?.videoRangeType,
                sourceBitrateBps = resolvedPlan.sourceBitrateBps ?: videoStream?.bitRate,
                codec = videoStream?.codec,
                container = resolvedPlan.container,
                requestCapBitrateBps = resolvedPlan.maxStreamingBitrate,
                effectiveTranscodeCapBitrateBps = resolvedPlan.effectiveTranscodeMaxStreamingBitrate,
                qualityPolicyMode = resolvedPlan.qualityPolicy.mode,
                bitrateConstraint = resolvedPlan.bitrateConstraint.diagnosticName(),
                recoveryIntent = resolvedPlan.recoveryIntent,
            ),
        )
    }
    return resolvedPlan
}

private fun resolveResponseSubtitle(
    streams: List<PlaybackMediaStream>,
    selectedStreamIndex: Int?,
    subtitleSelection: SubtitleSelectionIntent,
    detailMediaStreams: List<PlaybackMediaStream>,
    serverUrl: String,
): PlannedSubtitle {
    if (subtitleSelection == SubtitleSelectionIntent.Off || selectedStreamIndex == null) return PlannedSubtitle.Off
    val stream =
        streams.firstOrNull { candidate ->
            candidate.type.equals("Subtitle", ignoreCase = true) && candidate.index == selectedStreamIndex
        }
            ?: run {
                val detailStream =
                    detailMediaStreams.firstOrNull { candidate ->
                        candidate.type.equals("Subtitle", ignoreCase = true) &&
                            candidate.index == selectedStreamIndex
                    }
                val format = detailStream?.normalizedSubtitleFormat()
                return PlannedSubtitle.Unavailable(
                    streamIndex = selectedStreamIndex,
                    kind = subtitleKind(format),
                    normalizedFormat = format,
                    reason = "Selected response stream missing",
                    allowEncodeFallback = format != null,
                )
            }
    val format = stream.normalizedSubtitleFormat()
    val kind = subtitleKind(format)
    val delivery = stream.deliveryMethod.toSubtitleDeliveryMethod()
    if (delivery == SubtitleDeliveryMethod.Drop || delivery == SubtitleDeliveryMethod.Unavailable) {
        return PlannedSubtitle.Unavailable(
            selectedStreamIndex,
            kind,
            format,
            "Server did not approve subtitle delivery",
            allowEncodeFallback = format != null,
        )
    }
    val external = if (delivery == SubtitleDeliveryMethod.External) stream.toExternalSubtitle(serverUrl) else null
    if (delivery == SubtitleDeliveryMethod.External && external == null) {
        return PlannedSubtitle.Unavailable(
            selectedStreamIndex,
            kind,
            format,
            "External subtitle URL or MIME is unsupported",
            allowEncodeFallback = format != null,
        )
    }
    return PlannedSubtitle.Track(
        streamIndex = selectedStreamIndex,
        embeddedTrack =
            responseEmbeddedSubtitleTracks(streams)
                .firstOrNull { descriptor -> descriptor.jellyfinStreamIndex == selectedStreamIndex },
        deliveryMethod = delivery,
        kind = kind,
        externalResource = external,
        normalizedFormat = format,
    )
}

private fun String?.toSubtitleDeliveryMethod(): SubtitleDeliveryMethod =
    when {
        equals("Drop", true) -> SubtitleDeliveryMethod.Drop
        equals("Embed", true) -> SubtitleDeliveryMethod.Embed
        equals("External", true) -> SubtitleDeliveryMethod.External
        equals("Hls", true) -> SubtitleDeliveryMethod.Hls
        equals("Encode", true) -> SubtitleDeliveryMethod.Encode
        else -> SubtitleDeliveryMethod.Unavailable
    }

sealed class PlaybackPlanningException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    data object NoSupportedStream : PlaybackPlanningException("No supported playback stream is available.")

    /** The decoder cannot safely receive the source video without a transcode. */
    data object SourceVideoCopyRejected : PlaybackPlanningException("Source video exceeds the decoder capability bound.")

    /** The one allowed forced-transcode recovery did not return a safe stream. */
    class SourceVideoCopyUnsupported(
        val attemptedRequestPolicy: PlaybackInfoRequestPolicy? = null,
        val attemptedMaxStreamingBitrate: Long? = null,
        val attemptedQualityCapOrigin: PlaybackQualityCapOrigin? = null,
        val attemptedQualityPolicy: PlaybackQualityPolicy? = null,
        cause: Throwable? = null,
    ) : PlaybackPlanningException("No decodable transcoded playback stream is available.", cause)
}

private data class SourceVideoCopyPreflight(
    val allowsSourceCopy: Boolean,
    val allowsUnverifiedTranscode: Boolean,
    val hasFiniteCapabilityCap: Boolean,
)

private fun sourceVideoMetadataIsInsufficientForActiveCopyPolicy(
    videoStream: PlaybackMediaStream?,
    deviceCapabilities: DeviceDecodingCapabilities?,
    requestPolicy: PlaybackInfoRequestPolicy,
): Boolean {
    val hasGlobalResolutionBound =
        requestPolicy.qualityResolutionCap.hasFiniteVideoBound() ||
            requestPolicy.userVideoResolutionCap.hasFiniteVideoBound()
    val hasFiniteDeviceBound =
        deviceCapabilities
            ?.videoResolutionsByCodec
            ?.values
            ?.any { bound -> bound.hasFiniteVideoBound() } == true

    if (videoStream == null) {
        return hasGlobalResolutionBound || hasFiniteDeviceBound
    }

    val codec = canonicalVideoCodec(videoStream.codec)
    if (codec == null) {
        return hasFiniteDeviceBound
    }

    return false
}

private fun VideoCodecResolution?.hasFiniteVideoBound(): Boolean =
    this != null &&
        (maxWidth != null || maxHeight != null || maxFrameArea != null || maxFrameAreaPerSecond != null)

private fun sourceVideoCopyPreflight(
    videoStream: PlaybackMediaStream?,
    effectiveBound: VideoCodecResolution?,
    unsupportedRangeTypesByCodec: Map<String, Set<String>> = emptyMap(),
): SourceVideoCopyPreflight {
    if (
        videoStream != null &&
        VideoRangeTypePolicy.isExplicitlyUnsupported(
            codec = videoStream.codec,
            rangeType = videoStream.videoRangeType,
            unsupportedRangeTypesByCodec = unsupportedRangeTypesByCodec,
        )
    ) {
        return SourceVideoCopyPreflight(
            allowsSourceCopy = false,
            allowsUnverifiedTranscode = true,
            hasFiniteCapabilityCap = true,
        )
    }
    if (videoStream == null || effectiveBound == null) {
        return SourceVideoCopyPreflight(
            allowsSourceCopy = true,
            allowsUnverifiedTranscode = true,
            hasFiniteCapabilityCap = false,
        )
    }
    if (!effectiveBound.hasFiniteVideoBound()) {
        // An unknown bound is not evidence that this concrete source is
        // unsupported. The authoritative profile remains the server-facing
        // compatibility contract; only a measured finite bound rejects copy.
        return SourceVideoCopyPreflight(
            allowsSourceCopy = true,
            allowsUnverifiedTranscode = true,
            hasFiniteCapabilityCap = false,
        )
    }
    val width = videoStream.width
    val height = videoStream.height
    if (width == null || height == null || width <= 0 || height <= 0) {
        // Unknown source dimensions: unprovable for a copy, so never copy — but
        // the transcode stays available, because output dimensions ARE
        // expressible as profile conditions (the mechanism this plan adds). The
        // transcode-refusing fail-closed below is reserved for the frame-rate
        // case, which no profile condition can express.
        return SourceVideoCopyPreflight(
            allowsSourceCopy = false,
            allowsUnverifiedTranscode = true,
            hasFiniteCapabilityCap = true,
        )
    }
    val frameArea = blockPaddedArea(width, height)
    val exceedsFrameBound =
        (effectiveBound.maxWidth != null && width > effectiveBound.maxWidth) ||
            (effectiveBound.maxHeight != null && height > effectiveBound.maxHeight) ||
            (effectiveBound.maxFrameArea != null && frameArea > effectiveBound.maxFrameArea)
    val frameRate = videoStream.realFrameRate
    val hasFiniteThroughput = effectiveBound.maxFrameAreaPerSecond != null
    if (hasFiniteThroughput && (frameRate == null || !frameRate.isFinite() || frameRate <= 0.0)) {
        // PlaybackInfo does not expose negotiated output rate. Until a pinned
        // MaxFramerate request is probe-verified, an unknown-rate recovery is
        // not provably safe and must fail closed after its one retry.
        return SourceVideoCopyPreflight(
            allowsSourceCopy = false,
            allowsUnverifiedTranscode = false,
            hasFiniteCapabilityCap = true,
        )
    }
    val exceedsThroughput =
        frameRate != null &&
            effectiveBound.maxFrameAreaPerSecond != null &&
            frameArea.toDouble() * frameRate > effectiveBound.maxFrameAreaPerSecond.toDouble()
    return SourceVideoCopyPreflight(
        allowsSourceCopy = !exceedsFrameBound && !exceedsThroughput,
        allowsUnverifiedTranscode = true,
        hasFiniteCapabilityCap = true,
    )
}

private fun SourceVideoCopyPreflight.diagnosticResult(): PlaybackCapabilityResult =
    when {
        !allowsSourceCopy -> PlaybackCapabilityResult.SourceCopyRejected
        hasFiniteCapabilityCap -> PlaybackCapabilityResult.SourceCopyAllowed
        else -> PlaybackCapabilityResult.NoFiniteCapabilityCap
    }

private fun effectivePlaybackCeiling(
    videoStream: PlaybackMediaStream?,
    deviceCapabilities: DeviceDecodingCapabilities?,
    qualityRungCeiling: VideoCodecResolution?,
    userResolutionCeiling: VideoCodecResolution?,
): VideoCodecResolution? =
    reconcileVideoResolutionBounds(
        deviceCeiling = deviceResolutionCeiling(videoStream, deviceCapabilities),
        qualityRungCeiling = qualityRungCeiling,
        userResolutionCeiling = userResolutionCeiling,
    )

private fun deviceResolutionCeiling(
    videoStream: PlaybackMediaStream?,
    deviceCapabilities: DeviceDecodingCapabilities?,
): VideoCodecResolution? {
    val capabilities = deviceCapabilities ?: return null
    if (capabilities.videoResolutionsByCodec.isEmpty()) return null
    val codec = videoStream?.codec?.let(::canonicalVideoCodec)
    // A populated map without this codec is unknown evidence, not an
    // unsupported-codec claim. Only an actual finite entry can cap copy.
    return codec?.let(capabilities.videoResolutionsByCodec::get)
}

private fun resolutionPolicyFor(
    videoStream: PlaybackMediaStream?,
    deviceCapabilities: DeviceDecodingCapabilities?,
    qualityRungCeiling: VideoCodecResolution?,
    userResolutionCeiling: VideoCodecResolution?,
): PlaybackResolutionPolicy =
    playbackResolutionPolicy(
        deviceCeiling = deviceResolutionCeiling(videoStream, deviceCapabilities),
        qualityRungCeiling = qualityRungCeiling,
        userResolutionCeiling = userResolutionCeiling,
    )

private fun userResolutionIsSoleCopyCause(
    videoStream: PlaybackMediaStream?,
    deviceCapabilities: DeviceDecodingCapabilities?,
    qualityRungCeiling: VideoCodecResolution?,
    userResolutionCeiling: VideoCodecResolution?,
): Boolean {
    if (userResolutionCeiling == null) return false
    val withUserBound =
        sourceVideoCopyPreflight(
            videoStream = videoStream,
            effectiveBound =
                effectivePlaybackCeiling(
                    videoStream = videoStream,
                    deviceCapabilities = deviceCapabilities,
                    qualityRungCeiling = qualityRungCeiling,
                    userResolutionCeiling = userResolutionCeiling,
                ),
            unsupportedRangeTypesByCodec =
                deviceCapabilities?.unsupportedVideoRangeTypesByCodec.orEmpty(),
        )
    if (withUserBound.allowsSourceCopy) return false
    val withoutUserBound =
        sourceVideoCopyPreflight(
            videoStream = videoStream,
            effectiveBound =
                effectivePlaybackCeiling(
                    videoStream = videoStream,
                    deviceCapabilities = deviceCapabilities,
                    qualityRungCeiling = qualityRungCeiling,
                    userResolutionCeiling = null,
                ),
            unsupportedRangeTypesByCodec =
                deviceCapabilities?.unsupportedVideoRangeTypesByCodec.orEmpty(),
        )
    return withoutUserBound.allowsSourceCopy
}

private fun forcedTranscodeMaxFramerate(effectiveBound: VideoCodecResolution?): Int {
    val throughput = effectiveBound?.maxFrameAreaPerSecond ?: return DEFAULT_FORCED_TRANSCODE_MAX_FRAMERATE
    val frameArea =
        effectiveBound.maxFrameArea
            ?: effectiveBound.maxWidth
                ?.let { width ->
                    effectiveBound.maxHeight?.let { height -> blockPaddedArea(width, height) }
                }
            ?: return DEFAULT_FORCED_TRANSCODE_MAX_FRAMERATE
    return (throughput / frameArea).coerceIn(1L, MAX_FORCED_TRANSCODE_MAX_FRAMERATE.toLong()).toInt()
}

private fun PlaybackMediaStream.withMetadataBorrowedFrom(detailStream: PlaybackMediaStream?): PlaybackMediaStream =
    if (detailStream == null) {
        this
    } else {
        copy(
            codec = codec?.takeIf(String::isNotBlank) ?: detailStream.codec,
            width = width ?: detailStream.width,
            height = height ?: detailStream.height,
            realFrameRate = realFrameRate ?: detailStream.realFrameRate,
            videoRangeType = videoRangeType?.takeIf(String::isNotBlank) ?: detailStream.videoRangeType,
        )
    }

private fun List<PlaybackMediaStream>.firstVideoStreamOrNull(): PlaybackMediaStream? =
    firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }

private fun PlaybackQualityCapOrigin.isUserSelectedRung(): Boolean =
    this == PlaybackQualityCapOrigin.ExplicitSessionChoice ||
        this == PlaybackQualityCapOrigin.SettingsDefault ||
        this == PlaybackQualityCapOrigin.AutoSessionRecovery

private const val DEFAULT_FORCED_TRANSCODE_MAX_FRAMERATE = 30
private const val MAX_FORCED_TRANSCODE_MAX_FRAMERATE = 120

private val playbackInfoPlannerLogger = diagnosticLogger(DiagnosticTag.PlaybackInfoPlanner)
