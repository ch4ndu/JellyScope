// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopVlcRuntimeDiscoveryTest {
    @Test
    fun fileLayoutDeterminesAvailabilityWithoutCreatingAnEngine() {
        val root = Files.createTempDirectory("jellyscope-vlc-runtime")
        val lib = root.resolve("lib").createDirectories()
        val runtime = DesktopVlcRuntimeDiscovery.runtimeUnder(root.toFile())

        assertFalse(DesktopVlcRuntimeDiscovery.isUsableRuntime(runtime))

        lib.resolve("libvlc.dylib").createFile()
        assertFalse(DesktopVlcRuntimeDiscovery.isUsableRuntime(runtime))

        lib.resolve("libvlccore.dylib").createFile()
        assertFalse(DesktopVlcRuntimeDiscovery.isUsableRuntime(runtime))

        root.resolve("plugins").createDirectories()
        assertTrue(DesktopVlcRuntimeDiscovery.isUsableRuntime(runtime))

        Files.delete(runtime.libraryFile.toPath())
        assertFalse(DesktopVlcRuntimeDiscovery.isUsableRuntime(runtime))
    }

    @Test
    fun surfaceHandleRejectsInvalidNativeOwnership() {
        assertFalse(DesktopVlcSurfaceHandle.create(0L, 1L) != null)
        assertFalse(DesktopVlcSurfaceHandle.create(1L, 0L) != null)
        assertTrue(DesktopVlcSurfaceHandle.create(1L, 1L) != null)
    }

    @Test
    fun jnaStructuresExposeAReadableRuntimeLayout() {
        assertTrue(LibVlcTrackDescription(com.sun.jna.Pointer(1L)).size() > 0)
        assertTrue(LibVlcMediaStats().size() > 0)
    }

    @Test
    fun nativeLogModuleNamesCannotCarryUrlsOrCredentials() {
        assertTrue(isSafeVlcModuleName("dav1d"))
        assertTrue(isSafeVlcModuleName("macosx_window"))
        assertFalse(isSafeVlcModuleName("http://server/video?api_key=secret"))
        assertFalse(isSafeVlcModuleName("module with detail"))
    }

    @Test
    fun startTimeOptionUsesLocaleIndependentSecondsWithoutLosingMilliseconds() {
        assertEquals(null, desktopVlcStartTimeOption(-1L))
        assertEquals(null, desktopVlcStartTimeOption(0L))
        assertEquals(":start-time=12", desktopVlcStartTimeOption(12_000L))
        assertEquals(":start-time=12.345", desktopVlcStartTimeOption(12_345L))
        assertEquals(":start-time=12.05", desktopVlcStartTimeOption(12_050L))
    }
}
