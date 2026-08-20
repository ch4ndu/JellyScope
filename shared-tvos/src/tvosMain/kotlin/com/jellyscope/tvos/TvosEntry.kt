// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos

import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.data.remote.AuthHeaderBuilder
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.di.coreModule
import com.jellyscope.core.di.tvosCoreModule
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.detectDisplaySupportsHdr
import com.jellyscope.core.util.LogBufferStore
import com.jellyscope.core.util.configureApplicationLogWriters
import com.jellyscope.core.util.consumePreviousRunFailure
import com.jellyscope.core.util.installPreviousRunFailureHandler
import com.jellyscope.tvos.di.tvosPresentationModule
import com.jellyscope.tvos.presenter.TvHomePresenter
import com.jellyscope.tvos.presenter.TvItemDetailPresenter
import com.jellyscope.tvos.presenter.TvLibrariesPresenter
import com.jellyscope.tvos.presenter.TvLibraryBrowsePresenter
import com.jellyscope.tvos.presenter.TvLoginPresenter
import com.jellyscope.tvos.presenter.TvPlaybackRequest
import com.jellyscope.tvos.presenter.TvPlaybackSessionPresenter
import com.jellyscope.tvos.presenter.TvSearchPresenter
import com.jellyscope.tvos.presenter.TvSessionPresenter
import com.jellyscope.tvos.presenter.TvSettingsPresenter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.Foundation.NSBundle

/**
 * The entire Swift-facing surface of the tvOS shell: Koin bootstrap, typed
 * presenter factories, and dev prefill. Swift never touches Koin directly.
 */
object TvosEntry {
    private var started = false

    fun start() {
        if (started) {
            return
        }
        // Swift calls this UIKit entry on the main thread. Capture the display
        // signal once before Koin starts; providers only read this snapshot.
        val displaySupportsHdr = detectDisplaySupportsHdr()
        startKoin {
            modules(
                tvosCoreModule(displaySupportsHdr = displaySupportsHdr),
                coreModule,
                module {
                    single { ClientInfo(versionName = bundleVersionName()) }
                },
                tvosPresentationModule,
            )
        }
        val koin = KoinPlatform.getKoin()
        configureApplicationLogWriters(
            diagnosticsWriter = koin.get<LogBufferStore>().logWriter,
            preferenceStore = koin.get<LogCollectionPreferenceStore>(),
            applicationScope = koin.get<CoroutineScope>(),
            isDebugBuild = TvDevServerConfig.IS_DEBUG_BUILD,
        )
        consumePreviousRunFailure(
            store = koin.get<PreviousRunFailureStore>(),
            preferenceStore = koin.get<LogCollectionPreferenceStore>(),
            logBufferStore = koin.get<LogBufferStore>(),
        )
        installPreviousRunFailureHandler(
            store = koin.get<PreviousRunFailureStore>(),
            platform = PreviousRunFailurePlatform.TvOs,
        )
        started = true
    }

    fun sessionPresenter(): TvSessionPresenter = koin().get()

    fun loginPresenter(): TvLoginPresenter = koin().get()

    fun homePresenter(session: Session): TvHomePresenter = koin().get { parametersOf(session) }

    fun librariesPresenter(session: Session): TvLibrariesPresenter = koin().get { parametersOf(session) }

    fun libraryBrowsePresenter(
        session: Session,
        libraryId: String,
    ): TvLibraryBrowsePresenter = koin().get { parametersOf(session, libraryId) }

    fun itemDetailPresenter(
        session: Session,
        itemId: String,
    ): TvItemDetailPresenter = koin().get { parametersOf(session, itemId) }

    fun searchPresenter(session: Session): TvSearchPresenter = koin().get { parametersOf(session) }

    fun settingsPresenter(session: Session): TvSettingsPresenter = koin().get { parametersOf(session) }

    fun playbackPresenter(
        session: Session,
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Long,
    ): TvPlaybackSessionPresenter =
        koin().get {
            parametersOf(
                TvPlaybackRequest(
                    session = session,
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    startPositionTicks = startPositionTicks,
                ),
            )
        }

    /**
     * Auth header for Swift-side artwork requests (AsyncImage cannot send
     * headers). Opaque value only — never log it.
     */
    fun imageAuthHeader(session: Session): String =
        AuthHeaderBuilder.build(
            deviceName = koin().get<DeviceInfoProvider>().deviceName,
            deviceId = session.deviceId,
            clientInfo = koin().get(),
            token = session.accessToken,
        )

    fun devServerUrl(): String = TvDevServerConfig.SERVER_URL

    fun devUsername(): String = TvDevServerConfig.USERNAME

    fun sourceRevision(): String = TvDistributionBuildInfo.SOURCE_REVISION

    fun devPassword(): String = TvDevServerConfig.PASSWORD

    private fun koin() = KoinPlatform.getKoin()

    private fun bundleVersionName(): String =
        listOf("CFBundleShortVersionString", "CFBundleVersion")
            .firstNotNullOfOrNull { key ->
                NSBundle.mainBundle
                    .objectForInfoDictionaryKey(key)
                    ?.toString()
                    ?.takeIf { value -> value.isNotBlank() }
            } ?: "dev"
}
