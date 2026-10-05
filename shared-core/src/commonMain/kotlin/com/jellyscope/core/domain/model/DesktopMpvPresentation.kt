// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

/** App-global desktop mpv presentation. This is not a player backend or account preference. */
enum class DesktopMpvPresentation {
    /** Root-bundled runtime with the existing IOSurface/OpenGL presentation. */
    Standard,

    /** Isolated packaged Metal runtime. Experimental. */
    Metal,
}

enum class DesktopMpvPresentationAvailability {
    /** This platform never offers the selector; Standard always runs. */
    Unsupported,

    /** Both modes can be chosen; a change applies after restart. */
    Selectable,

    /** This install has no packaged Metal runtime; Standard runs. */
    MetalUnavailable,

    /** A development launch override fixes the active mode for this process. */
    LaunchOverride,
}

/**
 * [selected] is the durable saved choice. [active] is frozen at launch and
 * never changes while the process runs. [isSaving] covers a started write
 * until it settles, outliving the Settings screen that started it; no other
 * selection is admitted meanwhile.
 */
data class DesktopMpvPresentationState(
    val selected: DesktopMpvPresentation,
    val active: DesktopMpvPresentation,
    val availability: DesktopMpvPresentationAvailability,
    val isSaving: Boolean = false,
) {
    val isSelectorVisible: Boolean
        get() = availability != DesktopMpvPresentationAvailability.Unsupported

    val isSelectorEnabled: Boolean
        get() =
            availability == DesktopMpvPresentationAvailability.Selectable ||
                availability == DesktopMpvPresentationAvailability.MetalUnavailable

    val isMetalSelectable: Boolean
        get() = availability == DesktopMpvPresentationAvailability.Selectable

    /** Only a restart can apply the saved choice. */
    val isRestartRequired: Boolean
        get() = availability == DesktopMpvPresentationAvailability.Selectable && selected != active
}
