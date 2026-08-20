// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

/** Supported-app entry-point lifecycle surface; tvOS never binds this feature. */
interface DownloadLifecycleHost {
    fun start()

    fun stop()

    /**
     * Schedules the already-durable runnable head after an explicit foreground
     * enqueue/resume/retry action.  Implementations return the native admission
     * result and never substitute another SDK path.
     */
    suspend fun wakeFromUserAction(): Result<Unit>
}
