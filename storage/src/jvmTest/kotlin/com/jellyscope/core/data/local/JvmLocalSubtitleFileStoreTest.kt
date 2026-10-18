// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmLocalSubtitleFileStoreTest {
    private lateinit var rootDirectory: Path

    @BeforeTest
    fun setUp() {
        rootDirectory = Files.createTempDirectory("jellyscope-local-subtitle-test")
    }

    @AfterTest
    fun tearDown() {
        rootDirectory.toFile().deleteRecursively()
    }

    @Test
    fun deletionIsIdempotentAndReportsAStillPresentTargetWithoutItsPath() =
        runTest {
            val store = JvmLocalSubtitleFileStore(rootDirectory = rootDirectory.toFile())
            store.writeAtomically("subtitle.vtt", byteArrayOf(1, 2, 3))

            store.delete("subtitle.vtt")
            store.delete("subtitle.vtt")

            assertFalse(store.exists("subtitle.vtt"))

            val blockedFileId = "private-title.vtt"
            val blockedTarget = rootDirectory.resolve("subtitles").resolve(blockedFileId)
            Files.createDirectories(blockedTarget.resolve("child"))

            val failure = assertFailsWith<IllegalStateException> { store.delete(blockedFileId) }

            assertEquals("Unable to delete local subtitle file.", failure.message)
            assertFalse(failure.message?.contains(blockedFileId) == true)
            assertTrue(Files.exists(blockedTarget))
        }
}
