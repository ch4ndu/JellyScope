// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacMpvIoSurfacePresentationTest {
    @Test
    fun ownershipRotationNeverReusesPresentedOrPendingBuffers() {
        val model = MacMpvIoSurfaceSwapchainModel(widthPx = 100, heightPx = 50)

        assertTrue(model.markPending(0))
        assertTrue(model.markPresented(0))
        assertTrue(model.markPending(1))
        assertTrue(model.markPending(2))
        assertNull(model.freeIndex())

        assertTrue(model.markPresented(1))
        assertEquals(MacMpvIoSurfaceBufferOwnership.Free, model.ownership(0))
        assertEquals(MacMpvIoSurfaceBufferOwnership.Presented, model.ownership(1))
        assertEquals(MacMpvIoSurfaceBufferOwnership.Pending, model.ownership(2))
        assertEquals(0, model.freeIndex())
    }

    @Test
    fun noFreeBufferDeliversZeroSizeSentinelAndStillInvokesRenderCallback() {
        val model = MacMpvIoSurfaceSwapchainModel(widthPx = 100, heightPx = 50)
        assertTrue(model.markPending(0))
        assertTrue(model.markPresented(0))
        assertTrue(model.markPending(1))
        assertTrue(model.markPending(2))

        val target = chooseMacMpvIoSurfaceRenderTarget(model, fboIdForIndex = { 99 }, flipY = true)
        var callbackInvoked = false
        val renderResult =
            invokeMacMpvIoSurfaceRender(target) {
                callbackInvoked = true
                it.widthPx == 0 && it.heightPx == 0
            }

        assertNull(target.bufferIndex)
        assertTrue(callbackInvoked)
        assertTrue(renderResult)
    }

    @Test
    fun presentAfterSkippedFrameRequestsRedrawWhenAFreeBufferReturns() {
        val model = MacMpvIoSurfaceSwapchainModel(widthPx = 100, heightPx = 50)
        model.markFrameSkipped()
        assertTrue(model.consumeRedrawAfterPresent())
        assertFalse(model.consumeRedrawAfterPresent())
    }

    @Test
    fun generationStampedPresentsBecomeStaleAfterResizeAndClose() {
        val model = MacMpvIoSurfaceSwapchainModel(widthPx = 100, heightPx = 50)
        val initialGeneration = model.generation

        assertTrue(model.rebuild(widthPx = 200, heightPx = 100))
        assertFalse(model.acceptsPresent(initialGeneration))
        assertTrue(model.acceptsPresent(model.generation))

        val currentGeneration = model.generation
        model.close()
        assertFalse(model.acceptsPresent(currentGeneration))
        assertFalse(model.rebuild(widthPx = 300, heightPx = 150))
        assertEquals(200, model.widthPx)
        assertEquals(100, model.heightPx)
    }
}
