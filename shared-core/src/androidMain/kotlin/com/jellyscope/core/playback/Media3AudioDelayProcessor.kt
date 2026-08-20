// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.jellyscope.core.domain.model.PLAYBACK_TIMING_OFFSET_LIMIT_MS
import com.jellyscope.core.domain.playback.Media3AudioTimingProcessor
import com.jellyscope.core.domain.playback.PlayerTimingCommandResult
import com.jellyscope.core.domain.playback.PlayerTimingSupport
import java.nio.ByteBuffer

/**
 * PCM delay processor used by the Media3 audio sink. Positive values delay the
 * audio by inserting silence; negative values drop the corresponding leading
 * PCM frames. Encoded passthrough/offload never reaches this processor (the
 * custom sink forces a decoder while an offset is active), so it only ever sees
 * PCM.
 *
 * The processor deliberately does NOT compensate the media clock: the inserted
 * silence / dropped frames flow through to the audio clock so the audible audio
 * shifts relative to the clock-slaved video. Reported position tracks video
 * (accepted; see docs/guides/data-playback.md). An offset change is applied by a
 * controller-driven re-prepare, whose sink flush re-runs [onFlush] and
 * re-establishes the shift at the new position — this processor holds no live
 * transition state of its own.
 */
internal class Media3AudioDelayProcessor :
    BaseAudioProcessor(),
    Media3AudioTimingProcessor {
    override val support: PlayerTimingSupport = PlayerTimingSupport.Supported

    @Volatile private var requestedOffsetMs = 0L
    private var pendingSilenceBytes = 0
    private var pendingDropBytes = 0

    // Reused zero-filled scratch so silence is emitted with one bulk copy per
    // chunk instead of a byte-by-byte loop (BaseAudioProcessor reuses its output
    // buffer, so it is not guaranteed zeroed on reuse).
    private val silenceChunk = ByteArray(MAX_SILENCE_CHUNK_BYTES)

    override fun setOffset(offsetMs: Long): PlayerTimingCommandResult {
        // Single last-line-of-defense clamp so buffer sizing in onFlush stays
        // bounded even if a caller bypasses the ViewModel seam.
        requestedOffsetMs = offsetMs.coerceIn(-PLAYBACK_TIMING_OFFSET_LIMIT_MS, PLAYBACK_TIMING_OFFSET_LIMIT_MS)
        return PlayerTimingCommandResult.Applied
    }

    /** Read by the audio output provider on the playback thread. */
    fun hasActiveOffset(): Boolean = requestedOffsetMs != 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Inactive at zero offset so 100% of normal playback keeps the input
        // format untouched (no per-buffer PCM copy). A zero<->non-zero change is
        // picked up by the controller's re-prepare, which re-runs onConfigure.
        if (requestedOffsetMs == 0L || !isSupportedPcm(inputAudioFormat)) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        val format = inputAudioFormat
        val frameBytes = format.bytesPerFrame.takeIf { value -> value > 0 } ?: return
        val offsetFrames = format.sampleRate.toLong() * requestedOffsetMs / 1_000L
        if (offsetFrames >= 0L) {
            pendingSilenceBytes = (offsetFrames * frameBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            pendingDropBytes = 0
        } else {
            pendingSilenceBytes = 0
            pendingDropBytes = (-offsetFrames * frameBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = outputAudioFormat.bytesPerFrame.takeIf { value -> value > 0 } ?: return

        if (pendingSilenceBytes > 0) {
            // Emit silence in bounded, frame-aligned chunks across successive
            // queueInput calls (the sink re-calls until the input is consumed).
            val chunk = minOf(pendingSilenceBytes, MAX_SILENCE_CHUNK_BYTES)
            val emit = (chunk - (chunk % frameBytes)).coerceAtLeast(frameBytes)
            pendingSilenceBytes -= emit
            val output = replaceOutputBuffer(emit)
            output.put(silenceChunk, 0, emit)
            output.flip()
            return
        }

        if (pendingDropBytes > 0) {
            val dropped = minOf(pendingDropBytes, inputBuffer.remaining())
            inputBuffer.position(inputBuffer.position() + dropped)
            pendingDropBytes -= dropped
            if (!inputBuffer.hasRemaining()) return
        }

        val copyBytes = inputBuffer.remaining() - (inputBuffer.remaining() % frameBytes)
        if (copyBytes <= 0) return
        val output = replaceOutputBuffer(copyBytes)
        val originalLimit = inputBuffer.limit()
        inputBuffer.limit(inputBuffer.position() + copyBytes)
        output.put(inputBuffer)
        inputBuffer.limit(originalLimit)
        output.flip()
    }

    private fun isSupportedPcm(format: AudioFormat): Boolean =
        format.sampleRate > 0 &&
            format.channelCount > 0 &&
            format.encoding in
            setOf(
                C.ENCODING_PCM_8BIT,
                C.ENCODING_PCM_16BIT,
                C.ENCODING_PCM_24BIT,
                C.ENCODING_PCM_32BIT,
                C.ENCODING_PCM_FLOAT,
            )

    private companion object {
        const val MAX_SILENCE_CHUNK_BYTES = 64 * 1024
    }
}
