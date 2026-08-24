// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.repository.LocalSubtitleMutationCoordinator
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalSubtitleActionsTest {
    @Test
    fun srtIsConvertedToCanonicalWebVtt() {
        val normalized =
            normalizeSubtitle(
                "1\r\n00:00:01,250 --> 00:00:03,000\r\nHello\r\n".encodeToByteArray(),
                "srt",
            )

        assertEquals("srt", normalized.originalFormat)
        assertTrue(normalized.webVtt.startsWith("WEBVTT\n\n"))
        assertTrue("00:00:01.250 --> 00:00:03.000" in normalized.webVtt)
    }

    @Test
    fun utf16WebVttIsDecoded() {
        val text = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello"
        val bytes =
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                text
                    .flatMap { character ->
                        listOf((character.code and 0xFF).toByte(), (character.code shr 8).toByte())
                    }.toByteArray()

        assertTrue("Hello" in normalizeSubtitle(bytes, "vtt").webVtt)
    }

    @Test
    fun htmlAndUnsupportedFormatsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            normalizeSubtitle("<!doctype html><title>Error</title>".encodeToByteArray(), "srt")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeSubtitle("not a timed subtitle".encodeToByteArray(), null)
        }
    }

    @Test
    fun oversizedPayloadIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            normalizeSubtitle(ByteArray(5 * 1024 * 1024 + 1), "srt")
        }
    }

    @Test
    fun duplicateInstallReselectsExistingAssetAndDeleteRemovesFileAndSelection() =
        runTest {
            val assetStore = ActionTestAssetStore()
            val fileStore = ActionTestFileStore()
            val selectionStore = ActionTestSelectionStore()
            val coordinator = LocalSubtitleMutationCoordinator(assetStore, fileStore, selectionStore, backgroundScope)
            val install = InstallLocalSubtitleAction(coordinator)
            val request = installRequest()

            val first = install(request)
            val second = install(request)

            assertEquals(first, second)
            assertEquals(1, fileStore.writeCount)
            val key = SubtitleSelectionKey("server", "user", "item", "source")
            assertEquals(SubtitleSelectionIntent.LocalAsset(first.id), selectionStore.get(key))

            DeleteLocalSubtitleAction(coordinator)(first.id)

            assertEquals(null, assetStore.get(first.id))
            assertEquals(null, selectionStore.get(key))
            assertEquals(setOf(first.fileId), fileStore.deleted)
        }

    @Test
    fun normalizationAndFinalEncodingRunOnTheInjectedWorkerDispatcherBeforeActorWrites() =
        runTest {
            val assetStore = ActionTestAssetStore()
            val fileStore = ActionTestFileStore()
            val selectionStore = ActionTestSelectionStore()
            val coordinator = LocalSubtitleMutationCoordinator(assetStore, fileStore, selectionStore, backgroundScope)
            val workerDispatcher = QueuedActionDispatcher()
            val payloadEncoder = RecordingLocalSubtitlePayloadEncoder(workerDispatcher)
            val action = InstallLocalSubtitleAction(coordinator, workerDispatcher, payloadEncoder)
            val install = async { action(installRequest()) }

            runCurrent()

            assertEquals(1, workerDispatcher.pendingCount)
            assertEquals(0, fileStore.writeCount)
            assertEquals(null, fileStore.lastWrittenText)
            assertEquals(0, payloadEncoder.calls)

            workerDispatcher.runNext()
            assertEquals(0, fileStore.writeCount)
            runCurrent()
            advanceUntilIdle()
            install.await()

            assertEquals(1, fileStore.writeCount)
            assertEquals("WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nHello\n", fileStore.lastWrittenText)
            assertEquals(1, payloadEncoder.calls)
            assertEquals(listOf(true), payloadEncoder.workerContexts)
        }
}

private fun installRequest() =
    InstallLocalSubtitleRequest(
        context = LocalSubtitleContext("server", "user", "item", "source"),
        provider = "OpenSubtitles",
        providerSubtitleId = "subtitle",
        providerFileId = "file",
        language = "en",
        label = "English",
        releaseName = "Movie.1080p",
        declaredFormat = "srt",
        hearingImpaired = false,
        forced = false,
        trusted = true,
        bytes = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".encodeToByteArray(),
    )

private class ActionTestAssetStore : LocalSubtitleAssetStore {
    private val assets = MutableStateFlow<List<LocalSubtitleAsset>>(emptyList())

    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> =
        assets.map { values -> values.filter { it.context == context } }

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> = assets

    override suspend fun get(assetId: String): LocalSubtitleAsset? = assets.value.firstOrNull { it.id == assetId }

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? =
        assets.value.firstOrNull {
            it.context == context &&
                it.provider == provider &&
                it.providerFileId == providerFileId
        }

    override suspend fun upsert(asset: LocalSubtitleAsset) {
        assets.value = assets.value.filterNot { it.id == asset.id } + asset
    }

    override suspend fun delete(assetId: String) {
        assets.value = assets.value.filterNot { it.id == assetId }
    }

    override suspend fun all(): List<LocalSubtitleAsset> = assets.value

    override suspend fun clearAll() {
        assets.value = emptyList()
    }
}

private class ActionTestFileStore : LocalSubtitleFileStore {
    private val files = mutableMapOf<String, ByteArray>()
    val deleted = mutableSetOf<String>()
    var writeCount = 0
    val lastWrittenText: String?
        get() = files.values.lastOrNull()?.decodeToString()

    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) {
        writeCount += 1
        files[fileId] = bytes
    }

    override suspend fun read(fileId: String): ByteArray? = files[fileId]

    override suspend fun exists(fileId: String): Boolean = fileId in files

    override suspend fun delete(fileId: String) {
        files.remove(fileId)
        deleted += fileId
    }

    override suspend fun listFileIds(): Set<String> = files.keys

    override fun resolvePath(fileId: String): String? = fileId.takeIf { it in files }
}

private class ActionTestSelectionStore : SubtitleSelectionStore {
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

    override suspend fun clearServerScoped(serverId: String) {
        values.keys.removeAll { it.serverId == serverId }
    }

    override suspend fun clearServerScoped() {
        values.clear()
    }
}

private val LocalSubtitleAsset.context: LocalSubtitleContext
    get() = LocalSubtitleContext(serverId, userId, itemId, mediaSourceId)

private class QueuedActionDispatcher : CoroutineDispatcher() {
    private val pending = ArrayDeque<Runnable>()
    var isRunning = false
        private set

    val pendingCount: Int
        get() = pending.size

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        pending.addLast(block)
    }

    fun runNext() {
        isRunning = true
        try {
            pending.removeFirst().run()
        } finally {
            isRunning = false
        }
    }
}

private class RecordingLocalSubtitlePayloadEncoder(
    private val dispatcher: QueuedActionDispatcher,
) : LocalSubtitlePayloadEncoder {
    var calls = 0
    val workerContexts = mutableListOf<Boolean>()

    override fun encode(webVtt: String): ByteArray {
        calls += 1
        workerContexts += dispatcher.isRunning
        return webVtt.encodeToByteArray()
    }
}
