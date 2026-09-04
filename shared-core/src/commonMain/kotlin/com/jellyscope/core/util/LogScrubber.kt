// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import com.jellyscope.core.domain.playback.playbackDiagnosticFieldNames

/**
 * Capture boundary for client log uploads. Only known structured diagnostics are retained;
 * regular Kermit output is deliberately not considered safe enough to scrub for upload.
 */
object LogScrubber {
    private val ordinaryAllowedTags =
        DiagnosticTag.entries
            .filter { tag -> tag.capturePolicy == DiagnosticTagCapturePolicy.Ordinary }
            .map { tag -> tag.wireValue }
            .toSet()

    private val diagnosticMessage =
        Regex(
            pattern =
                "^(?=.*(?:^|\\s)stage=[A-Za-z][A-Za-z0-9_.-]{0,79})(?=.*(?:^|\\s)event=[A-Za-z][A-Za-z0-9_.-]{0,79})(?:[A-Za-z][A-Za-z0-9]*=[^\\s]+)(?:\\s+[A-Za-z][A-Za-z0-9]*=[^\\s]+)*$",
        )
    internal val allowedFields =
        playbackDiagnosticFieldNames +
            setOf(
                "exitReason",
                "exitImportance",
                "peakPssMiB",
                "peakRssMiB",
                "exitAgeBucket",
                // Bounded, identity-free VLCKit transition/terminal evidence.
                // These fields are intentionally explicit: adding a Kermit log
                // alone must never bypass the client-log capture allowlist.
                "generation",
                "transitionSequence",
                "kind",
                "state",
                "targetPositionMs",
                "nativePositionMs",
                "publishedPositionMs",
                "targetArrived",
                "clockAdvanced",
                "pictureAdvances",
                "requiredPictureAdvances",
                "decodedVideo",
                "displayedPictures",
                "lostPictures",
                "hasVideoOut",
                "videoWidth",
                "videoHeight",
                "nativePlaying",
                "nativeState",
                "nativeBuffering",
                "nativeBufferingProgressMilli",
                "playIntent",
                "pendingInitialSeek",
                "playbackEverProgressed",
                "terminalEvent",
                "previousPositionMs",
                "durationMs",
                "previousStatus",
                "decisionStatus",
                "decisionPositionMs",
                "endRejected",
                "transitionKind",
                "transitionState",
                "transitionTargetPositionMs",
                "transitionNativePositionMs",
                // Identity-free OpenSubtitles response-shape diagnostics.
                "requestKind",
                "responseContentType",
                "causeType",
                "queryCount",
                "resultCount",
                "result",
                "failure",
                // Identity-free trickplay request, decode, and crop evidence.
                "surface",
                "tileIndex",
                "thumbnailWidth",
                "thumbnailHeight",
                "tileColumns",
                "tileRows",
                "cropColumn",
                "cropRow",
                "decodedWidth",
                "decodedHeight",
                "dataSource",
                "sampled",
            )
    private val url = Regex("(?i)https?://[^\\s]+")
    private val credential =
        Regex("(?i)\\b(token|access[_-]?token|api[_-]?key|authorization|secret|password)=([^\\s]+)")
    private val bearer = Regex("(?i)\\bbearer\\s+[^\\s]+")
    private val absolutePath = Regex("(?i)(?:[A-Z]:[\\\\/]|/)[^\\s]+")
    private val serverAddress =
        Regex("(?i)\\b(?:(?:\\d{1,3}\\.){3}\\d{1,3}|localhost|[a-z0-9][a-z0-9.-]*\\.(?:local|lan|internal))(?::\\d+)?\\b")

    fun capture(
        tag: String,
        message: String,
    ): String? {
        if (tag == DiagnosticTag.JellyScopePlaybackProbe.wireValue) {
            return captureDesktopPlaybackProbe(message)
        }
        if (tag !in ordinaryAllowedTags ||
            !diagnosticMessage.matches(message) ||
            message.split(' ').any { field -> field.substringBefore('=') !in allowedFields }
        ) {
            return null
        }

        return message
            .replace(url, "<redacted-url>")
            .replace(credential) { match -> "${match.groupValues[1]}=<redacted>" }
            .replace(bearer, "Bearer <redacted>")
            .replace(absolutePath, "<redacted-path>")
            .replace(serverAddress, "<redacted-server>")
    }

    private fun captureDesktopPlaybackProbe(message: String): String? {
        if (message.length > MAX_PROBE_MESSAGE_LENGTH || !diagnosticMessage.matches(message)) return null
        val entries = message.split(' ').map { field -> field.substringBefore('=') to field.substringAfter('=') }
        if (entries.any { (name, value) -> name.isEmpty() || value.isEmpty() }) return null
        if (entries.map { entry -> entry.first }.toSet().size != entries.size) return null
        if (entries.any { (_, value) -> desktopProbeForbiddenValue.containsMatchIn(value) }) return null
        val fields = entries.toMap()
        if (fields["stage"] != "probe") return null
        val schema = desktopProbeSchemas[fields["event"]] ?: return null
        if (fields.keys != schema) return null
        if (fields.any { (name, value) -> !validDesktopProbeField(name, value) }) return null
        return message
    }

    private fun validDesktopProbeField(
        name: String,
        value: String,
    ): Boolean =
        when (name) {
            "stage" -> value == "probe"
            "event" -> value in desktopProbeSchemas
            "surface" -> value in setOf("LIBVLC", "IOSURFACE", "OPENGL")
            "action" -> value in desktopProbeActions
            "source" -> value in setOf("COMPOSE", "AWT")
            "inputEvent" -> value in setOf("PRESS", "RELEASE", "CANCEL", "CLICK", "TOOLTIP_SHOWN", "TOOLTIP_HIDDEN")
            "comparison" -> value == "SUPPORTED" || value == "UNSUPPORTED"
            "windowEvent" -> value in setOf("PLACEMENT", "ACTIVATION", "FOCUS")
            "placement" -> value in setOf("FLOATING", "MAXIMIZED", "FULLSCREEN", "unchanged")
            "active", "focused", "visible", "accepted", "resource" -> value == "true" || value == "false"
            "nativeIds" -> value == "none" || desktopProbeIntegerList.matches(value)
            "classes" -> value == "none" || desktopProbeTokenList.matches(value)
            in desktopProbeNumericFields -> desktopProbeInteger.matches(value)
            else -> desktopProbeToken.matches(value)
        }

    private val desktopProbeSchemas =
        mapOf(
            "mpvStatus" to
                probeSchema(
                    "status",
                    "positionMs",
                    "durationMs",
                    "streamMode",
                    "presentation",
                    "hwdecRequested",
                    "hwdecResolved",
                    "decoder",
                    "width",
                    "height",
                    "frameRateMilli",
                    "dropped",
                    "decoderDropped",
                    "outputDropped",
                ),
            "vlc" to
                probeSchema(
                    "action",
                    "result",
                    "mode",
                    "delivery",
                    "detail",
                    "targetId",
                    "nativeResult",
                    "selectedId",
                    "readbackId",
                    "positionMs",
                    "ordinal",
                    "count",
                    "candidateId",
                    "accepted",
                    "resource",
                    "nativeIds",
                ),
            "surfaceLifecycle" to
                probeSchema("surface", "action", "visible", "generation", "width", "height"),
            "surfaceTiming" to
                probeSchema("frames", "renderMaxMicros", "presentMaxMicros"),
            "surfaceFailure" to
                probeSchema(
                    "surface",
                    "failure",
                    "failureStage",
                    "reason",
                    "errorCode",
                    "action",
                    "generation",
                    "width",
                    "height",
                ),
            "hierarchy" to probeSchema("owner", "count", "layeredCount", "classes"),
            "input" to
                probeSchema(
                    "sequence",
                    "timeNanos",
                    "source",
                    "target",
                    "inputEvent",
                    "coordinateSpace",
                    "x",
                    "y",
                    "densityMilli",
                    "comparison",
                    "offsetX",
                    "offsetY",
                ),
            "window" to probeSchema("windowEvent", "placement", "active", "focused"),
        )

    private fun probeSchema(vararg names: String): Set<String> = setOf("stage", "event", *names)

    private val desktopProbeNumericFields =
        setOf(
            "positionMs",
            "durationMs",
            "width",
            "height",
            "frameRateMilli",
            "dropped",
            "decoderDropped",
            "outputDropped",
            "targetId",
            "nativeResult",
            "selectedId",
            "readbackId",
            "ordinal",
            "count",
            "candidateId",
            "generation",
            "frames",
            "renderMaxMicros",
            "presentMaxMicros",
            "errorCode",
            "layeredCount",
            "sequence",
            "timeNanos",
            "x",
            "y",
            "densityMilli",
            "offsetX",
            "offsetY",
        )
    private val desktopProbeActions =
        setOf(
            "PREPARE_START",
            "EXTERNAL_SUBTITLE",
            "AUDIO_SET",
            "SUBTITLE_SET",
            "COMMAND",
            "PREPARE",
            "START_POSITION",
            "AUDIO_COHORT",
            "AUDIO_RESOLUTION",
            "SUBTITLE_MAPPING",
            "PLAYING_TRACKS",
            "PRESENTATION_READY",
            "FAILURE",
            "VISIBLE",
            "HIDDEN",
            "RESIZE",
            "ATTACH",
            "FORCED",
            "unknown",
            "useOpenGl",
            "keepLastGood",
        )
    private val desktopProbeInteger = Regex("-?[0-9]{1,16}")
    private val desktopProbeIntegerList = Regex("-?[0-9]{1,10}(?:,-?[0-9]{1,10}){0,31}")
    private val desktopProbeToken = Regex("[A-Za-z0-9][A-Za-z0-9._:+-]{0,79}")
    private val desktopProbeTokenList =
        Regex("[A-Za-z0-9][A-Za-z0-9._:+-]{0,79}(?:,[A-Za-z0-9][A-Za-z0-9._:+-]{0,79}){0,15}")
    private val desktopProbeForbiddenValue =
        Regex(
            "(?i)(?:https?:|authorization|bearer|password|secret|access[_-]?token|api[_-]?key|username|jellyscopeplaybackprobe|stage:|event:)",
        )

    private const val MAX_PROBE_MESSAGE_LENGTH = 2_048
}
