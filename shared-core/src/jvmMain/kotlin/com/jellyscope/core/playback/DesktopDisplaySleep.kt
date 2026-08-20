// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

object DesktopDisplaySleep {
    private const val ASSERTION_TYPE = "PreventUserIdleDisplaySleep"
    private const val ASSERTION_REASON = "JellyScope playback"
    private const val ASSERTION_LEVEL_ON = 255
    private const val CF_STRING_ENCODING_UTF8 = 0x08000100

    private val logger = Logger.withTag("DesktopDisplaySleep")
    private val lock = Any()
    private val nativeBridge: NativeBridge? by lazy {
        if (!Platform.isMac()) {
            null
        } else {
            runCatching {
                NativeBridge(
                    ioKit =
                        Native.load(
                            "/System/Library/Frameworks/IOKit.framework/IOKit",
                            IOKit::class.java,
                        ),
                    coreFoundation =
                        Native.load(
                            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
                            CoreFoundation::class.java,
                        ),
                )
            }.onFailure {
                logger.i { "macOS display-sleep bridge unavailable" }
            }.getOrNull()
        }
    }
    private var assertionId: Int? = null

    fun acquire() {
        if (!Platform.isMac()) return
        synchronized(lock) {
            if (assertionId != null) return
            val bridge = nativeBridge ?: return
            runCatching {
                val assertionType =
                    bridge.coreFoundation.CFStringCreateWithCString(
                        null,
                        ASSERTION_TYPE,
                        CF_STRING_ENCODING_UTF8,
                    )
                val assertionReason =
                    bridge.coreFoundation.CFStringCreateWithCString(
                        null,
                        ASSERTION_REASON,
                        CF_STRING_ENCODING_UTF8,
                    )
                try {
                    if (assertionType == null || assertionReason == null) {
                        logger.i { "macOS display-sleep assertion unavailable" }
                        return@runCatching
                    }
                    val createdId = IntByReference()
                    val result =
                        bridge.ioKit.IOPMAssertionCreateWithName(
                            assertionType,
                            ASSERTION_LEVEL_ON,
                            assertionReason,
                            createdId,
                        )
                    if (result == 0) {
                        assertionId = createdId.value
                    } else {
                        logger.i { "macOS display-sleep assertion rejected" }
                    }
                } finally {
                    assertionType?.let(bridge.coreFoundation::CFRelease)
                    assertionReason?.let(bridge.coreFoundation::CFRelease)
                }
            }.onFailure {
                logger.i { "macOS display-sleep assertion failed" }
            }
        }
    }

    fun release() {
        if (!Platform.isMac()) return
        synchronized(lock) {
            val activeAssertionId = assertionId ?: return
            assertionId = null
            val bridge = nativeBridge ?: return
            runCatching {
                if (bridge.ioKit.IOPMAssertionRelease(activeAssertionId) != 0) {
                    logger.i { "macOS display-sleep assertion release rejected" }
                }
            }.onFailure {
                logger.i { "macOS display-sleep assertion release failed" }
            }
        }
    }

    private data class NativeBridge(
        val ioKit: IOKit,
        val coreFoundation: CoreFoundation,
    )

    @Suppress("FunctionName")
    private interface IOKit : Library {
        fun IOPMAssertionCreateWithName(
            assertionType: Pointer,
            assertionLevel: Int,
            assertionName: Pointer,
            assertionId: IntByReference,
        ): Int

        fun IOPMAssertionRelease(assertionId: Int): Int
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFStringCreateWithCString(
            allocator: Pointer?,
            cString: String,
            encoding: Int,
        ): Pointer?

        fun CFRelease(value: Pointer)
    }
}
