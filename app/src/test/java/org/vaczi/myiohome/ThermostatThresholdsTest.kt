package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Test
import org.vaczi.myiohome.ui.shiftedThresholds

/** The thermostat editor must never rewrite the device's thresholds by rounding. */
class ThermostatThresholdsTest {

    @Test
    fun zeroShiftKeepsAnOddBandExactly() {
        // 22.5 / 23.0 °C: midpoint and half-band are not whole tenths.
        assertEquals(225 to 230, shiftedThresholds(225, 230, 0, 0))
        assertEquals(200 to 201, shiftedThresholds(200, 201, 0, 0))
    }

    @Test
    fun heatingTargetShiftMovesBothAndHysteresisWidensOutward() {
        assertEquals(227 to 232, shiftedThresholds(225, 230, 2, 0))
        assertEquals(224 to 231, shiftedThresholds(225, 230, 0, 1))
    }

    @Test
    fun coolingKeepsItsDirection() {
        // Cooling: on above off. Widening raises on and lowers off.
        assertEquals(251 to 239, shiftedThresholds(250, 240, 0, 1))
        assertEquals(245 to 235, shiftedThresholds(250, 240, -5, 0))
    }
}
