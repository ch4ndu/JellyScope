// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.DownloadArtifactPartKey
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.OfflineArtifactRef
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.StreamMode
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

/** Physical identity restricted to the trusted resolver and deletion boundary. */
internal data class OfflineArtifactLeaseIdentity(
    val downloadId: DownloadId,
    val attemptGeneration: Long,
) {
    constructor(reference: OfflineArtifactRef) : this(reference.downloadId, reference.attemptGeneration)

    init {
        require(attemptGeneration >= 0L) { "Attempt generation must not be negative." }
    }

    override fun toString(): String = "OfflineArtifactLeaseIdentity"
}

/** Root-contained player resource whose path never reaches UI, persistence, or diagnostics. */
internal class TrustedOfflineArtifactResource(
    val partKey: DownloadArtifactPartKey,
    private val localPath: String,
) {
    init {
        require(localPath.isNotBlank() && '\u0000' !in localPath) { "Trusted artifact path must be usable." }
    }

    fun pathForPlatformPlayer(): String = localPath

    override fun toString(): String = "TrustedOfflineArtifactResource"
}

internal enum class OfflineArtifactResolutionFailure {
    UnauthorizedOrMissing,
    NotCompleted,
    StaleGeneration,
    UnsupportedArtifact,
    MissingArtifact,
    CorruptArtifact,
    DeletionInProgress,
}

internal sealed interface OfflineArtifactResolution {
    data class Available(
        val lease: OfflineArtifactLease,
    ) : OfflineArtifactResolution

    data class Unavailable(
        val failure: OfflineArtifactResolutionFailure,
    ) : OfflineArtifactResolution
}

/**
 * Revalidates account, completion, generation, containment, and package integrity
 * before leasing an offline artifact. An opaque route reference grants no authority.
 */
internal interface OfflineArtifactResolver {
    suspend fun acquire(
        expectedAccountIdentity: AccountIdentity,
        reference: OfflineArtifactRef,
    ): OfflineArtifactResolution
}

/** Shared offline admission before any platform-native resource handling. */
internal suspend fun OfflineArtifactResolver.acquireForOfflinePlan(
    plan: PlaybackPlan,
    expectedAccountIdentity: AccountIdentity,
): OfflineArtifactResolution {
    if (plan.streamMode != StreamMode.Offline) {
        return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.UnsupportedArtifact)
    }
    when (plan.offlineArtifactKind) {
        DownloadArtifactKind.OriginalFile,
        DownloadArtifactKind.LocalHlsPackage,
        -> Unit
        null -> return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.UnsupportedArtifact)
    }
    val reference =
        plan.offlineArtifactRef
            ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.UnauthorizedOrMissing)
    if (plan.offlineAccountIdentity != expectedAccountIdentity) {
        return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.UnauthorizedOrMissing)
    }
    return acquire(expectedAccountIdentity, reference)
}

class OfflineArtifactLease internal constructor(
    val artifactKind: DownloadArtifactKind,
    private val mainResource: TrustedOfflineArtifactResource,
    private val sidecarResources: List<TrustedOfflineArtifactResource>,
    private val releaseLease: () -> Unit,
) {
    internal val mainPathForController: String
        get() = mainResource.pathForPlatformPlayer()

    internal val sidecarPathsForController: List<String>
        get() = sidecarResources.map { resource -> resource.pathForPlatformPlayer() }

    init {
        val resourceKeys = listOf(mainResource.partKey) + sidecarResources.map { resource -> resource.partKey }
        require(resourceKeys.distinct().size == resourceKeys.size) { "Offline artifact resources must be unique." }
    }

    private val released = atomic(false)

    fun release() {
        if (released.compareAndSet(false, true)) {
            releaseLease()
        }
    }

    override fun toString(): String = "OfflineArtifactLease"
}

/** Holds the generation-bound lease so stale prepares cannot release a replacement's lease. */
internal class OfflineArtifactLeaseHolder {
    private val lock = ReentrantLock()
    private var lease: OfflineArtifactLease? = null

    fun replace(next: OfflineArtifactLease?) {
        val previous =
            lock.withLock {
                val old = lease
                lease = next
                old
            }
        previous?.release()
    }

    fun release() {
        replace(null)
    }

    /** Detaches without release so asynchronous native teardown can retain the old lease. */
    fun detach(): OfflineArtifactLease? =
        lock.withLock {
            val detached = lease
            lease = null
            detached
        }

    val current: OfflineArtifactLease?
        get() = lock.withLock { lease }
}

internal sealed interface OfflineArtifactDeletionGuardResult<out T> {
    data object ArtifactInUse : OfflineArtifactDeletionGuardResult<Nothing>

    data class Granted<T>(
        val value: T,
    ) : OfflineArtifactDeletionGuardResult<T>
}

/**
 * Prevents playback admission after deletion starts without holding the registry
 * lock across suspending deletion work.
 */
internal class OfflineArtifactLeaseRegistry {
    private val lock = ReentrantLock()
    private val activeLeaseCounts = mutableMapOf<OfflineArtifactLeaseIdentity, Int>()
    private val deleting = mutableSetOf<OfflineArtifactLeaseIdentity>()

    internal fun acquire(
        identity: OfflineArtifactLeaseIdentity,
        artifactKind: DownloadArtifactKind,
        mainResource: TrustedOfflineArtifactResource,
        sidecarResources: List<TrustedOfflineArtifactResource> = emptyList(),
    ): OfflineArtifactLease? =
        lock.withLock {
            if (identity in deleting) return@withLock null
            val currentCount = activeLeaseCounts.getOrElse(identity) { 0 }
            check(currentCount < Int.MAX_VALUE) { "Offline artifact lease count exceeded the supported range." }
            val lease =
                OfflineArtifactLease(
                    artifactKind = artifactKind,
                    mainResource = mainResource,
                    sidecarResources = sidecarResources.toList(),
                    releaseLease = { release(identity) },
                )
            activeLeaseCounts[identity] = currentCount + 1
            lease
        }

    fun isLeased(identity: OfflineArtifactLeaseIdentity): Boolean = lock.withLock { activeLeaseCounts.getOrElse(identity) { 0 } > 0 }

    suspend fun <T> withDeletionGuard(
        identity: OfflineArtifactLeaseIdentity,
        block: suspend () -> T,
    ): OfflineArtifactDeletionGuardResult<T> = withDeletionGuard(setOf(identity), block)

    /** Atomically guards a whole account-removal set before any destructive work begins. */
    suspend fun <T> withDeletionGuard(
        identities: Set<OfflineArtifactLeaseIdentity>,
        block: suspend () -> T,
    ): OfflineArtifactDeletionGuardResult<T> {
        val guardedIdentities = identities.toSet()
        require(guardedIdentities.isNotEmpty()) { "At least one artifact identity is required." }
        val admitted =
            lock.withLock {
                if (guardedIdentities.any { identity ->
                        identity in deleting || activeLeaseCounts.getOrElse(identity) { 0 } > 0
                    }
                ) {
                    false
                } else {
                    deleting += guardedIdentities
                    true
                }
            }
        if (!admitted) return OfflineArtifactDeletionGuardResult.ArtifactInUse

        return try {
            OfflineArtifactDeletionGuardResult.Granted(block())
        } finally {
            lock.withLock {
                deleting -= guardedIdentities
            }
        }
    }

    private fun release(identity: OfflineArtifactLeaseIdentity) {
        lock.withLock {
            val current = activeLeaseCounts[identity] ?: return
            if (current == 1) {
                activeLeaseCounts.remove(identity)
            } else {
                activeLeaseCounts[identity] = current - 1
            }
        }
    }
}
