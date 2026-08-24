// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.android

import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaSession
import com.jellyscope.core.data.local.PictureInPictureStore
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.ui.screen.player.AndroidActivePlayer
import com.jellyscope.ui.screen.player.AndroidActivePlayerRegistry
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get

fun ComponentActivity.installMobilePlayerPlatformOwner() {
    val owner =
        MobilePlayerPlatformOwner(
            activity = this,
            pictureInPictureStore = get(),
        )
    lifecycle.addObserver(owner)
    owner.install()
}

private class MobilePlayerPlatformOwner(
    private val activity: ComponentActivity,
    private val pictureInPictureStore: PictureInPictureStore,
) : DefaultLifecycleObserver {
    private var activePlayer: AndroidActivePlayer? = null
    private var mediaSession: MediaSession? = null
    private var mediaSessionPlayer: BackendMediaSessionPlayer? = null
    private var receiverRegistered = false
    private var wasInPictureInPicture = false
    private val pictureInPictureAttemptGate = AndroidPictureInPictureAttemptGate()

    private val userLeaveHintListener =
        Runnable {
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
                enterPictureInPictureIfEligible()
            }
        }

    private val pictureInPictureModeChangedListener =
        Consumer<PictureInPictureModeChangedInfo> { info ->
            wasInPictureInPicture = info.isInPictureInPictureMode
            AndroidActivePlayerRegistry.setPictureInPictureMode(info.isInPictureInPictureMode)
        }

    private val pictureInPictureActionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val active = activePlayer ?: return
                when (intent.action) {
                    ACTION_PIP_PLAY_PAUSE -> {
                        when (androidPictureInPictureTransportAction(active.playbackState.status)) {
                            AndroidPictureInPictureTransportAction.Pause -> active.commandCallbacks.pause()
                            AndroidPictureInPictureTransportAction.Play -> active.commandCallbacks.play()
                        }
                    }
                    ACTION_PIP_REWIND -> seekBy(-PIP_SEEK_MS)
                    ACTION_PIP_FORWARD -> seekBy(PIP_SEEK_MS)
                }
            }
        }

    fun install() {
        activity.addOnUserLeaveHintListener(userLeaveHintListener)
        activity.addOnPictureInPictureModeChangedListener(pictureInPictureModeChangedListener)
        activity.lifecycleScope.launch {
            AndroidActivePlayerRegistry.activePlayer.collect { active ->
                updateActivePlayer(active)
            }
        }
        activity.lifecycleScope.launch {
            pictureInPictureStore.enabled.collect {
                activePlayer?.let { active -> updatePictureInPictureParams(active) }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (wasInPictureInPicture && !activity.isChangingConfigurations) {
            activePlayer?.onCloseFromPictureInPicture?.invoke()
            wasInPictureInPicture = false
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        activity.removeOnUserLeaveHintListener(userLeaveHintListener)
        activity.removeOnPictureInPictureModeChangedListener(pictureInPictureModeChangedListener)
        unregisterPictureInPictureReceiver()
        releaseMediaSession()
    }

    private fun updateActivePlayer(active: AndroidActivePlayer?) {
        // Capture the previous player BEFORE reassigning so ensureMediaSession can
        // detect a real controller change (the prior guard compared active against
        // itself and never rebuilt the session on a backend swap).
        val previous = activePlayer
        activePlayer = active
        if (active == null) {
            releaseMediaSession()
            // Playback armed auto-enter PiP (API S+). Once the player is gone
            // (e.g. the user pressed back), disarm it — otherwise backgrounding
            // the app later drops the whole UI into a PiP frame with no video.
            disablePictureInPictureAutoEnter()
            return
        }

        if (previous == null ||
            previous.controller !== active.controller ||
            previous.playbackItemId != active.playbackItemId
        ) {
            pictureInPictureAttemptGate.reset()
        }

        // ensureMediaSession's reuse branch already pushes a fresh state update
        // through the adapter, so no separate metadata-update path is needed.
        ensureMediaSession(active, previous)
        updatePictureInPictureParams(active)
    }

    private fun ensureMediaSession(
        active: AndroidActivePlayer,
        previous: AndroidActivePlayer?,
    ) {
        if (mediaSessionPlayer != null && previous?.controller === active.controller) {
            mediaSessionPlayer?.update(active)
            return
        }
        releaseMediaSession()
        val adapter = BackendMediaSessionPlayer(active)
        mediaSession =
            MediaSession
                .Builder(activity, adapter)
                // Tapping the system media notification returns to the player.
                .setSessionActivity(
                    PendingIntent.getActivity(
                        activity,
                        0,
                        Intent(activity, activity.javaClass),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build()
        mediaSessionPlayer = adapter
    }

    private fun releaseMediaSession() {
        val adapter = mediaSessionPlayer
        adapter?.update(null)
        mediaSession?.release()
        adapter?.release()
        mediaSession = null
        mediaSessionPlayer = null
    }

    private fun enterPictureInPictureIfEligible() {
        if (!pictureInPictureStore.enabled.value) {
            return
        }
        val active = activePlayer ?: return
        if (!isPictureInPictureSupported() || !active.playbackState.status.isPictureInPictureEligible()) {
            return
        }
        val params = buildPictureInPictureParams(active) ?: return
        try {
            if (!activity.enterPictureInPictureMode(params)) {
                pictureInPictureLogger.w { ANDROID_PICTURE_IN_PICTURE_ENTER_REJECTED_DIAGNOSTIC }
            }
        } catch (failure: Throwable) {
            pictureInPictureLogger.w {
                androidPictureInPictureFailureDiagnostic(
                    event = AndroidPictureInPictureFailureEvent.Enter,
                    throwable = failure,
                )
            }
        }
    }

    private fun updatePictureInPictureParams(active: AndroidActivePlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isPictureInPictureSupported()) {
            return
        }
        val sourceRect = active.sourceRect ?: activity.visibleWindowRect()
        val isPlaying = active.playbackState.status.isPictureInPicturePlaying()
        val signature =
            androidPictureInPictureSignature(
                aspect = androidPictureInPictureAspect(active.videoWidth, active.videoHeight),
                isPlaying = isPlaying,
                autoEnterRequested = pictureInPictureStore.enabled.value,
                autoEnterSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            )
        val update =
            pictureInPictureAttemptGate.nextUpdate(
                signature = signature,
                sourceHint = sourceRect.toPictureInPictureSourceHint(),
            )
        val params =
            when (update) {
                AndroidPictureInPictureUpdate.Full ->
                    buildPictureInPictureParams(
                        active = active,
                        signature = signature,
                        sourceRect = sourceRect,
                    )
                AndroidPictureInPictureUpdate.SourceHintOnly ->
                    // Android merges explicitly set fields, so this moving
                    // geometry cannot resend or replace the stable aspect.
                    PictureInPictureParams
                        .Builder()
                        .setSourceRectHint(sourceRect)
                        .build()
                AndroidPictureInPictureUpdate.None -> return
            }

        try {
            activity.setPictureInPictureParams(params)
        } catch (failure: Throwable) {
            pictureInPictureLogger.w {
                androidPictureInPictureFailureDiagnostic(
                    event = AndroidPictureInPictureFailureEvent.SetParams,
                    throwable = failure,
                )
            }
        }
    }

    // Clears any armed auto-enter so the app can't fall into PiP once no player
    // is active. Only API S+ has auto-enter; older levels enter PiP manually via
    // the user-leave hint, which already checks for an eligible active player.
    private fun disablePictureInPictureAutoEnter() {
        pictureInPictureAttemptGate.reset()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isPictureInPictureSupported()) {
            return
        }
        try {
            activity.setPictureInPictureParams(
                PictureInPictureParams
                    .Builder()
                    .setAutoEnterEnabled(false)
                    .build(),
            )
        } catch (failure: Throwable) {
            pictureInPictureLogger.w {
                androidPictureInPictureFailureDiagnostic(
                    event = AndroidPictureInPictureFailureEvent.DisableAutoEnter,
                    throwable = failure,
                )
            }
        }
    }

    private fun buildPictureInPictureParams(active: AndroidActivePlayer): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null
        }

        val isPlaying = active.playbackState.status.isPictureInPicturePlaying()
        val signature =
            androidPictureInPictureSignature(
                aspect = androidPictureInPictureAspect(active.videoWidth, active.videoHeight),
                isPlaying = isPlaying,
                autoEnterRequested = pictureInPictureStore.enabled.value,
                autoEnterSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            )
        return buildPictureInPictureParams(
            active = active,
            signature = signature,
            sourceRect = active.sourceRect ?: activity.visibleWindowRect(),
        )
    }

    private fun buildPictureInPictureParams(
        active: AndroidActivePlayer,
        signature: AndroidPictureInPictureSignature,
        sourceRect: Rect,
    ): PictureInPictureParams {
        ensurePictureInPictureReceiver()
        val builder =
            PictureInPictureParams
                .Builder()
                .setAspectRatio(Rational(signature.aspect.numerator, signature.aspect.denominator))
                .setSourceRectHint(sourceRect)
                .setActions(buildPictureInPictureActions(active))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(signature.autoEnterEnabled)
        }
        return builder.build()
    }

    private fun buildPictureInPictureActions(active: AndroidActivePlayer): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return emptyList()
        }

        val isPlaying = active.playbackState.status.isPictureInPicturePlaying()
        val playPauseTitle =
            activity.getString(
                if (isPlaying) {
                    R.string.pip_pause
                } else {
                    R.string.pip_play
                },
            )
        return listOf(
            RemoteAction(
                Icon.createWithResource(activity, android.R.drawable.ic_media_rew),
                activity.getString(R.string.pip_rewind),
                activity.getString(R.string.pip_rewind),
                pictureInPicturePendingIntent(ACTION_PIP_REWIND, REQUEST_PIP_REWIND),
            ),
            RemoteAction(
                Icon.createWithResource(
                    activity,
                    if (isPlaying) {
                        android.R.drawable.ic_media_pause
                    } else {
                        android.R.drawable.ic_media_play
                    },
                ),
                playPauseTitle,
                playPauseTitle,
                pictureInPicturePendingIntent(ACTION_PIP_PLAY_PAUSE, REQUEST_PIP_PLAY_PAUSE),
            ),
            RemoteAction(
                Icon.createWithResource(activity, android.R.drawable.ic_media_ff),
                activity.getString(R.string.pip_forward),
                activity.getString(R.string.pip_forward),
                pictureInPicturePendingIntent(ACTION_PIP_FORWARD, REQUEST_PIP_FORWARD),
            ),
        )
    }

    private fun pictureInPicturePendingIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            activity,
            requestCode,
            Intent(action).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensurePictureInPictureReceiver() {
        if (receiverRegistered) {
            return
        }
        val filter =
            IntentFilter().apply {
                addAction(ACTION_PIP_PLAY_PAUSE)
                addAction(ACTION_PIP_REWIND)
                addAction(ACTION_PIP_FORWARD)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(pictureInPictureActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(pictureInPictureActionReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterPictureInPictureReceiver() {
        if (!receiverRegistered) {
            return
        }
        activity.unregisterReceiver(pictureInPictureActionReceiver)
        receiverRegistered = false
    }

    private fun seekBy(deltaMs: Long) {
        val active = activePlayer ?: return
        val playbackState = active.playbackState
        val target =
            (playbackState.positionMs + deltaMs)
                .coerceAtLeast(0L)
                .let { position ->
                    playbackState.durationMs?.let { duration -> position.coerceAtMost(duration) } ?: position
                }
        active.commandCallbacks.seekTo(target)
    }

    private fun isPictureInPictureSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return false
        }
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        val mode =
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                Process.myUid(),
                activity.packageName,
            )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

private fun PlaybackStatus.isPictureInPictureEligible(): Boolean = this == PlaybackStatus.Playing || this == PlaybackStatus.Buffering

private fun PlaybackStatus.isPictureInPicturePlaying(): Boolean =
    androidPictureInPictureTransportAction(this) == AndroidPictureInPictureTransportAction.Pause

private fun ComponentActivity.visibleWindowRect(): Rect =
    Rect().also { rect ->
        window.decorView.getGlobalVisibleRect(rect)
        if (rect.width() <= 0 || rect.height() <= 0) {
            rect.set(0, 0, DEFAULT_PIP_WIDTH, DEFAULT_PIP_HEIGHT)
        }
    }

private fun Rect.toPictureInPictureSourceHint(): AndroidPictureInPictureSourceHint =
    AndroidPictureInPictureSourceHint(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

private val pictureInPictureLogger = diagnosticLogger(DiagnosticTag.AndroidPlaybackModule)

private const val ACTION_PIP_PLAY_PAUSE = "com.jellyscope.android.PIP_PLAY_PAUSE"
private const val ACTION_PIP_REWIND = "com.jellyscope.android.PIP_REWIND"
private const val ACTION_PIP_FORWARD = "com.jellyscope.android.PIP_FORWARD"
private const val REQUEST_PIP_PLAY_PAUSE = 10
private const val REQUEST_PIP_REWIND = 11
private const val REQUEST_PIP_FORWARD = 12
private const val PIP_SEEK_MS = 10_000L
private const val DEFAULT_PIP_WIDTH = 16
private const val DEFAULT_PIP_HEIGHT = 9
