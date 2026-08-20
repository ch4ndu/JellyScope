// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.TileSizeId
import kotlinx.coroutines.test.runTest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmFileSettingsStoresTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jellyscope-settings-store-test")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun themeAndPictureInPicturePersistAcrossStoreInstances() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            val preferences = JvmFilePreferencesStore(file)

            val themeStore = JvmFileAppThemeStore(preferences)
            val pipStore = JvmFilePictureInPictureStore(preferences)

            assertEquals(AppColorThemeId.Ember, themeStore.theme.value)
            assertTrue(pipStore.enabled.value)

            themeStore.setTheme(AppColorThemeId.Midnight)
            pipStore.setEnabled(false)

            val restoredPreferences = JvmFilePreferencesStore(file)
            assertEquals(AppColorThemeId.Midnight, JvmFileAppThemeStore(restoredPreferences).theme.value)
            assertFalse(JvmFilePictureInPictureStore(restoredPreferences).enabled.value)
        }

    @Test
    fun unknownThemeFallsBackToEmber() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            JvmFilePreferencesStore(file).writeString("app_theme.color", "Unknown")

            val store = JvmFileAppThemeStore(JvmFilePreferencesStore(file))

            assertEquals(AppColorThemeId.Ember, store.theme.value)
        }

    @Test
    fun tileSizePersistsAcrossStoreInstances() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            val preferences = JvmFilePreferencesStore(file)
            val store = JvmFileTileSizeStore(preferences)

            assertEquals(TileSizeId.Medium, store.tileSize.value)
            store.setTileSize(TileSizeId.Large)

            val restored = JvmFileTileSizeStore(JvmFilePreferencesStore(file))
            assertEquals(TileSizeId.Large, restored.tileSize.value)
        }

    @Test
    fun verboseLogcatDefaultsOffAndPersistsAcrossStoreInstances() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            val store = JvmFileLogCollectionPreferenceStore(JvmFilePreferencesStore(file))

            assertFalse(store.verboseLogcatEnabled.value)
            store.setVerboseLogcatEnabled(true)

            val restored = JvmFileLogCollectionPreferenceStore(JvmFilePreferencesStore(file))
            assertTrue(restored.verboseLogcatEnabled.value)
        }

    @Test
    fun logCollectionDefaultsOnOnlyWhenAbsentAndPersistsExplicitFalse() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            val store = JvmFileLogCollectionPreferenceStore(JvmFilePreferencesStore(file))

            assertTrue(store.enabled.value)
            store.setEnabled(false)

            assertFalse(store.enabled.value)
            assertFalse(
                JvmFileLogCollectionPreferenceStore(JvmFilePreferencesStore(file)).enabled.value,
            )
        }

    @Test
    fun failedLogCollectionReplacementLeavesThePublishedStateUnchanged() =
        runTest {
            val blockedParent = tempDir.resolve("blocked-parent").toFile().also { file -> file.writeText("not a directory") }
            val file = blockedParent.resolve("preferences.json")
            val store = JvmFileLogCollectionPreferenceStore(JvmFilePreferencesStore(file))

            assertTrue(store.enabled.value)
            assertFailsWith<Exception> { store.setEnabled(false) }
            assertTrue(store.enabled.value)
        }

    @Test
    fun playbackInfoAtStartDefaultsOffAndPersistsAcrossStoreInstances() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            val store = JvmFileLogCollectionPreferenceStore(JvmFilePreferencesStore(file))

            assertFalse(store.playbackInfoAtStartEnabled.value)
            store.setPlaybackInfoAtStartEnabled(true)

            val restored = JvmFileLogCollectionPreferenceStore(JvmFilePreferencesStore(file))
            assertTrue(restored.playbackInfoAtStartEnabled.value)
        }

    @Test
    fun unknownTileSizeFallsBackToMedium() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            JvmFilePreferencesStore(file).writeString("tile_size.value", "Unknown")

            val store = JvmFileTileSizeStore(JvmFilePreferencesStore(file))

            assertEquals(TileSizeId.Medium, store.tileSize.value)
        }

    @Test
    fun libraryAndGridSortPersistAndClearByServer() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            val preferences = JvmFilePreferencesStore(file)
            val libraryStore = JvmFileLibrarySortStore(preferences)
            val gridStore = JvmFileGridSortStore(preferences)

            libraryStore.setSort("server-a:library-1", LibrarySortBy.DateCreated, LibrarySortOrder.Descending)
            libraryStore.setSort("server-b:library-2", LibrarySortBy.Name, LibrarySortOrder.Ascending)
            gridStore.setSort("server-a:resume", "DateCreated")
            gridStore.setSort("server-b:next-up", "SortName")

            libraryStore.clearServerScoped("server-a")
            gridStore.clearServerScoped("server-a")

            val restoredPreferences = JvmFilePreferencesStore(file)
            val restoredLibraryStore = JvmFileLibrarySortStore(restoredPreferences)
            val restoredGridStore = JvmFileGridSortStore(restoredPreferences)

            assertNull(restoredLibraryStore.savedSort("server-a:library-1"))
            assertNull(restoredGridStore.savedSort("server-a:resume"))
            assertEquals(
                SavedLibrarySort(
                    sortBy = LibrarySortBy.Name,
                    sortOrder = LibrarySortOrder.Ascending,
                ),
                restoredLibraryStore.savedSort("server-b:library-2"),
            )
            assertEquals("SortName", restoredGridStore.savedSort("server-b:next-up"))
        }

    @Test
    fun malformedLibrarySortFallsBackToNull() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            JvmFilePreferencesStore(file).writeString("library_sort.server-a:library-1", "Unknown:Descending")

            val store = JvmFileLibrarySortStore(JvmFilePreferencesStore(file))

            assertNull(store.savedSort("server-a:library-1"))
        }

    @Test
    fun libraryViewPreferencePersistsAndClearsByServer() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            val store = JvmFileLibraryViewPreferencesStore(JvmFilePreferencesStore(file))

            assertTrue(store.rememberLastView.value)
            store.setRememberLastView(false)
            store.setSavedView("server-a:user:library-1", LibraryInnerView.Genres)
            store.setSavedView("server-b:user:library-2", LibraryInnerView.Collections)
            store.clearServerScoped("server-a")

            val restored = JvmFileLibraryViewPreferencesStore(JvmFilePreferencesStore(file))
            assertFalse(restored.rememberLastView.value)
            assertNull(restored.savedView("server-a:user:library-1"))
            assertEquals(LibraryInnerView.Collections, restored.savedView("server-b:user:library-2"))
        }

    @Test
    fun corruptFileFallsBackAndCanBeOverwritten() =
        runTest {
            val file = tempDir.resolve("preferences.json").toFile()
            Files.writeString(file.toPath(), "{", StandardCharsets.UTF_8)
            val themeStore = JvmFileAppThemeStore(JvmFilePreferencesStore(file))

            assertEquals(AppColorThemeId.Ember, themeStore.theme.value)

            themeStore.setTheme(AppColorThemeId.Ocean)

            assertEquals(AppColorThemeId.Ocean, JvmFileAppThemeStore(JvmFilePreferencesStore(file)).theme.value)
        }
}
