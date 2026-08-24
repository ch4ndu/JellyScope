// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidLocalSubtitleFileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val subtitleDirectory = File(context.noBackupFilesDir, "subtitles")

    @BeforeTest
    fun setUp() {
        subtitleDirectory.deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        subtitleDirectory.deleteRecursively()
    }

    @Test
    fun deletionIsIdempotentAndReportsAStillPresentTargetWithoutItsPath() =
        runTest {
            val store = AndroidLocalSubtitleFileStore(context)
            store.writeAtomically("subtitle.vtt", byteArrayOf(1, 2, 3))

            store.delete("subtitle.vtt")
            store.delete("subtitle.vtt")

            assertFalse(store.exists("subtitle.vtt"))

            val blockedFileId = "private-title.vtt"
            val blockedTarget = File(subtitleDirectory, blockedFileId)
            check(File(blockedTarget, "child").apply { parentFile?.mkdirs() }.createNewFile())

            val failure = assertFailsWith<IllegalStateException> { store.delete(blockedFileId) }

            assertEquals("Unable to delete local subtitle file.", failure.message)
            assertFalse(failure.message?.contains(blockedFileId) == true)
            assertTrue(blockedTarget.exists())
        }
}
