// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap

private const val MEDIA_PLAYER_FRAMEWORK =
    "/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer"
private const val NOW_PLAYING_CLASS_NAME = "JellyScopeRemoteCommandTarget"
private const val NOW_PLAYING_SKIP_INTERVAL_SECONDS = 15.0
private const val MS_PER_SECOND = 1_000.0
private const val REMOTE_COMMAND_HANDLER_TYPES = "q@:@"

internal enum class MacosNowPlayingFailureEvent(
    val token: String,
) {
    Load("load"),
    Install("install"),
    Publish("publish"),
    Clear("clear"),
}

internal fun interface MacosNowPlayingFailureReporter {
    fun report(
        event: MacosNowPlayingFailureEvent,
        throwable: Throwable,
    )
}

internal object DefaultMacosNowPlayingFailureReporter : MacosNowPlayingFailureReporter {
    private val logger = diagnosticLogger(DiagnosticTag.MacosNowPlaying)

    override fun report(
        event: MacosNowPlayingFailureEvent,
        throwable: Throwable,
    ) {
        logger.i { macosNowPlayingFailureDiagnostic(event, throwable) }
    }
}

internal fun macosNowPlayingFailureDiagnostic(
    event: MacosNowPlayingFailureEvent,
    throwable: Throwable,
): String =
    formatSafeFailureDiagnostic(
        stage = "nowPlaying",
        event = event.token,
        throwable = throwable,
    )

internal interface MacosNowPlayingNativeFacade {
    fun objcClass(name: String): Pointer?

    fun allocateClassPair(
        superclass: Pointer,
        name: String,
    ): Pointer?

    fun registerClassPair(runtimeClass: Pointer)

    fun addMethod(
        runtimeClass: Pointer,
        selector: Pointer,
        implementation: Pointer,
        typeEncoding: String,
    ): Boolean

    fun selector(name: String): Pointer

    fun sendPointer(
        receiver: Pointer,
        selector: Pointer,
    ): Pointer?

    fun sendVoidPointer2(
        receiver: Pointer,
        selector: Pointer,
        first: Pointer,
        second: Pointer,
    )

    fun readDouble(
        receiver: Pointer,
        selectorName: String,
    ): Double

    fun setPreferredInterval(
        commandCenter: Pointer,
        commandSelector: String,
        seconds: Double,
    )

    fun publishNowPlayingInfo(
        title: String,
        artist: String?,
        durationMs: Long?,
        positionMs: Long,
        playbackRate: Double,
        onFailure: (Throwable) -> Unit,
    )

    fun clearNowPlayingInfo(onFailure: (Throwable) -> Unit)
}

/**
 * macOS-only bridge for MPNowPlayingInfoCenter and MPRemoteCommandCenter.
 * Construction is intentionally explicit and side-effectful only after the
 * MediaPlayer framework has been loaded; the JVM actual wraps construction in
 * runCatching so unsupported hosts remain a no-op.
 */
internal class MacosNowPlayingBridge(
    private val currentPositionMs: () -> Long,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val native: MacosNowPlayingNativeFacade = MacosNowPlayingNative.load(),
    private val failureReporter: MacosNowPlayingFailureReporter = DefaultMacosNowPlayingFailureReporter,
) : NowPlayingCommandTarget {
    @Volatile
    private var installed = false

    @Volatile
    private var hasActiveContent = false

    @Volatile
    private var latestStatus: PlaybackStatus? = null

    private var publishedSnapshot: NowPlayingSnapshot? = null

    fun install() {
        runCatching {
            MacosNowPlayingProcessDispatcher.install(this, native)
            installed = true
        }.onFailure { failure -> failureReporter.report(MacosNowPlayingFailureEvent.Install, failure) }
    }

    fun update(
        metadata: PlayerMediaMetadata?,
        state: PlaybackState,
    ) {
        if (!installed) return
        latestStatus = state.status
        val title = metadata?.title?.takeIf(String::isNotBlank)
        hasActiveContent = title != null
        val artist = metadata?.seriesName ?: metadata?.episodeLabel
        val next =
            title?.let {
                NowPlayingSnapshot(
                    title = it,
                    artist = artist,
                    durationMs = state.durationMs,
                    status = state.status,
                    positionMs = state.positionMs,
                    playbackSpeed = state.playbackSpeed,
                )
            }
        when (nowPlayingPublicationDecision(previous = publishedSnapshot, next = next)) {
            NowPlayingPublication.Clear -> {
                val hadPublishedSnapshot = publishedSnapshot != null
                publishedSnapshot = null
                if (hadPublishedSnapshot) {
                    clearNowPlayingInfo()
                }
                return
            }
            NowPlayingPublication.NoChange -> return
            NowPlayingPublication.PublishFull -> Unit
        }

        val snapshot = next ?: return
        publishedSnapshot = snapshot
        val rate = nowPlayingPlaybackRate(status = snapshot.status, playbackSpeed = snapshot.playbackSpeed)
        runCatching {
            native.publishNowPlayingInfo(
                title = snapshot.title,
                artist = snapshot.artist,
                durationMs = snapshot.durationMs,
                positionMs = snapshot.positionMs,
                playbackRate = rate,
                onFailure = { failure ->
                    failureReporter.report(MacosNowPlayingFailureEvent.Publish, failure)
                },
            )
        }.onFailure { failure -> failureReporter.report(MacosNowPlayingFailureEvent.Publish, failure) }
    }

    fun teardown() {
        if (!installed) return
        installed = false
        hasActiveContent = false
        latestStatus = null
        MacosNowPlayingProcessDispatcher.teardown(this)
        clearPublishedState()
        clearNowPlayingInfo()
    }

    override val isActive: Boolean
        get() = installed && hasActiveContent

    override fun handle(command: NowPlayingCommand) {
        if (!installed || !hasActiveContent) return
        runCatching {
            when (command) {
                NowPlayingCommand.Play -> onPlay()
                NowPlayingCommand.Pause -> onPause()
                NowPlayingCommand.Toggle ->
                    if (latestStatus == PlaybackStatus.Playing) onPause() else onPlay()
                is NowPlayingCommand.SkipForward ->
                    onSeekTo(offsetPosition(currentPositionMs(), command.intervalSeconds, direction = 1.0))
                is NowPlayingCommand.SkipBackward ->
                    onSeekTo(offsetPosition(currentPositionMs(), command.intervalSeconds, direction = -1.0))
                is NowPlayingCommand.ChangePosition ->
                    onSeekTo((command.positionSeconds * MS_PER_SECOND).toLong().coerceAtLeast(0L))
            }
        }
    }

    private fun clearPublishedState() {
        publishedSnapshot = null
    }

    private fun clearNowPlayingInfo() {
        runCatching {
            native.clearNowPlayingInfo { failure ->
                failureReporter.report(MacosNowPlayingFailureEvent.Clear, failure)
            }
        }.onFailure { failure -> failureReporter.report(MacosNowPlayingFailureEvent.Clear, failure) }
    }

    private fun offsetPosition(
        positionMs: Long,
        intervalSeconds: Double,
        direction: Double,
    ): Long =
        (positionMs.toDouble() + intervalSeconds * MS_PER_SECOND * direction)
            .coerceIn(0.0, Long.MAX_VALUE.toDouble())
            .toLong()
}

/**
 * Owns the Objective-C class, target, IMP callbacks, and command registrations
 * for the lifetime of the JVM process. Teardown only changes the current
 * bridge reference; it never removes a target whose class was registered once.
 */
private object MacosNowPlayingProcessDispatcher {
    private val lock = Any()
    private val commandDispatcher = NowPlayingCommandDispatcher()
    private var native: MacosNowPlayingNativeFacade? = null
    private var registeredClass: Pointer? = null
    private var commandTarget: Pointer? = null
    private var callbacks: CommandCallbacks? = null

    fun install(
        bridge: MacosNowPlayingBridge,
        bridgeNative: MacosNowPlayingNativeFacade,
    ) {
        synchronized(lock) {
            if (registeredClass == null) {
                registerOnce(bridgeNative)
            }
            check(native === bridgeNative) { "Now Playing native bridge changed after registration" }
            commandDispatcher.install(bridge)
        }
    }

    fun teardown(bridge: MacosNowPlayingBridge) {
        commandDispatcher.teardown(bridge)
    }

    private fun registerOnce(bridgeNative: MacosNowPlayingNativeFacade) {
        native = bridgeNative
        val existingClass = bridgeNative.objcClass(NOW_PLAYING_CLASS_NAME)
        check(existingClass == null) {
            "Now Playing runtime class is already owned by another registration"
        }
        val superclass = bridgeNative.objcClass("NSObject") ?: error("NSObject unavailable")
        val runtimeClass =
            bridgeNative.allocateClassPair(superclass, NOW_PLAYING_CLASS_NAME)
                ?: error("Unable to allocate Now Playing command target")
        val newCallbacks = CommandCallbacks()
        addCallback(bridgeNative, runtimeClass, "handlePlay:", newCallbacks.play)
        addCallback(bridgeNative, runtimeClass, "handlePause:", newCallbacks.pause)
        addCallback(bridgeNative, runtimeClass, "handleToggle:", newCallbacks.toggle)
        addCallback(bridgeNative, runtimeClass, "handleSkipForward:", newCallbacks.skipForward)
        addCallback(bridgeNative, runtimeClass, "handleSkipBackward:", newCallbacks.skipBackward)
        addCallback(bridgeNative, runtimeClass, "handleChangePosition:", newCallbacks.changePosition)
        bridgeNative.registerClassPair(runtimeClass)

        val allocatedTarget =
            bridgeNative.sendPointer(
                runtimeClass,
                bridgeNative.selector("alloc"),
            ) ?: error("Unable to create Now Playing command target")
        val target =
            bridgeNative.sendPointer(allocatedTarget, bridgeNative.selector("init"))
                ?: error("Unable to initialize Now Playing command target")
        val commandCenter =
            bridgeNative.sendPointer(
                bridgeNative.objcClass("MPRemoteCommandCenter")
                    ?: error("MPRemoteCommandCenter unavailable"),
                bridgeNative.selector("sharedCommandCenter"),
            ) ?: error("MPRemoteCommandCenter unavailable")
        listOf(
            "playCommand" to "handlePlay:",
            "pauseCommand" to "handlePause:",
            "togglePlayPauseCommand" to "handleToggle:",
            "skipForwardCommand" to "handleSkipForward:",
            "skipBackwardCommand" to "handleSkipBackward:",
            "changePlaybackPositionCommand" to "handleChangePosition:",
        ).forEach { (commandProperty, action) ->
            val command =
                bridgeNative.sendPointer(commandCenter, bridgeNative.selector(commandProperty))
                    ?: error("Remote command unavailable: $commandProperty")
            bridgeNative.sendVoidPointer2(
                command,
                bridgeNative.selector("addTarget:action:"),
                target,
                bridgeNative.selector(action),
            )
        }
        bridgeNative.setPreferredInterval(
            commandCenter = commandCenter,
            commandSelector = "skipForwardCommand",
            seconds = NOW_PLAYING_SKIP_INTERVAL_SECONDS,
        )
        bridgeNative.setPreferredInterval(
            commandCenter = commandCenter,
            commandSelector = "skipBackwardCommand",
            seconds = NOW_PLAYING_SKIP_INTERVAL_SECONDS,
        )
        callbacks = newCallbacks
        registeredClass = runtimeClass
        commandTarget = target
    }

    private fun addCallback(
        bridgeNative: MacosNowPlayingNativeFacade,
        runtimeClass: Pointer,
        selectorName: String,
        callback: RemoteCommandCallback,
    ) {
        check(
            bridgeNative.addMethod(
                runtimeClass,
                bridgeNative.selector(selectorName),
                CallbackReference.getFunctionPointer(callback),
                REMOTE_COMMAND_HANDLER_TYPES,
            ),
        ) { "Unable to register remote command callback: $selectorName" }
    }

    private class CommandCallbacks {
        val play = callback { dispatch(NowPlayingCommand.Play) }
        val pause = callback { dispatch(NowPlayingCommand.Pause) }
        val toggle = callback { dispatch(NowPlayingCommand.Toggle) }
        val skipForward =
            callback { event ->
                dispatch(
                    NowPlayingCommand.SkipForward(
                        intervalSeconds = eventInterval(event),
                    ),
                )
            }
        val skipBackward =
            callback { event ->
                dispatch(
                    NowPlayingCommand.SkipBackward(
                        intervalSeconds = eventInterval(event),
                    ),
                )
            }
        val changePosition =
            callback { event ->
                val eventPosition =
                    event?.let { eventPointer ->
                        MacosNowPlayingProcessDispatcher.native?.readDouble(eventPointer, "positionTime")
                    } ?: return@callback NowPlayingCommandResult.NoSuchContent
                dispatch(NowPlayingCommand.ChangePosition(eventPosition))
            }

        private fun eventInterval(event: Pointer?): Double {
            val interval =
                event?.let { eventPointer ->
                    MacosNowPlayingProcessDispatcher.native?.readDouble(eventPointer, "interval")
                }
            return interval?.takeIf { it.isFinite() && it > 0.0 }
                ?: NOW_PLAYING_SKIP_INTERVAL_SECONDS
        }

        private fun dispatch(command: NowPlayingCommand): NowPlayingCommandResult =
            runCatching { commandDispatcher.dispatch(command) }
                .getOrDefault(NowPlayingCommandResult.NoSuchContent)
    }

    private fun callback(action: (Pointer?) -> NowPlayingCommandResult): RemoteCommandCallback =
        RemoteCommandCallback { _, _, event ->
            runCatching { action(event).nativeValue }
                .getOrDefault(NowPlayingCommandResult.NoSuchContent.nativeValue)
        }

    private fun interface RemoteCommandCallback : Callback {
        fun invoke(
            target: Pointer,
            selector: Pointer,
            event: Pointer?,
        ): Long
    }
}

internal class MacosNowPlayingNative private constructor(
    @Suppress("UNUSED_PARAMETER") private val mediaPlayerFramework: NativeLibrary,
    private val system: LibSystem,
    private val mainQueue: Pointer,
    private val keys: NowPlayingFrameworkKeys,
) : MacosNowPlayingNativeFacade {
    private val selectors = ConcurrentHashMap<String, Pointer>()
    private val pendingMainQueueCallbacks = ConcurrentHashMap.newKeySet<DispatchWork>()

    override fun objcClass(name: String): Pointer? = MacObjectiveCRuntime.objcGetClass(name)

    override fun allocateClassPair(
        superclass: Pointer,
        name: String,
    ): Pointer? = MacObjectiveCRuntime.allocateClassPair(superclass, name)

    override fun registerClassPair(runtimeClass: Pointer) {
        MacObjectiveCRuntime.registerClassPair(runtimeClass)
    }

    override fun addMethod(
        runtimeClass: Pointer,
        selector: Pointer,
        implementation: Pointer,
        typeEncoding: String,
    ): Boolean = MacObjectiveCRuntime.classAddMethod(runtimeClass, selector, implementation, typeEncoding)

    override fun selector(name: String): Pointer = selectors.getOrPut(name) { MacObjectiveCRuntime.selector(name) }

    override fun sendPointer(
        receiver: Pointer,
        selector: Pointer,
    ): Pointer? = MacObjectiveCRuntime.sendPointerReturnNoArgs(receiver, selector)

    override fun sendVoidPointer2(
        receiver: Pointer,
        selector: Pointer,
        first: Pointer,
        second: Pointer,
    ) {
        MacObjectiveCRuntime.sendVoidReturnTwoPointers(receiver, selector, first, second)
    }

    override fun readDouble(
        receiver: Pointer,
        selectorName: String,
    ): Double = MacObjectiveCRuntime.sendDoubleReturnNoArgs(receiver, selector(selectorName))

    override fun setPreferredInterval(
        commandCenter: Pointer,
        commandSelector: String,
        seconds: Double,
    ) {
        val command = sendPointer(commandCenter, selector(commandSelector)) ?: return
        val number =
            MacObjectiveCRuntime.sendPointerReturnDouble(
                objcClass("NSNumber") ?: return,
                selector("numberWithDouble:"),
                seconds,
            ) ?: return
        val preferredIntervals =
            MacObjectiveCRuntime.sendPointerReturnOnePointer(
                objcClass("NSArray") ?: return,
                selector("arrayWithObject:"),
                number,
            ) ?: return
        MacObjectiveCRuntime.sendVoidReturnNullablePointer(command, selector("setPreferredIntervals:"), preferredIntervals)
    }

    override fun publishNowPlayingInfo(
        title: String,
        artist: String?,
        durationMs: Long?,
        positionMs: Long,
        playbackRate: Double,
        onFailure: (Throwable) -> Unit,
    ) {
        dispatchOnMain(onFailure) {
            val info = requireNotNull(buildNowPlayingInfo(title, artist, durationMs, positionMs, playbackRate))
            val center =
                sendPointer(
                    objcClass("MPNowPlayingInfoCenter") ?: error("MPNowPlayingInfoCenter unavailable"),
                    selector("defaultCenter"),
                ) ?: error("MPNowPlayingInfoCenter default center unavailable")
            MacObjectiveCRuntime.sendVoidReturnNullablePointer(center, selector("setNowPlayingInfo:"), info)
        }
    }

    override fun clearNowPlayingInfo(onFailure: (Throwable) -> Unit) {
        dispatchOnMain(onFailure) {
            val center =
                sendPointer(
                    objcClass("MPNowPlayingInfoCenter") ?: error("MPNowPlayingInfoCenter unavailable"),
                    selector("defaultCenter"),
                ) ?: error("MPNowPlayingInfoCenter default center unavailable")
            MacObjectiveCRuntime.sendVoidReturnNullablePointer(center, selector("setNowPlayingInfo:"), null)
        }
    }

    internal fun buildNowPlayingInfo(
        title: String,
        artist: String?,
        durationMs: Long?,
        positionMs: Long,
        playbackRate: Double,
    ): Pointer? {
        val values = mutableListOf<Pair<Pointer, Pointer>>()
        values += keys.title to stringValue(title)
        artist?.let { values += keys.artist to stringValue(it) }
        durationMs?.let { duration ->
            values += keys.duration to numberValue(duration.toDouble() / MS_PER_SECOND)
        }
        values += keys.elapsedTime to numberValue(positionMs.toDouble() / MS_PER_SECOND)
        values += keys.playbackRate to numberValue(playbackRate)
        val objects = Memory((Native.POINTER_SIZE * values.size).toLong())
        val keys = Memory((Native.POINTER_SIZE * values.size).toLong())
        values.forEachIndexed { index, (key, value) ->
            val offset = (index * Native.POINTER_SIZE).toLong()
            objects.setPointer(offset, value)
            keys.setPointer(offset, key)
        }
        return MacObjectiveCRuntime.sendPointerReturnTwoPointersAndUnsignedNativeLong(
            objcClass("NSDictionary") ?: return null,
            selector("dictionaryWithObjects:forKeys:count:"),
            objects,
            keys,
            NativeLong(values.size.toLong()),
        )
    }

    private fun stringValue(value: String): Pointer {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val cString = Memory((bytes.size + 1).toLong())
        cString.write(0L, bytes, 0, bytes.size)
        cString.setByte(bytes.size.toLong(), 0)
        return requireNotNull(
            MacObjectiveCRuntime.sendPointerReturnOnePointer(
                objcClass("NSString") ?: error("NSString unavailable"),
                selector("stringWithUTF8String:"),
                cString,
            ),
        )
    }

    private fun numberValue(value: Double): Pointer =
        requireNotNull(
            MacObjectiveCRuntime.sendPointerReturnDouble(
                objcClass("NSNumber") ?: error("NSNumber unavailable"),
                selector("numberWithDouble:"),
                value,
            ),
        )

    private fun dispatchOnMain(
        onFailure: (Throwable) -> Unit,
        action: () -> Unit,
    ) {
        lateinit var callback: DispatchWork
        callback =
            DispatchWork {
                pendingMainQueueCallbacks.remove(callback)
                runCatching(action).onFailure(onFailure)
            }
        pendingMainQueueCallbacks += callback
        runCatching {
            system.dispatch_async_f(mainQueue, Pointer.NULL, callback)
        }.onFailure { failure ->
            pendingMainQueueCallbacks.remove(callback)
            onFailure(failure)
        }
    }

    companion object {
        // One native surface per process: the command-target class, its IMPs,
        // and the framework handles are process-lifetime, so every bridge
        // instance must share the same native identity or the dispatcher's
        // registration invariant would reject reinstalls. A failed load is not
        // cached — Kotlin lazy re-runs the initializer on the next access.
        private val shared: MacosNowPlayingNative by lazy { create() }

        fun load(): MacosNowPlayingNative = shared

        private fun create(): MacosNowPlayingNative {
            // This must precede every objc_getClass call: MediaPlayer's ObjC
            // classes are not available until its framework is dlopen'd.
            val mediaPlayerFramework = NativeLibrary.getInstance(MEDIA_PLAYER_FRAMEWORK)
            val systemLibrary = NativeLibrary.getInstance("System")
            val system = Native.load("System", LibSystem::class.java)
            // dispatch_get_main_queue() is a header macro, NOT an exported
            // function (dlsym-verified): the main queue is the address of the
            // exported global `_dispatch_main_q`.
            val mainQueue =
                requireNotNull(systemLibrary.getGlobalVariableAddress("_dispatch_main_q")) {
                    "libdispatch main queue unavailable"
                }
            val keys =
                NowPlayingFrameworkKeys(
                    title = mediaPlayerFramework.frameworkStringConstant("MPMediaItemPropertyTitle"),
                    artist = mediaPlayerFramework.frameworkStringConstant("MPMediaItemPropertyArtist"),
                    duration = mediaPlayerFramework.frameworkStringConstant("MPMediaItemPropertyPlaybackDuration"),
                    elapsedTime =
                        mediaPlayerFramework.frameworkStringConstant(
                            "MPNowPlayingInfoPropertyElapsedPlaybackTime",
                        ),
                    playbackRate =
                        mediaPlayerFramework.frameworkStringConstant("MPNowPlayingInfoPropertyPlaybackRate"),
                )
            return MacosNowPlayingNative(mediaPlayerFramework, system, mainQueue, keys)
        }
    }

    @Suppress("FunctionName")
    private interface LibSystem : Library {
        fun dispatch_async_f(
            queue: Pointer,
            context: Pointer?,
            work: DispatchWork,
        )
    }

    private fun interface DispatchWork : Callback {
        fun invoke(context: Pointer?)
    }
}

private data class NowPlayingFrameworkKeys(
    val title: Pointer,
    val artist: Pointer,
    val duration: Pointer,
    val elapsedTime: Pointer,
    val playbackRate: Pointer,
)

private fun NativeLibrary.frameworkStringConstant(symbol: String): Pointer {
    val address = requireNotNull(getGlobalVariableAddress(symbol)) { "MediaPlayer key unavailable" }
    val value = address.getPointer(0L)
    check(value != null && Pointer.nativeValue(value) != 0L) { "MediaPlayer key unavailable" }
    return value
}
