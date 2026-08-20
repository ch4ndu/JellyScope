// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import android.security.NetworkSecurityPolicy
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.remote.AuthHeaderBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.security.CredentialOriginGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal data class AndroidMpvNetworkRequest(
    val url: String,
    val authorizationHeader: String?,
    val subtitleUrl: String?,
    val localSubtitlePath: String?,
    val subtitleRejection: AndroidMpvNetworkRejection? = null,
)

internal enum class AndroidMpvNetworkRejection {
    InvalidUrl,
    CrossOrigin,
    CleartextNotPermitted,
    MissingLocalSubtitle,
}

internal sealed interface AndroidMpvNetworkResult {
    data class Accepted(
        val request: AndroidMpvNetworkRequest,
    ) : AndroidMpvNetworkResult

    data class Rejected(
        val reason: AndroidMpvNetworkRejection,
    ) : AndroidMpvNetworkResult
}

internal interface AndroidMpvNetworkPolicyContract {
    fun loadRequest(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ): AndroidMpvNetworkResult

    suspend fun ensureTrustBundle(): File
}

/**
 * Owns the small security policy that native FFmpeg does not inherit from
 * OkHttp.  It strips inbound auth-query parameters, attaches only the modern
 * token-only header on the trusted server origin, and rejects foreign remote
 * subtitle origins before mpv sees them.
 */
internal class AndroidMpvNetworkPolicy(
    context: Context,
    private val session: Session,
    private val localSubtitleFileStore: LocalSubtitleFileStore? = null,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
    private val trustCertificates: (() -> List<X509Certificate>)? = null,
    private val cleartextPermitted: (String) -> Boolean = { host ->
        NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
    },
) : AndroidMpvNetworkPolicyContract {
    private val appContext = context.applicationContext
    private val credentialOriginGuard = CredentialOriginGuard(session.serverUrl)
    private val trustBundleFile = File(File(appContext.cacheDir, "mpv"), "trust-bundle.pem")

    override fun loadRequest(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ): AndroidMpvNetworkResult {
        val sanitizedMainUrl = sanitizeUrl(plan.streamUrl) ?: return AndroidMpvNetworkResult.Rejected(AndroidMpvNetworkRejection.InvalidUrl)
        val mainUri = parseUri(sanitizedMainUrl) ?: return AndroidMpvNetworkResult.Rejected(AndroidMpvNetworkRejection.InvalidUrl)
        val sameOrigin = credentialOriginGuard.mayAttachCredentials(sanitizedMainUrl)
        if (mainUri.scheme.equals("http", ignoreCase = true)) {
            if (!sameOrigin) {
                return AndroidMpvNetworkResult.Rejected(AndroidMpvNetworkRejection.CrossOrigin)
            }
            if (!isCleartextPermitted(mainUri.host)) {
                return AndroidMpvNetworkResult.Rejected(AndroidMpvNetworkRejection.CleartextNotPermitted)
            }
        }
        val authorizationHeader =
            if (sameOrigin) {
                AuthHeaderBuilder
                    .buildTokenOnly(session.accessToken)
                    ?.let { value -> "Authorization: $value" }
            } else {
                null
            }
        val subtitle = subtitleResource(subtitleAsset)
        val subtitleResource =
            when (subtitle) {
                is AndroidMpvSubtitleResource.Rejected ->
                    AndroidMpvNetworkRequest(
                        url = sanitizedMainUrl,
                        authorizationHeader = authorizationHeader,
                        subtitleUrl = null,
                        localSubtitlePath = null,
                        subtitleRejection = subtitle.reason,
                    )
                is AndroidMpvSubtitleResource.Accepted ->
                    AndroidMpvNetworkRequest(
                        url = sanitizedMainUrl,
                        authorizationHeader = authorizationHeader,
                        subtitleUrl = subtitle.remoteUrl,
                        localSubtitlePath = subtitle.localPath,
                    )
            }
        return AndroidMpvNetworkResult.Accepted(subtitleResource)
    }

    override suspend fun ensureTrustBundle(): File =
        withContext(ioDispatcher) {
            ensureTrustBundleBlocking()
        }

    /** Caller must already be on the controller-construction/native worker. */
    internal fun ensureTrustBundleBlocking(): File {
        val directory = trustBundleFile.parentFile ?: error("mpv trust bundle has no parent")
        if (!directory.exists()) check(directory.mkdirs() || directory.isDirectory) { "Unable to create mpv trust directory" }
        val certificates = trustCertificates?.invoke() ?: defaultTrustCertificates()
        val pem = certificatesToPem(certificates)
        val temporary = File(directory, ".${trustBundleFile.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(pem.toByteArray(Charsets.US_ASCII)) }
        atomicReplace(temporary, trustBundleFile)
        return trustBundleFile
    }

    private fun subtitleResource(asset: SubtitleAsset?): AndroidMpvSubtitleResource =
        when (asset) {
            null -> AndroidMpvSubtitleResource.Accepted(null, null)
            is SubtitleAsset.JellyfinRemote -> {
                val url = sanitizeUrl(asset.url)
                val uri = url?.let(::parseUri)
                if (url == null || uri == null || !credentialOriginGuard.mayAttachCredentials(url)) {
                    AndroidMpvSubtitleResource.Rejected(AndroidMpvNetworkRejection.CrossOrigin)
                } else if (uri.scheme.equals("http", ignoreCase = true) && !isCleartextPermitted(uri.host)) {
                    AndroidMpvSubtitleResource.Rejected(AndroidMpvNetworkRejection.CleartextNotPermitted)
                } else {
                    AndroidMpvSubtitleResource.Accepted(remoteUrl = url, localPath = null)
                }
            }
            is SubtitleAsset.LocalFile -> {
                val path = localSubtitleFileStore?.resolvePath(asset.fileId)
                if (path.isNullOrBlank()) {
                    AndroidMpvSubtitleResource.Rejected(AndroidMpvNetworkRejection.MissingLocalSubtitle)
                } else {
                    AndroidMpvSubtitleResource.Accepted(remoteUrl = null, localPath = path)
                }
            }
        }

    private fun sanitizeUrl(value: String): String? {
        val candidate = value.trim()
        if (candidate.isBlank() || candidate.startsWith("//") || '\\' in candidate) return null
        val absolute =
            if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
                candidate
            } else {
                if (candidate.contains(Regex("^[A-Za-z][A-Za-z0-9+.-]*:"))) return null
                "${session.serverUrl.trimEnd('/')}/${candidate.trimStart('/')}"
            }
        val uri = parseUri(absolute) ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) return null
        return CredentialOriginGuard.stripAuthQueryParams(absolute)
    }

    private fun parseUri(value: String): URI? =
        runCatching { URI(value) }
            .getOrNull()
            ?.takeIf { uri -> !uri.host.isNullOrBlank() }

    private fun isCleartextPermitted(host: String?): Boolean = host?.takeIf(String::isNotBlank)?.let(cleartextPermitted) == true

    private fun defaultTrustCertificates(): List<X509Certificate> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .flatMap { manager -> manager.acceptedIssuers.asList() }
            .distinctBy { certificate -> certificate.subjectX500Principal.name to certificate.serialNumber }
    }

    private fun atomicReplace(
        temporary: File,
        target: File,
    ) {
        val replaced =
            runCatching {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess
        if (!replaced) {
            if (target.exists()) check(target.delete()) { "Unable to replace mpv trust bundle" }
            check(temporary.renameTo(target)) { "Unable to install mpv trust bundle" }
        }
    }
}

internal fun certificatesToPem(certificates: List<X509Certificate>): String =
    buildString {
        certificates.forEach { certificate ->
            appendLine("-----BEGIN CERTIFICATE-----")
            appendLine(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(certificate.encoded))
            appendLine("-----END CERTIFICATE-----")
        }
    }

private sealed interface AndroidMpvSubtitleResource {
    data class Accepted(
        val remoteUrl: String?,
        val localPath: String?,
    ) : AndroidMpvSubtitleResource

    data class Rejected(
        val reason: AndroidMpvNetworkRejection,
    ) : AndroidMpvSubtitleResource
}
