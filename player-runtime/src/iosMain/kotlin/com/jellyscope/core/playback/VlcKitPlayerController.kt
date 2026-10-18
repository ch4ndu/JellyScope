// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlayerController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import platform.UIKit.UIView

/** iOS compatibility wrapper around the shared Apple VLCKit controller. */
public class VlcKitPlayerController private constructor(
    private val delegate: AppleVlcKitPlayerController,
    private val surfaceOwner: IosVlcKitSurfaceOwner,
) : PlayerController by delegate,
    IosPlaybackSurfaceProvider,
    IosPictureInPictureSurfaceProvider {
    private constructor(bundle: IosVlcKitControllerBundle) : this(bundle.controller, bundle.surfaceOwner)

    public constructor(
        session: Session,
        stateScope: CoroutineScope,
        localSubtitleFileStore: LocalSubtitleFileStore? = null,
        diagnosticPlatform: PlaybackDiagnosticPlatform = PlaybackDiagnosticPlatform.Ios,
        ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
    ) : this(
        createIosVlcKitControllerBundle(
            session = session,
            stateScope = stateScope,
            localSubtitleFileStore = localSubtitleFileStore,
            diagnosticPlatform = diagnosticPlatform,
            ioDispatcher = ioDispatcher,
        ),
    )

    internal fun setOfflineArtifactResolver(resolver: OfflineArtifactResolver) {
        delegate.setOfflineArtifactResolver(resolver)
    }

    override fun createSurfaceView(): UIView = delegate.createSurfaceView()

    override fun configurePictureInPicture(
        enabled: Boolean,
        callbacks: IosPictureInPictureSurfaceCallbacks,
    ) {
        if (delegate.isReleased) return
        surfaceOwner.configurePictureInPicture(enabled, callbacks)
        delegate.createSurfaceView()
    }

    override fun clearPictureInPictureCallbacks() {
        surfaceOwner.clearPictureInPictureCallbacks()
    }

    override fun requestPictureInPictureStart(): IosPictureInPictureStartAdmission = surfaceOwner.requestPictureInPictureStart()

    override fun requestPictureInPictureStop(sourceIdentity: Long) {
        surfaceOwner.requestPictureInPictureStop(sourceIdentity)
    }
}

private data class IosVlcKitControllerBundle(
    val controller: AppleVlcKitPlayerController,
    val surfaceOwner: IosVlcKitSurfaceOwner,
)

private fun createIosVlcKitControllerBundle(
    session: Session,
    stateScope: CoroutineScope,
    localSubtitleFileStore: LocalSubtitleFileStore?,
    diagnosticPlatform: PlaybackDiagnosticPlatform,
    ioDispatcher: CoroutineDispatcher,
): IosVlcKitControllerBundle {
    lateinit var surfaceOwner: IosVlcKitSurfaceOwner
    val controller =
        AppleVlcKitPlayerController(
            session = session,
            stateScope = stateScope,
            localSubtitleFileStore = localSubtitleFileStore,
            diagnosticPlatform = diagnosticPlatform,
            ioDispatcher = ioDispatcher,
            surfaceOwnerFactory = { transport ->
                IosVlcKitSurfaceOwner(transport).also { owner -> surfaceOwner = owner }
            },
        )
    return IosVlcKitControllerBundle(controller, surfaceOwner)
}
