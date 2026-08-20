// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.playback.DesktopPlaybackProbe
import com.jellyscope.core.playback.DesktopProbeToken
import com.jellyscope.core.playback.DesktopSurfaceAction
import com.jellyscope.core.playback.DesktopSurfaceFailureProbeRecord
import com.jellyscope.core.playback.DesktopSurfaceKind
import com.jellyscope.core.playback.DesktopSurfaceLifecycleProbeRecord
import com.jellyscope.core.playback.DesktopSurfaceTimingProbeRecord
import com.jellyscope.core.playback.MpvOpenGlFramebuffer
import com.jellyscope.core.playback.MpvOpenGlSurfaceKind
import com.jellyscope.core.util.nextPositiveGeneration
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

internal enum class MacMpvIoSurfaceBufferOwnership {
    Free,
    Pending,
    Presented,
}

internal data class MacMpvIoSurfaceRenderTargetDecision(
    val framebuffer: MpvOpenGlFramebuffer,
    val bufferIndex: Int?,
)

/** Pure swapchain ownership bookkeeping used by the native surface and JVM tests. */
internal class MacMpvIoSurfaceSwapchainModel(
    widthPx: Int,
    heightPx: Int,
    generation: Long = 1L,
    bufferCount: Int = IOSURFACE_SWAPCHAIN_BUFFER_COUNT,
) {
    init {
        require(widthPx > 0 && heightPx > 0)
        require(bufferCount > 0)
        require(generation > 0L)
    }

    var widthPx: Int = widthPx
        private set
    var heightPx: Int = heightPx
        private set
    var generation: Long = generation
        private set
    var closed: Boolean = false
        private set

    private val ownership = MutableList(bufferCount) { MacMpvIoSurfaceBufferOwnership.Free }
    private var skippedFrame = false

    fun ownership(index: Int): MacMpvIoSurfaceBufferOwnership = ownership[index]

    fun freeIndex(): Int? = ownership.indexOfFirst { state -> state == MacMpvIoSurfaceBufferOwnership.Free }.takeIf { it >= 0 }

    fun markPending(index: Int): Boolean {
        if (ownership.getOrNull(index) != MacMpvIoSurfaceBufferOwnership.Free) return false
        ownership[index] = MacMpvIoSurfaceBufferOwnership.Pending
        return true
    }

    fun markFree(index: Int): Boolean {
        if (ownership.getOrNull(index) != MacMpvIoSurfaceBufferOwnership.Pending) return false
        ownership[index] = MacMpvIoSurfaceBufferOwnership.Free
        return true
    }

    fun markPresented(index: Int): Boolean {
        if (ownership.getOrNull(index) != MacMpvIoSurfaceBufferOwnership.Pending) return false
        ownership.indices
            .firstOrNull { candidate -> ownership[candidate] == MacMpvIoSurfaceBufferOwnership.Presented }
            ?.let { previous -> ownership[previous] = MacMpvIoSurfaceBufferOwnership.Free }
        ownership[index] = MacMpvIoSurfaceBufferOwnership.Presented
        return true
    }

    fun markFrameSkipped() {
        skippedFrame = true
    }

    fun consumeRedrawAfterPresent(): Boolean {
        val shouldRedraw = skippedFrame
        skippedFrame = false
        return shouldRedraw
    }

    fun acceptsPresent(presentGeneration: Long): Boolean = !closed && generation == presentGeneration

    fun rebuild(
        widthPx: Int,
        heightPx: Int,
    ): Boolean {
        if (closed || widthPx <= 0 || heightPx <= 0) return false
        this.widthPx = widthPx
        this.heightPx = heightPx
        generation = nextPositiveGeneration(generation)
        ownership.indices.forEach { index -> ownership[index] = MacMpvIoSurfaceBufferOwnership.Free }
        return true
    }

    fun close() {
        closed = true
        generation = nextPositiveGeneration(generation)
    }

    fun renderTarget(
        fboIdForIndex: (Int) -> Int,
        flipY: Boolean,
    ): MacMpvIoSurfaceRenderTargetDecision {
        val index = freeIndex()
        return if (index == null) {
            MacMpvIoSurfaceRenderTargetDecision(
                framebuffer = MpvOpenGlFramebuffer(widthPx = 0, heightPx = 0),
                bufferIndex = null,
            )
        } else {
            MacMpvIoSurfaceRenderTargetDecision(
                framebuffer =
                    MpvOpenGlFramebuffer(
                        widthPx = widthPx,
                        heightPx = heightPx,
                        fboId = fboIdForIndex(index),
                        flipY = flipY,
                    ),
                bufferIndex = index,
            )
        }
    }
}

internal fun chooseMacMpvIoSurfaceRenderTarget(
    model: MacMpvIoSurfaceSwapchainModel,
    fboIdForIndex: (Int) -> Int,
    flipY: Boolean,
): MacMpvIoSurfaceRenderTargetDecision = model.renderTarget(fboIdForIndex, flipY)

internal fun invokeMacMpvIoSurfaceRender(
    target: MacMpvIoSurfaceRenderTargetDecision,
    render: (MpvOpenGlFramebuffer) -> Boolean,
): Boolean = render(target.framebuffer)

private class MacMpvIoSurfaceNativeBuffer(
    val surface: Pointer,
    val textureId: Int,
    val fboId: Int,
)

private class MacMpvIoSurfaceNativeSwapchain(
    val model: MacMpvIoSurfaceSwapchainModel,
    val buffers: List<MacMpvIoSurfaceNativeBuffer>,
)

internal class MacMpvIoSurfacePresentation private constructor(
    override val generation: Long,
    private val view: Pointer,
    private val videoLayer: Pointer,
    private val cglContext: Pointer,
    private val pixelFormat: Pointer,
    private var swapchain: MacMpvIoSurfaceNativeSwapchain,
    private val requestRedraw: (Long) -> Unit,
    private var contentsScale: Double,
) : MacMpvHostedSurface {
    private val lock = Any()
    private var closed = false

    override val surfaceKind: MpvOpenGlSurfaceKind = MpvOpenGlSurfaceKind.IOSurface
    override val presentationLabel: String = "IOSurface Render API"

    override fun resolveGlSymbol(name: String): Long =
        runCatching {
            Pointer.nativeValue(ioSurfaceOpenGlLibrary.getFunction(name))
        }.getOrDefault(0L)

    override fun withCurrentContext(render: (MpvOpenGlFramebuffer) -> Boolean): Boolean {
        var redraw = false
        val entered =
            synchronized(lock) {
                if (closed) return@synchronized false
                val setError = ioSurfaceOpenGl.CGLSetCurrentContext(cglContext)
                if (setError != CGL_NO_ERROR) {
                    logMacMpvIoSurfaceFailure("context-set", errorCode = setError)
                    return@synchronized false
                }
                try {
                    val activeSwapchain = swapchain
                    val target =
                        chooseMacMpvIoSurfaceRenderTarget(
                            model = activeSwapchain.model,
                            fboIdForIndex = { index -> activeSwapchain.buffers[index].fboId },
                            flipY = IOSURFACE_FLIP_Y,
                        )
                    target.bufferIndex?.let {
                        ioSurfaceOpenGl.glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer.fboId)
                    }
                    val renderStartedAt = System.nanoTime()
                    val rendered = runCatching { invokeMacMpvIoSurfaceRender(target, render) }.getOrDefault(false)
                    val renderNanos = System.nanoTime() - renderStartedAt
                    if (target.bufferIndex == null) {
                        activeSwapchain.model.markFrameSkipped()
                    } else if (rendered && activeSwapchain.model.markPending(target.bufferIndex)) {
                        ioSurfaceOpenGl.glFlush()
                        // Present directly from this thread. Routing presents
                        // through the main queue made buffer recycling depend
                        // on main-queue latency: pointer-driven Compose redraw
                        // backed presents up, exhausted the swapchain, and
                        // every sentinel skip surfaced as an mpv output drop
                        // (measured ~30/s during the wiggle protocol). The
                        // dedicated sublayer is app-owned, so mutating its
                        // contents inside an explicit CATransaction off-main
                        // is the supported pattern and frees the previous
                        // buffer immediately.
                        val presentStartedAt = System.nanoTime()
                        presentFromRenderThread(activeSwapchain, target.bufferIndex)
                        recordSegmentTiming(renderNanos, System.nanoTime() - presentStartedAt)
                        redraw = activeSwapchain.model.consumeRedrawAfterPresent()
                    }
                    target.bufferIndex?.let { ioSurfaceOpenGl.glBindFramebuffer(GL_FRAMEBUFFER, 0) }
                    true
                } finally {
                    val clearError = ioSurfaceOpenGl.CGLSetCurrentContext(Pointer.NULL)
                    if (clearError != CGL_NO_ERROR) {
                        logMacMpvIoSurfaceFailure("context-clear", errorCode = clearError)
                    }
                }
            }
        if (redraw) requestRedraw(generation)
        return entered
    }

    // Probe-only segment timing: distinguishes mpv render time (GPU
    // contention) from CATransaction commit time (render-server contention)
    // per ~2s window so the drop mechanism is attributable from one run.
    private var timingWindowStartedAt = 0L
    private var timingFrames = 0
    private var renderMaxNanos = 0L
    private var presentMaxNanos = 0L

    private fun recordSegmentTiming(
        renderNanos: Long,
        presentNanos: Long,
    ) {
        if (!DesktopPlaybackProbe.isEnabled) return
        val now = System.nanoTime()
        if (timingWindowStartedAt == 0L) timingWindowStartedAt = now
        timingFrames += 1
        if (renderNanos > renderMaxNanos) renderMaxNanos = renderNanos
        if (presentNanos > presentMaxNanos) presentMaxNanos = presentNanos
        if (now - timingWindowStartedAt >= TIMING_WINDOW_NANOS) {
            DesktopPlaybackProbe.emit(
                DesktopSurfaceTimingProbeRecord(
                    frames = timingFrames,
                    renderMaxMicros = renderMaxNanos / 1_000,
                    presentMaxMicros = presentMaxNanos / 1_000,
                ),
            )
            timingWindowStartedAt = now
            timingFrames = 0
            renderMaxNanos = 0L
            presentMaxNanos = 0L
        }
    }

    private fun presentFromRenderThread(
        activeSwapchain: MacMpvIoSurfaceNativeSwapchain,
        bufferIndex: Int,
    ) {
        if (!activeSwapchain.model.markPresented(bufferIndex)) return
        val transaction = MacObjectiveCRuntime.objcGetClass("CATransaction") ?: return
        MacObjectiveCRuntime.sendVoidReturnNoArgs(transaction, IoSurfaceSelectors.begin)
        try {
            MacObjectiveCRuntime.sendVoidReturnByte(transaction, IoSurfaceSelectors.setDisableActions, OBJC_YES)
            MacObjectiveCRuntime.sendVoidReturnDouble(videoLayer, IoSurfaceSelectors.setContentsScale, contentsScale)
            MacObjectiveCRuntime.sendVoidReturnNullablePointer(
                videoLayer,
                IoSurfaceSelectors.setContents,
                activeSwapchain.buffers[bufferIndex].surface,
            )
        } finally {
            MacObjectiveCRuntime.sendVoidReturnNoArgs(transaction, IoSurfaceSelectors.commit)
        }
    }

    override fun resize(snapshot: MacAppKitViewSnapshot): Boolean {
        var replacement: MacMpvIoSurfaceNativeSwapchain? = null
        var previous: MacMpvIoSurfaceNativeSwapchain? = null
        val nextScale = snapshot.contentsScale()
        val created =
            synchronized(lock) {
                if (closed) return@synchronized false
                val nextGeneration = nextPositiveGeneration(swapchain.model.generation)
                replacement = createNativeSwapchain(snapshot.widthPx, snapshot.heightPx, nextGeneration)
                replacement != null
            }
        if (!created) {
            logMacMpvResizeFailure(snapshot)
            return false
        }

        val frameUpdated =
            runCatching {
                MacMpvAppKitDispatch.run {
                    MacObjectiveCRuntime.sendVoidReturnRect(view, IoSurfaceSelectors.setFrame, snapshot.frame)
                    MacObjectiveCRuntime.sendVoidReturnRect(
                        videoLayer,
                        IoSurfaceSelectors.setFrame,
                        videoLayerBounds(snapshot),
                    )
                    MacObjectiveCRuntime.sendVoidReturnDouble(
                        videoLayer,
                        IoSurfaceSelectors.setContentsScale,
                        nextScale,
                    )
                }
            }.isSuccess
        if (!frameUpdated) {
            synchronized(lock) { replacement?.let(::destroyNativeSwapchain) }
            logMacMpvResizeFailure(snapshot)
            return false
        }

        val swapped =
            synchronized(lock) {
                if (closed) {
                    false
                } else {
                    previous = swapchain
                    swapchain = replacement ?: return@synchronized false
                    contentsScale = nextScale
                    true
                }
            }
        if (swapped) {
            synchronized(lock) { previous?.let(::destroyNativeSwapchain) }
            logMacMpvIoSurfaceLifecycle(
                action = DesktopSurfaceAction.RESIZE,
                snapshot = snapshot,
                generation = swapchain.model.generation,
            )
        } else {
            synchronized(lock) { replacement?.let(::destroyNativeSwapchain) }
            logMacMpvResizeFailure(snapshot)
        }
        return swapped
    }

    override fun close() {
        var oldSwapchain: MacMpvIoSurfaceNativeSwapchain? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            swapchain.model.close()
            oldSwapchain = swapchain
        }
        destroyNativeSwapchain(oldSwapchain)
        runCatching { ioSurfaceOpenGl.CGLDestroyContext(cglContext) }
        runCatching { ioSurfaceOpenGl.CGLDestroyPixelFormat(pixelFormat) }
        runCatching {
            MacMpvAppKitDispatch.run {
                MacObjectiveCRuntime.sendVoidReturnNoArgs(videoLayer, IoSurfaceSelectors.removeFromSuperlayer)
                MacObjectiveCRuntime.sendVoidReturnNoArgs(videoLayer, IoSurfaceSelectors.release)
                MacObjectiveCRuntime.sendVoidReturnNoArgs(view, IoSurfaceSelectors.removeFromSuperview)
                MacObjectiveCRuntime.sendVoidReturnNoArgs(view, IoSurfaceSelectors.release)
            }
        }
    }

    private fun createNativeSwapchain(
        widthPx: Int,
        heightPx: Int,
        generation: Long,
    ): MacMpvIoSurfaceNativeSwapchain? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val setError = ioSurfaceOpenGl.CGLSetCurrentContext(cglContext)
        if (setError != CGL_NO_ERROR) {
            logMacMpvIoSurfaceFailure("swapchain-context-set", errorCode = setError)
            checkCglContextClear()
            return null
        }
        val buffers = mutableListOf<MacMpvIoSurfaceNativeBuffer>()
        return try {
            repeat(IOSURFACE_SWAPCHAIN_BUFFER_COUNT) {
                val surface = createIoSurface(widthPx, heightPx) ?: error("IOSurfaceCreate failed")
                var textureId = 0
                var fboId = 0
                try {
                    textureId = generateTexture()
                    check(
                        ioSurfaceOpenGl.CGLTexImageIOSurface2D(
                            cglContext,
                            GL_TEXTURE_RECTANGLE_ARB,
                            GL_RGBA8,
                            widthPx,
                            heightPx,
                            GL_BGRA,
                            GL_UNSIGNED_INT_8_8_8_8_REV,
                            surface,
                            0,
                        ) == CGL_NO_ERROR,
                    ) { "CGLTexImageIOSurface2D failed" }
                    fboId = generateFramebuffer()
                    ioSurfaceOpenGl.glBindFramebuffer(GL_FRAMEBUFFER, fboId)
                    ioSurfaceOpenGl.glFramebufferTexture2D(
                        GL_FRAMEBUFFER,
                        GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_RECTANGLE_ARB,
                        textureId,
                        0,
                    )
                    check(
                        ioSurfaceOpenGl.glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE,
                    ) { "IOSurface framebuffer is incomplete" }
                    buffers += MacMpvIoSurfaceNativeBuffer(surface, textureId, fboId)
                } catch (failure: Throwable) {
                    deleteNativeBuffer(textureId, fboId, surface)
                    throw failure
                }
            }
            ioSurfaceOpenGl.glBindFramebuffer(GL_FRAMEBUFFER, 0)
            MacMpvIoSurfaceNativeSwapchain(
                model = MacMpvIoSurfaceSwapchainModel(widthPx, heightPx, generation),
                buffers = buffers.toList(),
            )
        } catch (failure: Throwable) {
            buffers.asReversed().forEach { buffer ->
                deleteNativeBuffer(buffer.textureId, buffer.fboId, buffer.surface)
            }
            logMacMpvIoSurfaceFailure("swapchain-create", reason = failure.javaClass.simpleName)
            null
        } finally {
            val clearError = ioSurfaceOpenGl.CGLSetCurrentContext(Pointer.NULL)
            if (clearError != CGL_NO_ERROR) {
                logMacMpvIoSurfaceFailure("swapchain-context-clear", errorCode = clearError)
            }
        }
    }

    private fun destroyNativeSwapchain(nativeSwapchain: MacMpvIoSurfaceNativeSwapchain?) {
        if (nativeSwapchain == null) return
        val setError = ioSurfaceOpenGl.CGLSetCurrentContext(cglContext)
        if (setError == CGL_NO_ERROR) {
            nativeSwapchain.buffers.asReversed().forEach { buffer ->
                deleteNativeBuffer(buffer.textureId, buffer.fboId, buffer.surface)
            }
            ioSurfaceOpenGl.glBindFramebuffer(GL_FRAMEBUFFER, 0)
        } else {
            nativeSwapchain.buffers.asReversed().forEach { buffer ->
                coreFoundation.CFRelease(buffer.surface)
            }
        }
        checkCglContextClear("destroy-context-clear-failed")
    }

    private fun generateTexture(): Int {
        val texture = IntByReference()
        ioSurfaceOpenGl.glGenTextures(1, texture)
        check(texture.value > 0) { "glGenTextures failed" }
        ioSurfaceOpenGl.glBindTexture(GL_TEXTURE_RECTANGLE_ARB, texture.value)
        return texture.value
    }

    private fun generateFramebuffer(): Int {
        val framebuffer = IntByReference()
        ioSurfaceOpenGl.glGenFramebuffers(1, framebuffer)
        check(framebuffer.value > 0) { "glGenFramebuffers failed" }
        return framebuffer.value
    }

    private fun deleteNativeBuffer(
        textureId: Int,
        fboId: Int,
        surface: Pointer,
    ) {
        if (fboId > 0) ioSurfaceOpenGl.glDeleteFramebuffers(1, IntByReference(fboId))
        if (textureId > 0) ioSurfaceOpenGl.glDeleteTextures(1, IntByReference(textureId))
        coreFoundation.CFRelease(surface)
    }

    companion object {
        fun create(
            snapshot: MacAppKitViewSnapshot,
            generation: Long,
            requestRedraw: (Long) -> Unit,
        ): MacMpvIoSurfacePresentation? {
            if (generation <= 0L) return null
            var hosted: MacMpvIoSurfaceHostedView? = null
            var pixelFormat: Pointer? = null
            var cglContext: Pointer? = null
            var nativeSwapchain: MacMpvIoSurfaceNativeSwapchain? = null
            var result: MacMpvIoSurfacePresentation? = null
            try {
                NativeLibrary.getInstance(IOSURFACE_FRAMEWORK)
                NativeLibrary.getInstance(OPENGL_FRAMEWORK)
                hosted = createView(snapshot)
                if (hosted == null) {
                    logMacMpvIoSurfaceFailure("create", reason = "view")
                    return null
                }
                pixelFormat = choosePixelFormat()
                cglContext = pixelFormat?.let(::createContext)
                if (pixelFormat == null || cglContext == null) return null
                nativeSwapchain =
                    createNativeSwapchainForContext(
                        context = cglContext,
                        widthPx = snapshot.widthPx,
                        heightPx = snapshot.heightPx,
                        generation = 1L,
                    )
                if (nativeSwapchain == null) return null
                result =
                    MacMpvIoSurfacePresentation(
                        generation = generation,
                        view = hosted.view,
                        videoLayer = hosted.videoLayer,
                        cglContext = cglContext,
                        pixelFormat = pixelFormat,
                        swapchain = nativeSwapchain,
                        requestRedraw = requestRedraw,
                        contentsScale = snapshot.contentsScale(),
                    )
                logMacMpvIoSurfaceLifecycle(
                    action = DesktopSurfaceAction.ATTACH,
                    snapshot = snapshot,
                    generation = generation,
                )
                return result
            } catch (failure: Throwable) {
                logMacMpvIoSurfaceFailure("create", reason = failure.javaClass.simpleName)
                return null
            } finally {
                if (result == null) {
                    nativeSwapchain?.let { swapchain ->
                        destroyNativeSwapchainForContext(cglContext, swapchain)
                    }
                    cglContext?.let { context ->
                        runCatching { ioSurfaceOpenGl.CGLDestroyContext(context) }
                    }
                    pixelFormat?.let { format ->
                        runCatching { ioSurfaceOpenGl.CGLDestroyPixelFormat(format) }
                    }
                    hosted?.let(::removeHostedView)
                }
            }
        }

        private fun choosePixelFormat(): Pointer? {
            val attributes =
                Memory((CGL_PIXEL_FORMAT_ATTRIBUTES.size * Int.SIZE_BYTES).toLong()).apply {
                    CGL_PIXEL_FORMAT_ATTRIBUTES.forEachIndexed { index, attribute ->
                        setInt(index.toLong() * Int.SIZE_BYTES, attribute)
                    }
                }
            val pixelFormat = PointerByReference()
            val numberOfScreens = IntByReference()
            val error = ioSurfaceOpenGl.CGLChoosePixelFormat(attributes, pixelFormat, numberOfScreens)
            if (error != CGL_NO_ERROR || numberOfScreens.value <= 0) {
                logMacMpvIoSurfaceFailure("pixel-format", errorCode = error)
                return null
            }
            return pixelFormat.value
        }

        private fun createContext(pixelFormat: Pointer): Pointer? {
            val context = PointerByReference()
            val error = ioSurfaceOpenGl.CGLCreateContext(pixelFormat, Pointer.NULL, context)
            if (error != CGL_NO_ERROR) {
                logMacMpvIoSurfaceFailure("context-create", errorCode = error)
                return null
            }
            return context.value
        }

        private fun createNativeSwapchainForContext(
            context: Pointer?,
            widthPx: Int,
            heightPx: Int,
            generation: Long,
        ): MacMpvIoSurfaceNativeSwapchain? {
            if (context == null || widthPx <= 0 || heightPx <= 0) return null
            val setError = ioSurfaceOpenGl.CGLSetCurrentContext(context)
            if (setError != CGL_NO_ERROR) {
                checkCglContextClear()
                return null
            }
            val buffers = mutableListOf<MacMpvIoSurfaceNativeBuffer>()
            return try {
                repeat(IOSURFACE_SWAPCHAIN_BUFFER_COUNT) {
                    val surface = createIoSurface(widthPx, heightPx) ?: error("IOSurfaceCreate failed")
                    var textureId = 0
                    var fboId = 0
                    try {
                        textureId = generateTextureForContext()
                        check(
                            ioSurfaceOpenGl.CGLTexImageIOSurface2D(
                                context,
                                GL_TEXTURE_RECTANGLE_ARB,
                                GL_RGBA8,
                                widthPx,
                                heightPx,
                                GL_BGRA,
                                GL_UNSIGNED_INT_8_8_8_8_REV,
                                surface,
                                0,
                            ) == CGL_NO_ERROR,
                        ) { "CGLTexImageIOSurface2D failed" }
                        fboId = generateFramebufferForContext()
                        ioSurfaceOpenGl.glBindFramebuffer(GL_FRAMEBUFFER, fboId)
                        ioSurfaceOpenGl.glFramebufferTexture2D(
                            GL_FRAMEBUFFER,
                            GL_COLOR_ATTACHMENT0,
                            GL_TEXTURE_RECTANGLE_ARB,
                            textureId,
                            0,
                        )
                        check(
                            ioSurfaceOpenGl.glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE,
                        ) { "IOSurface framebuffer is incomplete" }
                        buffers += MacMpvIoSurfaceNativeBuffer(surface, textureId, fboId)
                    } catch (failure: Throwable) {
                        deleteNativeBufferForContext(textureId, fboId, surface)
                        throw failure
                    }
                }
                ioSurfaceOpenGl.glBindFramebuffer(GL_FRAMEBUFFER, 0)
                MacMpvIoSurfaceNativeSwapchain(
                    model = MacMpvIoSurfaceSwapchainModel(widthPx, heightPx, generation),
                    buffers = buffers.toList(),
                )
            } catch (failure: Throwable) {
                buffers.asReversed().forEach { buffer ->
                    deleteNativeBufferForContext(buffer.textureId, buffer.fboId, buffer.surface)
                }
                logMacMpvIoSurfaceFailure("swapchain-create", reason = failure.javaClass.simpleName)
                null
            } finally {
                checkCglContextClear()
            }
        }

        private fun generateTextureForContext(): Int {
            val texture = IntByReference()
            ioSurfaceOpenGl.glGenTextures(1, texture)
            check(texture.value > 0) { "glGenTextures failed" }
            ioSurfaceOpenGl.glBindTexture(GL_TEXTURE_RECTANGLE_ARB, texture.value)
            return texture.value
        }

        private fun generateFramebufferForContext(): Int {
            val framebuffer = IntByReference()
            ioSurfaceOpenGl.glGenFramebuffers(1, framebuffer)
            check(framebuffer.value > 0) { "glGenFramebuffers failed" }
            return framebuffer.value
        }

        private fun deleteNativeBufferForContext(
            textureId: Int,
            fboId: Int,
            surface: Pointer,
        ) {
            if (fboId > 0) ioSurfaceOpenGl.glDeleteFramebuffers(1, IntByReference(fboId))
            if (textureId > 0) ioSurfaceOpenGl.glDeleteTextures(1, IntByReference(textureId))
            coreFoundation.CFRelease(surface)
        }

        private fun createView(snapshot: MacAppKitViewSnapshot): MacMpvIoSurfaceHostedView? {
            var result: MacMpvIoSurfaceHostedView? = null
            MacMpvAppKitDispatch.run {
                val viewClass =
                    MacAppKitInteropViewClass.resolve(
                        className = MPV_IO_SURFACE_VIEW_CLASS,
                        superclassName = "NSView",
                    ) ?: return@run
                val allocation = MacObjectiveCRuntime.sendPointerReturnNoArgs(viewClass, IoSurfaceSelectors.alloc) ?: return@run
                val view = MacObjectiveCRuntime.sendPointerReturnRect(allocation, IoSurfaceSelectors.initWithFrame, snapshot.frame)
                if (view == null) {
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(allocation, IoSurfaceSelectors.release)
                    logMacMpvIoSurfaceFailure("view-create", failureStage = "init")
                    return@run
                }
                // A fresh NSView has no backing layer until wantsLayer is set;
                // these two calls enable layer backing, so the layer check must
                // come AFTER them (checking before released the view every time
                // and silently forced the NSOpenGLView fallback).
                MacAppKitInteropViewClass.applyBlackBackground(view)
                MacAppKitInteropViewClass.applyBelowComposeOrdering(view)
                val backingLayer = MacObjectiveCRuntime.sendPointerReturnNoArgs(view, IoSurfaceSelectors.layer)
                if (backingLayer == null) {
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(view, IoSurfaceSelectors.release)
                    logMacMpvIoSurfaceFailure("view-create", failureStage = "layer")
                    return@run
                }
                // The video frames go to a dedicated app-owned sublayer, not the
                // view's backing layer: AppKit manages a layer-backed view's own
                // layer, and presents happen off-main from the render thread.
                val layerClass = MacObjectiveCRuntime.objcGetClass("CALayer")
                val videoLayer =
                    layerClass
                        ?.let { objcClass -> MacObjectiveCRuntime.sendPointerReturnNoArgs(objcClass, IoSurfaceSelectors.alloc) }
                        ?.let { allocation -> MacObjectiveCRuntime.sendPointerReturnNoArgs(allocation, IoSurfaceSelectors.init) }
                if (videoLayer == null) {
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(view, IoSurfaceSelectors.release)
                    logMacMpvIoSurfaceFailure("view-create", failureStage = "video-layer")
                    return@run
                }
                MacObjectiveCRuntime.sendVoidReturnRect(videoLayer, IoSurfaceSelectors.setFrame, videoLayerBounds(snapshot))
                MacObjectiveCRuntime.sendVoidReturnNullablePointer(backingLayer, IoSurfaceSelectors.addSublayer, videoLayer)
                MacObjectiveCRuntime.sendVoidReturnPointerSignedNativeLongAndNullablePointer(
                    Pointer(snapshot.rootViewAddress),
                    IoSurfaceSelectors.addSubviewPositionedRelativeTo,
                    view,
                    NativeLong(NS_VIEW_BELOW),
                    Pointer.NULL,
                )
                MacAppKitInteropViewClass.logViewHierarchy(Pointer(snapshot.rootViewAddress), "mpv-iosurface-attach")
                result = MacMpvIoSurfaceHostedView(view = view, videoLayer = videoLayer)
            }
            return result
        }

        private fun removeHostedView(hosted: MacMpvIoSurfaceHostedView) {
            runCatching {
                MacMpvAppKitDispatch.run {
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(hosted.videoLayer, IoSurfaceSelectors.removeFromSuperlayer)
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(hosted.videoLayer, IoSurfaceSelectors.release)
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(hosted.view, IoSurfaceSelectors.removeFromSuperview)
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(hosted.view, IoSurfaceSelectors.release)
                }
            }
        }

        private fun destroyNativeSwapchainForContext(
            context: Pointer?,
            nativeSwapchain: MacMpvIoSurfaceNativeSwapchain,
        ) {
            if (context == null) return
            val setError = ioSurfaceOpenGl.CGLSetCurrentContext(context)
            if (setError == CGL_NO_ERROR) {
                nativeSwapchain.buffers.asReversed().forEach { buffer ->
                    deleteNativeBufferForContext(buffer.textureId, buffer.fboId, buffer.surface)
                }
            } else {
                nativeSwapchain.buffers.asReversed().forEach { buffer ->
                    coreFoundation.CFRelease(buffer.surface)
                }
            }
            checkCglContextClear()
        }
    }
}

private fun MacAppKitViewSnapshot.contentsScale(): Double =
    frame.size.width
        .takeIf { width -> width > 0.0 }
        ?.let { width -> widthPx / width }
        ?.takeIf { scale -> scale.isFinite() && scale > 0.0 }
        ?: 1.0

private fun createIoSurface(
    widthPx: Int,
    heightPx: Int,
): Pointer? {
    val dictionary =
        coreFoundation.CFDictionaryCreateMutable(
            Pointer.NULL,
            IOSURFACE_PROPERTY_COUNT.toLong(),
            Pointer.NULL,
            Pointer.NULL,
        ) ?: return null
    val values = mutableListOf<Pointer>()
    val keys = mutableListOf<Pointer>()
    return try {
        fun putInt(
            keyName: String,
            value: Int,
        ) {
            val key =
                coreFoundation.CFStringCreateWithCString(Pointer.NULL, keyName, CF_STRING_ENCODING_UTF8)
                    ?: error("CFStringCreateWithCString failed")
            val numberMemory = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, value) }
            val number =
                coreFoundation.CFNumberCreate(
                    Pointer.NULL,
                    CF_NUMBER_SINT32_TYPE,
                    numberMemory,
                ) ?: error("CFNumberCreate failed")
            keys += key
            values += number
            coreFoundation.CFDictionarySetValue(dictionary, key, number)
        }
        putInt("IOSurfaceWidth", widthPx)
        putInt("IOSurfaceHeight", heightPx)
        putInt("IOSurfaceBytesPerElement", BYTES_PER_PIXEL)
        putInt("IOSurfacePixelFormat", IOSURFACE_BGRA_PIXEL_FORMAT)
        ioSurface.IOSurfaceCreate(dictionary)
    } finally {
        values.forEach(coreFoundation::CFRelease)
        keys.forEach(coreFoundation::CFRelease)
        coreFoundation.CFRelease(dictionary)
    }
}

private fun checkCglContextClear(prefix: String = "context-clear-failed") {
    val clearError = ioSurfaceOpenGl.CGLSetCurrentContext(Pointer.NULL)
    if (clearError != CGL_NO_ERROR) {
        logMacMpvIoSurfaceFailure(prefix, errorCode = clearError)
    }
}

private const val TIMING_WINDOW_NANOS = 2_000_000_000L

private fun logMacMpvIoSurfaceLifecycle(
    action: DesktopSurfaceAction,
    snapshot: MacAppKitViewSnapshot,
    generation: Long,
) {
    if (!DesktopPlaybackProbe.isEnabled) return
    DesktopPlaybackProbe.emit(
        DesktopSurfaceLifecycleProbeRecord(
            surface = DesktopSurfaceKind.IOSURFACE,
            action = action,
            generation = generation,
            width = snapshot.widthPx,
            height = snapshot.heightPx,
        ),
    )
}

private fun logMacMpvIoSurfaceFailure(
    failure: String,
    failureStage: String? = null,
    reason: String? = null,
    errorCode: Int = 0,
) {
    if (!DesktopPlaybackProbe.isEnabled) return
    DesktopPlaybackProbe.emit(
        DesktopSurfaceFailureProbeRecord(
            surface = DesktopSurfaceKind.IOSURFACE,
            failure = DesktopProbeToken.from(failure),
            failureStage = DesktopProbeToken.from(failureStage),
            reason = DesktopProbeToken.from(reason),
            errorCode = errorCode,
        ),
    )
}

private class MacMpvIoSurfaceHostedView(
    val view: Pointer,
    val videoLayer: Pointer,
)

internal fun videoLayerBounds(snapshot: MacAppKitViewSnapshot): NSRect.ByValue =
    NSRect.ByValue(
        origin = NSPoint.ByValue(0.0, 0.0),
        size = NSSize.ByValue(snapshot.frame.size.width, snapshot.frame.size.height),
    )

@Suppress("FunctionName")
private interface MacMpvIoSurfaceLib : Library {
    fun IOSurfaceCreate(properties: Pointer): Pointer?
}

@Suppress("FunctionName")
private interface MacMpvIoSurfaceCoreFoundationLib : Library {
    fun CFDictionaryCreateMutable(
        allocator: Pointer?,
        capacity: Long,
        keyCallBacks: Pointer?,
        valueCallBacks: Pointer?,
    ): Pointer?

    fun CFDictionarySetValue(
        dictionary: Pointer,
        key: Pointer,
        value: Pointer,
    )

    fun CFStringCreateWithCString(
        allocator: Pointer?,
        string: String,
        encoding: Int,
    ): Pointer?

    fun CFNumberCreate(
        allocator: Pointer?,
        numberType: Int,
        value: Pointer,
    ): Pointer?

    fun CFRelease(value: Pointer)
}

@Suppress("FunctionName")
private interface MacMpvIoSurfaceOpenGlLib : Library {
    fun CGLChoosePixelFormat(
        attributes: Pointer,
        pixelFormat: PointerByReference,
        numberOfScreens: IntByReference,
    ): Int

    fun CGLDestroyPixelFormat(pixelFormat: Pointer)

    fun CGLCreateContext(
        pixelFormat: Pointer,
        share: Pointer?,
        context: PointerByReference,
    ): Int

    fun CGLDestroyContext(context: Pointer)

    fun CGLSetCurrentContext(context: Pointer?): Int

    fun CGLTexImageIOSurface2D(
        context: Pointer,
        target: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        surface: Pointer,
        plane: Int,
    ): Int

    fun glGenTextures(
        count: Int,
        textures: IntByReference,
    )

    fun glBindTexture(
        target: Int,
        texture: Int,
    )

    fun glGenFramebuffers(
        count: Int,
        framebuffers: IntByReference,
    )

    fun glBindFramebuffer(
        target: Int,
        framebuffer: Int,
    )

    fun glFramebufferTexture2D(
        target: Int,
        attachment: Int,
        textarget: Int,
        texture: Int,
        level: Int,
    )

    fun glCheckFramebufferStatus(target: Int): Int

    fun glDeleteFramebuffers(
        count: Int,
        framebuffers: IntByReference,
    )

    fun glDeleteTextures(
        count: Int,
        textures: IntByReference,
    )

    fun glFlush()
}

private object IoSurfaceSelectors {
    val alloc: Pointer by lazy { MacObjectiveCRuntime.selector("alloc") }
    val release: Pointer by lazy { MacObjectiveCRuntime.selector("release") }
    val init: Pointer by lazy { MacObjectiveCRuntime.selector("init") }
    val initWithFrame: Pointer by lazy { MacObjectiveCRuntime.selector("initWithFrame:") }
    val addSublayer: Pointer by lazy { MacObjectiveCRuntime.selector("addSublayer:") }
    val removeFromSuperlayer: Pointer by lazy { MacObjectiveCRuntime.selector("removeFromSuperlayer") }
    val addSubviewPositionedRelativeTo: Pointer by lazy {
        MacObjectiveCRuntime.selector("addSubview:positioned:relativeTo:")
    }
    val layer: Pointer by lazy { MacObjectiveCRuntime.selector("layer") }
    val setFrame: Pointer by lazy { MacObjectiveCRuntime.selector("setFrame:") }
    val setContents: Pointer by lazy { MacObjectiveCRuntime.selector("setContents:") }
    val setContentsScale: Pointer by lazy { MacObjectiveCRuntime.selector("setContentsScale:") }
    val removeFromSuperview: Pointer by lazy { MacObjectiveCRuntime.selector("removeFromSuperview") }
    val begin: Pointer by lazy { MacObjectiveCRuntime.selector("begin") }
    val setDisableActions: Pointer by lazy { MacObjectiveCRuntime.selector("setDisableActions:") }
    val commit: Pointer by lazy { MacObjectiveCRuntime.selector("commit") }
}

private val ioSurface by lazy {
    Native.load(IOSURFACE_FRAMEWORK, MacMpvIoSurfaceLib::class.java)
}
private val coreFoundation by lazy {
    Native.load(CORE_FOUNDATION_FRAMEWORK, MacMpvIoSurfaceCoreFoundationLib::class.java)
}
private val ioSurfaceOpenGl by lazy {
    Native.load(OPENGL_FRAMEWORK, MacMpvIoSurfaceOpenGlLib::class.java)
}
private val ioSurfaceOpenGlLibrary by lazy { NativeLibrary.getInstance(OPENGL_FRAMEWORK) }

private const val IOSURFACE_FRAMEWORK = "/System/Library/Frameworks/IOSurface.framework/IOSurface"
private const val CORE_FOUNDATION_FRAMEWORK = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
private const val OPENGL_FRAMEWORK = "/System/Library/Frameworks/OpenGL.framework/OpenGL"
private const val MPV_IO_SURFACE_VIEW_CLASS = "JellyScopeMpvIOSurfaceView"
private const val IOSURFACE_SWAPCHAIN_BUFFER_COUNT = 3
private const val IOSURFACE_PROPERTY_COUNT = 4
private const val BYTES_PER_PIXEL = 4
private const val IOSURFACE_BGRA_PIXEL_FORMAT = 0x42475241

// Empirically determined during the probe: FBO-texture rendering presented
// via CALayer contents needs the opposite orientation from FBO 0.
private const val IOSURFACE_FLIP_Y = false
private const val CGL_NO_ERROR = 0
private const val CGL_ATTRIB_ACCELERATED = 73
private const val CGL_ATTRIB_OPENGL_PROFILE = 99
private const val CGL_PROFILE_3_2_CORE = 0x3200
private const val GL_TEXTURE_RECTANGLE_ARB = 0x84F5
private const val GL_RGBA8 = 0x8058
private const val GL_BGRA = 0x80E1
private const val GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367
private const val GL_FRAMEBUFFER = 0x8D40
private const val GL_COLOR_ATTACHMENT0 = 0x8CE0
private const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5
private const val CF_NUMBER_SINT32_TYPE = 3
private const val CF_STRING_ENCODING_UTF8 = 0x08000100
private const val NS_VIEW_BELOW = -1L
private val OBJC_YES: Byte = 1
private val CGL_PIXEL_FORMAT_ATTRIBUTES =
    intArrayOf(
        CGL_ATTRIB_ACCELERATED,
        CGL_ATTRIB_OPENGL_PROFILE,
        CGL_PROFILE_3_2_CORE,
        0,
    )
