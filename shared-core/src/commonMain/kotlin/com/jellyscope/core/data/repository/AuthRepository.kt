// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.local.StoreCleanupException
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.JellyfinApiException
import com.jellyscope.core.data.remote.PublicSystemInfoDto
import com.jellyscope.core.data.remote.ServerUrlNormalizationResult
import com.jellyscope.core.data.remote.ServerUrlNormalizer
import com.jellyscope.core.domain.model.AuthError
import com.jellyscope.core.domain.model.QuickConnectCode
import com.jellyscope.core.domain.model.QuickConnectLoginUpdate
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.core.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

interface AuthRepository {
    suspend fun validateServer(input: String): Result<ServerInfo>

    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session>

    fun loginWithQuickConnect(serverInfo: ServerInfo): Flow<Result<QuickConnectLoginUpdate>> =
        flowOf(Result.failure(AuthError.QuickConnectUnavailable))

    suspend fun addAccount(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session> =
        login(
            serverUrl = serverUrl,
            username = username,
            password = password,
        )

    suspend fun signOut(
        accountId: String,
        authorization: SessionRemovalAuthorization = SessionRemovalAuthorization.None,
    ): Result<Unit> = Result.failure(AuthError.AccountNotFound)

    suspend fun logout(authorization: SessionRemovalAuthorization = SessionRemovalAuthorization.None): Result<Unit>
}

class DefaultAuthRepository(
    private val jellyfinApi: JellyfinApi,
    private val sessionStore: SessionStore,
    private val sessionRepository: SessionRepository,
    private val sessionTransitionCoordinator: SessionTransitionCoordinator,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : AuthRepository {
    override suspend fun validateServer(input: String): Result<ServerInfo> {
        val normalized =
            when (val result = ServerUrlNormalizer.normalize(input)) {
                is ServerUrlNormalizationResult.Normalized -> result.url
                is ServerUrlNormalizationResult.Invalid -> return Result.failure(AuthError.InvalidUrl)
            }

        return runCatchingCancellable {
            withContext(workDispatcher) {
                jellyfinApi.getPublicSystemInfo(normalized).toDomain(serverUrl = normalized)
            }
        }.mapError(DiagnosticOperation.AuthValidateServer)
    }

    override suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session> {
        val normalizedServerUrl =
            when (val result = ServerUrlNormalizer.normalize(serverUrl)) {
                is ServerUrlNormalizationResult.Normalized -> result.url
                is ServerUrlNormalizationResult.Invalid -> return Result.failure(AuthError.InvalidUrl)
            }
        val authenticationAttempt = sessionTransitionCoordinator.beginAuthenticationAttempt()
        return runCatchingCancellable {
            withContext(workDispatcher) {
                val deviceId = sessionStore.readOrCreateDeviceId(deviceInfoProvider::newDeviceId)
                val serverInfo =
                    jellyfinApi
                        .getPublicSystemInfo(normalizedServerUrl)
                        .toDomain(serverUrl = normalizedServerUrl)
                val authResult =
                    jellyfinApi.authenticateByName(
                        serverUrl = normalizedServerUrl,
                        username = username,
                        password = password,
                    )
                val session = authResult.toSession(serverInfo, deviceId)

                sessionRepository.setLoggedIn(session, authenticationAttempt)
                session
            }
        }.mapError(DiagnosticOperation.AuthLogin)
    }

    override fun loginWithQuickConnect(serverInfo: ServerInfo): Flow<Result<QuickConnectLoginUpdate>> =
        flow {
            val authenticationAttempt = sessionTransitionCoordinator.beginAuthenticationAttempt()
            val deviceId = sessionStore.readOrCreateDeviceId(deviceInfoProvider::newDeviceId)
            val initiated = jellyfinApi.initiateQuickConnect(serverInfo.serverUrl)
            val code =
                QuickConnectCode(
                    secret = initiated.secret,
                    code = initiated.code,
                )
            emit(Result.success(QuickConnectLoginUpdate.CodeAvailable(code)))

            var state = initiated
            while (!state.authenticated) {
                emit(Result.success(QuickConnectLoginUpdate.Polling(code)))
                delay(QUICK_CONNECT_POLL_INTERVAL)
                state =
                    jellyfinApi.getQuickConnectState(
                        serverUrl = serverInfo.serverUrl,
                        secret = code.secret,
                    )
            }

            val authResult =
                jellyfinApi.authenticateWithQuickConnect(
                    serverUrl = serverInfo.serverUrl,
                    secret = code.secret,
                )
            val session = authResult.toSession(serverInfo, deviceId)
            sessionRepository.setLoggedIn(session, authenticationAttempt)
            emit(Result.success(QuickConnectLoginUpdate.Success(session)))
        }.flowOn(workDispatcher).catch { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            authLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "quick-connect",
                    event = "failed",
                    operation = DiagnosticOperation.AuthQuickConnect,
                    throwable = throwable,
                )
            }
            emit(Result.failure(throwable.toAuthError()))
        }

    override suspend fun addAccount(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Session> =
        login(
            serverUrl = serverUrl,
            username = username,
            password = password,
        )

    override suspend fun signOut(
        accountId: String,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> =
        runCatchingCancellable {
            sessionRepository.removeAccount(accountId, authorization).getOrThrow()
            Unit
        }.mapError(DiagnosticOperation.AuthSignOut)

    override suspend fun logout(authorization: SessionRemovalAuthorization): Result<Unit> =
        runCatchingCancellable {
            val previousServerUrl =
                when (val state = sessionRepository.sessionState.value) {
                    is SessionState.LoggedIn ->
                        state.session.serverUrl
                    is SessionState.LoggedOut ->
                        state.serverUrl
                    SessionState.Restoring ->
                        null
                }

            sessionRepository.setLoggedOut(previousServerUrl, authorization).getOrThrow()
        }.mapError(DiagnosticOperation.AuthLogout)
}

private fun PublicSystemInfoDto.toDomain(serverUrl: String): ServerInfo =
    ServerInfo(
        serverUrl = serverUrl,
        serverId = id,
        serverName = serverName,
        version = version,
        productName = productName,
    )

private fun com.jellyscope.core.data.remote.AuthenticationResultDto.toSession(
    serverInfo: ServerInfo,
    deviceId: String,
): Session =
    Session(
        serverUrl = serverInfo.serverUrl,
        serverId = serverId,
        serverName = serverInfo.serverName,
        userId = user.id,
        userName = user.name,
        accessToken = accessToken,
        deviceId = deviceId,
        // The server policy is intentionally kept raw in the DTO. This client
        // temporarily projects the effective Downloads permission off at the
        // session boundary for every authentication path.
        enableContentDownloading = false,
    )

private fun <T> Result<T>.mapError(operation: DiagnosticOperation): Result<T> =
    fold(
        onSuccess = { value -> Result.success(value) },
        onFailure = { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            authLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "auth-request",
                    event = "failed",
                    operation = operation,
                    throwable = throwable,
                )
            }
            Result.failure(
                when (throwable) {
                    is SessionRemovalError,
                    is StoreCleanupException,
                    -> throwable
                    else -> throwable.toAuthError()
                },
            )
        },
    )

private fun Throwable.toAuthError(): AuthError =
    when (this) {
        JellyfinApiException.NotReachable -> AuthError.NotReachable
        JellyfinApiException.InvalidCredentials -> AuthError.InvalidCredentials
        JellyfinApiException.Unauthorized -> AuthError.InvalidCredentials
        JellyfinApiException.QuickConnectExpired -> AuthError.QuickConnectExpired
        JellyfinApiException.QuickConnectUnavailable -> AuthError.QuickConnectUnavailable
        is JellyfinApiException.ServerError -> AuthError.ServerError(statusCode)
        is JellyfinApiException.Unexpected -> AuthError.ServerError(statusCode = null)
        is AuthError -> this
        else -> AuthError.ServerError(statusCode = null)
    }

private val QUICK_CONNECT_POLL_INTERVAL = 5.seconds

private val authLogger = diagnosticLogger(DiagnosticTag.AuthRepository)
