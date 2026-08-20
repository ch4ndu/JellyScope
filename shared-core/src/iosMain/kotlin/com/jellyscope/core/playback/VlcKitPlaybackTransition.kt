// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.math.abs

internal enum class VlcKitPlaybackTransitionKind {
    Prepare,
    Resume,
    Seek,
}

internal enum class VlcKitPlaybackTransitionState {
    Pending,
    Ready,
    TimedOut,
}

internal data class VlcKitPlaybackTransitionDecision(
    val state: VlcKitPlaybackTransitionState,
    val publishedPositionMs: Long,
    val forceBuffering: Boolean,
    val arrivedNow: Boolean = false,
    val becameReady: Boolean = false,
)

internal data class VlcKitPlaybackTransitionSnapshot(
    val kind: VlcKitPlaybackTransitionKind,
    val state: VlcKitPlaybackTransitionState,
    val targetPositionMs: Long,
    val nativePositionMs: Long,
    val targetArrived: Boolean,
    val clockAdvanced: Boolean,
    val pictureAdvances: Int,
    val requiredPictureAdvances: Int,
)

/**
 * PiP admission needs a session-local content latch, not a claim that the fact
 * is reliable enough for shared health policy. A normal Ready transition has
 * clock and picture evidence. When VLCKit's public picture counter is absent,
 * a bounded timeout may still admit the engine-owned PiP source if the native
 * player says its video output exists and playback is active.
 */
internal fun shouldLatchVlcKitPictureInPictureContent(
    transitionState: VlcKitPlaybackTransitionState,
    hasVideoOut: Boolean,
    nativePlaying: Boolean,
): Boolean =
    transitionState == VlcKitPlaybackTransitionState.Ready ||
        (
            transitionState == VlcKitPlaybackTransitionState.TimedOut &&
                hasVideoOut &&
                nativePlaying
        )

internal class VlcKitPlaybackTransition {
    private var active: ActiveTransition? = null

    fun currentDecision(generation: Long): VlcKitPlaybackTransitionDecision? =
        active?.takeIf { transition -> transition.generation == generation }?.decision()

    fun transitionSequence(generation: Long): Long? =
        active?.takeIf { transition -> transition.generation == generation }?.transitionSequence

    fun snapshot(generation: Long): VlcKitPlaybackTransitionSnapshot? =
        active?.takeIf { transition -> transition.generation == generation }?.snapshot()

    fun begin(
        generation: Long,
        kind: VlcKitPlaybackTransitionKind,
        targetPositionMs: Long,
        playIntent: Boolean,
        videoExpected: Boolean,
        requiredPictureAdvances: Int,
        arrivalToleranceMs: Long,
        transitionSequence: Long?,
        initialDisplayedPictures: Long?,
    ): VlcKitPlaybackTransitionDecision {
        val target = targetPositionMs.coerceAtLeast(0L)
        active =
            ActiveTransition(
                generation = generation,
                kind = kind,
                targetPositionMs = target,
                playIntent = playIntent,
                videoExpected = videoExpected,
                requiredPictureAdvances = if (videoExpected) requiredPictureAdvances.coerceAtLeast(0) else 0,
                arrivalToleranceMs = arrivalToleranceMs.coerceAtLeast(0L),
                transitionSequence = transitionSequence,
                lastDisplayedPictures = initialDisplayedPictures,
            )
        return requireNotNull(active).decision()
    }

    fun observePosition(
        generation: Long,
        nativePositionMs: Long,
    ): VlcKitPlaybackTransitionDecision? {
        val current = active?.takeIf { transition -> transition.generation == generation } ?: return null
        val position = nativePositionMs.coerceAtLeast(0L)
        if (current.state == VlcKitPlaybackTransitionState.TimedOut) {
            // TimedOut is a diagnostic/recovery outcome, not a permanent UI
            // interlock. Continue following the honest native clock after the
            // bounded readiness window expires so the timeline and terminal
            // completion evidence cannot remain pinned for the whole session.
            current.lastNativePositionMs = position
            return current.decision()
        }
        if (current.state == VlcKitPlaybackTransitionState.Ready) {
            current.lastNativePositionMs = position
            return current.decision()
        }

        var arrivedNow = false
        val arrivalPosition = current.arrivalPositionMs
        val arrivedAtTarget =
            current.kind == VlcKitPlaybackTransitionKind.Prepare &&
                current.targetPositionMs == 0L ||
                current.kind == VlcKitPlaybackTransitionKind.Resume &&
                position >= (current.targetPositionMs - current.arrivalToleranceMs).coerceAtLeast(0L) ||
                abs(position - current.targetPositionMs) <= current.arrivalToleranceMs
        if (arrivalPosition == null && arrivedAtTarget) {
            current.arrivalPositionMs = position
            arrivedNow = true
        } else if (arrivalPosition != null && position > arrivalPosition) {
            current.clockAdvanced = true
        }
        current.lastNativePositionMs = position
        val becameReady = current.resolveReady()
        return current.decision(arrivedNow = arrivedNow, becameReady = becameReady)
    }

    fun observeDisplayedPictures(
        generation: Long,
        displayedPictures: Long?,
    ): VlcKitPlaybackTransitionDecision? {
        val current = active?.takeIf { transition -> transition.generation == generation } ?: return null
        if (current.state != VlcKitPlaybackTransitionState.Pending) return current.decision()
        val count = displayedPictures?.takeIf { value -> value >= 0L } ?: return current.decision()
        val previous = current.lastDisplayedPictures
        when {
            previous == null -> {
                current.lastDisplayedPictures = count
                current.recordPictureAdvances(count)
            }
            count < previous -> {
                current.lastDisplayedPictures = count
                current.pictureAdvances = 0
            }
            count > previous -> {
                current.lastDisplayedPictures = count
                current.recordPictureAdvances(count - previous)
            }
        }
        val becameReady = current.resolveReady()
        return current.decision(becameReady = becameReady)
    }

    fun pause(generation: Long): VlcKitPlaybackTransitionDecision? {
        val current = active?.takeIf { transition -> transition.generation == generation } ?: return null
        current.playIntent = false
        current.transitionSequence = null
        if (current.state == VlcKitPlaybackTransitionState.TimedOut) {
            current.state = VlcKitPlaybackTransitionState.Pending
        }
        val becameReady = current.resolveReady()
        return current.decision(becameReady = becameReady)
    }

    fun timeout(
        generation: Long,
        transitionSequence: Long,
    ): VlcKitPlaybackTransitionDecision? {
        val current =
            active?.takeIf { transition ->
                transition.generation == generation &&
                    transition.transitionSequence == transitionSequence &&
                    transition.state == VlcKitPlaybackTransitionState.Pending &&
                    transition.playIntent &&
                    transition.videoExpected
            } ?: return null
        current.state = VlcKitPlaybackTransitionState.TimedOut
        return current.decision()
    }

    fun clear() {
        active = null
    }

    private data class ActiveTransition(
        val generation: Long,
        val kind: VlcKitPlaybackTransitionKind,
        val targetPositionMs: Long,
        var playIntent: Boolean,
        val videoExpected: Boolean,
        val requiredPictureAdvances: Int,
        val arrivalToleranceMs: Long,
        var transitionSequence: Long?,
        var lastDisplayedPictures: Long?,
        var pictureAdvances: Int = 0,
        var arrivalPositionMs: Long? = null,
        var clockAdvanced: Boolean = false,
        var lastNativePositionMs: Long = targetPositionMs,
        var state: VlcKitPlaybackTransitionState = VlcKitPlaybackTransitionState.Pending,
    ) {
        fun recordPictureAdvances(count: Long) {
            if (count <= 0L || pictureAdvances >= requiredPictureAdvances) return
            pictureAdvances =
                (pictureAdvances.toLong() + count)
                    .coerceAtMost(requiredPictureAdvances.toLong())
                    .toInt()
        }

        fun resolveReady(): Boolean {
            if (state != VlcKitPlaybackTransitionState.Pending || arrivalPositionMs == null) return false
            val ready =
                if (playIntent) {
                    clockAdvanced &&
                        lastNativePositionMs - requireNotNull(arrivalPositionMs) >= 1L &&
                        pictureAdvances >= requiredPictureAdvances
                } else {
                    true
                }
            if (ready) state = VlcKitPlaybackTransitionState.Ready
            return ready
        }

        fun decision(
            arrivedNow: Boolean = false,
            becameReady: Boolean = false,
        ): VlcKitPlaybackTransitionDecision =
            VlcKitPlaybackTransitionDecision(
                state = state,
                publishedPositionMs =
                    if (state == VlcKitPlaybackTransitionState.Pending) {
                        targetPositionMs
                    } else {
                        lastNativePositionMs
                    },
                forceBuffering = playIntent && state == VlcKitPlaybackTransitionState.Pending,
                arrivedNow = arrivedNow,
                becameReady = becameReady,
            )

        fun snapshot(): VlcKitPlaybackTransitionSnapshot =
            VlcKitPlaybackTransitionSnapshot(
                kind = kind,
                state = state,
                targetPositionMs = targetPositionMs,
                nativePositionMs = lastNativePositionMs,
                targetArrived = arrivalPositionMs != null,
                clockAdvanced = clockAdvanced,
                pictureAdvances = pictureAdvances,
                requiredPictureAdvances = requiredPictureAdvances,
            )
    }
}
