// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.playback.DesktopPlaybackProbe
import com.jellyscope.core.playback.DesktopSurfaceAction
import com.jellyscope.core.playback.DesktopSurfaceKind
import com.jellyscope.core.playback.DesktopSurfaceLifecycleProbeRecord
import com.jellyscope.core.playback.DesktopVlcSurfaceHandle
import com.jellyscope.core.util.nextPositiveGeneration
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Canvas
import java.awt.Color
import java.awt.Component
import java.awt.EventQueue
import java.awt.Insets
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.lang.reflect.InaccessibleObjectException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

internal class MacLibVlcNativeHost(
    private val onSurfaceReady: (DesktopVlcSurfaceHandle) -> Unit,
    private val onSurfaceUnavailable: (Long) -> Unit,
    private val onSurfaceResized: (Long, Int, Int) -> Unit,
    private val requestDetach: (Long, () -> Unit) -> Unit,
) : Canvas() {
    private val detachStarted = AtomicBoolean(false)
    private val detachAcknowledged = AtomicBoolean(false)
    private val nativeViewCreated = AtomicBoolean(false)

    // Shared between the EDT and the AppKit surface worker, matching the mpv host.
    @Volatile
    private var generation = 0L

    @Volatile
    private var nativeViewAddress = 0L
    private var window: Window? = null

    @Volatile
    private var videoVisible = false

    @Volatile
    private var videoContentSize: java.awt.Dimension? = null

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
        nativeViewCreated.set(false)
        generation = nextMacLibVlcSurfaceGeneration()
        val attachGeneration = generation
        EventQueue.invokeLater {
            if (!isDisplayable || detachStarted.get()) return@invokeLater
            val containingWindow = SwingUtilities.getWindowAncestor(this) ?: return@invokeLater
            window = containingWindow
            addComponentListener(boundsListener)
            containingWindow.addComponentListener(boundsListener)
            val snapshot =
                MacAppKitPeerResolver.resolve(this, containingWindow, videoContentSize)
                    ?: run {
                        onSurfaceUnavailable(generation)
                        return@invokeLater
                    }
            MacAppKitSurfaceWorker.execute {
                val address =
                    runCatching { MacLibVlcAppKit.createView(snapshot) }
                        .getOrDefault(0L)
                if (address <= 0L || detachStarted.get() || generation != attachGeneration) {
                    if (address > 0L) runCatching { MacLibVlcAppKit.removeView(address) }
                    if (!detachStarted.get() && generation == attachGeneration) {
                        onSurfaceUnavailable(attachGeneration)
                    }
                    return@execute
                }
                val handle = DesktopVlcSurfaceHandle.create(address, attachGeneration)
                if (handle == null) {
                    MacLibVlcAppKit.removeView(address)
                    return@execute
                }
                nativeViewAddress = address
                nativeViewCreated.set(true)
                MacLibVlcAppKit.setViewVisible(address, videoVisible)
                onSurfaceReady(handle)
                onSurfaceResized(attachGeneration, snapshot.width, snapshot.height)
            }
        }
    }

    override fun removeNotify() {
        removeComponentListener(boundsListener)
        window?.let { containingWindow ->
            containingWindow.removeComponentListener(boundsListener)
        }
        window = null
        if (!nativeViewCreated.get() || detachAcknowledged.get()) {
            detachStarted.set(true)
            super.removeNotify()
            return
        }
        if (detachStarted.compareAndSet(false, true)) {
            val detachGeneration = generation
            val detachAddress = nativeViewAddress
            requestDetach(detachGeneration) {
                MacAppKitSurfaceWorker.execute {
                    if (detachAddress > 0L) runCatching { MacLibVlcAppKit.removeView(detachAddress) }
                    EventQueue.invokeLater {
                        if (generation == detachGeneration) {
                            if (nativeViewAddress == detachAddress) nativeViewAddress = 0L
                            nativeViewCreated.set(false)
                            detachAcknowledged.set(true)
                        }
                        finishPeerRemoval()
                    }
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

    fun setVideoVisible(visible: Boolean) {
        videoVisible = visible
        val address = nativeViewAddress
        if (address <= 0L || !nativeViewCreated.get() || detachStarted.get()) return
        MacAppKitSurfaceWorker.execute {
            if (nativeViewAddress == address && !detachStarted.get()) {
                MacLibVlcAppKit.setViewVisible(address, visible)
            }
        }
    }

    private fun scheduleResize() {
        if (!EventQueue.isDispatchThread() || !nativeViewCreated.get() || detachStarted.get()) return
        val containingWindow = window ?: return
        val snapshot = MacAppKitPeerResolver.resolve(this, containingWindow, videoContentSize) ?: return
        val address = nativeViewAddress
        val resizeGeneration = generation
        if (address <= 0L) return
        MacAppKitSurfaceWorker.execute {
            if (!detachStarted.get() && nativeViewAddress == address && generation == resizeGeneration) {
                val resized = runCatching { MacLibVlcAppKit.resizeView(address, snapshot.frame) }.isSuccess
                if (resized) {
                    onSurfaceResized(resizeGeneration, snapshot.width, snapshot.height)
                } else {
                    onSurfaceUnavailable(resizeGeneration)
                }
            }
        }
    }

    private fun finishPeerRemoval() {
        if (isDisplayable) {
            super.removeNotify()
        }
    }
}

internal data class MacAppKitViewSnapshot(
    val rootViewAddress: Long,
    val frame: NSRect.ByValue,
    val width: Int,
    val height: Int,
    val widthPx: Int = width,
    val heightPx: Int = height,
)

internal object MacAppKitPeerResolver {
    /**
     * [contentSize] overrides the component's own size: the hosting
     * SwingPanel is a 1dp anchor at the video area's top-left (a full-size
     * interop rectangle steals clicks from in-scene Compose controls on
     * macOS), so the video extent comes from Compose layout instead.
     */
    fun resolve(
        component: Component,
        window: Window,
        contentSize: java.awt.Dimension? = null,
    ): MacAppKitViewSnapshot? {
        if (!isMacOs() || !EventQueue.isDispatchThread() || !component.isDisplayable) return null
        val rootAddress = resolveWindowAwtViewAddress(window) ?: return null
        val relative = SwingUtilities.convertPoint(component, 0, 0, window)
        val insets: Insets = window.insets
        val contentX = (relative.x - insets.left).coerceAtLeast(0)
        val contentY = (relative.y - insets.top).coerceAtLeast(0)
        val width = (contentSize?.width ?: component.width).coerceAtLeast(0)
        val height = (contentSize?.height ?: component.height).coerceAtLeast(0)
        val transform = component.graphicsConfiguration?.defaultTransform
        val scaleX = transform?.scaleX?.takeIf { value -> value.isFinite() && value > 0.0 } ?: 1.0
        val scaleY = transform?.scaleY?.takeIf { value -> value.isFinite() && value > 0.0 } ?: 1.0
        val contentHeight = (window.height - insets.top - insets.bottom).coerceAtLeast(0)
        val frame =
            calculateMacAppKitViewFrame(
                contentX = contentX,
                contentY = contentY,
                contentHeight = contentHeight,
                width = width,
                height = height,
            )
        return MacAppKitViewSnapshot(
            rootViewAddress = rootAddress,
            frame = frame,
            width = width,
            height = height,
            widthPx = (width * scaleX).roundToInt().coerceAtLeast(0),
            heightPx = (height * scaleY).roundToInt().coerceAtLeast(0),
        )
    }

    internal fun resolveWindowAwtViewAddress(window: Window): Long? =
        try {
            val accessorClass = Class.forName("sun.awt.AWTAccessor")
            val componentAccessor = accessorClass.getMethod("getComponentAccessor").invoke(null)
            val peer =
                Class
                    .forName("sun.awt.AWTAccessor\$ComponentAccessor")
                    .getMethod("getPeer", Component::class.java)
                    .invoke(componentAccessor, window)
                    ?: return null
            val platformWindow = peer.javaClass.getMethod("getPlatformWindow").invoke(peer) ?: return null
            val contentView = platformWindow.javaClass.getMethod("getContentView").invoke(platformWindow) ?: return null
            val getAwtView = contentView.javaClass.getMethod("getAWTView")
            getAwtView.isAccessible = true
            (getAwtView.invoke(contentView) as? Number)?.toLong()?.takeIf { address -> address > 0L }
        } catch (_: InaccessibleObjectException) {
            null
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun isMacOs(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .contains("mac", ignoreCase = true)
}

internal fun calculateMacAppKitViewFrame(
    contentX: Int,
    contentY: Int,
    contentHeight: Int,
    width: Int,
    height: Int,
): NSRect.ByValue {
    val safeX = contentX.coerceAtLeast(0)
    val safeWidth = width.coerceAtLeast(0)
    val safeHeight = height.coerceAtLeast(0)
    val appKitY = (contentHeight.coerceAtLeast(0) - contentY.coerceAtLeast(0) - safeHeight).coerceAtLeast(0)
    return NSRect.ByValue(
        origin = NSPoint.ByValue(safeX.toDouble(), appKitY.toDouble()),
        size = NSSize.ByValue(safeWidth.toDouble(), safeHeight.toDouble()),
    )
}

internal object MacLibVlcAppKit {
    private val dispatch by lazy { Native.load("System", DispatchLib::class.java) }
    private val mainQueue by lazy {
        NativeLibrary
            .getInstance("System")
            .getGlobalVariableAddress("_dispatch_main_q")
    }

    fun createView(snapshot: MacAppKitViewSnapshot): Long {
        var result = 0L
        runOnAppKit {
            val viewClass =
                MacAppKitInteropViewClass.resolve(
                    className = LIBVLC_VIDEO_VIEW_CLASS,
                    superclassName = "NSView",
                ) ?: return@runOnAppKit
            val allocated =
                MacObjectiveCRuntime.sendPointerReturnNoArgs(
                    viewClass,
                    MacObjectiveCRuntime.selector("alloc"),
                )
                    ?: return@runOnAppKit
            val wrapper =
                MacObjectiveCRuntime.sendPointerReturnRect(
                    allocated,
                    MacObjectiveCRuntime.selector("initWithFrame:"),
                    snapshot.frame,
                )
            if (wrapper != null) {
                MacAppKitInteropViewClass.applyBlackBackground(wrapper)
                MacAppKitInteropViewClass.applyBelowComposeOrdering(wrapper)
                MacObjectiveCRuntime.sendVoidReturnDouble(
                    wrapper,
                    MacObjectiveCRuntime.selector("setAlphaValue:"),
                    HIDDEN_VIDEO_ALPHA,
                )
                val rootView = Pointer(snapshot.rootViewAddress)
                MacObjectiveCRuntime.sendVoidReturnPointerSignedNativeLongAndNullablePointer(
                    rootView,
                    MacObjectiveCRuntime.selector("addSubview:positioned:relativeTo:"),
                    wrapper,
                    NativeLong(NS_VIEW_BELOW),
                    null,
                )
                MacAppKitInteropViewClass.logViewHierarchy(rootView, "libvlc-attach")
                result = Pointer.nativeValue(wrapper)
            }
        }
        return result
    }

    fun resizeView(
        viewAddress: Long,
        frame: NSRect.ByValue,
    ) {
        runOnAppKit {
            MacObjectiveCRuntime.sendVoidReturnRect(
                Pointer(viewAddress),
                MacObjectiveCRuntime.selector("setFrame:"),
                frame,
            )
        }
    }

    fun setViewVisible(
        viewAddress: Long,
        visible: Boolean,
    ) {
        runOnAppKit {
            MacObjectiveCRuntime.sendVoidReturnDouble(
                Pointer(viewAddress),
                MacObjectiveCRuntime.selector("setAlphaValue:"),
                if (visible) VISIBLE_VIDEO_ALPHA else HIDDEN_VIDEO_ALPHA,
            )
        }
        logMacLibVlcSurfaceProbe(if (visible) "visible" else "hidden")
    }

    fun removeView(viewAddress: Long) {
        runOnAppKit {
            val view = Pointer(viewAddress)
            MacObjectiveCRuntime.sendVoidReturnNoArgs(view, MacObjectiveCRuntime.selector("removeFromSuperview"))
            MacObjectiveCRuntime.sendVoidReturnNoArgs(view, MacObjectiveCRuntime.selector("release"))
        }
    }

    private fun runOnAppKit(action: () -> Unit) {
        val work = DispatchLib.DispatchWork { _ -> action() }
        dispatch.dispatch_sync_f(mainQueue, null, work)
    }
}

private fun nextMacLibVlcSurfaceGeneration(): Long {
    while (true) {
        val current = macLibVlcSurfaceGeneration.get()
        val next = nextPositiveGeneration(current)
        if (macLibVlcSurfaceGeneration.compareAndSet(current, next)) return next
    }
}

internal object MacAppKitSurfaceWorker {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "jellyscope-mac-libvlc-surface").apply { isDaemon = true }
        }

    fun execute(action: () -> Unit) {
        executor.execute(action)
    }
}

@Suppress("FunctionName")
private interface DispatchLib : Library {
    fun dispatch_sync_f(
        queue: Pointer,
        context: Pointer?,
        work: DispatchWork,
    )

    fun interface DispatchWork : Callback {
        fun invoke(context: Pointer?)
    }
}

@Structure.FieldOrder("x", "y")
internal open class NSPoint(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
) : Structure() {
    class ByValue(
        x: Double,
        y: Double,
    ) : NSPoint(x, y),
        Structure.ByValue
}

@Structure.FieldOrder("width", "height")
internal open class NSSize(
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0,
) : Structure() {
    class ByValue(
        width: Double,
        height: Double,
    ) : NSSize(width, height),
        Structure.ByValue
}

@Structure.FieldOrder("origin", "size")
internal open class NSRect(
    @JvmField var origin: NSPoint = NSPoint(),
    @JvmField var size: NSSize = NSSize(),
) : Structure() {
    class ByValue(
        origin: NSPoint.ByValue,
        size: NSSize.ByValue,
    ) : NSRect(origin, size),
        Structure.ByValue
}

private val macLibVlcSurfaceGeneration = AtomicLong(0L)
private const val LIBVLC_VIDEO_VIEW_CLASS = "JellyScopeLibVlcVideoView"
private const val NS_VIEW_BELOW = -1L
private const val HIDDEN_VIDEO_ALPHA = 0.0
private const val VISIBLE_VIDEO_ALPHA = 1.0

private fun logMacLibVlcSurfaceProbe(event: String) {
    if (!DesktopPlaybackProbe.isEnabled) return
    val action =
        if (event == "visible") DesktopSurfaceAction.VISIBLE else DesktopSurfaceAction.HIDDEN
    DesktopPlaybackProbe.emit(
        DesktopSurfaceLifecycleProbeRecord(
            surface = DesktopSurfaceKind.LIBVLC,
            action = action,
            visible = action == DesktopSurfaceAction.VISIBLE,
        ),
    )
}
