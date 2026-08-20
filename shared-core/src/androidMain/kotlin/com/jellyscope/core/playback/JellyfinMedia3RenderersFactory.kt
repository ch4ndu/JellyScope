// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Project-owned Media3 renderer seam. The pinned Jellyfin FFmpeg extension is
 * discovered by Media3's extension renderer path, while decoder fallback stays
 * enabled for the platform decoder ladder.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class JellyfinMedia3RenderersFactory(
    context: Context,
    private val audioProcessor: Media3AudioDelayProcessor,
) : DefaultRenderersFactory(context) {
    private val defaultOffloadSupportProvider = DefaultAudioOffloadSupportProvider(context)

    init {
        setEnableDecoderFallback(true)
        // Native decoders remain first. The FFmpeg extension is the fallback
        // extension renderer supplied by the pinned Jellyfin artifact.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean,
    ): AudioSink {
        val sink =
            DefaultAudioSink
                // Context-aware builder keeps encoded-passthrough capabilities
                // (AC3/E-AC3/DTS bitstreaming) at zero offset. The no-context
                // builder assumes PCM-only capabilities and silently disables
                // passthrough for the whole session.
                .Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                // Preserve Media3's normal offload/passthrough policy at zero
                // offset. Once timing is non-zero, the provider closes the
                // encoded-output escape hatch so the PCM processor is guaranteed
                // to receive the samples.
                .setAudioOffloadSupportProvider { format, audioAttributes ->
                    if (audioProcessor.hasActiveOffset()) {
                        androidx.media3.exoplayer.audio.AudioOffloadSupport.DEFAULT_UNSUPPORTED
                    } else {
                        defaultOffloadSupportProvider.getAudioOffloadSupport(format, audioAttributes)
                    }
                }
                // The delay processor participates in Media3's default chain
                // (which still appends silence-skip + Sonic for speed/pitch). No
                // media-clock compensation is applied: the master audio clock
                // MUST count inserted silence and let dropped frames advance the
                // playout position, because that is exactly what shifts the
                // audible audio relative to the clock-slaved video. Reported
                // position (and Jellyfin progress) therefore tracks video; the
                // resulting <=10 s cosmetic shift vs the audible audio is an
                // accepted trade (see docs/guides/data-playback.md).
                .setAudioProcessors(arrayOf(audioProcessor))
                .build()
        // Media3 normally treats a device-supported encoded format as a
        // direct/passthrough route, which bypasses every AudioProcessor. When
        // timing is active, make the renderer select a decoder instead so the
        // encoded stream is converted to PCM before it reaches the processor.
        return TimingAwareAudioSink(sink, audioProcessor)
    }
}

private class TimingAwareAudioSink(
    sink: AudioSink,
    private val audioProcessor: Media3AudioDelayProcessor,
) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean =
        if (audioProcessor.hasActiveOffset() && format.sampleMimeType != MimeTypes.AUDIO_RAW) {
            false
        } else {
            super.supportsFormat(format)
        }

    override fun getFormatSupport(format: Format): Int =
        if (audioProcessor.hasActiveOffset() && format.sampleMimeType != MimeTypes.AUDIO_RAW) {
            // Mirror supportsFormat: an encoded route cannot carry the PCM
            // delay, so report unsupported (not WITH_TRANSCODING) so the two
            // forwarding answers cannot disagree.
            AudioSink.SINK_FORMAT_UNSUPPORTED
        } else {
            super.getFormatSupport(format)
        }

    override fun getFormatOffloadSupport(format: Format): androidx.media3.exoplayer.audio.AudioOffloadSupport =
        if (audioProcessor.hasActiveOffset()) {
            androidx.media3.exoplayer.audio.AudioOffloadSupport.DEFAULT_UNSUPPORTED
        } else {
            super.getFormatOffloadSupport(format)
        }
}
