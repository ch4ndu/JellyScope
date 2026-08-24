// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

internal class LocalSubtitleStorageReconciler(
    private val coordinator: LocalSubtitleMutationCoordinator,
) {
    internal suspend fun reconcile() = coordinator.reconcileStorage()
}
