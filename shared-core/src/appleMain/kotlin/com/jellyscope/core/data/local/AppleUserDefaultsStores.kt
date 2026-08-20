// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.TileSizeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

internal class AppleUserDefaultsAppThemeStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : AppThemeStore {
    private val _theme = MutableStateFlow(defaults.readTheme())

    override val theme: StateFlow<AppColorThemeId> = _theme.asStateFlow()

    override suspend fun setTheme(theme: AppColorThemeId) {
        defaults.setObject(theme.name, forKey = APP_THEME_KEY)
        defaults.synchronize()
        _theme.value = theme
    }
}

internal class AppleUserDefaultsTileSizeStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : TileSizeStore {
    private val _tileSize = MutableStateFlow(defaults.readTileSize())

    override val tileSize: StateFlow<TileSizeId> = _tileSize.asStateFlow()

    override suspend fun setTileSize(tileSize: TileSizeId) {
        defaults.setObject(tileSize.name, forKey = TILE_SIZE_KEY)
        defaults.synchronize()
        _tileSize.value = tileSize
    }
}

internal class AppleUserDefaultsPictureInPictureStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : PictureInPictureStore {
    private val _enabled = MutableStateFlow(defaults.readPictureInPictureEnabled())

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        defaults.setObject(enabled.toString(), forKey = PICTURE_IN_PICTURE_ENABLED_KEY)
        defaults.synchronize()
        _enabled.value = enabled
    }
}

internal class AppleUserDefaultsLogCollectionPreferenceStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    synchronizeDefaults: (() -> Boolean)? = null,
) : LogCollectionPreferenceStore {
    private val synchronizeWriter: () -> Boolean = synchronizeDefaults ?: { defaults.synchronize() }
    private val _enabled =
        MutableStateFlow(
            if (defaults.objectForKey(LOG_COLLECTION_ENABLED_KEY) == null) {
                true
            } else {
                defaults.boolForKey(LOG_COLLECTION_ENABLED_KEY)
            },
        )
    private val _verboseLogcatEnabled = MutableStateFlow(defaults.boolForKey(VERBOSE_LOGCAT_ENABLED_KEY))
    private val _playbackInfoAtStartEnabled = MutableStateFlow(defaults.boolForKey(PLAYBACK_INFO_AT_START_ENABLED_KEY))

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val verboseLogcatEnabled: StateFlow<Boolean> = _verboseLogcatEnabled.asStateFlow()
    override val playbackInfoAtStartEnabled: StateFlow<Boolean> = _playbackInfoAtStartEnabled.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        val wasPresent = defaults.objectForKey(LOG_COLLECTION_ENABLED_KEY) != null
        val previousValue = _enabled.value
        defaults.setBool(enabled, forKey = LOG_COLLECTION_ENABLED_KEY)
        if (!synchronizeWriter()) {
            if (wasPresent) {
                defaults.setBool(previousValue, forKey = LOG_COLLECTION_ENABLED_KEY)
            } else {
                defaults.removeObjectForKey(LOG_COLLECTION_ENABLED_KEY)
            }
            synchronizeWriter()
            error("Unable to durably save log collection preference.")
        }
        _enabled.value = enabled
    }

    override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = VERBOSE_LOGCAT_ENABLED_KEY)
        defaults.synchronize()
        _verboseLogcatEnabled.value = enabled
    }

    override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = PLAYBACK_INFO_AT_START_ENABLED_KEY)
        defaults.synchronize()
        _playbackInfoAtStartEnabled.value = enabled
    }
}

internal class AppleUserDefaultsLibrarySortStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val json: Json = Json,
) : LibrarySortStore,
    ServerScopedClearableStore {
    private val mutex = Mutex()

    override fun savedSort(libraryKey: String): SavedLibrarySort? =
        defaults
            .readMap(LIBRARY_SORT_KEY, json)[libraryKey]
            ?.toSavedLibrarySortOrNull()

    override suspend fun setSort(
        libraryKey: String,
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    ) {
        mutex.withLock {
            val updated = defaults.readMap(LIBRARY_SORT_KEY, json).toMutableMap()
            updated[libraryKey] = SavedLibrarySort(sortBy = sortBy, sortOrder = sortOrder).toStoredValue()
            defaults.writeMap(LIBRARY_SORT_KEY, updated, json)
        }
    }

    override suspend fun clearServerScoped() {
        mutex.withLock { defaults.writeMap(LIBRARY_SORT_KEY, emptyMap(), json) }
    }

    override suspend fun clearServerScoped(serverId: String) {
        mutex.withLock {
            val prefix = "$serverId:"
            val updated =
                defaults
                    .readMap(LIBRARY_SORT_KEY, json)
                    .filterKeys { key -> !key.startsWith(prefix) }
            defaults.writeMap(LIBRARY_SORT_KEY, updated, json)
        }
    }
}

internal class AppleUserDefaultsLibraryViewPreferencesStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val json: Json = Json,
) : LibraryViewPreferencesStore,
    ServerScopedClearableStore {
    private val mutex = Mutex()

    private val _rememberLastView = MutableStateFlow(defaults.readRememberLastLibraryView())

    override val rememberLastView: StateFlow<Boolean> = _rememberLastView.asStateFlow()

    override fun lastLibraryId(accountKey: String): String? = defaults.readMap(LAST_LIBRARY_KEY, json)[accountKey]

    override fun savedView(libraryKey: String): LibraryInnerView? =
        defaults.readMap(LIBRARY_VIEW_KEY, json)[libraryKey]?.toLibraryInnerView()

    override suspend fun setRememberLastView(enabled: Boolean) {
        defaults.setObject(enabled.toString(), forKey = REMEMBER_LAST_LIBRARY_VIEW_KEY)
        defaults.synchronize()
        _rememberLastView.value = enabled
    }

    override suspend fun setLastLibraryId(
        accountKey: String,
        libraryId: String,
    ) {
        mutex.withLock {
            val updated = defaults.readMap(LAST_LIBRARY_KEY, json).toMutableMap()
            updated[accountKey] = libraryId
            defaults.writeMap(LAST_LIBRARY_KEY, updated, json)
        }
    }

    override suspend fun setSavedView(
        libraryKey: String,
        view: LibraryInnerView,
    ) {
        mutex.withLock {
            val updated = defaults.readMap(LIBRARY_VIEW_KEY, json).toMutableMap()
            updated[libraryKey] = view.name
            defaults.writeMap(LIBRARY_VIEW_KEY, updated, json)
        }
    }

    override suspend fun clearServerScoped() {
        mutex.withLock {
            defaults.writeMap(LIBRARY_VIEW_KEY, emptyMap(), json)
            defaults.writeMap(LAST_LIBRARY_KEY, emptyMap(), json)
        }
    }

    override suspend fun clearServerScoped(serverId: String) {
        mutex.withLock {
            val updated = defaults.readMap(LIBRARY_VIEW_KEY, json).filterKeys { key -> !key.startsWith("$serverId:") }
            defaults.writeMap(LIBRARY_VIEW_KEY, updated, json)
            val lastLibraryUpdated = defaults.readMap(LAST_LIBRARY_KEY, json).filterKeys { key -> !key.startsWith("$serverId:") }
            defaults.writeMap(LAST_LIBRARY_KEY, lastLibraryUpdated, json)
        }
    }
}

internal class AppleUserDefaultsGridSortStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val json: Json = Json,
) : GridSortStore,
    ServerScopedClearableStore {
    private val mutex = Mutex()

    override fun savedSort(gridKey: String): String? = defaults.readMap(GRID_SORT_KEY, json)[gridKey]

    override suspend fun setSort(
        gridKey: String,
        sortName: String,
    ) {
        mutex.withLock {
            val updated = defaults.readMap(GRID_SORT_KEY, json).toMutableMap()
            updated[gridKey] = sortName
            defaults.writeMap(GRID_SORT_KEY, updated, json)
        }
    }

    override suspend fun clearServerScoped() {
        mutex.withLock { defaults.writeMap(GRID_SORT_KEY, emptyMap(), json) }
    }

    override suspend fun clearServerScoped(serverId: String) {
        mutex.withLock {
            val prefix = "$serverId:"
            val updated =
                defaults
                    .readMap(GRID_SORT_KEY, json)
                    .filterKeys { key -> !key.startsWith(prefix) }
            defaults.writeMap(GRID_SORT_KEY, updated, json)
        }
    }
}

private fun NSUserDefaults.readTheme(): AppColorThemeId = stringForKey(APP_THEME_KEY)?.toAppColorThemeId() ?: AppColorThemeId.Ember

private fun NSUserDefaults.readTileSize(): TileSizeId = stringForKey(TILE_SIZE_KEY)?.toTileSizeId() ?: TileSizeId.Medium

private fun NSUserDefaults.readPictureInPictureEnabled(): Boolean =
    stringForKey(PICTURE_IN_PICTURE_ENABLED_KEY)?.toBooleanStrictOrNull() ?: true

private fun NSUserDefaults.readRememberLastLibraryView(): Boolean =
    stringForKey(REMEMBER_LAST_LIBRARY_VIEW_KEY)?.toBooleanStrictOrNull() ?: true

private fun String.toLibraryInnerView(): LibraryInnerView? = toTolerantEnumOrNull<LibraryInnerView>()

private fun NSUserDefaults.readMap(
    key: String,
    json: Json,
): Map<String, String> =
    stringForKey(key)
        ?.let { encoded ->
            runCatching {
                json.decodeFromString<Map<String, String>>(encoded)
            }.getOrNull()
        }.orEmpty()

private fun NSUserDefaults.writeMap(
    key: String,
    values: Map<String, String>,
    json: Json,
) {
    if (values.isEmpty()) {
        removeObjectForKey(key)
    } else {
        setObject(json.encodeToString(values), forKey = key)
    }
    synchronize()
}

private fun String.toAppColorThemeId(): AppColorThemeId = toTolerantEnumOrNull<AppColorThemeId>() ?: AppColorThemeId.Ember

private fun String.toTileSizeId(): TileSizeId = toTolerantEnumOrNull<TileSizeId>() ?: TileSizeId.Medium

private const val APP_THEME_KEY = "com.jellyscope.app-theme.color"
private const val TILE_SIZE_KEY = "com.jellyscope.tile-size"
private const val PICTURE_IN_PICTURE_ENABLED_KEY = "com.jellyscope.picture-in-picture.enabled"
private const val LOG_COLLECTION_ENABLED_KEY = "com.jellyscope.log-collection.enabled"
private const val VERBOSE_LOGCAT_ENABLED_KEY = "com.jellyscope.log-collection.verbose-logcat-enabled"
private const val PLAYBACK_INFO_AT_START_ENABLED_KEY = "com.jellyscope.log-collection.playback-info-at-start-enabled"
private const val LIBRARY_SORT_KEY = "com.jellyscope.library-sort"
private const val GRID_SORT_KEY = "com.jellyscope.grid-sort"
private const val REMEMBER_LAST_LIBRARY_VIEW_KEY = "com.jellyscope.library-view.remember"
private const val LIBRARY_VIEW_KEY = "com.jellyscope.library-view.saved"
private const val LAST_LIBRARY_KEY = "com.jellyscope.library-view.last-library"
