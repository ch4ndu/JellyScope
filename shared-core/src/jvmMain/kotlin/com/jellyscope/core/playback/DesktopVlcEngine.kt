// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import co.touchlab.kermit.Logger
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import kotlin.math.max

internal enum class DesktopVlcEvent {
    Opening,
    Buffering,
    Playing,
    Paused,
    Stopped,
    EndReached,
    EncounteredError,
    SeekableChanged,
    TracksChanged,
}

internal data class DesktopVlcTrack(
    val id: Int,
    val label: String?,
)

internal enum class DesktopVlcNativeState {
    NothingSpecial,
    Opening,
    Buffering,
    Playing,
    Paused,
    Stopped,
    Ended,
    Error,
    Unknown,
}

internal data class DesktopVlcSnapshot(
    val positionMs: Long,
    val durationMs: Long?,
    val seekable: Boolean,
    val displayedPictures: Long?,
    val lostPictures: Long?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val nativeState: DesktopVlcNativeState = DesktopVlcNativeState.Unknown,
)

internal interface DesktopVlcEngine {
    fun setEventListener(listener: ((DesktopVlcEvent) -> Unit)?)

    fun prepare(
        authorizedMediaUrl: String,
        externalSubtitleResource: String?,
        startPositionMs: Long,
    )

    fun setDrawable(surfaceAddress: Long?)

    fun play(): Boolean

    fun pause()

    fun seekTo(positionMs: Long)

    fun stop()

    fun setVolume(percent: Int)

    fun setMuted(muted: Boolean)

    fun setRate(speed: Float)

    fun setFillCrop(
        crop: Boolean,
        width: Int,
        height: Int,
    )

    fun audioTracks(): List<DesktopVlcTrack>

    fun subtitleTracks(): List<DesktopVlcTrack>

    fun selectAudioTrack(id: Int): Boolean

    /** Returns true only when native readback confirms the requested selector. */
    fun selectSubtitleTrack(id: Int?): Boolean

    fun snapshot(): DesktopVlcSnapshot

    fun release()
}

internal object DesktopVlcEngineFactory {
    fun create(): DesktopVlcEngine = JnaDesktopVlcEngine(DesktopVlcRuntimeDiscovery.discover())
}

internal data class DesktopVlcRuntime(
    val libraryFile: File,
    val coreLibraryFile: File,
    val pluginsDirectory: File,
)

internal object DesktopVlcRuntimeDiscovery {
    fun discover(): DesktopVlcRuntime {
        val bundledRoot =
            System
                .getProperty("compose.application.resources.dir")
                ?.takeIf { path -> path.isNotBlank() }
                ?.let(::File)
        val candidates =
            listOfNotNull(
                bundledRoot?.let(::runtimeUnder),
                runtimeUnder(File("/Applications/VLC.app/Contents/MacOS")),
            )
        return candidates.firstOrNull(::isUsableRuntime)
            ?: throw DesktopVlcInitializationException()
    }

    internal fun runtimeUnder(root: File): DesktopVlcRuntime =
        DesktopVlcRuntime(
            libraryFile = File(root, "lib/libvlc.dylib"),
            coreLibraryFile = File(root, "lib/libvlccore.dylib"),
            pluginsDirectory = File(root, "plugins"),
        )

    internal fun isUsableRuntime(runtime: DesktopVlcRuntime): Boolean =
        runtime.libraryFile.isFile &&
            runtime.coreLibraryFile.isFile &&
            runtime.pluginsDirectory.isDirectory
}

private val cachedDesktopLibVlcRuntimeAvailable: Boolean by lazy {
    // Settings/profile discovery is intentionally a cheap, cached file check.
    // A VLC install that appears after this is evaluated requires an app restart
    // to change the advertised backend; playback performs fresh discovery when
    // it creates the engine.
    runCatching { DesktopVlcRuntimeDiscovery.discover() }.isSuccess
}

fun desktopLibVlcRuntimeAvailable(): Boolean = cachedDesktopLibVlcRuntimeAvailable

internal class DesktopVlcInitializationException : IllegalStateException("Desktop LibVLC is unavailable.")

private class JnaDesktopVlcEngine(
    runtime: DesktopVlcRuntime,
) : DesktopVlcEngine {
    private val lock = Any()
    private val lib: LibVlc
    private val instance: Pointer
    private val player: Pointer
    private val eventManager: Pointer
    private val logger = Logger.withTag("DesktopLibVlc")
    private var media: Pointer? = null

    // Written under `lock`, read from libvlc's own event-callback thread.
    @Volatile
    private var listener: ((DesktopVlcEvent) -> Unit)? = null
    private var callback: LibVlc.EventCallback? = null
    private var logCallback: LibVlc.LogCallback? = null
    private var lastLoggedErrorModule: String? = null
    private var muted = false
    private var volume = 100

    init {
        val environment = Native.load("c", DesktopVlcPosixC::class.java)
        if (environment.setenv("VLC_PLUGIN_PATH", runtime.pluginsDirectory.absolutePath, 1) != 0) {
            throw DesktopVlcInitializationException()
        }
        runCatching { System.load(runtime.coreLibraryFile.absolutePath) }
            .getOrElse { throw DesktopVlcInitializationException() }
        lib =
            runCatching {
                Native.load(runtime.libraryFile.absolutePath, LibVlc::class.java)
            }.getOrElse { throw DesktopVlcInitializationException() }
        val version =
            runCatching { lib.libvlc_get_version()?.getString(0).orEmpty() }
                .getOrDefault("")
        if (!version.startsWith("3.")) {
            throw DesktopVlcInitializationException()
        }
        val arguments = desktopVlcInstanceArguments()
        instance =
            lib.libvlc_new(arguments.size, arguments)
                ?: throw DesktopVlcInitializationException()
        installSafeErrorLogging()
        player =
            lib.libvlc_media_player_new(instance)
                ?: run {
                    lib.libvlc_release(instance)
                    throw DesktopVlcInitializationException()
                }
        eventManager =
            lib.libvlc_media_player_event_manager(player)
                ?: run {
                    lib.libvlc_media_player_release(player)
                    lib.libvlc_release(instance)
                    throw DesktopVlcInitializationException()
                }
    }

    override fun setEventListener(listener: ((DesktopVlcEvent) -> Unit)?) {
        synchronized(lock) {
            detachCallback()
            this.listener = listener
            if (listener == null) return
            val nextCallback =
                LibVlc.EventCallback { event, _ ->
                    val eventType = event?.getInt(0) ?: return@EventCallback
                    eventType.toDesktopVlcEvent()?.let { mapped -> this.listener?.invoke(mapped) }
                }
            callback = nextCallback
            OBSERVED_EVENTS.forEach { event ->
                lib.libvlc_event_attach(eventManager, event, nextCallback, null)
            }
        }
    }

    override fun prepare(
        authorizedMediaUrl: String,
        externalSubtitleResource: String?,
        startPositionMs: Long,
    ) {
        synchronized(lock) {
            val nextMedia =
                lib.libvlc_media_new_location(instance, authorizedMediaUrl)
                    ?: throw DesktopVlcInitializationException()
            desktopVlcStartTimeOption(startPositionMs)?.let { option ->
                lib.libvlc_media_add_option(nextMedia, option)
                emitDesktopVlcProbe(
                    DesktopVlcProbeRecord(
                        action = DesktopVlcProbeAction.PREPARE_START,
                        positionMs = startPositionMs.coerceAtLeast(0L),
                    ),
                )
            }
            if (externalSubtitleResource != null) {
                val accepted =
                    lib.libvlc_media_slaves_add(
                        nextMedia,
                        LIBVLC_MEDIA_SLAVE_TYPE_SUBTITLE,
                        LIBVLC_MEDIA_SLAVE_PRIORITY,
                        externalSubtitleResource,
                    )
                emitDesktopVlcProbe(
                    DesktopVlcProbeRecord(
                        action = DesktopVlcProbeAction.EXTERNAL_SUBTITLE,
                        nativeResult = accepted,
                        detail = DesktopProbeToken.from(externalSubtitleResource.substringBefore(':', "unknown")),
                    ),
                )
                if (accepted != 0) {
                    lib.libvlc_media_release(nextMedia)
                    throw DesktopVlcSubtitleException()
                }
            }
            lib.libvlc_media_player_set_media(player, nextMedia)
            media?.let(lib::libvlc_media_release)
            media = nextMedia
        }
    }

    override fun setDrawable(surfaceAddress: Long?) {
        synchronized(lock) {
            lib.libvlc_media_player_set_nsobject(player, surfaceAddress?.let(::Pointer))
        }
    }

    override fun play(): Boolean = synchronized(lock) { lib.libvlc_media_player_play(player) == 0 }

    override fun pause() {
        synchronized(lock) { lib.libvlc_media_player_set_pause(player, 1) }
    }

    override fun seekTo(positionMs: Long) {
        synchronized(lock) { lib.libvlc_media_player_set_time(player, positionMs.coerceAtLeast(0L)) }
    }

    override fun stop() {
        synchronized(lock) { lib.libvlc_media_player_stop(player) }
    }

    override fun setVolume(percent: Int) {
        synchronized(lock) {
            volume = percent.coerceIn(0, 100)
            lib.libvlc_audio_set_volume(player, if (muted) 0 else volume)
        }
    }

    override fun setMuted(muted: Boolean) {
        synchronized(lock) {
            this.muted = muted
            lib.libvlc_audio_set_volume(player, if (muted) 0 else volume)
        }
    }

    override fun setRate(speed: Float) {
        synchronized(lock) { lib.libvlc_media_player_set_rate(player, speed) }
    }

    override fun setFillCrop(
        crop: Boolean,
        width: Int,
        height: Int,
    ) {
        synchronized(lock) {
            lib.libvlc_video_set_scale(player, 0f)
            val geometry =
                if (crop && width > 0 && height > 0) {
                    "${width / greatestCommonDivisor(width, height)}:${height / greatestCommonDivisor(width, height)}"
                } else {
                    null
                }
            lib.libvlc_video_set_crop_geometry(player, geometry)
        }
    }

    override fun audioTracks(): List<DesktopVlcTrack> =
        synchronized(lock) {
            readTrackDescriptions(lib.libvlc_audio_get_track_description(player))
                .filterNot { track -> track.id == -1 }
        }

    override fun subtitleTracks(): List<DesktopVlcTrack> =
        synchronized(lock) {
            readTrackDescriptions(lib.libvlc_video_get_spu_description(player))
                .filterNot { track -> track.id == -1 }
        }

    override fun selectAudioTrack(id: Int): Boolean =
        synchronized(lock) {
            val setResult = lib.libvlc_audio_set_track(player, id)
            val selectedTrack = lib.libvlc_audio_get_track(player)
            emitDesktopVlcProbe(
                DesktopVlcProbeRecord(
                    action = DesktopVlcProbeAction.AUDIO_SET,
                    targetId = id,
                    nativeResult = setResult,
                    selectedId = selectedTrack,
                ),
            )
            selectedTrack == id || setResult == 0
        }

    override fun selectSubtitleTrack(id: Int?): Boolean =
        synchronized(lock) {
            val target = id ?: -1
            val setResult = lib.libvlc_video_set_spu(player, target)
            val selectedTrack = lib.libvlc_video_get_spu(player)
            emitDesktopVlcProbe(
                DesktopVlcProbeRecord(
                    action = DesktopVlcProbeAction.SUBTITLE_SET,
                    targetId = target,
                    nativeResult = setResult,
                    selectedId = selectedTrack,
                    accepted = selectedTrack == target,
                ),
            )
            selectedTrack == target
        }

    override fun snapshot(): DesktopVlcSnapshot =
        synchronized(lock) {
            val currentMedia = media
            val stats =
                currentMedia?.let { mediaPointer ->
                    LibVlcMediaStats().also { value ->
                        value.write()
                        if (lib.libvlc_media_get_stats(mediaPointer, value) == 0) return@let null
                        value.read()
                    }
                }
            val width = IntByReference()
            val height = IntByReference()
            val hasSize = lib.libvlc_video_get_size(player, 0, width, height) == 0
            val duration = lib.libvlc_media_player_get_length(player).takeIf { value -> value > 0L }
            DesktopVlcSnapshot(
                positionMs = lib.libvlc_media_player_get_time(player).coerceAtLeast(0L),
                durationMs = duration,
                seekable = lib.libvlc_media_player_is_seekable(player) != 0,
                displayedPictures = stats?.displayedPictures?.toLong()?.takeIf { value -> value >= 0L },
                lostPictures = stats?.lostPictures?.toLong()?.takeIf { value -> value >= 0L },
                width = width.value.takeIf { hasSize && it > 0 },
                height = height.value.takeIf { hasSize && it > 0 },
                frameRate = lib.libvlc_media_player_get_fps(player).toDouble().takeIf { value -> value > 0.0 },
                nativeState = lib.libvlc_media_player_get_state(player).toDesktopVlcNativeState(),
            )
        }

    override fun release() {
        synchronized(lock) {
            detachCallback()
            listener = null
            lib.libvlc_log_unset(instance)
            logCallback = null
            lib.libvlc_media_player_set_nsobject(player, null)
            lib.libvlc_media_player_stop(player)
            media?.let(lib::libvlc_media_release)
            media = null
            lib.libvlc_media_player_release(player)
            lib.libvlc_release(instance)
        }
    }

    private fun detachCallback() {
        val current = callback ?: return
        OBSERVED_EVENTS.forEach { event ->
            lib.libvlc_event_detach(eventManager, event, current, null)
        }
        callback = null
    }

    private fun installSafeErrorLogging() {
        val nextCallback =
            LibVlc.LogCallback { _, level, context, _, _ ->
                if (level < LIBVLC_LOG_ERROR || context == null) return@LogCallback
                val moduleReference = PointerByReference()
                lib.libvlc_log_get_context(context, moduleReference, null, null)
                val module =
                    moduleReference.value
                        ?.getString(0)
                        ?.takeIf(::isSafeVlcModuleName)
                        ?: "unknown"
                if (module != lastLoggedErrorModule) {
                    lastLoggedErrorModule = module
                    logger.w { "native error module=$module" }
                }
            }
        logCallback = nextCallback
        lib.libvlc_log_set(instance, nextCallback, null)
    }

    private fun readTrackDescriptions(head: Pointer?): List<DesktopVlcTrack> {
        if (head == null) return emptyList()
        val tracks = mutableListOf<DesktopVlcTrack>()
        var current: Pointer? = head
        while (current != null) {
            val description = LibVlcTrackDescription(current)
            description.read()
            tracks +=
                DesktopVlcTrack(
                    id = description.id,
                    label = description.name?.getString(0)?.takeIf { value -> value.isNotBlank() },
                )
            current = description.next
        }
        lib.libvlc_track_description_list_release(head)
        return tracks
    }
}

internal fun desktopVlcInstanceArguments(): Array<String> =
    arrayOf(
        "--no-video-title-show",
        "--no-osd",
        "--quiet",
        "--no-video-on-top",
    )

internal fun desktopVlcStartTimeOption(positionMs: Long): String? {
    val safePositionMs = positionMs.coerceAtLeast(0L)
    if (safePositionMs == 0L) return null
    val seconds = safePositionMs / MILLIS_PER_SECOND
    val milliseconds = safePositionMs % MILLIS_PER_SECOND
    val value =
        if (milliseconds == 0L) {
            seconds.toString()
        } else {
            "$seconds.${milliseconds.toString().padStart(3, '0').trimEnd('0')}"
        }
    return ":start-time=$value"
}

internal fun emitDesktopVlcProbe(record: DesktopVlcProbeRecord) {
    if (!DesktopPlaybackProbe.isEnabled) return
    DesktopPlaybackProbe.emit(record)
}

internal class DesktopVlcSubtitleException : IllegalStateException("Desktop LibVLC rejected the subtitle.")

private fun Int.toDesktopVlcEvent(): DesktopVlcEvent? =
    when (this) {
        LIBVLC_EVENT_OPENING -> DesktopVlcEvent.Opening
        LIBVLC_EVENT_BUFFERING -> DesktopVlcEvent.Buffering
        LIBVLC_EVENT_PLAYING -> DesktopVlcEvent.Playing
        LIBVLC_EVENT_PAUSED -> DesktopVlcEvent.Paused
        LIBVLC_EVENT_STOPPED -> DesktopVlcEvent.Stopped
        LIBVLC_EVENT_END_REACHED -> DesktopVlcEvent.EndReached
        LIBVLC_EVENT_ENCOUNTERED_ERROR -> DesktopVlcEvent.EncounteredError
        LIBVLC_EVENT_SEEKABLE_CHANGED -> DesktopVlcEvent.SeekableChanged
        LIBVLC_EVENT_ES_ADDED,
        LIBVLC_EVENT_ES_DELETED,
        LIBVLC_EVENT_ES_SELECTED,
        -> DesktopVlcEvent.TracksChanged
        else -> null
    }

private fun greatestCommonDivisor(
    first: Int,
    second: Int,
): Int {
    var a = max(first, 1)
    var b = max(second, 1)
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a
}

@Suppress("FunctionName")
private interface LibVlc : Library {
    fun libvlc_get_version(): Pointer?

    fun libvlc_new(
        argc: Int,
        argv: Array<String>,
    ): Pointer?

    fun libvlc_release(instance: Pointer)

    fun libvlc_log_set(
        instance: Pointer,
        callback: LogCallback,
        userData: Pointer?,
    )

    fun libvlc_log_unset(instance: Pointer)

    fun libvlc_log_get_context(
        context: Pointer,
        module: PointerByReference?,
        file: PointerByReference?,
        line: IntByReference?,
    )

    fun libvlc_media_new_location(
        instance: Pointer,
        location: String,
    ): Pointer?

    fun libvlc_media_release(media: Pointer)

    fun libvlc_media_add_option(
        media: Pointer,
        option: String,
    )

    fun libvlc_media_slaves_add(
        media: Pointer,
        type: Int,
        priority: Int,
        uri: String,
    ): Int

    fun libvlc_media_get_stats(
        media: Pointer,
        stats: LibVlcMediaStats,
    ): Int

    fun libvlc_media_player_new(instance: Pointer): Pointer?

    fun libvlc_media_player_release(player: Pointer)

    fun libvlc_media_player_set_media(
        player: Pointer,
        media: Pointer,
    )

    fun libvlc_media_player_event_manager(player: Pointer): Pointer?

    fun libvlc_media_player_set_nsobject(
        player: Pointer,
        drawable: Pointer?,
    )

    fun libvlc_media_player_play(player: Pointer): Int

    fun libvlc_media_player_set_pause(
        player: Pointer,
        pause: Int,
    )

    fun libvlc_media_player_stop(player: Pointer)

    fun libvlc_media_player_get_time(player: Pointer): Long

    fun libvlc_media_player_set_time(
        player: Pointer,
        timeMs: Long,
    )

    fun libvlc_media_player_get_length(player: Pointer): Long

    fun libvlc_media_player_get_state(player: Pointer): Int

    fun libvlc_media_player_is_seekable(player: Pointer): Int

    fun libvlc_media_player_set_rate(
        player: Pointer,
        rate: Float,
    ): Int

    fun libvlc_media_player_get_fps(player: Pointer): Float

    fun libvlc_audio_set_volume(
        player: Pointer,
        volume: Int,
    ): Int

    fun libvlc_audio_get_track_description(player: Pointer): Pointer?

    fun libvlc_audio_get_track(player: Pointer): Int

    fun libvlc_audio_set_track(
        player: Pointer,
        track: Int,
    ): Int

    fun libvlc_video_get_spu_description(player: Pointer): Pointer?

    fun libvlc_video_get_spu(player: Pointer): Int

    fun libvlc_video_set_spu(
        player: Pointer,
        track: Int,
    ): Int

    fun libvlc_video_get_size(
        player: Pointer,
        video: Int,
        width: IntByReference,
        height: IntByReference,
    ): Int

    fun libvlc_video_set_scale(
        player: Pointer,
        scale: Float,
    )

    fun libvlc_video_set_crop_geometry(
        player: Pointer,
        geometry: String?,
    )

    fun libvlc_track_description_list_release(description: Pointer)

    fun libvlc_event_attach(
        eventManager: Pointer,
        eventType: Int,
        callback: EventCallback,
        userData: Pointer?,
    ): Int

    fun libvlc_event_detach(
        eventManager: Pointer,
        eventType: Int,
        callback: EventCallback,
        userData: Pointer?,
    )

    fun interface EventCallback : Callback {
        fun invoke(
            event: Pointer?,
            userData: Pointer?,
        )
    }

    fun interface LogCallback : Callback {
        fun invoke(
            userData: Pointer?,
            level: Int,
            context: Pointer?,
            format: Pointer?,
            arguments: Pointer?,
        )
    }
}

@Suppress("FunctionName")
private interface DesktopVlcPosixC : Library {
    fun setenv(
        name: String,
        value: String,
        overwrite: Int,
    ): Int
}

@Structure.FieldOrder("id", "name", "next")
internal class LibVlcTrackDescription(
    pointer: Pointer,
) : Structure(pointer) {
    @JvmField var id: Int = 0

    @JvmField var name: Pointer? = null

    @JvmField var next: Pointer? = null
}

@Structure.FieldOrder(
    "readBytes",
    "inputBitrate",
    "demuxReadBytes",
    "demuxBitrate",
    "demuxCorrupted",
    "demuxDiscontinuity",
    "decodedVideo",
    "decodedAudio",
    "displayedPictures",
    "lostPictures",
    "playedAudioBuffers",
    "lostAudioBuffers",
    "sentPackets",
    "sentBytes",
    "sendBitrate",
)
internal class LibVlcMediaStats : Structure() {
    @JvmField var readBytes: Int = 0

    @JvmField var inputBitrate: Float = 0f

    @JvmField var demuxReadBytes: Int = 0

    @JvmField var demuxBitrate: Float = 0f

    @JvmField var demuxCorrupted: Int = 0

    @JvmField var demuxDiscontinuity: Int = 0

    @JvmField var decodedVideo: Int = 0

    @JvmField var decodedAudio: Int = 0

    @JvmField var displayedPictures: Int = 0

    @JvmField var lostPictures: Int = 0

    @JvmField var playedAudioBuffers: Int = 0

    @JvmField var lostAudioBuffers: Int = 0

    @JvmField var sentPackets: Int = 0

    @JvmField var sentBytes: Int = 0

    @JvmField var sendBitrate: Float = 0f
}

private const val LIBVLC_EVENT_OPENING = 0x102
private const val LIBVLC_EVENT_BUFFERING = 0x103
private const val LIBVLC_EVENT_PLAYING = 0x104
private const val LIBVLC_EVENT_PAUSED = 0x105
private const val LIBVLC_EVENT_STOPPED = 0x106
private const val LIBVLC_EVENT_END_REACHED = 0x109
private const val LIBVLC_EVENT_ENCOUNTERED_ERROR = 0x10a
private const val LIBVLC_EVENT_SEEKABLE_CHANGED = 0x10d
private const val LIBVLC_EVENT_ES_ADDED = 0x114
private const val LIBVLC_EVENT_ES_DELETED = 0x115
private const val LIBVLC_EVENT_ES_SELECTED = 0x116
private const val LIBVLC_MEDIA_SLAVE_TYPE_SUBTITLE = 1
private const val LIBVLC_MEDIA_SLAVE_PRIORITY = 4
private const val LIBVLC_LOG_ERROR = 4
private const val MILLIS_PER_SECOND = 1_000L

private fun Int.toDesktopVlcNativeState(): DesktopVlcNativeState =
    when (this) {
        0 -> DesktopVlcNativeState.NothingSpecial
        1 -> DesktopVlcNativeState.Opening
        2 -> DesktopVlcNativeState.Buffering
        3 -> DesktopVlcNativeState.Playing
        4 -> DesktopVlcNativeState.Paused
        5 -> DesktopVlcNativeState.Stopped
        6 -> DesktopVlcNativeState.Ended
        7 -> DesktopVlcNativeState.Error
        else -> DesktopVlcNativeState.Unknown
    }

private val OBSERVED_EVENTS =
    intArrayOf(
        LIBVLC_EVENT_OPENING,
        LIBVLC_EVENT_BUFFERING,
        LIBVLC_EVENT_PLAYING,
        LIBVLC_EVENT_PAUSED,
        LIBVLC_EVENT_STOPPED,
        LIBVLC_EVENT_END_REACHED,
        LIBVLC_EVENT_ENCOUNTERED_ERROR,
        LIBVLC_EVENT_SEEKABLE_CHANGED,
        LIBVLC_EVENT_ES_ADDED,
        LIBVLC_EVENT_ES_DELETED,
        LIBVLC_EVENT_ES_SELECTED,
    )

internal fun isSafeVlcModuleName(value: String): Boolean =
    value.isNotEmpty() &&
        value.length <= 64 &&
        value.all { character ->
            character.isLetterOrDigit() || character == '_' || character == '-' || character == '.'
        }
