// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlin.test.Test
import kotlin.test.assertEquals

class MacosNowPlayingDispatcherTest {
    @Test
    fun installTeardownReinstallRoutesCommandsWithoutReRegistration() {
        val queued = mutableListOf<() -> Unit>()
        val dispatcher = NowPlayingCommandDispatcher { runnable -> queued += runnable }
        val first = RecordingTarget()
        val second = RecordingTarget()

        assertEquals(
            NowPlayingCommandResult.NoSuchContent,
            dispatcher.dispatch(NowPlayingCommand.Play),
        )
        dispatcher.install(first)
        assertEquals(NowPlayingCommandResult.Success, dispatcher.dispatch(NowPlayingCommand.Play))
        dispatcher.teardown(first)
        dispatcher.install(second)

        assertEquals(NowPlayingCommandResult.Success, dispatcher.dispatch(NowPlayingCommand.Pause))
        queued.removeFirst().invoke()
        queued.removeFirst().invoke()

        assertEquals(emptyList<NowPlayingCommand>(), first.commands)
        assertEquals(listOf<NowPlayingCommand>(NowPlayingCommand.Pause), second.commands)
    }

    @Test
    fun inactiveTargetReportsNoSuchContentWithoutEnqueueing() {
        val queued = mutableListOf<() -> Unit>()
        val dispatcher = NowPlayingCommandDispatcher { runnable -> queued += runnable }
        val target = RecordingTarget(active = false)

        dispatcher.install(target)

        assertEquals(
            NowPlayingCommandResult.NoSuchContent,
            dispatcher.dispatch(NowPlayingCommand.Play),
        )
        assertEquals(0, queued.size)
        assertEquals(emptyList<NowPlayingCommand>(), target.commands)
    }

    @Test
    fun queuedCommandForReplacedBridgeIsDropped() {
        val queued = mutableListOf<() -> Unit>()
        val dispatcher = NowPlayingCommandDispatcher { runnable -> queued += runnable }
        val oldTarget = RecordingTarget()
        val newTarget = RecordingTarget()

        dispatcher.install(oldTarget)
        assertEquals(NowPlayingCommandResult.Success, dispatcher.dispatch(NowPlayingCommand.Play))
        dispatcher.teardown(oldTarget)
        dispatcher.install(newTarget)

        queued.removeFirst().invoke()

        assertEquals(emptyList<NowPlayingCommand>(), oldTarget.commands)
        assertEquals(emptyList<NowPlayingCommand>(), newTarget.commands)
    }
}

private class RecordingTarget(
    private val active: Boolean = true,
) : NowPlayingCommandTarget {
    val commands = mutableListOf<NowPlayingCommand>()

    override val isActive: Boolean
        get() = active

    override fun handle(command: NowPlayingCommand) {
        commands += command
    }
}
