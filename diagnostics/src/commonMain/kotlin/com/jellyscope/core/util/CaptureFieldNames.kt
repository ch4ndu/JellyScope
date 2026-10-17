// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

/** Store-owned capture envelope names. Producers can never supply or override them. */
internal val reservedCaptureFieldNames: Set<String> = setOf("diagEpoch", "diagSegment", "diagElapsedMs")
