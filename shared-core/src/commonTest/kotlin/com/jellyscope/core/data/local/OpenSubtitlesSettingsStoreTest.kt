// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.action.SetOpenSubtitleResultPreferenceAction
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.usecase.GetOpenSubtitleResultPreferenceUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OpenSubtitlesSettingsStoreTest {
    @Test
    fun missingBlankAndUnknownValuesDecodeToNoPreference() =
        runTest {
            listOf(null, "", "   ", "FuturePreference").forEach { stored ->
                val secureStore = FakeSecureStore()
                stored?.let { secureStore.write(PREFERENCE_KEY, it) }

                assertEquals(
                    OpenSubtitleResultPreference.NoPreference,
                    OpenSubtitlesSettingsStore(secureStore).resultPreference(),
                )
            }
        }

    @Test
    fun useCaseAndActionPersistEveryPreferenceAndKeepDefaultSparse() =
        runTest {
            val secureStore = FakeSecureStore()
            val store = OpenSubtitlesSettingsStore(secureStore)
            val getPreference = GetOpenSubtitleResultPreferenceUseCase(store)
            val setPreference = SetOpenSubtitleResultPreferenceAction(store)

            setPreference(OpenSubtitleResultPreference.PreferHearingImpaired)
            assertEquals(OpenSubtitleResultPreference.PreferHearingImpaired, getPreference())
            assertEquals("PreferHearingImpaired", secureStore.read(PREFERENCE_KEY))

            setPreference(OpenSubtitleResultPreference.PreferForced)
            assertEquals(OpenSubtitleResultPreference.PreferForced, getPreference())
            assertEquals(
                OpenSubtitleResultPreference.PreferForced,
                OpenSubtitlesSettingsStore(secureStore).resultPreference(),
            )

            setPreference(OpenSubtitleResultPreference.NoPreference)
            assertEquals(OpenSubtitleResultPreference.NoPreference, getPreference())
            assertEquals(null, secureStore.read(PREFERENCE_KEY))
            assertEquals(
                OpenSubtitleResultPreference.NoPreference,
                OpenSubtitlesSettingsStore(secureStore).resultPreference(),
            )
        }

    @Test
    fun failedWriteRollsBackTheAuthoritativePreference() =
        runTest {
            val secureStore = FakeSecureStore()
            val store = OpenSubtitlesSettingsStore(secureStore)
            store.setResultPreference(OpenSubtitleResultPreference.PreferHearingImpaired)
            secureStore.failOnWriteKey = PREFERENCE_KEY

            assertFailsWith<IllegalStateException> {
                store.setResultPreference(OpenSubtitleResultPreference.PreferForced)
            }

            assertEquals(OpenSubtitleResultPreference.PreferHearingImpaired, store.resultPreference())
            assertEquals(
                OpenSubtitleResultPreference.PreferHearingImpaired,
                OpenSubtitlesSettingsStore(secureStore).resultPreference(),
            )
        }

    @Test
    fun failedDefaultRemovalRollsBackTheAuthoritativePreference() =
        runTest {
            val secureStore = FakeSecureStore()
            val store = OpenSubtitlesSettingsStore(secureStore)
            store.setResultPreference(OpenSubtitleResultPreference.PreferForced)
            secureStore.failOnRemoveKey = PREFERENCE_KEY

            assertFailsWith<IllegalStateException> {
                store.setResultPreference(OpenSubtitleResultPreference.NoPreference)
            }

            assertEquals(OpenSubtitleResultPreference.PreferForced, store.resultPreference())
            assertEquals(
                OpenSubtitleResultPreference.PreferForced,
                OpenSubtitlesSettingsStore(secureStore).resultPreference(),
            )
        }

    @Test
    fun immediateReadWaitsForPreferencePersistenceAndObservesTheCommittedChoice() =
        runTest {
            val secureStore = BlockingPreferenceSecureStore()
            val store = OpenSubtitlesSettingsStore(secureStore)
            val write = async { store.setResultPreference(OpenSubtitleResultPreference.PreferForced) }
            secureStore.writeStarted.await()
            val immediateRead = async { store.resultPreference() }

            assertFalse(immediateRead.isCompleted)
            secureStore.allowWrite.complete(Unit)

            write.await()
            assertEquals(OpenSubtitleResultPreference.PreferForced, immediateRead.await())
        }
}

private class BlockingPreferenceSecureStore : SecureStore {
    val writeStarted = CompletableDeferred<Unit>()
    val allowWrite = CompletableDeferred<Unit>()
    private val values = mutableMapOf<String, String>()

    override suspend fun read(key: String): String? = values[key]

    override suspend fun write(
        key: String,
        value: String,
    ) {
        if (key == PREFERENCE_KEY) {
            writeStarted.complete(Unit)
            allowWrite.await()
        }
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }
}

private const val PREFERENCE_KEY = "opensubtitles_result_preference"
