// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.repository.LocalSubtitleInstallReservation
import com.jellyscope.core.data.repository.LocalSubtitleInstallResult
import com.jellyscope.core.data.repository.LocalSubtitleMutationCoordinator
import com.jellyscope.core.data.repository.LocalSubtitleSyncRepository
import com.jellyscope.core.data.repository.OpenSubtitlesRepository
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import com.jellyscope.core.domain.model.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

data class InstallLocalSubtitleRequest(
    val context: LocalSubtitleContext,
    val provider: String,
    val providerSubtitleId: String,
    val providerFileId: String,
    val language: String,
    val label: String,
    val releaseName: String?,
    val declaredFormat: String?,
    val hearingImpaired: Boolean,
    val forced: Boolean,
    val trusted: Boolean,
    val bytes: ByteArray,
)

class InstallLocalSubtitleAction internal constructor(
    private val coordinator: LocalSubtitleMutationCoordinator,
    private val workerDispatcher: CoroutineDispatcher,
    private val payloadEncoder: LocalSubtitlePayloadEncoder,
) {
    constructor(
        coordinator: LocalSubtitleMutationCoordinator,
        workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(coordinator, workerDispatcher, DefaultLocalSubtitlePayloadEncoder)

    internal fun reserve(context: LocalSubtitleContext): LocalSubtitleInstallReservation = coordinator.reserveInstall(context)

    suspend operator fun invoke(request: InstallLocalSubtitleRequest): LocalSubtitleAsset = install(reserve(request.context), request).asset

    internal suspend fun install(
        reservation: LocalSubtitleInstallReservation,
        request: InstallLocalSubtitleRequest,
    ): LocalSubtitleInstallResult {
        val normalized =
            withContext(workerDispatcher) {
                normalizeSubtitle(request.bytes, request.declaredFormat).let { subtitle ->
                    NormalizedSubtitlePayload(
                        originalFormat = subtitle.originalFormat,
                        webVttBytes = payloadEncoder.encode(subtitle.webVtt),
                    )
                }
            }
        val now = Clock.System.now().toEpochMilliseconds()
        val identity = stableAssetIdentity(request, now)
        val fileId = "$identity.vtt"
        val asset =
            LocalSubtitleAsset(
                id = identity,
                serverId = request.context.serverId,
                userId = request.context.userId,
                itemId = request.context.itemId,
                mediaSourceId = request.context.mediaSourceId,
                provider = request.provider,
                providerSubtitleId = request.providerSubtitleId,
                providerFileId = request.providerFileId,
                language = request.language,
                label = request.label,
                releaseName = request.releaseName,
                originalFormat = normalized.originalFormat,
                mimeType = "text/vtt",
                fileId = fileId,
                hearingImpaired = request.hearingImpaired,
                forced = request.forced,
                trusted = request.trusted,
                createdAtEpochMs = now,
                lastUsedAtEpochMs = now,
                syncState = LocalSubtitleSyncState.Pending,
            )
        return coordinator.commitInstall(
            reservation = reservation,
            candidate = asset,
            webVttBytes = normalized.webVttBytes,
        )
    }
}

class DownloadAndInstallOpenSubtitleAction(
    private val repository: OpenSubtitlesRepository,
    private val installLocalSubtitleAction: InstallLocalSubtitleAction,
) {
    suspend operator fun invoke(
        context: LocalSubtitleContext,
        result: OpenSubtitleSearchResult,
    ): InstalledOpenSubtitle {
        require(result.selectable) { result.unavailableReason ?: "Subtitle result is unavailable." }
        val reservation = installLocalSubtitleAction.reserve(context)
        val download = repository.download(result.fileId)
        val installed =
            installLocalSubtitleAction.install(
                reservation = reservation,
                request =
                    InstallLocalSubtitleRequest(
                        context = context,
                        provider = "OpenSubtitles",
                        providerSubtitleId = result.subtitleId,
                        providerFileId = result.fileId,
                        language = result.language,
                        label = result.releaseName ?: result.fileName,
                        releaseName = result.releaseName,
                        declaredFormat = result.format,
                        hearingImpaired = result.hearingImpaired,
                        forced = result.forced,
                        trusted = result.trusted,
                        bytes = download.bytes,
                    ),
            )
        return InstalledOpenSubtitle(
            asset = installed.asset,
            selectionApplied = installed.selectionApplied,
            quotaRemaining = download.remaining,
            quotaResetTime = download.resetTime,
        )
    }
}

data class InstalledOpenSubtitle(
    val asset: LocalSubtitleAsset,
    val selectionApplied: Boolean,
    val quotaRemaining: Int?,
    val quotaResetTime: String?,
)

class DeleteLocalSubtitleAction(
    private val coordinator: LocalSubtitleMutationCoordinator,
) {
    suspend operator fun invoke(assetId: String) = coordinator.deleteAsset(assetId)
}

class ClearLocalSubtitlesAction(
    private val coordinator: LocalSubtitleMutationCoordinator,
) {
    suspend operator fun invoke() = coordinator.clearAllLocalSubtitles()
}

class RetryLocalSubtitleSyncAction(
    private val assetStore: LocalSubtitleAssetStore,
    private val syncRepository: LocalSubtitleSyncRepository,
) {
    suspend operator fun invoke(
        session: Session,
        assetId: String,
    ) {
        val asset = assetStore.get(assetId) ?: return
        syncRepository.retry(session, asset)
    }
}

internal data class NormalizedSubtitle(
    val originalFormat: String,
    val webVtt: String,
)

private data class NormalizedSubtitlePayload(
    val originalFormat: String,
    val webVttBytes: ByteArray,
)

internal fun interface LocalSubtitlePayloadEncoder {
    fun encode(webVtt: String): ByteArray
}

private object DefaultLocalSubtitlePayloadEncoder : LocalSubtitlePayloadEncoder {
    override fun encode(webVtt: String): ByteArray = webVtt.encodeToByteArray()
}

internal fun normalizeSubtitle(
    bytes: ByteArray,
    declaredFormat: String?,
): NormalizedSubtitle {
    require(bytes.isNotEmpty()) { "Subtitle file is empty." }
    require(bytes.size <= MAX_LOCAL_SUBTITLE_BYTES) { "Subtitle file exceeds the 5 MiB limit." }
    val text = decodeSubtitleText(bytes).replace("\r\n", "\n").replace('\r', '\n').trim()
    require(text.isNotEmpty()) { "Subtitle file is empty." }
    val prefix = text.take(256).lowercase()
    require(!prefix.contains("<html") && !prefix.contains("<!doctype")) { "Downloaded content is not a subtitle." }
    val declared = declaredFormat?.trim()?.lowercase()?.removePrefix(".")
    return when {
        text.startsWith("WEBVTT") || declared == "vtt" || declared == "webvtt" ->
            NormalizedSubtitle("vtt", text.ensureWebVttHeader())
        declared == "srt" || SRT_TIMING.containsMatchIn(text) ->
            NormalizedSubtitle("srt", srtToWebVtt(text))
        else -> throw IllegalArgumentException("Unsupported subtitle format.")
    }
}

private fun decodeSubtitleText(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return decodeUtf16(bytes.copyOfRange(2, bytes.size), littleEndian = true)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return decodeUtf16(bytes.copyOfRange(2, bytes.size), littleEndian = false)
    }
    val utf8 = bytes.removeUtf8Bom().decodeToString(throwOnInvalidSequence = false)
    if ('\uFFFD' !in utf8 && utf8.none { character -> character.code in 0..8 || character.code in 14..31 }) return utf8
    return bytes.joinToString(separator = "") { byte -> WINDOWS_1252[byte.toInt() and 0xFF].toString() }
}

private fun decodeUtf16(
    bytes: ByteArray,
    littleEndian: Boolean,
): String =
    buildString(bytes.size / 2) {
        var index = 0
        while (index + 1 < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            val second = bytes[index + 1].toInt() and 0xFF
            append(if (littleEndian) ((second shl 8) or first).toChar() else ((first shl 8) or second).toChar())
            index += 2
        }
    }

private fun ByteArray.removeUtf8Bom(): ByteArray =
    if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) copyOfRange(3, size) else this

private fun String.ensureWebVttHeader(): String = if (startsWith("WEBVTT")) "$this\n" else "WEBVTT\n\n$this\n"

private fun srtToWebVtt(text: String): String =
    "WEBVTT\n\n" +
        text.replace(
            SRT_ARROW_COMMA,
        ) { match -> "${match.groupValues[1]}.${match.groupValues[2]} --> ${match.groupValues[3]}.${match.groupValues[4]}" } +
        "\n"

private fun stableAssetIdentity(
    request: InstallLocalSubtitleRequest,
    createdAtEpochMs: Long,
): String {
    val seed =
        listOf(
            request.context.serverId,
            request.context.userId,
            request.context.itemId,
            request.context.mediaSourceId,
            request.provider,
            request.providerFileId,
        ).joinToString("|")
    return "subtitle-${seed.hashCode().toUInt().toString(16)}-${createdAtEpochMs.toULong().toString(16)}"
}

private const val MAX_LOCAL_SUBTITLE_BYTES = 5 * 1024 * 1024
private val SRT_TIMING = Regex("\\d{2}:\\d{2}:\\d{2},\\d{3}\\s+-->\\s+\\d{2}:\\d{2}:\\d{2},\\d{3}")
private val SRT_ARROW_COMMA = Regex("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})\\s+-->\\s+(\\d{2}:\\d{2}:\\d{2}),(\\d{3})")
private val WINDOWS_1252 =
    CharArray(256) { index -> index.toChar() }.also { table ->
        table[0x80] = '\u20AC'
        table[0x82] = '\u201A'
        table[0x83] =
            '\u0192'
        table[0x84] = '\u201E'
        table[0x85] = '\u2026'
        table[0x86] = '\u2020'
        table[0x87] = '\u2021'
        table[0x88] = '\u02C6'
        table[0x89] =
            '\u2030'
        table[0x8A] = '\u0160'
        table[0x8B] = '\u2039'
        table[0x8C] = '\u0152'
        table[0x8E] = '\u017D'
        table[0x91] = '\u2018'
        table[0x92] =
            '\u2019'
        table[0x93] = '\u201C'
        table[0x94] = '\u201D'
        table[0x95] = '\u2022'
        table[0x96] = '\u2013'
        table[0x97] = '\u2014'
        table[0x98] =
            '\u02DC'
        table[0x99] = '\u2122'
        table[0x9A] = '\u0161'
        table[0x9B] = '\u203A'
        table[0x9C] = '\u0153'
        table[0x9E] =
            '\u017E'
        table[0x9F] = '\u0178'
    }
