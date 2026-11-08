package com.druk.lmplayground.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopSequenceFilterTest {

    /** Feeds [parts] as a growing reply and returns everything sent. */
    private fun run(filter: StopSequenceFilter, vararg parts: String): String {
        val sent = StringBuilder()
        var full = ""
        for (p in parts) {
            full += p
            sent.append(filter.update(full))
        }
        sent.append(filter.finish())
        return sent.toString()
    }

    @Test
    fun noStopsPassesEverythingThrough() {
        val f = StopSequenceFilter(emptyList())
        assertEquals("Hello world", run(f, "Hel", "lo ", "world"))
        assertFalse(f.stopped)
    }

    @Test
    fun cutsAtTheStopAndDropsIt() {
        val f = StopSequenceFilter(listOf("\nUser:"))
        assertEquals("Hi there", run(f, "Hi ", "there", "\nUs", "er: more"))
        assertTrue(f.stopped)
        assertEquals("Hi there", f.text)
    }

    @Test
    fun holdsBackAPossibleStartUntilItIsn't() {
        val f = StopSequenceFilter(listOf("END"))
        assertEquals("", f.update("ab"))
        assertEquals("a", f.update("abE"))
        assertEquals("bE", f.update("abExy"))
        assertEquals("xy", f.finish())
    }

    @Test
    fun earliestOfSeveralStopsWins() {
        val f = StopSequenceFilter(listOf("###", "\n\n"))
        assertEquals("one", run(f, "one\n\ntwo###"))
    }
}
