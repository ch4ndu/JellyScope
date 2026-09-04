// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.jellyscopeDataDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal class DesktopMpvNetworkPolicy(
    rootDirectory: File = jellyscopeDataDirectory(),
    private val trustCertificates: () -> List<X509Certificate> = ::defaultJvmTrustCertificates,
) {
    private val trustBundleFile = File(File(rootDirectory, "mpv"), "trust-bundle.pem")

    fun ensureTrustBundleBlocking(): File {
        val directory = trustBundleFile.parentFile ?: error("mpv trust bundle has no parent")
        check(directory.exists() || directory.mkdirs()) { "Unable to create mpv trust directory" }
        val certificates = trustCertificates()
        check(certificates.isNotEmpty()) { "JVM trust manager has no accepted issuers" }
        val temporary = Files.createTempFile(directory.toPath(), ".trust-bundle-", ".pem")
        try {
            Files.write(temporary, certificates.toPem().encodeToByteArray())
            Files.move(temporary, trustBundleFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (exception: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            throw IllegalStateException("Unable to install mpv trust bundle", exception)
        }
        return trustBundleFile
    }
}

private fun defaultJvmTrustCertificates(): List<X509Certificate> {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers
        .filterIsInstance<X509TrustManager>()
        .flatMap { manager -> manager.acceptedIssuers.asList() }
        .distinctBy { certificate -> certificate.subjectX500Principal.name to certificate.serialNumber }
}

private fun List<X509Certificate>.toPem(): String {
    val certificates = this
    return buildString {
        certificates.forEach { certificate ->
            appendLine("-----BEGIN CERTIFICATE-----")
            appendLine(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(certificate.encoded))
            appendLine("-----END CERTIFICATE-----")
        }
    }
}
