// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

const val DESKTOP_PLAYBACK_PROBE_PROPERTY = "jellyscope.desktop.playbackProbeLog"

/** Process-lifetime desktop probe gate and the only probe logging sink. */
object DesktopPlaybackProbe {
    private val preferenceEnabled = AtomicBoolean(false)
    private val cliForced =
        System.getProperty(DESKTOP_PLAYBACK_PROBE_PROPERTY)?.toBooleanStrictOrNull() == true
    private val logger = diagnosticLogger(DiagnosticTag.JellyScopePlaybackProbe)

    val isEnabled: Boolean
        get() = cliForced || preferenceEnabled.get()

    fun setPreferenceEnabled(enabled: Boolean) {
        preferenceEnabled.set(enabled)
    }

    fun emit(record: DesktopPlaybackProbeRecord) {
        if (!isEnabled) return
        logger.i { record.serialized }
    }
}

@JvmInline
value class DesktopProbeToken private constructor(
    val value: String,
) {
    companion object {
        fun from(value: Any?): DesktopProbeToken {
            val raw = value?.toString()?.trim()
            val normalized =
                raw
                    ?.takeUnless(::isUnsafeDesktopProbeToken)
                    ?.trim()
                    ?.take(MAX_TOKEN_LENGTH)
                    ?.map { character ->
                        if (character.isLetterOrDigit() || character in TOKEN_PUNCTUATION) character else '_'
                    }?.joinToString("")
                    ?.trim('_', '.', '-')
                    ?.takeIf(String::isNotEmpty)
                    ?: "unknown"
            return DesktopProbeToken(normalized)
        }
    }
}

private fun isUnsafeDesktopProbeToken(value: String): Boolean {
    val lower = value.lowercase()
    return value.any { it == '/' || it == '\\' || it == '@' } ||
        "://" in value ||
        listOf("authorization", "bearer", "password", "secret", "access_token", "api_key", "username")
            .any(lower::contains)
}

sealed interface DesktopPlaybackProbeRecord {
    val serialized: String
}

data class MpvStatusProbeRecord(
    val status: DesktopProbeToken,
    val positionMs: Long,
    val durationMs: Long?,
    val streamMode: DesktopProbeToken,
    val presentation: DesktopProbeToken,
    val hardwareDecodeRequested: DesktopProbeToken,
    val hardwareDecodeResolved: DesktopProbeToken,
    val decoder: DesktopProbeToken,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val droppedFrames: Long?,
    val decoderDroppedFrames: Long?,
    val outputDroppedFrames: Long?,
) : DesktopPlaybackProbeRecord {
    override val serialized: String
        get() =
            probeMessage(
                "mpvStatus",
                "status=${status.value}",
                "positionMs=${positionMs.boundedLong()}",
                "durationMs=${durationMs?.boundedLong() ?: -1}",
                "streamMode=${streamMode.value}",
                "presentation=${presentation.value}",
                "hwdecRequested=${hardwareDecodeRequested.value}",
                "hwdecResolved=${hardwareDecodeResolved.value}",
                "decoder=${decoder.value}",
                "width=${width.boundedInt()}",
                "height=${height.boundedInt()}",
                "frameRateMilli=${frameRate.boundedMilli()}",
                "dropped=${droppedFrames.boundedCount()}",
                "decoderDropped=${decoderDroppedFrames.boundedCount()}",
                "outputDropped=${outputDroppedFrames.boundedCount()}",
            )
}

enum class DesktopVlcProbeAction {
    PREPARE_START,
    EXTERNAL_SUBTITLE,
    AUDIO_SET,
    SUBTITLE_SET,
    COMMAND,
    PREPARE,
    START_POSITION,
    AUDIO_COHORT,
    AUDIO_RESOLUTION,
    SUBTITLE_MAPPING,
    PLAYING_TRACKS,
    PRESENTATION_READY,
    FAILURE,
}

data class DesktopVlcProbeRecord(
    val action: DesktopVlcProbeAction,
    val result: DesktopProbeToken = DesktopProbeToken.from(null),
    val mode: DesktopProbeToken = DesktopProbeToken.from(null),
    val delivery: DesktopProbeToken = DesktopProbeToken.from(null),
    val detail: DesktopProbeToken = DesktopProbeToken.from(null),
    val targetId: Int? = null,
    val nativeResult: Int? = null,
    val selectedId: Int? = null,
    val readbackId: Int? = null,
    val positionMs: Long? = null,
    val ordinal: Int? = null,
    val count: Int? = null,
    val candidateId: Int? = null,
    val accepted: Boolean? = null,
    val resource: Boolean? = null,
    val nativeIds: List<Int> = emptyList(),
) : DesktopPlaybackProbeRecord {
    override val serialized: String
        get() =
            probeMessage(
                "vlc",
                "action=${action.name}",
                "result=${result.value}",
                "mode=${mode.value}",
                "delivery=${delivery.value}",
                "detail=${detail.value}",
                "targetId=${targetId ?: -1}",
                "nativeResult=${nativeResult ?: -1}",
                "selectedId=${selectedId ?: -1}",
                "readbackId=${readbackId ?: -1}",
                "positionMs=${positionMs?.boundedLong() ?: -1}",
                "ordinal=${ordinal ?: -1}",
                "count=${count?.coerceIn(-1, MAX_COUNT) ?: -1}",
                "candidateId=${candidateId ?: -1}",
                "accepted=${accepted ?: false}",
                "resource=${resource ?: false}",
                "nativeIds=${nativeIds.take(MAX_ID_LIST).joinToString(",").ifEmpty { "none" }}",
            )
}

enum class DesktopSurfaceKind { LIBVLC, IOSURFACE, OPENGL }

enum class DesktopSurfaceAction { VISIBLE, HIDDEN, RESIZE, ATTACH, FORCED }

data class DesktopSurfaceLifecycleProbeRecord(
    val surface: DesktopSurfaceKind,
    val action: DesktopSurfaceAction,
    val visible: Boolean = true,
    val generation: Long = -1,
    val width: Int = -1,
    val height: Int = -1,
) : DesktopPlaybackProbeRecord {
    override val serialized: String
        get() =
            probeMessage(
                "surfaceLifecycle",
                "surface=${surface.name}",
                "action=${action.name}",
                "visible=$visible",
                "generation=${generation.boundedLong()}",
                "width=${width.coerceIn(-1, MAX_DIMENSION)}",
                "height=${height.coerceIn(-1, MAX_DIMENSION)}",
            )
}

data class DesktopSurfaceTimingProbeRecord(
    val frames: Int,
    val renderMaxMicros: Long,
    val presentMaxMicros: Long,
) : DesktopPlaybackProbeRecord {
    override val serialized: String
        get() =
            probeMessage(
                "surfaceTiming",
                "frames=${frames.coerceIn(0, MAX_COUNT)}",
                "renderMaxMicros=${renderMaxMicros.boundedLong()}",
                "presentMaxMicros=${presentMaxMicros.boundedLong()}",
            )
}

data class DesktopSurfaceFailureProbeRecord(
    val surface: DesktopSurfaceKind,
    val failure: DesktopProbeToken,
    val failureStage: DesktopProbeToken = DesktopProbeToken.from(null),
    val reason: DesktopProbeToken = DesktopProbeToken.from(null),
    val errorCode: Int = 0,
    val action: DesktopProbeToken = DesktopProbeToken.from(null),
    val generation: Long = -1,
    val width: Int = -1,
    val height: Int = -1,
) : DesktopPlaybackProbeRecord {
    override val serialized: String
        get() =
            probeMessage(
                "surfaceFailure",
                "surface=${surface.name}",
                "failure=${failure.value}",
                "failureStage=${failureStage.value}",
                "reason=${reason.value}",
                "errorCode=$errorCode",
                "action=${action.value}",
                "generation=${generation.boundedLong()}",
                "width=${width.coerceIn(-1, MAX_DIMENSION)}",
                "height=${height.coerceIn(-1, MAX_DIMENSION)}",
            )
}

data class DesktopHierarchyProbeRecord(
    val owner: DesktopProbeToken,
    val count: Int,
    val layeredCount: Int,
    val classes: List<DesktopProbeToken>,
) : DesktopPlaybackProbeRecord {
    override val serialized: String
        get() =
            probeMessage(
                "hierarchy",
                "owner=${owner.value}",
                "count=${count.coerceIn(0, MAX_COUNT)}",
                "layeredCount=${layeredCount.coerceIn(0, MAX_COUNT)}",
                "classes=${classes.take(MAX_CLASS_LIST).joinToString(",") { it.value }.ifEmpty { "none" }}",
            )
}

data class DesktopInputProbeRecord(
    val sequence: Long,
    val timeNanos: Long,
    val source: DesktopProbeToken,
    val target: DesktopProbeToken,
    val inputEvent: DesktopProbeToken,
    val coordinateSpace: DesktopProbeToken,
    val x: Int,
    val y: Int,
    val densityMilli: Int,
    val comparison: DesktopProbeToken,
    val offsetX: Int,
    val offsetY: Int,
) : DesktopPlaybackProbeRecord {
    override val serialized: String
        get() =
            probeMessage(
                "input",
                "sequence=${sequence.boundedLong()}",
                "timeNanos=${timeNanos.boundedLong()}",
                "source=${source.value}",
                "target=${target.value}",
                "inputEvent=${inputEvent.value}",
                "coordinateSpace=${coordinateSpace.value}",
                "x=$x",
                "y=$y",
                "densityMilli=$densityMilli",
                "comparison=${comparison.value}",
                "offsetX=$offsetX",
                "offsetY=$offsetY",
            )
}

data class DesktopWindowProbeRecord(
    val windowEvent: DesktopProbeToken,
    val placement: DesktopProbeToken,
    val active: Boolean,
    val focused: Boolean,
) : DesktopPlaybackProbeRecord {
    override val serialized: String
        get() =
            probeMessage(
                "window",
                "windowEvent=${windowEvent.value}",
                "placement=${placement.value}",
                "active=$active",
                "focused=$focused",
            )
}

private fun probeMessage(
    event: String,
    vararg fields: String,
): String =
    buildString {
        append("stage=probe event=")
        append(event)
        fields.forEach { field ->
            append(' ')
            append(field)
        }
    }

private fun Long.boundedLong(): Long = coerceIn(-1, MAX_LONG_VALUE)

private fun Long?.boundedCount(): Long = this?.coerceIn(-1, MAX_LONG_VALUE) ?: -1

private fun Int?.boundedInt(): Int = this?.coerceIn(-1, MAX_DIMENSION) ?: -1

private fun Double?.boundedMilli(): Int =
    this
        ?.takeIf { it.isFinite() }
        ?.times(1_000.0)
        ?.roundToInt()
        ?.coerceIn(-1, MAX_FRAME_RATE_MILLI)
        ?: -1

private const val MAX_TOKEN_LENGTH = 80
private const val MAX_DIMENSION = 65_535
private const val MAX_FRAME_RATE_MILLI = 1_000_000
private const val MAX_COUNT = 1_000_000
private const val MAX_LONG_VALUE = 9_999_999_999_999_999L
private const val MAX_ID_LIST = 32
private const val MAX_CLASS_LIST = 16
private val TOKEN_PUNCTUATION = setOf('.', '_', ':', '+', '-')
