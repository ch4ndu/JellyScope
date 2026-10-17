// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.repository.LocalSubtitleMutationCoordinator
import com.jellyscope.core.data.repository.OpenSubtitleDownload
import com.jellyscope.core.data.repository.OpenSubtitlesRepository
import com.jellyscope.core.domain.action.DownloadAndInstallOpenSubtitleAction
import com.jellyscope.core.domain.action.InstallLocalSubtitleAction
import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.usecase.SearchOpenSubtitlesUseCase
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenSubtitleSearchDialogTest {
    @Test
    fun closingOnOneSourceThenOpeningOnAnotherUsesDifferentViewModelIdentity() {
        val sourceOne = request(mediaSourceId = "source-1")
        val sourceTwo = request(mediaSourceId = "source-2")

        val sourceOneKey = openSubtitleSearchViewModelKey(sourceOne)
        val sourceTwoKey = openSubtitleSearchViewModelKey(sourceTwo)

        assertNotEquals(sourceOneKey, sourceTwoKey)
        assertEquals(sourceTwoKey, openSubtitleSearchViewModelKey(sourceTwo.copy()))
    }

    @Test
    fun searchFailureEmitsOnlyTypedSafeDiagnostic() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val messages = mutableListOf<String>()
            val writer =
                object : LogWriter() {
                    override fun log(
                        severity: Severity,
                        message: String,
                        tag: String,
                        throwable: Throwable?,
                    ) {
                        if (tag == DiagnosticTag.OpenSubtitles.wireValue) {
                            messages += message
                            assertNull(throwable)
                        }
                    }
                }
            val repository = FailingOpenSubtitlesRepository()
            val coordinator =
                LocalSubtitleMutationCoordinator(
                    assetStore = NoopLocalSubtitleAssetStore(),
                    fileStore = NoopLocalSubtitleFileStore(),
                    selectionStore = NoopSubtitleSelectionStore(),
                    scope = backgroundScope,
                )
            val install =
                InstallLocalSubtitleAction(
                    coordinator = coordinator,
                    workerDispatcher = dispatcher,
                )
            try {
                Logger.setLogWriters(writer)
                OpenSubtitleSearchViewModel(
                    request = request(mediaSourceId = "source-1"),
                    searchOpenSubtitlesUseCase = SearchOpenSubtitlesUseCase(repository),
                    downloadAndInstallOpenSubtitleAction = DownloadAndInstallOpenSubtitleAction(repository, install),
                )
                advanceUntilIdle()
            } finally {
                Logger.setLogWriters(emptyList())
                Dispatchers.resetMain()
            }

            val message =
                messages.firstOrNull { value -> value.contains("operation=openSubtitleSearch") }
                    ?: error(messages.joinToString(" | "))
            assertEquals("stage=search event=failed operation=openSubtitleSearch exceptionType=IllegalStateException", message)
            assertEquals(message, LogScrubber.capture(DiagnosticTag.OpenSubtitles.wireValue, message))
            assertFalse(message.contains("subtitle identity message"))
            assertFalse(message.contains("episode-1"))
            assertTrue(message.contains("exceptionType=IllegalStateException"))
        }

    @Test
    fun supersededDownloadDoesNotEmitInstalledButALaterWinningInstallDoes() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val result = selectableResult()
                val repository = GatedDownloadOpenSubtitlesRepository(result)
                val assetStore = NoopLocalSubtitleAssetStore()
                val fileStore = NoopLocalSubtitleFileStore()
                val selectionStore = NoopSubtitleSelectionStore()
                val coordinator =
                    LocalSubtitleMutationCoordinator(
                        assetStore = assetStore,
                        fileStore = fileStore,
                        selectionStore = selectionStore,
                        scope = backgroundScope,
                    )
                val install = InstallLocalSubtitleAction(coordinator, dispatcher)
                val saveSelection = SaveSubtitleSelectionAction(coordinator, backgroundScope)
                val viewModel =
                    OpenSubtitleSearchViewModel(
                        request = request(mediaSourceId = "source-1"),
                        searchOpenSubtitlesUseCase = SearchOpenSubtitlesUseCase(repository),
                        downloadAndInstallOpenSubtitleAction = DownloadAndInstallOpenSubtitleAction(repository, install),
                    )
                val selectionToken = DetailSubtitleSelectionToken(DetailSubtitleSelectionOwner(), 0)
                val emitted = mutableListOf<LocalSubtitleAsset>()
                backgroundScope.launch {
                    viewModel.installedAcknowledgements.collect { acknowledgement -> emitted += acknowledgement.asset }
                }
                advanceUntilIdle()

                viewModel.install(result, selectionToken)
                repository.downloadStarted.await()
                val key = SubtitleSelectionKey("server-1", "user-1", "episode-1", "source-1")
                val newerWrite = saveSelection.save(key, SubtitleSelectionIntent.Track(7))
                runCurrent()
                newerWrite.await()
                repository.releaseDownload.complete(Unit)
                runCurrent()
                advanceUntilIdle()

                val retainedAsset = assetStore.all().single()
                assertEquals(SubtitleSelectionIntent.Track(7), selectionStore.get(key))
                assertTrue(fileStore.exists(retainedAsset.fileId))
                assertTrue(emitted.isEmpty())

                viewModel.install(result, selectionToken)
                runCurrent()
                advanceUntilIdle()

                assertEquals(SubtitleSelectionIntent.LocalAsset(retainedAsset.id), selectionStore.get(key))
                assertEquals(listOf(retainedAsset), emitted)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun request(mediaSourceId: String) =
        OpenSubtitleSearchRequest(
            context =
                LocalSubtitleContext(
                    serverId = "server-1",
                    userId = "user-1",
                    itemId = "episode-1",
                    mediaSourceId = mediaSourceId,
                ),
            title = "Episode",
            year = 2024,
            imdbId = "tt123",
            seasonNumber = 1,
            episodeNumber = 1,
            language = "eng",
        )
}

private class FailingOpenSubtitlesRepository : OpenSubtitlesRepository {
    override suspend fun search(request: OpenSubtitleSearchRequest): List<OpenSubtitleSearchResult> =
        throw IllegalStateException("subtitle identity message")

    override suspend fun download(fileId: String): OpenSubtitleDownload =
        OpenSubtitleDownload(ByteArray(0), remaining = null, resetTime = null)
}

private class GatedDownloadOpenSubtitlesRepository(
    private val result: OpenSubtitleSearchResult,
) : OpenSubtitlesRepository {
    val downloadStarted = CompletableDeferred<Unit>()
    val releaseDownload = CompletableDeferred<Unit>()

    override suspend fun search(request: OpenSubtitleSearchRequest): List<OpenSubtitleSearchResult> = listOf(result)

    override suspend fun download(fileId: String): OpenSubtitleDownload {
        downloadStarted.complete(Unit)
        releaseDownload.await()
        return OpenSubtitleDownload(
            bytes = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n".encodeToByteArray(),
            remaining = 4,
            resetTime = "later",
        )
    }
}

private class NoopLocalSubtitleAssetStore : LocalSubtitleAssetStore {
    private var asset: LocalSubtitleAsset? = null

    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> = emptyFlow()

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> = emptyFlow()

    override suspend fun get(assetId: String): LocalSubtitleAsset? = asset?.takeIf { it.id == assetId }

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? =
        asset?.takeIf {
            it.serverId == context.serverId &&
                it.userId == context.userId &&
                it.itemId == context.itemId &&
                it.mediaSourceId == context.mediaSourceId &&
                it.provider == provider &&
                it.providerFileId == providerFileId
        }

    override suspend fun upsert(asset: LocalSubtitleAsset) {
        this.asset = asset
    }

    override suspend fun delete(assetId: String) {
        if (asset?.id == assetId) asset = null
    }

    override suspend fun all(): List<LocalSubtitleAsset> = listOfNotNull(asset)

    override suspend fun clearAll() {
        asset = null
    }
}

private class NoopLocalSubtitleFileStore : LocalSubtitleFileStore {
    private val files = mutableMapOf<String, ByteArray>()

    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) {
        files[fileId] = bytes
    }

    override suspend fun read(fileId: String): ByteArray? = files[fileId]

    override suspend fun exists(fileId: String): Boolean = fileId in files

    override suspend fun delete(fileId: String) {
        files.remove(fileId)
    }

    override suspend fun listFileIds(): Set<String> = files.keys

    override fun resolvePath(fileId: String): String? = fileId.takeIf { it in files }
}

private class NoopSubtitleSelectionStore : SubtitleSelectionStore {
    private val values = mutableMapOf<SubtitleSelectionKey, SubtitleSelectionIntent>()

    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = values[key]

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) {
        values[key] = selection
    }

    override suspend fun delete(key: SubtitleSelectionKey) {
        values.remove(key)
    }

    override suspend fun clearAccount(accountIdentity: com.jellyscope.core.domain.model.AccountIdentity) = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

private fun selectableResult() =
    OpenSubtitleSearchResult(
        subtitleId = "subtitle-1",
        fileId = "file-1",
        fileName = "episode.srt",
        language = "en",
        releaseName = "Episode.1080p",
        hearingImpaired = false,
        forced = false,
        trusted = true,
        rating = 8.0,
        downloadCount = 100,
        fps = 24.0,
        format = "srt",
        selectable = true,
    )
