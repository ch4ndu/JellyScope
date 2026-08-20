// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.playback.DesktopPlaybackProbe
import com.jellyscope.core.playback.DesktopProbeToken
import com.jellyscope.core.playback.DesktopSurfaceAction
import com.jellyscope.core.playback.DesktopSurfaceFailureProbeRecord
import com.jellyscope.core.playback.DesktopSurfaceKind
import com.jellyscope.core.playback.DesktopSurfaceLifecycleProbeRecord
import com.jellyscope.core.playback.MpvOpenGlDetachCallback
import com.jellyscope.core.playback.MpvOpenGlDetachDisposition
import com.jellyscope.core.playback.MpvOpenGlFramebuffer
import com.jellyscope.core.playback.MpvOpenGlRenderSurface
import com.jellyscope.core.playback.MpvOpenGlSurfaceKind
import com.jellyscope.core.playback.MpvOpenGlSurfaceUnavailableReason
import com.jellyscope.core.util.nextPositiveGeneration
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.awt.Canvas
import java.awt.Color
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

internal interface MacMpvHostedSurface : MpvOpenGlRenderSurface {
    fun resize(snapshot: MacAppKitViewSnapshot): Boolean
}

internal class MacMpvOpenGlHost(
    private val onSurfaceReady: (MpvOpenGlRenderSurface) -> Unit,
    private val onSurfaceUnavailable: (Long, MpvOpenGlSurfaceUnavailableReason) -> Unit,
    private val onSurfaceResized: (Long) -> Unit,
    private val requestDetach: (Long, MpvOpenGlDetachCallback) -> Unit,
) : Canvas() {
    @Volatile
    private var videoContentSize: java.awt.Dimension? = null
    private val detachStarted = AtomicBoolean(false)
    private val detachAcknowledged = AtomicBoolean(false)
    private val resizeRequestStamp = AtomicLong(0L)

    @Volatile
    private var generation = 0L

    @Volatile
    private var surface: MacMpvHostedSurface? = null
    private var window: Window? = null
    private val boundsListener =
        object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent?) = scheduleResize()

            override fun componentResized(event: ComponentEvent?) = scheduleResize()
        }

    init {
        background = Color.BLACK
        isFocusable = false
        ignoreRepaint = true
        enableInputMethods(false)
        focusTraversalKeysEnabled = false
    }

    override fun addNotify() {
        super.addNotify()
        detachStarted.set(false)
        detachAcknowledged.set(false)
        generation = nextMacMpvOpenGlSurfaceGeneration()
        val attachGeneration = generation
        EventQueue.invokeLater {
            if (!isDisplayable || detachStarted.get() || generation != attachGeneration) return@invokeLater
            if (!isMacOs()) {
                onSurfaceUnavailable(
                    attachGeneration,
                    MpvOpenGlSurfaceUnavailableReason.UnsupportedOperatingSystem,
                )
                return@invokeLater
            }
            val containingWindow =
                SwingUtilities.getWindowAncestor(this)
                    ?: run {
                        onSurfaceUnavailable(
                            attachGeneration,
                            MpvOpenGlSurfaceUnavailableReason.ComponentNotDisplayable,
                        )
                        return@invokeLater
                    }
            window = containingWindow
            addComponentListener(boundsListener)
            containingWindow.addComponentListener(boundsListener)
            val snapshot =
                MacAppKitPeerResolver.resolve(this, containingWindow, videoContentSize)
                    ?: run {
                        onSurfaceUnavailable(
                            attachGeneration,
                            MpvOpenGlSurfaceUnavailableReason.InaccessibleJdkInternals,
                        )
                        return@invokeLater
                    }
            MacAppKitSurfaceWorker.execute {
                val created =
                    createMacMpvSurface(
                        snapshot = snapshot,
                        generation = attachGeneration,
                        requestRedraw = onSurfaceResized,
                    )
                if (
                    created == null ||
                    detachStarted.get() ||
                    generation != attachGeneration
                ) {
                    created?.close()
                    if (!detachStarted.get() && generation == attachGeneration) {
                        onSurfaceUnavailable(
                            attachGeneration,
                            if (created == null) {
                                MpvOpenGlSurfaceUnavailableReason.OpenGlUnavailable
                            } else {
                                MpvOpenGlSurfaceUnavailableReason.StaleGeneration
                            },
                        )
                    }
                    return@execute
                }
                surface = created
                onSurfaceReady(created)
                onSurfaceResized(attachGeneration)
            }
        }
    }

    override fun removeNotify() {
        removeComponentListener(boundsListener)
        window?.let { containingWindow ->
            containingWindow.removeComponentListener(boundsListener)
        }
        window = null
        val attachedSurface = surface
        if (attachedSurface == null || detachAcknowledged.get()) {
            detachStarted.set(true)
            super.removeNotify()
            return
        }
        if (detachStarted.compareAndSet(false, true)) {
            val detachGeneration = generation
            requestDetach(
                detachGeneration,
                MpvOpenGlDetachCallback { disposition ->
                    MacAppKitSurfaceWorker.execute {
                        closeOpenGlSurfaceAfterDetach(attachedSurface, disposition)
                        EventQueue.invokeLater {
                            if (generation == detachGeneration && surface === attachedSurface) {
                                surface = null
                                detachAcknowledged.set(true)
                            }
                            finishPeerRemoval()
                        }
                    }
                },
            )
        }
    }

    override fun contains(
        x: Int,
        y: Int,
    ): Boolean = false

    override fun update(graphics: java.awt.Graphics?) = Unit

    override fun paint(graphics: java.awt.Graphics?) = Unit

    /** Compose-provided video extent in AWT points; see MacAppKitPeerResolver. */
    fun setVideoContentSize(
        width: Int,
        height: Int,
    ) {
        val next = java.awt.Dimension(width.coerceAtLeast(0), height.coerceAtLeast(0))
        if (videoContentSize == next) return
        videoContentSize = next
        val update = { scheduleResize() }
        if (EventQueue.isDispatchThread()) update() else EventQueue.invokeLater(update)
    }

    private fun scheduleResize() {
        if (!EventQueue.isDispatchThread() || detachStarted.get()) return
        val containingWindow = window ?: return
        val snapshot = MacAppKitPeerResolver.resolve(this, containingWindow, videoContentSize) ?: return
        val resizeGeneration = generation
        val attachedSurface = surface ?: return
        // Live drag-resize fires many events; each IOSurface resize rebuilds
        // a full swapchain, so a backlog of stale rebuilds delayed the video's
        // visual growth by seconds. Stamp each request and skip superseded
        // ones so the worker only ever rebuilds for the freshest size.
        val stamp = resizeRequestStamp.incrementAndGet()
        MacAppKitSurfaceWorker.execute {
            if (
                resizeRequestStamp.get() == stamp &&
                !detachStarted.get() &&
                generation == resizeGeneration &&
                surface === attachedSurface
            ) {
                if (attachedSurface.resize(snapshot)) {
                    onSurfaceResized(resizeGeneration)
                } else {
                    logMacMpvResizeFailure(snapshot)
                }
            }
        }
    }

    private fun finishPeerRemoval() {
        if (isDisplayable) {
            super.removeNotify()
        }
    }

    private fun isMacOs(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .contains("mac", ignoreCase = true)
}

internal fun closeOpenGlSurfaceAfterDetach(
    surface: MpvOpenGlRenderSurface,
    disposition: MpvOpenGlDetachDisposition,
) {
    if (disposition != MpvOpenGlDetachDisposition.Quarantined) {
        surface.close()
    }
}

private class MacMpvOpenGlRenderSurface private constructor(
    override val generation: Long,
    private val view: Pointer,
    private val context: Pointer,
    private val cglContext: Pointer,
    widthPx: Int,
    heightPx: Int,
) : MacMpvHostedSurface {
    private val lock = Any()
    private var framebuffer = MpvOpenGlFramebuffer(widthPx, heightPx)
    private var contextUpdateRequired = true
    private var closed = false

    override val surfaceKind: MpvOpenGlSurfaceKind = MpvOpenGlSurfaceKind.OpenGL
    override val presentationLabel: String = "OpenGL Render API"

    override fun resolveGlSymbol(name: String): Long =
        runCatching {
            Pointer.nativeValue(openGlLibrary.getFunction(name))
        }.getOrDefault(0L)

    override fun withCurrentContext(render: (MpvOpenGlFramebuffer) -> Boolean): Boolean =
        synchronized(lock) {
            if (closed) {
                return@synchronized false
            }
            if (contextUpdateRequired) {
                MacMpvAppKitDispatch.run {
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(context, Selectors.update)
                }
                contextUpdateRequired = false
            }
            if (openGl.CGLLockContext(cglContext) != CGL_NO_ERROR) return@synchronized false
            try {
                MacObjectiveCRuntime.sendVoidReturnNoArgs(context, Selectors.makeCurrentContext)
                val currentFramebuffer = framebuffer
                val rendered =
                    if (currentFramebuffer.widthPx > 0 && currentFramebuffer.heightPx > 0) {
                        render(currentFramebuffer)
                    } else {
                        false
                    }
                if (rendered) {
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(context, Selectors.flushBuffer)
                }
                true
            } finally {
                openGl.CGLUnlockContext(cglContext)
            }
        }

    override fun resize(snapshot: MacAppKitViewSnapshot): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false
            runCatching {
                MacMpvAppKitDispatch.run {
                    MacObjectiveCRuntime.sendVoidReturnRect(view, Selectors.setFrame, snapshot.frame)
                }
                framebuffer = MpvOpenGlFramebuffer(snapshot.widthPx, snapshot.heightPx)
                contextUpdateRequired = true
                logPlaybackProbeSurface("resize", snapshot)
                true
            }.getOrDefault(false)
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            MacMpvAppKitDispatch.run {
                runCatching {
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(context, Selectors.clearDrawable)
                }
                MacObjectiveCRuntime.sendVoidReturnNoArgs(view, Selectors.removeFromSuperview)
                MacObjectiveCRuntime.sendVoidReturnNoArgs(view, Selectors.release)
            }
        }
    }

    companion object {
        fun create(
            snapshot: MacAppKitViewSnapshot,
            generation: Long,
        ): MacMpvHostedSurface? {
            if (generation <= 0L) return null
            var result: MacMpvHostedSurface? = null
            MacMpvAppKitDispatch.run {
                NativeLibrary.getInstance(APP_KIT_FRAMEWORK)
                val attributes =
                    Memory((OPENGL_PIXEL_FORMAT_ATTRIBUTES.size * Int.SIZE_BYTES).toLong()).apply {
                        OPENGL_PIXEL_FORMAT_ATTRIBUTES.forEachIndexed { index, attribute ->
                            setInt(index.toLong() * Int.SIZE_BYTES, attribute)
                        }
                    }
                val pixelFormatAllocation =
                    MacObjectiveCRuntime.sendPointerReturnNoArgs(
                        MacObjectiveCRuntime.objcGetClass("NSOpenGLPixelFormat")
                            ?: return@run,
                        Selectors.alloc,
                    ) ?: return@run
                val pixelFormat =
                    MacObjectiveCRuntime.sendPointerReturnOnePointer(
                        pixelFormatAllocation,
                        Selectors.initWithAttributes,
                        attributes,
                    )
                if (pixelFormat == null) {
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(pixelFormatAllocation, Selectors.release)
                    return@run
                }
                val viewClass =
                    MacAppKitInteropViewClass.resolve(
                        className = MPV_OPEN_GL_VIEW_CLASS,
                        superclassName = "NSOpenGLView",
                    ) ?: return@run
                val viewAllocation =
                    MacObjectiveCRuntime.sendPointerReturnNoArgs(
                        viewClass,
                        Selectors.alloc,
                    )
                val view =
                    viewAllocation?.let { allocation ->
                        MacObjectiveCRuntime.sendPointerReturnRectAndPointer(
                            allocation,
                            Selectors.initWithFramePixelFormat,
                            snapshot.frame,
                            pixelFormat,
                        )
                    }
                MacObjectiveCRuntime.sendVoidReturnNoArgs(pixelFormat, Selectors.release)
                if (view == null) {
                    viewAllocation?.let { allocation ->
                        MacObjectiveCRuntime.sendVoidReturnNoArgs(allocation, Selectors.release)
                    }
                    return@run
                }
                MacObjectiveCRuntime.sendVoidReturnByte(
                    view,
                    Selectors.setWantsBestResolutionOpenGlSurface,
                    OBJC_YES,
                )
                val context = MacObjectiveCRuntime.sendPointerReturnNoArgs(view, Selectors.openGlContext)
                val cglContext = context?.let { value -> MacObjectiveCRuntime.sendPointerReturnNoArgs(value, Selectors.cglContextObj) }
                if (context == null || cglContext == null || Pointer.nativeValue(cglContext) == 0L) {
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(view, Selectors.release)
                    return@run
                }
                // Swap interval 0: flushBuffer must not block on display
                // refresh. The view is layer-backed (composited, no tearing),
                // and under pointer-driven Compose redraw load a vsync-blocked
                // swap starves mpv's render thread into output frame drops
                // (measured ~50-90 drops/s while the scene repaints).
                val swapInterval =
                    Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, 0) }
                MacObjectiveCRuntime.sendVoidReturnPointerAndSignedNativeLong(
                    context,
                    Selectors.setValuesForParameter,
                    swapInterval,
                    NativeLong(NS_OPENGL_CONTEXT_PARAMETER_SWAP_INTERVAL),
                )
                // NSOpenGLView can briefly expose the native window backing
                // color before mpv renders its first frame. Present a black
                // buffer immediately so startup never flashes white.
                MacObjectiveCRuntime.sendVoidReturnNoArgs(context, Selectors.makeCurrentContext)
                openGl.glClearColor(0f, 0f, 0f, 1f)
                openGl.glClear(GL_COLOR_BUFFER_BIT)
                MacObjectiveCRuntime.sendVoidReturnNoArgs(context, Selectors.flushBuffer)
                val rootView = Pointer(snapshot.rootViewAddress)
                MacObjectiveCRuntime.sendVoidReturnPointerSignedNativeLongAndNullablePointer(
                    rootView,
                    Selectors.addSubviewPositionedRelativeTo,
                    view,
                    NativeLong(NS_VIEW_BELOW),
                    null,
                )
                MacAppKitInteropViewClass.applyBelowComposeOrdering(view)
                MacAppKitInteropViewClass.logViewHierarchy(rootView, "mpv-attach")
                result =
                    MacMpvOpenGlRenderSurface(
                        generation = generation,
                        view = view,
                        context = context,
                        cglContext = cglContext,
                        widthPx = snapshot.widthPx,
                        heightPx = snapshot.heightPx,
                    )
                logPlaybackProbeSurface("attach", snapshot)
            }
            return result
        }
    }
}

private fun createMacMpvSurface(
    snapshot: MacAppKitViewSnapshot,
    generation: Long,
    requestRedraw: (Long) -> Unit,
): MacMpvHostedSurface? {
    if (macMpvOpenGlSurfaceForced()) {
        logMacMpvOpenGlSurfaceForced(snapshot, generation)
        return runCatching {
            MacMpvOpenGlRenderSurface.create(snapshot, generation)
        }.getOrNull()
    }
    val ioSurface =
        runCatching {
            MacMpvIoSurfacePresentation.create(
                snapshot = snapshot,
                generation = generation,
                requestRedraw = { redrawGeneration ->
                    requestRedraw(redrawGeneration)
                },
            )
        }.getOrNull()
    if (ioSurface != null) return ioSurface
    logMacMpvSurfaceFallback(snapshot, generation)
    return runCatching {
        MacMpvOpenGlRenderSurface.create(snapshot, generation)
    }.getOrNull()
}

internal fun logMacMpvSurfaceFallback(
    snapshot: MacAppKitViewSnapshot,
    generation: Long,
) {
    if (!DesktopPlaybackProbe.isEnabled) return
    DesktopPlaybackProbe.emit(
        DesktopSurfaceFailureProbeRecord(
            surface = DesktopSurfaceKind.IOSURFACE,
            failure = DesktopProbeToken.from("fallback"),
            action = DesktopProbeToken.from("useOpenGl"),
            generation = generation,
            width = snapshot.widthPx,
            height = snapshot.heightPx,
        ),
    )
}

internal fun logMacMpvResizeFailure(snapshot: MacAppKitViewSnapshot) {
    if (!DesktopPlaybackProbe.isEnabled) return
    DesktopPlaybackProbe.emit(
        DesktopSurfaceFailureProbeRecord(
            surface = DesktopSurfaceKind.IOSURFACE,
            failure = DesktopProbeToken.from("resize"),
            action = DesktopProbeToken.from("keepLastGood"),
            width = snapshot.widthPx,
            height = snapshot.heightPx,
        ),
    )
}

private fun macMpvOpenGlSurfaceForced(): Boolean = System.getProperty(FORCE_MPV_OPENGL_SURFACE_PROPERTY)?.toBooleanStrictOrNull() == true

private fun logMacMpvOpenGlSurfaceForced(
    snapshot: MacAppKitViewSnapshot,
    generation: Long,
) {
    if (!DesktopPlaybackProbe.isEnabled) return
    DesktopPlaybackProbe.emit(
        DesktopSurfaceLifecycleProbeRecord(
            surface = DesktopSurfaceKind.OPENGL,
            action = DesktopSurfaceAction.FORCED,
            generation = generation,
            width = snapshot.widthPx,
            height = snapshot.heightPx,
        ),
    )
}

internal object MacMpvAppKitDispatch {
    private val dispatch by lazy { Native.load("System", MpvOpenGlDispatchLib::class.java) }
    private val mainQueue by lazy {
        NativeLibrary
            .getInstance("System")
            .getGlobalVariableAddress("_dispatch_main_q")
    }

    fun run(action: () -> Unit) {
        val work = MpvOpenGlDispatchLib.DispatchWork { _ -> action() }
        dispatch.dispatch_sync_f(mainQueue, null, work)
    }
}

private object Selectors {
    val alloc: Pointer by lazy { MacObjectiveCRuntime.selector("alloc") }
    val release: Pointer by lazy { MacObjectiveCRuntime.selector("release") }
    val initWithAttributes: Pointer by lazy { MacObjectiveCRuntime.selector("initWithAttributes:") }
    val initWithFramePixelFormat: Pointer by lazy { MacObjectiveCRuntime.selector("initWithFrame:pixelFormat:") }
    val setWantsBestResolutionOpenGlSurface: Pointer by lazy {
        MacObjectiveCRuntime.selector("setWantsBestResolutionOpenGLSurface:")
    }
    val openGlContext: Pointer by lazy { MacObjectiveCRuntime.selector("openGLContext") }
    val cglContextObj: Pointer by lazy { MacObjectiveCRuntime.selector("CGLContextObj") }
    val addSubviewPositionedRelativeTo: Pointer by lazy {
        MacObjectiveCRuntime.selector("addSubview:positioned:relativeTo:")
    }
    val setFrame: Pointer by lazy { MacObjectiveCRuntime.selector("setFrame:") }
    val setValuesForParameter: Pointer by lazy { MacObjectiveCRuntime.selector("setValues:forParameter:") }
    val makeCurrentContext: Pointer by lazy { MacObjectiveCRuntime.selector("makeCurrentContext") }
    val update: Pointer by lazy { MacObjectiveCRuntime.selector("update") }
    val flushBuffer: Pointer by lazy { MacObjectiveCRuntime.selector("flushBuffer") }
    val clearDrawable: Pointer by lazy { MacObjectiveCRuntime.selector("clearDrawable") }
    val removeFromSuperview: Pointer by lazy { MacObjectiveCRuntime.selector("removeFromSuperview") }
}

@Suppress("FunctionName")
private interface OpenGlLib : Library {
    fun CGLLockContext(context: Pointer): Int

    fun CGLUnlockContext(context: Pointer): Int

    fun glClearColor(
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    )

    fun glClear(mask: Int)
}

@Suppress("FunctionName")
private interface MpvOpenGlDispatchLib : Library {
    fun dispatch_sync_f(
        queue: Pointer,
        context: Pointer?,
        work: DispatchWork,
    )

    fun interface DispatchWork : Callback {
        fun invoke(context: Pointer?)
    }
}

private fun nextMacMpvOpenGlSurfaceGeneration(): Long {
    while (true) {
        val current = macMpvOpenGlSurfaceGeneration.get()
        val next = nextPositiveGeneration(current)
        if (macMpvOpenGlSurfaceGeneration.compareAndSet(current, next)) return next
    }
}

private const val APP_KIT_FRAMEWORK = "/System/Library/Frameworks/AppKit.framework/AppKit"
private const val OPENGL_FRAMEWORK = "/System/Library/Frameworks/OpenGL.framework/OpenGL"
private const val NS_OPENGL_PFA_DOUBLE_BUFFER = 5
private const val NS_OPENGL_PFA_ACCELERATED = 73
private const val CGL_NO_ERROR = 0
private const val GL_COLOR_BUFFER_BIT = 0x00004000
private const val MPV_OPEN_GL_VIEW_CLASS = "JellyScopeMpvOpenGLView"
private const val NS_VIEW_BELOW = -1L
private const val NS_OPENGL_CONTEXT_PARAMETER_SWAP_INTERVAL = 222L
private val OBJC_YES: Byte = 1
private val OPENGL_PIXEL_FORMAT_ATTRIBUTES =
    intArrayOf(
        NS_OPENGL_PFA_DOUBLE_BUFFER,
        NS_OPENGL_PFA_ACCELERATED,
        0,
    )
private val openGl by lazy { Native.load(OPENGL_FRAMEWORK, OpenGlLib::class.java) }
private val openGlLibrary by lazy { NativeLibrary.getInstance(OPENGL_FRAMEWORK) }
private val macMpvOpenGlSurfaceGeneration = AtomicLong(0L)

private fun logPlaybackProbeSurface(
    event: String,
    snapshot: MacAppKitViewSnapshot,
) {
    if (!DesktopPlaybackProbe.isEnabled) return
    DesktopPlaybackProbe.emit(
        DesktopSurfaceLifecycleProbeRecord(
            surface = DesktopSurfaceKind.OPENGL,
            action = if (event == "attach") DesktopSurfaceAction.ATTACH else DesktopSurfaceAction.RESIZE,
            width = snapshot.widthPx,
            height = snapshot.heightPx,
        ),
    )
}

private const val FORCE_MPV_OPENGL_SURFACE_PROPERTY = "jellyscope.desktop.forceMpvOpenGlSurface"
