// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import platform.UIKit.UIView

/** Provides a native iOS view for player backends that do not use AVPlayerLayer. */
public interface IosPlaybackSurfaceProvider {
    public fun createSurfaceView(): UIView
}

/** iOS-only PiP capability kept separate from the common player contract. */
public interface IosPictureInPictureSurfaceProvider {
    public fun configurePictureInPicture(
        enabled: Boolean,
        callbacks: IosPictureInPictureSurfaceCallbacks,
    )

    public fun clearPictureInPictureCallbacks()

    /**
     * Admits the current native source for an explicit PiP start. Implementations
     * enqueue the native command only after returning the typed identity.
     */
    public fun requestPictureInPictureStart(): IosPictureInPictureStartAdmission

    /** Stops only the native PiP window that still owns [sourceIdentity]. */
    public fun requestPictureInPictureStop(sourceIdentity: Long)
}

/**
 * Project-owned PiP admission. Native AVKit/VLCKit objects never cross this
 * boundary, and a rejected request carries no guessed native reason.
 */
public sealed interface IosPictureInPictureStartAdmission {
    public data object Rejected : IosPictureInPictureStartAdmission

    public data class ExplicitStartRequested(
        val sourceIdentity: Long,
    ) : IosPictureInPictureStartAdmission

    public data class AutomaticStartPending(
        val sourceIdentity: Long,
    ) : IosPictureInPictureStartAdmission
}

public enum class IosPictureInPictureWindowState {
    Started,
    Stopped,
}

/** Project-owned window facts, scoped to the source that produced them. */
public data class IosPictureInPictureSurfaceCallbacks(
    val onWindowStateChanged: (
        sourceIdentity: Long,
        state: IosPictureInPictureWindowState,
    ) -> Unit,
    val onSourceInvalidated: (sourceIdentity: Long) -> Unit,
)
