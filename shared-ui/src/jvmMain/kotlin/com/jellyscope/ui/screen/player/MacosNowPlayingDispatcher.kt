// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import javax.swing.SwingUtilities

// Values are Apple's MPRemoteCommandHandlerStatus constants (MPRemoteCommand.h):
// Success = 0, NoSuchContent = 100, CommandFailed = 200.
internal enum class NowPlayingCommandResult(
    val nativeValue: Long,
) {
    Success(0L),
    NoSuchContent(100L),
    CommandFailed(200L),
}

internal sealed interface NowPlayingCommand {
    data object Play : NowPlayingCommand

    data object Pause : NowPlayingCommand

    data object Toggle : NowPlayingCommand

    data class SkipForward(
        val intervalSeconds: Double,
    ) : NowPlayingCommand

    data class SkipBackward(
        val intervalSeconds: Double,
    ) : NowPlayingCommand

    data class ChangePosition(
        val positionSeconds: Double,
    ) : NowPlayingCommand
}

internal interface NowPlayingCommandTarget {
    // Synchronous no-content gate: the dispatcher consults this BEFORE
    // enqueueing so the native handler can honestly return NoSuchContent
    // (the queued runnable's staleness re-check remains the async guard).
    val isActive: Boolean

    fun handle(command: NowPlayingCommand)
}

/**
 * Process-independent command kernel. The native command target is registered
 * once, while this reference is replaced for each player session. Capturing
 * the target before enqueueing is deliberate: the runnable must not deliver a
 * media-key event to a player that has already been torn down or replaced.
 */
internal class NowPlayingCommandDispatcher(
    private val enqueue: ((() -> Unit) -> Unit) = { runnable ->
        SwingUtilities.invokeLater(runnable)
    },
) {
    private val lifecycleLock = Any()

    @Volatile
    private var installedTarget: NowPlayingCommandTarget? = null

    fun install(target: NowPlayingCommandTarget) {
        synchronized(lifecycleLock) {
            installedTarget = target
        }
    }

    fun teardown(target: NowPlayingCommandTarget) {
        synchronized(lifecycleLock) {
            if (installedTarget === target) {
                installedTarget = null
            }
        }
    }

    fun dispatch(command: NowPlayingCommand): NowPlayingCommandResult {
        val target = installedTarget ?: return NowPlayingCommandResult.NoSuchContent
        if (!target.isActive) return NowPlayingCommandResult.NoSuchContent
        return runCatching {
            enqueue {
                if (installedTarget === target) {
                    runCatching { target.handle(command) }
                }
            }
            NowPlayingCommandResult.Success
        }.getOrDefault(NowPlayingCommandResult.NoSuchContent)
    }
}
