package com.pulsevoicephone

import org.junit.Assert.*
import org.junit.Test

class DiagnosticBufferTest {
    @Test fun retainsOnlyLast100Events() {
        var log = ""
        repeat(140) { log = DiagnosticBuffer.append(log, "event-$it") }
        assertEquals(100, log.lines().size)
        assertEquals("event-40", log.lines().first())
        assertEquals("event-139", log.lines().last())
    }

    @Test fun multiLineEventCannotInjectExtraEntriesAndLengthIsBounded() {
        val log = DiagnosticBuffer.append("", "first\nsecond\rthird" + "x".repeat(2000))
        assertEquals(1, log.lines().size)
        assertTrue(log.startsWith("first second third"))
        assertEquals(1200, log.length)
    }
}
