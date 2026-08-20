// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine-use lease: `lifecycleLock` never protected the native context from
 * teardown, so `sub-add` — which libmpv resolves synchronously over the network
 * — is issued outside that lock under a lease that teardown must await.
 */
class MpvPlayerControllerEngineLeaseTest {
    @Test
    fun teardownWaitsForAnInFlightSubtitleCommandInsteadOfFreeingUnderIt() {
        val lib = LeaseLibMpv()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MpvPlayerController(leaseSession, scope, lib)
        try {
            controller.prepare(externalSubtitlePlan(leaseSubtitleTarget), remoteSubtitleAsset)
            lib.enqueueStart(lib.currentPlaylistEntryId)
            lib.enqueueFileLoaded()
            assertTrue(lib.subAddEntered.await(3L, TimeUnit.SECONDS))

            controller.release()
            Thread.sleep(300L)

            assertEquals(0, lib.terminateCount.get())
            assertEquals(0, lib.renderContextFreeCount.get())

            lib.releaseSubAdd.countDown()
            waitUntil { lib.terminateCount.get() == 1 }

            assertEquals(1, lib.renderContextFreeCount.get())
            assertNull(quarantinedEngineOf(controller))
        } finally {
            lib.releaseSubAdd.countDown()
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun commandsIssuedAfterTeardownBeganNeverReachTheContext() {
        val lib = LeaseLibMpv()
        lib.blockTerminate.set(true)
        val surface = RecordingOpenGlSurface(generation = 1L, lifecycle = lib.lifecycle)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller =
            MpvPlayerController(
                session = leaseSession,
                stateScope = scope,
                mpv = lib,
                presentationPreference = MpvPresentationPreference.MacOsOpenGl,
            )
        try {
            controller.attachOpenGlSurface(surface)
            waitUntil { controller.presentationState.value is MpvPresentationState.OpenGlActive }
            controller.prepare(playbackPlan())
            waitUntil { lib.commands.any { command -> command.firstOrNull() == "loadfile" } }

            val detached = CountDownLatch(1)
            controller.detachOpenGlSurface(1L, MpvOpenGlDetachCallback { detached.countDown() })
            assertTrue(lib.terminateEntered.await(3L, TimeUnit.SECONDS))

            val commandsAtTeardown = lib.commands.size
            val propertyWritesAtTeardown = lib.propertyWrites.size
            controller.play()
            controller.pause()
            controller.seekTo(4_000L)
            controller.setPlaybackSpeed(1.5f)
            controller.setVolume(30)
            controller.selectEmbeddedSubtitle(null)
            controller.stop()

            assertEquals(commandsAtTeardown, lib.commands.size)
            assertEquals(propertyWritesAtTeardown, lib.propertyWrites.size)

            lib.releaseTerminate.countDown()
            assertTrue(detached.await(3L, TimeUnit.SECONDS))
            assertEquals(1, lib.terminateCount.get())
        } finally {
            lib.releaseTerminate.countDown()
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun deferredDestroyHoldsTheSurfaceOpenAndReportsTerminatedOnlyAfterTheRealDestroy() {
        val lib = LeaseLibMpv()
        val surface = RecordingOpenGlSurface(generation = 1L, lifecycle = lib.lifecycle)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var fakeClockNanos = 0L
        val controller =
            MpvPlayerController(
                session = leaseSession,
                stateScope = scope,
                mpv = lib,
                presentationPreference = MpvPresentationPreference.MacOsOpenGl,
                engineLeaseClockNanos = {
                    fakeClockNanos += TimeUnit.MILLISECONDS.toNanos(100L)
                    fakeClockNanos
                },
                engineLeaseWait = { lib.leaseDrainPolls.incrementAndGet() },
            )
        try {
            controller.attachOpenGlSurface(surface)
            waitUntil { controller.presentationState.value is MpvPresentationState.OpenGlActive }
            controller.prepare(externalSubtitlePlan(leaseSubtitleTarget), remoteSubtitleAsset)
            lib.enqueueStart(lib.currentPlaylistEntryId)
            lib.enqueueFileLoaded()
            assertTrue(lib.subAddEntered.await(3L, TimeUnit.SECONDS))

            val detached = CountDownLatch(1)
            val disposition = AtomicReference<MpvOpenGlDetachDisposition?>(null)
            controller.detachOpenGlSurface(
                1L,
                MpvOpenGlDetachCallback { result ->
                    disposition.set(result)
                    lib.lifecycle += "detach"
                    detached.countDown()
                },
            )
            assertFalse(detached.await(100L, TimeUnit.MILLISECONDS))
            assertFalse(surface.closed.get())
            assertNull(disposition.get())
            assertTrue(lib.leaseDrainPolls.get() > 0)
            assertEquals(0, lib.terminateCount.get())
            assertEquals(0, lib.renderContextFreeCount.get())
            assertNull(quarantinedEngineOf(controller))

            lib.releaseSubAdd.countDown()
            assertTrue(detached.await(3L, TimeUnit.SECONDS))

            assertEquals(MpvOpenGlDetachDisposition.Terminated, disposition.get())
            assertTrue(surface.closed.get())
            assertEquals(1, lib.terminateCount.get())
            assertEquals(1, lib.renderContextFreeCount.get())
            assertNull(quarantinedEngineOf(controller))
            assertTrue(lib.lifecycle.indexOf("render-free") < lib.lifecycle.indexOf("terminate"))
            assertTrue(lib.lifecycle.indexOf("terminate") < lib.lifecycle.indexOf("surface-close"))
            assertTrue(lib.lifecycle.indexOf("surface-close") < lib.lifecycle.indexOf("detach"))
        } finally {
            lib.releaseSubAdd.countDown()
            controller.release()
            scope.cancel()
        }
    }
}

private class LeaseLibMpv : LibMpv {
    val subAddEntered = CountDownLatch(1)
    val releaseSubAdd = CountDownLatch(1)
    val terminateEntered = CountDownLatch(1)
    val releaseTerminate = CountDownLatch(1)
    val blockTerminate = AtomicBoolean(false)
    val terminateCount = AtomicInteger(0)
    val renderContextFreeCount = AtomicInteger(0)
    val leaseDrainPolls = AtomicInteger(0)
    val commands = CopyOnWriteArrayList<List<String>>()
    val propertyWrites = CopyOnWriteArrayList<String>()
    val lifecycle = CopyOnWriteArrayList<String>()

    @Volatile
    var currentPlaylistEntryId = 0L
        private set

    private val context = Pointer(1L)
    private val renderContext = Pointer(2L)
    private val nativeEvents = ArrayDeque<Memory>()

    override fun mpv_create(): Pointer = context

    override fun mpv_initialize(ctx: Pointer): Int = 0

    override fun mpv_terminate_destroy(ctx: Pointer) {
        terminateEntered.countDown()
        if (blockTerminate.get()) releaseTerminate.await(10L, TimeUnit.SECONDS)
        terminateCount.incrementAndGet()
        lifecycle += "terminate"
    }

    override fun mpv_error_string(error: Int): Pointer? = null

    override fun mpv_set_option_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int = 0

    override fun mpv_set_property_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int {
        propertyWrites += "$name=$data"
        return 0
    }

    override fun mpv_get_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int {
        val value =
            when (name) {
                "playlist-pos" -> 0L
                "playlist/0/id" -> currentPlaylistEntryId
                else -> return -1
            }
        data.setLong(0L, value)
        return 0
    }

    override fun mpv_get_property_string(
        ctx: Pointer,
        name: String,
    ): Pointer? = null

    override fun mpv_free(data: Pointer) = Unit

    override fun mpv_command(
        ctx: Pointer,
        args: Array<String?>,
    ): Int {
        val command = args.filterNotNull()
        commands += command
        return when (command.firstOrNull()) {
            "loadfile" -> {
                currentPlaylistEntryId += 1L
                0
            }
            "sub-add" -> {
                subAddEntered.countDown()
                releaseSubAdd.await(10L, TimeUnit.SECONDS)
                0
            }
            else -> 0
        }
    }

    override fun mpv_wait_event(
        ctx: Pointer,
        timeout: Double,
    ): Pointer? = synchronized(nativeEvents) { nativeEvents.pollFirst() }

    override fun mpv_render_context_create(
        res: PointerByReference,
        mpv: Pointer,
        params: Array<MpvRenderParam>,
    ): Int {
        res.value = renderContext
        return 0
    }

    override fun mpv_render_context_render(
        ctx: Pointer,
        params: Array<MpvRenderParam>,
    ): Int = 0

    override fun mpv_render_context_set_update_callback(
        ctx: Pointer,
        callback: LibMpv.MpvRenderUpdateFn?,
        callbackCtx: Pointer?,
    ) = Unit

    override fun mpv_render_context_update(ctx: Pointer): Long = 0L

    override fun mpv_render_context_free(ctx: Pointer) {
        renderContextFreeCount.incrementAndGet()
        lifecycle += "render-free"
    }

    fun enqueueStart(playlistEntryId: Long) {
        val payloadMemory = Memory(64L)
        MpvEventStartFile(payloadMemory).apply {
            this.playlistEntryId = playlistEntryId
            write()
        }
        enqueueEvent(LibMpv.EVENT_START_FILE, payloadMemory)
    }

    fun enqueueFileLoaded() = enqueueEvent(LibMpv.EVENT_FILE_LOADED, null)

    private fun enqueueEvent(
        eventId: Int,
        payload: Memory?,
    ) {
        val eventMemory = Memory(64L)
        MpvEvent(eventMemory).apply {
            this.eventId = eventId
            data = payload
            write()
        }
        synchronized(nativeEvents) {
            if (payload != null) retainedPayloads += payload
            nativeEvents += eventMemory
        }
    }

    private companion object {
        val retainedPayloads = CopyOnWriteArrayList<Memory>()
    }
}

private class RecordingOpenGlSurface(
    override val generation: Long,
    private val lifecycle: MutableList<String>,
) : MpvOpenGlRenderSurface {
    val closed = AtomicBoolean(false)

    override fun resolveGlSymbol(name: String): Long = 1L

    override fun withCurrentContext(render: (MpvOpenGlFramebuffer) -> Boolean): Boolean {
        if (closed.get()) return false
        render(MpvOpenGlFramebuffer(widthPx = 1_280, heightPx = 720))
        return true
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            lifecycle += "surface-close"
        }
    }
}

private fun quarantinedEngineOf(controller: MpvPlayerController): Any? =
    controller.javaClass
        .getDeclaredField("quarantinedEngine")
        .apply { isAccessible = true }
        .get(controller)

private fun playbackPlan(): PlaybackPlan =
    PlaybackPlan(
        itemId = "item-1",
        mediaSourceId = "source-1",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/item-1/stream",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
    )

private fun externalSubtitlePlan(target: SubtitleActivationTarget): PlaybackPlan =
    playbackPlan().copy(
        selectedSubtitleStreamIndex = target.streamIndex,
        subtitleActivationTarget = target,
    )

private val leaseSubtitleTarget =
    SubtitleActivationTarget(
        requestId = 1L,
        itemId = "item-1",
        identity = SubtitleActivationIdentity.JellyfinTrack(4),
        kind = LocalSubtitleKind.ExternalText,
    )

private val remoteSubtitleAsset =
    SubtitleAsset.JellyfinRemote(
        url = "https://jellyfin.example/Videos/item-1/subtitles/4.srt",
        mimeType = "application/x-subrip",
        label = "English",
        language = "eng",
    )

private val leaseSession =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home",
        userId = "user-1",
        userName = "User",
        accessToken = "token",
        deviceId = "device-1",
    )

private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for mpv engine lease state" }
        Thread.sleep(20L)
    }
}
