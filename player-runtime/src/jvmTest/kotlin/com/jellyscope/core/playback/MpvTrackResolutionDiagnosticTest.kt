// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvTrackResolutionDiagnosticTest {
    @Test
    fun identicalMappingAcrossTwoPreparesIsLoggedOnlyOnce() {
        val mappingLogs = CopyOnWriteArrayList<String>()
        val writer =
            object : LogWriter() {
                override fun log(
                    severity: Severity,
                    message: String,
                    tag: String,
                    throwable: Throwable?,
                ) {
                    if (tag == "MpvPlayerController" && message.startsWith("stage=mapping event=resolved")) {
                        mappingLogs += message
                    }
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MpvPlayerController(diagnosticSession, scope, DiagnosticLibMpv())

        try {
            Logger.setLogWriters(listOf(writer))

            // This fixture deliberately reuses one target across both prepares, although
            // production never does: PlayerViewModel issues a fresh requestId for every
            // activation. Reuse is the only way an identical key can recur across prepare(),
            // which makes this probe detect an accidental mpv gate reset.
            controller.prepare(diagnosticPlan)
            controller.selectEmbeddedAudio(diagnosticSelection)
            waitUntil { mappingLogs.size == 1 }

            controller.prepare(diagnosticPlan)
            controller.selectEmbeddedAudio(diagnosticSelection)
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }
            Thread.sleep(300L)

            assertEquals(1, mappingLogs.size)
        } finally {
            controller.release()
            scope.cancel()
            Logger.setLogWriters(emptyList())
        }
    }
}

private class DiagnosticLibMpv : LibMpv {
    private val context = Pointer(1L)
    private val renderContext = Pointer(2L)
    private val paused = AtomicBoolean(true)
    private val selectedAudioId = AtomicLong(0L)
    private val returnedStrings = mutableListOf<Memory>()

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
        when (name) {
            "pause" -> paused.set(data == "yes")
            "aid" -> selectedAudioId.set(data.toLong())
        }
        return 0
    }

    override fun mpv_get_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int {
        when (name) {
            "time-pos" -> data.setDouble(0L, 0.0)
            "duration" -> data.setDouble(0L, 60.0)
            "demuxer-cache-time" -> data.setDouble(0L, 5.0)
            "track-list/count" -> data.setLong(0L, 1L)
            "track-list/0/id" -> data.setLong(0L, AUDIO_SELECTOR_ID)
            "track-list/0/ff-index" -> data.setLong(0L, diagnosticTarget.streamIndex.toLong())
            "aid" -> data.setLong(0L, selectedAudioId.get())
            "pause" -> data.setInt(0L, if (paused.get()) 1 else 0)
            "track-list/0/external",
            "paused-for-cache",
            "seeking",
            "eof-reached",
            "idle-active",
            -> data.setInt(0L, 0)
            else -> return -1
        }
        return 0
    }

    override fun mpv_get_property_string(
        ctx: Pointer,
        name: String,
    ): Pointer? {
        val value =
            when (name) {
                "track-list/0/type" -> MPV_TRACK_TYPE_AUDIO
                "track-list/0/codec" -> "aac"
                "track-list/0/lang" -> "eng"
                "track-list/0/title" -> "English"
                else -> return null
            }
        return Memory((value.encodeToByteArray().size + 1).toLong()).also { memory ->
            memory.setString(0L, value)
            synchronized(returnedStrings) { returnedStrings += memory }
        }
    }

    override fun mpv_free(data: Pointer) = Unit

    override fun mpv_command(
        ctx: Pointer,
        args: Array<String?>,
    ): Int = 0

    override fun mpv_wait_event(
        ctx: Pointer,
        timeout: Double,
    ): Pointer? = null

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
        const val AUDIO_SELECTOR_ID = 7L
    }
}

private val diagnosticTarget = AudioActivationTarget(requestId = 1L, itemId = "item-1", streamIndex = 4)

private val diagnosticDescriptor =
    PlannedEmbeddedTrack(
        jellyfinStreamIndex = diagnosticTarget.streamIndex,
        filteredContainerOrdinal = 0,
        codec = "aac",
        normalizedLanguage = "eng",
        label = "English",
    )

private val diagnosticSelection = EmbeddedAudioSelection(diagnosticTarget, diagnosticDescriptor)

private val diagnosticPlan =
    PlaybackPlan(
        itemId = diagnosticTarget.itemId,
        mediaSourceId = "source-1",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/item-1/stream",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
        selectedAudioStreamIndex = diagnosticTarget.streamIndex,
        embeddedAudioTracks = listOf(diagnosticDescriptor),
        audioActivationTarget = diagnosticTarget,
    )

private val diagnosticSession =
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
        check(System.nanoTime() < deadline) { "Timed out waiting for mpv diagnostic state" }
        Thread.sleep(20L)
    }
}
