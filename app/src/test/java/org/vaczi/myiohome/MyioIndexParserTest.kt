package org.vaczi.myiohome

import org.vaczi.myiohome.data.net.MyioIndexParser
import org.vaczi.myiohome.data.net.Perm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Index-page parser tests against a real capture:
 *  - index-futes-family.html : 192.168.1.11 (Fűtés, fw 3.7.5.1) fetched as the restricted **family** user.
 *
 * That user can read most outputs but cannot write the thermostat relays, so the capture exercises all
 * three permission states (no-read, read-only, read+write) — the whole point of the feature.
 */
class MyioIndexParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing fixture: $name"
        }.bufferedReader().use { it.readText() }

    private val meta = MyioIndexParser.parse(fixture("index-futes-family.html"))

    @Test
    fun parsesNamesIncludingTheRealIds() {
        // relay_description[1] → relay id 1; PCA_description[3] → real PCA id 2003 (N + 2000).
        assertEquals("Nappali levegő tilt", meta.relayNames[1])
        assertEquals("Átrium alsó lámpa", meta.pcaNames[2003])
    }

    @Test
    fun headerWithoutFetArrayYieldsEmptyPwmMaps() {
        val m = MyioIndexParser.parse("<script>var relays=[111,100];\nvar PCA=[11255];</script>")
        assertTrue(m.pwmPerms.isEmpty())
        assertTrue(m.pwmTurnOn.isEmpty())
        assertTrue(m.pwmTurnOff.isEmpty())
        assertEquals(2, m.relayPerms.size)
        assertEquals(1, m.pcaPerms.size)
    }

    @Test
    fun decodesRelayPermissions() {
        // relays=[101,100,101,...,0(=6th),...]: 3-digit RWS.
        assertEquals(Perm(readable = true, writable = false), meta.relayPerms[1])  // 101: view, no control
        assertEquals(Perm(readable = true, writable = false), meta.relayPerms[2])  // 100
        assertEquals(Perm(readable = false, writable = false), meta.relayPerms[6]) // 0: no rights
    }

    @Test
    fun decodesPcaPermissions() {
        // PCA=[0,0,10255,11000,...]: index i → id 2001+i, 5-digit RWVVV.
        assertEquals(Perm(readable = false, writable = false), meta.pcaPerms[2001]) // 0
        assertEquals(Perm(readable = true, writable = false), meta.pcaPerms[2003])  // 10255: view only
        assertEquals(Perm(readable = true, writable = true), meta.pcaPerms[2004])   // 11000: full control
    }

    @Test
    fun readsOnOffLevels() {
        // PCAMAX=[255,...] / PCAMIN=[0,...] → turnOn / turnOff for id 2001+i.
        assertEquals(255, meta.pcaTurnOn[2003])
        assertEquals(0, meta.pcaTurnOff[2003])
    }

    @Test
    fun toleratesAbsentArrays() {
        // A blank document yields empty maps (no crash) so callers stay fail-open.
        val empty = MyioIndexParser.parse("<html></html>")
        assertTrue(empty.relayPerms.isEmpty())
        assertTrue(empty.pcaNames.isEmpty())
        assertNull(empty.relayPerms[1])
        assertFalse(empty.pcaTurnOn.containsKey(2001))
    }

    @Test
    fun nonNumericElementKeepsLaterIndicesAligned() {
        // A stray non-numeric element (e.g. a firmware glitch) must NOT shift the ids of the elements
        // after it: relays[]=[101, undefined, 100] → id 1=101, id 2=0 (no rights, fail-closed for that
        // slot only), id 3 must still decode 100 — not the value that would land there if the bad
        // element were dropped instead of zeroed.
        val html = "<script>var relays=[101, undefined, 100];</script>"
        val meta = MyioIndexParser.parse(html)
        assertEquals(Perm(readable = true, writable = false), meta.relayPerms[1])  // 101
        assertEquals(Perm(readable = false, writable = false), meta.relayPerms[2]) // undefined → 0
        assertEquals(Perm(readable = true, writable = false), meta.relayPerms[3])  // 100, correctly at id 3
    }

    @Test
    fun emptyArrayYieldsNoEntries() {
        // `var relays=[];` must not be treated as a single zero-valued element.
        val html = "<script>var relays=[];</script>"
        val meta = MyioIndexParser.parse(html)
        assertTrue(meta.relayPerms.isEmpty())
    }
}
