// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.app.ActivityManager
import android.content.Context
import com.jellyscope.core.domain.playback.PlaybackBufferPolicy

internal enum class Media3MemoryClass {
    LowRam,
    Regular,
}

internal data class Media3LoadControlPolicy(
    val memoryClass: Media3MemoryClass,
    val diagnosticPolicy: PlaybackBufferPolicy,
    val minBufferMs: Int = MEDIA3_MIN_BUFFER_MS,
    val maxBufferMs: Int = MEDIA3_MAX_BUFFER_MS,
    val bufferForPlaybackMs: Int = MEDIA3_BUFFER_FOR_PLAYBACK_MS,
    val bufferAfterRebufferMs: Int = MEDIA3_BUFFER_AFTER_REBUFFER_MS,
    val targetBufferBytes: Int = MEDIA3_CONTROL_TARGET_BUFFER_BYTES,
    val prioritizeTimeOverSizeThresholds: Boolean = true,
)

internal fun resolveMedia3LoadControlPolicy(
    context: Context,
    useRegularCandidate: Boolean = false,
): Media3LoadControlPolicy {
    val activityManager =
        runCatching {
            context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
    val isLowRamDevice = runCatching { activityManager?.isLowRamDevice }.getOrNull()
    return createMedia3LoadControlPolicy(
        isLowRamDevice = isLowRamDevice,
        maxHeapBytes = Runtime.getRuntime().maxMemory(),
        useRegularCandidate = useRegularCandidate,
    )
}

internal fun createMedia3LoadControlPolicy(
    isLowRamDevice: Boolean?,
    maxHeapBytes: Long,
    useRegularCandidate: Boolean = false,
): Media3LoadControlPolicy {
    val memoryClass =
        if (isLowRamDevice == false && maxHeapBytes > 0L) {
            Media3MemoryClass.Regular
        } else {
            Media3MemoryClass.LowRam
        }
    val useCandidate = memoryClass == Media3MemoryClass.Regular && useRegularCandidate
    return Media3LoadControlPolicy(
        memoryClass = memoryClass,
        diagnosticPolicy =
            when {
                memoryClass == Media3MemoryClass.LowRam -> PlaybackBufferPolicy.LowRam16MiB
                useCandidate -> PlaybackBufferPolicy.RegularCandidate
                else -> PlaybackBufferPolicy.Regular16MiB
            },
        targetBufferBytes =
            if (useCandidate) {
                regularCandidateTargetBufferBytes(maxHeapBytes)
            } else {
                MEDIA3_CONTROL_TARGET_BUFFER_BYTES
            },
    )
}

internal fun regularCandidateTargetBufferBytes(maxHeapBytes: Long): Int {
    if (maxHeapBytes <= 0L) return MEDIA3_CONTROL_TARGET_BUFFER_BYTES
    val candidate =
        (maxHeapBytes / 3L)
            .coerceAtMost(MEDIA3_REGULAR_TARGET_CAP_BYTES.toLong())
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
    return candidate.toInt()
}

internal const val MEDIA3_MIN_BUFFER_MS = 8_000
internal const val MEDIA3_MAX_BUFFER_MS = 20_000
internal const val MEDIA3_BUFFER_FOR_PLAYBACK_MS = 1_000
internal const val MEDIA3_BUFFER_AFTER_REBUFFER_MS = 2_000
internal const val MEDIA3_CONTROL_TARGET_BUFFER_BYTES = 16 * 1024 * 1024
internal const val MEDIA3_REGULAR_TARGET_CAP_BYTES = 384 * 1024 * 1024
