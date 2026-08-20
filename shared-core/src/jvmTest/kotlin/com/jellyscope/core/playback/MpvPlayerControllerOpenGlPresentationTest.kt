// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpvPlayerControllerOpenGlPresentationTest {
    @Test
    fun subtitleClearanceSetBeforeSurfaceIsAppliedWhenPendingPrepareReplays() {
        val lib = OpenGlPresentationLibMpv()
        withOpenGlController(lib) { controller ->
            controller.setSubtitleClearanceActive(true)
            controller.prepare(openGlPlaybackPlan)

            assertFalse(lib.propertyWrites.any { write -> write.startsWith("sub-margin-y=") })

            controller.attachOpenGlSurface(FakeOpenGlSurface(generation = 1L))

            waitUntil { lib.commands.any { command -> command.firstOrNull() == "loadfile" } }
            assertTrue(lib.propertyWrites.contains("sub-margin-y=180"))
        }
    }

    @Test
    fun openGlSurfaceInitializesRenderApiWithoutWindowHandleAndReplaysPendingIntentOnce() {
        val lib = OpenGlPresentationLibMpv()
        val surface =
            FakeOpenGlSurface(
                generation = 1L,
                fboId = 37,
                flipY = false,
                presentationLabel = "Test IOSurface Render API",
            )
        withOpenGlController(lib) { controller ->
            assertEquals(PlayerBackend.Mpv, controller.activeBackend)
            assertEquals(
                PlaybackHealthMeasurementCapabilities.BufferingAndDroppedFrames,
                controller.playbackHealthMeasurementCapabilities,
            )
            assertEquals(
                VideoOutputMeasurementCapabilities.Unsupported,
                controller.videoOutputMeasurementCapabilities,
            )
            controller.prepare(openGlPlaybackPlan)
            controller.play()
            controller.seekTo(12_000L)
            controller.setPlaybackSpeed(1.25f)
            controller.setFillCrop(true)

            controller.attachOpenGlSurface(surface)

            waitUntil { controller.presentationState.value is MpvPresentationState.OpenGlActive }
            waitUntil { lib.commands.count { command -> command.firstOrNull() == "loadfile" } == 1 }
            waitUntil { lib.renderCount.get() > 0 }

            val initializedOptions = lib.initializedOptions.single()
            assertTrue(initializedOptions.contains("vo=libmpv"))
            assertFalse(initializedOptions.any { option -> option.startsWith("wid=") })
            assertFalse(initializedOptions.any { option -> option.startsWith("gpu-context=") })
            assertEquals(listOf("opengl"), lib.renderApis)
            assertTrue(lib.openGlInitParamSeen.get())
            assertEquals(1, lib.commands.count { command -> command.firstOrNull() == "loadfile" })
            assertTrue(lib.optionWrites.contains("start=+12.0"))
            assertFalse(lib.commands.any { command -> command.firstOrNull() == "seek" })
            waitUntil { lib.propertyWrites.contains("speed=1.25") }
            assertTrue(lib.propertyWrites.contains("speed=1.25"))
            assertTrue(lib.propertyWrites.contains("panscan=1.0"))
            assertTrue(lib.propertyWrites.contains("pause=no"))
            assertEquals(37 to (2_000 to 1_000), lib.lastFramebuffer)
            assertEquals(0, lib.lastFlipY)
            val loaded =
                controller.javaClass
                    .getDeclaredField("loaded")
                    .apply { isAccessible = true }
                    .get(controller) as AtomicBoolean
            loaded.set(true)
            controller.javaClass
                .getDeclaredMethod("updateRuntimeDiagnosticsIfDue")
                .apply { isAccessible = true }
                .invoke(controller)
            assertEquals("Test IOSurface Render API", controller.runtimeDiagnostics.value.presentationPath)
        }
    }

    @Test
    fun hardwareDecodePolicyIsLimitedToMacOsIosurface() {
        assertEquals(
            MpvHardwareDecodePolicy.VideoToolboxCopy,
            resolveMpvHardwareDecodePolicy(
                osName = "Mac OS X",
                surfaceKind = MpvOpenGlSurfaceKind.IOSurface,
            ),
        )

        assertEquals(
            MpvHardwareDecodePolicy.AutoSafe,
            resolveMpvHardwareDecodePolicy(
                osName = "Mac OS X",
                surfaceKind = MpvOpenGlSurfaceKind.OpenGL,
            ),
        )
        assertEquals(
            MpvHardwareDecodePolicy.AutoSafe,
            resolveMpvHardwareDecodePolicy(
                osName = "Linux",
                surfaceKind = MpvOpenGlSurfaceKind.IOSurface,
            ),
        )
        assertEquals(
            MpvHardwareDecodePolicy.AutoSafe,
            resolveMpvHardwareDecodePolicy(
                osName = "Mac OS X",
                surfaceKind = null,
            ),
        )
    }

    @Test
    fun controllerWritesResolvedHardwareDecodeOptionForEachSurfaceRoute() {
        val cases =
            listOf(
                HardwareDecodeControllerCase(
                    name = "macOS IOSurface",
                    osName = "Mac OS X",
                    surfaceKind = MpvOpenGlSurfaceKind.IOSurface,
                    expectedOptionValue = "videotoolbox-copy",
                ),
                HardwareDecodeControllerCase(
                    name = "macOS OpenGL",
                    osName = "Mac OS X",
                    surfaceKind = MpvOpenGlSurfaceKind.OpenGL,
                    expectedOptionValue = "auto-safe",
                ),
                HardwareDecodeControllerCase(
                    name = "non-macOS IOSurface",
                    osName = "Linux",
                    surfaceKind = MpvOpenGlSurfaceKind.IOSurface,
                    expectedOptionValue = "auto-safe",
                ),
            )

        cases.forEach { testCase ->
            val lib = OpenGlPresentationLibMpv()
            withSystemProperties(
                mapOf("os.name" to testCase.osName),
            ) {
                withOpenGlController(lib) { controller ->
                    controller.attachOpenGlSurface(
                        FakeOpenGlSurface(
                            generation = 1L,
                            surfaceKind = testCase.surfaceKind,
                            presentationLabel =
                                when (testCase.surfaceKind) {
                                    MpvOpenGlSurfaceKind.IOSurface -> "IOSurface Render API"
                                    MpvOpenGlSurfaceKind.OpenGL -> "OpenGL Render API"
                                },
                        ),
                    )

                    waitUntil { lib.initializedOptions.isNotEmpty() }
                    val hwdecOption =
                        lib.initializedOptions
                            .single()
                            .single { option -> option.startsWith("hwdec=") }
                    assertEquals(
                        "hwdec=${testCase.expectedOptionValue}",
                        hwdecOption,
                        testCase.name,
                    )
                }
            }
        }
    }

    @Test
    fun playbackProbeIncludesRequestedDecodeAndSanitizesNativeValues() {
        val probe =
            MpvStatusProbeRecord(
                status = DesktopProbeToken.from(PlaybackStatus.Playing),
                positionMs = -1L,
                durationMs = -2L,
                streamMode = DesktopProbeToken.from(StreamMode.DirectPlay),
                presentation = DesktopProbeToken.from("IOSurface Render API"),
                hardwareDecodeRequested = DesktopProbeToken.from(MpvHardwareDecodePolicy.VideoToolboxCopy),
                hardwareDecodeResolved = DesktopProbeToken.from("https://token.example/server/video"),
                decoder = DesktopProbeToken.from("Google VP9 · videotoolbox-copy"),
                width = 1_920,
                height = 1_080,
                frameRate = 24.0,
                droppedFrames = 0L,
                decoderDroppedFrames = 1L,
                outputDroppedFrames = 2L,
            ).serialized

        assertTrue(probe.contains("event=mpvStatus"))
        assertTrue(probe.contains("presentation=IOSurface_Render_API"))
        assertTrue(probe.contains("hwdecRequested=VideoToolboxCopy"))
        assertTrue(probe.contains("hwdecResolved=unknown"))
        assertTrue(probe.contains("decoder=Google_VP9___videotoolbox-copy"))
        assertTrue(probe.contains("positionMs=-1"))
        assertTrue(probe.contains("durationMs=-1"))
        assertFalse(probe.contains("https://"))
        assertFalse(probe.contains("token.example"))
        assertFalse(probe.contains("/"))
    }

    @Test
    fun stagedPrepareIntentIsClaimedByOnlyOneReplayOwner() {
        val lib = OpenGlPresentationLibMpv()
        withOpenGlController(lib) { controller ->
            controller.prepare(openGlPlaybackPlan)
            val claim =
                controller.javaClass
                    .getDeclaredMethod("claimPendingPrepareIntent")
                    .apply { isAccessible = true }
            val start = CountDownLatch(1)
            val results = CopyOnWriteArrayList<Any?>()
            val callers =
                List(2) {
                    thread(start = false) {
                        start.await()
                        results += claim.invoke(controller)
                    }
                }

            callers.forEach(Thread::start)
            start.countDown()
            callers.forEach(Thread::join)

            assertEquals(1, results.count { result -> result != null })
            assertEquals(1, results.count { result -> result == null })
        }
    }

    @Test
    fun claimedReplayCannotMutateAReplacementPrepare() {
        val lib = OpenGlPresentationLibMpv()
        withOpenGlController(lib) { controller ->
            val staleIntent = stageAndClaimReplayIntent(controller)
            controller.attachOpenGlSurface(FakeOpenGlSurface(generation = 1L))
            waitUntil {
                controller.presentationState.value is MpvPresentationState.OpenGlActive &&
                    lib.initializedOptions.size == 1
            }

            val replacementPlan =
                openGlPlaybackPlan.copy(
                    itemId = "replacement",
                    mediaSourceId = "replacement-source",
                    streamUrl = "https://cdn.example/replacement",
                    playbackSpeed = 0.75f,
                    subtitleStyle = SubtitleStyle(fontScale = 0.9f),
                )
            controller.prepare(replacementPlan)

            val expectedState = controller.playbackState.value
            val expectedOptions = lib.optionWrites.toList()
            val expectedProperties = lib.propertyWrites.toList()
            val expectedCommands = lib.commands.map { command -> command.toList() }

            replayClaimedIntent(controller, staleIntent)

            assertEquals(expectedState, controller.playbackState.value)
            assertEquals(expectedOptions, lib.optionWrites)
            assertEquals(expectedProperties, lib.propertyWrites)
            assertEquals(expectedCommands, lib.commands)
            assertEquals(1, lib.commands.count { command -> command.firstOrNull() == "loadfile" })
        }
    }

    @Test
    fun claimedReplayCannotMutateAStoppedController() {
        val lib = OpenGlPresentationLibMpv()
        withOpenGlController(lib) { controller ->
            val staleIntent = stageAndClaimReplayIntent(controller)
            controller.attachOpenGlSurface(FakeOpenGlSurface(generation = 1L))
            waitUntil {
                controller.presentationState.value is MpvPresentationState.OpenGlActive &&
                    lib.initializedOptions.size == 1
            }
            controller.stop()

            val expectedState = controller.playbackState.value
            val expectedOptions = lib.optionWrites.toList()
            val expectedProperties = lib.propertyWrites.toList()
            val expectedCommands = lib.commands.map { command -> command.toList() }

            replayClaimedIntent(controller, staleIntent)

            assertEquals(PlaybackStatus.Idle, controller.playbackState.value.status)
            assertEquals(expectedState, controller.playbackState.value)
            assertEquals(expectedOptions, lib.optionWrites)
            assertEquals(expectedProperties, lib.propertyWrites)
            assertEquals(expectedCommands, lib.commands)
            assertEquals(0, lib.commands.count { command -> command.firstOrNull() == "loadfile" })
        }
    }

    private fun stageAndClaimReplayIntent(controller: MpvPlayerController): Any {
        controller.prepare(
            openGlPlaybackPlan.copy(
                itemId = "stale",
                mediaSourceId = "stale-source",
                startPositionMs = 33_000L,
                audioActivationTarget = staleAudioSelection.target,
            ),
        )
        controller.selectEmbeddedAudio(staleAudioSelection)
        controller.setPlaybackSpeed(1.75f)
        controller.setSubtitleStyle(SubtitleStyle(fontScale = 1.4f, foregroundColor = "#FFFF00"))
        controller.play()

        return requireNotNull(
            controller.javaClass
                .getDeclaredMethod("claimPendingPrepareIntent")
                .apply { isAccessible = true }
                .invoke(controller),
        )
    }

    private fun replayClaimedIntent(
        controller: MpvPlayerController,
        intent: Any,
    ) {
        controller.javaClass.declaredMethods
            .single { method ->
                method.name == "replayPendingInitializationIntent" && method.parameterCount == 1
            }.apply { isAccessible = true }
            .invoke(controller, intent)
    }

    @Test
    fun releaseDuringPreAdmissionWindowIsNotOverwrittenBySoftwareInitialization() {
        // The lifecycle regression: the presentation state must not be published
        // outside the lock after an unlocked precondition check, allowing an
        // initialization to overwrite `Released`. The locked admission re-checks
        // `released`, so the initialization is rejected instead.
        //
        // MECHANISM (fragile on purpose, keep it in mind when refactoring):
        // `presentationMetrics.reset()` runs in `startSoftwareInitialization`
        // AFTER the fast-path check and BEFORE the locked admission, so holding
        // that object's monitor parks the initializer in exactly the window this
        // test needs. If that call ever moves inside `engineLock`, this test will
        // deadlock rather than fail — move the hold point, do not delete the test.
        val lib = OpenGlPresentationLibMpv()
        withOpenGlController(lib) { controller ->
            controller.prepare(openGlPlaybackPlan)
            val presentationMetrics =
                controller.javaClass
                    .getDeclaredField("presentationMetrics")
                    .apply { isAccessible = true }
                    .get(controller)
            val released =
                controller.javaClass
                    .getDeclaredField("released")
                    .apply { isAccessible = true }
                    .get(controller) as AtomicBoolean
            try {
                val initializationThread =
                    thread(start = false) {
                        controller.openGlSurfaceUnavailable(
                            generation = 1L,
                            reason = MpvOpenGlSurfaceUnavailableReason.InaccessibleJdkInternals,
                        )
                    }
                lateinit var releaseThread: Thread
                synchronized(presentationMetrics) {
                    initializationThread.start()
                    waitUntil { initializationThread.state == Thread.State.BLOCKED }
                    releaseThread = thread(start = false) { controller.release() }
                    releaseThread.start()
                    waitUntil {
                        released.get() && controller.presentationState.value == MpvPresentationState.Released
                    }
                }

                initializationThread.join(3_000L)
                releaseThread.join(3_000L)
                assertFalse(initializationThread.isAlive)
                assertFalse(releaseThread.isAlive)

                assertEquals(MpvPresentationState.Released, controller.presentationState.value)
                assertEquals(0, lib.createCount.get(), "a rejected initialization must not create an engine")
            } finally {
                controller.release()
            }
        }
    }

    @Test
    fun releaseWinsAgainstAdmittedSoftwareInitialization() {
        // Release must win against an already-admitted
        // initialization. The initializer is held inside engine creation — past
        // admission and outside `engineLock` — so release() can genuinely
        // interleave. The initializer must not republish an active presentation
        // state over `Released` or install an engine that nothing owns.
        val allowCreate = CountDownLatch(1)
        val lib = OpenGlPresentationLibMpv(allowCreate = allowCreate)
        withOpenGlController(lib) { controller ->
            controller.prepare(openGlPlaybackPlan)
            val observedStates = CopyOnWriteArrayList<MpvPresentationState>()
            val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val observationJob =
                observationScope.launch {
                    controller.presentationState.collect { state -> observedStates += state }
                }
            try {
                controller.openGlSurfaceUnavailable(
                    generation = 1L,
                    reason = MpvOpenGlSurfaceUnavailableReason.InaccessibleJdkInternals,
                )
                assertTrue(
                    lib.createStarted.await(3L, TimeUnit.SECONDS),
                    "software initialization never reached engine creation",
                )

                controller.release()
                assertEquals(MpvPresentationState.Released, controller.presentationState.value)

                allowCreate.countDown()

                // Whatever the held initializer created must be torn down rather
                // than left installed, and teardown must complete.
                waitUntil { lib.terminateCount.get() == lib.createCount.get() }

                // The authoritative assertion: had the held initializer
                // republished, the latest value would be an active state.
                assertEquals(MpvPresentationState.Released, controller.presentationState.value)

                // Supplementary ordering check. Wait for the collector to observe
                // Released rather than sleeping for it.
                waitUntil { observedStates.contains(MpvPresentationState.Released) }
                val releasedIndex = observedStates.indexOfLast { state -> state == MpvPresentationState.Released }
                assertTrue(releasedIndex >= 0, "Released state was not observed")
                assertTrue(
                    observedStates.drop(releasedIndex + 1).none { state ->
                        state is MpvPresentationState.InitializingOpenGl ||
                            state is MpvPresentationState.OpenGlActive ||
                            state is MpvPresentationState.SoftwareActive
                    },
                    "presentation became active after release: $observedStates",
                )
            } finally {
                observationJob.cancel()
                observationScope.cancel()
            }
        }
    }

    @Test
    fun openGlInitializationFailureFallsBackToSoftwareWithinSamePendingPrepare() {
        val lib = OpenGlPresentationLibMpv(failOpenGlRenderContext = true)
        withOpenGlController(lib) { controller ->
            controller.prepare(openGlPlaybackPlan)
            controller.play()
            controller.attachOpenGlSurface(FakeOpenGlSurface(generation = 1L))

            waitUntil { lib.renderApis == listOf("opengl", "sw") }
            waitUntil { lib.commands.count { command -> command.firstOrNull() == "loadfile" } == 1 }

            val state = assertIs<MpvPresentationState.SoftwareActive>(controller.presentationState.value)
            assertEquals(MpvOpenGlSurfaceUnavailableReason.OpenGlInitializationFailed, state.fallbackReason)
            assertEquals(2, lib.createCount.get())
            assertEquals(2, lib.initializeCount.get())
            assertEquals(1, lib.terminateCount.get())
            assertTrue(lib.propertyWrites.contains("pause=no"))
        }
    }

    @Test
    fun missingOpenGlSurfaceFallsBackToSoftwareWithinBoundedDeadline() {
        val lib = OpenGlPresentationLibMpv()
        withOpenGlController(lib) { controller ->
            val startedAt = System.nanoTime()
            controller.prepare(openGlPlaybackPlan)
            controller.play()

            waitUntil(timeoutSeconds = 4L) { lib.renderApis == listOf("sw") }
            waitUntil { lib.commands.any { command -> command.firstOrNull() == "loadfile" } }

            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            val state = assertIs<MpvPresentationState.SoftwareActive>(controller.presentationState.value)
            assertEquals(MpvOpenGlSurfaceUnavailableReason.SurfaceTimeout, state.fallbackReason)
            assertTrue(elapsedMs in 1_800L..3_500L, "Fallback took ${elapsedMs}ms")
        }
    }

    @Test
    fun detachAcknowledgesOnlyAfterRenderContextDestroyAndSurfaceClose() {
        val allowTerminate = CountDownLatch(1)
        val lib = OpenGlPresentationLibMpv(allowTerminate = allowTerminate)
        val surface = FakeOpenGlSurface(generation = 1L)
        withOpenGlController(lib) { controller ->
            controller.attachOpenGlSurface(surface)
            waitUntil { controller.presentationState.value is MpvPresentationState.OpenGlActive }

            val detached = CountDownLatch(1)
            controller.detachOpenGlSurface(1L, MpvOpenGlDetachCallback { detached.countDown() })
            waitUntil { lib.terminateStarted.count == 0L }

            assertFalse(detached.await(100L, TimeUnit.MILLISECONDS))
            assertFalse(surface.closed.get())

            allowTerminate.countDown()
            assertTrue(detached.await(3L, TimeUnit.SECONDS))
            assertEquals(1, lib.renderContextFreeCount.get())
            assertEquals(1, lib.terminateCount.get())
            assertTrue(surface.closed.get())
            assertEquals(MpvPresentationState.Failed, controller.presentationState.value)
        }
    }

    @Test
    fun hungRenderQuarantinesEngineAndSkipsSurfaceCloseAndLaterNativeCalls() {
        val lib = OpenGlPresentationLibMpv()
        val surface = BlockingOpenGlSurface(generation = 1L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller =
            MpvPlayerController(
                session = openGlSession,
                stateScope = scope,
                mpv = lib,
                presentationPreference = MpvPresentationPreference.MacOsOpenGl,
            )
        try {
            controller.attachOpenGlSurface(surface)
            waitUntil { controller.presentationState.value is MpvPresentationState.OpenGlActive }
            surface.blockNextRender()
            controller.requestOpenGlRender(1L)
            assertTrue(surface.renderEntered.await(3L, TimeUnit.SECONDS))

            val detached = CountDownLatch(1)
            val disposition = AtomicReference<MpvOpenGlDetachDisposition?>(null)
            controller.detachOpenGlSurface(
                1L,
                MpvOpenGlDetachCallback { result ->
                    disposition.set(result)
                    detached.countDown()
                },
            )

            assertTrue(detached.await(6L, TimeUnit.SECONDS))
            assertEquals(MpvOpenGlDetachDisposition.Quarantined, disposition.get())
            assertFalse(surface.closed.get())
            assertEquals(0, lib.renderContextFreeCount.get())
            assertEquals(0, lib.terminateCount.get())

            val quarantined =
                controller.javaClass
                    .getDeclaredField("quarantinedEngine")
                    .apply { isAccessible = true }
                    .get(controller)
            assertNotNull(quarantined)
            val renderState =
                quarantined.javaClass
                    .getDeclaredField("renderState")
                    .apply { isAccessible = true }
                    .get(quarantined)
            assertNotNull(
                renderState.javaClass
                    .getDeclaredField("getProcAddressCallback")
                    .apply { isAccessible = true }
                    .get(renderState),
            )

            val creates = lib.createCount.get()
            val commands = lib.commands.size
            val properties = lib.propertyWrites.size
            controller.play()
            controller.pause()
            controller.seekTo(4_000L)
            controller.release()
            assertEquals(creates, lib.createCount.get())
            assertEquals(commands, lib.commands.size)
            assertEquals(properties, lib.propertyWrites.size)
            assertEquals(0, lib.terminateCount.get())
        } finally {
            surface.releaseRender()
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun unavailableSurfaceRejectsAStaleLaterAttach() {
        val lib = OpenGlPresentationLibMpv()
        withOpenGlController(lib) { controller ->
            controller.openGlSurfaceUnavailable(
                generation = 2L,
                reason = MpvOpenGlSurfaceUnavailableReason.InaccessibleJdkInternals,
            )
            controller.attachOpenGlSurface(FakeOpenGlSurface(generation = 1L))

            Thread.sleep(100L)
            assertEquals(0, lib.createCount.get())
            val state = assertIs<MpvPresentationState.SoftwareActive>(controller.presentationState.value)
            assertEquals(MpvOpenGlSurfaceUnavailableReason.InaccessibleJdkInternals, state.fallbackReason)
        }
    }

    @Test
    fun prepareDoesNotReplaceTerminalEngineFailureWithLoading() {
        val lib =
            OpenGlPresentationLibMpv(
                failOpenGlRenderContext = true,
                failSoftwareRenderContext = true,
            )
        withOpenGlController(lib) { controller ->
            controller.prepare(openGlPlaybackPlan)
            controller.attachOpenGlSurface(FakeOpenGlSurface(generation = 1L))

            waitUntil { controller.presentationState.value == MpvPresentationState.Failed }
            assertEquals(PlaybackStatus.Failed, controller.playbackState.value.status)

            controller.prepare(openGlPlaybackPlan.copy(itemId = "replacement"))

            assertEquals(MpvPresentationState.Failed, controller.presentationState.value)
            assertEquals(PlaybackStatus.Failed, controller.playbackState.value.status)
            assertEquals(0, lib.commands.count { command -> command.firstOrNull() == "loadfile" })
        }
    }
}

private class FakeOpenGlSurface(
    override val generation: Long,
    private val fboId: Int = 0,
    private val flipY: Boolean = true,
    override val surfaceKind: MpvOpenGlSurfaceKind = MpvOpenGlSurfaceKind.OpenGL,
    override val presentationLabel: String = MPV_PRESENTATION_OPENGL,
) : MpvOpenGlRenderSurface {
    val closed = AtomicBoolean(false)

    override fun resolveGlSymbol(name: String): Long = 1L

    override fun withCurrentContext(render: (MpvOpenGlFramebuffer) -> Boolean): Boolean {
        if (closed.get()) return false
        render(
            MpvOpenGlFramebuffer(
                widthPx = 2_000,
                heightPx = 1_000,
                fboId = fboId,
                flipY = flipY,
            ),
        )
        return true
    }

    override fun close() {
        closed.set(true)
    }
}

private class BlockingOpenGlSurface(
    override val generation: Long,
) : MpvOpenGlRenderSurface {
    val closed = AtomicBoolean(false)
    val renderEntered = CountDownLatch(1)
    private val blockRender = AtomicBoolean(false)
    private val allowRender = CountDownLatch(1)

    override fun resolveGlSymbol(name: String): Long = 1L

    override fun withCurrentContext(render: (MpvOpenGlFramebuffer) -> Boolean): Boolean {
        if (blockRender.get()) {
            renderEntered.countDown()
            while (true) {
                try {
                    if (allowRender.await(100L, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    // Keep the render hung through shutdownNow so the controller
                    // exercises its quarantine path.
                }
            }
            return false
        }
        if (closed.get()) return false
        render(MpvOpenGlFramebuffer(widthPx = 2_000, heightPx = 1_000))
        return true
    }

    override fun close() {
        closed.set(true)
    }

    fun blockNextRender() {
        blockRender.set(true)
    }

    fun releaseRender() {
        allowRender.countDown()
    }
}

private class OpenGlPresentationLibMpv(
    private val failOpenGlRenderContext: Boolean = false,
    private val failSoftwareRenderContext: Boolean = false,
    private val allowTerminate: CountDownLatch? = null,
    private val allowCreate: CountDownLatch? = null,
) : LibMpv {
    val createStarted = CountDownLatch(1)
    val createCount = AtomicInteger(0)
    val initializeCount = AtomicInteger(0)
    val terminateCount = AtomicInteger(0)
    val renderCount = AtomicInteger(0)
    val renderContextFreeCount = AtomicInteger(0)
    val terminateStarted = CountDownLatch(1)
    val initializedOptions = CopyOnWriteArrayList<List<String>>()
    val renderApis = CopyOnWriteArrayList<String>()
    val optionWrites = CopyOnWriteArrayList<String>()
    val propertyWrites = CopyOnWriteArrayList<String>()
    val commands = CopyOnWriteArrayList<List<String>>()
    val openGlInitParamSeen = AtomicBoolean(false)

    @Volatile var lastFramebuffer: Pair<Int, Pair<Int, Int>>? = null

    @Volatile var lastFlipY: Int? = null

    private val optionsByContext = mutableMapOf<Long, MutableList<String>>()

    override fun mpv_create(): Pointer {
        // Engine creation is a stable hold point for lifecycle-race tests: it
        // runs after admission and outside `engineLock`, so a concurrent
        // release() can still take that monitor while a create is in flight.
        createStarted.countDown()
        allowCreate?.await(3L, TimeUnit.SECONDS)
        val address = createCount.incrementAndGet().toLong()
        synchronized(optionsByContext) {
            optionsByContext[address] = mutableListOf()
        }
        return Pointer(address)
    }

    override fun mpv_initialize(ctx: Pointer): Int {
        initializeCount.incrementAndGet()
        initializedOptions +=
            synchronized(optionsByContext) {
                optionsByContext.getValue(Pointer.nativeValue(ctx)).toList()
            }
        return 0
    }

    override fun mpv_terminate_destroy(ctx: Pointer) {
        terminateStarted.countDown()
        allowTerminate?.await(3L, TimeUnit.SECONDS)
        terminateCount.incrementAndGet()
    }

    override fun mpv_error_string(error: Int): Pointer? = null

    override fun mpv_set_option_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int {
        optionWrites += "$name=$data"
        synchronized(optionsByContext) {
            optionsByContext.getValue(Pointer.nativeValue(ctx)) += "$name=$data"
        }
        return 0
    }

    override fun mpv_set_property_string(
        ctx: Pointer,
        name: String,
        data: String,
    ): Int {
        propertyWrites += "$name=$data"
        return 0
    }

    override fun mpv_get_property(
        ctx: Pointer,
        name: String,
        format: Int,
        data: Pointer,
    ): Int = -1

    override fun mpv_get_property_string(
        ctx: Pointer,
        name: String,
    ): Pointer? = null

    override fun mpv_free(data: Pointer) = Unit

    override fun mpv_command(
        ctx: Pointer,
        args: Array<String?>,
    ): Int {
        commands += args.filterNotNull()
        return 0
    }

    override fun mpv_wait_event(
        ctx: Pointer,
        timeout: Double,
    ): Pointer? = null

    override fun mpv_render_context_create(
        res: PointerByReference,
        mpv: Pointer,
        params: Array<MpvRenderParam>,
    ): Int {
        val api =
            params
                .first { param -> param.type == LibMpv.RENDER_PARAM_API_TYPE }
                .data
                ?.getString(0)
                .orEmpty()
        renderApis += api
        openGlInitParamSeen.set(
            openGlInitParamSeen.get() ||
                params.any { param -> param.type == LibMpv.RENDER_PARAM_OPENGL_INIT_PARAMS },
        )
        if (
            (api == OPENGL_RENDER_API && failOpenGlRenderContext) ||
            (api == SW_RENDER_API && failSoftwareRenderContext)
        ) {
            return -1
        }
        res.value = Pointer(10_000L + Pointer.nativeValue(mpv))
        return 0
    }

    override fun mpv_render_context_render(
        ctx: Pointer,
        params: Array<MpvRenderParam>,
    ): Int {
        renderCount.incrementAndGet()
        params
            .firstOrNull { param -> param.type == LibMpv.RENDER_PARAM_OPENGL_FBO }
            ?.data
            ?.let { fbo ->
                lastFramebuffer =
                    fbo.getInt(0) to
                    (fbo.getInt(Int.SIZE_BYTES.toLong()) to fbo.getInt((Int.SIZE_BYTES * 2).toLong()))
            }
        params
            .firstOrNull { param -> param.type == LibMpv.RENDER_PARAM_FLIP_Y }
            ?.data
            ?.let { flipY -> lastFlipY = flipY.getInt(0) }
        return 0
    }

    override fun mpv_render_context_set_update_callback(
        ctx: Pointer,
        callback: LibMpv.MpvRenderUpdateFn?,
        callbackCtx: Pointer?,
    ) = Unit

    override fun mpv_render_context_update(ctx: Pointer): Long = LibMpv.RENDER_UPDATE_FRAME

    override fun mpv_render_context_free(ctx: Pointer) {
        renderContextFreeCount.incrementAndGet()
    }
}

private fun withOpenGlController(
    lib: OpenGlPresentationLibMpv,
    block: (MpvPlayerController) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller =
        MpvPlayerController(
            session = openGlSession,
            stateScope = scope,
            mpv = lib,
            presentationPreference = MpvPresentationPreference.MacOsOpenGl,
        )
    try {
        block(controller)
    } finally {
        controller.release()
        if (lib.createCount.get() > 0) {
            waitUntil { lib.terminateCount.get() == lib.createCount.get() }
        }
        scope.cancel()
    }
}

private data class HardwareDecodeControllerCase(
    val name: String,
    val osName: String,
    val surfaceKind: MpvOpenGlSurfaceKind,
    val expectedOptionValue: String,
)

private val systemPropertyLock = Any()

private fun <T> withSystemProperties(
    properties: Map<String, String?>,
    block: () -> T,
): T =
    synchronized(systemPropertyLock) {
        val previousValues = properties.keys.associateWith { key -> System.getProperty(key) }
        try {
            properties.forEach { (key, value) ->
                if (value == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, value)
                }
            }
            block()
        } finally {
            previousValues.forEach { (key, value) ->
                if (value == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, value)
                }
            }
        }
    }

private val openGlPlaybackPlan =
    PlaybackPlan(
        itemId = "8k-item",
        mediaSourceId = "8k-source",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/8k-item/stream",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
    )

private val staleAudioSelection =
    AudioActivationTarget(
        requestId = 41L,
        itemId = "stale",
        streamIndex = 3,
    ).let { target ->
        EmbeddedAudioSelection(
            target = target,
            descriptor =
                PlannedEmbeddedTrack(
                    jellyfinStreamIndex = target.streamIndex,
                    filteredContainerOrdinal = 0,
                    codec = "aac",
                    normalizedLanguage = "eng",
                    label = "Stale English",
                ),
        )
    }

private val openGlSession =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home",
        userId = "user-1",
        userName = "User",
        accessToken = "token",
        deviceId = "device-1",
    )

private fun waitUntil(
    timeoutSeconds: Long = 3L,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for OpenGL mpv state" }
        Thread.sleep(20L)
    }
}
