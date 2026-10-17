// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import co.touchlab.kermit.Severity
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryTrigger
import com.jellyscope.core.domain.playback.PlaybackColorPrimaries
import com.jellyscope.core.domain.playback.PlaybackColorTransfer
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackActivation
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackState
import com.jellyscope.core.domain.playback.PlaybackHealthSignalKind
import com.jellyscope.core.domain.playback.PlaybackHealthThresholdClass
import com.jellyscope.core.domain.playback.PlaybackNativePlayerMilestone
import com.jellyscope.core.domain.playback.PlaybackNativeSampleCause
import com.jellyscope.core.domain.playback.PlaybackOutputColorConversion
import com.jellyscope.core.domain.playback.PlaybackOutputColorEvidence
import com.jellyscope.core.domain.playback.PlaybackPhysicalDisplayConfirmation
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.PlaybackRendererRange
import com.jellyscope.core.domain.playback.PlaybackTerminalOutcome
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.SoftwarePlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.VideoOutputEvidence
import com.jellyscope.core.domain.playback.playbackDiagnosticEventNames
import com.jellyscope.core.domain.playback.playbackDiagnosticStageNames
import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

/**
 * One random capture epoch and one monotonic origin for every diagnostic owner in this process.
 * The epoch only links retained lines from one process; it is never derived from time, account,
 * device, installation, storage record IDs, or upload acknowledgement state.
 */
internal object DiagnosticCaptureIdentity {
    val epoch: String =
        Random.nextBytes(EPOCH_BYTES).joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    private val origin = TimeSource.Monotonic.markNow()
    private val nextSegment = atomic(0L)

    fun elapsedMs(): Long = origin.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L)

    /** Ephemeral collection boundary; disabled and re-enabled collection never share a segment. */
    fun allocateSegment(): Long = nextSegment.getAndIncrement()

    /** The exact versioned prefix. The store counts it inside its existing line and byte bounds. */
    fun envelope(
        segment: Long,
        elapsedMs: Long,
    ): String = "$ENVELOPE_START diagEpoch=$epoch diagSegment=$segment diagElapsedMs=$elapsedMs] "
}

/**
 * Process-unique playback diagnostic sessions. Values only correlate diagnostics and are
 * deliberately separate from operational launch, recovery, and stale-callback generations.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
object PlaybackDiagnosticSessions {
    private val next = atomic(0L)

    fun allocate(): Long = next.incrementAndGet()
}

internal class EncodedPlaybackTimeline(
    val json: String,
    val eventCount: Int,
    val historyComplete: Boolean,
)

/**
 * Bounded Chrome trace-event encoder for an already-admitted [LogBufferSnapshot]. It never parses
 * arbitrary files or raw native logs, never logs, and exports only closed names, tokens, booleans,
 * and clamped numbers. Captures and sessions are remapped to file-local lanes; intervals are
 * derived only when both endpoints share a trusted capture, session, backend, and non-null prepare
 * key and no unattributable line that could hide a lane boundary was retained between them.
 */
internal object PlaybackDiagnosticTimeline {
    /** Returns null when no timed playback event was collected. */
    fun encode(snapshot: LogBufferSnapshot): EncodedPlaybackTimeline? {
        val coverage = Coverage()
        val records =
            snapshot.text
                .lineSequence()
                .mapIndexedNotNull { index, line -> parse(line, index, coverage) }
                .toList()
        if (records.isEmpty()) return null

        val captures = LinkedHashMap<CaptureKey, CaptureLanes>()
        records.forEach { record ->
            val lanes = captures.getOrPut(record.capture) { CaptureLanes(pid = captures.size + 1) }
            lanes.zeroMs = minOf(lanes.zeroMs, record.elapsedMs)
            record.session?.let { session -> lanes.sessions.getOrPut(session) { lanes.sessions.size + 1 } }
        }
        val items = ArrayList<TraceItem>(records.size)
        records.forEach { record ->
            val lanes = captures.getValue(record.capture)
            items += TraceItem(lanes.pid, lanes.tid(record), lanes.micros(record), record.ingress * 2L, instant(record, lanes))
        }
        if (coverage.intervalsAllowed) {
            records
                .filter { record -> record.session != null }
                .groupBy { record -> record.capture to record.session }
                .values
                .forEach { lane -> items += intervals(lane, captures.getValue(lane.first().capture), coverage) }
        }
        items.sortWith(compareBy<TraceItem>({ it.pid }, { it.tsMicros }, { it.order }))
        return emit(items, captures, coverage)
    }

    private fun parse(
        line: String,
        ingress: Int,
        coverage: Coverage,
    ): TimelineRecord? {
        if (line.isEmpty()) return null
        if (line == DIAGNOSTIC_TRUNCATION_MARKER) {
            coverage.droppedLines = true
            return null
        }
        if (!line.startsWith(ENVELOPE_FAMILY)) {
            coverage.legacyLines += 1
            coverage.ambiguousLines += ingress
            return null
        }
        if (line.endsWith(DIAGNOSTIC_LINE_TRUNCATION_SUFFIX)) {
            coverage.truncatedLines += 1
            coverage.ambiguousLines += ingress
            return null
        }
        val record = parseEnvelope(line, ingress)
        when (record) {
            null -> {
                coverage.malformedLines += 1
                coverage.ambiguousLines += ingress
            }

            is Unsupported -> {
                coverage.unsupportedEvents += 1
                if (record.tag in sessionOwnerTags) coverage.ambiguousLines += ingress
            }

            is TimelineRecord ->
                if (record.session == null) {
                    coverage.uncorrelatedEvents += 1
                    // A session owner's line without a trusted session may be an unseen boundary.
                    if (record.tag in sessionOwnerTags) coverage.ambiguousLines += ingress
                }
        }
        return record as? TimelineRecord
    }

    /** Returns null for a malformed envelope or invalid required value; [Unsupported] for other schemas. */
    private fun parseEnvelope(
        line: String,
        ingress: Int,
    ): ParsedLine? {
        val match = envelopePattern.matchEntire(line) ?: return null
        val (epoch, segmentValue, elapsedValue, severity, tag, message) = match.destructured
        val segment = segmentValue.toLongOrNull() ?: return null
        val elapsedMs = elapsedValue.toLongOrNull()?.takeIf { value -> value <= MAX_TRACE_ELAPSED_MS } ?: return null
        if (severity !in severityNames || tag !in diagnosticTagNames) return null
        val fields = parseFields(message) ?: return null
        val stage = fields["stage"] ?: return null
        val event = fields["event"] ?: return null
        if (stage !in playbackDiagnosticStageNames || event !in playbackDiagnosticEventNames) return Unsupported(tag)
        val backend = fields["backend"]?.let { value -> value.takeIf(backendNames::contains) ?: return null }
        val platform = fields["platform"]?.let { value -> value.takeIf(platformNames::contains) ?: return null }
        val prepare = fields["prepareSequence"]?.let { value -> value.toLongOrNull()?.takeIf { it >= 0L } ?: return null }
        val session = fields["sessionSequence"]?.let { value -> value.toLongOrNull()?.takeIf { it >= 0L } ?: return null }
        return TimelineRecord(
            ingress = ingress,
            capture = CaptureKey(epoch, segment),
            elapsedMs = elapsedMs,
            tag = tag,
            stage = stage,
            event = event,
            // Owner-local native fallbacks are indistinguishable from allocated sessions, so only
            // the session owners' records are trusted. Clamped formatter ceilings stay ambiguous.
            session = session?.takeIf { tag in sessionOwnerTags && it in 1L until MAX_FORMATTED_SEQUENCE },
            backend = backend,
            platform = platform,
            prepare = prepare?.takeIf { it < MAX_FORMATTED_SEQUENCE },
            firstVideoOutputObserved = fields["firstVideoOutputObserved"] == "true",
            args = exportArgs(fields),
        )
    }

    private fun parseFields(message: String): Map<String, String>? {
        val fields = LinkedHashMap<String, String>()
        message.split(' ').forEach { field ->
            val name = field.substringBefore('=', missingDelimiterValue = "")
            val value = field.substringAfter('=', missingDelimiterValue = "")
            if (!fieldName.matches(name) || value.isEmpty() || fields.put(name, value) != null) return null
        }
        return fields
    }

    private fun exportArgs(fields: Map<String, String>): Map<String, JsonPrimitive> {
        val args = LinkedHashMap<String, JsonPrimitive>()
        fields.forEach { (name, value) ->
            when (name) {
                in tokenArgs -> value.takeIf(tokenArgs.getValue(name)::contains)?.let { args[name] = JsonPrimitive(it) }
                in booleanArgs -> value.toBooleanStrictOrNull()?.let { args[name] = JsonPrimitive(it) }
                in boundedArgs ->
                    value
                        .toLongOrNull()
                        ?.takeIf { it >= 0L }
                        ?.coerceAtMost(boundedArgs.getValue(name))
                        ?.let { args[name] = JsonPrimitive(it) }
            }
        }
        return args
    }

    private fun instant(
        record: TimelineRecord,
        lanes: CaptureLanes,
    ): JsonObject =
        buildJsonObject {
            put("name", record.event)
            put("cat", record.stage)
            put("ph", "i")
            put("s", "t")
            put("ts", lanes.micros(record))
            put("pid", lanes.pid)
            put("tid", lanes.tid(record))
            putJsonObject("args") {
                put("source", record.tag)
                lanes.prepare(record)?.let { put("prepare", it) }
                record.args.forEach { (name, value) -> put(name, value) }
            }
        }

    /**
     * Prepare request -> dispatch is labeled as dispatch, never startup. Every span needs the same
     * non-null prepare identity recorded on both endpoints; a missing one is never inferred from a
     * neighboring line, so those endpoints stay instants. A first-output span needs an observed
     * shared-owner output with no newer prepare or terminal event in the lane. tvOS observation
     * generation is not proven here, so it stays instant-only.
     */
    private fun intervals(
        lane: List<TimelineRecord>,
        lanes: CaptureLanes,
        coverage: Coverage,
    ): List<TraceItem> {
        val spans = ArrayList<TraceItem>()
        var request: TimelineRecord? = null
        var dispatch: TimelineRecord? = null

        fun derive(
            name: String,
            start: TimelineRecord?,
            end: TimelineRecord,
        ) {
            if (start == null || !start.pairsWith(end)) return
            if (coverage.ambiguousBetween(start, end)) {
                coverage.suppressedIntervals += 1
            } else {
                spans += span(name, start, end, lanes)
            }
        }
        lane.forEach { record ->
            when (record.event) {
                PREPARE_REQUESTED -> {
                    request = record
                    dispatch = null
                }

                PREPARE_DISPATCHED -> {
                    derive(PREPARE_DISPATCH_SPAN, request, record)
                    request = null
                    dispatch = record.takeIf { it.prepare != null && it.platform == SHARED_PLATFORM }
                }

                VIDEO_OUTPUT ->
                    if (record.firstVideoOutputObserved) {
                        if (record.platform == SHARED_PLATFORM) derive(FIRST_VIDEO_OUTPUT_SPAN, dispatch, record)
                        dispatch = null
                    }

                TERMINAL_ERROR, FAILED -> {
                    request = null
                    dispatch = null
                }
            }
        }
        return spans
    }

    /** Equal recorded times with later ingress order are a real zero-duration span. */
    private fun TimelineRecord.pairsWith(end: TimelineRecord): Boolean =
        backend != null &&
            backend == end.backend &&
            prepare != null &&
            prepare == end.prepare &&
            end.ingress > ingress &&
            end.elapsedMs >= elapsedMs

    private fun span(
        name: String,
        start: TimelineRecord,
        end: TimelineRecord,
        lanes: CaptureLanes,
    ): TraceItem {
        val startMicros = lanes.micros(start)
        val element =
            buildJsonObject {
                put("name", name)
                put("cat", DERIVED_CATEGORY)
                put("ph", "X")
                put("ts", startMicros)
                put("dur", lanes.micros(end) - startMicros)
                put("pid", lanes.pid)
                put("tid", lanes.tid(start))
                putJsonObject("args") {
                    start.backend?.let { put("backend", it) }
                    lanes.prepare(end)?.let { put("prepare", it) }
                }
            }
        return TraceItem(lanes.pid, lanes.tid(start), startMicros, start.ingress * 2L + 1L, element)
    }

    private fun emit(
        items: List<TraceItem>,
        captures: Map<CaptureKey, CaptureLanes>,
        coverage: Coverage,
    ): EncodedPlaybackTimeline {
        val output = StringBuilder(TRACE_EVENTS_OPEN)
        var bytes = TRACE_EVENTS_OPEN.length
        var first = true
        val namedProcesses = HashSet<Int>()
        val namedThreads = HashSet<Pair<Int, Int>>()

        fun append(
            element: JsonElement,
            budget: Int,
        ): Boolean {
            val encoded = traceJson.encodeToString(JsonElement.serializer(), element)
            val size = encoded.encodeToByteArray().size + if (first) 0 else 1
            if (bytes + size > budget) return false
            if (!first) output.append(',')
            output.append(encoded)
            bytes += size
            first = false
            return true
        }

        val eventBudget = MAX_TIMELINE_BYTES - RESERVED_TRAILER_BYTES
        var admitted = 0
        for (item in items) {
            val fits =
                (item.pid in namedProcesses || append(metadata("process_name", item.pid, 0, "capture ${item.pid}"), eventBudget)) &&
                    (
                        (item.pid to item.tid) in namedThreads ||
                            append(metadata("thread_name", item.pid, item.tid, laneName(item.tid)), eventBudget)
                    ) &&
                    append(item.element, eventBudget)
            if (!fits) break
            namedProcesses += item.pid
            namedThreads += item.pid to item.tid
            admitted += 1
        }
        coverage.omittedEvents = items.size - admitted

        // The trailer is fixed-shape and small; the reserved budget keeps the whole file bounded.
        val limitations = coverage.limitations()
        if (limitations.isNotEmpty()) {
            append(metadata("process_name", COVERAGE_PID, 0, "coverage"), MAX_TIMELINE_BYTES)
            append(coverageEvent(coverage), MAX_TIMELINE_BYTES)
        }
        if (coverage.omittedEvents > 0) {
            append(globalMarker(OUTPUT_TRUNCATED_MARKER), MAX_TIMELINE_BYTES)
        }
        val otherData =
            buildJsonObject {
                put("format", FORMAT_NAME)
                put("version", FORMAT_VERSION)
                put("clock", "capture-relative-monotonic")
                put("historyComplete", coverage.historyComplete)
                put("captures", captures.size)
                put("events", admitted)
                put("limitations", JsonArray(limitations.map { limitation -> JsonPrimitive(limitation) }))
            }
        output
            .append("],\"displayTimeUnit\":\"ms\",\"otherData\":")
            .append(traceJson.encodeToString(JsonElement.serializer(), otherData))
            .append('}')
        return EncodedPlaybackTimeline(
            json = output.toString(),
            eventCount = admitted,
            historyComplete = coverage.historyComplete,
        )
    }

    private fun metadata(
        name: String,
        pid: Int,
        tid: Int,
        label: String,
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            put("ph", "M")
            put("pid", pid)
            put("tid", tid)
            putJsonObject("args") { put("name", label) }
        }

    private fun laneName(tid: Int): String = if (tid == UNCORRELATED_TID) "uncorrelated" else "session $tid"

    private fun coverageEvent(coverage: Coverage): JsonObject =
        buildJsonObject {
            put("name", COVERAGE_LIMITED_MARKER)
            put("cat", COVERAGE_CATEGORY)
            put("ph", "i")
            put("s", "g")
            put("ts", 0)
            put("pid", COVERAGE_PID)
            put("tid", 0)
            putJsonObject("args") {
                put("droppedLines", coverage.droppedLines)
                put("legacyLines", coverage.legacyLines)
                put("malformedLines", coverage.malformedLines)
                put("truncatedLines", coverage.truncatedLines)
                put("unsupportedEvents", coverage.unsupportedEvents)
                put("uncorrelatedEvents", coverage.uncorrelatedEvents)
                put("omittedEvents", coverage.omittedEvents)
                put("derivedIntervals", coverage.intervalsAllowed)
                put("suppressedIntervals", coverage.suppressedIntervals)
            }
        }

    private fun globalMarker(name: String): JsonObject =
        buildJsonObject {
            put("name", name)
            put("cat", COVERAGE_CATEGORY)
            put("ph", "i")
            put("s", "g")
            put("ts", 0)
            put("pid", COVERAGE_PID)
            put("tid", 0)
        }

    private sealed interface ParsedLine

    /** A well-formed admitted line outside the selected playback schema; only its closed tag is kept. */
    private class Unsupported(
        val tag: String,
    ) : ParsedLine

    private data class CaptureKey(
        val epoch: String,
        val segment: Long,
    )

    private class TimelineRecord(
        val ingress: Int,
        val capture: CaptureKey,
        val elapsedMs: Long,
        val tag: String,
        val stage: String,
        val event: String,
        val session: Long?,
        val backend: String?,
        val platform: String?,
        val prepare: Long?,
        val firstVideoOutputObserved: Boolean,
        val args: Map<String, JsonPrimitive>,
    ) : ParsedLine

    private class CaptureLanes(
        val pid: Int,
    ) {
        var zeroMs: Long = Long.MAX_VALUE
        val sessions = LinkedHashMap<Long, Int>()
        private val prepares = HashMap<Triple<Long?, String?, Long>, Int>()

        fun tid(record: TimelineRecord): Int = record.session?.let(sessions::getValue) ?: UNCORRELATED_TID

        /** Each capture starts at its own zero; bounded elapsed values keep microseconds exact. */
        fun micros(record: TimelineRecord): Long = (record.elapsedMs - zeroMs) * MICROS_PER_MILLI

        fun prepare(record: TimelineRecord): Int? =
            record.prepare?.let { prepare ->
                prepares.getOrPut(Triple(record.session, record.backend, prepare)) { prepares.size + 1 }
            }
    }

    private class TraceItem(
        val pid: Int,
        val tid: Int,
        val tsMicros: Long,
        val order: Long,
        val element: JsonObject,
    )

    /**
     * Counts only; no raw line content is retained. Every kind of omitted or unattributable input
     * is a named limitation, so history is complete only when nothing was lost or left out.
     */
    private class Coverage {
        var droppedLines = false
        var legacyLines = 0
        var malformedLines = 0
        var truncatedLines = 0

        /** Well-formed lines outside the selected playback stage/event schema. */
        var unsupportedEvents = 0
        var uncorrelatedEvents = 0
        var omittedEvents = 0
        var suppressedIntervals = 0

        /** Ingress order of lines that could hide a session owner's prepare or terminal boundary. */
        val ambiguousLines = ArrayList<Int>()

        /** Lost or unreadable lines anywhere mean no derived interval can prove its completeness. */
        val intervalsAllowed: Boolean
            get() = !droppedLines && malformedLines == 0 && truncatedLines == 0

        val historyComplete: Boolean
            get() = limitations().isEmpty()

        /** [ambiguousLines] is filled in ingress order, so it stays sorted for the search. */
        fun ambiguousBetween(
            start: TimelineRecord,
            end: TimelineRecord,
        ): Boolean {
            val next = ambiguousLines.binarySearch(start.ingress).let { index -> if (index >= 0) index + 1 else -index - 1 }
            return next < ambiguousLines.size && ambiguousLines[next] < end.ingress
        }

        fun limitations(): List<String> =
            buildList {
                if (droppedLines) add("dropped-lines")
                if (legacyLines > 0) add("legacy-untimed-lines")
                if (malformedLines > 0) add("malformed-lines")
                if (truncatedLines > 0) add("truncated-lines")
                if (unsupportedEvents > 0) add("unsupported-events")
                if (uncorrelatedEvents > 0) add("uncorrelated-events")
                if (omittedEvents > 0) add("output-truncated")
            }
    }

    private val traceJson = Json
    private val envelopePattern =
        Regex(
            "\\[jst1 diagEpoch=([0-9a-f]{32}) diagSegment=(0|[1-9][0-9]{0,18}) " +
                "diagElapsedMs=(0|[1-9][0-9]{0,18})] ([A-Za-z]+) ([A-Za-z][A-Za-z0-9]{0,79}) (\\S.*)",
        )
    private val fieldName = Regex("[A-Za-z][A-Za-z0-9]{0,79}")
    private val severityNames = Severity.entries.map { severity -> severity.name }.toSet()
    private val diagnosticTagNames = DiagnosticTag.entries.map { tag -> tag.wireValue }.toSet()
    private val sessionOwnerTags =
        setOf(
            DiagnosticTag.PlayerViewModel.wireValue,
            DiagnosticTag.TvPlaybackSessionPresenter.wireValue,
            DiagnosticTag.PlaybackInfoPlanner.wireValue,
        )
    private val backendNames = PlayerBackend.entries.map { backend -> backend.name.lowercase() }.toSet()
    private val platformNames = PlaybackDiagnosticPlatform.entries.map { platform -> platform.name.lowercase() }.toSet()
    private val tokenArgs: Map<String, Set<String>> =
        mapOf(
            "platform" to platformNames,
            "backend" to backendNames,
            "requestedBackend" to backendNames,
            "streamMode" to StreamMode.entries.names(),
            "terminalOutcome" to PlaybackTerminalOutcome.entries.names(),
            "recoveryDecision" to PlaybackRecoveryDecision.entries.names(),
            "autoRecoveryTrigger" to AutoPlaybackRecoveryTrigger.entries.names(),
            "softwareRecoveryDecision" to SoftwarePlaybackRecoveryDecision.entries.names(),
            "healthSignal" to PlaybackHealthSignalKind.entries.names(),
            "healthThresholdClass" to PlaybackHealthThresholdClass.entries.names(),
            "trackKind" to PlaybackDiagnosticTrackKind.entries.map { kind -> kind.name.lowercase() }.toSet(),
            "trackRequestedState" to PlaybackDiagnosticTrackState.entries.names(),
            "trackConfirmedState" to PlaybackDiagnosticTrackState.entries.names(),
            "trackActivation" to PlaybackDiagnosticTrackActivation.entries.names(),
            "nativePlayerMilestone" to PlaybackNativePlayerMilestone.entries.names(),
            "nativeSampleCause" to PlaybackNativeSampleCause.entries.names(),
            "firstVideoOutputEvidence" to VideoOutputEvidence.entries.names(),
            "qualityPolicyMode" to PlaybackQualityMode.entries.names(),
            "colorInputTransfer" to PlaybackColorTransfer.entries.names(),
            "colorInputPrimaries" to PlaybackColorPrimaries.entries.names(),
            "colorTargetTransfer" to PlaybackColorTransfer.entries.names(),
            "colorTargetPrimaries" to PlaybackColorPrimaries.entries.names(),
            "colorRendererRange" to PlaybackRendererRange.entries.names(),
            "colorEvidence" to PlaybackOutputColorEvidence.entries.names(),
            "colorConversion" to PlaybackOutputColorConversion.entries.names(),
            "colorPhysicalDisplay" to PlaybackPhysicalDisplayConfirmation.entries.names(),
        )
    private val booleanArgs =
        setOf(
            "firstVideoOutputAvailable",
            "firstVideoOutputObserved",
            "nativeReady",
            "nativeLoadOutstanding",
            "nativeStartObserved",
            "nativeFileLoaded",
            "nativePaused",
            "trackMatchesRequest",
            "healthFirstPlayingObserved",
            "retryAttempted",
            "degradationAttempted",
        )
    private val boundedArgs: Map<String, Long> =
        mapOf(
            "nativePrepareToFirstFrameMs" to MAX_EXPORTED_DURATION_MS,
            "launchToFirstFrameMs" to MAX_EXPORTED_DURATION_MS,
            "totalRebufferMs" to MAX_EXPORTED_DURATION_MS,
            "maxRebufferMs" to MAX_EXPORTED_DURATION_MS,
            "maxAudioFeedGapMs" to MAX_EXPORTED_DURATION_MS,
            "healthDurationMs" to MAX_EXPORTED_DURATION_MS,
            "healthBufferingDurationMs" to MAX_EXPORTED_DURATION_MS,
            "tvDisplaySwitchDurationMs" to MAX_EXPORTED_DURATION_MS,
            "rebufferCount" to MAX_EXPORTED_COUNT,
            "audioUnderrunCount" to MAX_EXPORTED_COUNT,
            "healthCount" to MAX_EXPORTED_COUNT,
            "healthStallCount" to MAX_EXPORTED_COUNT,
            "healthBufferingIntervalCount" to MAX_EXPORTED_COUNT,
            "backendDroppedVideoFrames" to MAX_EXPORTED_COUNT,
            "decoderDroppedVideoFrames" to MAX_EXPORTED_COUNT,
            "outputDroppedVideoFrames" to MAX_EXPORTED_COUNT,
            "runtimeVideoWidth" to MAX_EXPORTED_DIMENSION,
            "runtimeVideoHeight" to MAX_EXPORTED_DIMENSION,
        )

    private fun <T : Enum<T>> List<T>.names(): Set<String> = map { entry -> entry.name }.toSet()
}

private const val EPOCH_BYTES = 16
private const val ENVELOPE_FAMILY = "[jst"
private const val ENVELOPE_START = "[jst1"
private const val TRACE_EVENTS_OPEN = "{\"traceEvents\":["
private const val FORMAT_NAME = "jellyscope-playback-timeline"
private const val FORMAT_VERSION = 1
private const val MAX_TIMELINE_BYTES = 2 * 1024 * 1024
private const val RESERVED_TRAILER_BYTES = 8 * 1024
private const val MICROS_PER_MILLI = 1_000L

/** Keeps microsecond timestamps within the exactly representable JSON integer range. */
private const val MAX_TRACE_ELAPSED_MS = 9_007_199_254_740L

/** The playback formatter clamps sequences here, so the ceiling itself is ambiguous. */
private const val MAX_FORMATTED_SEQUENCE = 1_000_000L
private val MAX_EXPORTED_DURATION_MS = 24.hours.inWholeMilliseconds
private const val MAX_EXPORTED_COUNT = 1_000_000_000L
private const val MAX_EXPORTED_DIMENSION = 32_768L
private const val UNCORRELATED_TID = 0
private const val COVERAGE_PID = 0
private const val SHARED_PLATFORM = "shared"
private const val PREPARE_REQUESTED = "prepare-requested"
private const val PREPARE_DISPATCHED = "prepare-dispatched"
private const val VIDEO_OUTPUT = "video-output"
private const val TERMINAL_ERROR = "terminal-error"
private const val FAILED = "failed"
private const val PREPARE_DISPATCH_SPAN = "prepare-dispatch"
private const val FIRST_VIDEO_OUTPUT_SPAN = "dispatch-to-first-video-output"
private const val DERIVED_CATEGORY = "derived"
private const val COVERAGE_CATEGORY = "coverage"
private const val COVERAGE_LIMITED_MARKER = "coverage-limited"
private const val OUTPUT_TRUNCATED_MARKER = "timeline-truncated"
