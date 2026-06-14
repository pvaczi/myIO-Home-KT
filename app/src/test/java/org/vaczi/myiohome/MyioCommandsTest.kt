package org.vaczi.myiohome

import org.vaczi.myiohome.data.model.CoverOutputType
import org.vaczi.myiohome.data.net.MyioCommands
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the exact wire format of control commands (see `MyIO/AGENTS.md`). */
class MyioCommandsTest {

    @Test
    fun relayOnOff() {
        assertEquals("r_ON=3", MyioCommands.relay(3, on = true))
        assertEquals("r_OFF=3", MyioCommands.relay(3, on = false))
    }

    @Test
    fun pcaUsesRealDeviceId() {
        assertEquals("PCA_ON=2005", MyioCommands.pcaOnOff(2005, on = true))
        assertEquals("PCA_OFF=2005", MyioCommands.pcaOnOff(2005, on = false))
        assertEquals("PCA*2005=128", MyioCommands.pcaBrightness(2005, 128))
    }

    @Test
    fun fetMapsDomainIdToOneBasedIndex() {
        // domain id 101 → wire index 1
        assertEquals(1, MyioCommands.fetIndex(101))
        assertEquals("f_ON=1", MyioCommands.fetOnOff(101, on = true))
        assertEquals("f_OFF=12", MyioCommands.fetOnOff(112, on = false))
        assertEquals("fet*1=200", MyioCommands.fetBrightness(101, 200))
    }

    @Test
    fun brightnessIsClampedToByteRange() {
        assertEquals("PCA*2005=255", MyioCommands.pcaBrightness(2005, 999))
        assertEquals("fet*1=0", MyioCommands.fetBrightness(101, -5))
    }

    @Test
    fun thermostatSetpointsUseTenthsAndPerTypeIdSpace() {
        // Relay: documented min_temp_ON*x / max_temp_OFF*x, x = relay id directly.
        assertEquals("min_temp_ON*3=235", MyioCommands.relayThermostatOn(3, 235))
        assertEquals("max_temp_OFF*3=250", MyioCommands.relayThermostatOff(3, 250))
        // FET/PWM: mix_temp_MIN*x / mix_temp_MAX*x, x = id − 100.
        assertEquals("mix_temp_MIN*1=210", MyioCommands.fetThermostatOn(101, 210))
        assertEquals("mix_temp_MAX*12=260", MyioCommands.fetThermostatOff(112, 260))
        // PCA: PCA_temp_MIN*x / PCA_temp_MAX*x, x = id − 2000.
        assertEquals("PCA_temp_MIN*5=220", MyioCommands.pcaThermostatOn(2005, 220))
        assertEquals("PCA_temp_MAX*117=245", MyioCommands.pcaThermostatOff(2117, 245))
    }

    @Test
    fun coverDrivesOppositeOffThenWantedOn() {
        // PCA cover, up=2002 down=2001: OPEN stops down then drives up; CLOSE the reverse; STOP both off.
        assertEquals(listOf("PCA_OFF=2001", "PCA_ON=2002"), MyioCommands.coverOpen(CoverOutputType.PCA, 2002, 2001))
        assertEquals(listOf("PCA_OFF=2002", "PCA_ON=2001"), MyioCommands.coverClose(CoverOutputType.PCA, 2002, 2001))
        assertEquals(listOf("PCA_OFF=2002", "PCA_OFF=2001"), MyioCommands.coverStop(CoverOutputType.PCA, 2002, 2001))
        // Relay variant uses r_ON / r_OFF.
        assertEquals(listOf("r_OFF=4", "r_ON=3"), MyioCommands.coverOpen(CoverOutputType.RELAY, 3, 4))
    }
}
