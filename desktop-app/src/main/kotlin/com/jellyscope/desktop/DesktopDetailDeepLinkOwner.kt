// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.desktop

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.ui.navigation.InitialDetailNavigationEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class DesktopDetailDeepLinkOwner(
    initialItemId: String? = null,
) {
    private var nextEventId = 1L
    private var activeAccountIdentity: AccountIdentity? = null
    private var activeBoundaryEpoch: Long? = null
    private val _event =
        MutableStateFlow(
            initialItemId
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { itemId -> InitialDetailNavigationEvent(eventId = 0L, itemId = itemId) },
        )
    val event: StateFlow<InitialDetailNavigationEvent?> = _event.asStateFlow()

    fun publish(itemId: String) {
        val normalized = itemId.trim().takeIf(String::isNotBlank) ?: return
        _event.value =
            InitialDetailNavigationEvent(
                eventId = nextEventId++,
                itemId = normalized,
                accountIdentity = activeAccountIdentity,
                boundaryEpoch = activeBoundaryEpoch,
            )
    }

    fun updateBoundary(
        accountIdentity: AccountIdentity?,
        boundaryEpoch: Long?,
    ) {
        require((accountIdentity == null) == (boundaryEpoch == null))
        val incomingBoundary =
            accountIdentity?.let { identity ->
                boundaryEpoch?.let { epoch -> identity to epoch }
            }
        val isFirstNonNullBoundary =
            activeAccountIdentity == null && activeBoundaryEpoch == null && incomingBoundary != null
        activeAccountIdentity = accountIdentity
        activeBoundaryEpoch = boundaryEpoch
        _event.update { event ->
            val scopedEvent =
                if (isFirstNonNullBoundary) {
                    event
                        ?.takeIf { pendingEvent -> pendingEvent.accountIdentity == null }
                        ?.let { pendingEvent ->
                            pendingEvent.copy(
                                accountIdentity = incomingBoundary.first,
                                boundaryEpoch = incomingBoundary.second,
                            )
                        }
                        ?: event
                } else {
                    event
                }
            val isEligibleBoundary =
                incomingBoundary != null &&
                    scopedEvent?.isEligibleFor(incomingBoundary.first, incomingBoundary.second) == true
            val isIneligible = scopedEvent?.accountIdentity != null && !isEligibleBoundary
            if (isIneligible) {
                null
            } else {
                scopedEvent
            }
        }
    }

    fun acknowledge(eventId: Long) {
        if (_event.value?.eventId == eventId) {
            _event.value = null
        }
    }
}
