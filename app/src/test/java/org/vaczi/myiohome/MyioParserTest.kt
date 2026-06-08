package org.vaczi.myiohome

import org.vaczi.myiohome.data.model.SensorKind
import org.vaczi.myiohome.data.net.MyioParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests against real captured responses:
 *  - *-futes.json  : 192.168.1.11 (Fűtés, fw 3.7.5.1, 16 relay / 32 PCA)
 *  - p_desc-vilagitas.xml : 192.168.1.13 (Világítás, fw 3.9.1) — PCA names incl. id > 2100
 */
class MyioParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing fixture: $name"
        }.bufferedReader().use { it.readText() }

    @Test
    fun parsesInfo() {
        val info = MyioParser.parseInfo(fixture("info-futes.json"))
        assertEquals("AB12-3456-7890-Xy00", info.serial)
        assertEquals("3.7.5.1", info.version)
        assertEquals(16, info.relayCount)
        assertEquals(32, info.pcaCount)
        assertEquals(3, info.api)
    }

    @Test
    fun parsesStateWithoutNames() {
        val state = MyioParser.parseState(fixture("sens_out-futes.json"))
        assertEquals(16, state.relays.size)
        assertEquals(32, state.pcas.size)
        assertEquals(12, state.pwms.size)

        val relay1 = state.relays.first { it.id == 1 }
        assertTrue(relay1.on)
        assertEquals(53, relay1.sensorId)
        assertEquals(230, relay1.sensorOnTenths)
        assertTrue(relay1.sensorControlled)

        val pca2003 = state.pcas.first { it.id == 2003 }
        assertEquals(255, pca2003.state)
        assertTrue(pca2003.on)

        // sensor id 64 (HMV tároló): temp field is value ×100 (e.g. 4387 → 43.87 °C in this fixture)
        val s64 = state.sensors.first { it.id == 64 }
        assertEquals(SensorKind.TEMPERATURE, s64.kind)
        assertEquals(43.87, s64.value, 0.001)

        // No names in sens_out.json
        assertEquals("", relay1.name)

        // Energy meter id 200 reports imp=0 (no meter connected) → dropped, like the bridge does.
        assertNull(state.sensors.firstOrNull { it.id == 200 })
    }

    @Test
    fun parsesStateWithNames() {
        val state = MyioParser.parseState(fixture("d_sens_out-futes.json"))
        val relay1 = state.relays.first { it.id == 1 }
        assertEquals("Nappali levegő tilt", relay1.name)
        assertNotNull(state.pcas.firstOrNull())
        assertTrue(state.relays.size >= 16)

        // Configured turn-on level (only present in d_sens_out): PWM 200, PCA 255 in this fixture.
        assertEquals(200, state.pwms.first { it.id == 101 }.turnOn)
        assertEquals(255, state.pcas.first { it.id == 2001 }.turnOn)
    }

    @Test
    fun parsesPcaDescWorkaround() {
        val names = MyioParser.parsePcaDesc(fixture("p_desc-vilagitas.xml"))
        // <1>Háló szoba</1> → real PCA id 2001
        assertEquals("Háló szoba", names[2001])
        // id > 2100 names that d_sens_out.json leaves empty (the firmware bug this works around)
        assertEquals("Konyha falfülke led", names[2101])
        assertEquals("Iroda led", names[2117])
        // blank entries are dropped
        assertFalse(names.containsKey(2097))
    }
}
