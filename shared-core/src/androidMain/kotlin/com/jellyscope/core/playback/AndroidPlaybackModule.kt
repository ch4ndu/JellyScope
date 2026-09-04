// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.AndroidLibVlcAvailability
import com.jellyscope.core.domain.playback.AndroidLibVlcRuntimeAvailability
import com.jellyscope.core.domain.playback.AndroidMpvRuntimeAvailability
import com.jellyscope.core.domain.playback.AndroidMpvUnavailableReason
import com.jellyscope.core.domain.playback.PlaybackBackendAbi
import com.jellyscope.core.domain.playback.PlaybackBackendAvailability
import com.jellyscope.core.domain.playback.PlaybackBackendConstructionResult
import com.jellyscope.core.domain.playback.PlaybackBackendConstructionStage
import com.jellyscope.core.domain.playback.PlaybackBackendUnavailableReason
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import org.koin.dsl.module

fun androidPlaybackModule(context: Context) =
    module {
        factory<PlayerController> { (session: Session, _backend: PlayerBackend, _allowInsecureDesktopTls: Boolean) ->
            val backend = _backend
            val libVlcAvailable =
                backend == PlayerBackend.LibVlc &&
                    AndroidLibVlcAvailability.check(context.applicationContext) == AndroidLibVlcRuntimeAvailability.Available
            val mpvAvailability =
                if (backend == PlayerBackend.Mpv) {
                    AndroidMpvRuntimeAvailability.check(context.applicationContext)
                } else {
                    null
                }
            var mpvConstructionStage = PlaybackBackendConstructionStage.NotAttempted
            var mpvConstructionFailure: Throwable? = null
            val mpvController =
                if (
                    backend == PlayerBackend.Mpv &&
                    mpvAvailability is AndroidMpvRuntimeAvailability.Bundled
                ) {
                    runCatching {
                        mpvConstructionStage = PlaybackBackendConstructionStage.ResolveSubtitleStore
                        val localSubtitleFileStore = get<LocalSubtitleFileStore>()
                        mpvConstructionStage = PlaybackBackendConstructionStage.CreateNetworkPolicy
                        val networkPolicy =
                            AndroidMpvNetworkPolicy(
                                context = context.applicationContext,
                                session = session,
                                localSubtitleFileStore = localSubtitleFileStore,
                            )
                        mpvConstructionStage = PlaybackBackendConstructionStage.CreateTrustBundle
                        val trustBundlePath = networkPolicy.ensureTrustBundleBlocking().absolutePath
                        mpvConstructionStage = PlaybackBackendConstructionStage.ResolveAudioFocus
                        val audioFocusCoordinator = get<AndroidAudioFocusCoordinator>()
                        mpvConstructionStage = PlaybackBackendConstructionStage.CreateController
                        AndroidMpvPlayerController(
                            context = context.applicationContext,
                            session = session,
                            localSubtitleFileStore = localSubtitleFileStore,
                            audioFocusCoordinator = audioFocusCoordinator,
                            networkPolicy = networkPolicy,
                            logCollectionPreferences = get(),
                            initialCaBundlePath = trustBundlePath,
                        )
                    }.onFailure { failure ->
                        mpvConstructionFailure = failure
                    }.getOrNull()
                } else {
                    null
                }
            if (backend == PlayerBackend.Mpv) {
                playbackModuleLogger.i {
                    androidMpvConstructionDiagnostic(
                        availability = mpvAvailability,
                        stage = mpvConstructionStage,
                        controllerCreated = mpvController != null,
                        failure = mpvConstructionFailure,
                    )
                }
            }
            playbackModuleLogger.i {
                "create backend=${backend.name} libVlcAvailable=$libVlcAvailable mpvAvailable=${mpvController != null}"
            }
            val controller: PlayerController =
                when {
                    mpvController != null -> mpvController
                    libVlcAvailable ->
                        LibVlcPlayerController(
                            context = context.applicationContext,
                            session = session,
                            localSubtitleFileStore = get(),
                            audioFocusCoordinator = get(),
                        )
                    else ->
                        Media3PlayerController(
                            context = context.applicationContext,
                            session = session,
                            deviceInfoProvider = get(),
                            clientInfo = get(),
                            localSubtitleFileStore = get(),
                            audioFocusCoordinator = get(),
                        )
                }
            getOrNull<OfflineArtifactResolver>()?.let { resolver ->
                when (val supportedController = controller) {
                    is AndroidMpvPlayerController -> supportedController.setOfflineArtifactResolver(resolver)
                    is LibVlcPlayerController -> supportedController.setOfflineArtifactResolver(resolver)
                    is Media3PlayerController -> supportedController.setOfflineArtifactResolver(resolver)
                    else -> Unit
                }
            }
            controller
        }
    }

private val playbackModuleLogger = diagnosticLogger(DiagnosticTag.AndroidPlaybackModule)

internal fun androidMpvConstructionDiagnostic(
    availability: AndroidMpvRuntimeAvailability?,
    stage: PlaybackBackendConstructionStage,
    controllerCreated: Boolean,
    failure: Throwable?,
): String {
    val initializationFailure = failure as? AndroidMpvInitializationException
    val diagnosticFailure =
        initializationFailure?.cause
            ?: failure?.takeUnless {
                initializationFailure?.nativeFailureCode != null
            }
    return formatPlaybackDiagnostic(
        PlaybackDiagnostic(
            stage = PlaybackDiagnosticStage.NativePlayer,
            event = PlaybackDiagnosticEvent.BackendConstruction,
            platform = PlaybackDiagnosticPlatform.Android,
            backend = PlayerBackend.Mpv,
            requestedBackend = PlayerBackend.Mpv,
            backendAvailability =
                when (availability) {
                    is AndroidMpvRuntimeAvailability.Bundled -> PlaybackBackendAvailability.Bundled
                    is AndroidMpvRuntimeAvailability.Unavailable -> PlaybackBackendAvailability.Unavailable
                    null -> null
                },
            backendUnavailableReason =
                (availability as? AndroidMpvRuntimeAvailability.Unavailable)
                    ?.reason
                    ?.toPlaybackDiagnosticReason(),
            backendAbi =
                (availability as? AndroidMpvRuntimeAvailability.Bundled)
                    ?.abi
                    ?.toPlaybackDiagnosticAbi(),
            backendConstructionStage = initializationFailure?.constructionStage ?: stage,
            backendConstructionResult =
                when {
                    controllerCreated -> PlaybackBackendConstructionResult.Created
                    failure != null -> PlaybackBackendConstructionResult.Failed
                    availability is AndroidMpvRuntimeAvailability.Unavailable ->
                        PlaybackBackendConstructionResult.Unavailable
                    else -> PlaybackBackendConstructionResult.NotAttempted
                },
            backendConfigurationKey = initializationFailure?.configurationKey,
            nativeCode = initializationFailure?.nativeFailureCode?.toLong(),
            exceptionType = diagnosticFailure?.playbackExceptionType(),
        ),
    )
}

private fun AndroidMpvUnavailableReason.toPlaybackDiagnosticReason(): PlaybackBackendUnavailableReason =
    when (this) {
        AndroidMpvUnavailableReason.OsApiBelowMinimum -> PlaybackBackendUnavailableReason.OsApiBelowMinimum
        AndroidMpvUnavailableReason.ClassLoaderUnavailable -> PlaybackBackendUnavailableReason.ClassLoaderUnavailable
        AndroidMpvUnavailableReason.MpvLibraryMissing -> PlaybackBackendUnavailableReason.MpvLibraryMissing
        AndroidMpvUnavailableReason.PlayerLibraryMissing -> PlaybackBackendUnavailableReason.PlayerLibraryMissing
        AndroidMpvUnavailableReason.LibraryAbiUnknown -> PlaybackBackendUnavailableReason.LibraryAbiUnknown
        AndroidMpvUnavailableReason.LibraryAbiMismatch -> PlaybackBackendUnavailableReason.LibraryAbiMismatch
        AndroidMpvUnavailableReason.AbiNotShipped -> PlaybackBackendUnavailableReason.AbiNotShipped
        AndroidMpvUnavailableReason.DeviceAbiUnsupported -> PlaybackBackendUnavailableReason.DeviceAbiUnsupported
    }

private fun String.toPlaybackDiagnosticAbi(): PlaybackBackendAbi? =
    when (this) {
        "arm64-v8a" -> PlaybackBackendAbi.Arm64V8a
        "armeabi-v7a" -> PlaybackBackendAbi.ArmeabiV7a
        "x86_64" -> PlaybackBackendAbi.X86_64
        else -> null
    }
