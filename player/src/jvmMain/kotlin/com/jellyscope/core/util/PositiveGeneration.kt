// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

/**
 * Returns the next positive process-local generation, wrapping the signed range to one.
 *
 * This value is not durable identity, cross-process identity, or a security token.
 */
fun nextPositiveGeneration(current: Long): Long {
    require(current >= 0L) { "Generation must be non-negative." }
    return if (current == Long.MAX_VALUE) 1L else current + 1L
}
