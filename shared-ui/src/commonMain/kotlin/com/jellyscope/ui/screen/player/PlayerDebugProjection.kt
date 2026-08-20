// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState

data class PlayerDebugSection(
    val title: String,
    val rows: List<PlayerDebugRowModel>,
)

data class PlayerDebugRowModel(
    val label: String,
    val value: String,
    val emphasize: Boolean = false,
)

fun playerDebugSections(
    debugInfo: PlayerDebugInfo?,
    playbackState: PlaybackState,
    runtimeDiagnostics: PlaybackRuntimeDiagnostics,
): List<PlayerDebugSection> {
    if (debugInfo == null) {
        return listOf(
            PlayerDebugSection(
                title = "Playback",
                rows =
                    listOf(
                        PlayerDebugRowModel("Playback info", "Unavailable"),
                        PlayerDebugRowModel("Status", playbackState.status.name),
                    ),
            ),
        )
    }

    return listOf(
        PlayerDebugSection(
            title = "Playback",
            rows =
                buildList {
                    add(PlayerDebugRowModel("Backend", debugInfo.backend.name))
                    add(PlayerDebugRowModel("Play method", debugInfo.playMethod))
                    add(
                        PlayerDebugRowModel(
                            "Transcode reasons",
                            debugInfo.transcodeReasonSummary(),
                            emphasize = debugInfo.transcodeReasons.isNotEmpty(),
                        ),
                    )
                    debugInfo.container
                        ?.takeIf(String::isNotBlank)
                        ?.let { container -> add(PlayerDebugRowModel("Container", container)) }
                    add(PlayerDebugRowModel("Video", debugInfo.videoSummary()))
                    add(PlayerDebugRowModel("Audio", debugInfo.audioSummary()))
                    add(PlayerDebugRowModel("Subtitle render", debugInfo.subtitleRenderSummary()))
                    add(PlayerDebugRowModel("Subtitle styleable", debugInfo.subtitleStyleable.yesNo()))
                    add(
                        PlayerDebugRowModel(
                            "Launch / native first frame",
                            listOfNotNull(
                                debugInfo.launchToFirstFrameMs?.let { "$it ms" },
                                runtimeDiagnostics.nativePrepareToFirstFrameMs?.let { "$it ms" },
                            ).joinToString(" / ").ifEmpty { PLAYER_DEBUG_UNAVAILABLE },
                        ),
                    )
                    add(PlayerDebugRowModel("Rebuffers", runtimeDiagnostics.rebufferSummary()))
                    add(PlayerDebugRowModel("Audio underruns", runtimeDiagnostics.underrunSummary()))
                    add(PlayerDebugRowModel("Status", playbackState.status.name))
                },
        ),
        PlayerDebugSection(
            title = "Runtime",
            rows =
                listOf(
                    PlayerDebugRowModel("Decoder", runtimeDiagnostics.videoDecoderName.nonBlankOrUnavailable()),
                    PlayerDebugRowModel("Runtime format", runtimeDiagnostics.runtimeFormatSummary()),
                    PlayerDebugRowModel(
                        "Dropped frames",
                        runtimeDiagnostics.droppedVideoFrames?.toString() ?: PLAYER_DEBUG_UNAVAILABLE,
                    ),
                    PlayerDebugRowModel(
                        "Buffer policy",
                        runtimeDiagnostics.bufferPolicy?.name ?: PLAYER_DEBUG_UNAVAILABLE,
                    ),
                    PlayerDebugRowModel("Target / allocated", runtimeDiagnostics.bufferAllocationSummary()),
                    PlayerDebugRowModel(
                        "Buffered ahead",
                        runtimeDiagnostics.bufferedAheadMs?.debugSecondsLabel() ?: PLAYER_DEBUG_UNAVAILABLE,
                    ),
                    PlayerDebugRowModel(
                        "Bandwidth estimate",
                        runtimeDiagnostics.bandwidthEstimateBps?.debugMbpsLabel() ?: PLAYER_DEBUG_UNAVAILABLE,
                    ),
                ),
        ),
        PlayerDebugSection(
            title = "Policy",
            rows =
                listOf(
                    PlayerDebugRowModel(
                        "Source bitrate",
                        debugInfo.sourceBitrateBps?.debugMbpsLabel() ?: PLAYER_DEBUG_UNAVAILABLE,
                    ),
                    PlayerDebugRowModel(
                        "Request cap",
                        debugInfo.requestCapBitrateBps?.debugMbpsLabel() ?: "No client quality cap",
                    ),
                    PlayerDebugRowModel(
                        "Cap origin",
                        debugInfo.qualityCapOrigin?.debugLabel() ?: "None",
                    ),
                    PlayerDebugRowModel("Quality policy", debugInfo.qualityPolicyMode),
                    PlayerDebugRowModel(
                        "Capability result",
                        debugInfo.capabilityResult.debugLabel(),
                    ),
                    PlayerDebugRowModel(
                        "First video output",
                        debugInfo.firstVideoOutput.debugLabel(),
                    ),
                    PlayerDebugRowModel("Effective transcode cap", debugInfo.effectiveTranscodeCap),
                ),
        ),
    )
}

const val PLAYER_DEBUG_UNAVAILABLE = "—"

private fun PlayerDebugInfo.videoSummary(): String =
    listOfNotNull(
        videoCodec,
        videoPresentation?.debugLabel() ?: videoResolution,
        videoBitrateBps?.debugMbpsLabel(),
    ).joinToString(" · ").ifEmpty { PLAYER_DEBUG_UNAVAILABLE }

private fun PlayerDebugInfo.audioSummary(): String =
    listOfNotNull(audioCodec, audioChannels, audioLanguage)
        .joinToString(" · ")
        .ifEmpty { PLAYER_DEBUG_UNAVAILABLE }

private fun String?.nonBlankOrUnavailable(): String = this?.takeIf(String::isNotBlank) ?: PLAYER_DEBUG_UNAVAILABLE

private fun Boolean.yesNo(): String = if (this) "Yes" else "No"
