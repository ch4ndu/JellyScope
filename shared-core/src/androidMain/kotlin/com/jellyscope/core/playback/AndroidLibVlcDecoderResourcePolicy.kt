// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackDecoderResourcePolicy

/**
 * Android libvlc 3.7.5 / bundled dav1d resource tuning. This is neither a
 * capability claim nor a user quality, device, or stream limit.
 */
internal object AndroidLibVlcDecoderResourcePolicy {
    private const val DAV1D_FRAME_THREADS_OPTION = "dav1d-thread-frames"
    private const val MINIMUM_CANDIDATE_FRAME_THREADS = 1
    private const val MAXIMUM_CANDIDATE_FRAME_THREADS = 2

    /**
     * Candidate 1 survived the Cube's 8K60 AV1 gate; candidate 2 exited
     * before the debug overlay and therefore is not eligible for release.
     */
    const val FRAME_THREADS: Int = MINIMUM_CANDIDATE_FRAME_THREADS

    val diagnosticPolicy: PlaybackDecoderResourcePolicy =
        PlaybackDecoderResourcePolicy.BoundedDav1dFrameThreads
    val mediaOptions: List<String> = listOf(":$DAV1D_FRAME_THREADS_OPTION=$FRAME_THREADS")

    init {
        check(FRAME_THREADS in MINIMUM_CANDIDATE_FRAME_THREADS..MAXIMUM_CANDIDATE_FRAME_THREADS)
    }
}
