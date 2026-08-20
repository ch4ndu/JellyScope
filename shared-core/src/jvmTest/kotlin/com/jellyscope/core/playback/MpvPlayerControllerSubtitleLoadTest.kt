// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpvPlayerControllerSubtitleLoadTest {
    @Test
    fun externalSubtitleAttachesOnceOnlyAfterMatchingFileLoadedAndExactTitleConfirmsActive() {
        val lib = LifecycleLibMpv()
        withController(lib) { controller ->
            val target = subtitleTarget(1L)
            controller.prepare(externalPlan(target), remoteSubtitle)

            assertEquals(0, lib.subAddCommands().size)
            lib.enqueueStart(lib.currentPlaylistEntryId + 100L)
            lib.enqueueFileLoaded()
            Thread.sleep(150L)
            assertEquals(0, lib.subAddCommands().size)

            lib.enqueueStart(lib.currentPlaylistEntryId)
            lib.enqueueFileLoaded()
            waitUntil { lib.subAddCommands().size == 1 }
            waitUntil { controller.playbackState.value.subtitleActivation is SubtitleActivationState.Active }

            assertEquals(1, lib.subAddCommands().size)
            assertEquals(target, assertIs<SubtitleActivationState.Active>(controller.playbackState.value.subtitleActivation).target)
        }
    }

    @Test
    fun rapidSameUrlPrepareAllowsOnlyNewestPlaylistEntryToAttach() {
        val lib = LifecycleLibMpv()
        withController(lib) { controller ->
            controller.prepare(externalPlan(subtitleTarget(1L)), remoteSubtitle)
            val firstEntry = lib.currentPlaylistEntryId
            controller.prepare(externalPlan(subtitleTarget(2L)), remoteSubtitle)
            val secondEntry = lib.currentPlaylistEntryId

            lib.enqueueStart(firstEntry)
            lib.enqueueFileLoaded()
            lib.enqueueStart(secondEntry)
            lib.enqueueFileLoaded()
            waitUntil { lib.subAddCommands().size == 1 }

            assertTrue(firstEntry != secondEntry)
            assertTrue(
                lib
                    .subAddCommands()
                    .single()
                    .last()
                    .contains("2"),
            )
        }
    }

    @Test
    fun stopAndMatchingEndInvalidatePendingWhileRejectedSubAddIsNonfatalUnavailable() {
        val lib = LifecycleLibMpv()
        withController(lib) { controller ->
            val target = subtitleTarget(1L)
            controller.prepare(externalPlan(target), remoteSubtitle)
            val stoppedEntry = lib.currentPlaylistEntryId
            controller.stop()
            lib.enqueueStart(stoppedEntry)
            lib.enqueueFileLoaded()
            Thread.sleep(150L)
            assertTrue(lib.subAddCommands().isEmpty())

            controller.prepare(externalPlan(target.copy(requestId = 2L)), remoteSubtitle)
            val endedEntry = lib.currentPlaylistEntryId
            lib.enqueueEnd(endedEntry, LibMpv.END_FILE_REASON_ERROR, -12)
            lib.enqueueStart(endedEntry)
            lib.enqueueFileLoaded()
            waitUntil { controller.playbackState.value.subtitleActivation is SubtitleActivationState.Unavailable }
            assertTrue(lib.subAddCommands().isEmpty())

            lib.subAddResult = -5
            val rejectedTarget = target.copy(requestId = 3L)
            controller.prepare(externalPlan(rejectedTarget), remoteSubtitle)
            lib.enqueueStart(lib.currentPlaylistEntryId)
            lib.enqueueFileLoaded()
            waitUntil {
                (controller.playbackState.value.subtitleActivation as? SubtitleActivationState.Unavailable)?.target == rejectedTarget
            }
            assertFalse(controller.playbackState.value.status == com.jellyscope.core.domain.playback.PlaybackStatus.Failed)
        }
    }

    @Test
    fun localFileUsesResolvedPrivatePathWithoutServerOrCredentialMaterial() {
        val lib = LifecycleLibMpv()
        val localPath = "/app/private/subtitles/local-1.vtt"
        val store = FakeLocalSubtitleFileStore(localPath)
        withController(lib, store) { controller ->
            val target =
                SubtitleActivationTarget(
                    requestId = 4L,
                    itemId = "item-1",
                    identity = SubtitleActivationIdentity.LocalAsset("asset-1"),
                    kind = LocalSubtitleKind.ExternalText,
                )
            val asset = SubtitleAsset.LocalFile("asset-1", "local-1.vtt", "text/vtt", "English", "eng")
            controller.prepare(externalPlan(target).copy(subtitleAsset = asset), asset)
            lib.enqueueStart(lib.currentPlaylistEntryId)
            lib.enqueueFileLoaded()
            waitUntil { lib.subAddCommands().size == 1 }

            val command = lib.subAddCommands().single()
            assertEquals(localPath, command[1])
            assertFalse(command.joinToString(" ").contains("jellyfin.example"))
            assertFalse(command.joinToString(" ").contains("token"))
            assertFalse(command.joinToString(" ").contains("api_key"))
            assertFalse(command.joinToString(" ").contains("http-header"))
        }
    }

    @Test
    fun eventsQueuedInsideLoadCommandCannotBeatPendingPublication() {
        val releaseLoad = CountDownLatch(1)
        val lib = LifecycleLibMpv(releaseLoad = releaseLoad, enqueueLifecycleDuringLoad = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MpvPlayerController(session, scope, lib)
        try {
            val prepareJob = scope.launch { controller.prepare(externalPlan(subtitleTarget(9L)), remoteSubtitle) }
            assertTrue(lib.loadEntered.await(2, TimeUnit.SECONDS))
            Thread.sleep(150L)
            assertTrue(lib.subAddCommands().isEmpty())

            releaseLoad.countDown()
            runBlocking { prepareJob.join() }
            waitUntil { lib.subAddCommands().size == 1 }
        } finally {
            releaseLoad.countDown()
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun subtitleClearanceSetBeforePrepareIsRetainedByThePrepareStyle() {
        val lib = LifecycleLibMpv()
        withController(lib) { controller ->
            controller.setSubtitleClearanceActive(true)
            lib.clearPropertyWrites()

            controller.prepare(externalPlan(subtitleTarget(10L)), remoteSubtitle)

            assertEquals(listOf("sub-margin-y=180"), lib.propertyWritesFor("sub-margin-y"))
        }
    }

    @Test
    fun liveSubtitleClearanceTransitionsOnlyWriteTheMarginWithoutLoadingMedia() {
        val lib = LifecycleLibMpv()
        withController(lib) { controller ->
            controller.prepare(externalPlan(subtitleTarget(11L)), remoteSubtitle)
            val loadCount = lib.loadFileCommandCount()
            lib.clearPropertyWrites()

            controller.setSubtitleClearanceActive(true)
            controller.setSubtitleClearanceActive(false)

            assertEquals(
                listOf("sub-margin-y=180", "sub-margin-y=34"),
                lib.propertyWritesFor("sub-margin-y"),
            )
            assertEquals(loadCount, lib.loadFileCommandCount())
        }
    }

    @Test
    fun subtitleStyleUpdatePreservesTheCurrentlyResolvedClearance() {
        val lib = LifecycleLibMpv()
        withController(lib) { controller ->
            controller.prepare(externalPlan(subtitleTarget(12L)), remoteSubtitle)
            controller.setSubtitleClearanceActive(true)
            lib.clearPropertyWrites()

            controller.setSubtitleStyle(SubtitleStyle(fontScale = 1.25f))

            assertEquals(listOf("sub-margin-y=180"), lib.propertyWritesFor("sub-margin-y"))
        }
    }

    @Test
    fun laterPreparePreservesTheLatestRetainedClearanceState() {
        val lib = LifecycleLibMpv()
        withController(lib) { controller ->
            controller.prepare(externalPlan(subtitleTarget(13L)), remoteSubtitle)
            controller.setSubtitleClearanceActive(true)
            controller.setSubtitleClearanceActive(false)
            lib.clearPropertyWrites()

            controller.prepare(externalPlan(subtitleTarget(14L)), remoteSubtitle)

            assertEquals(listOf("sub-margin-y=34"), lib.propertyWritesFor("sub-margin-y"))
        }
    }
}

private class LifecycleLibMpv(
    private val releaseLoad: CountDownLatch? = null,
    private val enqueueLifecycleDuringLoad: Boolean = false,
) : LibMpv {
    val loadEntered = CountDownLatch(1)
    var currentPlaylistEntryId = 0L
        private set
    var subAddResult = 0
    private val context = Pointer(1L)
    private val renderContext = Pointer(2L)
    private val nativeEvents = ArrayDeque<Memory>()
    private val commands = mutableListOf<List<String>>()
    private val propertyWrites = mutableListOf<String>()
    private val returnedStrings = mutableListOf<Memory>()
    private var externalTrackTitle: String? = null

    override fun mpv_create(): Pointer = context

    override fun mpv_initialize(ctx: Pointer): Int = 0

    override fun mpv_terminate_destroy(ctx: Pointer) = Unit

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
        synchronized(propertyWrites) { propertyWrites += "$name=$data" }
        return 0
    }

    override fun mpv_get_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int {
        if (name == "track-list/0/external" || name == "track-list/0/selected") {
            data.setInt(0L, if (externalTrackTitle == null) 0 else 1)
            return 0
        }
        val value =
            when (name) {
                "playlist-pos" -> 0L
                "playlist/0/id" -> currentPlaylistEntryId
                "track-list/count" -> if (externalTrackTitle == null) 0L else 1L
                else -> return -1
            }
        data.setLong(0L, value)
        return 0
    }

    override fun mpv_get_property_string(
        ctx: Pointer,
        name: String,
    ): Pointer? {
        val value =
            when (name) {
                "track-list/0/type" -> MPV_TRACK_TYPE_SUBTITLE
                "track-list/0/title" -> externalTrackTitle
                else -> null
            } ?: return null
        return Memory((value.encodeToByteArray().size + 1).toLong()).also { memory ->
            memory.setString(0L, value)
            returnedStrings += memory
        }
    }

    override fun mpv_free(data: Pointer) = Unit

    override fun mpv_command(
        ctx: Pointer,
        args: Array<String?>,
    ): Int {
        val command = args.filterNotNull()
        synchronized(commands) { commands += command }
        return when (command.firstOrNull()) {
            "loadfile" -> {
                currentPlaylistEntryId += 1L
                if (enqueueLifecycleDuringLoad) {
                    enqueueStart(currentPlaylistEntryId)
                    enqueueFileLoaded()
                }
                loadEntered.countDown()
                releaseLoad?.await(2, TimeUnit.SECONDS)
                0
            }
            "sub-add" -> {
                if (subAddResult == 0) externalTrackTitle = command.getOrNull(3)
                subAddResult
            }
            else -> 0
        }
    }

    override fun mpv_wait_event(
        ctx: Pointer,
        timeout: Double,
    ): Pointer? = synchronized(nativeEvents) { nativeEvents.pollFirst() }

    fun enqueueStart(playlistEntryId: Long) {
        val payloadMemory = Memory(64L)
        MpvEventStartFile(payloadMemory).apply {
            this.playlistEntryId = playlistEntryId
            write()
        }
        enqueueEvent(LibMpv.EVENT_START_FILE, payloadMemory)
    }

    fun enqueueFileLoaded() = enqueueEvent(LibMpv.EVENT_FILE_LOADED, null)

    fun enqueueEnd(
        playlistEntryId: Long,
        reason: Int,
        error: Int,
    ) {
        val payloadMemory = Memory(64L)
        MpvEventEndFile(payloadMemory).apply {
            this.reason = reason
            this.error = error
            this.playlistEntryId = playlistEntryId
            write()
        }
        enqueueEvent(LibMpv.EVENT_END_FILE, payloadMemory)
    }

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

    fun subAddCommands(): List<List<String>> =
        synchronized(commands) {
            commands.filter { command -> command.firstOrNull() == "sub-add" }.map { command -> command.toList() }
        }

    fun propertyWritesFor(name: String): List<String> =
        synchronized(propertyWrites) {
            propertyWrites.filter { write -> write.startsWith("$name=") }.toList()
        }

    fun clearPropertyWrites() = synchronized(propertyWrites) { propertyWrites.clear() }

    fun loadFileCommandCount(): Int = synchronized(commands) { commands.count { command -> command.firstOrNull() == "loadfile" } }

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

    override fun mpv_render_context_free(ctx: Pointer) = Unit

    private companion object {
        val retainedPayloads = mutableListOf<Memory>()
    }
}

private class FakeLocalSubtitleFileStore(
    private val path: String,
) : LocalSubtitleFileStore {
    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun read(fileId: String): ByteArray? = null

    override suspend fun exists(fileId: String): Boolean = true

    override suspend fun delete(fileId: String) = Unit

    override suspend fun listFileIds(): Set<String> = emptySet()

    override fun resolvePath(fileId: String): String = path
}

private fun withController(
    lib: LifecycleLibMpv,
    store: LocalSubtitleFileStore? = null,
    block: (MpvPlayerController) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = MpvPlayerController(session, scope, lib, store)
    try {
        block(controller)
    } finally {
        controller.release()
        scope.cancel()
    }
}

private fun externalPlan(target: SubtitleActivationTarget): PlaybackPlan =
    PlaybackPlan(
        itemId = target.itemId,
        mediaSourceId = "source-1",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/item-1/stream?api_key=token",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
        selectedSubtitleStreamIndex = target.streamIndex,
        subtitleActivationTarget = target,
    )

private fun subtitleTarget(requestId: Long) =
    SubtitleActivationTarget(
        requestId = requestId,
        itemId = "item-1",
        identity = SubtitleActivationIdentity.JellyfinTrack(4),
        kind = LocalSubtitleKind.ExternalText,
    )

private val remoteSubtitle =
    SubtitleAsset.JellyfinRemote(
        url = "https://jellyfin.example/Videos/item-1/subtitles/4.srt?api_key=token",
        mimeType = "application/x-subrip",
        label = "English",
        language = "eng",
    )

private val session =
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
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for mpv controller state" }
        Thread.sleep(20L)
    }
}
