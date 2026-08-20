// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

@Suppress("FunctionName")
internal interface LibMpv : Library {
    // client.h
    fun mpv_create(): Pointer?

    fun mpv_initialize(ctx: Pointer): Int

    fun mpv_terminate_destroy(ctx: Pointer)

    // Human-readable name for an mpv error code (e.g. -12 -> "unsupported").
    fun mpv_error_string(error: Int): Pointer?

    fun mpv_set_option_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int

    fun mpv_set_property_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int

    // get property into a caller-allocated buffer (format: DOUBLE=5, INT64=4, FLAG=3)
    fun mpv_get_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int

    fun mpv_get_property_string(
        ctx: Pointer,
        name: String,
    ): Pointer?

    fun mpv_free(data: Pointer)

    // args = null-terminated array of C strings, e.g. ["loadfile", url, null]
    fun mpv_command(
        ctx: Pointer,
        args: Array<String?>,
    ): Int

    // The returned event and its payload are owned by libmpv and remain valid
    // only until the next call. Callers must copy any fields they need before
    // polling again.
    fun mpv_wait_event(
        ctx: Pointer,
        timeout: Double,
    ): Pointer?

    // render.h
    fun mpv_render_context_create(
        res: PointerByReference,
        mpv: Pointer,
        params: Array<MpvRenderParam>,
    ): Int

    fun mpv_render_context_render(
        ctx: Pointer,
        params: Array<MpvRenderParam>,
    ): Int

    fun mpv_render_context_set_update_callback(
        ctx: Pointer,
        callback: MpvRenderUpdateFn?,
        callbackCtx: Pointer?,
    )

    fun mpv_render_context_update(ctx: Pointer): Long

    fun mpv_render_context_free(ctx: Pointer)

    fun interface MpvRenderUpdateFn : Callback {
        fun invoke(cbCtx: Pointer?)
    }

    fun interface MpvOpenGlGetProcAddressFn : Callback {
        fun invoke(
            callbackCtx: Pointer?,
            name: String?,
        ): Pointer?
    }

    companion object {
        val INSTANCE: LibMpv by lazy { loadLibMpv() }

        private fun loadLibMpv(): LibMpv {
            val bundledDir = System.getProperty("compose.application.resources.dir")
            val library =
                if (
                    com.sun.jna.Platform
                        .isMac()
                ) {
                    val bundled = bundledDir?.let { dir -> java.io.File(dir, "libmpv.2.dylib") }
                    bundled
                        ?.takeIf(java.io.File::isFile)
                        ?.absolutePath
                        ?: throw UnsatisfiedLinkError("Bundled macOS libmpv runtime is missing")
                } else {
                    val bundled =
                        bundledDir
                            ?.let { dir -> listOf("$dir/libmpv.so", "$dir/mpv-2.dll") }
                            .orEmpty()
                    val candidates =
                        bundled +
                            listOf(
                                "/usr/lib/x86_64-linux-gnu/libmpv.so",
                                "/usr/lib/libmpv.so",
                                "/usr/lib64/libmpv.so",
                            )
                    candidates.firstOrNull { path -> java.io.File(path).exists() } ?: "mpv"
                }
            val lib =
                com.sun.jna.Native
                    .load(library, LibMpv::class.java)
            // libmpv requires LC_NUMERIC=C ("Non-C locale detected. This is not
            // supported."), but the JVM inherits the user's locale at startup.
            // Reset only the numeric category on the C library; the JVM formats
            // numbers via java.util.Locale, so this doesn't affect Kotlin/Java.
            runCatching {
                val cLib =
                    if (com.sun.jna.Platform
                            .isWindows()
                    ) {
                        "msvcrt"
                    } else {
                        "c"
                    }
                val lcNumeric =
                    if (com.sun.jna.Platform
                            .isLinux()
                    ) {
                        LC_NUMERIC_GLIBC
                    } else {
                        LC_NUMERIC_BSD
                    }
                com.sun.jna.Native
                    .load(cLib, PosixC::class.java)
                    .setlocale(lcNumeric, "C")
            }
            return lib
        }

        // LC_NUMERIC category value differs by C library: glibc=1, BSD/macOS &
        // MSVCRT=4.
        private const val LC_NUMERIC_GLIBC = 1
        private const val LC_NUMERIC_BSD = 4

        // mpv_format
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5

        // mpv_render_param_type values from mpv 0.41 render.h.
        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_OPENGL_INIT_PARAMS = 2
        const val RENDER_PARAM_OPENGL_FBO = 3
        const val RENDER_PARAM_FLIP_Y = 4
        const val RENDER_PARAM_SW_SIZE = 17
        const val RENDER_PARAM_SW_FORMAT = 18
        const val RENDER_PARAM_SW_STRIDE = 19
        const val RENDER_PARAM_SW_POINTER = 20

        // mpv_render_context_update() return flags
        const val RENDER_UPDATE_FRAME = 1L

        // mpv_event_id values required by the controller lifecycle bridge.
        const val EVENT_NONE = 0
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_QUEUE_OVERFLOW = 24

        // mpv_end_file_reason values required before a deferred sidecar has
        // been attached. Unknown values intentionally remain ignored.
        const val END_FILE_REASON_EOF = 0
        const val END_FILE_REASON_STOP = 2
        const val END_FILE_REASON_ERROR = 4
        const val END_FILE_REASON_REDIRECT = 5

        // mpv_error values from client.h used for typed playback recovery and
        // tolerant initialization options.
        const val MPV_ERROR_OPTION_NOT_FOUND = -5
        const val MPV_ERROR_LOADING_FAILED = -13
        const val MPV_ERROR_NOTHING_TO_PLAY = -16
        const val MPV_ERROR_UNKNOWN_FORMAT = -17
        const val MPV_ERROR_UNSUPPORTED = -18
    }
}

// struct mpv_opengl_init_params { mpv_opengl_init_params::get_proc_address; void *get_proc_address_ctx; }
@Structure.FieldOrder("getProcAddress", "getProcAddressCtx")
internal open class MpvOpenGlInitParams(
    @JvmField var getProcAddress: LibMpv.MpvOpenGlGetProcAddressFn? = null,
    @JvmField var getProcAddressCtx: Pointer? = null,
) : Structure()

// struct mpv_opengl_fbo { int fbo; int w; int h; int internal_format; }
@Structure.FieldOrder("fbo", "width", "height", "internalFormat")
internal open class MpvOpenGlFbo(
    @JvmField var fbo: Int = 0,
    @JvmField var width: Int = 0,
    @JvmField var height: Int = 0,
    @JvmField var internalFormat: Int = 0,
) : Structure()

// Minimal C-library binding just to call setlocale(LC_NUMERIC, "C") for libmpv.
@Suppress("FunctionName")
private interface PosixC : Library {
    fun setlocale(
        category: Int,
        locale: String?,
    ): Pointer?
}

// struct mpv_render_param { enum type (int); void *data; }
@Structure.FieldOrder("type", "data")
internal open class MpvRenderParam(
    @JvmField var type: Int = 0,
    @JvmField var data: Pointer? = null,
) : Structure()

// client.h event structures. These are deliberately JVM-private boundary
// types: no libmpv pointer or ABI detail crosses into shared playback code.
@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
internal class MpvEvent(
    pointer: Pointer,
) : Structure(pointer) {
    @JvmField var eventId: Int = 0

    @JvmField var error: Int = 0

    @JvmField var replyUserdata: Long = 0L

    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("playlistEntryId")
internal class MpvEventStartFile(
    pointer: Pointer,
) : Structure(pointer) {
    @JvmField var playlistEntryId: Long = 0L
}

@Structure.FieldOrder("reason", "error", "playlistEntryId", "playlistInsertId", "playlistInsertNumEntries")
internal class MpvEventEndFile(
    pointer: Pointer,
) : Structure(pointer) {
    @JvmField var reason: Int = 0

    @JvmField var error: Int = 0

    @JvmField var playlistEntryId: Long = 0L

    @JvmField var playlistInsertId: Long = 0L

    @JvmField var playlistInsertNumEntries: Int = 0
}
