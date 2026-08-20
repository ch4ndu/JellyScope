// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.SubtitleSelectionKey
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.repository.OpenSubtitlesRepository
import com.jellyscope.core.domain.action.DownloadAndInstallOpenSubtitleAction
import com.jellyscope.core.domain.action.InstallLocalSubtitleAction
import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.OpenSubtitleDownload
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.usecase.SearchOpenSubtitlesUseCase
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
            val saveSelection = SaveSubtitleSelectionAction(NoopSubtitleSelectionStore(), backgroundScope)
            val install =
                InstallLocalSubtitleAction(
                    assetStore = NoopLocalSubtitleAssetStore(),
                    fileStore = NoopLocalSubtitleFileStore(),
                    saveSubtitleSelectionAction = saveSelection,
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

private class NoopLocalSubtitleAssetStore : LocalSubtitleAssetStore {
    override fun observe(context: LocalSubtitleContext): Flow<List<com.jellyscope.core.domain.model.LocalSubtitleAsset>> = emptyFlow()

    override fun observePendingSync(): Flow<List<com.jellyscope.core.domain.model.LocalSubtitleAsset>> = emptyFlow()

    override suspend fun get(assetId: String): com.jellyscope.core.domain.model.LocalSubtitleAsset? = null

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): com.jellyscope.core.domain.model.LocalSubtitleAsset? = null

    override suspend fun upsert(asset: com.jellyscope.core.domain.model.LocalSubtitleAsset) = Unit

    override suspend fun delete(assetId: String) = Unit

    override suspend fun all(): List<com.jellyscope.core.domain.model.LocalSubtitleAsset> = emptyList()

    override suspend fun clearAll() = Unit
}

private class NoopLocalSubtitleFileStore : LocalSubtitleFileStore {
    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun read(fileId: String): ByteArray? = null

    override suspend fun exists(fileId: String): Boolean = false

    override suspend fun delete(fileId: String) = Unit

    override suspend fun listFileIds(): Set<String> = emptySet()

    override fun resolvePath(fileId: String): String? = null
}

private class NoopSubtitleSelectionStore : SubtitleSelectionStore {
    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = null

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) = Unit

    override suspend fun delete(key: SubtitleSelectionKey) = Unit

    override suspend fun clearAccount(accountIdentity: com.jellyscope.core.domain.model.AccountIdentity) = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}
