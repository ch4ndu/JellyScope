// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.playback.DesktopHierarchyProbeRecord
import com.jellyscope.core.playback.DesktopPlaybackProbe
import com.jellyscope.core.playback.DesktopProbeToken
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.NativeLong
import com.sun.jna.Pointer

/**
 * Creates hit-test-transparent AppKit view subclasses for native video.
 *
 * The native video views render below Compose's transparent Metal layer and
 * return nil from hitTest:, so pointer input follows the normal AWT-to-Compose
 * path rather than targeting the native child.
 */
internal object MacAppKitInteropViewClass {
    private val hitTestSuppressionCallback = NativeHitTestCallback { _, _, _, _ -> null }

    @Synchronized
    fun resolve(
        className: String,
        superclassName: String,
    ): Pointer? {
        MacObjectiveCRuntime.objcGetClass(className)?.validPointer()?.let { return it }
        val superclass = MacObjectiveCRuntime.objcGetClass(superclassName)?.validPointer() ?: return null
        val subclass = MacObjectiveCRuntime.allocateClassPair(superclass, className)?.validPointer() ?: return null
        // Layer zPosition fixes rendering order but NOT hit-testing: AppKit
        // otherwise targets this subview for every mouse event. Returning nil
        // from hitTest: makes the video subtree input-transparent so events
        // reach the AWT content view and follow the real Compose input path.
        val hitTestAdded =
            MacObjectiveCRuntime.classAddMethod(
                subclass,
                MacObjectiveCRuntime.selector("hitTest:"),
                CallbackReference.getFunctionPointer(hitTestSuppressionCallback),
                NATIVE_HIT_TEST_TYPE_ENCODING,
            )
        if (!hitTestAdded) {
            MacObjectiveCRuntime.disposeClassPair(subclass)
            return MacObjectiveCRuntime.objcGetClass(className)?.validPointer()
        }
        MacObjectiveCRuntime.registerClassPair(subclass)
        return subclass
    }

    /**
     * Keeps the native player cutout black until the backend presents its
     * first frame. NSView otherwise exposes AppKit's default window backing
     * color during the short drawable/decoder startup interval.
     */
    fun applyBlackBackground(view: Pointer) {
        val colorClass = MacObjectiveCRuntime.objcGetClass("NSColor")?.validPointer() ?: return
        val blackColor =
            MacObjectiveCRuntime
                .sendPointerReturnNoArgs(colorClass, MacObjectiveCRuntime.selector("blackColor"))
                ?.validPointer()
                ?: return
        val cgColor =
            MacObjectiveCRuntime
                .sendPointerReturnNoArgs(blackColor, MacObjectiveCRuntime.selector("CGColor"))
                ?.validPointer()
                ?: return
        MacObjectiveCRuntime.sendVoidReturnByte(
            view,
            MacObjectiveCRuntime.selector("setWantsLayer:"),
            OBJC_YES,
        )
        val layer =
            MacObjectiveCRuntime
                .sendPointerReturnNoArgs(view, MacObjectiveCRuntime.selector("layer"))
                ?.validPointer()
                ?: return
        MacObjectiveCRuntime.sendVoidReturnNullablePointer(
            layer,
            MacObjectiveCRuntime.selector("setBackgroundColor:"),
            cgColor,
        )
    }

    /**
     * Compose renders through a CAMetalLayer attached to the AWT content
     * view's own layer. Subview backing layers can composite above that
     * sublayer regardless of `addSubview(positioned: below)`, which only
     * orders sibling views. A negative zPosition forces the video view's
     * layer subtree below Compose so in-scene controls stay visible.
     */
    fun applyBelowComposeOrdering(view: Pointer) {
        MacObjectiveCRuntime.sendVoidReturnByte(view, MacObjectiveCRuntime.selector("setWantsLayer:"), OBJC_YES)
        val layer =
            MacObjectiveCRuntime
                .sendPointerReturnNoArgs(view, MacObjectiveCRuntime.selector("layer"))
                ?.validPointer()
                ?: return
        MacObjectiveCRuntime.sendVoidReturnDouble(
            layer,
            MacObjectiveCRuntime.selector("setZPosition:"),
            VIDEO_LAYER_Z_POSITION,
        )
    }

    /** Probe-gated dump of the content view's subview stack for z-order triage. */
    fun logViewHierarchy(
        rootView: Pointer,
        tag: String,
    ) {
        if (!DesktopPlaybackProbe.isEnabled) return
        val subviews =
            MacObjectiveCRuntime
                .sendPointerReturnNoArgs(rootView, MacObjectiveCRuntime.selector("subviews"))
                ?.validPointer()
                ?: return
        val nativeCount =
            MacObjectiveCRuntime
                .sendUnsignedNativeLongReturnNoArgs(subviews, MacObjectiveCRuntime.selector("count"))
                .toLong()
        val count = nativeCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        var layeredCount = 0
        val classes =
            (0 until minOf(count, MAX_HIERARCHY_CLASSES)).map { index ->
                val subview =
                    MacObjectiveCRuntime.sendPointerReturnUnsignedNativeLong(
                        subviews,
                        MacObjectiveCRuntime.selector("objectAtIndex:"),
                        NativeLong(index.toLong()),
                    )
                        ?: return@map DesktopProbeToken.from(null)
                val className = MacObjectiveCRuntime.objectGetClassName(subview) ?: "?"
                val layered =
                    MacObjectiveCRuntime
                        .sendPointerReturnNoArgs(subview, MacObjectiveCRuntime.selector("layer"))
                        ?.validPointer() != null
                if (layered) layeredCount += 1
                DesktopProbeToken.from(className)
            }
        DesktopPlaybackProbe.emit(
            DesktopHierarchyProbeRecord(
                owner = DesktopProbeToken.from(tag),
                count = count,
                layeredCount = layeredCount,
                classes = classes,
            ),
        )
    }

    private fun Pointer.validPointer(): Pointer? = takeIf { value -> Pointer.nativeValue(value) != 0L }
}

// AppKit calls hitTest: at very high frequency. Declaring the CGPoint
// argument as two plain doubles is ABI-identical to the by-value struct on
// both arm64 (HFA in d0/d1) and x86-64 (xmm0/xmm1) and avoids JNA allocating
// a reflective Structure per call, which OOM-killed a playback session.
private fun interface NativeHitTestCallback : Callback {
    fun invoke(
        receiver: Pointer?,
        selector: Pointer?,
        pointX: Double,
        pointY: Double,
    ): Pointer?
}

@Suppress("FunctionName")
private const val NATIVE_HIT_TEST_TYPE_ENCODING = "@@:{CGPoint=dd}"
private val OBJC_YES: Byte = 1
private const val VIDEO_LAYER_Z_POSITION = -1.0
private const val MAX_HIERARCHY_CLASSES = 16
