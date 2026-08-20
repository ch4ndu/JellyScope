// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.android

import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.util.formatSafeFailureDiagnostic

internal data class AndroidPictureInPictureAspect(
    val numerator: Int,
    val denominator: Int,
) {
    init {
        require(numerator > 0 && denominator > 0)
        require(
            numerator.toLong() * MAX_PIP_ASPECT_DENOMINATOR <=
                denominator.toLong() * MAX_PIP_ASPECT_NUMERATOR,
        )
        require(
            numerator.toLong() * MAX_PIP_ASPECT_NUMERATOR >=
                denominator.toLong() * MAX_PIP_ASPECT_DENOMINATOR,
        )
        require(greatestCommonDivisor(numerator, denominator) == 1)
    }
}

internal data class AndroidPictureInPictureSignature(
    val aspect: AndroidPictureInPictureAspect,
    val isPlaying: Boolean,
    val autoEnterEnabled: Boolean,
)

internal data class AndroidPictureInPictureSourceHint(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal enum class AndroidPictureInPictureUpdate {
    Full,
    SourceHintOnly,
    None,
}

internal enum class AndroidPictureInPictureTransportAction {
    Play,
    Pause,
}

internal fun androidPictureInPictureTransportAction(status: PlaybackStatus): AndroidPictureInPictureTransportAction =
    when (status) {
        PlaybackStatus.Playing,
        PlaybackStatus.Buffering,
        -> AndroidPictureInPictureTransportAction.Pause

        else -> AndroidPictureInPictureTransportAction.Play
    }

internal class AndroidPictureInPictureAttemptGate {
    private var lastSignature: AndroidPictureInPictureSignature? = null
    private var lastSourceHint: AndroidPictureInPictureSourceHint? = null

    fun nextUpdate(
        signature: AndroidPictureInPictureSignature,
        sourceHint: AndroidPictureInPictureSourceHint,
    ): AndroidPictureInPictureUpdate {
        if (signature != lastSignature) {
            lastSignature = signature
            lastSourceHint = sourceHint
            return AndroidPictureInPictureUpdate.Full
        }
        if (sourceHint != lastSourceHint) {
            lastSourceHint = sourceHint
            return AndroidPictureInPictureUpdate.SourceHintOnly
        }
        return AndroidPictureInPictureUpdate.None
    }

    fun reset() {
        lastSignature = null
        lastSourceHint = null
    }
}

internal fun androidPictureInPictureAspect(
    width: Int?,
    height: Int?,
): AndroidPictureInPictureAspect {
    val positiveWidth = width?.takeIf { value -> value > 0 }
    val positiveHeight = height?.takeIf { value -> value > 0 }
    if (positiveWidth == null || positiveHeight == null) {
        return AndroidPictureInPictureAspect(
            numerator = DEFAULT_PIP_WIDTH,
            denominator = DEFAULT_PIP_HEIGHT,
        )
    }

    if (positiveWidth.toLong() * MAX_PIP_ASPECT_DENOMINATOR >
        positiveHeight.toLong() * MAX_PIP_ASPECT_NUMERATOR
    ) {
        return AndroidPictureInPictureAspect(
            numerator = MAX_PIP_ASPECT_NUMERATOR,
            denominator = MAX_PIP_ASPECT_DENOMINATOR,
        )
    }
    if (positiveWidth.toLong() * MAX_PIP_ASPECT_NUMERATOR <
        positiveHeight.toLong() * MAX_PIP_ASPECT_DENOMINATOR
    ) {
        return AndroidPictureInPictureAspect(
            numerator = MAX_PIP_ASPECT_DENOMINATOR,
            denominator = MAX_PIP_ASPECT_NUMERATOR,
        )
    }

    val divisor = greatestCommonDivisor(positiveWidth, positiveHeight)
    return AndroidPictureInPictureAspect(
        numerator = positiveWidth / divisor,
        denominator = positiveHeight / divisor,
    )
}

internal fun androidPictureInPictureSignature(
    aspect: AndroidPictureInPictureAspect,
    isPlaying: Boolean,
    autoEnterRequested: Boolean,
    autoEnterSupported: Boolean,
): AndroidPictureInPictureSignature =
    AndroidPictureInPictureSignature(
        aspect = aspect,
        isPlaying = isPlaying,
        autoEnterEnabled = autoEnterSupported && autoEnterRequested && isPlaying,
    )

internal enum class AndroidPictureInPictureFailureEvent(
    val diagnosticValue: String,
) {
    SetParams("set-params-failed"),
    Enter("enter-failed"),
    DisableAutoEnter("disable-auto-enter-failed"),
}

internal fun androidPictureInPictureFailureDiagnostic(
    event: AndroidPictureInPictureFailureEvent,
    throwable: Throwable,
): String =
    formatSafeFailureDiagnostic(
        stage = PICTURE_IN_PICTURE_DIAGNOSTIC_STAGE,
        event = event.diagnosticValue,
        throwable = throwable,
    )

internal const val ANDROID_PICTURE_IN_PICTURE_LOG_TAG = "AndroidPlaybackModule"
internal const val ANDROID_PICTURE_IN_PICTURE_ENTER_REJECTED_DIAGNOSTIC =
    "stage=picture-in-picture event=enter-rejected"

private fun greatestCommonDivisor(
    first: Int,
    second: Int,
): Int {
    var left = first
    var right = second
    while (right != 0) {
        val remainder = left % right
        left = right
        right = remainder
    }
    return left
}

private const val PICTURE_IN_PICTURE_DIAGNOSTIC_STAGE = "picture-in-picture"
private const val MAX_PIP_ASPECT_NUMERATOR = 239
private const val MAX_PIP_ASPECT_DENOMINATOR = 100
private const val DEFAULT_PIP_WIDTH = 16
private const val DEFAULT_PIP_HEIGHT = 9
