package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vaczi.myiohome.ui.ThermostatMode
import org.vaczi.myiohome.ui.thermostatMode
import org.vaczi.myiohome.ui.thermostatModeLabel
import org.vaczi.myiohome.ui.thresholdsValid

class ThermostatModeTest {
    @Test fun modeFollowsThresholdOrder() {
        assertEquals(ThermostatMode.HEATING, thermostatMode(210, 230))
        assertEquals(ThermostatMode.COOLING, thermostatMode(260, 240))
        assertEquals(ThermostatMode.INVALID, thermostatMode(220, 220))
    }

    @Test fun equalThresholdsAreInvalid() {
        assertFalse(thresholdsValid(220, 220))
        assertTrue(thresholdsValid(220, 221))
        assertTrue(thresholdsValid(221, 220))
    }

    @Test fun labels() {
        assertEquals("Fűtés", thermostatModeLabel(210, 230, humidity = false))
        assertEquals("Hűtés", thermostatModeLabel(260, 240, humidity = false))
        assertEquals("Párásítás", thermostatModeLabel(400, 500, humidity = true))
        assertEquals("Párátlanítás", thermostatModeLabel(600, 500, humidity = true))
        assertEquals("—", thermostatModeLabel(220, 220, humidity = false))
    }

    @Test fun swappingOrderFlipsMode() {
        assertEquals(ThermostatMode.HEATING, thermostatMode(200, 250))
        assertEquals(ThermostatMode.COOLING, thermostatMode(250, 200))
    }
}
