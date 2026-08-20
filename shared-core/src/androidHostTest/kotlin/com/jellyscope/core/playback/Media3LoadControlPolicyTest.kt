// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackBufferPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Media3LoadControlPolicyTest {
    @Test
    fun unknownLowRamClassificationUsesConservativeControl() {
        val policy =
            createMedia3LoadControlPolicy(
                isLowRamDevice = null,
                maxHeapBytes = 512L * 1024L * 1024L,
                useRegularCandidate = true,
            )

        assertEquals(Media3MemoryClass.LowRam, policy.memoryClass)
        assertEquals(PlaybackBufferPolicy.LowRam16MiB, policy.diagnosticPolicy)
        assertEquals(MEDIA3_CONTROL_TARGET_BUFFER_BYTES, policy.targetBufferBytes)
        assertTrue(policy.prioritizeTimeOverSizeThresholds)
    }

    @Test
    fun regularDevicesRetainControlTargetUntilCandidateIsExplicitlyEnabled() {
        val policy =
            createMedia3LoadControlPolicy(
                isLowRamDevice = false,
                maxHeapBytes = 768L * 1024L * 1024L,
            )

        assertEquals(Media3MemoryClass.Regular, policy.memoryClass)
        assertEquals(PlaybackBufferPolicy.Regular16MiB, policy.diagnosticPolicy)
        assertEquals(MEDIA3_CONTROL_TARGET_BUFFER_BYTES, policy.targetBufferBytes)
        assertEquals(8_000, policy.minBufferMs)
        assertEquals(20_000, policy.maxBufferMs)
        assertEquals(1_000, policy.bufferForPlaybackMs)
        assertEquals(2_000, policy.bufferAfterRebufferMs)
    }

    @Test
    fun regularCandidateIsOneThirdOfHeapAndCapped() {
        assertEquals(
            256 * 1024 * 1024,
            regularCandidateTargetBufferBytes(768L * 1024L * 1024L),
        )
        assertEquals(
            MEDIA3_REGULAR_TARGET_CAP_BYTES,
            regularCandidateTargetBufferBytes(Long.MAX_VALUE),
        )
        val invalidHeapPolicy =
            createMedia3LoadControlPolicy(
                isLowRamDevice = false,
                maxHeapBytes = -1L,
                useRegularCandidate = true,
            )
        assertEquals(Media3MemoryClass.LowRam, invalidHeapPolicy.memoryClass)
        assertEquals(PlaybackBufferPolicy.LowRam16MiB, invalidHeapPolicy.diagnosticPolicy)
        assertEquals(
            MEDIA3_CONTROL_TARGET_BUFFER_BYTES,
            invalidHeapPolicy.targetBufferBytes,
        )
    }
}
