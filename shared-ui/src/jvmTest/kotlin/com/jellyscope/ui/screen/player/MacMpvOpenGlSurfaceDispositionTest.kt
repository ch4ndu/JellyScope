// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.playback.MpvOpenGlDetachDisposition
import com.jellyscope.core.playback.MpvOpenGlFramebuffer
import com.jellyscope.core.playback.MpvOpenGlRenderSurface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacMpvOpenGlSurfaceDispositionTest {
    @Test
    fun quarantinedDetachSkipsSurfaceCloseWhileTerminatedDetachCloses() {
        val quarantinedSurface = CloseTrackingSurface()
        closeOpenGlSurfaceAfterDetach(quarantinedSurface, MpvOpenGlDetachDisposition.Quarantined)
        assertFalse(quarantinedSurface.closed.get())

        val terminatedSurface = CloseTrackingSurface()
        closeOpenGlSurfaceAfterDetach(terminatedSurface, MpvOpenGlDetachDisposition.Terminated)
        assertTrue(terminatedSurface.closed.get())
    }
}

private class CloseTrackingSurface : MpvOpenGlRenderSurface {
    override val generation: Long = 1L
    val closed = AtomicBoolean(false)

    override fun resolveGlSymbol(name: String): Long = 0L

    override fun withCurrentContext(render: (MpvOpenGlFramebuffer) -> Boolean): Boolean = false

    override fun close() {
        closed.set(true)
    }
}
