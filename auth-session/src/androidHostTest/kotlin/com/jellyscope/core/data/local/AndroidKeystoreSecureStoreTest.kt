// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.UnrecoverableKeyException
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreSecureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testKey = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    private val backupExcludedFile = File(context.noBackupFilesDir, STORE_FILE_NAME)
    private val legacyFileLocation = File(context.filesDir, STORE_FILE_NAME)

    @BeforeTest
    fun setUp() {
        backupExcludedFile.delete()
        legacyFileLocation.delete()
    }

    @AfterTest
    fun tearDown() {
        backupExcludedFile.delete()
        legacyFileLocation.delete()
    }

    @Test
    fun corruptStoreIsDiscardedAndCanRoundTripAfterwards() =
        runTest {
            backupExcludedFile.writeBytes(ByteArray(CORRUPT_FILE_BYTE_COUNT) { index -> index.toByte() })

            val store = AndroidKeystoreSecureStore(context, keyProvider = { testKey })

            assertNull(store.read("accessToken"))
            assertFalse(backupExcludedFile.exists())

            store.write("accessToken", "fresh-token")

            assertEquals("fresh-token", store.read("accessToken"))
            assertFalse(legacyFileLocation.exists())
        }

    @Test
    fun permanentKeyFailureIsDiscardedAndReturnsEmpty() =
        runTest {
            backupExcludedFile.writeBytes(ByteArray(CORRUPT_FILE_BYTE_COUNT) { index -> index.toByte() })

            val store =
                AndroidKeystoreSecureStore(context) {
                    throw UnrecoverableKeyException(RAW_THROWABLE_MESSAGE)
                }

            assertNull(store.read("accessToken"))
            assertFalse(backupExcludedFile.exists())
        }

    @Test
    fun transientKeyFailurePropagatesAndLeavesBlobIntact() =
        runTest {
            AndroidKeystoreSecureStore(context, keyProvider = { testKey })
                .write("accessToken", "preserved-token")
            val bytesBeforeFailure = backupExcludedFile.readBytes()

            val store =
                AndroidKeystoreSecureStore(context) {
                    throw IOException(RAW_THROWABLE_MESSAGE)
                }

            assertFailsWith<IOException> {
                store.read("accessToken")
            }
            assertTrue(backupExcludedFile.exists())
            assertEquals(bytesBeforeFailure.toList(), backupExcludedFile.readBytes().toList())
        }

    @Test
    fun recoveryDiagnosticsDistinguishPermanentKeyAndCorruptBlobWithoutThrowableDetails() =
        runTest {
            val messages = mutableListOf<String>()
            Logger.setLogWriters(
                listOf(
                    object : LogWriter() {
                        override fun log(
                            severity: Severity,
                            message: String,
                            tag: String,
                            throwable: Throwable?,
                        ) {
                            if (tag == SECURE_STORE_LOG_TAG) {
                                messages += message
                            }
                        }
                    },
                ),
            )
            try {
                backupExcludedFile.writeBytes(ByteArray(CORRUPT_FILE_BYTE_COUNT) { index -> index.toByte() })
                AndroidKeystoreSecureStore(context, keyProvider = { testKey }).read("accessToken")

                AndroidKeystoreSecureStore(context, keyProvider = { testKey })
                    .write("accessToken", "fresh-token")
                AndroidKeystoreSecureStore(context) {
                    throw UnrecoverableKeyException(RAW_THROWABLE_MESSAGE)
                }.read("accessToken")
            } finally {
                Logger.setLogWriters(emptyList())
            }

            assertTrue(messages.any { message -> message.contains("stage=secure-store event=corrupt-blob-recovery") })
            assertTrue(messages.any { message -> message.contains("stage=secure-store event=permanent-key-recovery") })
            val combinedMessages = messages.joinToString("\n")
            assertFalse(combinedMessages.contains(RAW_THROWABLE_MESSAGE))
            assertFalse(combinedMessages.contains("cause-token"))
            assertFalse(combinedMessages.contains("stack-token"))
        }
}

private const val SECURE_STORE_LOG_TAG = "AndroidKeystoreSecureStore"
private const val RAW_THROWABLE_MESSAGE = "raw keystore failure message cause-token stack-token"
private const val STORE_FILE_NAME = "secure-store.bin"
private const val CORRUPT_FILE_BYTE_COUNT = 13
