// SPDX-License-Identifier: MPL-2.0

@file:Suppress("ktlint:standard:filename")

package com.jellyscope.ui.screen.player

import com.jellyscope.core.playback.MpvNativeMetalSurface
import com.jellyscope.core.playback.MpvNativeSurfaceUnavailableReason
import com.jellyscope.core.playback.MpvOpenGlDetachCallback
import com.jellyscope.core.playback.MpvOpenGlDetachDisposition
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** AWT peer disposal is deferred through initialization and actual native destruction. */
internal class MacMpvMetalHost(
    private val onSurfaceReady: (MpvNativeMetalSurface) -> Unit,
    private val onSurfaceUnavailable: (Long, MpvNativeSurfaceUnavailableReason) -> Unit,
    private val requestDetach: (Long, MpvOpenGlDetachCallback) -> Unit,
) : Canvas() {
    private var videoContentSize: Dimension? = null
    private var window: Window? = null
    private var creating = false
    private var detachRequested = false
    private val removing = AtomicBoolean(false)
    private val resizeStamp = AtomicLong(0L)

    @Volatile
    private var generation = 0L

    @Volatile
    private var surface: MacMpvMetalSurface? = null

    private val boundsListener =
        object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent?) = scheduleResize()

            override fun componentResized(event: ComponentEvent?) = scheduleResize()
        }
    private val displayListener = PropertyChangeListener { scheduleResize() }

    init {
        background = Color.BLACK
        isFocusable = false
        ignoreRepaint = true
        enableInputMethods(false)
        focusTraversalKeysEnabled = false
    }

    override fun addNotify() {
        // A peer awaiting a detach acknowledgement cannot be recycled.
        if (isDisplayable) return
        super.addNotify()
        removing.set(false)
        detachRequested = false
        generation = nextMacMpvOpenGlSurfaceGeneration()
        val attachGeneration = generation
        EventQueue.invokeLater {
            if (!isDisplayable || removing.get() || generation != attachGeneration) return@invokeLater
            val containingWindow = SwingUtilities.getWindowAncestor(this)
            val snapshot = containingWindow?.let { MacAppKitPeerResolver.resolve(this, it, videoContentSize) }
            if (containingWindow == null || snapshot == null) {
                onSurfaceUnavailable(attachGeneration, MpvNativeSurfaceUnavailableReason.HostUnavailable)
                return@invokeLater
            }
            window = containingWindow
            addComponentListener(boundsListener)
            addPropertyChangeListener("graphicsConfiguration", displayListener)
            containingWindow.addComponentListener(boundsListener)
            containingWindow.addPropertyChangeListener("graphicsConfiguration", displayListener)
            creating = true
            MacAppKitSurfaceWorker.execute {
                val created = runCatching { MacMpvMetalSurface.create(snapshot, attachGeneration) }.getOrNull()
                EventQueue.invokeLater {
                    creating = false
                    if (removing.get() || generation != attachGeneration) {
                        closeAfterDetach(created, attachGeneration, MpvOpenGlDetachDisposition.Terminated)
                    } else if (created == null) {
                        onSurfaceUnavailable(attachGeneration, MpvNativeSurfaceUnavailableReason.HostUnavailable)
                    } else {
                        surface = created
                        onSurfaceReady(created)
                        scheduleResize()
                    }
                }
            }
        }
    }

    override fun removeNotify() {
        removing.set(true)
        removeComponentListener(boundsListener)
        removePropertyChangeListener("graphicsConfiguration", displayListener)
        window?.removeComponentListener(boundsListener)
        window?.removePropertyChangeListener("graphicsConfiguration", displayListener)
        window = null
        if (creating || detachRequested) return
        val attached = surface
        if (attached == null) {
            super.removeNotify()
            return
        }
        detachRequested = true
        val detachGeneration = generation
        requestDetach(
            detachGeneration,
            MpvOpenGlDetachCallback { disposition -> closeAfterDetach(attached, detachGeneration, disposition) },
        )
    }

    private fun closeAfterDetach(
        attached: MacMpvMetalSurface?,
        detachGeneration: Long,
        disposition: MpvOpenGlDetachDisposition,
    ) {
        MacAppKitSurfaceWorker.execute {
            val closed =
                disposition == MpvOpenGlDetachDisposition.Terminated && runCatching { attached?.close() }.isSuccess
            EventQueue.invokeLater {
                if (generation != detachGeneration) return@invokeLater
                if (!closed) {
                    // AWT's disposer must not collect a peer under an unknown native
                    // owner. Retain the host for the remainder of this experiment.
                    quarantinedMetalHosts.add(this)
                } else {
                    surface = null
                    if (isDisplayable) super.removeNotify()
                }
            }
        }
    }

    override fun contains(
        x: Int,
        y: Int,
    ): Boolean = false

    override fun update(graphics: java.awt.Graphics?) = Unit

    override fun paint(graphics: java.awt.Graphics?) = Unit

    fun setVideoContentSize(
        width: Int,
        height: Int,
    ) {
        val next = Dimension(width.coerceAtLeast(0), height.coerceAtLeast(0))
        val update = {
            if (videoContentSize != next) {
                videoContentSize = next
                scheduleResize()
            }
        }
        if (EventQueue.isDispatchThread()) update() else EventQueue.invokeLater(update)
    }

    private fun scheduleResize() {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater { scheduleResize() }
            return
        }
        if (removing.get()) return
        val containingWindow = window ?: return
        val attached = surface ?: return
        val snapshot = MacAppKitPeerResolver.resolve(this, containingWindow, videoContentSize) ?: return
        val stamp = resizeStamp.incrementAndGet()
        val resizeGeneration = generation
        MacAppKitSurfaceWorker.execute {
            if (
                stamp != resizeStamp.get() || removing.get() || generation != resizeGeneration || surface !== attached
            ) {
                return@execute
            }
            if (runCatching { attached.resize(snapshot) }.isFailure) {
                onSurfaceUnavailable(resizeGeneration, MpvNativeSurfaceUnavailableReason.GeometryUpdateFailed)
            }
        }
    }
}

private class MacMpvMetalSurface private constructor(
    override val generation: Long,
    private val view: Pointer,
    private val layer: Pointer,
    private val device: Pointer,
) : MpvNativeMetalSurface {
    private val closed = AtomicBoolean(false)
    override val metalLayerAddress: Long = Pointer.nativeValue(layer)

    fun resize(snapshot: MacAppKitViewSnapshot) {
        onMetalAppKit {
            if (!closed.get()) updateGeometry(snapshot)
        }
    }

    private fun updateGeometry(snapshot: MacAppKitViewSnapshot) {
        metalTransaction {
            MacObjectiveCRuntime.sendVoidReturnRect(view, MetalSelectors.setFrame, snapshot.frame)
            // NSView coordinates are points; CAMetalLayer.drawableSize is pixels.
            // Read AppKit's current scale as AWT's display-change notification can lag.
            val window = MacObjectiveCRuntime.sendPointerReturnNoArgs(view, MetalSelectors.window)
            val scale =
                window
                    ?.let { MacObjectiveCRuntime.sendDoubleReturnNoArgs(it, MetalSelectors.backingScaleFactor) }
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?: (snapshot.widthPx.toDouble() / snapshot.width.coerceAtLeast(1)).coerceAtLeast(1.0)
            MacObjectiveCRuntime.sendVoidReturnDouble(layer, MetalSelectors.setContentsScale, scale)
            MacObjectiveCRuntime.sendVoidReturnRect(
                layer,
                MetalSelectors.setFrame,
                NSRect.ByValue(
                    NSPoint.ByValue(0.0, 0.0),
                    NSSize.ByValue(snapshot.width.toDouble(), snapshot.height.toDouble()),
                ),
            )
            MacObjectiveCRuntime.sendVoidReturnSize(
                layer,
                MetalSelectors.setDrawableSize,
                NSSize.ByValue(
                    (snapshot.width * scale).roundToInt().coerceAtLeast(1).toDouble(),
                    (snapshot.height * scale).roundToInt().coerceAtLeast(1).toDouble(),
                ),
            )
        }
        // The experimental runtime must detect drawableSize changes and report
        // VO resize/expose events, including while paused. Updating this layer
        // alone leaves mpv's cached video and subtitle rectangles stale.
    }

    override fun close() {
        onMetalAppKit {
            if (!closed.compareAndSet(false, true)) return@onMetalAppKit
            // The delegate is non-retaining. Both alloc references survive until
            // core acknowledges destroy; clear it before either object is released.
            MacObjectiveCRuntime.sendVoidReturnNullablePointer(layer, MetalSelectors.setDelegate, null)
            MacObjectiveCRuntime.sendVoidReturnNullablePointer(view, MetalSelectors.setLayer, null)
            MacObjectiveCRuntime.sendVoidReturnNoArgs(view, MetalSelectors.removeFromSuperview)
            MacObjectiveCRuntime.sendVoidReturnNoArgs(layer, MetalSelectors.release)
            MacObjectiveCRuntime.sendVoidReturnNoArgs(view, MetalSelectors.release)
            // MTLCreateSystemDefaultDevice returns an owned reference, in addition
            // to the layer's strong device property (which MoltenVK may replace).
            MacObjectiveCRuntime.sendVoidReturnNoArgs(device, MetalSelectors.release)
        }
    }

    companion object {
        fun create(
            snapshot: MacAppKitViewSnapshot,
            generation: Long,
        ): MacMpvMetalSurface =
            onMetalAppKit {
                NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit")
                NativeLibrary.getInstance("/System/Library/Frameworks/QuartzCore.framework/QuartzCore")
                val viewClass = checkNotNull(MacAppKitInteropViewClass.resolve("JellyScopeMpvMetalView", "NSView"))
                val layerClass = checkNotNull(MacObjectiveCRuntime.objcGetClass("CAMetalLayer"))
                val device = checkNotNull(metal.MTLCreateSystemDefaultDevice())
                var view: Pointer? = null
                var layer: Pointer? = null
                try {
                    val viewAllocation =
                        checkNotNull(MacObjectiveCRuntime.sendPointerReturnNoArgs(viewClass, MetalSelectors.alloc))
                    // init consumes the allocation, including its nil failure path.
                    val createdView =
                        checkNotNull(
                            MacObjectiveCRuntime.sendPointerReturnRect(viewAllocation, MetalSelectors.initWithFrame, snapshot.frame),
                        )
                    view = createdView
                    val layerAllocation =
                        checkNotNull(MacObjectiveCRuntime.sendPointerReturnNoArgs(layerClass, MetalSelectors.alloc))
                    val createdLayer =
                        checkNotNull(MacObjectiveCRuntime.sendPointerReturnNoArgs(layerAllocation, MetalSelectors.initialize))
                    layer = createdLayer
                    metalTransaction {
                        MacObjectiveCRuntime.sendVoidReturnNullablePointer(createdLayer, MetalSelectors.setDevice, device)
                        MacObjectiveCRuntime.sendVoidReturnUnsignedNativeLong(
                            createdLayer,
                            MetalSelectors.setPixelFormat,
                            NativeLong(MTL_PIXEL_FORMAT_BGRA8_UNORM),
                        )
                        MacObjectiveCRuntime.sendVoidReturnByte(createdLayer, MetalSelectors.setFramebufferOnly, 0)
                        disableMetalLayerActions(createdLayer)
                        // Set the layer before wantsLayer for an app-managed layer-hosting view.
                        MacObjectiveCRuntime.sendVoidReturnNullablePointer(createdView, MetalSelectors.setLayer, createdLayer)
                        MacObjectiveCRuntime.sendVoidReturnByte(createdView, MetalSelectors.setWantsLayer, 1)
                        MacObjectiveCRuntime.sendVoidReturnByte(createdLayer, MetalSelectors.setMasksToBounds, 1)
                        MacAppKitInteropViewClass.applyBlackBackground(createdView)
                        MacAppKitInteropViewClass.applyBelowComposeOrdering(createdView)
                        MacObjectiveCRuntime.sendVoidReturnPointerSignedNativeLongAndNullablePointer(
                            Pointer(snapshot.rootViewAddress),
                            MetalSelectors.addSubview,
                            createdView,
                            NativeLong(-1L),
                            null,
                        )
                        MacObjectiveCRuntime.sendVoidReturnNullablePointer(createdLayer, MetalSelectors.setDelegate, createdView)
                    }
                    MacMpvMetalSurface(generation, createdView, createdLayer, device).also { it.updateGeometry(snapshot) }
                } catch (throwable: Throwable) {
                    layer?.let { MacObjectiveCRuntime.sendVoidReturnNullablePointer(it, MetalSelectors.setDelegate, null) }
                    view?.let {
                        MacObjectiveCRuntime.sendVoidReturnNullablePointer(it, MetalSelectors.setLayer, null)
                        MacObjectiveCRuntime.sendVoidReturnNoArgs(it, MetalSelectors.removeFromSuperview)
                        MacObjectiveCRuntime.sendVoidReturnNoArgs(it, MetalSelectors.release)
                    }
                    layer?.let { MacObjectiveCRuntime.sendVoidReturnNoArgs(it, MetalSelectors.release) }
                    MacObjectiveCRuntime.sendVoidReturnNoArgs(device, MetalSelectors.release)
                    throw throwable
                }
            }
    }
}

/** Capture failures inside the JNA callback instead of letting JNA swallow them. */
private fun <T> onMetalAppKit(action: () -> T): T {
    var result: Result<T>? = null
    MacMpvAppKitDispatch.run { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
}

private fun metalTransaction(action: () -> Unit) {
    val transaction = checkNotNull(MacObjectiveCRuntime.objcGetClass("CATransaction"))
    MacObjectiveCRuntime.sendVoidReturnNoArgs(transaction, MetalSelectors.begin)
    try {
        MacObjectiveCRuntime.sendVoidReturnByte(transaction, MetalSelectors.setDisableActions, 1)
        action()
    } finally {
        MacObjectiveCRuntime.sendVoidReturnNoArgs(transaction, MetalSelectors.commit)
    }
}

private fun disableMetalLayerActions(layer: Pointer) {
    val names = listOf("bounds", "position", "contents", "contentsScale", "drawableSize", "sublayers")
    val stringClass = checkNotNull(MacObjectiveCRuntime.objcGetClass("NSString"))
    val nullValue =
        checkNotNull(
            MacObjectiveCRuntime.sendPointerReturnNoArgs(
                checkNotNull(MacObjectiveCRuntime.objcGetClass("NSNull")),
                MetalSelectors.nullValue,
            ),
        )
    Memory((names.size * Native.POINTER_SIZE).toLong()).use { keys ->
        Memory(keys.size()).use { values ->
            names.forEachIndexed { index, name ->
                Memory(name.length.toLong() + 1).use { bytes ->
                    bytes.setString(0L, name)
                    val key =
                        checkNotNull(MacObjectiveCRuntime.sendPointerReturnOnePointer(stringClass, MetalSelectors.stringWithUtf8, bytes))
                    keys.setPointer(index.toLong() * Native.POINTER_SIZE, key)
                    values.setPointer(index.toLong() * Native.POINTER_SIZE, nullValue)
                }
            }
            val dictionary =
                checkNotNull(
                    MacObjectiveCRuntime.sendPointerReturnTwoPointersAndUnsignedNativeLong(
                        checkNotNull(MacObjectiveCRuntime.objcGetClass("NSDictionary")),
                        MetalSelectors.dictionaryWithObjectsForKeysCount,
                        values,
                        keys,
                        NativeLong(names.size.toLong()),
                    ),
                )
            MacObjectiveCRuntime.sendVoidReturnNullablePointer(layer, MetalSelectors.setActions, dictionary)
        }
    }
}

private object MetalSelectors {
    val alloc = MacObjectiveCRuntime.selector("alloc")
    val initialize = MacObjectiveCRuntime.selector("init")
    val release = MacObjectiveCRuntime.selector("release")
    val initWithFrame = MacObjectiveCRuntime.selector("initWithFrame:")
    val setLayer = MacObjectiveCRuntime.selector("setLayer:")
    val setWantsLayer = MacObjectiveCRuntime.selector("setWantsLayer:")
    val setDevice = MacObjectiveCRuntime.selector("setDevice:")
    val setDelegate = MacObjectiveCRuntime.selector("setDelegate:")
    val setPixelFormat = MacObjectiveCRuntime.selector("setPixelFormat:")
    val setFramebufferOnly = MacObjectiveCRuntime.selector("setFramebufferOnly:")
    val setMasksToBounds = MacObjectiveCRuntime.selector("setMasksToBounds:")
    val setContentsScale = MacObjectiveCRuntime.selector("setContentsScale:")
    val setDrawableSize = MacObjectiveCRuntime.selector("setDrawableSize:")
    val setFrame = MacObjectiveCRuntime.selector("setFrame:")
    val window = MacObjectiveCRuntime.selector("window")
    val backingScaleFactor = MacObjectiveCRuntime.selector("backingScaleFactor")
    val addSubview = MacObjectiveCRuntime.selector("addSubview:positioned:relativeTo:")
    val removeFromSuperview = MacObjectiveCRuntime.selector("removeFromSuperview")
    val begin = MacObjectiveCRuntime.selector("begin")
    val commit = MacObjectiveCRuntime.selector("commit")
    val setDisableActions = MacObjectiveCRuntime.selector("setDisableActions:")
    val setActions = MacObjectiveCRuntime.selector("setActions:")
    val nullValue = MacObjectiveCRuntime.selector("null")
    val stringWithUtf8 = MacObjectiveCRuntime.selector("stringWithUTF8String:")
    val dictionaryWithObjectsForKeysCount = MacObjectiveCRuntime.selector("dictionaryWithObjects:forKeys:count:")
}

@Suppress("FunctionName")
private interface MetalLib : Library {
    fun MTLCreateSystemDefaultDevice(): Pointer?
}

private val metal by lazy { Native.load("/System/Library/Frameworks/Metal.framework/Metal", MetalLib::class.java) }
private val quarantinedMetalHosts = mutableSetOf<MacMpvMetalHost>() // EDT-owned, process lifetime.
private const val MTL_PIXEL_FORMAT_BGRA8_UNORM = 80L
