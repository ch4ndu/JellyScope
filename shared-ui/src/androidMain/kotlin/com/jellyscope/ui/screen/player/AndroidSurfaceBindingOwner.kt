// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

/** Main-thread owner for one exact Android controller/player surface binding. */
internal class AndroidSurfaceBindingOwner {
    private var nextSequence = 0L
    private var latestSequence = 0L
    private var latestReleased = false
    private var activeBinding: ActiveBinding? = null

    fun newToken(): AndroidSurfaceBindingToken = AndroidSurfaceBindingToken(owner = this, sequence = ++nextSequence)

    fun bind(
        token: AndroidSurfaceBindingToken,
        detach: () -> Unit,
        attach: () -> Unit,
    ) {
        check(token.owner === this) { "Surface binding token belongs to a different owner." }
        if (token.sequence < latestSequence || (token.sequence == latestSequence && latestReleased)) return
        if (activeBinding?.token == token) return

        latestSequence = token.sequence
        latestReleased = false
        detachActive()
        activeBinding = ActiveBinding(token, detach)
        try {
            attach()
        } catch (failure: Throwable) {
            activeBinding = null
            try {
                detach()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun release(token: AndroidSurfaceBindingToken) {
        if (token.owner !== this || activeBinding?.token != token) return
        latestReleased = true
        detachActive()
    }

    fun isCurrent(token: AndroidSurfaceBindingToken): Boolean = token.owner === this && !latestReleased && activeBinding?.token == token

    private fun detachActive() {
        val binding = activeBinding ?: return
        activeBinding = null
        binding.detach()
    }

    private class ActiveBinding(
        val token: AndroidSurfaceBindingToken,
        val detach: () -> Unit,
    )
}

internal class AndroidSurfaceBindingIdentityKey(
    private val controller: Any,
    private val platformPlayer: Any?,
) {
    override fun equals(other: Any?): Boolean =
        other is AndroidSurfaceBindingIdentityKey &&
            other.controller === controller &&
            other.platformPlayer === platformPlayer

    override fun hashCode(): Int = 0
}

internal class AndroidSurfaceBindingToken internal constructor(
    internal val owner: AndroidSurfaceBindingOwner,
    val sequence: Long,
)

internal val androidPlayerSurfaceBindingOwner = AndroidSurfaceBindingOwner()
