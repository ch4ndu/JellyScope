// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/** Closed transfer characteristics; any other native name is not recognized and stays null. */
enum class PlaybackColorTransfer {
    Pq,
    Hlg,
    Bt1886,
    Srgb,
    PowerGamma,
    Linear,
}

/** Closed color primaries; any other native name is not recognized and stays null. */
enum class PlaybackColorPrimaries {
    Bt601,
    Bt709,
    Bt2020,
    DciP3,
    DisplayP3,
}

/** Dynamic range of the renderer target only; never a statement about the physical display. */
enum class PlaybackRendererRange {
    Hdr,
    Sdr,
    Unknown,
}

enum class PlaybackOutputColorEvidence {
    /** The native renderer reported a target for the current video. */
    NativeTarget,
    Unavailable,
}

/**
 * [HdrInputToSdrTarget] is the observed input/target pair only. It does not identify a
 * tone-mapping algorithm, prove that one ran, or prove the displayed luminance.
 */
enum class PlaybackOutputColorConversion {
    HdrInputToSdrTarget,
    NotIndicated,
    Unknown,
}

/** No backend can confirm what the physical display presented, so this has a single value. */
enum class PlaybackPhysicalDisplayConfirmation {
    Unknown,
}

/**
 * Observational renderer-target color facts for one coherent native sample. They never feed
 * planning, stream quality, health/recovery, capability caches, backend selection, transcode or
 * request policy.
 *
 * Invariants: with [PlaybackOutputColorEvidence.Unavailable] both target fields are null and
 * range and conversion are Unknown, while the input fields may still describe the current
 * video's decoded parameters from the same sample. With [PlaybackOutputColorEvidence.NativeTarget]
 * a null target field means the native name was not recognized. Null input fields mean not
 * observed or not recognized. Unrecognized or missing evidence never defaults to SDR.
 */
@ConsistentCopyVisibility
data class PlaybackOutputColor private constructor(
    val inputTransfer: PlaybackColorTransfer?,
    val inputPrimaries: PlaybackColorPrimaries?,
    val targetTransfer: PlaybackColorTransfer?,
    val targetPrimaries: PlaybackColorPrimaries?,
    val rendererRange: PlaybackRendererRange,
    val evidence: PlaybackOutputColorEvidence,
    val conversion: PlaybackOutputColorConversion,
) {
    val physicalDisplay: PlaybackPhysicalDisplayConfirmation
        get() = PlaybackPhysicalDisplayConfirmation.Unknown

    companion object {
        val Unknown =
            PlaybackOutputColor(
                inputTransfer = null,
                inputPrimaries = null,
                targetTransfer = null,
                targetPrimaries = null,
                rendererRange = PlaybackRendererRange.Unknown,
                evidence = PlaybackOutputColorEvidence.Unavailable,
                conversion = PlaybackOutputColorConversion.Unknown,
            )

        /**
         * Normalizes one coherent mpv sample. `video-target-params` is the renderer target;
         * `video-out-params` (filter output), configured `target-trc`, source metadata and the
         * presentation path are deliberately not inputs. A null target transfer means the
         * native property was absent or failed.
         */
        @OptIn(ExperimentalObjCRefinement::class)
        @HiddenFromObjC
        fun fromMpv(
            videoPresent: Boolean,
            inputTransfer: String?,
            inputPrimaries: String?,
            targetTransfer: String?,
            targetPrimaries: String?,
        ): PlaybackOutputColor {
            if (!videoPresent) return Unknown
            val input = mpvTransfer(inputTransfer)
            val inputColorPrimaries = mpvPrimaries(inputPrimaries)
            if (targetTransfer == null) {
                return Unknown.copy(inputTransfer = input, inputPrimaries = inputColorPrimaries)
            }
            val target = mpvTransfer(targetTransfer)
            val range = target.dynamicRange()
            val inputRange = input.dynamicRange()
            return PlaybackOutputColor(
                inputTransfer = input,
                inputPrimaries = inputColorPrimaries,
                targetTransfer = target,
                targetPrimaries = mpvPrimaries(targetPrimaries),
                rendererRange = range,
                evidence = PlaybackOutputColorEvidence.NativeTarget,
                conversion =
                    when {
                        range == PlaybackRendererRange.Unknown || inputRange == PlaybackRendererRange.Unknown ->
                            PlaybackOutputColorConversion.Unknown
                        inputRange == PlaybackRendererRange.Hdr && range == PlaybackRendererRange.Sdr ->
                            PlaybackOutputColorConversion.HdrInputToSdrTarget
                        else -> PlaybackOutputColorConversion.NotIndicated
                    },
            )
        }
    }
}

/** Linear alone cannot distinguish extended-range HDR from ordinary SDR, so it stays Unknown. */
private fun PlaybackColorTransfer?.dynamicRange(): PlaybackRendererRange =
    when (this) {
        PlaybackColorTransfer.Pq, PlaybackColorTransfer.Hlg -> PlaybackRendererRange.Hdr
        PlaybackColorTransfer.Bt1886, PlaybackColorTransfer.Srgb, PlaybackColorTransfer.PowerGamma ->
            PlaybackRendererRange.Sdr
        PlaybackColorTransfer.Linear, null -> PlaybackRendererRange.Unknown
    }

private fun mpvTransfer(name: String?): PlaybackColorTransfer? =
    when (name) {
        "pq" -> PlaybackColorTransfer.Pq
        "hlg" -> PlaybackColorTransfer.Hlg
        "bt.1886" -> PlaybackColorTransfer.Bt1886
        "srgb" -> PlaybackColorTransfer.Srgb
        "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8" -> PlaybackColorTransfer.PowerGamma
        "linear" -> PlaybackColorTransfer.Linear
        else -> null
    }

private fun mpvPrimaries(name: String?): PlaybackColorPrimaries? =
    when (name) {
        "bt.601-525", "bt.601-625" -> PlaybackColorPrimaries.Bt601
        "bt.709" -> PlaybackColorPrimaries.Bt709
        "bt.2020" -> PlaybackColorPrimaries.Bt2020
        "dci-p3" -> PlaybackColorPrimaries.DciP3
        "display-p3" -> PlaybackColorPrimaries.DisplayP3
        else -> null
    }
