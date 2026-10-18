// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.ApplePlaintextSecureStore
import com.jellyscope.core.data.local.SecureStore
import com.jellyscope.core.data.local.createJellyfinStoreDatabase
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.platform.AppleDiagnosticsEnvironment
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.playback.AppleDeviceProfileProvider
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.playback.AppleAVPlayerController
import com.jellyscope.core.playback.OfflineArtifactResolver
import com.jellyscope.core.playback.createAppleVlcKitController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import platform.Foundation.NSUUID
import platform.UIKit.UIDevice

/**
 * Apple-family (iOS/tvOS) DI. Session credentials persist through the shared
 * SecureStore boundary so launch restore follows the same common path as
 * Android; credentials use the app-owned plaintext UserDefaults store.
 * Note: tvOS caps UserDefaults persistence at ~500 KB — the UserDefaults-backed
 * preference stores bound here keep their data well under that budget.
 */
fun appleCoreModule(
    diagnosticPlatform: PlaybackDiagnosticPlatform = PlaybackDiagnosticPlatform.Ios,
    displaySupportsHdr: Boolean = false,
    developerOpenSubtitlesApiKey: String? = null,
) = module {
    single {
        CoreConfig(
            enableHttpLogging = false,
            developerOpenSubtitlesApiKey = developerOpenSubtitlesApiKey,
        )
    }
    single<SecureStore> { ApplePlaintextSecureStore() }
    single<DeviceInfoProvider> { AppleDeviceInfoProvider() }
    single<DiagnosticsEnvironment> { AppleDiagnosticsEnvironment(diagnosticPlatform) }
    single<DeviceProfileProvider> {
        AppleDeviceProfileProvider(
            displaySupportsHdr = displaySupportsHdr,
            isTvOs = diagnosticPlatform == PlaybackDiagnosticPlatform.TvOs,
        )
    }
    factory<PlayerController> { (session: Session, backend: PlayerBackend, _allowInsecureDesktopTls: Boolean) ->
        val offlineArtifactResolver = getOrNull<OfflineArtifactResolver>()
        when (backend) {
            PlayerBackend.VlcKit ->
                createAppleVlcKitController(
                    session = session,
                    stateScope = get(),
                    localSubtitleFileStore = get(),
                    diagnosticPlatform = diagnosticPlatform,
                    offlineArtifactResolver = offlineArtifactResolver,
                )
            PlayerBackend.Auto,
            PlayerBackend.AVPlayer,
            PlayerBackend.ExoPlayer,
            PlayerBackend.Mpv,
            PlayerBackend.LibVlc,
            ->
                AppleAVPlayerController(
                    session = session,
                    deviceInfoProvider = get(),
                    clientInfo = get(),
                    stateScope = get(),
                    localSubtitleFileStore = get(),
                    diagnosticPlatform = diagnosticPlatform,
                ).also { controller ->
                    offlineArtifactResolver?.let(controller::setOfflineArtifactResolver)
                }
        }
    }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    includes(appleStorageModule())
    single { createJellyfinStoreDatabase() }
}

private class AppleDeviceInfoProvider : DeviceInfoProvider {
    override val deviceName: String = UIDevice.currentDevice.model

    override fun newDeviceId(): String = NSUUID().UUIDString
}
