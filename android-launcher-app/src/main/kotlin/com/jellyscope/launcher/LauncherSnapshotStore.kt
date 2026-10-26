// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.jellyscope.core.data.local.AccountScopedClearableStore
import com.jellyscope.core.data.local.AccountStoreCleaner
import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.RefetchableServerCache
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.local.appendCleanupFailures
import com.jellyscope.core.data.local.throwIfCleanupFailed
import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Normal operations enter the registry gate before this store's mutex. Cleanup already owns that gate. */
internal class LauncherSnapshotStore(
    context: Context,
    private val registry: ServerScopedStoreRegistry,
    private val json: Json,
) : RefetchableServerCache,
    AccountScopedClearableStore {
    private val directory = File(context.noBackupFilesDir, "launcher-rows")
    private val mutex = Mutex()
    private var boundary: LauncherBoundary? = null
    private var snapshot: LauncherSnapshot? = null
    private var generation = 0L
    private var refreshGeneration: Long? = null
    private val _state = MutableStateFlow(LauncherMediaState())
    val state = _state.asStateFlow()
    private val _cleanupPending = MutableStateFlow(false)
    val cleanupPending = _cleanupPending.asStateFlow()

    suspend fun activate(
        target: LauncherBoundary,
        source: String,
        lease: AccountWorkLease,
    ) = guarded(lease) {
        generation += 1
        refreshGeneration = null
        boundary = target
        val restored =
            try {
                val pending = _cleanupPending.value || exists(pendingFile(fileStem(target.account)))
                if (pending) _cleanupPending.value = true
                if (pending) null else read(target.account)
            } catch (failure: Exception) {
                recordCleanupFailure(failure)
                null
            }
        snapshot = restored?.takeIf { it.source == source } ?: LauncherSnapshot(version = 2, source = source)
        val status = if (_cleanupPending.value) LauncherRowStatus.Failed else LauncherRowStatus.Cached
        _state.value =
            LauncherMediaState(
                target.key,
                rows(status),
                configurationStatus =
                    if (snapshot?.layout == null) LauncherConfigurationStatus.Defaults else LauncherConfigurationStatus.Cached,
            )
    }

    suspend fun beginRefresh(
        target: LauncherBoundary,
        lease: AccountWorkLease,
    ): Long? =
        guarded(lease) {
            if (boundary != target) return@guarded null
            generation += 1
            refreshGeneration = null
            try {
                establishPending(fileStem(target.account))
            } catch (failure: Exception) {
                recordCleanupFailure(failure)
                return@guarded null
            }
            refreshGeneration = generation
            _state.value = _state.value.copy(refreshing = true)
            generation
        }

    suspend fun isCurrent(
        target: LauncherBoundary,
        lease: AccountWorkLease,
        work: LauncherRowWork,
    ): Boolean =
        guarded(lease) {
            current(target, work)
        } == true

    suspend fun adoptLayout(
        target: LauncherBoundary,
        lease: AccountWorkLease,
        request: Long,
        policy: String,
        allowedLibraries: Set<String>,
        configuration: LauncherConfigurationResult,
    ): LauncherRefreshLayout? =
        guarded(lease) {
            if (boundary != target || generation != request || refreshGeneration != request) return@guarded null
            val current = snapshot ?: return@guarded null
            val changedPolicy = current.policyFingerprint != policy
            val layout =
                (configuration.layout ?: current.layout)?.validated()?.let { value ->
                    value.copy(rows = value.rows.filter { it.libraryId == null || it.libraryId in allowedLibraries })
                }
            val effective = (layout ?: defaultLauncherLayout()).rows.filter { it.visible }
            val retained =
                if (changedPolicy) {
                    emptyList()
                } else {
                    current.rows.filter { saved -> effective.any { it.id == saved.id && it.queryKey == saved.queryKey } }
                }
            val updated = current.copy(version = 2, policyFingerprint = policy, layout = layout, rows = retained)
            val invalidateArtwork = changedPolicy || retained.size != current.rows.size
            val previousRows = _state.value.rows
            // Keep a safe retry candidate, but publish new layout metadata only after its durable write.
            snapshot = updated
            if (invalidateArtwork) {
                _state.value = _state.value.copy(rows = previousRows.map { it.copy(cards = emptyList()) })
            }
            try {
                write(target.account, updated)
            } catch (failure: Exception) {
                recordCleanupFailure(failure)
                throw failure
            }
            val status =
                when {
                    configuration.layout != null -> LauncherConfigurationStatus.Current
                    configuration.status == LauncherConfigurationStatus.Unavailable && layout != null -> LauncherConfigurationStatus.Cached
                    else -> configuration.status
                }
            _state.value =
                _state.value.copy(
                    rows =
                        rows(LauncherRowStatus.Cached).map { row ->
                            val previous = previousRows.firstOrNull { it.id == row.id && it.layout.queryKey == row.layout.queryKey }
                            if (!changedPolicy && previous != null) row.copy(status = previous.status) else row
                        },
                    configurationStatus = status,
                )
            LauncherRefreshLayout(effective.map { LauncherRowWork(it, it.queryKey, request) }, invalidateArtwork)
        }

    suspend fun failRefresh(
        target: LauncherBoundary,
        lease: AccountWorkLease,
        request: Long,
        configuration: LauncherConfigurationResult,
    ) = guarded(lease) {
        if (boundary == target && generation == request && refreshGeneration == request) {
            val status =
                when (configuration.status) {
                    LauncherConfigurationStatus.Invalid, LauncherConfigurationStatus.Unsupported -> configuration.status
                    else -> if (snapshot?.layout == null) LauncherConfigurationStatus.Unavailable else LauncherConfigurationStatus.Cached
                }
            _state.value =
                _state.value.copy(
                    rows = _state.value.rows.map { it.copy(status = LauncherRowStatus.Failed) },
                    configurationStatus = status,
                )
        }
    }

    suspend fun commit(
        target: LauncherBoundary,
        lease: AccountWorkLease,
        work: LauncherRowWork,
        cards: List<LauncherCard>?,
        status: LauncherRowStatus,
    ) = guarded(lease) {
        if (!current(target, work)) return@guarded
        val current = snapshot ?: return@guarded
        if (cards != null) {
            val updated =
                current.copy(
                    rows =
                        current.rows.filterNot { it.id == work.id } +
                            LauncherRowSnapshot(work.id, work.queryKey, cards, System.currentTimeMillis()),
                )
            if (status == LauncherRowStatus.Denied || cards.isEmpty()) {
                // A denial or successful empty row stays hidden if persistence fails.
                snapshot = updated
                _state.value =
                    _state.value.copy(
                        rows =
                            _state.value.rows.map {
                                if (it.id ==
                                    work.id
                                ) {
                                    it.copy(cards = emptyList(), status = status)
                                } else {
                                    it
                                }
                            },
                    )
            }
            try {
                write(target.account, updated)
            } catch (failure: Exception) {
                recordCleanupFailure(failure)
                throw failure
            }
            snapshot = updated
        }
        _state.value =
            _state.value.copy(
                rows =
                    _state.value.rows.map { row ->
                        if (row.id == work.id) row.copy(cards = cards ?: row.cards, status = status) else row
                    },
            )
    }

    suspend fun finish(
        target: LauncherBoundary,
        lease: AccountWorkLease,
        request: Long,
        success: Boolean,
    ): Boolean =
        guarded(lease) {
            if (boundary == target && generation == request && refreshGeneration == request) {
                val durable = finishPending(target.account)
                refreshGeneration = null
                _state.value =
                    _state.value.copy(
                        refreshing = false,
                        lastRefreshMs = if (success && durable) System.currentTimeMillis() else _state.value.lastRefreshMs,
                    )
                durable
            } else {
                false
            }
        } == true

    suspend fun rejectAll(
        target: LauncherBoundary,
        lease: AccountWorkLease,
        request: Long,
    ) = guarded(lease) {
        if (boundary == target && generation == request && refreshGeneration == request) {
            generation += 1
            refreshGeneration = null
            snapshot = snapshot?.copy(rows = emptyList())
            _state.value = _state.value.copy(rows = rows(LauncherRowStatus.Denied), refreshing = false)
            finishPending(target.account)
        }
    }

    suspend fun hide() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                generation += 1
                refreshGeneration = null
                boundary = null
                snapshot = null
                _state.value = LauncherMediaState()
            }
        }

    override suspend fun clearServerScoped() = clearMatching { true }

    override suspend fun clearServerScoped(serverId: String) = clearMatching { it.startsWith("${launcherHash(serverId)}-") }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) = clearMatching { it.startsWith(fileStem(accountIdentity)) }

    private suspend fun clearMatching(matches: (String) -> Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (boundary?.account?.let { matches(fileStem(it)) } != false) {
                    generation += 1
                    refreshGeneration = null
                    boundary = null
                    snapshot = null
                    _state.value = LauncherMediaState()
                }
                val failures = mutableListOf<Throwable>()
                try {
                    val files = directory.listFiles()
                    check(files != null || !exists(directory))
                    val groups = files.orEmpty().filter { matches(it.name) }.groupBy { it.name.substringBefore('.') }
                    groups.forEach { (stem, entries) ->
                        try {
                            establishPending(stem)
                            val marker = pendingFile(stem)
                            val artifacts = (snapshotArtifacts(stem) + entries.filter { it != marker }).distinct()
                            val removalFailures = mutableListOf<Throwable>()
                            artifacts.forEach { file ->
                                try {
                                    deleteVerified(file)
                                } catch (failure: Exception) {
                                    appendCleanupFailures(removalFailures, failure)
                                }
                            }
                            try {
                                syncDirectory()
                            } catch (failure: Exception) {
                                appendCleanupFailures(removalFailures, failure)
                            }
                            throwIfCleanupFailed(removalFailures)
                            removePending(stem)
                        } catch (failure: Exception) {
                            appendCleanupFailures(failures, failure)
                        }
                    }
                } catch (failure: Exception) {
                    appendCleanupFailures(failures, failure)
                }
                try {
                    _cleanupPending.value = failures.isNotEmpty() || hasPendingMarkers()
                } catch (failure: Exception) {
                    _cleanupPending.value = true
                    appendCleanupFailures(failures, failure)
                }
                if (failures.isNotEmpty()) {
                    launcherFailure("snapshot-cleanup", failures.first())
                }
                throwIfCleanupFailed(failures)
            }
        }

    private suspend fun <T> guarded(
        lease: AccountWorkLease,
        block: suspend () -> T,
    ): T? =
        withContext(Dispatchers.IO) {
            registry.withGuardedLease(lease) { mutex.withLock { block() } }
        }

    private fun current(
        target: LauncherBoundary,
        work: LauncherRowWork,
    ): Boolean =
        boundary == target &&
            generation == work.generation &&
            refreshGeneration == work.generation &&
            effectiveRows().any { it.id == work.id && it.queryKey == work.queryKey }

    private fun effectiveRows(): List<LauncherLayoutRow> = (snapshot?.layout ?: defaultLauncherLayout()).rows.filter { it.visible }

    private fun rows(status: LauncherRowStatus): List<LauncherRow> =
        effectiveRows().map { layout ->
            val saved = snapshot?.rows?.firstOrNull { it.id == layout.id && it.queryKey == layout.queryKey }
            LauncherRow(
                layout = layout,
                localTitleResource = if (snapshot?.layout == null) defaultLauncherTitle(layout.id) else null,
                cards = saved?.cards.orEmpty(),
                status = if (saved == null && status == LauncherRowStatus.Cached) LauncherRowStatus.Loading else status,
            )
        }

    private fun read(account: AccountIdentity): LauncherSnapshot? {
        val file = atomicFile(account)
        if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) return null
        return try {
            val bytes = ByteArray(MAX_SNAPSHOT_BYTES + 1)
            val count =
                file.openRead().use { input ->
                    var used = 0
                    while (used < bytes.size) {
                        val read = input.read(bytes, used, bytes.size - used)
                        if (read < 0) break
                        used += read
                    }
                    used
                }
            require(count <= MAX_SNAPSHOT_BYTES)
            val value = json.decodeFromString<LauncherSnapshot>(bytes.decodeToString(0, count, throwOnInvalidSequence = true))
            require(value.version == 1 || value.version == 2)
            if (value.version == 1) {
                require(value.layout == null && value.rows.size <= 4)
            }
            validatedSnapshot(value.copy(version = 2))
        } catch (exception: Exception) {
            launcherFailure("snapshot-read", exception)
            null
        }
    }

    private fun write(
        account: AccountIdentity,
        value: LauncherSnapshot,
    ) {
        check(directory.isDirectory || directory.mkdirs())
        val bytes = json.encodeToString(validatedSnapshot(value)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SNAPSHOT_BYTES)
        val file = atomicFile(account)
        val stream = file.startWrite()
        var finished = false
        try {
            stream.use {
                it.write(bytes)
                it.fd.sync()
                file.finishWrite(it)
            }
            finished = true
            verifyContents(file.baseFile, bytes)
            snapshotArtifacts(fileStem(account)).drop(1).forEach(::deleteVerified)
            syncDirectory()
            verifyContents(file.baseFile, bytes)
        } catch (exception: Exception) {
            if (!finished) file.failWrite(stream)
            throw exception
        }
    }

    /** The marker covers the whole refresh, including a crash before a denied response is handled. */
    private fun establishPending(stem: String) {
        val createdDirectory = !directory.isDirectory
        check(directory.isDirectory || directory.mkdirs())
        val marker = pendingFile(stem)
        marker.outputStream().use {
            it.write(pendingContent)
            it.fd.sync()
        }
        verifyContents(marker, pendingContent)
        syncDirectory()
        if (createdDirectory) syncDirectory(checkNotNull(directory.parentFile))
    }

    private fun finishPending(account: AccountIdentity): Boolean =
        try {
            write(account, checkNotNull(snapshot))
            removePending(fileStem(account))
            _cleanupPending.value = hasPendingMarkers()
            true
        } catch (failure: Exception) {
            recordCleanupFailure(failure)
            false
        }

    private fun removePending(stem: String) {
        deleteVerified(pendingFile(stem))
        syncDirectory()
    }

    private fun recordCleanupFailure(failure: Exception) {
        _cleanupPending.value = true
        _state.value =
            _state.value.copy(
                refreshing = false,
                rows =
                    _state.value.rows.map {
                        if (it.status ==
                            LauncherRowStatus.Denied
                        ) {
                            it
                        } else {
                            it.copy(status = LauncherRowStatus.Failed)
                        }
                    },
            )
        launcherFailure("snapshot-persistence", failure)
    }

    private fun deleteVerified(file: File) {
        if (exists(file)) check(file.delete())
        check(!exists(file))
    }

    private fun exists(file: File): Boolean =
        try {
            Os.lstat(file.path)
            true
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.ENOENT) false else throw failure
        }

    private fun verifyContents(
        file: File,
        expected: ByteArray,
    ) {
        check(exists(file) && file.isFile && file.length() == expected.size.toLong())
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var offset = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(offset + count <= expected.size)
                repeat(count) { check(buffer[it] == expected[offset + it]) }
                offset += count
            }
            check(offset == expected.size)
            input.fd.sync()
        }
    }

    private fun syncDirectory(target: File = directory) {
        check(target.isDirectory)
        val descriptor = Os.open(target.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun pendingFile(stem: String) = File(directory, "$stem.pending")

    private fun hasPendingMarkers(): Boolean {
        val files = directory.listFiles()
        check(files != null || !exists(directory))
        return files.orEmpty().any { it.name.endsWith(".pending") }
    }

    private fun snapshotArtifacts(stem: String): List<File> =
        listOf(File(directory, "$stem.json"), File(directory, "$stem.json.bak"), File(directory, "$stem.json.new"))

    private fun atomicFile(account: AccountIdentity) = AtomicFile(File(directory, "${fileStem(account)}.json"))

    private fun fileStem(account: AccountIdentity) = "${launcherHash(account.serverId)}-${launcherAccountKey(account)}"

    private fun validatedSnapshot(value: LauncherSnapshot): LauncherSnapshot {
        require(value.version == 2 && value.source.matches(Regex("[a-f0-9]{64}")))
        require(value.policyFingerprint == null || value.policyFingerprint.matches(Regex("[a-f0-9]{64}")))
        value.layout?.validated()
        val effective = (value.layout ?: defaultLauncherLayout()).rows.filter { it.visible }
        require(value.rows.size <= MAX_LAYOUT_ROWS && value.rows.distinctBy { it.id }.size == value.rows.size)
        require(
            value.rows.all { saved ->
                val row = effective.firstOrNull { it.id == saved.id && it.queryKey == saved.queryKey }
                row != null &&
                    saved.updatedAtMs >= 0 &&
                    saved.cards.size <= row.limit &&
                    saved.cards.distinctBy { it.id }.size == saved.cards.size &&
                    saved.cards.all(::validCard)
            },
        )
        return value
    }

    private companion object {
        const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
        val pendingContent = byteArrayOf(1)

        fun validCard(card: LauncherCard): Boolean =
            card.id.isNotBlank() &&
                card.id.length <= 256 &&
                card.title.length <= 300 &&
                (card.imageTag?.length ?: 0) <= 256 &&
                (card.primaryTag?.length ?: 0) <= 256 &&
                (card.backdropTag?.length ?: 0) <= 256 &&
                (card.overview?.length ?: 0) <= MAX_HERO_OVERVIEW &&
                (card.seriesName?.length ?: 0) <= 300 &&
                (card.year == null || card.year in 1..9999) &&
                (card.runtimeMinutes == null || card.runtimeMinutes in 0..MAX_HERO_RUNTIME_MINUTES) &&
                (card.officialRating?.length ?: 0) <= 64 &&
                (card.season == null || card.season >= 0) &&
                (card.episode == null || card.episode >= 0) &&
                (card.progress == null || card.progress.isFinite() && card.progress in 0f..1f)
    }
}

internal class LauncherAccountStoreCleaner(
    private val sessionStore: SessionStore,
    private val snapshots: LauncherSnapshotStore,
    private val images: LauncherImages,
) : AccountStoreCleaner {
    private val _cleanupPending = MutableStateFlow(false)
    val cleanupPending =
        combine(_cleanupPending, snapshots.cleanupPending) { logoutFailure, cacheFailure ->
            logoutFailure || cacheFailure
        }

    fun markLogoutFailed() {
        _cleanupPending.value = true
    }

    override suspend fun clearAll() {
        val failures = mutableListOf<Throwable>()
        var credentialsCleared = false
        try {
            sessionStore.clearSession()
            credentialsCleared = true
        } catch (failure: Throwable) {
            appendCleanupFailures(failures, failure)
        }
        if (credentialsCleared) {
            try {
                sessionStore.clearLogoutPending()
            } catch (failure: Throwable) {
                appendCleanupFailures(failures, failure)
            }
        }
        attemptAll(failures, { snapshots.clearServerScoped() }, { images.clearServerScoped() })
        _cleanupPending.value = failures.isNotEmpty()
        throwIfCleanupFailed(failures)
    }

    override suspend fun clearServer(serverId: String) {
        val failures = mutableListOf<Throwable>()
        attemptAll(failures, {
            sessionStore.clearServerScoped(serverId)
        }, { snapshots.clearServerScoped(serverId) }, { images.clearServerScoped(serverId) })
        throwIfCleanupFailed(failures)
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        val failures = mutableListOf<Throwable>()
        attemptAll(failures, {
            snapshots.clearAccount(accountIdentity)
        }, { images.clearAccount(accountIdentity) }, { sessionStore.clearAccount(accountIdentity) })
        throwIfCleanupFailed(failures)
    }

    private suspend fun attemptAll(
        failures: MutableList<Throwable>,
        vararg operations: suspend () -> Unit,
    ) {
        operations.forEach { operation ->
            try {
                operation()
            } catch (failure: Throwable) {
                appendCleanupFailures(failures, failure)
            }
        }
    }
}
