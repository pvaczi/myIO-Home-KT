package org.vaczi.myiohome

import org.vaczi.myiohome.data.model.SensorKind
import org.vaczi.myiohome.data.net.MyioParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-parser tests against a real captured response:
 *  - *-futes.json : 192.168.1.11 (Fűtés, fw 3.7.5.1, 16 relay / 32 PCA)
 *
 * Names + permissions + on/off levels now come from the index page (`GET /`), covered separately in
 * [MyioIndexParserTest]; `sens_out.json` here is the lightweight, name-less hot-path poll.
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
}
