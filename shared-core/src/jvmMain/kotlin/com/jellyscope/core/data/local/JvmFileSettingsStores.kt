// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.TileSizeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class JvmFilePreferencesStore(
    private val file: File,
    private val json: Json = Json,
) {
    private val mutex = Mutex()
    private val lock = Any()
    private val values = readMapFromDisk().toMutableMap()

    fun readString(key: String): String? =
        synchronized(lock) {
            values[key]
        }

    suspend fun writeString(
        key: String,
        value: String,
    ) {
        writeStrings(mapOf(key to value))
    }

    suspend fun writeStrings(valuesToWrite: Map<String, String>) {
        mutex.withLock {
            val snapshot =
                synchronized(lock) {
                    values.toMutableMap().apply { putAll(valuesToWrite) }.toMap()
                }
            writeMap(snapshot)
            synchronized(lock) {
                values.clear()
                values.putAll(snapshot)
            }
        }
    }

    suspend fun clearKeys(prefix: String) {
        mutex.withLock {
            val snapshot =
                synchronized(lock) {
                    val matchingKeys = values.keys.filter { key -> key.startsWith(prefix) }
                    values.toMutableMap().apply { matchingKeys.forEach(::remove) }.toMap()
                }
            writeMap(snapshot)
            synchronized(lock) {
                values.clear()
                values.putAll(snapshot)
            }
        }
    }

    private fun readMapFromDisk(): Map<String, String> {
        if (!file.exists()) {
            return emptyMap()
        }

        return runCatching {
            json.decodeFromString<Map<String, String>>(Files.readString(file.toPath(), StandardCharsets.UTF_8))
        }.getOrDefault(emptyMap())
    }

    private suspend fun writeMap(values: Map<String, String>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()

            if (values.isEmpty()) {
                Files.deleteIfExists(file.toPath())
                return@withContext
            }

            val tempFile = File(file.parentFile ?: File("."), "${file.name}.tmp")
            Files.writeString(tempFile.toPath(), json.encodeToString(values), StandardCharsets.UTF_8)
            moveReplacing(tempFile, file)
        }
    }

    private fun moveReplacing(
        source: File,
        target: File,
    ) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        val moved =
            runCatching {
                Files.move(
                    sourcePath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess

        if (!moved) {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal class JvmFileAppThemeStore(
    private val preferences: JvmFilePreferencesStore,
) : AppThemeStore {
    private val _theme = MutableStateFlow(preferences.readTheme())

    override val theme: StateFlow<AppColorThemeId> = _theme.asStateFlow()

    override suspend fun setTheme(theme: AppColorThemeId) {
        preferences.writeString(APP_THEME_KEY, theme.name)
        _theme.value = theme
    }
}

internal class JvmFileTileSizeStore(
    private val preferences: JvmFilePreferencesStore,
) : TileSizeStore {
    private val _tileSize = MutableStateFlow(preferences.readTileSize())

    override val tileSize: StateFlow<TileSizeId> = _tileSize.asStateFlow()

    override suspend fun setTileSize(tileSize: TileSizeId) {
        preferences.writeString(TILE_SIZE_KEY, tileSize.name)
        _tileSize.value = tileSize
    }
}

internal class JvmFilePictureInPictureStore(
    private val preferences: JvmFilePreferencesStore,
) : PictureInPictureStore {
    private val _enabled = MutableStateFlow(preferences.readPictureInPictureEnabled())

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        preferences.writeString(PICTURE_IN_PICTURE_ENABLED_KEY, enabled.toString())
        _enabled.value = enabled
    }
}

internal class JvmFileLogCollectionPreferenceStore(
    private val preferences: JvmFilePreferencesStore,
) : LogCollectionPreferenceStore {
    private val _enabled = MutableStateFlow(preferences.readLogCollectionEnabled())
    private val _verboseLogcatEnabled = MutableStateFlow(preferences.readVerboseLogcatEnabled())
    private val _playbackInfoAtStartEnabled = MutableStateFlow(preferences.readPlaybackInfoAtStartEnabled())

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val verboseLogcatEnabled: StateFlow<Boolean> = _verboseLogcatEnabled.asStateFlow()
    override val playbackInfoAtStartEnabled: StateFlow<Boolean> = _playbackInfoAtStartEnabled.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        preferences.writeString(LOG_COLLECTION_ENABLED_KEY, enabled.toString())
        _enabled.value = enabled
    }

    override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
        preferences.writeString(VERBOSE_LOGCAT_ENABLED_KEY, enabled.toString())
        _verboseLogcatEnabled.value = enabled
    }

    override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        preferences.writeString(PLAYBACK_INFO_AT_START_ENABLED_KEY, enabled.toString())
        _playbackInfoAtStartEnabled.value = enabled
    }
}

internal class JvmFileLibrarySortStore(
    private val preferences: JvmFilePreferencesStore,
) : LibrarySortStore,
    ServerScopedClearableStore {
    override fun savedSort(libraryKey: String): SavedLibrarySort? =
        preferences
            .readString("$LIBRARY_SORT_KEY_PREFIX$libraryKey")
            ?.toSavedLibrarySortOrNull()

    override suspend fun setSort(
        libraryKey: String,
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    ) {
        preferences.writeString(
            "$LIBRARY_SORT_KEY_PREFIX$libraryKey",
            SavedLibrarySort(sortBy = sortBy, sortOrder = sortOrder).toStoredValue(),
        )
    }

    override suspend fun clearServerScoped() {
        preferences.clearKeys(LIBRARY_SORT_KEY_PREFIX)
    }

    override suspend fun clearServerScoped(serverId: String) {
        preferences.clearKeys("$LIBRARY_SORT_KEY_PREFIX$serverId:")
    }
}

internal class JvmFileLibraryViewPreferencesStore(
    private val preferences: JvmFilePreferencesStore,
) : LibraryViewPreferencesStore,
    ServerScopedClearableStore {
    private val _rememberLastView = MutableStateFlow(preferences.readRememberLastLibraryView())

    override val rememberLastView: StateFlow<Boolean> = _rememberLastView.asStateFlow()

    override fun lastLibraryId(accountKey: String): String? = preferences.readString("$LAST_LIBRARY_KEY_PREFIX$accountKey")

    override fun savedView(libraryKey: String): LibraryInnerView? =
        preferences.readString("$LIBRARY_VIEW_KEY_PREFIX$libraryKey")?.toLibraryInnerView()

    override suspend fun setRememberLastView(enabled: Boolean) {
        preferences.writeString(REMEMBER_LAST_LIBRARY_VIEW_KEY, enabled.toString())
        _rememberLastView.value = enabled
    }

    override suspend fun setLastLibraryId(
        accountKey: String,
        libraryId: String,
    ) {
        preferences.writeString("$LAST_LIBRARY_KEY_PREFIX$accountKey", libraryId)
    }

    override suspend fun setSavedView(
        libraryKey: String,
        view: LibraryInnerView,
    ) {
        preferences.writeString("$LIBRARY_VIEW_KEY_PREFIX$libraryKey", view.name)
    }

    override suspend fun clearServerScoped() {
        preferences.clearKeys(LIBRARY_VIEW_KEY_PREFIX)
        preferences.clearKeys(LAST_LIBRARY_KEY_PREFIX)
    }

    override suspend fun clearServerScoped(serverId: String) {
        preferences.clearKeys("$LIBRARY_VIEW_KEY_PREFIX$serverId:")
        preferences.clearKeys("$LAST_LIBRARY_KEY_PREFIX$serverId:")
    }
}

internal class JvmFileGridSortStore(
    private val preferences: JvmFilePreferencesStore,
) : GridSortStore,
    ServerScopedClearableStore {
    override fun savedSort(gridKey: String): String? = preferences.readString("$GRID_SORT_KEY_PREFIX$gridKey")

    override suspend fun setSort(
        gridKey: String,
        sortName: String,
    ) {
        preferences.writeString("$GRID_SORT_KEY_PREFIX$gridKey", sortName)
    }

    override suspend fun clearServerScoped() {
        preferences.clearKeys(GRID_SORT_KEY_PREFIX)
    }

    override suspend fun clearServerScoped(serverId: String) {
        preferences.clearKeys("$GRID_SORT_KEY_PREFIX$serverId:")
    }
}

private fun JvmFilePreferencesStore.readTheme(): AppColorThemeId = readString(APP_THEME_KEY)?.toAppColorThemeId() ?: AppColorThemeId.Ember

private fun JvmFilePreferencesStore.readTileSize(): TileSizeId = readString(TILE_SIZE_KEY)?.toTileSizeId() ?: TileSizeId.Medium

private fun JvmFilePreferencesStore.readPictureInPictureEnabled(): Boolean =
    readString(PICTURE_IN_PICTURE_ENABLED_KEY)?.toBooleanStrictOrNull() ?: true

private fun JvmFilePreferencesStore.readLogCollectionEnabled(): Boolean =
    readString(LOG_COLLECTION_ENABLED_KEY)?.toBooleanStrictOrNull() ?: false

private fun JvmFilePreferencesStore.readVerboseLogcatEnabled(): Boolean =
    readString(VERBOSE_LOGCAT_ENABLED_KEY)?.toBooleanStrictOrNull() ?: false

private fun JvmFilePreferencesStore.readPlaybackInfoAtStartEnabled(): Boolean =
    readString(PLAYBACK_INFO_AT_START_ENABLED_KEY)?.toBooleanStrictOrNull() ?: false

private fun JvmFilePreferencesStore.readRememberLastLibraryView(): Boolean =
    readString(REMEMBER_LAST_LIBRARY_VIEW_KEY)?.toBooleanStrictOrNull() ?: true

private fun String.toLibraryInnerView(): LibraryInnerView? = toTolerantEnumOrNull<LibraryInnerView>()

private fun String.toAppColorThemeId(): AppColorThemeId = toTolerantEnumOrNull<AppColorThemeId>() ?: AppColorThemeId.Ember

private fun String.toTileSizeId(): TileSizeId = toTolerantEnumOrNull<TileSizeId>() ?: TileSizeId.Medium

private const val APP_THEME_KEY = "app_theme.color"
private const val TILE_SIZE_KEY = "tile_size.value"
private const val PICTURE_IN_PICTURE_ENABLED_KEY = "picture_in_picture.enabled"
private const val LOG_COLLECTION_ENABLED_KEY = "log_collection.enabled"
private const val VERBOSE_LOGCAT_ENABLED_KEY = "log_collection.verbose_logcat_enabled"
private const val PLAYBACK_INFO_AT_START_ENABLED_KEY = "log_collection.playback_info_at_start_enabled"
private const val LIBRARY_SORT_KEY_PREFIX = "library_sort."
private const val GRID_SORT_KEY_PREFIX = "grid_sort."
private const val REMEMBER_LAST_LIBRARY_VIEW_KEY = "library_view.remember"
private const val LIBRARY_VIEW_KEY_PREFIX = "library_view.saved."
private const val LAST_LIBRARY_KEY_PREFIX = "library_view.last_library."
