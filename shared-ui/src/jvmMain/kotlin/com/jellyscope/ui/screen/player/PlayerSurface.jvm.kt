// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.playback.DesktopVlcVideoOutput
import com.jellyscope.core.playback.MpvPresentationState
import com.jellyscope.core.playback.MpvRenderTarget
import com.jellyscope.core.playback.MpvVideoOutput
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

@Composable
actual fun PlayerSurface(
    controller: PlayerController,
    modifier: Modifier,
    resizeMode: PlayerResizeMode,
    // libmpv subtitle style is applied by the desktop PlayerController rather
    // than this software-frame drawing surface.
    subtitleStyle: com.jellyscope.core.domain.playback.SubtitleStyle,
    subtitleClearanceActive: Boolean,
    subtitleBottomClearance: androidx.compose.ui.unit.Dp?,
    pictureInPictureRequiresLinearPlayback: Boolean,
) {
    val platformPlayer = controller.platformPlayer
    val vlcOutput = platformPlayer as? DesktopVlcVideoOutput
    if (vlcOutput != null) {
        LaunchedEffect(resizeMode, vlcOutput) {
            vlcOutput.setFillCrop(crop = resizeMode != PlayerResizeMode.Fit)
        }
        NativeLibVlcPlayerSurface(
            output = vlcOutput,
            modifier = modifier,
        )
        return
    }

    val output = platformPlayer as? MpvVideoOutput
    if (output == null) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black))
        return
    }

    LaunchedEffect(output, subtitleClearanceActive) {
        output.setSubtitleClearanceActive(subtitleClearanceActive)
    }

    val presentationState by output.presentationState.collectAsState()

    LaunchedEffect(resizeMode, output) {
        output.setFillCrop(crop = resizeMode != PlayerResizeMode.Fit)
    }

    when (presentationState) {
        MpvPresentationState.WaitingForOpenGlSurface,
        is MpvPresentationState.InitializingOpenGl,
        is MpvPresentationState.OpenGlActive,
        ->
            OpenGlMpvPlayerSurface(
                output = output,
                modifier = modifier,
            )
        is MpvPresentationState.SoftwareActive ->
            SoftwareMpvPlayerSurface(output = output, modifier = modifier)
        MpvPresentationState.Failed,
        MpvPresentationState.Released,
        -> Box(modifier = modifier.fillMaxSize().background(Color.Black))
    }
}

@Composable
internal actual fun PlayerPointerActivityRegistration(
    enabled: Boolean,
    onActivity: () -> Unit,
) {
    val currentOnActivity by rememberUpdatedState(onActivity)
    DisposableEffect(enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }
        // The activity signal only resets the controls hide deadline, so
        // per-event granularity is wasted recomposition. Unthrottled resets at
        // pointer-move frequency stalled video presentation on macOS.
        var lastActivityAtNanos = 0L
        val throttledActivity = {
            val nowNanos = System.nanoTime()
            if (nowNanos - lastActivityAtNanos >= POINTER_ACTIVITY_THROTTLE_NANOS) {
                lastActivityAtNanos = nowNanos
                currentOnActivity()
            }
        }
        val listener =
            java.awt.event.AWTEventListener { event ->
                val mouseEvent = event as? MouseEvent ?: return@AWTEventListener
                val component = mouseEvent.source as? Component ?: return@AWTEventListener
                if (SwingUtilities.getWindowAncestor(component)?.isShowing == true) {
                    throttledActivity()
                }
            }
        Toolkit
            .getDefaultToolkit()
            .addAWTEventListener(listener, AWTEvent.MOUSE_MOTION_EVENT_MASK)
        onDispose {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
    }
}

private const val POINTER_ACTIVITY_THROTTLE_NANOS = 200_000_000L

@Composable
private fun NativeLibVlcPlayerSurface(
    output: DesktopVlcVideoOutput,
    modifier: Modifier,
) {
    val videoPresentationReady by output.videoPresentationReady.collectAsState()
    val host =
        remember(output) {
            MacLibVlcNativeHost(
                onSurfaceReady = output::attachVideoSurface,
                onSurfaceUnavailable = output::videoSurfaceUnavailable,
                onSurfaceResized = output::resizeVideoSurface,
                requestDetach = output::detachVideoSurface,
            )
        }
    LaunchedEffect(host, videoPresentationReady) {
        host.setVideoVisible(videoPresentationReady)
    }
    NativeSurfaceAnchor(modifier = modifier) { widthPt, heightPt ->
        SwingPanel(
            background = Color.Black,
            factory = { host },
            update = {
                host.setVideoContentSize(widthPt, heightPt)
            },
            modifier = Modifier.size(NATIVE_ANCHOR_SIZE_DP.dp),
        )
    }
}

@Composable
private fun OpenGlMpvPlayerSurface(
    output: MpvVideoOutput,
    modifier: Modifier,
) {
    val host =
        remember(output) {
            MacMpvOpenGlHost(
                onSurfaceReady = output::attachOpenGlSurface,
                onSurfaceUnavailable = output::openGlSurfaceUnavailable,
                onSurfaceResized = output::requestOpenGlRender,
                requestDetach = output::detachOpenGlSurface,
            )
        }
    NativeSurfaceAnchor(modifier = modifier) { widthPt, heightPt ->
        SwingPanel(
            background = Color.Black,
            factory = { host },
            update = {
                host.setVideoContentSize(widthPt, heightPt)
            },
            modifier = Modifier.size(NATIVE_ANCHOR_SIZE_DP.dp),
        )
    }
}

/**
 * Hosts the AWT anchor for a native video view as a 1dp corner marker
 * instead of a full-size interop rectangle. On macOS, Compose routes input
 * inside SwingPanel bounds to the interop component even when Compose
 * content renders above it (documented interop-blending limitation), which
 * made in-scene controls unclickable. The native view's real extent comes
 * from this Box's layout size (AWT points == dp on desktop) via
 * setVideoContentSize; its origin is the anchor's position.
 *
 * A full-size SwingPanel also implicitly punched the transparency hole that
 * let the native view show through the Compose scene; without it the scene's
 * opaque background hides the video (black screen with audio). The
 * BlendMode.Clear rect below re-punches that hole explicitly, and content
 * composed above it (the in-scene controls) still renders normally.
 */
@Composable
private fun NativeSurfaceAnchor(
    modifier: Modifier,
    content: @Composable (widthPt: Int, heightPt: Int) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(color = Color.Black, blendMode = BlendMode.Clear)
                    },
        )
        content(maxWidth.value.roundToInt(), maxHeight.value.roundToInt())
    }
}

private const val NATIVE_ANCHOR_SIZE_DP = 1

@Composable
private fun SoftwareMpvPlayerSurface(
    output: MpvVideoOutput,
    modifier: Modifier,
) {
    var frame by remember(output) { mutableStateOf<ImageBitmap?>(null) }
    val compositionScope = rememberCoroutineScope()
    val surface =
        remember(output) {
            DesktopMpvSurfaceCoordinator(
                output = output,
                parentContext = compositionScope.coroutineContext,
                publish = { image -> frame = image },
            )
        }

    DisposableEffect(surface) {
        surface.start()
        onDispose {
            surface.dispose()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }

        LaunchedEffect(widthPx, heightPx, surface) {
            if (widthPx > 0 && heightPx > 0) {
                delay(PLAYER_SURFACE_RESIZE_DEBOUNCE_MS)
            }
            surface.resize(widthPx, heightPx)
        }

        frame?.let { imageBitmap ->
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private class DesktopMpvSurfaceCoordinator(
    private val output: MpvVideoOutput,
    parentContext: CoroutineContext,
    private val publish: (ImageBitmap?) -> Unit,
) {
    private val scope = CoroutineScope(parentContext.minusKey(Job) + SupervisorJob())
    private val resizeMutex = Mutex()
    private val active = AtomicReference<MpvBufferCoordinator?>(null)
    private var disposed = false

    fun start() {
        output.setOnFrameAvailable {
            active.get()?.signal()
        }
    }

    suspend fun resize(
        width: Int,
        height: Int,
    ) {
        withContext(NonCancellable) {
            resizeMutex.withLock {
                if (disposed) return@withLock
                val previous = active.getAndSet(null)
                val retainedFrame = previous?.stopForResize()
                val generation = output.resize(width, height)
                if (!validSurfaceSize(width, height)) {
                    clearAndClose(retainedFrame)
                    return@withLock
                }

                val next =
                    MpvBufferCoordinator.create(
                        output = output,
                        width = width,
                        height = height,
                        generation = generation,
                        scope = scope,
                        publish = publish,
                        retainedFrame = retainedFrame,
                    )
                if (next == null) {
                    surfaceLogger.i { "stage=render event=rejected platform=desktop" }
                    clearAndClose(retainedFrame)
                    return@withLock
                }
                active.set(next)
                next.signal()
            }
        }
    }

    fun dispose() {
        output.setOnFrameAvailable(null)
        if (disposed) return
        disposed = true
        scope.launch {
            withContext(NonCancellable) {
                resizeMutex.withLock {
                    val current = active.getAndSet(null)
                    current?.stopAndClose(clearPublishedFrame = true)
                    output.resize(0, 0)
                }
            }
            scope.cancel()
        }
    }

    private suspend fun clearAndClose(retainedFrame: RetainedMpvFrame?) {
        withContext(Dispatchers.Main.immediate) {
            publish(null)
            withFrameNanos { }
        }
        retainedFrame?.close()
    }
}

private class MpvBufferCoordinator private constructor(
    private val output: MpvVideoOutput,
    private val buffers: List<MpvBitmapBuffer>,
    private val scope: CoroutineScope,
    private val publish: (ImageBitmap?) -> Unit,
    private var retainedFrame: RetainedMpvFrame?,
) {
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var front: MpvBitmapBuffer? = null
    private var back = buffers.first()
    private val renderJob =
        scope.launch(Dispatchers.Default) {
            for (ignored in signals) {
                renderLatestFrame()
            }
        }

    fun signal() {
        signals.trySend(Unit)
    }

    suspend fun stopForResize(): RetainedMpvFrame? {
        signals.close()
        renderJob.cancelAndJoin()
        val visibleFront = front
        buffers
            .filter { buffer -> buffer !== visibleFront }
            .forEach(MpvBitmapBuffer::close)
        return if (visibleFront != null) {
            retainedFrame?.close()
            retainedFrame = null
            RetainedMpvFrame(visibleFront)
        } else {
            retainedFrame
        }
    }

    suspend fun stopAndClose(clearPublishedFrame: Boolean) {
        signals.close()
        renderJob.cancelAndJoin()
        if (clearPublishedFrame) {
            withContext(Dispatchers.Main.immediate) {
                publish(null)
                withFrameNanos { }
            }
        }
        retainedFrame?.close()
        retainedFrame = null
        buffers.forEach(MpvBitmapBuffer::close)
    }

    private suspend fun renderLatestFrame() {
        val renderBuffer = back
        val result = output.renderFrameIfNeeded(renderBuffer.target) ?: return
        if (result.generation != renderBuffer.target.generation) return
        renderBuffer.bitmap.notifyPixelsChanged()

        val previousFront = front
        front = renderBuffer
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            publish(renderBuffer.imageBitmap)
            output.recordFramePublished(System.nanoTime())
            withFrameNanos { }
        }
        retainedFrame?.close()
        retainedFrame = null
        back =
            previousFront
                ?: buffers.first { buffer -> buffer !== renderBuffer }
    }

    companion object {
        fun create(
            output: MpvVideoOutput,
            width: Int,
            height: Int,
            generation: Long,
            scope: CoroutineScope,
            publish: (ImageBitmap?) -> Unit,
            retainedFrame: RetainedMpvFrame?,
        ): MpvBufferCoordinator? {
            val first = MpvBitmapBuffer.create(width, height, generation) ?: return null
            val second =
                MpvBitmapBuffer.create(width, height, generation)
                    ?: run {
                        first.close()
                        return null
                    }
            return MpvBufferCoordinator(
                output = output,
                buffers = listOf(first, second),
                scope = scope,
                publish = publish,
                retainedFrame = retainedFrame,
            )
        }
    }
}

private class MpvBitmapBuffer private constructor(
    val bitmap: Bitmap,
    val imageBitmap: ImageBitmap,
    val target: MpvRenderTarget,
) {
    fun close() {
        bitmap.close()
    }

    companion object {
        fun create(
            width: Int,
            height: Int,
            generation: Long,
        ): MpvBitmapBuffer? {
            if (!validSurfaceSize(width, height)) return null
            val bitmap = Bitmap()
            val allocated =
                runCatching {
                    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                    bitmap.allocPixels(info)
                }.getOrDefault(false)
            if (!allocated) {
                bitmap.close()
                return null
            }
            val pixmap =
                bitmap.peekPixels()
                    ?: run {
                        bitmap.close()
                        return null
                    }
            val address = pixmap.addr
            val stride = pixmap.rowBytes
            pixmap.close()
            if (address == 0L || stride.toLong() < width.toLong() * MPV_BYTES_PER_PIXEL) {
                bitmap.close()
                return null
            }
            return MpvBitmapBuffer(
                bitmap = bitmap,
                imageBitmap = bitmap.asComposeImageBitmap(),
                target =
                    MpvRenderTarget(
                        pixelAddress = address,
                        width = width,
                        height = height,
                        stride = stride,
                        generation = generation,
                    ),
            )
        }
    }
}

private class RetainedMpvFrame(
    private val buffer: MpvBitmapBuffer,
) {
    fun close() {
        buffer.close()
    }
}

private fun validSurfaceSize(
    width: Int,
    height: Int,
): Boolean {
    if (width <= 0 || height <= 0 || width > Int.MAX_VALUE / MPV_BYTES_PER_PIXEL) return false
    val stride = width.toLong() * MPV_BYTES_PER_PIXEL
    return stride <= Long.MAX_VALUE / height.toLong()
}

private const val PLAYER_SURFACE_RESIZE_DEBOUNCE_MS = 80L
private const val MPV_BYTES_PER_PIXEL = 4
private val surfaceLogger = diagnosticLogger(DiagnosticTag.DesktopMpvSurface)
