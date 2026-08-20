// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.util.LogScrubber
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MacosNowPlayingNativePublicationTest {
    @Test
    fun frameworkOwnedKeysPopulateTheActualNowPlayingCenterDictionary() {
        if (!Platform.isMac()) return

        val native = MacosNowPlayingNative.load()
        val dictionary =
            assertNotNull(
                native.buildNowPlayingInfo(
                    title = "Synthetic title",
                    artist = "Synthetic series",
                    durationMs = 120_000,
                    positionMs = 45_000,
                    playbackRate = 1.25,
                ),
            )
        val center =
            assertNotNull(
                MacObjectiveCRuntime.sendPointerReturnNoArgs(
                    assertNotNull(MacObjectiveCRuntime.objcGetClass("MPNowPlayingInfoCenter")),
                    MacObjectiveCRuntime.selector("defaultCenter"),
                ),
            )

        try {
            MacObjectiveCRuntime.sendVoidReturnNullablePointer(
                center,
                MacObjectiveCRuntime.selector("setNowPlayingInfo:"),
                dictionary,
            )
            val published =
                assertNotNull(
                    MacObjectiveCRuntime.sendPointerReturnNoArgs(
                        center,
                        MacObjectiveCRuntime.selector("nowPlayingInfo"),
                    ),
                )
            val framework = NativeLibrary.getInstance(MEDIA_PLAYER_FRAMEWORK_PATH)

            assertEquals(
                "Synthetic title",
                stringValue(dictionaryValue(published, frameworkKey(framework, "MPMediaItemPropertyTitle"))),
            )
            assertEquals(
                "Synthetic series",
                stringValue(dictionaryValue(published, frameworkKey(framework, "MPMediaItemPropertyArtist"))),
            )
            assertEquals(
                120.0,
                numberValue(dictionaryValue(published, frameworkKey(framework, "MPMediaItemPropertyPlaybackDuration"))),
            )
            assertEquals(
                45.0,
                numberValue(
                    dictionaryValue(
                        published,
                        frameworkKey(framework, "MPNowPlayingInfoPropertyElapsedPlaybackTime"),
                    ),
                ),
            )
            assertEquals(
                1.25,
                numberValue(dictionaryValue(published, frameworkKey(framework, "MPNowPlayingInfoPropertyPlaybackRate"))),
            )
        } finally {
            MacObjectiveCRuntime.sendVoidReturnNullablePointer(
                center,
                MacObjectiveCRuntime.selector("setNowPlayingInfo:"),
                null,
            )
            assertNull(
                MacObjectiveCRuntime.sendPointerReturnNoArgs(
                    center,
                    MacObjectiveCRuntime.selector("nowPlayingInfo"),
                ),
            )
        }
    }

    @Test
    fun everyFailureBoundaryProducesAnAllowlistedClassOnlyDiagnostic() {
        MacosNowPlayingFailureEvent.entries.forEach { event ->
            val diagnostic = macosNowPlayingFailureDiagnostic(event, IllegalStateException("secret message /Users/test"))

            assertEquals(diagnostic, LogScrubber.capture("MacosNowPlaying", diagnostic))
            assertFalse(diagnostic.contains("secret"))
            assertFalse(diagnostic.contains("/Users"))
            assertFalse(diagnostic.contains("message"))
        }
    }

    @Test
    fun loadBoundaryReportsAndDegradesToNoBridge() {
        val failures = mutableListOf<Pair<MacosNowPlayingFailureEvent, Throwable>>()
        val reporter = MacosNowPlayingFailureReporter { event, throwable -> failures += event to throwable }

        val bridge =
            loadMacosNowPlayingBridge(failureReporter = reporter) {
                throw IllegalStateException("native load failed")
            }

        assertNull(bridge)
        assertEquals(MacosNowPlayingFailureEvent.Load, failures.single().first)
        assertEquals(IllegalStateException::class, failures.single().second::class)
    }

    private fun frameworkKey(
        framework: NativeLibrary,
        symbol: String,
    ): Pointer = assertNotNull(framework.getGlobalVariableAddress(symbol).getPointer(0L))

    private fun dictionaryValue(
        dictionary: Pointer,
        key: Pointer,
    ): Pointer =
        assertNotNull(
            MacObjectiveCRuntime.sendPointerReturnOnePointer(
                dictionary,
                MacObjectiveCRuntime.selector("objectForKey:"),
                key,
            ),
        )

    private fun stringValue(value: Pointer): String =
        assertNotNull(
            MacObjectiveCRuntime.sendPointerReturnNoArgs(
                value,
                MacObjectiveCRuntime.selector("UTF8String"),
            ),
        ).getString(0L)

    private fun numberValue(value: Pointer): Double =
        MacObjectiveCRuntime.sendDoubleReturnNoArgs(value, MacObjectiveCRuntime.selector("doubleValue"))

    private companion object {
        const val MEDIA_PLAYER_FRAMEWORK_PATH = "/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer"
    }
}
