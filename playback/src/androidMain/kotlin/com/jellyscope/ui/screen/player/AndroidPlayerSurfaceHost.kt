// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.SubtitleEdgeStyle
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.playback.AndroidMedia3SubtitleTimingBridge
import com.jellyscope.core.playback.AndroidPlayerSurfaceBridge
import com.jellyscope.core.playback.AndroidSurfacePresentation
import com.jellyscope.core.playback.AndroidSurfaceResizeMode
import android.graphics.Color as AndroidColor

@Composable
fun AndroidPlayerSurfaceHost(
    controller: PlayerController,
    modifier: Modifier,
    resizeMode: PlayerResizeMode,
    subtitleStyle: SubtitleStyle,
    subtitleBottomInsetPx: Int,
    mobileSubtitleBaseTextSizeSp: Float? = null,
) {
    val platformPlayer = controller.platformPlayer
    val bindingOwner = androidPlayerSurfaceBindingOwner
    val bindingIdentity = AndroidSurfaceBindingIdentityKey(controller, platformPlayer)
    val bindingToken = remember(bindingIdentity) { bindingOwner.newToken() }

    key(bindingToken) {
        val surfaceBridge = platformPlayer as? AndroidPlayerSurfaceBridge
        if (surfaceBridge != null) {
            AndroidView(
                factory = { context ->
                    surfaceBridge.createSurfaceView(context).also { view ->
                        view.keepScreenOn = true
                        bindingOwner.bind(
                            token = bindingToken,
                            detach = {
                                view.keepScreenOn = false
                                surfaceBridge.detachSurface(view)
                            },
                            attach = {
                                surfaceBridge.updatePresentation(
                                    resizeMode.toAndroidSurfacePresentation(subtitleBottomInsetPx),
                                )
                                view.post {
                                    if (bindingOwner.isCurrent(bindingToken)) {
                                        surfaceBridge.attachSurface(view)
                                    }
                                }
                            },
                        )
                    }
                },
                update = { view ->
                    view.keepScreenOn = true
                    if (bindingOwner.isCurrent(bindingToken)) {
                        surfaceBridge.updatePresentation(
                            resizeMode.toAndroidSurfacePresentation(subtitleBottomInsetPx),
                        )
                    }
                },
                onRelease = { bindingOwner.release(bindingToken) },
                modifier = modifier,
            )
        } else {
            val mediaPlayer = platformPlayer as? ExoPlayer
            val media3ResizeMode = resizeMode.toMedia3ResizeMode()
            val subtitleTimingBridge = controller as? AndroidMedia3SubtitleTimingBridge
            AndroidView(
                factory = { context ->
                    val playerView =
                        PlayerView(context).apply {
                            useController = false
                            keepScreenOn = true
                            this.resizeMode = media3ResizeMode
                            // The overlay below is the single, order-independent cue
                            // target; suppress PlayerView's own subtitle rendering.
                            subtitleView?.visibility = View.GONE
                        }
                    val overlay =
                        SubtitleView(context).apply {
                            applySubtitleStyleWhenReady(subtitleStyle, mobileSubtitleBaseTextSizeSp)
                            applySubtitleBottomInset(subtitleBottomInsetPx)
                        }
                    FrameLayout(context).apply {
                        keepScreenOn = true
                        addView(playerView, matchParentParams())
                        addView(overlay, matchParentParams())
                        bindingOwner.bind(
                            token = bindingToken,
                            detach = {
                                keepScreenOn = false
                                subtitleTimingBridge?.detachSubtitleView()
                                playerView.player = null
                            },
                            attach = {
                                playerView.player = mediaPlayer
                                mediaPlayer?.let { player ->
                                    subtitleTimingBridge?.attachSubtitleView(player) { cues -> overlay.setCues(cues) }
                                }
                            },
                        )
                    }
                },
                update = { frame ->
                    frame.keepScreenOn = true
                    val playerView = frame.getChildAt(0) as PlayerView
                    val overlay = frame.getChildAt(1) as SubtitleView
                    playerView.resizeMode = media3ResizeMode
                    playerView.subtitleView?.visibility = View.GONE
                    overlay.applySubtitleStyleWhenReady(subtitleStyle, mobileSubtitleBaseTextSizeSp)
                    overlay.applySubtitleBottomInset(subtitleBottomInsetPx)
                },
                onRelease = { bindingOwner.release(bindingToken) },
                modifier = modifier,
            )
        }
    }
}

private fun matchParentParams(): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

// The controller records the user's subtitle appearance in state; rendering is
// owned by the timing-adjusted overlay and must be applied to that view.
private fun SubtitleView.applySubtitleStyle(
    style: SubtitleStyle,
    mobileSubtitleBaseTextSizeSp: Float?,
) {
    val foreground = style.foregroundColor.parseColorOr(AndroidColor.WHITE)
    val background = style.backgroundColor.parseColorOr(AndroidColor.TRANSPARENT)
    val edgeType =
        when (style.edgeStyle) {
            SubtitleEdgeStyle.None -> CaptionStyleCompat.EDGE_TYPE_NONE
            SubtitleEdgeStyle.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleEdgeStyle.DropShadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        }
    setApplyEmbeddedStyles(false)
    setStyle(
        CaptionStyleCompat(
            foreground,
            background,
            AndroidColor.TRANSPARENT,
            edgeType,
            AndroidColor.BLACK,
            null,
        ),
    )
    if (mobileSubtitleBaseTextSizeSp == null) {
        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.fontScale)
    } else {
        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, mobileSubtitleBaseTextSizeSp * style.fontScale)
    }
}

private fun SubtitleView.applySubtitleStyleWhenReady(
    style: SubtitleStyle,
    mobileSubtitleBaseTextSizeSp: Float?,
) {
    applySubtitleStyle(style, mobileSubtitleBaseTextSizeSp)
    post { applySubtitleStyle(style, mobileSubtitleBaseTextSizeSp) }
}

private fun SubtitleView.applySubtitleBottomInset(bottomInsetPx: Int) {
    if (paddingBottom != bottomInsetPx) {
        setPadding(paddingLeft, paddingTop, paddingRight, bottomInsetPx)
    }
}

private fun String?.parseColorOr(default: Int): Int =
    this?.let { value -> runCatching { AndroidColor.parseColor(value) }.getOrNull() } ?: default

private fun PlayerResizeMode.toMedia3ResizeMode(): Int =
    when (this) {
        PlayerResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        PlayerResizeMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        PlayerResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

private fun PlayerResizeMode.toAndroidSurfacePresentation(subtitleBottomInsetPx: Int): AndroidSurfacePresentation =
    AndroidSurfacePresentation(
        resizeMode =
            when (this) {
                PlayerResizeMode.Fit -> AndroidSurfaceResizeMode.Fit
                PlayerResizeMode.Fill -> AndroidSurfaceResizeMode.Fill
                PlayerResizeMode.Zoom -> AndroidSurfaceResizeMode.Zoom
            },
        subtitleBottomInsetPx = subtitleBottomInsetPx,
    )
