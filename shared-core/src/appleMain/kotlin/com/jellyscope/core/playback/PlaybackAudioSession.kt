// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

/**
 * Interruption callbacks for the controller that most recently activated the
 * playback audio session. Invoked on the main queue.
 */
internal interface PlaybackAudioSessionHandler {
    /** A system interruption (call, Siri, alarm) started: pause and remember intent. */
    fun onAudioSessionInterruptionBegan()

    /** The interruption ended; the session has been reactivated before this call. */
    fun onAudioSessionInterruptionEnded(shouldResume: Boolean)
}

/**
 * App-global owner of the Apple playback [AVAudioSession] lifecycle, shared by
 * [AppleAVPlayerController] and the iOS VLCKit controller.
 *
 * All session mutations run on a single-parallelism dispatcher so a stale
 * delayed deactivation can never execute after a newer activation: `activate`
 * cancels the pending deactivate before enqueueing its own work, and because
 * both bodies execute on the same serialized queue, `setActive(true)` always
 * lands after any deactivate body that had already passed its delay.
 *
 * Deactivation is ownership-gated: only the controller whose `activate` most
 * recently ran may schedule it, so the eager AVPlayer controller released by a
 * backend swap before it ever prepared cannot deactivate a session it never
 * activated. The short delay covers dispose-to-reopen gaps without flapping
 * other apps' audio (`NotifyOthersOnDeactivation` fires on the real close).
 */
internal object PlaybackAudioSession {
    private val sessionDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + sessionDispatcher)
    private val logger = diagnosticLogger(DiagnosticTag.PlaybackAudioSession)

    @Volatile
    private var currentHandler: PlaybackAudioSessionHandler? = null

    @Volatile
    private var activationOwner: PlaybackAudioSessionHandler? = null

    @Volatile
    private var diagnosticPlatform: PlaybackDiagnosticPlatform = PlaybackDiagnosticPlatform.Ios

    private var deactivateJob: Job? = null
    private var interruptionObserver: Any? = null

    /**
     * Activate the playback session for [owner] and make it the interruption
     * handler. Idempotent; called from `prepare()` on the main thread. The
     * synchronous cross-process `setActive(true)` runs off-main because the
     * first activation after launch can block for 100s of ms.
     */
    fun activate(
        owner: PlaybackAudioSessionHandler,
        platform: PlaybackDiagnosticPlatform,
    ) {
        installInterruptionObserverIfNeeded()
        currentHandler = owner
        activationOwner = owner
        diagnosticPlatform = platform
        deactivateJob?.cancel()
        deactivateJob = null
        scope.launch { activateSession() }
    }

    /**
     * Called from a controller's `release()`. Unregisters [owner] as the
     * interruption handler and, only when [owner] owns the current activation,
     * schedules the delayed, notify-others deactivation.
     */
    fun release(owner: PlaybackAudioSessionHandler) {
        if (currentHandler === owner) {
            currentHandler = null
        }
        if (activationOwner !== owner) return
        activationOwner = null
        deactivateJob?.cancel()
        deactivateJob =
            scope.launch {
                delay(DEACTIVATE_DELAY_MS)
                runCatching {
                    AVAudioSession.sharedInstance().setActive(
                        active = false,
                        withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                        error = null,
                    )
                }.onFailure { throwable ->
                    logFailure(throwable)
                }
            }
    }

    private fun activateSession() {
        runCatching {
            val audioSession = AVAudioSession.sharedInstance()
            val categorySet = audioSession.setCategory(AVAudioSessionCategoryPlayback, error = null)
            val modeSet = audioSession.setMode(AVAudioSessionModeMoviePlayback, error = null)
            val activeSet = audioSession.setActive(active = true, error = null)
            if (!categorySet || !modeSet || !activeSet) {
                logFailure(throwable = null)
            }
        }.onFailure { throwable ->
            logFailure(throwable)
        }
    }

    private fun installInterruptionObserverIfNeeded() {
        if (interruptionObserver != null) return
        interruptionObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVAudioSessionInterruptionNotification,
                `object` = null,
                queue = null,
            ) { notification ->
                val userInfo = notification?.userInfo ?: return@addObserverForName
                val type = (userInfo[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue
                when (type) {
                    AVAudioSessionInterruptionTypeBegan -> {
                        logEvent(PlaybackDiagnosticEvent.Waiting)
                        dispatchToHandler { handler -> handler.onAudioSessionInterruptionBegan() }
                    }

                    AVAudioSessionInterruptionTypeEnded -> {
                        // No registered handler means playback was released during
                        // the interruption: do NOT reactivate a session nobody
                        // owns (it would undo the delayed deactivation and steal
                        // audio focus with nothing playing).
                        if (currentHandler == null) return@addObserverForName
                        val options =
                            (userInfo[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0uL
                        val shouldResume =
                            options and AVAudioSessionInterruptionOptionShouldResume ==
                                AVAudioSessionInterruptionOptionShouldResume
                        logEvent(PlaybackDiagnosticEvent.Resolved)
                        // Reactivate on the serialized session queue BEFORE the
                        // handler decides whether to resume playback; re-check the
                        // handler there in case release raced the dispatch.
                        scope.launch {
                            if (currentHandler == null) return@launch
                            activateSession()
                            dispatchToHandler { handler ->
                                handler.onAudioSessionInterruptionEnded(shouldResume)
                            }
                        }
                    }
                }
            }
    }

    private inline fun dispatchToHandler(crossinline block: (PlaybackAudioSessionHandler) -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            currentHandler?.let(block)
        }
    }

    private fun logEvent(event: PlaybackDiagnosticEvent) {
        logger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.AudioSession,
                    event = event,
                    platform = diagnosticPlatform,
                ),
            )
        }
    }

    private fun logFailure(throwable: Throwable?) {
        logger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.AudioSession,
                    event = PlaybackDiagnosticEvent.Failed,
                    platform = diagnosticPlatform,
                    exceptionType = throwable?.playbackExceptionType(),
                ),
            )
        }
    }

    private const val DEACTIVATE_DELAY_MS = 500L
}
