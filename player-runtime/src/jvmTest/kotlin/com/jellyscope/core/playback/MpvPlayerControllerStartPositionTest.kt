// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvPlayerControllerStartPositionTest {
    @Test
    fun everyLoadExplicitlySetsOrResetsStartPosition() {
        val lib = RecordingLibMpv()
        val controller = MpvPlayerController(session, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), lib)
        try {
            controller.prepare(playbackPlan(itemId = "resumed", startPositionMs = 12_000L))
            controller.prepare(playbackPlan(itemId = "from-start", startPositionMs = 0L))

            val loadIndexes = lib.events.indices.filter { index -> lib.events[index].startsWith("command:loadfile:") }
            assertEquals(2, loadIndexes.size)
            loadIndexes.forEach { loadIndex ->
                assertEquals(
                    "property:pause:yes",
                    lib.events.take(loadIndex).last { event -> event.startsWith("property:pause:") },
                )
            }
            assertEquals(
                "option:start:+12.0",
                lib.events.take(loadIndexes[0]).last { event -> event.startsWith("option:start:") },
            )
            assertEquals(
                "option:start:none",
                lib.events.take(loadIndexes[1]).last { event -> event.startsWith("option:start:") },
            )
        } finally {
            controller.release()
        }
    }

    @Test
    fun failedStartResetPreventsLoadingWithStaleState() {
        val lib = RecordingLibMpv(failStartOption = true)
        val controller = MpvPlayerController(session, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), lib)
        try {
            controller.prepare(playbackPlan(itemId = "from-start", startPositionMs = 0L))

            assertTrue(lib.events.none { event -> event.startsWith("command:loadfile:") })
            assertEquals(PlaybackStatus.Failed, controller.playbackState.value.status)
        } finally {
            controller.release()
        }
    }
}

private class RecordingLibMpv(
    private val failStartOption: Boolean = false,
) : LibMpv {
    val events = mutableListOf<String>()
    private val context = Pointer(1L)
    private val renderContext = Pointer(2L)

    override fun mpv_create(): Pointer = context

    override fun mpv_initialize(ctx: Pointer): Int = 0

    override fun mpv_terminate_destroy(ctx: Pointer) = Unit

    override fun mpv_error_string(error: Int): Pointer? = null

    override fun mpv_set_option_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int {
        events += "option:$name:$data"
        return if (failStartOption && name == "start") -1 else 0
    }

    override fun mpv_set_property_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int {
        events += "property:$name:$data"
        return 0
    }

    override fun mpv_get_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int = -1

    override fun mpv_get_property_string(
        ctx: Pointer,
        name: String,
    ): Pointer? = null

    override fun mpv_free(data: Pointer) = Unit

    override fun mpv_command(
        ctx: Pointer,
        args: Array<String?>,
    ): Int {
        events += "command:${args.filterNotNull().joinToString(":")}"
        return 0
    }

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
}

private fun playbackPlan(
    itemId: String,
    startPositionMs: Long,
): PlaybackPlan =
    PlaybackPlan(
        itemId = itemId,
        mediaSourceId = "source-$itemId",
        startPositionMs = startPositionMs,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/$itemId/stream",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
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
