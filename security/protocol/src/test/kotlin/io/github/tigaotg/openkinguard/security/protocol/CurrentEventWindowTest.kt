package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.*
import org.junit.Test

class CurrentEventWindowTest {
    @Test fun `one active per device while preserving the input history`() {
        val all = listOf(
            EventSlot("oldA", "A", 100L),
            EventSlot("oldB", "B", 110L, closed = true),
            EventSlot("newA", "A", 120L),
            EventSlot("newB", "B", 130L),
            EventSlot("archived", "C", 140L, supersededBy = "newC"),
        )
        assertEquals(setOf("newA", "newB"), CurrentEventWindow.currentIds(all))
        assertEquals(5, all.size)
    }

    @Test fun `stale signed event cannot supersede a newer current one`() {
        assertFalse(CurrentEventWindow.arrivesAfter(200L, 300L))
        assertFalse(CurrentEventWindow.arrivesAfter(300L, 300L))
        assertTrue(CurrentEventWindow.arrivesAfter(301L, 300L))
        assertTrue(CurrentEventWindow.arrivesAfter(301L, 0L))
    }

    @Test fun `closed or explicitly superseded event is never current`() {
        assertTrue(CurrentEventWindow.currentIds(listOf(
            EventSlot("closed", "A", 300L, closed = true),
            EventSlot("old", "B", 301L, supersededBy = "new"),
        )).isEmpty())
    }
}
