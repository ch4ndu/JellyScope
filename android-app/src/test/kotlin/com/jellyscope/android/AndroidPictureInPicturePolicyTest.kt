// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.android

import com.jellyscope.core.util.LogScrubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AndroidPictureInPicturePolicyTest {
    @Test
    fun plannedVideoDimensionsProduceNormalizedStableAspects() {
        assertEquals(
            AndroidPictureInPictureAspect(959, 540),
            androidPictureInPictureAspect(3836, 2160),
        )
        assertEquals(
            AndroidPictureInPictureAspect(16, 9),
            androidPictureInPictureAspect(1920, 1080),
        )
        assertEquals(
            AndroidPictureInPictureAspect(9, 16),
            androidPictureInPictureAspect(1080, 1920),
        )
        assertEquals(
            AndroidPictureInPictureAspect(1, 1),
            androidPictureInPictureAspect(Int.MAX_VALUE, Int.MAX_VALUE),
        )
    }

    @Test
    fun missingOrInvalidDimensionsUseTheStableFallback() {
        val fallback = AndroidPictureInPictureAspect(16, 9)

        assertEquals(fallback, androidPictureInPictureAspect(null, null))
        assertEquals(fallback, androidPictureInPictureAspect(null, 1080))
        assertEquals(fallback, androidPictureInPictureAspect(1920, null))
        assertEquals(fallback, androidPictureInPictureAspect(0, 1080))
        assertEquals(fallback, androidPictureInPictureAspect(1920, 0))
        assertEquals(fallback, androidPictureInPictureAspect(-1920, 1080))
        assertEquals(fallback, androidPictureInPictureAspect(1920, -1080))
    }

    @Test
    fun extremeAspectsClampToExactAndroidBounds() {
        assertEquals(
            AndroidPictureInPictureAspect(239, 100),
            androidPictureInPictureAspect(1000, 1),
        )
        assertEquals(
            AndroidPictureInPictureAspect(100, 239),
            androidPictureInPictureAspect(1, 1000),
        )
        assertEquals(
            AndroidPictureInPictureAspect(239, 100),
            androidPictureInPictureAspect(239, 100),
        )
        assertEquals(
            AndroidPictureInPictureAspect(100, 239),
            androidPictureInPictureAspect(100, 239),
        )
        assertEquals(
            AndroidPictureInPictureAspect(239, 100),
            androidPictureInPictureAspect(Int.MAX_VALUE, 1),
        )
        assertEquals(
            AndroidPictureInPictureAspect(100, 239),
            androidPictureInPictureAspect(1, Int.MAX_VALUE),
        )
    }

    @Test
    fun changingBoundsNeverReadmitAnAspectBearingUpdate() {
        val gate = AndroidPictureInPictureAttemptGate()
        val signature = pausedSignature()
        val fallbackHint = AndroidPictureInPictureSourceHint(0, 0, 16, 9)

        assertEquals(AndroidPictureInPictureUpdate.Full, gate.nextUpdate(signature, fallbackHint))

        var fullUpdateCount = 1
        repeat(20) { offset ->
            val update =
                gate.nextUpdate(
                    signature = signature,
                    sourceHint = AndroidPictureInPictureSourceHint(0, offset + 1, 1080, 1800 - offset),
                )
            if (update == AndroidPictureInPictureUpdate.Full) {
                fullUpdateCount += 1
            }
            assertEquals(AndroidPictureInPictureUpdate.SourceHintOnly, update)
        }

        assertEquals(1, fullUpdateCount)
    }

    @Test
    fun firstRealHintIsSeparateAndRepeatedHintsAreSuppressed() {
        val gate = AndroidPictureInPictureAttemptGate()
        val signature = pausedSignature()
        val fallbackHint = AndroidPictureInPictureSourceHint(0, 0, 16, 9)
        val realHint = AndroidPictureInPictureSourceHint(0, 200, 1080, 1800)

        assertEquals(AndroidPictureInPictureUpdate.Full, gate.nextUpdate(signature, fallbackHint))
        assertEquals(AndroidPictureInPictureUpdate.SourceHintOnly, gate.nextUpdate(signature, realHint))
        assertEquals(AndroidPictureInPictureUpdate.None, gate.nextUpdate(signature, realHint))
    }

    @Test
    fun attemptedFullUpdateStaysClosedUntilSemanticsChangeOrSessionResets() {
        val gate = AndroidPictureInPictureAttemptGate()
        val sourceHint = AndroidPictureInPictureSourceHint(0, 200, 1080, 1800)
        val paused = pausedSignature()
        val playing =
            androidPictureInPictureSignature(
                aspect = paused.aspect,
                isPlaying = true,
                autoEnterRequested = true,
                autoEnterSupported = true,
            )

        assertEquals(AndroidPictureInPictureUpdate.Full, gate.nextUpdate(paused, sourceHint))
        // The gate records before the external call, so a caller-side failure cannot reopen it.
        assertEquals(AndroidPictureInPictureUpdate.None, gate.nextUpdate(paused, sourceHint))
        assertEquals(AndroidPictureInPictureUpdate.Full, gate.nextUpdate(playing, sourceHint))

        gate.reset()

        assertEquals(AndroidPictureInPictureUpdate.Full, gate.nextUpdate(playing, sourceHint))
    }

    @Test
    fun autoEnterAffectsTheSignatureOnlyOnSupportedApis() {
        val aspect = AndroidPictureInPictureAspect(16, 9)
        val olderDisabled = androidPictureInPictureSignature(aspect, true, false, false)
        val olderEnabled = androidPictureInPictureSignature(aspect, true, true, false)
        val modernDisabled = androidPictureInPictureSignature(aspect, true, false, true)
        val modernEnabled = androidPictureInPictureSignature(aspect, true, true, true)
        val olderPaused = androidPictureInPictureSignature(aspect, false, true, false)

        assertEquals(olderDisabled, olderEnabled)
        assertNotEquals(modernDisabled, modernEnabled)
        assertNotEquals(olderEnabled, olderPaused)
    }

    @Test
    fun pictureInPictureDiagnosticsAreFixedCaptureCompatibleAndIdentityFree() {
        val cause = IllegalArgumentException("private cause")
        val failure = IllegalStateException("private item path and token", cause)

        AndroidPictureInPictureFailureEvent.entries.forEach { event ->
            val diagnostic = androidPictureInPictureFailureDiagnostic(event, failure)

            assertEquals(
                diagnostic,
                LogScrubber.capture(ANDROID_PICTURE_IN_PICTURE_LOG_TAG, diagnostic),
            )
            assertFalse(diagnostic.contains(failure.message.orEmpty()))
            assertFalse(diagnostic.contains(cause.message.orEmpty()))
            assertFalse(diagnostic.contains("IllegalArgumentException"))
        }
        assertEquals(
            ANDROID_PICTURE_IN_PICTURE_ENTER_REJECTED_DIAGNOSTIC,
            LogScrubber.capture(
                ANDROID_PICTURE_IN_PICTURE_LOG_TAG,
                ANDROID_PICTURE_IN_PICTURE_ENTER_REJECTED_DIAGNOSTIC,
            ),
        )
    }

    private fun pausedSignature(): AndroidPictureInPictureSignature =
        androidPictureInPictureSignature(
            aspect = AndroidPictureInPictureAspect(16, 9),
            isPlaying = false,
            autoEnterRequested = true,
            autoEnterSupported = true,
        )
}
