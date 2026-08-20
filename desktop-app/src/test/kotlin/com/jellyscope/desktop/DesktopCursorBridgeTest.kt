// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.desktop

import java.awt.Cursor
import java.awt.Panel
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCursorBridgeTest {
    @Test
    fun appliesCursorToNestedComposeComponentTree() {
        val leaf = Panel()
        val child = Panel().apply { add(leaf) }
        val root = Panel().apply { add(child) }
        val cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)

        root.setCursorRecursively(cursor)

        assertEquals(cursor, root.cursor)
        assertEquals(cursor, child.cursor)
        assertEquals(cursor, leaf.cursor)
    }

    @Test
    fun itemDetailDeepLinkAcceptsOnlyTheCanonicalSingleItemPath() {
        assertEquals(
            "1caf4b5cfc367bf0ec41c9b230ae10bf",
            parseDesktopItemDetailDeepLink(
                "jellyscope://details/1caf4b5cfc367bf0ec41c9b230ae10bf",
            ),
        )
        assertEquals(null, parseDesktopItemDetailDeepLink("https://details/item-1"))
        assertEquals(null, parseDesktopItemDetailDeepLink("jellyscope://player/item-1"))
        assertEquals(null, parseDesktopItemDetailDeepLink("jellyscope://details/one/two"))
        assertEquals(null, parseDesktopItemDetailDeepLink(null))
    }
}
