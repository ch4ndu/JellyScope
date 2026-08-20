// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

internal object DiagnosticsCodecAllowlist {
    const val UNRECOGNIZED = "unrecognized"

    val allowedCodecs: Set<String> =
        diagnosticsDeviceProfileDeclarations
            .asSequence()
            .flatMap { declaration -> (declaration.videoCodecs + declaration.audioCodecs).asSequence() }
            .mapNotNull(::canonicalVideoCodec)
            .toSet()

    val allowedContainers: Set<String> =
        diagnosticsDeviceProfileDeclarations
            .asSequence()
            .flatMap { declaration -> declaration.containers.asSequence() }
            .mapNotNull(::canonicalDiagnosticValue)
            .toSet()

    val allowedChannelLayouts: Set<String> =
        setOf("mono", "stereo", "2.1", "3.0", "4.0", "5.0", "5.1", "6.1", "7.1", "downmix")

    fun renderCodec(codec: String?): String =
        canonicalVideoCodec(codec)
            ?.takeIf { value -> value in allowedCodecs }
            ?: UNRECOGNIZED

    fun renderContainer(container: String?): String =
        canonicalDiagnosticValue(container)
            ?.takeIf { value -> value in allowedContainers }
            ?: UNRECOGNIZED

    fun renderChannelLayout(channelLayout: String?): String =
        canonicalDiagnosticValue(channelLayout)
            ?.takeIf { value -> value in allowedChannelLayouts }
            ?: UNRECOGNIZED
}

private fun canonicalDiagnosticValue(value: String?): String? =
    value
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotBlank)
