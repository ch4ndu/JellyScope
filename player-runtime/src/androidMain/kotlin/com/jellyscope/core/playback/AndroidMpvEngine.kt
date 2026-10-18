// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import android.view.Surface
import com.jellyscope.core.domain.playback.PlaybackNativeFailureReason
import com.jellyscope.core.domain.playback.PlaybackNativeVideoFormat
import dev.jdtech.mpv.MPVLib
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Project-owned events emitted by the libmpv adapter. */
internal sealed interface AndroidMpvEvent {
    data class VideoFormat(
        val format: PlaybackNativeVideoFormat,
    ) : AndroidMpvEvent

    data class PropertyBoolean(
        val name: String,
        val value: Boolean,
    ) : AndroidMpvEvent

    data class PropertyLong(
        val name: String,
        val value: Long,
    ) : AndroidMpvEvent

    data class PropertyDouble(
        val name: String,
        val value: Double,
    ) : AndroidMpvEvent

    data class PropertyString(
        val name: String,
        val value: String,
    ) : AndroidMpvEvent

    data class PropertyCleared(
        val name: String,
    ) : AndroidMpvEvent

    data class NativeEvent(
        val id: Int,
    ) : AndroidMpvEvent

    /** Raw native text is reduced to a bounded category before crossing the seam. */
    data class SanitizedLog(
        val severity: AndroidMpvLogSeverity,
        val category: AndroidMpvLogCategory,
        val reason: PlaybackNativeFailureReason = PlaybackNativeFailureReason.Other,
    ) : AndroidMpvEvent
}

internal enum class AndroidMpvLogSeverity {
    Error,
}

internal enum class AndroidMpvLogRequestLevel {
    Disabled,
    Error,
    VideoFormat,
}

internal enum class AndroidMpvLogCategory {
    Decoder,
    Network,
    Unsupported,
    Unknown,
}

internal interface AndroidMpvEngineObserver {
    fun onEvent(event: AndroidMpvEvent)
}

/**
 * Narrow project-owned boundary around the pinned Android libmpv wrapper.
 * Every method is blocking and must be called from the controller's serialized
 * native dispatcher. No vendor type escapes this file.
 */
internal interface AndroidMpvEngine {
    fun requestLogMessages(level: AndroidMpvLogRequestLevel): Int

    fun setOptionString(
        name: String,
        value: String,
    ): Int

    fun initialize()

    fun destroy()

    fun command(arguments: Array<String>)

    fun getPropertyBoolean(name: String): Boolean?

    fun getPropertyLong(name: String): Long?

    fun getPropertyDouble(name: String): Double?

    fun getPropertyString(name: String): String?

    fun setPropertyBoolean(
        name: String,
        value: Boolean,
    )

    fun setPropertyLong(
        name: String,
        value: Long,
    )

    fun setPropertyDouble(
        name: String,
        value: Double,
    )

    fun setPropertyString(
        name: String,
        value: String,
    )

    fun observeProperty(
        name: String,
        format: Int,
    )

    fun attachSurface(surface: Surface)

    fun detachSurface()

    /** Stop embedded output, detach only after native retirement, then drain old events. */
    fun detachSurfaceAfterStop(isReleased: () -> Boolean): Int

    fun addObserver(observer: AndroidMpvEngineObserver)

    fun removeObserver(observer: AndroidMpvEngineObserver)
}

internal fun interface AndroidMpvEngineFactory {
    fun create(context: Context): AndroidMpvEngine
}

internal object AndroidMpvNativeEventIds {
    const val END_FILE = 7
    const val FILE_LOADED = 8
    const val IDLE = 11
    const val START_FILE = 6
    const val SHUTDOWN = 1
    const val VIDEO_RECONFIG = 17
}

internal object AndroidMpvPropertyFormat {
    const val STRING = 1
    const val FLAG = 3
    const val INT64 = 4
    const val DOUBLE = 5
}

internal object AndroidMpvDefaultEngineFactory : AndroidMpvEngineFactory {
    override fun create(context: Context): AndroidMpvEngine =
        AndroidMpvLibEngine(
            MPVLib.create(context.applicationContext)
                ?: error("libmpv create returned no engine"),
        )
}

/** Vendor-containing implementation; keep it private to the Android edge. */
private class AndroidMpvLibEngine(
    private val library: MPVLib,
) : AndroidMpvEngine {
    private val observers = CopyOnWriteArrayList<AndroidMpvEngineObserver>()
    private val videoFormatCapture = AtomicBoolean(false)
    private var surfaceDetachSequence = 0L
    private val observedSurfaceDetachSequence = AtomicLong(0L)

    private val eventObserver =
        object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {
                dispatch(AndroidMpvEvent.PropertyCleared(property))
            }

            override fun eventProperty(
                property: String,
                value: Long,
            ) {
                if (property == SURFACE_DETACH_FENCE) {
                    observedSurfaceDetachSequence.set(value)
                    return
                }
                dispatch(AndroidMpvEvent.PropertyLong(property, value))
            }

            override fun eventProperty(
                property: String,
                value: Double,
            ) {
                dispatch(AndroidMpvEvent.PropertyDouble(property, value))
            }

            override fun eventProperty(
                property: String,
                value: Boolean,
            ) {
                dispatch(AndroidMpvEvent.PropertyBoolean(property, value))
            }

            override fun eventProperty(
                property: String,
                value: String,
            ) {
                dispatch(AndroidMpvEvent.PropertyString(property, value))
            }

            override fun event(eventId: Int) {
                dispatch(AndroidMpvEvent.NativeEvent(eventId))
            }
        }

    private val logObserver =
        object : MPVLib.LogObserver {
            override fun logMessage(
                prefix: String,
                level: Int,
                text: String,
            ) {
                if (videoFormatCapture.get() && level == MPVLib.MpvLogLevel.MPV_LOG_LEVEL_V) {
                    parseAndroidMpvVideoFormat(prefix, text)?.let { format ->
                        dispatch(AndroidMpvEvent.VideoFormat(format))
                    }
                }
                if (level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) return
                dispatch(
                    AndroidMpvEvent.SanitizedLog(
                        severity = AndroidMpvLogSeverity.Error,
                        category = classifyMpvLog(prefix, text),
                        reason = classifyMpvFailureReason(text),
                    ),
                )
            }
        }

    init {
        library.addObserver(eventObserver)
        library.addLogObserver(logObserver)
    }

    override fun setOptionString(
        name: String,
        value: String,
    ): Int = library.setOptionString(name, value)

    override fun requestLogMessages(level: AndroidMpvLogRequestLevel): Int {
        videoFormatCapture.set(level == AndroidMpvLogRequestLevel.VideoFormat)
        val result =
            try {
                library.requestLogMessages(
                    when (level) {
                        AndroidMpvLogRequestLevel.Disabled -> MPVLib.LogRequestLevel.Disabled
                        AndroidMpvLogRequestLevel.Error -> MPVLib.LogRequestLevel.Error
                        AndroidMpvLogRequestLevel.VideoFormat -> MPVLib.LogRequestLevel.Verbose
                    },
                )
            } catch (throwable: Throwable) {
                videoFormatCapture.set(false)
                throw throwable
            }
        if (result < 0) videoFormatCapture.set(false)
        return result
    }

    override fun initialize() = library.init()

    override fun destroy() {
        library.removeObserver(eventObserver)
        library.removeLogObserver(logObserver)
        observers.clear()
        library.destroy()
    }

    override fun command(arguments: Array<String>) = library.command(arguments)

    override fun getPropertyBoolean(name: String): Boolean? = library.getPropertyBoolean(name)

    override fun getPropertyLong(name: String): Long? = library.getPropertyInt(name)?.toLong()

    override fun getPropertyDouble(name: String): Double? = library.getPropertyDouble(name)

    override fun getPropertyString(name: String): String? = library.getPropertyString(name)

    override fun setPropertyBoolean(
        name: String,
        value: Boolean,
    ) = library.setPropertyBoolean(name, value)

    override fun setPropertyLong(
        name: String,
        value: Long,
    ) = library.setPropertyInt(name, value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())

    override fun setPropertyDouble(
        name: String,
        value: Double,
    ) = library.setPropertyDouble(name, value)

    override fun setPropertyString(
        name: String,
        value: String,
    ) = library.setPropertyString(name, value)

    override fun observeProperty(
        name: String,
        format: Int,
    ) = library.observeProperty(name, format)

    override fun attachSurface(surface: Surface) = library.attachSurface(surface)

    override fun detachSurface() = library.detachSurface()

    override fun detachSurfaceAfterStop(isReleased: () -> Boolean): Int {
        val deadline = System.nanoTime() + SURFACE_DETACH_TIMEOUT_NS
        val start = library.beginSurfaceDetach()
        if (start < 0) return start
        // Events are not shutdown evidence. JNI checks stopped state and destroys
        // any retained VO synchronously before releasing its Surface reference.
        while (true) {
            if (isReleased()) return 1
            val result = library.tryDetachStoppedSurface()
            if (result < 0) return result
            if (result == 0) break
            if (System.nanoTime() >= deadline) return NATIVE_DETACH_FAILED
            Thread.sleep(SURFACE_DETACH_POLL_MS)
        }
        val sequence = ++surfaceDetachSequence
        val marker = library.setSurfaceDetachFence(sequence)
        if (marker < 0) return marker
        // This exact property value follows all old queued END_FILE events.
        // The event thread signals it without Main or the controller native mutex.
        while (observedSurfaceDetachSequence.get() != sequence) {
            if (isReleased()) return 1
            if (System.nanoTime() >= deadline) return NATIVE_DETACH_FAILED
            Thread.sleep(SURFACE_DETACH_POLL_MS)
        }
        return 0
    }

    override fun addObserver(observer: AndroidMpvEngineObserver) {
        observers.addIfAbsent(observer)
    }

    override fun removeObserver(observer: AndroidMpvEngineObserver) {
        observers.remove(observer)
    }

    private fun dispatch(event: AndroidMpvEvent) {
        observers.forEach { observer -> observer.onEvent(event) }
    }

    private companion object {
        const val SURFACE_DETACH_FENCE = "user-data/jellyscope-surface-detach"
        const val SURFACE_DETACH_POLL_MS = 10L
        const val SURFACE_DETACH_TIMEOUT_NS = 3_000_000_000L
        const val NATIVE_DETACH_FAILED = -20
    }
}

private fun classifyMpvLog(
    prefix: String,
    text: String,
): AndroidMpvLogCategory {
    val value = "$prefix $text".lowercase()
    return when {
        "network" in value || "http" in value || "tls" in value || "ssl" in value -> AndroidMpvLogCategory.Network
        "decoder" in value || "codec" in value || "hwdec" in value -> AndroidMpvLogCategory.Decoder
        "unsupported" in value || "not found" in value || "unknown format" in value -> AndroidMpvLogCategory.Unsupported
        else -> AndroidMpvLogCategory.Unknown
    }
}

private fun classifyMpvFailureReason(text: String): PlaybackNativeFailureReason {
    val value = text.lowercase()
    return when {
        "cannot convert" in value -> PlaybackNativeFailureReason.VideoConversionUnsupported
        "failed initializing any suitable video output" in value -> PlaybackNativeFailureReason.VideoOutputInitializationFailed
        "failed to initialize a decoder" in value || "could not open codec" in value ->
            PlaybackNativeFailureReason.DecoderInitializationFailed
        else -> PlaybackNativeFailureReason.Other
    }
}
