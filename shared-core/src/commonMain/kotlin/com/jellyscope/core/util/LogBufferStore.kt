// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.DIAGNOSTIC_MAX_BYTES
import com.jellyscope.core.data.local.DIAGNOSTIC_MAX_ENTRIES
import com.jellyscope.core.data.local.DiagnosticBreadcrumbRecord
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStorageState
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStoredRecord
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.random.Random

data class LogBufferSize(
    val entryCount: Int = 0,
    val byteCount: Int = 0,
)

/**
 * The acknowledgement token is deliberately local bookkeeping. It is never included in the
 * uploaded text and contains no user, server, path, or timestamp data.
 */
@ConsistentCopyVisibility
data class LogBufferAcknowledgement internal constructor(
    internal val maxSequence: Long,
    internal val representedTruncationRevision: Long,
    internal val fallbackRecordIds: Set<String>,
    internal val maxGeneration: Long,
    internal val historyEpoch: Long,
)

data class LogBufferSnapshot(
    val text: String,
    val maxGeneration: Long,
    val acknowledgement: LogBufferAcknowledgement =
        LogBufferAcknowledgement(
            maxSequence = 0L,
            representedTruncationRevision = 0L,
            fallbackRecordIds = emptySet(),
            maxGeneration = maxGeneration,
            historyEpoch = 0L,
        ),
)

/**
 * App-global safe capture owner. Kermit's synchronous writer performs only validation, bounded
 * UTF-8 work, and a bounded [Channel.trySend]. Persistence, hydration, trimming, and barriers
 * belong to the serialized actor and therefore never block a player, network, or UI caller.
 */
class LogBufferStore(
    private val preferenceStore: LogCollectionPreferenceStore,
    private val breadcrumbStore: DiagnosticBreadcrumbStore? = null,
    private val ownerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val actorDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    private val commands = Channel<Command>(INGRESS_CAPACITY)
    private val ingressOverflowed = atomic(false)
    private val nextGeneration = atomic(0L)
    private val recordIdPrefix = Random.nextLong().toString(16)
    private val _size = MutableStateFlow(LogBufferSize())
    private val entries = ArrayDeque<BufferedEntry>()
    private var byteCount = 0
    private var truncationRevision = 0L
    private var truncationMarkerNeedsPersistence = false
    private var disabled = false
    private var historyEpoch = 0L
    private var storageNeedsReconciliation = false

    val size: StateFlow<LogBufferSize> = _size.asStateFlow()

    /** Reads the authoritative preference without waiting for the actor. */
    internal fun isCollectionEnabled(): Boolean = preferenceStore.enabled.value

    val logWriter: LogWriter =
        object : LogWriter() {
            override fun log(
                severity: Severity,
                message: String,
                tag: String,
                throwable: Throwable?,
            ) {
                append(tag = tag, severity = severity, message = message)
            }
        }

    init {
        ownerScope.launch(actorDispatcher) { runActor() }
        ownerScope.launch(actorDispatcher) {
            preferenceStore.enabled.collect {
                commands.send(Command.PreferenceChanged)
            }
        }
    }

    /** Synchronous Kermit ingress; this function intentionally has no I/O or suspension. */
    fun append(
        tag: String,
        severity: Severity,
        message: String,
    ) {
        if (!preferenceStore.enabled.value) return
        val safeMessage = LogScrubber.capture(tag, message) ?: return
        val line = boundedLine("${severity.name} $tag $safeMessage")
        val generation = nextGeneration.incrementAndGet()
        val record =
            PendingRecord(
                generation = generation,
                recordId = "$recordIdPrefix-$generation",
                line = line,
                byteCount = line.encodeToByteArray().size,
            )
        if (commands.trySend(Command.Append(record)).isFailure) {
            ingressOverflowed.value = true
        }
    }

    suspend fun snapshot(): LogBufferSnapshot {
        val result = CompletableDeferred<LogBufferSnapshot>()
        commands.send(Command.Snapshot(result))
        return result.await()
    }

    /** Clears local and durable breadcrumb rows without deleting the database file. */
    suspend fun clear() {
        val result = CompletableDeferred<Unit>()
        commands.send(Command.Clear(result))
        result.await()
    }

    /** Clears only data represented by an upload acknowledgement token. */
    suspend fun clearThrough(acknowledgement: LogBufferAcknowledgement) {
        val result = CompletableDeferred<Unit>()
        commands.send(Command.ClearThrough(acknowledgement, result))
        result.await()
    }

    /** Compatibility bridge for existing focused tests; production uses the typed token. */
    suspend fun clearThrough(maxGeneration: Long) {
        val result = CompletableDeferred<Unit>()
        commands.send(Command.ClearThroughGeneration(maxGeneration, result))
        result.await()
    }

    /**
     * Awaits the disabled tombstone's purge barrier. The preference action calls this only after
     * the platform store has durably published false, so a failed purge cannot expose old rows.
     */
    suspend fun purgeForDisabledCollection() {
        val result = CompletableDeferred<Unit>()
        commands.send(Command.Purge(result))
        result.await()
    }

    private suspend fun runActor() {
        var lookahead: Command? = null
        try {
            initialize()
            while (true) {
                val command = lookahead ?: commands.receiveCatching().getOrNull() ?: return
                lookahead = null
                when (command) {
                    is Command.Append -> {
                        val batch = ArrayList<PendingRecord>(APPEND_BATCH_SIZE)
                        batch += command.record
                        while (batch.size < APPEND_BATCH_SIZE) {
                            val next = commands.tryReceive().getOrNull() ?: break
                            if (next is Command.Append) {
                                batch += next.record
                            } else {
                                lookahead = next
                                break
                            }
                        }
                        processAppends(batch)
                    }

                    is Command.Snapshot -> {
                        if (preferenceStore.enabled.value) {
                            flushPending()
                            command.result.complete(buildSnapshot())
                        } else {
                            purgeBestEffort()
                            command.result.complete(emptySnapshot())
                        }
                    }

                    is Command.Clear -> {
                        try {
                            clearMemory()
                            breadcrumbStore?.clear()
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Throwable) {
                            storageNeedsReconciliation = true
                            command.result.completeExceptionally(exception)
                            continue
                        }
                        command.result.complete(Unit)
                    }

                    is Command.ClearThrough -> {
                        try {
                            clearThroughToken(command.acknowledgement)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Throwable) {
                            command.result.completeExceptionally(exception)
                            continue
                        }
                        command.result.complete(Unit)
                    }

                    is Command.ClearThroughGeneration -> {
                        try {
                            val acknowledgement =
                                acknowledgementForGeneration(command.maxGeneration)
                            clearThroughToken(acknowledgement)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Throwable) {
                            command.result.completeExceptionally(exception)
                            continue
                        }
                        command.result.complete(Unit)
                    }

                    is Command.Purge -> {
                        try {
                            purgeStrict()
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Throwable) {
                            command.result.completeExceptionally(exception)
                            continue
                        }
                        command.result.complete(Unit)
                    }

                    is Command.PreferenceChanged -> {
                        if (preferenceStore.enabled.value) {
                            disabled = false
                        } else {
                            disabled = true
                            purgeBestEffort()
                        }
                    }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        }
    }

    private suspend fun initialize() {
        disabled = !preferenceStore.enabled.value
        if (disabled) {
            purgeBestEffort()
            return
        }
        hydrateBestEffort()
    }

    private suspend fun hydrateBestEffort() {
        reconcileStorage()
        publishSize()
    }

    /**
     * Reconciles the actor's fallback with durable rows. This is deliberately separate from
     * append flushing: a failed open or an uncertain transaction must be retried by a snapshot
     * even when no append is waiting in the ingress queue.
     */
    private suspend fun reconcileStorage(): Boolean {
        val store =
            breadcrumbStore ?: run {
                storageNeedsReconciliation = false
                return true
            }
        return try {
            val state = store.hydrate()
            if (!preferenceStore.enabled.value) {
                disabled = true
                purgeBestEffort()
                false
            } else {
                applyStorageState(state, submittedRecordIds = emptySet())
                storageNeedsReconciliation = false
                true
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Throwable) {
            // Ambiguous open errors are fail-closed: retain a bounded fallback and retry at the
            // next serialized barrier. No file deletion is authorized here.
            storageNeedsReconciliation = true
            false
        }
    }

    private suspend fun processAppends(batch: List<PendingRecord>) {
        if (!preferenceStore.enabled.value) {
            disabled = true
            return
        }
        disabled = false
        batch.forEach { record ->
            entries.addLast(
                BufferedEntry(
                    generation = record.generation,
                    recordId = record.recordId,
                    line = record.line,
                    byteCount = record.byteCount,
                    persistedSequence = null,
                ),
            )
            byteCount += record.byteCount
        }
        trimMemory()
        flushPending()
        publishSize()
    }

    private suspend fun flushPending() {
        val store =
            breadcrumbStore ?: run {
                if (ingressOverflowed.getAndSet(false)) {
                    truncationRevision += 1L
                }
                storageNeedsReconciliation = false
                return
            }
        if (storageNeedsReconciliation) reconcileStorage()
        val pending = entries.filter { entry -> entry.persistedSequence == null }
        val overflowed = ingressOverflowed.getAndSet(false)
        if (overflowed) {
            truncationRevision += 1L
            truncationMarkerNeedsPersistence = true
        }
        val persistTruncationMarker = truncationMarkerNeedsPersistence
        if (pending.isEmpty() && !persistTruncationMarker) return
        try {
            val state =
                store.appendAndTrim(
                    records =
                        pending.map { entry ->
                            DiagnosticBreadcrumbRecord(
                                recordId = entry.recordId,
                                line = entry.line,
                                byteCount = entry.byteCount,
                            )
                        },
                    ingressOverflowed = persistTruncationMarker,
                    targetTruncationRevision = truncationRevision.takeIf { persistTruncationMarker },
                )
            applyStorageState(
                state = state,
                submittedRecordIds = pending.mapTo(HashSet()) { entry -> entry.recordId },
            )
            if (state.truncationRevision >= truncationRevision) {
                truncationMarkerNeedsPersistence = false
            }
            storageNeedsReconciliation = false
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Throwable) {
            storageNeedsReconciliation = true
            // Keep all pending records in the bounded fallback. The next barrier retries storage.
        }
    }

    private fun applyStorageState(
        state: DiagnosticBreadcrumbStorageState,
        submittedRecordIds: Set<String>,
    ) {
        val storedById = state.records.associateBy { record -> record.recordId }
        val retained = ArrayDeque<BufferedEntry>()
        val entriesById = entries.associateBy { entry -> entry.recordId }

        // Durable sequence order is authoritative. It keeps an older committed row ahead of a
        // newer fallback row after a failed or uncertain append transaction.
        state.records
            .sortedBy { record -> record.sequence }
            .forEach { stored ->
                val current = entriesById[stored.recordId]
                retained.addLast(
                    current?.withSequence(stored.sequence)
                        ?: stored.toBufferedEntry(nextGeneration.incrementAndGet()),
                )
            }

        // Only actor rows that are still unpersisted may remain as fallback. A previously
        // persisted row missing from authoritative state was trimmed or acknowledged; a row
        // submitted to storage but absent after a committed retry is likewise not duplicated.
        val storedIds = storedById.keys
        entries.forEach { entry ->
            if (
                entry.recordId !in storedIds &&
                entry.persistedSequence == null &&
                entry.recordId !in submittedRecordIds
            ) {
                retained.addLast(entry)
            }
        }
        entries.clear()
        entries.addAll(retained)
        byteCount = entries.sumOf { entry -> entry.byteCount }
        truncationRevision = maxOf(truncationRevision, state.truncationRevision)
        if (state.truncationRevision >= truncationRevision) {
            truncationMarkerNeedsPersistence = false
        }
        trimMemory()
    }

    private suspend fun clearThroughToken(acknowledgement: LogBufferAcknowledgement) {
        if (acknowledgement.historyEpoch != historyEpoch) return
        val store = breadcrumbStore
        try {
            if (store != null) {
                store.clearThrough(
                    maxSequence = acknowledgement.maxSequence,
                    representedTruncationRevision = acknowledgement.representedTruncationRevision,
                    fallbackRecordIds = acknowledgement.fallbackRecordIds,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            storageNeedsReconciliation = true
            throw exception
        }
        val retained = ArrayDeque<BufferedEntry>()
        entries.forEach { entry ->
            val sent =
                entry.recordId in acknowledgement.fallbackRecordIds ||
                    (entry.persistedSequence != null && entry.persistedSequence <= acknowledgement.maxSequence)
            if (sent) {
                byteCount -= entry.byteCount
            } else {
                retained.addLast(entry)
            }
        }
        entries.clear()
        entries.addAll(retained)
        if (truncationRevision == acknowledgement.representedTruncationRevision) {
            truncationRevision = 0L
            truncationMarkerNeedsPersistence = false
        }
        publishSize()
    }

    private fun acknowledgementForGeneration(maxGeneration: Long): LogBufferAcknowledgement {
        val selected = entries.filter { entry -> entry.generation <= maxGeneration }
        return LogBufferAcknowledgement(
            maxSequence = selected.mapNotNull { entry -> entry.persistedSequence }.maxOrNull() ?: 0L,
            // The legacy generation-only bridge predates truncation revisions. It must never
            // acknowledge a marker created after the caller's snapshot; the typed token is the
            // production path when marker precision matters.
            representedTruncationRevision = 0L,
            fallbackRecordIds = selected.filter { entry -> entry.persistedSequence == null }.mapTo(HashSet()) { entry -> entry.recordId },
            maxGeneration = maxGeneration,
            historyEpoch = historyEpoch,
        )
    }

    private fun buildSnapshot(): LogBufferSnapshot {
        val text =
            buildString {
                if (truncationRevision > 0L) {
                    append(TRUNCATION_MARKER)
                    if (entries.isNotEmpty()) append('\n')
                }
                entries.forEachIndexed { index, entry ->
                    if (index > 0) append('\n')
                    append(entry.line)
                }
            }
        val maxGeneration = entries.lastOrNull()?.generation ?: nextGeneration.value
        return LogBufferSnapshot(
            text = text,
            maxGeneration = maxGeneration,
            acknowledgement =
                LogBufferAcknowledgement(
                    maxSequence = entries.mapNotNull { entry -> entry.persistedSequence }.maxOrNull() ?: 0L,
                    representedTruncationRevision = truncationRevision,
                    fallbackRecordIds =
                        entries
                            .filter { entry -> entry.persistedSequence == null }
                            .mapTo(HashSet()) { entry -> entry.recordId },
                    maxGeneration = maxGeneration,
                    historyEpoch = historyEpoch,
                ),
        )
    }

    private fun emptySnapshot(): LogBufferSnapshot =
        LogBufferSnapshot(
            text = "",
            maxGeneration = nextGeneration.value,
            acknowledgement =
                LogBufferAcknowledgement(
                    maxSequence = 0L,
                    representedTruncationRevision = 0L,
                    fallbackRecordIds = emptySet(),
                    maxGeneration = nextGeneration.value,
                    historyEpoch = historyEpoch,
                ),
        )

    private suspend fun purgeBestEffort() {
        clearMemory()
        try {
            breadcrumbStore?.purge()
            storageNeedsReconciliation = false
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Throwable) {
            storageNeedsReconciliation = true
            // A disabled snapshot remains empty and retries the exact purge barrier later.
        }
        publishSize()
    }

    private suspend fun purgeStrict() {
        disabled = true
        clearMemory()
        try {
            breadcrumbStore?.purge()
            storageNeedsReconciliation = false
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            storageNeedsReconciliation = true
            throw exception
        }
        publishSize()
    }

    private fun clearMemory() {
        historyEpoch += 1L
        entries.clear()
        byteCount = 0
        truncationRevision = 0L
        truncationMarkerNeedsPersistence = false
        ingressOverflowed.value = false
        publishSize()
    }

    private fun trimMemory() {
        var pruned = false
        while (entries.size > DIAGNOSTIC_MAX_ENTRIES || byteCount > DIAGNOSTIC_MAX_BYTES) {
            val removed = entries.removeFirstOrNull() ?: break
            byteCount -= removed.byteCount
            pruned = true
        }
        if (pruned) {
            truncationRevision += 1L
            truncationMarkerNeedsPersistence = true
        }
    }

    private fun publishSize() {
        _size.value = LogBufferSize(entryCount = entries.size, byteCount = byteCount)
    }

    private fun boundedLine(line: String): String {
        val bytes = line.encodeToByteArray()
        if (bytes.size <= MAX_LINE_BYTES) return line
        val suffix = TRUNCATION_SUFFIX.encodeToByteArray()
        var prefixLength = (MAX_LINE_BYTES - suffix.size).coerceAtLeast(0)
        while (prefixLength > 0) {
            val prefix = bytes.copyOf(prefixLength).decodeToString()
            if (!prefix.contains('\uFFFD')) {
                return prefix + TRUNCATION_SUFFIX
            }
            prefixLength -= 1
        }
        return TRUNCATION_SUFFIX.take(MAX_LINE_BYTES)
    }

    private data class PendingRecord(
        val generation: Long,
        val recordId: String,
        val line: String,
        val byteCount: Int,
    )

    private data class BufferedEntry(
        val generation: Long,
        val recordId: String,
        val line: String,
        val byteCount: Int,
        val persistedSequence: Long?,
    ) {
        fun withSequence(sequence: Long): BufferedEntry = copy(persistedSequence = sequence)
    }

    private fun DiagnosticBreadcrumbStoredRecord.toBufferedEntry(generation: Long): BufferedEntry =
        BufferedEntry(
            generation = generation,
            recordId = recordId,
            line = line,
            byteCount = byteCount,
            persistedSequence = sequence,
        )

    private sealed interface Command {
        data class Append(
            val record: PendingRecord,
        ) : Command

        data class Snapshot(
            val result: CompletableDeferred<LogBufferSnapshot>,
        ) : Command

        data class Clear(
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class ClearThrough(
            val acknowledgement: LogBufferAcknowledgement,
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class ClearThroughGeneration(
            val maxGeneration: Long,
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class Purge(
            val result: CompletableDeferred<Unit>,
        ) : Command

        data object PreferenceChanged : Command
    }

    private companion object {
        const val INGRESS_CAPACITY = 512
        const val APPEND_BATCH_SIZE = 64
        const val MAX_LINE_BYTES = 4_096
        const val TRUNCATION_MARKER = "[earlier diagnostic lines were dropped]"
        const val TRUNCATION_SUFFIX = " ...[truncated]"
    }
}
