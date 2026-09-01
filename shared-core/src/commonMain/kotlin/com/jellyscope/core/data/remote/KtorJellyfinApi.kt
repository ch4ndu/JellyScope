// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.estimateFixedDownloadBytes
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.resolveServerRelativeUrl
import com.jellyscope.core.domain.playback.subtitleHonestTranscodingUrl
import com.jellyscope.core.domain.playback.ticksToMilliseconds
import com.jellyscope.core.security.CredentialOriginGuard
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.safeDiagnosticType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.parseQueryString
import io.ktor.utils.io.errors.IOException
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

private val mediaSegmentTypes = listOf("Intro", "Outro", "Recap", "Preview", "Commercial")

class KtorJellyfinApi(
    private val client: HttpClient,
    private val authHeaderProvider: AuthHeaderProvider,
    private val downloadClient: HttpClient = client,
) : JellyfinApi {
    override suspend fun getCurrentUser(context: AuthenticatedRequestContext): UserDto =
        authenticatedGet(context, "/Users/${context.userId}") {}

    override suspend fun preflightOriginalDownload(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
    ): OriginalDownloadPreflightResult {
        val validation = validateOriginalDownloadSource(context, itemId, mediaSourceId)
        if (validation is OriginalSourceValidation.Rejected) {
            return OriginalDownloadPreflightResult.Rejected(validation.failure)
        }
        validation as OriginalSourceValidation.Ready

        val trustedUrl =
            trustedOriginalStreamUrl(context, itemId, mediaSourceId)
                ?: return OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.ServerUnavailable)

        return try {
            val authorization = authHeaderProvider.authHeader(token = context.accessToken)
            downloadClient
                .prepareGet(trustedUrl) {
                    applyOriginalDownloadHeaders(
                        authorization = authorization,
                        range = "bytes=0-0",
                    )
                }.execute { response ->
                    val responseFailure = response.originalDownloadStatusFailure()
                    if (responseFailure != null) {
                        return@execute OriginalDownloadPreflightResult.Rejected(responseFailure)
                    }

                    if (response.headers[HttpHeaders.ContentRange] == null) {
                        return@execute OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.SizeUnavailable)
                    }
                    val range =
                        response.validatedContentRange(expectedStart = 0L, requireCompleteTail = false)
                            ?: return@execute OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.SourceChanged)
                    if (range.endByteInclusive != 0L || !response.hasExpectedContentLength(1L)) {
                        return@execute OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.SourceChanged)
                    }
                    val declaredBytes = validation.declaredBytes
                    if (declaredBytes != null && declaredBytes != range.totalBytes) {
                        return@execute OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.SourceChanged)
                    }
                    val validator =
                        response.originalLastModified()
                            ?: return@execute OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.SourceChanged)

                    OriginalDownloadPreflightResult.Ready(
                        OriginalDownloadSource(
                            itemId = itemId,
                            mediaSourceId = mediaSourceId,
                            totalBytes = range.totalBytes,
                            lastModified = validator,
                            backendSource = validation.backendSource,
                            audioStreamIndices = validation.audioStreamIndices,
                            embeddedSubtitleStreamIndices = validation.embeddedSubtitleStreamIndices,
                            externalSubtitleStreamIndices = validation.externalSubtitleStreamIndices,
                            unsupportedExternalSubtitleStreamIndices = validation.unsupportedExternalSubtitleStreamIndices,
                        ),
                    )
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.Network)
        } catch (_: Throwable) {
            OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.ServerUnavailable)
        }
    }

    override suspend fun <T> streamOriginalDownload(
        context: AuthenticatedRequestContext,
        source: OriginalDownloadSource,
        startByte: Long,
        consume: suspend (OriginalDownloadStream) -> T,
    ): OriginalDownloadStreamResult<T> {
        if (startByte < 0L || startByte >= source.totalBytes) {
            return OriginalDownloadStreamResult.Rejected(OriginalDownloadFailure.SourceChanged)
        }
        val validation =
            validateOriginalDownloadSource(
                context = context,
                itemId = source.itemId,
                mediaSourceId = source.mediaSourceId,
            )
        if (validation is OriginalSourceValidation.Rejected) {
            return OriginalDownloadStreamResult.Rejected(validation.failure)
        }
        validation as OriginalSourceValidation.Ready
        if (validation.declaredBytes != null && validation.declaredBytes != source.totalBytes) {
            return OriginalDownloadStreamResult.Rejected(OriginalDownloadFailure.SourceChanged)
        }
        if (
            validation.audioStreamIndices != source.audioStreamIndices ||
            validation.embeddedSubtitleStreamIndices != source.embeddedSubtitleStreamIndices ||
            validation.externalSubtitleStreamIndices != source.externalSubtitleStreamIndices ||
            validation.unsupportedExternalSubtitleStreamIndices != source.unsupportedExternalSubtitleStreamIndices
        ) {
            return OriginalDownloadStreamResult.Rejected(OriginalDownloadFailure.SourceChanged)
        }

        val trustedUrl =
            trustedOriginalStreamUrl(context, source.itemId, source.mediaSourceId)
                ?: return OriginalDownloadStreamResult.Rejected(OriginalDownloadFailure.ServerUnavailable)

        return try {
            val authorization = authHeaderProvider.authHeader(token = context.accessToken)
            downloadClient
                .prepareGet(trustedUrl) {
                    applyOriginalDownloadHeaders(
                        authorization = authorization,
                        range = "bytes=$startByte-",
                        ifRange = source.lastModified,
                    )
                }.execute { response ->
                    val responseFailure = response.originalDownloadStatusFailure()
                    if (responseFailure != null) {
                        return@execute OriginalDownloadStreamResult.Rejected(responseFailure)
                    }

                    val range =
                        response.validatedContentRange(
                            expectedStart = startByte,
                            requireCompleteTail = true,
                        ) ?: return@execute OriginalDownloadStreamResult.Rejected(OriginalDownloadFailure.SourceChanged)
                    if (range.totalBytes != source.totalBytes ||
                        range.endByteInclusive != source.totalBytes - 1L ||
                        response.originalLastModified() != source.lastModified ||
                        !response.hasExpectedContentLength(source.totalBytes - startByte)
                    ) {
                        return@execute OriginalDownloadStreamResult.Rejected(OriginalDownloadFailure.SourceChanged)
                    }

                    val stream =
                        OriginalDownloadStream(
                            source = source,
                            startByte = startByte,
                            endByteInclusive = range.endByteInclusive,
                            body = response.bodyAsChannel(),
                        )
                    try {
                        try {
                            OriginalDownloadStreamResult.Success(consume(stream))
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Throwable) {
                            throw OriginalDownloadConsumerFailure(failure)
                        }
                    } finally {
                        stream.close()
                    }
                }
        } catch (consumerFailure: OriginalDownloadConsumerFailure) {
            throw consumerFailure.failure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            OriginalDownloadStreamResult.Rejected(OriginalDownloadFailure.Network)
        } catch (_: Throwable) {
            OriginalDownloadStreamResult.Rejected(OriginalDownloadFailure.ServerUnavailable)
        }
    }

    override suspend fun preflightFixedDownload(
        context: AuthenticatedRequestContext,
        request: FixedDownloadRequest,
    ): FixedDownloadPreflightResult {
        val validation = validateFixedDownloadSource(context, request)
        if (validation is FixedSourceValidation.Rejected) {
            return FixedDownloadPreflightResult.Rejected(validation.failure)
        }
        validation as FixedSourceValidation.Ready

        val subtitleFormat = validation.subtitleFormat
        val effectiveRequest = request.copy(subtitleFormat = subtitleFormat)
        val response =
            try {
                authenticatedPostReturning<PlaybackInfoRequestDto, PlaybackInfoResponseDto>(
                    context = context,
                    path = "/Items/${request.itemId}/PlaybackInfo",
                    body =
                        PlaybackInfoRequestDto(
                            userId = context.userId,
                            startTimeTicks = 0L,
                            mediaSourceId = request.mediaSourceId,
                            autoOpenLiveStream = false,
                            deviceProfile = fixedDownloadDeviceProfile(effectiveRequest),
                            audioStreamIndex = validation.audioStreamIndex,
                            subtitleStreamIndex = effectiveRequest.subtitleStreamIndex,
                            maxStreamingBitrate = request.quality.maxBitrateBps,
                            maxAudioChannels = null,
                            enableDirectPlay = false,
                            enableDirectStream = false,
                            enableTranscoding = true,
                            allowAudioStreamCopy = false,
                            allowVideoStreamCopy = false,
                            alwaysBurnInSubtitleWhenTranscoding =
                                true.takeIf {
                                    request.subtitleSelection is DownloadSubtitleSelection.Embedded
                                },
                        ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return fixedDownloadPreflightRejected(
                    failure = failure.toFixedDownloadFailure(),
                    reason = FixedDownloadDiagnosticReason.PlaybackInfoRequestFailed,
                    requestKind = request.requestKind,
                    throwable = failure,
                )
            }
        val matchingSources = response.mediaSources.filter { source -> source.id == request.mediaSourceId }
        val mediaSource =
            matchingSources.singleOrNull()
                ?: return fixedDownloadPreflightRejected(
                    failure = FixedDownloadFailure.SourceChanged,
                    reason =
                        if (matchingSources.isEmpty()) {
                            FixedDownloadDiagnosticReason.PlaybackMediaSourceMissing
                        } else {
                            FixedDownloadDiagnosticReason.PlaybackMediaSourceDuplicated
                        },
                    candidateCount = matchingSources.size,
                    requestKind = request.requestKind,
                )
        if (!mediaSource.supportsTranscoding) {
            return fixedDownloadPreflightRejected(
                failure = FixedDownloadFailure.UnsupportedArtifact,
                reason = FixedDownloadDiagnosticReason.TranscodingUnsupported,
                requestKind = request.requestKind,
            )
        }
        val rawUrl =
            mediaSource.transcodingUrl?.trim()
                ?: return fixedDownloadPreflightRejected(
                    failure = FixedDownloadFailure.ServerUnavailable,
                    reason = FixedDownloadDiagnosticReason.TranscodingUrlMissing,
                    requestKind = request.requestKind,
                )
        if (!mediaSource.transcodingSubProtocol.equals("hls", ignoreCase = true)) {
            return fixedDownloadPreflightRejected(
                failure = FixedDownloadFailure.UnsupportedArtifact,
                reason = FixedDownloadDiagnosticReason.TranscodingProtocolUnsupported,
                requestKind = request.requestKind,
            )
        }
        if (!mediaSource.transcodingContainer.isFixedHlsContainer() &&
            !rawUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        ) {
            return fixedDownloadPreflightRejected(
                failure = FixedDownloadFailure.UnsupportedArtifact,
                reason = FixedDownloadDiagnosticReason.TranscodingContainerUnsupported,
                requestKind = request.requestKind,
            )
        }
        val honestUrl = subtitleHonestTranscodingUrl(rawUrl, effectiveRequest.subtitleStreamIndex)
        val resourceUrl =
            resolveFixedResourceUrl(context, honestUrl)
                ?: return fixedDownloadPreflightRejected(
                    failure = FixedDownloadFailure.SourceChanged,
                    reason = FixedDownloadDiagnosticReason.TranscodingUrlRejected,
                    requestKind = request.requestKind,
                )
        val deviceId =
            fixedDownloadQueryParameter(resourceUrl, "deviceId")
                ?: return fixedDownloadPreflightRejected(
                    failure = FixedDownloadFailure.SourceChanged,
                    reason = FixedDownloadDiagnosticReason.DeviceIdMissing,
                    requestKind = request.requestKind,
                )
        val playSessionId =
            response.playSessionId
                ?.trim()
                ?.takeIf { value -> value.isBoundedDownloadIdentity() }
                ?: return fixedDownloadPreflightRejected(
                    failure = FixedDownloadFailure.SourceChanged,
                    reason = FixedDownloadDiagnosticReason.PlaySessionIdMissing,
                    requestKind = request.requestKind,
                )
        val estimate =
            estimateFixedDownloadBytes(request.quality.maxBitrateBps, validation.durationMs)
                ?: return fixedDownloadPreflightRejected(
                    failure = FixedDownloadFailure.SizeUnavailable,
                    reason = FixedDownloadDiagnosticReason.EstimateUnavailable,
                    requestKind = request.requestKind,
                )

        fixedDownloadLogger.i {
            "stage=fixed-download event=preflight-ready requestKind=${request.requestKind.name} result=Ready"
        }

        return FixedDownloadPreflightResult.Ready(
            FixedDownloadSource(
                itemId = request.itemId,
                mediaSourceId = request.mediaSourceId,
                quality = request.quality,
                audioStreamIndex = validation.audioStreamIndex,
                subtitleStreamIndex = effectiveRequest.subtitleStreamIndex,
                durationMs = validation.durationMs,
                estimatedBytes = estimate,
                transcodingUrl = resourceUrl,
                deviceId = deviceId,
                playSessionId = playSessionId,
            ),
        )
    }

    override suspend fun <T> streamFixedDownloadResource(
        context: AuthenticatedRequestContext,
        source: FixedDownloadSource,
        resourceUrl: String,
        maxBytes: Long,
        consume: suspend (FixedDownloadResource) -> T,
    ): FixedDownloadResourceResult<T> {
        if (maxBytes <= 0L || !resourceUrl.isBoundedResourceUrl()) {
            return FixedDownloadResourceResult.Rejected(
                failure = FixedDownloadFailure.SourceChanged,
                reason = FixedDownloadResourceRejectReason.InvalidRequest,
            )
        }
        val trustedUrl =
            resolveFixedResourceUrl(context, resourceUrl)
                ?: return FixedDownloadResourceResult.Rejected(
                    failure = FixedDownloadFailure.SourceChanged,
                    reason = FixedDownloadResourceRejectReason.UntrustedResourceUrl,
                )
        return try {
            val authorization = authHeaderProvider.authHeader(token = context.accessToken)
            downloadClient
                .prepareGet(trustedUrl) {
                    header(HttpHeaders.Authorization, authorization)
                    header(HttpHeaders.AcceptEncoding, "identity")
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = FIXED_DOWNLOAD_SOCKET_TIMEOUT_MS
                    }
                }.execute { response ->
                    val responseFailure = response.fixedDownloadStatusFailure()
                    if (responseFailure != null) {
                        return@execute FixedDownloadResourceResult.Rejected(
                            failure = responseFailure,
                            reason = FixedDownloadResourceRejectReason.HttpStatusRejected,
                        )
                    }
                    val declaredLength = response.headers[HttpHeaders.ContentLength]
                    val contentLength =
                        declaredLength
                            ?.takeIf { value -> value.isNotEmpty() && value.all { character -> character in '0'..'9' } }
                            ?.toLongOrNull()
                    if (declaredLength != null && contentLength == null) {
                        return@execute FixedDownloadResourceResult.Rejected(
                            failure = FixedDownloadFailure.SourceChanged,
                            reason = FixedDownloadResourceRejectReason.InvalidDeclaredLength,
                        )
                    }
                    if (contentLength != null && contentLength > maxBytes) {
                        return@execute FixedDownloadResourceResult.Rejected(
                            failure = FixedDownloadFailure.PayloadTooLarge,
                            reason = FixedDownloadResourceRejectReason.DeclaredLengthTooLarge,
                        )
                    }
                    val resource =
                        FixedDownloadResource(
                            source = source,
                            requestedUrl = trustedUrl,
                            body = response.bodyAsChannel(),
                            contentLength = contentLength,
                        )
                    try {
                        try {
                            FixedDownloadResourceResult.Success(consume(resource))
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Throwable) {
                            throw FixedDownloadResourceConsumerFailure(failure)
                        }
                    } finally {
                        resource.close()
                    }
                }
        } catch (consumerFailure: FixedDownloadResourceConsumerFailure) {
            throw consumerFailure.failure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            FixedDownloadResourceResult.Rejected(
                failure = FixedDownloadFailure.Network,
                reason = FixedDownloadResourceRejectReason.NetworkFailure,
            )
        } catch (_: Throwable) {
            FixedDownloadResourceResult.Rejected(
                failure = FixedDownloadFailure.ServerUnavailable,
                reason = FixedDownloadResourceRejectReason.UnexpectedTransportFailure,
            )
        }
    }

    override suspend fun stopFixedDownloadEncoding(
        context: AuthenticatedRequestContext,
        source: FixedDownloadSource,
    ): FixedDownloadCleanupResult =
        try {
            val response =
                downloadClient.delete("${context.serverUrl.trimEnd('/')}/Videos/ActiveEncodings") {
                    header(
                        HttpHeaders.Authorization,
                        authHeaderProvider.authHeader(token = context.accessToken),
                    )
                    parameter("deviceId", source.deviceId)
                    parameter("playSessionId", source.playSessionId)
                    timeout {
                        requestTimeoutMillis = FIXED_DOWNLOAD_CLEANUP_TIMEOUT_MS
                        socketTimeoutMillis = FIXED_DOWNLOAD_CLEANUP_TIMEOUT_MS
                    }
                }
            if (response.status == HttpStatusCode.NoContent) {
                FixedDownloadCleanupResult.Stopped
            } else {
                FixedDownloadCleanupResult.Rejected
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            FixedDownloadCleanupResult.Rejected
        }

    override suspend fun uploadSubtitle(
        context: AuthenticatedRequestContext,
        itemId: String,
        subtitle: UploadSubtitleDto,
    ) {
        authenticatedPost(context, "/Videos/$itemId/Subtitles", subtitle)
    }

    override suspend fun getSubtitleText(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
    ): String =
        authenticatedGetText(
            context,
            "/Videos/$itemId/$mediaSourceId/Subtitles/$streamIndex/Stream.vtt",
        )

    override suspend fun getSubtitleTextBounded(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
        maxBytes: Int,
    ): ByteArray {
        require(maxBytes in 0 until Int.MAX_VALUE) { "Maximum subtitle bytes must be bounded." }
        val path = "/Videos/$itemId/$mediaSourceId/Subtitles/$streamIndex/Stream.vtt"
        return try {
            downloadClient
                .prepareGet("${context.serverUrl}$path") {
                    header(HttpHeaders.Authorization, authHeaderProvider.authHeader(token = context.accessToken))
                }.execute { response ->
                    when {
                        response.status.value in 200..299 -> Unit
                        response.status == HttpStatusCode.Unauthorized -> throw JellyfinApiException.Unauthorized
                        response.status.value >= 500 -> throw JellyfinApiException.ServerError(response.status.value)
                        else -> throw JellyfinApiException.NotReachable
                    }
                    val channel = response.bodyAsChannel()
                    // The one-byte overflow proves oversize without allocating or collecting the
                    // complete server response.  The channel is closed with the response scope.
                    val bounded = ByteArray(maxBytes + 1)
                    var count = 0
                    while (count < bounded.size) {
                        val read = channel.readAvailable(bounded, count, bounded.size - count)
                        if (read < 0) break
                        if (read == 0) continue
                        count += read
                    }
                    if (count > maxBytes) throw JellyfinApiException.PayloadTooLarge
                    bounded.copyOf(count)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: JellyfinApiException) {
            throw failure
        } catch (_: IOException) {
            throw JellyfinApiException.NotReachable
        } catch (failure: Throwable) {
            throw JellyfinApiException.Unexpected(failure)
        }
    }

    override suspend fun postClientLogDocument(
        context: AuthenticatedRequestContext,
        content: String,
    ): ClientLogDocumentDto =
        try {
            val response =
                client.post("${context.serverUrl}/ClientLog/Document") {
                    header(
                        HttpHeaders.Authorization,
                        authHeaderProvider.authHeader(token = context.accessToken),
                    )
                    contentType(ContentType.Text.Plain)
                    setBody(content)
                }

            when {
                response.status.value in 200..299 -> response.body()
                response.status == HttpStatusCode.Forbidden -> throw JellyfinApiException.ClientLogUploadDisallowed
                response.status == HttpStatusCode.Unauthorized -> throw JellyfinApiException.Unauthorized
                response.status.value >= 500 -> throw JellyfinApiException.ServerError(response.status.value)
                else -> throw JellyfinApiException.NotReachable
            }
        } catch (exception: JellyfinApiException) {
            throw exception
        } catch (exception: SerializationException) {
            throw JellyfinApiException.Unexpected(exception)
        } catch (exception: IOException) {
            throw JellyfinApiException.NotReachable
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw JellyfinApiException.Unexpected(exception)
        }

    override suspend fun getPublicSystemInfo(serverUrl: String): PublicSystemInfoDto =
        try {
            val response =
                client.get("$serverUrl/System/Info/Public") {
                    header(HttpHeaders.Authorization, authHeaderProvider.authHeader(token = null))
                }

            if (response.status.value !in 200..299) {
                throw JellyfinApiException.NotReachable
            }

            response.body()
        } catch (exception: JellyfinApiException) {
            throw exception
        } catch (exception: SerializationException) {
            throw JellyfinApiException.NotReachable
        } catch (exception: IOException) {
            throw JellyfinApiException.NotReachable
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw JellyfinApiException.Unexpected(exception)
        }

    override suspend fun authenticateByName(
        serverUrl: String,
        username: String,
        password: String,
    ): AuthenticationResultDto =
        try {
            val response =
                client.post("$serverUrl/Users/AuthenticateByName") {
                    header(HttpHeaders.Authorization, authHeaderProvider.authHeader(token = null))
                    contentType(ContentType.Application.Json)
                    setBody(AuthenticationRequestDto(username = username, password = password))
                }

            when {
                response.status.value in 200..299 -> response.body()
                response.status == HttpStatusCode.Unauthorized ->
                    throw JellyfinApiException.InvalidCredentials
                response.status.value >= 500 ->
                    throw JellyfinApiException.ServerError(response.status.value)
                else ->
                    throw JellyfinApiException.NotReachable
            }
        } catch (exception: JellyfinApiException) {
            throw exception
        } catch (exception: IOException) {
            throw JellyfinApiException.NotReachable
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw JellyfinApiException.Unexpected(exception)
        }

    override suspend fun initiateQuickConnect(serverUrl: String): QuickConnectResultDto =
        unauthenticatedPostReturning(
            serverUrl = serverUrl,
            path = "/QuickConnect/Initiate",
        )

    override suspend fun getQuickConnectState(
        serverUrl: String,
        secret: String,
    ): QuickConnectResultDto =
        unauthenticatedGet(
            serverUrl = serverUrl,
            path = "/QuickConnect/Connect",
        ) {
            parameter("Secret", secret)
        }

    override suspend fun authenticateWithQuickConnect(
        serverUrl: String,
        secret: String,
    ): AuthenticationResultDto =
        unauthenticatedPostReturning(
            serverUrl = serverUrl,
            path = "/Users/AuthenticateWithQuickConnect",
            body = QuickConnectAuthenticationRequestDto(secret = secret),
        )

    override suspend fun getUserViews(context: AuthenticatedRequestContext): UserViewsQueryResultDto =
        authenticatedGet(context = context, path = "/UserViews") {
            parameter("userId", context.userId)
        }

    override suspend fun getResumeItems(
        context: AuthenticatedRequestContext,
        limit: Int,
        fields: List<String>,
        parentId: String?,
        includeItemTypes: List<String>,
    ): BaseItemQueryResultDto =
        authenticatedGet(context = context, path = "/UserItems/Resume") {
            parameter("userId", context.userId)
            parameter("limit", limit)
            parentId?.takeIf { it.isNotBlank() }?.let { parameter("parentId", it) }
            if (includeItemTypes.isNotEmpty()) {
                parameter("includeItemTypes", includeItemTypes.joinToString(","))
            }
            applyDefaultItemParameters(fields = fields)
        }

    override suspend fun getItemDetail(
        context: AuthenticatedRequestContext,
        itemId: String,
        includePlaybackFields: Boolean,
    ): BaseItemDto =
        authenticatedGet(context = context, path = "/Items/$itemId") {
            parameter("userId", context.userId)
            applyDefaultItemParameters(
                fields = if (includePlaybackFields) mediaSourceItemFields else detailScreenItemFields,
                imageTypes = detailImageTypes,
            )
        }

    override suspend fun getSimilarItems(
        context: AuthenticatedRequestContext,
        itemId: String,
        limit: Int,
    ): BaseItemQueryResultDto =
        authenticatedGet(context = context, path = "/Items/$itemId/Similar") {
            parameter("userId", context.userId)
            parameter("limit", limit)
            applyDefaultItemParameters()
        }

    override suspend fun getSeasons(
        context: AuthenticatedRequestContext,
        seriesId: String,
    ): BaseItemQueryResultDto =
        authenticatedGet(context = context, path = "/Shows/$seriesId/Seasons") {
            parameter("userId", context.userId)
            applyDefaultItemParameters()
        }

    override suspend fun getEpisodes(
        context: AuthenticatedRequestContext,
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): BaseItemQueryResultDto =
        authenticatedGet(context = context, path = "/Shows/$seriesId/Episodes") {
            parameter("userId", context.userId)
            parameter("seasonId", seasonId)
            seasonIndex?.let { index -> parameter("season", index) }
            parameter("sortBy", "ParentIndexNumber,IndexNumber")
            parameter("sortOrder", "Ascending")
            applyDefaultItemParameters(fields = episodeStripFields)
        }

    override suspend fun getNextUp(
        context: AuthenticatedRequestContext,
        seriesId: String?,
        limit: Int,
        fields: List<String>,
        parentId: String?,
    ): BaseItemQueryResultDto =
        authenticatedGet(context = context, path = "/Shows/NextUp") {
            parameter("userId", context.userId)
            parameter("limit", limit)
            seriesId?.let { parameter("seriesId", it) }
            parentId?.takeIf { it.isNotBlank() }?.let { parameter("parentId", it) }
            applyDefaultItemParameters(fields = fields)
        }

    override suspend fun getUpcomingEpisodes(
        context: AuthenticatedRequestContext,
        limit: Int,
        fields: List<String>,
    ): BaseItemQueryResultDto =
        authenticatedGet(context = context, path = "/Shows/Upcoming") {
            parameter("userId", context.userId)
            parameter("limit", limit)
            applyDefaultItemParameters(fields = fields)
        }

    override suspend fun getLatestItems(
        context: AuthenticatedRequestContext,
        limit: Int,
        fields: List<String>,
        parentId: String?,
        includeItemTypes: List<String>,
    ): List<BaseItemDto> =
        authenticatedGet(context = context, path = "/Items/Latest") {
            parameter("userId", context.userId)
            parameter("limit", limit)
            parentId?.takeIf { it.isNotBlank() }?.let { parameter("parentId", it) }
            if (includeItemTypes.isNotEmpty()) {
                parameter("includeItemTypes", includeItemTypes.joinToString(","))
            }
            applyDefaultItemParameters(fields = fields)
        }

    override suspend fun getMovieRecommendations(
        context: AuthenticatedRequestContext,
        parentId: String,
        categoryLimit: Int,
        itemLimit: Int,
    ): List<RecommendationDto> =
        authenticatedGet(context = context, path = "/Movies/Recommendations") {
            parameter("userId", context.userId)
            parameter("parentId", parentId)
            parameter("categoryLimit", categoryLimit)
            parameter("itemLimit", itemLimit)
            parameter("fields", defaultItemFields.joinToString(","))
        }

    override suspend fun getItems(
        context: AuthenticatedRequestContext,
        query: ItemsQuery,
    ): BaseItemQueryResultDto =
        authenticatedGet(context = context, path = "/Items") {
            parameter("userId", context.userId)
            parameter("recursive", query.recursive)
            parameter("enableTotalRecordCount", query.enableTotalRecordCount)
            query.enableUserData?.let { parameter("enableUserData", it) }
            parameter("includeItemTypes", query.includeItemTypes.joinToString(","))
            query.limit?.let { parameter("limit", it) }
            if (query.fields.isNotEmpty()) {
                parameter("fields", query.fields.joinToString(","))
            }
            if (query.enableImageTypes.isNotEmpty()) {
                parameter("enableImageTypes", query.enableImageTypes.joinToString(","))
            }
            parameter("imageTypeLimit", query.imageTypeLimit)
            query.parentId?.takeIf { it.isNotBlank() }?.let { parameter("parentId", it) }
            query.startIndex?.let { parameter("startIndex", it) }
            query.sortBy?.let { parameter("sortBy", it) }
            query.sortOrder?.let { parameter("sortOrder", it) }
            query.searchTerm?.takeIf { it.isNotBlank() }?.let { parameter("searchTerm", it) }
            if (query.personIds.isNotEmpty()) {
                parameter("personIds", query.personIds.joinToString(","))
            }
            if (query.genres.isNotEmpty()) {
                parameter("genres", query.genres.joinToString(","))
            }
            if (query.genreIds.isNotEmpty()) {
                parameter("genreIds", query.genreIds.joinToString(","))
            }
            if (query.years.isNotEmpty()) {
                parameter("years", query.years.joinToString(","))
            }
            if (query.officialRatings.isNotEmpty()) {
                parameter("officialRatings", query.officialRatings.joinToString(","))
            }
            if (query.studioIds.isNotEmpty()) {
                parameter("studioIds", query.studioIds.joinToString(","))
            }
            if (query.tags.isNotEmpty()) {
                parameter("tags", query.tags.joinToString(","))
            }
            if (query.seriesStatus.isNotEmpty()) {
                parameter("seriesStatus", query.seriesStatus.joinToString(","))
            }
            if (query.filters.isNotEmpty()) {
                parameter("filters", query.filters.joinToString(",") { filter -> filter.apiValue })
            }
            query.ids
                ?.takeIf { ids -> ids.isNotEmpty() }
                ?.let { ids -> parameter("Ids", ids.joinToString(",")) }
        }

    override suspend fun getGenres(
        context: AuthenticatedRequestContext,
        parentId: String?,
    ): ItemFacetQueryResultDto =
        authenticatedGet(context = context, path = "/Genres") {
            parameter("userId", context.userId)
            parentId?.takeIf { it.isNotBlank() }?.let { parameter("parentId", it) }
        }

    override suspend fun getStudios(
        context: AuthenticatedRequestContext,
        parentId: String?,
    ): ItemFacetQueryResultDto =
        authenticatedGet(context = context, path = "/Studios") {
            parameter("userId", context.userId)
            parentId?.takeIf { it.isNotBlank() }?.let { parameter("parentId", it) }
        }

    override suspend fun getLibraryFilterOptions(
        context: AuthenticatedRequestContext,
        parentId: String?,
    ): LibraryFilterOptionsDto =
        authenticatedGet(context = context, path = "/Items/Filters") {
            parameter("userId", context.userId)
            parentId?.takeIf { it.isNotBlank() }?.let { parameter("parentId", it) }
        }

    override suspend fun getMediaSegments(
        context: AuthenticatedRequestContext,
        itemId: String,
    ): List<MediaSegmentDto> =
        // Media segments (skip intro/outro/recap markers) are an OPTIONAL,
        // best-effort feature. The MediaSegments API only exists on Jellyfin
        // 10.10+, and even there an item may have none. authenticatedGetOrNotFound
        // already maps 404 -> empty, but a non-404 client error or a transient
        // IOException at playback start would otherwise surface as a (mislabeled)
        // NotReachable repository-failure warning. Any failure to fetch segments
        // simply means "no markers for this item", never a hard error — degrade to
        // empty instead of propagating. (Cancellation must still propagate.)
        try {
            authenticatedGetOrNotFound<List<MediaSegmentDto>>(
                context = context,
                path = "/MediaSegments/$itemId",
            ) {
                parameter("includeSegmentTypes", mediaSegmentTypes.joinToString(","))
            } ?: emptyList()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emptyList()
        }

    override suspend fun getPersons(
        context: AuthenticatedRequestContext,
        searchTerm: String,
        limit: Int,
    ): PersonsQueryResultDto =
        authenticatedGet(context = context, path = "/Persons") {
            parameter("userId", context.userId)
            parameter("searchTerm", searchTerm)
            parameter("limit", limit)
            parameter("enableImageTypes", "Primary")
            parameter("imageTypeLimit", 1)
        }

    override suspend fun getPlaybackInfo(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        deviceProfile: PlaybackDeviceProfileDto,
        playerDevicePolicy: EffectivePlayerDevicePolicy,
        requestPolicy: PlaybackInfoRequestPolicy,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): PlaybackInfoResponseDto =
        authenticatedPostReturning(
            context = context,
            path = "/Items/$itemId/PlaybackInfo",
            body =
                PlaybackInfoRequestDto(
                    userId = context.userId,
                    startTimeTicks = startTimeTicks,
                    mediaSourceId = mediaSourceId,
                    autoOpenLiveStream = false,
                    deviceProfile = deviceProfile,
                    audioStreamIndex = audioStreamIndex,
                    subtitleStreamIndex = subtitleStreamIndex,
                    maxStreamingBitrate = maxStreamingBitrate,
                    maxAudioChannels = playerDevicePolicy.maxAudioChannels,
                    enableDirectPlay = requestPolicy.enableDirectPlay,
                    enableDirectStream = requestPolicy.enableDirectStream,
                    enableTranscoding = requestPolicy.enableTranscoding,
                    allowAudioStreamCopy = requestPolicy.allowAudioStreamCopy && playerDevicePolicy.allowAudioStreamCopy,
                    allowVideoStreamCopy = requestPolicy.allowVideoStreamCopy && playerDevicePolicy.allowVideoStreamCopy,
                ),
        )

    override suspend fun reportPlaybackStart(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        positionTicks: Long,
        playSessionId: String,
        playMethod: String,
    ) {
        authenticatedPost(
            context = context,
            path = "/Sessions/Playing",
            body =
                PlaybackStartRequestDto(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    positionTicks = positionTicks,
                    playMethod = playMethod,
                    playSessionId = playSessionId,
                    canSeek = true,
                ),
        )
    }

    override suspend fun reportPlaybackProgress(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        positionTicks: Long,
        playSessionId: String,
        playMethod: String,
        isPaused: Boolean,
        eventName: String,
    ) {
        authenticatedPost(
            context = context,
            path = "/Sessions/Playing/Progress",
            body =
                PlaybackProgressRequestDto(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    positionTicks = positionTicks,
                    playMethod = playMethod,
                    playSessionId = playSessionId,
                    canSeek = true,
                    isPaused = isPaused,
                    eventName = eventName,
                ),
        )
    }

    override suspend fun reportPlaybackStopped(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
        positionTicks: Long,
        playSessionId: String,
    ) {
        authenticatedPost(
            context = context,
            path = "/Sessions/Playing/Stopped",
            body =
                PlaybackStoppedRequestDto(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    positionTicks = positionTicks,
                    playSessionId = playSessionId,
                ),
        )
    }

    override suspend fun markItemPlayed(
        context: AuthenticatedRequestContext,
        itemId: String,
    ) {
        authenticatedMutation(
            context = context,
            path = "/UserPlayedItems/$itemId",
            method = HttpMethod.Post,
            configure = { parameter("userId", context.userId) },
            transform = { Unit },
        )
    }

    override suspend fun markItemUnplayed(
        context: AuthenticatedRequestContext,
        itemId: String,
    ) {
        authenticatedMutation(
            context = context,
            path = "/UserPlayedItems/$itemId",
            method = HttpMethod.Delete,
            configure = { parameter("userId", context.userId) },
            transform = { Unit },
        )
    }

    override suspend fun setItemFavorite(
        context: AuthenticatedRequestContext,
        itemId: String,
    ) {
        authenticatedMutation(
            context = context,
            path = "/UserFavoriteItems/$itemId",
            method = HttpMethod.Post,
            configure = { parameter("userId", context.userId) },
            transform = { Unit },
        )
    }

    override suspend fun unsetItemFavorite(
        context: AuthenticatedRequestContext,
        itemId: String,
    ) {
        authenticatedMutation(
            context = context,
            path = "/UserFavoriteItems/$itemId",
            method = HttpMethod.Delete,
            configure = { parameter("userId", context.userId) },
            transform = { Unit },
        )
    }

    private suspend inline fun <reified T> unauthenticatedGet(
        serverUrl: String,
        path: String,
        crossinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): T =
        try {
            val response =
                client.get("$serverUrl$path") {
                    header(HttpHeaders.Authorization, authHeaderProvider.authHeader(token = null))
                    configure()
                }

            when {
                response.status.value in 200..299 -> response.body()
                response.status == HttpStatusCode.BadRequest ->
                    throw JellyfinApiException.QuickConnectExpired
                response.status == HttpStatusCode.NotFound ->
                    throw JellyfinApiException.QuickConnectUnavailable
                response.status.value >= 500 ->
                    throw JellyfinApiException.ServerError(response.status.value)
                else ->
                    throw JellyfinApiException.NotReachable
            }
        } catch (exception: JellyfinApiException) {
            throw exception
        } catch (exception: SerializationException) {
            throw JellyfinApiException.Unexpected(exception)
        } catch (exception: IOException) {
            throw JellyfinApiException.NotReachable
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw JellyfinApiException.Unexpected(exception)
        }

    private suspend inline fun <reified Response> unauthenticatedPostReturning(
        serverUrl: String,
        path: String,
    ): Response =
        unauthenticatedPostReturning<Unit, Response>(
            serverUrl = serverUrl,
            path = path,
            body = null,
        )

    private suspend inline fun <reified Request, reified Response> unauthenticatedPostReturning(
        serverUrl: String,
        path: String,
        body: Request?,
    ): Response =
        try {
            val response =
                client.post("$serverUrl$path") {
                    header(HttpHeaders.Authorization, authHeaderProvider.authHeader(token = null))
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }

            when {
                response.status.value in 200..299 -> response.body()
                response.status == HttpStatusCode.BadRequest ->
                    throw JellyfinApiException.QuickConnectExpired
                response.status == HttpStatusCode.NotFound ->
                    throw JellyfinApiException.QuickConnectUnavailable
                response.status == HttpStatusCode.Unauthorized ->
                    throw JellyfinApiException.InvalidCredentials
                response.status.value >= 500 ->
                    throw JellyfinApiException.ServerError(response.status.value)
                else ->
                    throw JellyfinApiException.NotReachable
            }
        } catch (exception: JellyfinApiException) {
            throw exception
        } catch (exception: SerializationException) {
            throw JellyfinApiException.Unexpected(exception)
        } catch (exception: IOException) {
            throw JellyfinApiException.NotReachable
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw JellyfinApiException.Unexpected(exception)
        }

    private suspend fun validateFixedDownloadSource(
        context: AuthenticatedRequestContext,
        request: FixedDownloadRequest,
    ): FixedSourceValidation {
        if (context.serverUrl.isBlank() || context.userId.isBlank() || context.accessToken.isBlank()) {
            return fixedDownloadSourceRejected(
                failure = FixedDownloadFailure.AccountUnauthorized,
                reason = FixedDownloadDiagnosticReason.AuthenticationContextInvalid,
                requestKind = request.requestKind,
            )
        }
        val user =
            try {
                getCurrentUser(context)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return fixedDownloadSourceRejected(
                    failure = failure.toFixedDownloadFailure(),
                    reason = FixedDownloadDiagnosticReason.CurrentUserRequestFailed,
                    requestKind = request.requestKind,
                    throwable = failure,
                )
            }
        if (user.id != context.userId) {
            return fixedDownloadSourceRejected(
                failure = FixedDownloadFailure.AccountUnauthorized,
                reason = FixedDownloadDiagnosticReason.CurrentUserMismatch,
                requestKind = request.requestKind,
            )
        }
        if (user.policy?.enableContentDownloading != true) {
            return fixedDownloadSourceRejected(
                failure = FixedDownloadFailure.PermissionDenied,
                reason = FixedDownloadDiagnosticReason.DownloadPermissionDenied,
                requestKind = request.requestKind,
            )
        }
        val item =
            try {
                authenticatedGetOrNotFound<BaseItemDto>(
                    context = context,
                    path = "/Items/${request.itemId}",
                ) {
                    parameter("userId", context.userId)
                    applyDefaultItemParameters(
                        fields = mediaSourceItemFields,
                        imageTypes = detailImageTypes,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return fixedDownloadSourceRejected(
                    failure = failure.toFixedDownloadFailure(),
                    reason = FixedDownloadDiagnosticReason.ItemRequestFailed,
                    requestKind = request.requestKind,
                    throwable = failure,
                )
            }
        if (item == null ||
            item.id != request.itemId ||
            item.isLive == true ||
            item.type !in ORIGINAL_DOWNLOAD_ITEM_TYPES
        ) {
            return fixedDownloadSourceRejected(
                failure = FixedDownloadFailure.SourceUnavailable,
                reason = FixedDownloadDiagnosticReason.ItemUnavailable,
                requestKind = request.requestKind,
            )
        }
        val mediaSource =
            item.mediaSources.singleOrNull { source -> source.id == request.mediaSourceId }
                ?: return fixedDownloadSourceRejected(
                    failure = FixedDownloadFailure.SourceUnavailable,
                    reason = FixedDownloadDiagnosticReason.ItemMediaSourceUnavailable,
                    requestKind = request.requestKind,
                )
        if (mediaSource.isInfiniteStream == true) {
            return fixedDownloadSourceRejected(
                failure = FixedDownloadFailure.SourceUnavailable,
                reason = FixedDownloadDiagnosticReason.InfiniteStreamUnsupported,
                requestKind = request.requestKind,
            )
        }
        val durationTicks = item.runTimeTicks ?: mediaSource.runTimeTicks
        val durationMs =
            durationTicks?.takeIf { ticks -> ticks > 0L }?.let(::ticksToMilliseconds)
                ?: return fixedDownloadSourceRejected(
                    failure = FixedDownloadFailure.SizeUnavailable,
                    reason = FixedDownloadDiagnosticReason.DurationUnavailable,
                    requestKind = request.requestKind,
                )
        val audioStreams =
            mediaSource.mediaStreams.filter { stream ->
                stream.type.equals("Audio", ignoreCase = true) && stream.index != null && stream.index >= 0
            }
        val audioIndex =
            request.audioStreamIndex
                ?: audioStreams.firstOrNull { stream -> stream.isDefault == true }?.index
                ?: audioStreams.firstOrNull()?.index
                ?: return fixedDownloadSourceRejected(
                    failure = FixedDownloadFailure.SourceChanged,
                    reason = FixedDownloadDiagnosticReason.AudioStreamUnavailable,
                    requestKind = request.requestKind,
                )
        if (audioStreams.none { stream -> stream.index == audioIndex }) {
            return fixedDownloadSourceRejected(
                failure = FixedDownloadFailure.SourceChanged,
                reason = FixedDownloadDiagnosticReason.AudioStreamChanged,
                requestKind = request.requestKind,
            )
        }
        val subtitleFormat =
            when (val selection = request.subtitleSelection) {
                DownloadSubtitleSelection.Off -> null
                is DownloadSubtitleSelection.Embedded -> {
                    val stream =
                        mediaSource.mediaStreams.singleOrNull { candidate ->
                            candidate.type.equals("Subtitle", ignoreCase = true) &&
                                candidate.index == selection.streamIndex
                        } ?: return fixedDownloadSourceRejected(
                            failure = FixedDownloadFailure.SourceChanged,
                            reason = FixedDownloadDiagnosticReason.SubtitleStreamChanged,
                            requestKind = request.requestKind,
                        )
                    if (stream.isExternal == true) {
                        return fixedDownloadSourceRejected(
                            failure = FixedDownloadFailure.UnsupportedArtifact,
                            reason = FixedDownloadDiagnosticReason.ExternalSubtitleUnsupported,
                            requestKind = request.requestKind,
                        )
                    }
                    fixedTextSubtitleFormat(stream)
                        ?: return fixedDownloadSourceRejected(
                            failure = FixedDownloadFailure.UnsupportedArtifact,
                            reason = FixedDownloadDiagnosticReason.SubtitleFormatUnsupported,
                            requestKind = request.requestKind,
                        )
                }
                is DownloadSubtitleSelection.ExternalTextSidecar,
                is DownloadSubtitleSelection.ExternalServerTextSidecar,
                ->
                    return fixedDownloadSourceRejected(
                        failure = FixedDownloadFailure.UnsupportedArtifact,
                        reason = FixedDownloadDiagnosticReason.ExternalSubtitleUnsupported,
                        requestKind = request.requestKind,
                    )
            }
        return FixedSourceValidation.Ready(
            durationMs = durationMs,
            audioStreamIndex = audioIndex,
            subtitleFormat = subtitleFormat,
        )
    }

    private fun fixedTextSubtitleFormat(stream: MediaStreamDto): String? {
        val codec = stream.codec?.trim()?.lowercase()
        val extension =
            stream.deliveryUrl
                ?.substringBefore('?')
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase()
        return (codec ?: extension)
            ?.takeIf { value -> value in FIXED_TEXT_SUBTITLE_CODECS }
    }

    private fun resolveFixedResourceUrl(
        context: AuthenticatedRequestContext,
        candidate: String,
    ): String? {
        val resolved = resolveServerRelativeUrl(context.serverUrl, candidate)
        val decision = CredentialOriginGuard(context.serverUrl).decideResourceCredentials(resolved)
        return decision.sanitizedUrl.takeIf { decision.attachCredentials && it.isBoundedResourceUrl() }
    }

    private fun fixedDownloadQueryParameter(
        url: String,
        name: String,
    ): String? {
        val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        if (query.isBlank()) return null
        return parseQueryString(query)
            .entries()
            .firstOrNull { entry -> entry.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { value -> value.isBoundedDownloadIdentity() }
    }

    private fun HttpResponse.fixedDownloadStatusFailure(): FixedDownloadFailure? =
        when {
            status.value in 200..299 -> null
            status == HttpStatusCode.Unauthorized -> FixedDownloadFailure.AccountUnauthorized
            status == HttpStatusCode.Forbidden -> FixedDownloadFailure.PermissionDenied
            status == HttpStatusCode.NotFound -> FixedDownloadFailure.SourceUnavailable
            status.value >= 500 -> FixedDownloadFailure.ServerUnavailable
            else -> FixedDownloadFailure.SourceChanged
        }

    private fun Throwable.toFixedDownloadFailure(): FixedDownloadFailure =
        when (this) {
            JellyfinApiException.Unauthorized,
            JellyfinApiException.InvalidCredentials,
            -> FixedDownloadFailure.AccountUnauthorized
            JellyfinApiException.NotReachable,
            is IOException,
            -> FixedDownloadFailure.Network
            JellyfinApiException.PayloadTooLarge -> FixedDownloadFailure.PayloadTooLarge
            else -> FixedDownloadFailure.ServerUnavailable
        }

    // Like authenticatedGet, but a 404 returns null instead of NotReachable —
    // for endpoints that legitimately don't exist on older servers. Genuine
    // transport failures still map exactly as authenticatedGet does.
    private suspend inline fun <reified T : Any> authenticatedGetOrNotFound(
        context: AuthenticatedRequestContext,
        path: String,
        crossinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): T? =
        try {
            val response =
                client.get("${context.serverUrl}$path") {
                    header(
                        HttpHeaders.Authorization,
                        authHeaderProvider.authHeader(token = context.accessToken),
                    )
                    configure()
                }

            when {
                response.status.value in 200..299 -> response.body<T>()
                response.status == HttpStatusCode.NotFound -> null
                response.status == HttpStatusCode.Unauthorized ->
                    throw JellyfinApiException.Unauthorized
                response.status.value >= 500 ->
                    throw JellyfinApiException.ServerError(response.status.value)
                else ->
                    throw JellyfinApiException.NotReachable
            }
        } catch (exception: JellyfinApiException) {
            throw exception
        } catch (exception: SerializationException) {
            throw JellyfinApiException.Unexpected(exception)
        } catch (exception: IOException) {
            throw JellyfinApiException.NotReachable
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw JellyfinApiException.Unexpected(exception)
        }

    private suspend inline fun <reified T> authenticatedGet(
        context: AuthenticatedRequestContext,
        path: String,
        crossinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): T =
        try {
            val response =
                client.get("${context.serverUrl}$path") {
                    header(
                        HttpHeaders.Authorization,
                        authHeaderProvider.authHeader(token = context.accessToken),
                    )
                    configure()
                }

            when {
                response.status.value in 200..299 -> response.body()
                response.status == HttpStatusCode.Unauthorized ->
                    throw JellyfinApiException.Unauthorized
                response.status.value >= 500 ->
                    throw JellyfinApiException.ServerError(response.status.value)
                else ->
                    throw JellyfinApiException.NotReachable
            }
        } catch (exception: JellyfinApiException) {
            throw exception
        } catch (exception: SerializationException) {
            throw JellyfinApiException.Unexpected(exception)
        } catch (exception: IOException) {
            throw JellyfinApiException.NotReachable
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw JellyfinApiException.Unexpected(exception)
        }

    private suspend fun authenticatedGetText(
        context: AuthenticatedRequestContext,
        path: String,
    ): String {
        val response =
            client.get("${context.serverUrl}$path") {
                header(HttpHeaders.Authorization, authHeaderProvider.authHeader(token = context.accessToken))
            }
        if (response.status.value !in 200..299) throw JellyfinApiException.ServerError(response.status.value)
        return response.bodyAsText()
    }

    private suspend inline fun <reified T> authenticatedPost(
        context: AuthenticatedRequestContext,
        path: String,
        body: T,
    ) = authenticatedMutation(
        context = context,
        path = path,
        method = HttpMethod.Post,
        configure = {
            contentType(ContentType.Application.Json)
            setBody(body)
        },
        transform = { Unit },
    )

    private suspend inline fun <reified Request, reified Response> authenticatedPostReturning(
        context: AuthenticatedRequestContext,
        path: String,
        body: Request,
    ): Response =
        authenticatedMutation(
            context = context,
            path = path,
            method = HttpMethod.Post,
            configure = {
                contentType(ContentType.Application.Json)
                setBody(body)
            },
            transform = { response -> response.body() },
        )

    private suspend inline fun <Result> authenticatedMutation(
        context: AuthenticatedRequestContext,
        path: String,
        method: HttpMethod,
        crossinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
        crossinline transform: suspend (HttpResponse) -> Result,
    ): Result =
        try {
            val authorization = authHeaderProvider.authHeader(token = context.accessToken)
            val response =
                client.request("${context.serverUrl}$path") {
                    this.method = method
                    header(HttpHeaders.Authorization, authorization)
                    configure()
                }

            when {
                response.status.value in 200..299 -> transform(response)
                response.status == HttpStatusCode.Unauthorized ->
                    throw JellyfinApiException.Unauthorized
                response.status.value >= 500 ->
                    throw JellyfinApiException.ServerError(response.status.value)
                else ->
                    throw JellyfinApiException.NotReachable
            }
        } catch (exception: JellyfinApiException) {
            throw exception
        } catch (exception: SerializationException) {
            throw JellyfinApiException.Unexpected(exception)
        } catch (exception: IOException) {
            throw JellyfinApiException.NotReachable
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw JellyfinApiException.Unexpected(exception)
        }

    private suspend fun validateOriginalDownloadSource(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
    ): OriginalSourceValidation {
        if (
            context.serverUrl.isBlank() ||
            context.userId.isBlank() ||
            context.accessToken.isBlank()
        ) {
            return OriginalSourceValidation.Rejected(OriginalDownloadFailure.AccountUnauthorized)
        }
        if (itemId.isBlank() || mediaSourceId.isBlank()) {
            return OriginalSourceValidation.Rejected(OriginalDownloadFailure.SourceUnavailable)
        }

        val user =
            try {
                getCurrentUser(context)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return OriginalSourceValidation.Rejected(failure.toOriginalDownloadFailure())
            }
        if (user.id != context.userId || user.policy?.enableContentDownloading != true) {
            return OriginalSourceValidation.Rejected(
                if (user.id != context.userId) {
                    OriginalDownloadFailure.AccountUnauthorized
                } else {
                    OriginalDownloadFailure.PermissionDenied
                },
            )
        }

        val item =
            try {
                authenticatedGetOrNotFound<BaseItemDto>(
                    context = context,
                    path = "/Items/$itemId",
                ) {
                    parameter("userId", context.userId)
                    applyDefaultItemParameters(
                        fields = mediaSourceItemFields,
                        imageTypes = detailImageTypes,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return OriginalSourceValidation.Rejected(failure.toOriginalDownloadFailure())
            }
        if (
            item == null ||
            item.id != itemId ||
            item.isLive == true ||
            item.type !in ORIGINAL_DOWNLOAD_ITEM_TYPES
        ) {
            return OriginalSourceValidation.Rejected(OriginalDownloadFailure.SourceUnavailable)
        }

        val sources = item.mediaSources.filter { source -> source.id == mediaSourceId }
        if (sources.size != 1 || sources.single().isInfiniteStream == true) {
            return OriginalSourceValidation.Rejected(OriginalDownloadFailure.SourceUnavailable)
        }
        val source = sources.single()
        return OriginalSourceValidation.Ready(
            declaredBytes = source.size?.takeIf { size -> size > 0L },
            backendSource = source.toBackendSourceDescriptor(),
            audioStreamIndices =
                source.mediaStreams
                    .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
                    .mapNotNull { stream -> stream.index }
                    .toSet(),
            embeddedSubtitleStreamIndices =
                source.mediaStreams
                    .filter { stream ->
                        stream.type.equals("Subtitle", ignoreCase = true) && stream.isExternal != true
                    }.mapNotNull { stream -> stream.index }
                    .toSet(),
            externalSubtitleStreamIndices =
                source.mediaStreams
                    .filter { stream ->
                        stream.type.equals("Subtitle", ignoreCase = true) &&
                            stream.isExternal == true &&
                            stream.index != null &&
                            stream.isOriginalDownloadTextSubtitle()
                    }.mapNotNull { stream -> stream.index }
                    .toSet(),
            unsupportedExternalSubtitleStreamIndices =
                source.mediaStreams
                    .filter { stream ->
                        stream.type.equals("Subtitle", ignoreCase = true) &&
                            stream.isExternal == true &&
                            stream.index != null &&
                            !stream.isOriginalDownloadTextSubtitle()
                    }.mapNotNull { stream -> stream.index }
                    .toSet(),
        )
    }

    private fun MediaSourceDto.toBackendSourceDescriptor(): BackendSourceDescriptor {
        val video = mediaStreams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
        val audio =
            mediaStreams
                .filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
                .firstOrNull { stream -> stream.isDefault == true }
                ?: mediaStreams.firstOrNull { stream -> stream.type.equals("Audio", ignoreCase = true) }
        return BackendSourceDescriptor(
            container = container,
            videoCodec = video?.codec,
            audioCodec = audio?.codec,
            isHdrOrDolbyVision =
                listOfNotNull(video?.videoRangeType, video?.codec)
                    .any { value ->
                        value.contains("hdr", ignoreCase = true) ||
                            value.contains("dolby", ignoreCase = true) ||
                            value.contains("vision", ignoreCase = true)
                    },
            videoWidth = video?.width,
            videoHeight = video?.height,
            videoFrameRate = (video?.realFrameRate ?: video?.averageFrameRate)?.toDouble(),
        )
    }

    private fun MediaStreamDto.isOriginalDownloadTextSubtitle(): Boolean {
        val method = deliveryMethod?.trim()?.lowercase()
        if (method != null && method !in ORIGINAL_TEXT_SUBTITLE_DELIVERY_METHODS) return false
        val codec = codec?.trim()?.lowercase()
        val extension =
            deliveryUrl
                ?.substringBefore('?')
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase()
        return if (codec != null) {
            codec in ORIGINAL_TEXT_SUBTITLE_CODECS
        } else {
            extension in ORIGINAL_TEXT_SUBTITLE_CODECS
        }
    }

    private fun trustedOriginalStreamUrl(
        context: AuthenticatedRequestContext,
        itemId: String,
        mediaSourceId: String,
    ): String? {
        val candidate =
            runCatching {
                URLBuilder(context.serverUrl.trim().trimEnd('/'))
                    .apply {
                        appendPathSegments("Videos", itemId, "stream", encodeSlash = true)
                        parameters.append("static", "true")
                        parameters.append("MediaSourceId", mediaSourceId)
                    }.buildString()
            }.getOrNull() ?: return null
        val decision = CredentialOriginGuard(context.serverUrl).decideResourceCredentials(candidate)
        return decision.sanitizedUrl.takeIf { decision.attachCredentials }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyOriginalDownloadHeaders(
        authorization: String,
        range: String,
        ifRange: String? = null,
    ) {
        header(HttpHeaders.Authorization, authorization)
        header(HttpHeaders.Range, range)
        header(HttpHeaders.AcceptEncoding, "identity")
        header(HttpHeaders.CacheControl, "no-transform")
        ifRange?.let { validator -> header(HttpHeaders.IfRange, validator) }
        timeout {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = ORIGINAL_DOWNLOAD_SOCKET_TIMEOUT_MS
        }
    }

    private fun HttpResponse.originalDownloadStatusFailure(): OriginalDownloadFailure? =
        when {
            status == HttpStatusCode.PartialContent -> null
            status == HttpStatusCode.Unauthorized -> OriginalDownloadFailure.AccountUnauthorized
            status == HttpStatusCode.Forbidden -> OriginalDownloadFailure.PermissionDenied
            status == HttpStatusCode.NotFound -> OriginalDownloadFailure.SourceUnavailable
            status.value >= 500 -> OriginalDownloadFailure.ServerUnavailable
            else -> OriginalDownloadFailure.SourceChanged
        }

    private fun HttpResponse.validatedContentRange(
        expectedStart: Long,
        requireCompleteTail: Boolean,
    ): OriginalContentRange? {
        val encoding = headers[HttpHeaders.ContentEncoding]
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return null
        val raw = headers[HttpHeaders.ContentRange] ?: return null
        val match = ORIGINAL_CONTENT_RANGE.matchEntire(raw.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (
            total <= 0L ||
            start != expectedStart ||
            start < 0L ||
            end < start ||
            end >= total ||
            (requireCompleteTail && end != total - 1L)
        ) {
            return null
        }
        return OriginalContentRange(start, end, total)
    }

    private fun HttpResponse.hasExpectedContentLength(expectedBytes: Long): Boolean {
        val raw = headers[HttpHeaders.ContentLength] ?: return true
        return raw.toLongOrNull() == expectedBytes
    }

    private fun HttpResponse.originalLastModified(): String? =
        headers[HttpHeaders.LastModified]
            ?.trim()
            ?.takeIf(String::isSafeValidator)

    private fun Throwable.toOriginalDownloadFailure(): OriginalDownloadFailure =
        when (this) {
            JellyfinApiException.Unauthorized,
            JellyfinApiException.InvalidCredentials,
            -> OriginalDownloadFailure.AccountUnauthorized

            JellyfinApiException.NotReachable,
            is IOException,
            -> OriginalDownloadFailure.Network

            else -> OriginalDownloadFailure.ServerUnavailable
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applyDefaultItemParameters(
        fields: List<String> = defaultItemFields,
        imageTypes: List<String> = defaultImageTypes,
    ) {
        parameter("fields", fields.joinToString(","))
        parameter("enableImageTypes", imageTypes.joinToString(","))
        parameter("imageTypeLimit", 1)
    }
}

private sealed interface OriginalSourceValidation {
    data class Ready(
        val declaredBytes: Long?,
        val backendSource: BackendSourceDescriptor,
        val audioStreamIndices: Set<Int> = emptySet(),
        val embeddedSubtitleStreamIndices: Set<Int> = emptySet(),
        val externalSubtitleStreamIndices: Set<Int> = emptySet(),
        val unsupportedExternalSubtitleStreamIndices: Set<Int> = emptySet(),
    ) : OriginalSourceValidation

    data class Rejected(
        val failure: OriginalDownloadFailure,
    ) : OriginalSourceValidation
}

private data class OriginalContentRange(
    val startByte: Long,
    val endByteInclusive: Long,
    val totalBytes: Long,
)

private sealed interface FixedSourceValidation {
    data class Ready(
        val durationMs: Long,
        val audioStreamIndex: Int,
        val subtitleFormat: String?,
    ) : FixedSourceValidation

    data class Rejected(
        val failure: FixedDownloadFailure,
    ) : FixedSourceValidation
}

private enum class FixedDownloadDiagnosticReason {
    AuthenticationContextInvalid,
    CurrentUserRequestFailed,
    CurrentUserMismatch,
    DownloadPermissionDenied,
    ItemRequestFailed,
    ItemUnavailable,
    ItemMediaSourceUnavailable,
    InfiniteStreamUnsupported,
    DurationUnavailable,
    AudioStreamUnavailable,
    AudioStreamChanged,
    SubtitleStreamChanged,
    ExternalSubtitleUnsupported,
    SubtitleFormatUnsupported,
    PlaybackInfoRequestFailed,
    PlaybackMediaSourceMissing,
    PlaybackMediaSourceDuplicated,
    TranscodingUnsupported,
    TranscodingUrlMissing,
    TranscodingProtocolUnsupported,
    TranscodingContainerUnsupported,
    TranscodingUrlRejected,
    DeviceIdMissing,
    PlaySessionIdMissing,
    EstimateUnavailable,
}

private fun fixedDownloadSourceRejected(
    failure: FixedDownloadFailure,
    reason: FixedDownloadDiagnosticReason,
    requestKind: FixedDownloadRequestKind,
    throwable: Throwable? = null,
): FixedSourceValidation.Rejected {
    logFixedDownloadPreflightRejection(failure, reason, requestKind, throwable)
    return FixedSourceValidation.Rejected(failure)
}

private fun fixedDownloadPreflightRejected(
    failure: FixedDownloadFailure,
    reason: FixedDownloadDiagnosticReason,
    requestKind: FixedDownloadRequestKind,
    throwable: Throwable? = null,
    candidateCount: Int? = null,
): FixedDownloadPreflightResult.Rejected {
    logFixedDownloadPreflightRejection(failure, reason, requestKind, throwable, candidateCount)
    return FixedDownloadPreflightResult.Rejected(failure)
}

private fun logFixedDownloadPreflightRejection(
    failure: FixedDownloadFailure,
    reason: FixedDownloadDiagnosticReason,
    requestKind: FixedDownloadRequestKind,
    throwable: Throwable?,
    candidateCount: Int? = null,
) {
    fixedDownloadLogger.w {
        buildString {
            append("stage=fixed-download event=preflight-rejected")
            append(" reason=${reason.name}")
            append(" requestKind=${requestKind.name}")
            append(" result=${failure.name}")
            candidateCount?.let { count -> append(" candidateCount=$count") }
            throwable?.let { cause ->
                append(" exceptionType=${cause.safeDiagnosticType()}")
                cause.cause?.let { nested -> append(" causeType=${nested.safeDiagnosticType()}") }
                (cause as? JellyfinApiException.ServerError)?.let { serverError ->
                    append(" httpCode=${serverError.statusCode}")
                }
            }
        }
    }
}

private class FixedDownloadResourceConsumerFailure(
    val failure: Throwable,
) : Exception()

private class OriginalDownloadConsumerFailure(
    val failure: Throwable,
) : Exception()

private val ORIGINAL_DOWNLOAD_ITEM_TYPES = setOf("Movie", "Episode")
private val fixedDownloadLogger = diagnosticLogger(DiagnosticTag.FixedDownload)
private val ORIGINAL_TEXT_SUBTITLE_CODECS =
    setOf(
        "srt",
        "subrip",
        "vtt",
        "webvtt",
        "ass",
        "ssa",
        "ttml",
        "text/vtt",
        "text/webvtt",
        "application/x-subrip",
        "application/ttml+xml",
        "text/x-ssa",
    )
private val ORIGINAL_TEXT_SUBTITLE_DELIVERY_METHODS = setOf("external", "encode")
private val ORIGINAL_CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$", RegexOption.IGNORE_CASE)
private val FIXED_TEXT_SUBTITLE_CODECS = setOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa")

private fun String?.isFixedHlsContainer(): Boolean = this?.trim()?.lowercase() in setOf("m3u8", "hls", "ts")

private const val FIXED_DOWNLOAD_SOCKET_TIMEOUT_MS = 120_000L
private const val FIXED_DOWNLOAD_CLEANUP_TIMEOUT_MS = 10_000L
private const val ORIGINAL_DOWNLOAD_SOCKET_TIMEOUT_MS = 120_000L
