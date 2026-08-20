// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackDecoderResourcePolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidLibVlcDecoderResourcePolicyTest {
    @Test
    fun policyEmitsOnlyTheSelectedColonPrefixedDav1dOption() {
        assertEquals(1, AndroidLibVlcDecoderResourcePolicy.FRAME_THREADS)
        assertEquals(
            listOf(":dav1d-thread-frames=1"),
            AndroidLibVlcDecoderResourcePolicy.mediaOptions,
        )
        assertEquals(
            PlaybackDecoderResourcePolicy.BoundedDav1dFrameThreads,
            AndroidLibVlcDecoderResourcePolicy.diagnosticPolicy,
        )
    }
}
