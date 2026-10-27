package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vaczi.myiohome.data.net.PollBackoff

class PollBackoffTest {

    @Test
    fun backoffSequenceDoublesAndCaps() {
        val secs = (1..10).map { PollBackoff.backoffMs(it) / 1_000 }
        assertEquals(listOf(5L, 10, 20, 40, 80, 160, 300, 300, 300, 300), secs)
        assertEquals(300L, PollBackoff.backoffMs(1_000) / 1_000)
    }

    @Test
    fun offlineAfterThreeFailures() {
        assertTrue(!PollBackoff.isOffline(2))
        assertTrue(PollBackoff.isOffline(3))
    }

    @Test
    fun retryAfterSecondsParsedAndCapped() {
        assertEquals(7_000L, PollBackoff.parseRetryAfterMs("7"))
        assertEquals(7_000L, PollBackoff.parseRetryAfterMs(" 7 "))
        assertEquals(300_000L, PollBackoff.parseRetryAfterMs("99999"))
        assertNull(PollBackoff.parseRetryAfterMs(null))
        assertNull(PollBackoff.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(PollBackoff.parseRetryAfterMs("-3"))
    }

    @Test
    fun retryAfterWinsOverBackoff() {
        assertEquals(12_000L, PollBackoff.nextDelayMs(1, 12_000L))
        assertEquals(5_000L, PollBackoff.nextDelayMs(1, null))
    }

    @Test
    fun chunkJoinsWithAmpersand() {
        assertEquals(listOf("a=1&b=2"), PollBackoff.chunk(listOf("a=1", "b=2")))
        assertEquals(emptyList<String>(), PollBackoff.chunk(emptyList()))
    }

    @Test
    fun chunkNeverSplitsAFragmentAndRespectsLimit() {
        val frags = List(30) { "PCA_ON=${2001 + it}" } // 12 chars each
        val chunks = PollBackoff.chunk(frags)
        assertTrue(chunks.all { it.length <= 200 })
        assertEquals(frags, chunks.flatMap { it.split('&') })
        assertTrue(chunks.size > 1)
    }

    @Test
    fun chunkBoundaryIsExact() {
        val a = "x".repeat(99)
        val b = "y".repeat(100) // 99 + 1 + 100 = 200 → fits
        assertEquals(listOf("$a&$b"), PollBackoff.chunk(listOf(a, b)))
        val c = "y".repeat(101) // 201 → split
        assertEquals(listOf(a, c), PollBackoff.chunk(listOf(a, c)))
    }

    @Test
    fun oversizedFragmentGoesAlone() {
        val big = "z".repeat(300)
        assertEquals(listOf("a", big, "b"), PollBackoff.chunk(listOf("a", big, "b")))
    }
}
