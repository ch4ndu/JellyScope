// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.data.local.PictureInPictureStore
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.playback.IosPictureInPictureStartAdmission
import com.jellyscope.core.playback.IosPictureInPictureSurfaceCallbacks
import com.jellyscope.core.playback.IosPictureInPictureSurfaceProvider
import com.jellyscope.core.playback.IosPictureInPictureWindowState
import com.jellyscope.core.playback.IosPlaybackSurfaceProvider
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import org.koin.compose.koinInject
import platform.AVFoundation.AVLayerVideoGravity
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSTimer
import platform.Foundation.timeIntervalSinceReferenceDate
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObject

@Composable
actual fun PlayerSurface(
    controller: PlayerController,
    modifier: Modifier,
    resizeMode: PlayerResizeMode,
    // AVPlayer renders subtitles through its own item; subtitle style is applied
    // by the iOS PlayerController rather than this AVPlayerLayer surface.
    subtitleStyle: com.jellyscope.core.domain.playback.SubtitleStyle,
    subtitleClearanceActive: Boolean,
) {
    // iOS owns subtitle rendering in its player/controller; this desktop-only signal is inert.
    val pictureInPictureStore = koinInject<PictureInPictureStore>()
    val pictureInPictureEnabled by pictureInPictureStore.enabled.collectAsStateWithLifecycle()

    if (controller is IosPlaybackSurfaceProvider) {
        val surfaceProvider = controller
        val pictureInPictureProvider = controller as? IosPictureInPictureSurfaceProvider
        val callbacks = controller.pictureInPictureSurfaceCallbacks()
        UIKitView(
            factory = {
                surfaceProvider.createSurfaceView().also {
                    pictureInPictureProvider?.configurePictureInPicture(
                        enabled = pictureInPictureEnabled,
                        callbacks = callbacks,
                    )
                }
            },
            update = {
                pictureInPictureProvider?.configurePictureInPicture(
                    enabled = pictureInPictureEnabled,
                    callbacks = callbacks,
                )
            },
            onRelease = {
                pictureInPictureProvider?.clearPictureInPictureCallbacks()
            },
            modifier = modifier,
        )
        return
    }

    val videoGravity = resizeMode.toAVLayerVideoGravity()
    val runtimeDiagnostics by controller.runtimeDiagnostics.collectAsStateWithLifecycle()
    UIKitView(
        factory = {
            AVPlayerLayerView().apply {
                bind(
                    owner = controller,
                    player = controller.platformPlayer as? AVPlayer,
                    prepareEpoch = runtimeDiagnostics.prepareEpoch,
                    onVideoOutputReady = { epoch ->
                        controller.recordVideoOutputObservation(generation = epoch)
                    },
                )
                this.videoGravity = videoGravity
                this.pictureInPictureEnabled = pictureInPictureEnabled
            }
        },
        update = { view ->
            view.bind(
                owner = controller,
                player = controller.platformPlayer as? AVPlayer,
                prepareEpoch = runtimeDiagnostics.prepareEpoch,
                onVideoOutputReady = { epoch ->
                    controller.recordVideoOutputObservation(generation = epoch)
                },
            )
            view.videoGravity = videoGravity
            view.pictureInPictureEnabled = pictureInPictureEnabled
        },
        onRelease = { view -> view.release() },
        modifier = modifier,
    )
}

@Composable
internal actual fun PlayerPointerActivityRegistration(
    enabled: Boolean,
    onActivity: () -> Unit,
) = Unit

private class AVPlayerLayerView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val playerLayer = AVPlayerLayer()
    private val pictureInPictureDelegate = IosPictureInPictureDelegate()
    private var pictureInPictureController: AVPictureInPictureController? = null
    private var readinessTimer: NSTimer? = null
    private var expectedPrepareEpoch: Long? = null
    private var observedPrepareEpoch: Long? = null
    private var onVideoOutputReady: ((Long) -> Unit)? = null

    var player: AVPlayer?
        get() = playerLayer.player
        set(value) {
            playerLayer.player = value
            updatePictureInPictureController()
        }

    var videoGravity: AVLayerVideoGravity
        get() = playerLayer.videoGravity
        set(value) {
            playerLayer.videoGravity = value
        }

    var pictureInPictureEnabled: Boolean = false
        set(value) {
            field = value
            updatePictureInPictureController()
        }

    /**
     * The layer belongs to UIKit, while preparation belongs to the shared
     * controller. Pair the layer's positive ready-for-display fact with that
     * controller epoch so a stale layer from a replaced item cannot satisfy a
     * later health-session wait.
     */
    fun bind(
        owner: PlayerController,
        player: AVPlayer?,
        prepareEpoch: Long?,
        onVideoOutputReady: (Long) -> Unit,
    ) {
        val ownerChanged = pictureInPictureDelegate.owner !== owner
        val sourceIdentityChanged = expectedPrepareEpoch != prepareEpoch
        if (ownerChanged || sourceIdentityChanged) {
            clearPictureInPictureController()
        }
        pictureInPictureDelegate.owner = owner
        pictureInPictureDelegate.sourceIdentity = prepareEpoch
        val bindingChanged =
            playerLayer.player !== player || expectedPrepareEpoch != prepareEpoch
        this.onVideoOutputReady = onVideoOutputReady
        if (bindingChanged) {
            expectedPrepareEpoch = prepareEpoch
            observedPrepareEpoch = null
        }
        this.player = player
        notifyVideoOutputIfReady()
        updateReadinessPolling()
        updateAutomaticStartRegistration()
    }

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
        notifyVideoOutputIfReady()
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        updateReadinessPolling()
    }

    private fun updatePictureInPictureController() {
        if (playerLayer.player == null || !AVPictureInPictureController.isPictureInPictureSupported()) {
            clearPictureInPictureController()
            return
        }

        val controller =
            pictureInPictureController
                ?: runCatching {
                    AVPictureInPictureController(playerLayer = playerLayer).apply {
                        delegate = pictureInPictureDelegate
                    }
                }.onFailure { throwable ->
                    playerSurfaceLogger.w {
                        formatPlaybackDiagnostic(
                            PlaybackDiagnostic(
                                stage = PlaybackDiagnosticStage.PictureInPicture,
                                event = PlaybackDiagnosticEvent.Failed,
                                platform = PlaybackDiagnosticPlatform.Ios,
                                exceptionType = throwable.playbackExceptionType(),
                            ),
                        )
                    }
                }.getOrNull()
                ?: return

        pictureInPictureController = controller
        controller.canStartPictureInPictureAutomaticallyFromInline = pictureInPictureEnabled
        updateAutomaticStartRegistration()
    }

    private fun clearPictureInPictureController() {
        pictureInPictureDelegate.owner?.let { owner ->
            IosPictureInPictureCoordinator.clearAutomaticStartController(owner, this)
        }
        pictureInPictureController?.stopPictureInPicture()
        pictureInPictureController?.delegate = null
        pictureInPictureController = null
    }

    private fun updateReadinessPolling() {
        if (window == null || playerLayer.player == null || expectedPrepareEpoch == null) {
            readinessTimer?.invalidate()
            readinessTimer = null
            return
        }
        if (readinessTimer == null) {
            readinessTimer =
                NSTimer.scheduledTimerWithTimeInterval(
                    interval = IOS_VIDEO_OUTPUT_READY_POLL_INTERVAL_SECONDS,
                    repeats = true,
                ) {
                    notifyVideoOutputIfReady()
                }
        }
        notifyVideoOutputIfReady()
    }

    private fun notifyVideoOutputIfReady() {
        val generation = expectedPrepareEpoch ?: return
        if (observedPrepareEpoch == generation || !playerLayer.readyForDisplay) return
        observedPrepareEpoch = generation
        onVideoOutputReady?.invoke(generation)
        readinessTimer?.invalidate()
        readinessTimer = null
        updateAutomaticStartRegistration()
    }

    fun release() {
        readinessTimer?.invalidate()
        readinessTimer = null
        clearPictureInPictureController()
        playerLayer.player = null
        pictureInPictureDelegate.owner = null
        pictureInPictureDelegate.sourceIdentity = null
    }

    private fun updateAutomaticStartRegistration() {
        val owner = pictureInPictureDelegate.owner ?: return
        val sourceIdentity = expectedPrepareEpoch ?: return
        val controller = pictureInPictureController
        IosPictureInPictureCoordinator.updateAutomaticStartController(
            controller = owner,
            registrationOwner = this,
            sourceIdentity = sourceIdentity,
            eligible =
                pictureInPictureEnabled &&
                    controller != null &&
                    playerLayer.readyForDisplay,
            requestStop = { requestedIdentity ->
                if (requestedIdentity == expectedPrepareEpoch && controller === pictureInPictureController) {
                    controller?.stopPictureInPicture()
                }
            },
        )
    }
}

private const val IOS_VIDEO_OUTPUT_READY_POLL_INTERVAL_SECONDS = 0.1

private class IosPictureInPictureDelegate :
    NSObject(),
    AVPictureInPictureControllerDelegateProtocol {
    var owner: PlayerController? = null
    var sourceIdentity: Long? = null

    override fun pictureInPictureControllerDidStartPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        val callbackOwner = owner ?: return
        val callbackIdentity = sourceIdentity ?: return
        if (!IosPictureInPictureCoordinator.onPictureInPictureStarted(callbackOwner, callbackIdentity)) {
            pictureInPictureController.stopPictureInPicture()
        }
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError: NSError,
    ) {
        val callbackOwner = owner
        val callbackIdentity = sourceIdentity
        if (callbackOwner != null && callbackIdentity != null) {
            IosPictureInPictureCoordinator.onPictureInPictureStartFailed(callbackOwner, callbackIdentity)
        }
        playerSurfaceLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.PictureInPicture,
                    event = PlaybackDiagnosticEvent.Failed,
                    platform = PlaybackDiagnosticPlatform.Ios,
                    nativeCode = failedToStartPictureInPictureWithError.code,
                ),
            )
        }
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: (Boolean) -> Unit,
    ) {
        val callbackOwner = owner
        val callbackIdentity = sourceIdentity
        if (callbackOwner == null || callbackIdentity == null) {
            restoreUserInterfaceForPictureInPictureStopWithCompletionHandler(false)
        } else {
            IosPictureInPictureCoordinator.onRestoreUserInterface(
                owner = callbackOwner,
                sourceIdentity = callbackIdentity,
                completionHandler = restoreUserInterfaceForPictureInPictureStopWithCompletionHandler,
            )
        }
    }

    override fun pictureInPictureControllerDidStopPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        val callbackOwner = owner ?: return
        val callbackIdentity = sourceIdentity ?: return
        IosPictureInPictureCoordinator.onPictureInPictureStopped(callbackOwner, callbackIdentity)
    }
}

private fun PlayerResizeMode.toAVLayerVideoGravity(): AVLayerVideoGravity =
    when (this) {
        PlayerResizeMode.Fit -> AVLayerVideoGravityResizeAspect
        PlayerResizeMode.Fill -> AVLayerVideoGravityResizeAspectFill
        PlayerResizeMode.Zoom -> AVLayerVideoGravityResizeAspectFill
    }

internal data class IosPictureInPictureCallbacks(
    val controller: PlayerController,
    val onPictureInPictureModeChanged: (Boolean) -> Unit,
    val onCloseFromPictureInPicture: () -> Unit,
    val onBackgrounded: () -> Unit,
)

internal object IosPictureInPictureCoordinator {
    private var callbacks: IosPictureInPictureCallbacks? = null
    private val lifecycle = IosPictureInPictureLifecycle()
    private var automaticStartController: IosAutomaticPictureInPictureStartController? = null
    private var confirmationTimer: NSTimer? = null

    fun updateCallbacks(callbacks: IosPictureInPictureCallbacks) {
        if (this.callbacks?.controller !== callbacks.controller) {
            resetLifecycle()
            automaticStartController = null
        }
        this.callbacks = callbacks
    }

    fun clearCallbacks(controller: PlayerController) {
        if (callbacks?.controller === controller) {
            callbacks = null
            automaticStartController = null
            resetLifecycle()
        }
    }

    fun updateAutomaticStartController(
        controller: PlayerController,
        registrationOwner: Any,
        sourceIdentity: Long,
        eligible: Boolean,
        requestStop: (Long) -> Unit,
    ) {
        val previous = automaticStartController
        if (
            previous != null &&
            (
                previous.controller !== controller ||
                    previous.registrationOwner !== registrationOwner ||
                    previous.sourceIdentity != sourceIdentity
            )
        ) {
            previous.requestStop(previous.sourceIdentity)
            resetLifecycle()
            callbacks
                ?.takeIf { current -> current.controller === previous.controller }
                ?.onPictureInPictureModeChanged
                ?.invoke(false)
        }
        automaticStartController =
            IosAutomaticPictureInPictureStartController(
                controller = controller,
                registrationOwner = registrationOwner,
                sourceIdentity = sourceIdentity,
                eligible = eligible,
                requestStop = requestStop,
            )
        if (!eligible && lifecycle.sourceIdentity == sourceIdentity) {
            requestStop(sourceIdentity)
            resetLifecycle()
            callbacks
                ?.takeIf { current -> current.controller === controller }
                ?.onPictureInPictureModeChanged
                ?.invoke(false)
        }
    }

    fun clearAutomaticStartController(
        controller: PlayerController,
        registrationOwner: Any,
    ) {
        val current = automaticStartController ?: return
        if (current.controller !== controller || current.registrationOwner !== registrationOwner) return
        current.requestStop(current.sourceIdentity)
        automaticStartController = null
        if (lifecycle.sourceIdentity == current.sourceIdentity) {
            resetLifecycle()
            callbacks
                ?.takeIf { active -> active.controller === controller }
                ?.onPictureInPictureModeChanged
                ?.invoke(false)
        }
    }

    fun onWillResignActive(controller: PlayerController) {
        val currentCallbacks = callbacks?.takeIf { current -> current.controller === controller } ?: return
        if (!lifecycle.canAdmitStart()) return
        val admission =
            when (controller) {
                is IosPictureInPictureSurfaceProvider -> controller.requestPictureInPictureStart()
                else ->
                    automaticStartController
                        ?.takeIf { startController ->
                            startController.controller === controller && startController.eligible
                        }?.let { startController ->
                            IosPictureInPictureStartAdmission.AutomaticStartPending(startController.sourceIdentity)
                        } ?: IosPictureInPictureStartAdmission.Rejected
            }
        if (!lifecycle.beginStart(admission, pictureInPictureNowMs())) {
            (admission as? IosPictureInPictureStartAdmission.ExplicitStartRequested)
                ?.let { rejected ->
                    (controller as? IosPictureInPictureSurfaceProvider)
                        ?.requestPictureInPictureStop(rejected.sourceIdentity)
                }
            currentCallbacks.onPictureInPictureModeChanged(false)
        }
    }

    fun onDidEnterBackground(controller: PlayerController): Boolean {
        val currentCallbacks = callbacks?.takeIf { current -> current.controller === controller } ?: return true
        return when (val result = lifecycle.onDidEnterBackground()) {
            IosPictureInPictureBackgroundResult.KeepOpen -> false
            is IosPictureInPictureBackgroundResult.AwaitConfirmation -> {
                scheduleConfirmationDeadline(controller, lifecycle.sourceIdentity, result.deadlineAtMs)
                false
            }
            IosPictureInPictureBackgroundResult.Close -> {
                cancelConfirmationDeadline()
                currentCallbacks.onPictureInPictureModeChanged(false)
                true
            }
        }
    }

    fun onDidBecomeActive(controller: PlayerController) {
        val currentCallbacks = callbacks?.takeIf { current -> current.controller === controller } ?: return
        when (val result = lifecycle.onDidBecomeActive(pictureInPictureNowMs())) {
            IosPictureInPictureForegroundResult.None -> Unit
            is IosPictureInPictureForegroundResult.RequestStopAndRestore -> {
                cancelConfirmationDeadline()
                requestPictureInPictureStop(controller, result.sourceIdentity)
                scheduleConfirmationDeadline(controller, result.sourceIdentity, result.deadlineAtMs)
                currentCallbacks.onPictureInPictureModeChanged(false)
            }
            IosPictureInPictureForegroundResult.Restored -> {
                cancelConfirmationDeadline()
                currentCallbacks.onPictureInPictureModeChanged(false)
            }
        }
    }

    fun onPictureInPictureStarted(
        controller: PlayerController,
        sourceIdentity: Long,
    ): Boolean {
        val currentCallbacks = callbacks?.takeIf { current -> current.controller === controller } ?: return false
        return when (lifecycle.onPictureInPictureStarted(sourceIdentity, pictureInPictureNowMs())) {
            IosPictureInPictureStartResult.Started -> {
                cancelConfirmationDeadline()
                currentCallbacks.onPictureInPictureModeChanged(true)
                true
            }
            IosPictureInPictureStartResult.AlreadyActive -> true
            IosPictureInPictureStartResult.RequestStop -> {
                requestPictureInPictureStop(controller, sourceIdentity)
                scheduleConfirmationDeadline(
                    controller = controller,
                    sourceIdentity = sourceIdentity,
                    deadlineAtMs = pictureInPictureNowMs() + IOS_PIP_START_CONFIRMATION_TIMEOUT_MS,
                )
                currentCallbacks.onPictureInPictureModeChanged(false)
                true
            }
            IosPictureInPictureStartResult.Ignored -> {
                val acceptsForegroundAvKitStart =
                    controller !is IosPictureInPictureSurfaceProvider &&
                        UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground &&
                        automaticStartController
                            ?.takeIf { current ->
                                current.controller === controller &&
                                    current.sourceIdentity == sourceIdentity &&
                                    current.eligible
                            } != null &&
                        lifecycle.acceptForegroundNativeStart(sourceIdentity)
                if (acceptsForegroundAvKitStart) {
                    currentCallbacks.onPictureInPictureModeChanged(true)
                }
                acceptsForegroundAvKitStart
            }
        }
    }

    fun onPictureInPictureStartFailed(
        controller: PlayerController,
        sourceIdentity: Long,
    ) {
        val currentCallbacks = callbacks?.takeIf { current -> current.controller === controller } ?: return
        settleStopResult(
            currentCallbacks = currentCallbacks,
            controller = controller,
            sourceIdentity = sourceIdentity,
            result = lifecycle.onPictureInPictureStartFailed(sourceIdentity),
        )
    }

    fun onRestoreUserInterface(
        owner: PlayerController,
        sourceIdentity: Long,
        completionHandler: (Boolean) -> Unit,
    ) {
        val currentCallbacks = callbacks?.takeIf { current -> current.controller === owner }
        if (currentCallbacks == null) {
            completionHandler(false)
            return
        }
        val deadlineAtMs =
            lifecycle.onRestoreUserInterface(
                sourceIdentity = sourceIdentity,
                observedAtMs = pictureInPictureNowMs(),
                isAppBackgrounded =
                    UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground,
            )
        if (deadlineAtMs == null) {
            completionHandler(false)
            return
        }
        cancelConfirmationDeadline()
        scheduleConfirmationDeadline(owner, sourceIdentity, deadlineAtMs)
        currentCallbacks.onPictureInPictureModeChanged(false)
        completionHandler(true)
    }

    fun onPictureInPictureStopped(
        controller: PlayerController,
        sourceIdentity: Long,
    ) {
        val currentCallbacks = callbacks?.takeIf { current -> current.controller === controller } ?: return
        settleStopResult(
            currentCallbacks = currentCallbacks,
            controller = controller,
            sourceIdentity = sourceIdentity,
            result = lifecycle.onPictureInPictureStopped(sourceIdentity, pictureInPictureNowMs()),
        )
    }

    fun onSourceInvalidated(
        controller: PlayerController,
        sourceIdentity: Long,
    ) {
        val currentCallbacks = callbacks?.takeIf { current -> current.controller === controller } ?: return
        val isAppBackgrounded =
            UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground
        when (lifecycle.onSourceInvalidated(sourceIdentity, isAppBackgrounded)) {
            IosPictureInPictureSourceInvalidationResult.Ignored -> Unit
            IosPictureInPictureSourceInvalidationResult.Reset -> {
                cancelConfirmationDeadline()
                currentCallbacks.onPictureInPictureModeChanged(false)
            }
            IosPictureInPictureSourceInvalidationResult.Closed -> {
                cancelConfirmationDeadline()
                currentCallbacks.onPictureInPictureModeChanged(false)
                currentCallbacks.onCloseFromPictureInPicture()
            }
        }
    }

    private fun requestPictureInPictureStop(
        controller: PlayerController,
        sourceIdentity: Long,
    ) {
        when (controller) {
            is IosPictureInPictureSurfaceProvider -> controller.requestPictureInPictureStop(sourceIdentity)
            else ->
                automaticStartController
                    ?.takeIf { current ->
                        current.controller === controller && current.sourceIdentity == sourceIdentity
                    }?.requestStop
                    ?.invoke(sourceIdentity)
        }
    }

    private fun settleStopResult(
        currentCallbacks: IosPictureInPictureCallbacks,
        controller: PlayerController,
        sourceIdentity: Long,
        result: IosPictureInPictureStopResult,
    ) {
        when (result) {
            IosPictureInPictureStopResult.Inactive -> Unit
            IosPictureInPictureStopResult.Reset -> {
                cancelConfirmationDeadline()
                currentCallbacks.onPictureInPictureModeChanged(false)
            }
            IosPictureInPictureStopResult.Restored -> {
                cancelConfirmationDeadline()
                currentCallbacks.onPictureInPictureModeChanged(false)
            }
            IosPictureInPictureStopResult.Closed -> {
                cancelConfirmationDeadline()
                currentCallbacks.onPictureInPictureModeChanged(false)
                currentCallbacks.onCloseFromPictureInPicture()
            }
            is IosPictureInPictureStopResult.Pending -> {
                currentCallbacks.onPictureInPictureModeChanged(false)
                scheduleConfirmationDeadline(controller, sourceIdentity, result.deadlineAtMs)
            }
        }
    }

    private fun scheduleConfirmationDeadline(
        controller: PlayerController,
        sourceIdentity: Long?,
        deadlineAtMs: Long,
    ) {
        cancelConfirmationDeadline()
        val identity = sourceIdentity ?: return
        val remainingMs = (deadlineAtMs - pictureInPictureNowMs()).coerceAtLeast(0L)
        confirmationTimer =
            NSTimer.scheduledTimerWithTimeInterval(
                interval = remainingMs / 1_000.0,
                repeats = false,
            ) {
                confirmationTimer = null
                val currentCallbacks =
                    callbacks
                        ?.takeIf { current ->
                            current.controller === controller
                        }
                        ?: return@scheduledTimerWithTimeInterval
                when (lifecycle.onConfirmationDeadline(identity, pictureInPictureNowMs())) {
                    IosPictureInPictureDeadlineResult.None -> Unit
                    IosPictureInPictureDeadlineResult.Restored ->
                        currentCallbacks.onPictureInPictureModeChanged(false)
                    IosPictureInPictureDeadlineResult.Closed -> {
                        currentCallbacks.onPictureInPictureModeChanged(false)
                        currentCallbacks.onBackgrounded()
                    }
                }
            }
    }

    private fun cancelConfirmationDeadline() {
        confirmationTimer?.invalidate()
        confirmationTimer = null
    }

    private fun resetLifecycle() {
        cancelConfirmationDeadline()
        lifecycle.reset()
    }
}

private fun PlayerController.pictureInPictureSurfaceCallbacks(): IosPictureInPictureSurfaceCallbacks =
    IosPictureInPictureSurfaceCallbacks(
        onWindowStateChanged = { sourceIdentity, state ->
            when (state) {
                IosPictureInPictureWindowState.Started ->
                    if (!IosPictureInPictureCoordinator.onPictureInPictureStarted(this, sourceIdentity)) {
                        (this as? IosPictureInPictureSurfaceProvider)?.requestPictureInPictureStop(sourceIdentity)
                    }
                IosPictureInPictureWindowState.Stopped ->
                    IosPictureInPictureCoordinator.onPictureInPictureStopped(this, sourceIdentity)
            }
        },
        onSourceInvalidated = { sourceIdentity ->
            IosPictureInPictureCoordinator.onSourceInvalidated(this, sourceIdentity)
        },
    )

private data class IosAutomaticPictureInPictureStartController(
    val controller: PlayerController,
    val registrationOwner: Any,
    val sourceIdentity: Long,
    val eligible: Boolean,
    val requestStop: (Long) -> Unit,
)

private fun pictureInPictureNowMs(): Long = (platform.Foundation.NSDate.timeIntervalSinceReferenceDate * 1_000.0).toLong().coerceAtLeast(0L)

private val playerSurfaceLogger = diagnosticLogger(DiagnosticTag.IosPlayerSurface)
