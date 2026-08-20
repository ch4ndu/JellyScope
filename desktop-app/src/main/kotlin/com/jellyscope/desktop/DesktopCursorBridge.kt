// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.desktop

import java.awt.Component
import java.awt.Container
import java.awt.Cursor

internal fun Component.setCursorRecursively(cursor: Cursor) {
    this.cursor = cursor
    if (this is Container) {
        components.forEach { component -> component.setCursorRecursively(cursor) }
    }
}
