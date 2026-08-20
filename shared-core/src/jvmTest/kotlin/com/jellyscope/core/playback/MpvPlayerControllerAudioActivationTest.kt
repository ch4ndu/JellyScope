// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.AudioActivationState
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvPlayerControllerAudioActivationTest {
    @Test
    fun activationReleasesPlayIntentOnceAndExplicitPlayResumesSeparately() {
        val lib = StatefulAudioLibMpv()
        withAudioController(lib) { controller ->
            controller.prepare(audioPlan)
            controller.selectEmbeddedAudio(audioSelection)
            controller.play()

            assertEquals(0, lib.unpauseCount.get())
            lib.audioTrackAvailable.set(true)
            waitUntil { controller.playbackState.value.audioActivation == AudioActivationState.Active(audioTarget) }
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Playing }
            assertEquals(1, lib.unpauseCount.get())

            val pauseReadsAfterActivation = lib.pauseReadCount.get()
            waitUntil { lib.pauseReadCount.get() >= pauseReadsAfterActivation + 2 }
            assertEquals(1, lib.unpauseCount.get())

            controller.pause()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }
            assertEquals(1, lib.unpauseCount.get())

            controller.play()
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Playing }
            assertEquals(2, lib.unpauseCount.get())
        }
    }

    @Test
    fun pauseBeforeDelayedAudioResolutionPreventsLateUnpause() {
        val lib = StatefulAudioLibMpv()
        withAudioController(lib) { controller ->
            controller.prepare(audioPlan)
            controller.selectEmbeddedAudio(audioSelection)
            controller.play()
            controller.pause()

            lib.audioTrackAvailable.set(true)
            waitUntil { controller.playbackState.value.audioActivation == AudioActivationState.Active(audioTarget) }
            waitUntil { controller.playbackState.value.status == PlaybackStatus.Paused }

            assertEquals(0, lib.unpauseCount.get())
            assertTrue(lib.paused.get())
        }
    }
}

private class StatefulAudioLibMpv : LibMpv {
    val audioTrackAvailable = AtomicBoolean(false)
    val paused = AtomicBoolean(true)
    val pauseReadCount = AtomicInteger(0)
    val unpauseCount = AtomicInteger(0)

    private val selectedAudioId = AtomicLong(0L)
    private val context = Pointer(1L)
    private val renderContext = Pointer(2L)
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
            "pause" -> {
                paused.set(data == "yes")
                if (data == "no") unpauseCount.incrementAndGet()
            }
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
            "track-list/count" -> data.setLong(0L, if (audioTrackAvailable.get()) 1L else 0L)
            "track-list/0/id" -> data.setLong(0L, AUDIO_SELECTOR_ID)
            "track-list/0/ff-index" -> data.setLong(0L, audioTarget.streamIndex.toLong())
            "aid" -> data.setLong(0L, selectedAudioId.get())
            "pause" -> {
                pauseReadCount.incrementAndGet()
                data.setInt(0L, if (paused.get()) 1 else 0)
            }
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

private fun withAudioController(
    lib: StatefulAudioLibMpv,
    block: (MpvPlayerController) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = MpvPlayerController(audioSession, scope, lib)
    try {
        block(controller)
    } finally {
        controller.release()
        scope.cancel()
    }
}

private val audioTarget = AudioActivationTarget(requestId = 1L, itemId = "item-1", streamIndex = 4)

private val audioDescriptor =
    PlannedEmbeddedTrack(
        jellyfinStreamIndex = audioTarget.streamIndex,
        filteredContainerOrdinal = 0,
        codec = "aac",
        normalizedLanguage = "eng",
        label = "English",
    )

private val audioSelection = EmbeddedAudioSelection(audioTarget, audioDescriptor)

private val audioPlan =
    PlaybackPlan(
        itemId = audioTarget.itemId,
        mediaSourceId = "source-1",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/item-1/stream",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
        selectedAudioStreamIndex = audioTarget.streamIndex,
        embeddedAudioTracks = listOf(audioDescriptor),
        audioActivationTarget = audioTarget,
    )

private val audioSession =
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
