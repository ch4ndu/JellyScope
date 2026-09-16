// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos

import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.data.remote.AuthHeaderBuilder
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.di.coreModule
import com.jellyscope.core.di.downloadsModule
import com.jellyscope.core.di.tvosCoreModule
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.OfflineArtworkRole
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.detectDisplaySupportsHdr
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.playback.ApplePlaybackSurfaceProvider
import com.jellyscope.core.util.LogBufferStore
import com.jellyscope.core.util.configureApplicationLogWriters
import com.jellyscope.core.util.consumePreviousRunFailure
import com.jellyscope.core.util.installPreviousRunFailureHandler
import com.jellyscope.tvos.di.tvosPresentationModule
import com.jellyscope.tvos.presenter.TvAccountsPresenter
import com.jellyscope.tvos.presenter.TvAppearancePresenter
import com.jellyscope.tvos.presenter.TvDetailPlaybackSelection
import com.jellyscope.tvos.presenter.TvDeviceSettingsPresenter
import com.jellyscope.tvos.presenter.TvDownloadArtworkPresenter
import com.jellyscope.tvos.presenter.TvDownloadArtworkRequest
import com.jellyscope.tvos.presenter.TvDownloadRequest
import com.jellyscope.tvos.presenter.TvDownloadRequestPresenter
import com.jellyscope.tvos.presenter.TvDownloadsPresenter
import com.jellyscope.tvos.presenter.TvHomePresenter
import com.jellyscope.tvos.presenter.TvHomeRowKind
import com.jellyscope.tvos.presenter.TvHomeViewAllPresenter
import com.jellyscope.tvos.presenter.TvItemDetailPresenter
import com.jellyscope.tvos.presenter.TvItemDetailRequest
import com.jellyscope.tvos.presenter.TvLibrariesPresenter
import com.jellyscope.tvos.presenter.TvLibraryBrowsePresenter
import com.jellyscope.tvos.presenter.TvLibraryHubPresenter
import com.jellyscope.tvos.presenter.TvLibraryTile
import com.jellyscope.tvos.presenter.TvLoginPresenter
import com.jellyscope.tvos.presenter.TvOfflinePlaybackPresenter
import com.jellyscope.tvos.presenter.TvOfflinePlaybackRequest
import com.jellyscope.tvos.presenter.TvPersonPresenter
import com.jellyscope.tvos.presenter.TvPlaybackRequest
import com.jellyscope.tvos.presenter.TvPlaybackSessionPresenter
import com.jellyscope.tvos.presenter.TvPlaybackSubtitleMode
import com.jellyscope.tvos.presenter.TvSearchPresenter
import com.jellyscope.tvos.presenter.TvSessionPresenter
import com.jellyscope.tvos.presenter.TvSettingsPresenter
import com.jellyscope.tvos.presenter.TvSubtitleSettingsPresenter
import com.jellyscope.tvos.presenter.TvSubtitlesPresenter
import com.jellyscope.tvos.presenter.TvSubtitlesRequest
import kotlinx.coroutines.CoroutineScope
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.Foundation.NSBundle
import platform.UIKit.UIView

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
                tvosCoreModule(
                    displaySupportsHdr = displaySupportsHdr,
                    developerOpenSubtitlesApiKey =
                        TvDevServerConfig.OPEN_SUBTITLES_API_KEY.takeIf(String::isNotBlank),
                ),
                coreModule,
                downloadsModule,
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
        koin.get<DownloadLifecycleHost>().start()
        started = true
    }

    fun sessionPresenter(): TvSessionPresenter = koin().get()

    fun loginPresenter(): TvLoginPresenter = koin().get()

    fun accountsPresenter(): TvAccountsPresenter = koin().get()

    fun appearancePresenter(): TvAppearancePresenter = koin().get()

    fun deviceSettingsPresenter(): TvDeviceSettingsPresenter = koin().get()

    fun subtitleSettingsPresenter(): TvSubtitleSettingsPresenter = koin().get()

    fun subtitlesPresenter(
        session: Session,
        itemId: String,
        mediaSourceId: String?,
    ): TvSubtitlesPresenter = koin().get { parametersOf(TvSubtitlesRequest(session, itemId, mediaSourceId)) }

    fun downloadsPresenter(session: Session): TvDownloadsPresenter = koin().get { parametersOf(session) }

    fun downloadArtworkPresenter(
        session: Session,
        downloadId: String,
        attemptGeneration: Long,
        role: OfflineArtworkRole,
    ): TvDownloadArtworkPresenter =
        koin().get { parametersOf(TvDownloadArtworkRequest(session, DownloadId(downloadId), attemptGeneration, role)) }

    fun downloadRequestPresenter(
        session: Session,
        selection: TvDetailPlaybackSelection,
    ): TvDownloadRequestPresenter = koin().get { parametersOf(TvDownloadRequest(session, selection)) }

    fun offlinePlaybackPresenter(
        session: Session,
        downloadId: String,
        restart: Boolean,
    ): TvOfflinePlaybackPresenter = koin().get { parametersOf(TvOfflinePlaybackRequest(session, downloadId, restart)) }

    fun offlinePlaybackSurface(presenter: TvOfflinePlaybackPresenter): UIView? =
        (presenter.surfaceController as? ApplePlaybackSurfaceProvider)?.createSurfaceView()

    fun homePresenter(session: Session): TvHomePresenter = koin().get { parametersOf(session) }

    fun homeViewAllPresenter(
        session: Session,
        row: TvHomeRowKind,
    ): TvHomeViewAllPresenter = koin().get { parametersOf(session, row) }

    fun librariesPresenter(session: Session): TvLibrariesPresenter = koin().get { parametersOf(session) }

    fun libraryHubPresenter(
        session: Session,
        library: TvLibraryTile,
    ): TvLibraryHubPresenter = koin().get { parametersOf(session, library) }

    fun libraryBrowsePresenter(
        session: Session,
        library: TvLibraryTile,
    ): TvLibraryBrowsePresenter = koin().get { parametersOf(session, library) }

    fun itemDetailPresenter(
        session: Session,
        itemId: String,
        initialSeasonId: String? = null,
    ): TvItemDetailPresenter = koin().get { parametersOf(TvItemDetailRequest(session, itemId, initialSeasonId)) }

    fun personPresenter(
        session: Session,
        personId: String,
    ): TvPersonPresenter = koin().get { parametersOf(session, personId) }

    fun searchPresenter(session: Session): TvSearchPresenter = koin().get { parametersOf(session) }

    fun settingsPresenter(session: Session): TvSettingsPresenter = koin().get { parametersOf(session) }

    fun playbackPresenter(
        session: Session,
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Long,
        audioStreamIndex: Int? = null,
        subtitleMode: TvPlaybackSubtitleMode = TvPlaybackSubtitleMode.Unspecified,
        subtitleStreamIndex: Int? = null,
        subtitleAssetId: String? = null,
    ): TvPlaybackSessionPresenter =
        koin().get {
            parametersOf(
                TvPlaybackRequest(
                    session = session,
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    startPositionTicks = startPositionTicks,
                    audioStreamIndex = audioStreamIndex,
                    subtitleMode = subtitleMode,
                    subtitleStreamIndex = subtitleStreamIndex,
                    subtitleAssetId = subtitleAssetId,
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
