package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.CoverOutputType
import org.vaczi.myiohome.data.model.CoverState
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.ui.CoverMove
import org.vaczi.myiohome.ui.GroupControl
import org.vaczi.myiohome.ui.GroupControl.withLevel
import org.vaczi.myiohome.ui.GroupControl.withOnOff
import org.vaczi.myiohome.ui.groupMembers

/** Member selection, command fragments and optimistic state of zone group control. */
class GroupControlTest {

    private val cover = Cover("Nappali", upId = 2010, downId = 2011, type = CoverOutputType.PCA, state = CoverState.STOPPED, writable = true)
    private val lockedCover = cover.copy(name = "Zárt", upId = 2020, downId = 2021, writable = false, key = "C2020")

    private val state = ServerState(
        relays = listOf(
            RelayOutput(1, "Relé", on = false),
            RelayOutput(2, "Termosztátos", on = false, sensorId = 5),
            RelayOutput(3, "Zárolt", on = false, writable = false),
        ),
        pcas = listOf(
            PcaOutput(2001, "Lámpa", state = 0, turnOn = 200),
            PcaOutput(2002, "Dimm", state = 0, isPwm = true, turnOn = 255),
            PcaOutput(2010, "Redőny fel", state = 0),
            PcaOutput(2011, "Redőny le", state = 0),
            PcaOutput(2012, "Fűtött", state = 0, isPwm = true, sensorId = 3),
        ),
        pwms = listOf(
            PwmOutput(101, "Szalag", state = 0, turnOn = 128),
            PwmOutput(102, "Zárolt szalag", state = 0, writable = false),
        ),
    )

    private val allKeys = setOf("R1", "R2", "R3", "P2001", "P2002", "P2010", "P2011", "P2012", "F101", "F102", cover.outputKey(), lockedCover.outputKey())

    @Test
    fun eligibilityExcludesLockedThermostatAndCoverEndpoints() {
        val m = groupMembers(state, listOf(cover, lockedCover), allKeys)
        assertEquals(listOf(1), m.relays.map { it.id })
        assertEquals(listOf(2001, 2002), m.pcas.map { it.id })
        assertEquals(listOf(101), m.pwms.map { it.id })
        assertEquals(listOf(cover), m.covers)
        assertEquals(2, m.dimCount)
        assertEquals(4, m.switchCount)
    }

    @Test
    fun onlyZoneKeysAreSelected() {
        val m = groupMembers(state, emptyList(), setOf("R1", "F101", "P9999"))
        assertEquals(listOf(1), m.relays.map { it.id })
        assertTrue(m.pcas.isEmpty())
        assertEquals(listOf(101), m.pwms.map { it.id })
    }

    @Test
    fun onOffFragmentsUseSingleToggleBuilders() {
        val m = groupMembers(state, emptyList(), setOf("R1", "P2001", "F101"))
        assertEquals(listOf("r_ON=1", "PCA_ON=2001", "f_ON=1"), GroupControl.onOffFragments(m, true))
        assertEquals(listOf("r_OFF=1", "PCA_OFF=2001", "f_OFF=1"), GroupControl.onOffFragments(m, false))
    }

    @Test
    fun optimisticOnUsesConfiguredLevels() {
        val m = groupMembers(state, emptyList(), setOf("R1", "P2001", "F101"))
        val on = state.withOnOff(m, true)
        assertTrue(on.relays.first { it.id == 1 }.on)
        assertEquals(200, on.pcas.first { it.id == 2001 }.state)
        assertEquals(128, on.pwms.first { it.id == 101 }.state)
        // untouched members stay as they were
        assertEquals(0, on.pcas.first { it.id == 2002 }.state)
        val off = on.withOnOff(m, false)
        assertEquals(0, off.pcas.first { it.id == 2001 }.state)
    }

    @Test
    fun levelFragmentsCoverDimmableMembersOnly() {
        val m = groupMembers(state, emptyList(), setOf("R1", "P2001", "P2002", "F101"))
        assertEquals(listOf("PCA*2002=100", "fet*1=100"), GroupControl.levelFragments(m, 100))
        val st = state.withLevel(m, 100)
        assertEquals(100, st.pcas.first { it.id == 2002 }.state)
        assertEquals(100, st.pwms.first { it.id == 101 }.state)
        assertEquals(0, st.pcas.first { it.id == 2001 }.state)
    }

    @Test
    fun coverFragmentsPerMove() {
        assertEquals(listOf("PCA_OFF=2011&PCA_ON=2010"), GroupControl.coverFragments(listOf(cover), CoverMove.OPEN))
        assertEquals(listOf("PCA_OFF=2010&PCA_ON=2011"), GroupControl.coverFragments(listOf(cover), CoverMove.CLOSE))
        assertEquals(listOf("PCA_OFF=2010&PCA_OFF=2011"), GroupControl.coverFragments(listOf(cover), CoverMove.STOP))
    }
}
