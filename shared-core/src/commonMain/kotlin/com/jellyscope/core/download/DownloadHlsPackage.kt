// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.data.local.DownloadArtifactPartCheckpoint
import com.jellyscope.core.data.local.DownloadArtifactPartKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The deliberately small HLS subset that can be made into a JellyScope offline package.
 *
 * This is not a general HLS parser.  The server probe established one master variant and one
 * finite HLS-TS VOD media playlist.  Anything outside that shape is rejected before a remote
 * resource is opened.  In particular, remote references are retained only in this in-memory
 * value and never cross the checkpoint boundary.
 */
internal data class DownloadHlsPackage(
    val master: DownloadHlsMasterPlaylist,
    val media: DownloadHlsMediaPlaylist,
) {
    val identity: DownloadHlsCheckpointIdentity =
        DownloadHlsCheckpointIdentity(
            variantIdentity = master.variantIdentity,
            mediaSequence = media.mediaSequence,
            targetDurationMillis = media.targetDurationMillis,
            segments =
                media.segments.map { segment ->
                    DownloadHlsSegmentIdentity(
                        ordinal = segment.ordinal,
                        durationMillis = segment.durationMillis,
                        extension = segment.extension,
                    )
                },
        )

    val localParts: List<DownloadHlsLocalPart> =
        buildList {
            add(DownloadHlsLocalPart(DownloadHlsPartNames.MASTER, masterText().encodeToByteArray().size.toLong()))
            add(DownloadHlsLocalPart(DownloadHlsPartNames.MEDIA, mediaText().encodeToByteArray().size.toLong()))
            media.segments.forEach { segment ->
                add(DownloadHlsLocalPart(segment.localPartKey, 0L))
            }
        }

    /** A credential-free checkpoint with deterministic local names and no completed bytes. */
    fun initialCheckpoint(): DownloadHlsCheckpoint =
        DownloadHlsCheckpoint(
            identity = identity,
            master = DownloadHlsCheckpointPart(DownloadHlsPartNames.MASTER.value, 0L, false),
            media = DownloadHlsCheckpointPart(DownloadHlsPartNames.MEDIA.value, 0L, false),
            segments =
                media.segments.map { segment ->
                    DownloadHlsCheckpointPart(segment.localPartKey.value, 0L, false)
                },
        )

    /**
     * The finalized master playlist intentionally points only at the local media playlist.
     * Attribute values are canonicalized so the same normalized variant identity is visible in
     * the local package and in a fresh resume parse.
     */
    fun masterText(): String =
        buildString {
            append("#EXTM3U\n")
            append("#EXT-X-STREAM-INF:")
            append(master.normalizedAttributes)
            append('\n')
            append(DownloadHlsPartNames.MEDIA.value)
            append('\n')
        }

    /** The finalized media playlist contains only deterministic local part names. */
    fun mediaText(): String =
        buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:")
            append(media.version)
            append('\n')
            append("#EXT-X-TARGETDURATION:")
            append(media.targetDurationSeconds)
            append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:")
            append(media.mediaSequence)
            append('\n')
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            media.segments.forEach { segment ->
                append("#EXTINF:")
                append(segment.durationText)
                append(',')
                append(segment.title)
                append('\n')
                append(segment.localPartKey.value)
                append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }

    fun expectedPartKeys(): List<DownloadArtifactPartKey> = localParts.map { part -> part.partKey }

    companion object {
        /**
         * Parses a master and its selected child media playlist.  The child URI is checked as a
         * same-origin relative reference by [parseMasterOnly]; the caller resolves it only while the
         * authenticated transport operation is alive.
         */
        fun parse(
            masterText: String,
            mediaText: String,
        ): DownloadHlsParseResult {
            val master = parseMasterPlaylist(masterText)
            if (master is DownloadHlsParseResult.Failure) return master
            val media = parseMediaPlaylist(mediaText)
            if (media is DownloadHlsParseResult.Failure) return media
            val parsedMaster =
                (master as? DownloadHlsParseResult.Master)?.value
                    ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMaster)
            val parsedMedia =
                (media as? DownloadHlsParseResult.Media)?.value
                    ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMedia)
            return DownloadHlsParseResult.Package(
                DownloadHlsPackage(master = parsedMaster, media = parsedMedia),
            )
        }

        /** Parse only the master so the authenticated transport can fetch its one child playlist. */
        fun parseMasterOnly(masterText: String): DownloadHlsParseResult = parseMasterPlaylist(masterText)

        /** Parse only the selected child media playlist after the transport has fetched it. */
        fun parseMediaOnly(mediaText: String): DownloadHlsParseResult = parseMediaPlaylist(mediaText)
    }
}

internal data class DownloadHlsMasterPlaylist(
    /** The child URI is in-memory transport input only and is never encoded into a checkpoint. */
    val childPlaylistUri: String,
    val variantIdentity: String,
    val normalizedAttributes: String,
)

internal data class DownloadHlsMediaPlaylist(
    val version: Int,
    val targetDurationSeconds: Int,
    val targetDurationMillis: Long,
    val mediaSequence: Long,
    val segments: List<DownloadHlsSegment>,
)

internal data class DownloadHlsSegment(
    /** Remote URI is intentionally transient and must not be put into [DownloadHlsCheckpoint]. */
    val remoteUri: String,
    val ordinal: Int,
    val durationText: String,
    val durationMillis: Long,
    val title: String,
    val extension: String,
    val localPartKey: DownloadArtifactPartKey,
)

internal data class DownloadHlsLocalPart(
    val partKey: DownloadArtifactPartKey,
    val expectedLengthBytes: Long,
)

internal object DownloadHlsPartNames {
    val MASTER: DownloadArtifactPartKey = DownloadArtifactPartKey.from("master.m3u8")
    val MEDIA: DownloadArtifactPartKey = DownloadArtifactPartKey.from("media.m3u8")
    val CHECKPOINT: DownloadArtifactPartKey = DownloadArtifactPartKey.from("checkpoint.json")

    fun segment(ordinal: Int): DownloadArtifactPartKey = DownloadArtifactPartKey.from("segment-${ordinal.toString().padStart(6, '0')}.ts")
}

internal sealed interface DownloadHlsParseResult {
    data class Master(
        val value: DownloadHlsMasterPlaylist,
    ) : DownloadHlsParseResult

    data class Media(
        val value: DownloadHlsMediaPlaylist,
    ) : DownloadHlsParseResult

    data class Package(
        val value: DownloadHlsPackage,
    ) : DownloadHlsParseResult

    data class Failure(
        val reason: DownloadHlsRejectReason,
    ) : DownloadHlsParseResult
}

internal enum class DownloadHlsRejectReason {
    EmptyPlaylist,
    PlaylistTooLarge,
    LineTooLong,
    MissingExtM3u,
    InvalidMaster,
    InvalidVariant,
    InvalidMedia,
    UnsupportedTag,
    UnsupportedResource,
    MissingRequiredTag,
    InvalidDuration,
    InvalidSequence,
    IncompletePlaylist,
    TooManySegments,
}

/** Credential-free, normalized source identity persisted by the HLS checkpoint manifest. */
@Serializable
internal data class DownloadHlsCheckpointIdentity(
    val variantIdentity: String,
    val mediaSequence: Long,
    val targetDurationMillis: Long,
    val segments: List<DownloadHlsSegmentIdentity>,
)

@Serializable
internal data class DownloadHlsSegmentIdentity(
    val ordinal: Int,
    val durationMillis: Long,
    val extension: String,
)

/** One private local package member.  [localName] is a relative opaque part name only. */
@Serializable
internal data class DownloadHlsCheckpointPart(
    val localName: String,
    val lengthBytes: Long,
    val complete: Boolean,
) {
    init {
        require(localName.isNotBlank()) { "HLS checkpoint part name must not be blank." }
        require(DownloadArtifactPartKey.fromOrNull(localName) != null) {
            "HLS checkpoint part name must be one relative opaque artifact key."
        }
        require(lengthBytes >= 0L) { "HLS checkpoint length must not be negative." }
        require(complete || lengthBytes == 0L) {
            "Incomplete HLS checkpoint parts must not retain a byte length."
        }
    }
}

/**
 * The one checkpoint manifest for a staged HLS package.  It contains only normalized identity and
 * local part facts.  It deliberately has no remote playlist URI, segment query, server URL, or
 * credential-bearing value.
 */
@Serializable
internal data class DownloadHlsCheckpoint(
    val formatVersion: Int = HLS_CHECKPOINT_FORMAT_VERSION,
    val identity: DownloadHlsCheckpointIdentity,
    val master: DownloadHlsCheckpointPart,
    val media: DownloadHlsCheckpointPart,
    val segments: List<DownloadHlsCheckpointPart>,
) {
    init {
        require(formatVersion == HLS_CHECKPOINT_FORMAT_VERSION) { "Unsupported HLS checkpoint format." }
        require(master.localName == DownloadHlsPartNames.MASTER.value)
        require(media.localName == DownloadHlsPartNames.MEDIA.value)
        require(segments.map { part -> part.localName }.distinct().size == segments.size)
        require(segments.size == identity.segments.size)
        require(segments.indices.all { index -> segments[index].localName == DownloadHlsPartNames.segment(index).value })
        require(identity.variantIdentity.isNotBlank()) { "HLS checkpoint variant identity must not be blank." }
        require(identity.mediaSequence >= 0L) { "HLS checkpoint media sequence must not be negative." }
        require(identity.targetDurationMillis > 0L) { "HLS checkpoint target duration must be positive." }
        require(
            identity.segments.indices.all { index ->
                val segment = identity.segments[index]
                segment.ordinal == index && segment.durationMillis > 0L && segment.extension == "ts"
            },
        ) { "HLS checkpoint segment identity is invalid." }
    }

    fun encode(): String = HLS_CHECKPOINT_JSON.encodeToString(this)

    fun isCompatible(packageValue: DownloadHlsPackage): Boolean =
        identity == packageValue.identity &&
            segments.size == packageValue.media.segments.size &&
            segments.indices.all { index ->
                segments[index].localName ==
                    packageValue.media.segments[index]
                        .localPartKey.value
            } &&
            master.localName == DownloadHlsPartNames.MASTER.value &&
            media.localName == DownloadHlsPartNames.MEDIA.value

    fun isComplete(): Boolean =
        master.complete &&
            master.lengthBytes > 0L &&
            media.complete &&
            media.lengthBytes > 0L &&
            segments.isNotEmpty() &&
            segments.all { part ->
                part.complete && part.lengthBytes > 0L
            }

    fun allParts(): List<DownloadHlsCheckpointPart> = listOf(master, media) + segments

    fun artifactCheckpoint(): List<DownloadArtifactPartCheckpoint> =
        allParts().map { part ->
            DownloadArtifactPartCheckpoint(
                partKey = DownloadArtifactPartKey.from(part.localName),
                lengthBytes = part.lengthBytes,
            )
        }

    companion object {
        fun decode(encoded: String): DownloadHlsCheckpoint? =
            if (encoded.length > MAX_HLS_CHECKPOINT_BYTES) {
                null
            } else {
                runCatching { HLS_CHECKPOINT_JSON.decodeFromString<DownloadHlsCheckpoint>(encoded) }.getOrNull()
            }
    }
}

private const val HLS_CHECKPOINT_FORMAT_VERSION = 1
private const val MAX_HLS_PLAYLIST_BYTES = 1_048_576
private const val MAX_HLS_LINE_LENGTH = 8_192
private const val MAX_HLS_SEGMENTS = 8_192
private const val MAX_HLS_CHECKPOINT_BYTES = 1_048_576

private val HLS_CHECKPOINT_JSON =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

private fun parseMasterPlaylist(text: String): DownloadHlsParseResult {
    val lines = playlistLines(text) ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.PlaylistTooLarge)
    if (lines.isEmpty()) return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.EmptyPlaylist)
    if (lines.first() != "#EXTM3U") return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.MissingExtM3u)

    var attributes: String? = null
    var childUri: String? = null
    lines.drop(1).forEach { line ->
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXT-X-STREAM-INF:") -> {
                if (attributes != null || childUri != null) return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMaster)
                val rawAttributes = line.substringAfter(':', missingDelimiterValue = "")
                val canonical =
                    canonicalizeAttributes(rawAttributes)
                        ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidVariant)
                attributes = canonical
            }
            line.startsWith('#') -> return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.UnsupportedTag)
            else -> {
                if (attributes == null || childUri != null || !isSafeRelativeResource(line, extension = "m3u8")) {
                    return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.UnsupportedResource)
                }
                childUri = line
            }
        }
    }
    val normalized = attributes ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.MissingRequiredTag)
    val child = childUri ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.MissingRequiredTag)
    return DownloadHlsParseResult.Master(
        DownloadHlsMasterPlaylist(
            childPlaylistUri = child,
            variantIdentity = normalized,
            normalizedAttributes = normalized,
        ),
    )
}

private fun parseMediaPlaylist(text: String): DownloadHlsParseResult {
    val lines = playlistLines(text) ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.PlaylistTooLarge)
    if (lines.isEmpty()) return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.EmptyPlaylist)
    if (lines.first() != "#EXTM3U") return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.MissingExtM3u)

    var version: Int? = null
    var targetDurationSeconds: Int? = null
    var mediaSequence: Long? = null
    var playlistTypeVod = false
    var endList = false
    var pendingDuration: ParsedDuration? = null
    val segments = mutableListOf<DownloadHlsSegment>()

    lines.drop(1).forEach { line ->
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXT-X-VERSION:") -> {
                if (version != null) return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMedia)
                version = line.substringAfter(':').toIntOrNull()
                if (version?.let { value -> value <= 0 } != false) {
                    return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMedia)
                }
            }
            line.startsWith("#EXT-X-TARGETDURATION:") -> {
                if (targetDurationSeconds != null) return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMedia)
                targetDurationSeconds = line.substringAfter(':').toIntOrNull()
                if (targetDurationSeconds?.let { value -> value <= 0 } != false) {
                    return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMedia)
                }
            }
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                if (mediaSequence != null) return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMedia)
                mediaSequence = line.substringAfter(':').toLongOrNull()
                if (mediaSequence?.let { value -> value < 0L } != false) {
                    return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidSequence)
                }
            }
            line == "#EXT-X-PLAYLIST-TYPE:VOD" -> {
                if (playlistTypeVod) return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMedia)
                playlistTypeVod = true
            }
            line.startsWith("#EXTINF:") -> {
                if (pendingDuration != null || segments.size >= MAX_HLS_SEGMENTS) {
                    return DownloadHlsParseResult.Failure(
                        if (segments.size >= MAX_HLS_SEGMENTS) {
                            DownloadHlsRejectReason.TooManySegments
                        } else {
                            DownloadHlsRejectReason.InvalidMedia
                        },
                    )
                }
                pendingDuration = parseDuration(line.substringAfter(':'))
                    ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidDuration)
            }
            line == "#EXT-X-ENDLIST" -> {
                if (endList || pendingDuration != null) return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.IncompletePlaylist)
                endList = true
            }
            line.startsWith('#') -> return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.UnsupportedTag)
            else -> {
                val duration =
                    pendingDuration
                        ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMedia)
                if (endList || !isSafeRelativeResource(line, extension = "ts")) {
                    return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.UnsupportedResource)
                }
                val ordinal = segments.size
                segments +=
                    DownloadHlsSegment(
                        remoteUri = line,
                        ordinal = ordinal,
                        durationText = duration.normalizedText,
                        durationMillis = duration.milliseconds,
                        title = duration.title,
                        extension = "ts",
                        localPartKey = DownloadHlsPartNames.segment(ordinal),
                    )
                pendingDuration = null
            }
        }
    }
    val parsedVersion =
        version
            ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.MissingRequiredTag)
    val parsedTargetDurationSeconds =
        targetDurationSeconds
            ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.MissingRequiredTag)
    val parsedMediaSequence =
        mediaSequence
            ?: return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.MissingRequiredTag)
    if (!playlistTypeVod || !endList || pendingDuration != null || segments.isEmpty()) {
        return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.MissingRequiredTag)
    }
    if (lines.lastOrNull { line -> line.isNotEmpty() } != "#EXT-X-ENDLIST") {
        return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.IncompletePlaylist)
    }

    if (parsedTargetDurationSeconds.toLong() > Long.MAX_VALUE / 1_000L) {
        return DownloadHlsParseResult.Failure(DownloadHlsRejectReason.InvalidMedia)
    }
    return DownloadHlsParseResult.Media(
        DownloadHlsMediaPlaylist(
            version = parsedVersion,
            targetDurationSeconds = parsedTargetDurationSeconds,
            targetDurationMillis = parsedTargetDurationSeconds.toLong() * 1_000L,
            mediaSequence = parsedMediaSequence,
            segments = segments,
        ),
    )
}

private data class ParsedDuration(
    val normalizedText: String,
    val milliseconds: Long,
    val title: String,
)

private fun parseDuration(raw: String): ParsedDuration? {
    val pieces = raw.split(',', limit = 2)
    val number = pieces.firstOrNull()?.trim().orEmpty()
    if (number.isEmpty()) return null
    val dot = number.indexOf('.')
    val wholeText = if (dot < 0) number else number.substring(0, dot)
    val fractionText = if (dot < 0) "" else number.substring(dot + 1)
    if (wholeText.isEmpty() ||
        wholeText.any { character -> !character.isDigit() } ||
        fractionText.any { character -> !character.isDigit() }
    ) {
        return null
    }
    if (fractionText.length > 3) return null
    val whole = wholeText.toLongOrNull() ?: return null
    if (whole > (Long.MAX_VALUE - 999L) / 1_000L) return null
    val milliseconds = whole * 1_000L + fractionText.padEnd(3, '0').toLongOrNull().orZero()
    if (milliseconds <= 0L) return null
    return ParsedDuration(
        normalizedText = "$whole.${fractionText.padEnd(3, '0')}",
        milliseconds = milliseconds,
        title = pieces.getOrNull(1).orEmpty(),
    )
}

private fun Long?.orZero(): Long = this ?: 0L

private fun playlistLines(text: String): List<String>? {
    if (text.isEmpty()) return emptyList()
    if (text.encodeToByteArray().size > MAX_HLS_PLAYLIST_BYTES) return null
    val lines = text.removePrefix("\uFEFF").split('\n')
    if (lines.any { line -> line.removeSuffix("\r").length > MAX_HLS_LINE_LENGTH }) return null
    return lines.map { line -> line.removeSuffix("\r").trim() }
}

/** Only origin-relative, traversal-free resources from the verified HLS subset are accepted. */
private fun isSafeRelativeResource(
    value: String,
    extension: String,
): Boolean {
    if (
        value.isEmpty() ||
        value.startsWith('/') ||
        value.startsWith("\\") ||
        value.startsWith("//") ||
        value.any { character -> character.code < 0x20 || character == '\\' || character == '#' }
    ) {
        return false
    }
    val path = value.substringBefore('?')
    if (path.isEmpty() || path.split('/').any { part -> part.isEmpty() || part == "." || part == ".." }) return false
    if (path.substringAfterLast('.', missingDelimiterValue = "").lowercase() != extension) return false
    val schemeEnd = value.indexOf(':')
    val slashEnd = value.indexOf('/')
    if (schemeEnd >= 0 && (slashEnd < 0 || schemeEnd < slashEnd)) return false
    return true
}

/** Canonicalizes an HLS attribute list without retaining its URI-bearing sibling. */
private fun canonicalizeAttributes(raw: String): String? {
    if (raw.isBlank()) return null
    val attributes = mutableListOf<Pair<String, String>>()
    var index = 0
    while (index < raw.length) {
        val equals = raw.indexOf('=', index)
        if (equals <= index) return null
        val key = raw.substring(index, equals).trim()
        if (
            key.isEmpty() ||
            key !in ALLOWED_MASTER_ATTRIBUTE_KEYS ||
            key.any { character -> !character.isUpperCase() && !character.isDigit() && character != '-' }
        ) {
            return null
        }
        index = equals + 1
        val quoted = index < raw.length && raw[index] == '"'
        val valueStart = index
        if (quoted) {
            index++
            val close = raw.indexOf('"', index)
            if (close < 0) return null
            val value = raw.substring(index, close)
            if (value.any { character -> character.code < 0x20 }) return null
            attributes += key to "\"$value\""
            index = close + 1
        } else {
            val comma = raw.indexOf(',', index)
            val end = if (comma < 0) raw.length else comma
            val value = raw.substring(valueStart, end).trim()
            if (value.isEmpty() || value.any { character -> character.code < 0x20 || character == '"' }) return null
            attributes += key to value
            index = end
        }
        if (index == raw.length) break
        if (raw[index] != ',') return null
        index++
        if (index == raw.length) return null
    }
    if (attributes.map { (key, _) -> key }.distinct().size != attributes.size) return null
    return attributes.sortedBy { (key, _) -> key }.joinToString(",") { (key, value) -> "$key=$value" }
}

private val ALLOWED_MASTER_ATTRIBUTE_KEYS =
    setOf(
        "AVERAGE-BANDWIDTH",
        "AUDIO",
        "BANDWIDTH",
        "CLOSED-CAPTIONS",
        "CODECS",
        "FRAME-RATE",
        "RESOLUTION",
        "SUBTITLES",
        "VIDEO-RANGE",
    )
