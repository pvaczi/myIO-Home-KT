package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vaczi.myiohome.data.local.Scene
import org.vaczi.myiohome.data.local.SceneEntry
import org.vaczi.myiohome.data.local.SceneStore
import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments
import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.CoverOutputType
import org.vaczi.myiohome.data.model.CoverState
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.outputKey
import org.vaczi.myiohome.ui.SceneCapture
import org.vaczi.myiohome.ui.SceneControl
import org.vaczi.myiohome.ui.SceneOutcome
import org.vaczi.myiohome.ui.SceneServer

/** Scene capture selection, run plan (fragments + optimistic state), summary wording and persistence. */
class SceneControlTest {

    private val cover = Cover("Nappali", upId = 2010, downId = 2011, type = CoverOutputType.PCA, state = CoverState.STOPPED, writable = true)

    private val state = ServerState(
        relays = listOf(
            RelayOutput(1, "Be", on = true),
            RelayOutput(2, "Ki", on = false),
            RelayOutput(3, "Termosztátos", on = true, sensorId = 5),
            RelayOutput(4, "Zárolt", on = true, writable = false),
            RelayOutput(5, "Rejtett", on = true),
            RelayOutput(6, "Másik be", on = true),
        ),
        pcas = listOf(
            PcaOutput(2001, "Lámpa", state = 255, turnOn = 200),
            PcaOutput(2002, "Dimm", state = 90, isPwm = true),
            PcaOutput(2003, "Dimm ki", state = 0, isPwm = true),
            PcaOutput(2010, "Redőny fel", state = 255),
            PcaOutput(2011, "Redőny le", state = 0),
        ),
        pwms = listOf(PwmOutput(101, "Szalag", state = 40)),
    )
    private val allKeys = (state.relays.map { it.outputKey() } + state.pcas.map { it.outputKey() } + state.pwms.map { it.outputKey() }).toSet()
    private val a = SceneServer("a", "Fűtés", state, listOf(cover), hiddenKeys = setOf("R5"), allowedKeys = allKeys)

    @Test
    fun captureTakesOnlyEligibleOnOutputs() {
        val c = SceneControl.capture(listOf(a, SceneServer("off", "Redőny", state, connected = false)))
        assertEquals(
            listOf(
                SceneEntry("a", "R1", 1), SceneEntry("a", "R6", 1),
                SceneEntry("a", "P2001", 255), SceneEntry("a", "P2002", 90),
                SceneEntry("a", "F101", 40),
            ),
            c.entries,
        )
        assertEquals(setOf("a"), c.serverIds)
    }

    @Test
    fun overwriteKeepsEntriesOfServersNotCaptured() {
        val old = listOf(SceneEntry("a", "R9", 1), SceneEntry("b", "R1", 1))
        val merged = SceneControl.mergeCapture(old, SceneCapture(listOf(SceneEntry("a", "R1", 1)), setOf("a")))
        assertEquals(listOf(SceneEntry("b", "R1", 1), SceneEntry("a", "R1", 1)), merged)
    }

    @Test
    fun defaultNameSkipsUsed() {
        assertEquals("Jelenet 1", SceneControl.defaultName(emptyList()))
        assertEquals("Jelenet 4", SceneControl.defaultName(listOf(Scene(name = "Jelenet 3"), Scene(name = "Jelenet 1"))))
    }

    @Test
    fun planBuildsFragmentsAndOptimisticState() {
        val scene = Scene(
            name = "Este",
            entries = listOf(
                SceneEntry("a", "R2", 1), SceneEntry("a", "P2001", 255), SceneEntry("a", "P2003", 128),
                SceneEntry("a", "F101", 77),
                SceneEntry("a", "R3", 1),     // thermostat-controlled → no longer eligible → missing
                SceneEntry("a", "R99", 1),    // gone → missing
                SceneEntry("gone", "R1", 1),  // unknown server → missing
            ),
        )
        val plan = SceneControl.plan(scene, listOf(a))
        assertEquals(1, plan.servers.size)
        assertEquals(
            listOf("r_ON=2", "PCA_ON=2001", "PCA*2003=128", "fet*1=77"),
            plan.servers[0].fragments,
        )
        assertEquals(3, plan.missing)
        val after = plan.servers[0].optimistic(state)
        assertTrue(after.relays.first { it.id == 2 }.on)
        assertEquals(200, after.pcas.first { it.id == 2001 }.state)
        assertEquals(128, after.pcas.first { it.id == 2003 }.state)
        assertEquals(77, after.pwms.first { it.id == 101 }.state)
        // Everything else untouched without turnOthersOff.
        assertTrue(after.relays.first { it.id == 1 }.on)
    }

    @Test
    fun turnOthersOffSwitchesOffOnlyOtherEligibleOnOutputs() {
        val scene = Scene(name = "Egy", entries = listOf(SceneEntry("a", "R1", 1)), turnOthersOff = true)
        val plan = SceneControl.plan(scene, listOf(a))
        // Others that are on and eligible: R6, P2001, P2002, F101 (R3 thermostat, R4 locked, R5 hidden, cover pair excluded).
        assertEquals(
            listOf("r_OFF=6", "PCA_OFF=2001", "PCA_OFF=2002", "f_OFF=1", "r_ON=1"),
            plan.servers[0].fragments,
        )
        val after = plan.servers[0].optimistic(state)
        assertTrue(!after.relays.first { it.id == 6 }.on)
        assertEquals(0, after.pcas.first { it.id == 2001 }.state)
        assertTrue(after.relays.first { it.id == 3 }.on)
        assertTrue(after.relays.first { it.id == 4 }.on)
        assertTrue(after.relays.first { it.id == 5 }.on)
        assertEquals(255, after.pcas.first { it.id == 2010 }.state)
    }

    @Test
    fun notConnectedServersAreSkippedAndNamed() {
        val b = SceneServer("b", "Redőny", state, connected = false)
        val scene = Scene(name = "Ketto", entries = listOf(SceneEntry("a", "R2", 1), SceneEntry("b", "R2", 1)))
        val plan = SceneControl.plan(scene, listOf(a, b))
        assertEquals(listOf("a"), plan.servers.map { it.serverId })
        assertEquals(listOf("Redőny"), plan.unavailable)
    }

    @Test
    fun summaryWording() {
        val b = SceneServer("b", "Redőny", state, connected = false)
        val c = SceneServer("c", "Fűtés2", state, allowedKeys = allKeys)
        val scene = Scene(name = "Este", entries = listOf(
            SceneEntry("a", "R2", 1), SceneEntry("c", "R2", 1), SceneEntry("b", "R2", 1), SceneEntry("a", "R99", 1),
        ))
        val plan = SceneControl.plan(scene, listOf(a, b, c))
        assertEquals(
            "„Este”: 2 szerver kész, kihagyva: Redőny (nem elérhető), 1 kimenet már nem létezik",
            SceneControl.summary("Este", plan, plan.servers.map { it.serverName to SceneOutcome.OK }),
        )
        assertEquals(
            "„Este”: 1 szerver kész, kihagyva: Redőny (nem elérhető), nem sikerült: Fűtés2, 1 kimenet már nem létezik",
            SceneControl.summary("Este", plan, listOf("Fűtés" to SceneOutcome.OK, "Fűtés2" to SceneOutcome.FAILED)),
        )
    }

    @Test
    fun captureExcludesOutputsNotAllowedInScenes() {
        val c = SceneControl.capture(listOf(a.copy(allowedKeys = setOf("R1", "P2002"))))
        assertEquals(listOf(SceneEntry("a", "R1", 1), SceneEntry("a", "P2002", 90)), c.entries)
        // Nothing marked → no candidate at all (the UI shows the hint instead of the checklist).
        assertEquals(emptyList<SceneEntry>(), SceneControl.capture(listOf(a.copy(allowedKeys = emptySet()))).entries)
    }

    @Test
    fun planSkipsAndCountsEntriesNotAllowed() {
        val server = a.copy(allowedKeys = allKeys - "R2")
        val scene = Scene(name = "Este", entries = listOf(SceneEntry("a", "R2", 1), SceneEntry("a", "P2001", 255)))
        val plan = SceneControl.plan(scene, listOf(server))
        assertEquals(listOf("PCA_ON=2001"), plan.servers[0].fragments)
        assertEquals(1, plan.notAllowed)
        assertEquals(0, plan.missing)
        assertEquals(
            "„Este”: 1 szerver kész, 1 kimenet nincs jelenetbe jelölve, kihagyva",
            SceneControl.summary("Este", plan, plan.servers.map { it.serverName to SceneOutcome.OK }),
        )
    }

    @Test
    fun turnOthersOffNeverTargetsOutputsNotAllowed() {
        val server = a.copy(allowedKeys = allKeys - setOf("R6", "P2002"))
        val scene = Scene(name = "Egy", entries = listOf(SceneEntry("a", "R1", 1)), turnOthersOff = true)
        val plan = SceneControl.plan(scene, listOf(server))
        // R6 (e.g. a circulation pump) and P2002 are on but not allowed → never switched off.
        assertEquals(listOf("PCA_OFF=2001", "f_OFF=1", "r_ON=1"), plan.servers[0].fragments)
        val after = plan.servers[0].optimistic(state)
        assertTrue(after.relays.first { it.id == 6 }.on)
        assertEquals(90, after.pcas.first { it.id == 2002 }.state)
    }

    @Test
    fun candidateGroupsFollowZoneOrderAndDisambiguateServers() {
        val b = SceneServer(
            "b", "Világítás",
            ServerState(relays = listOf(RelayOutput(1, "Be", on = true), RelayOutput(2, "Egyedi", on = true))),
            allowedKeys = setOf("R1", "R2"),
        )
        val capture = SceneControl.capture(listOf(a, b))
        val zones = listOf(Zone(id = "z1", name = "Nappali"), Zone(id = "z2", name = "Konyha"))
        val assignments = mapOf(
            "a" to ZoneAssignments(mapOf("R1" to listOf("z2", "z1"), "P2001" to listOf("z2"), "R6" to listOf("z1"))),
            "b" to ZoneAssignments(mapOf("R2" to listOf("z1"), "R1" to listOf("deleted-zone"))),
        )
        val zoneOrder = mapOf("z1" to listOf("b|R2", "a|R6"))
        val groups = SceneControl.candidateGroups(capture, listOf(a, b), zones, assignments, zoneOrder)

        assertEquals(listOf("Nappali", "Konyha", "Zóna nélkül"), groups.map { it.title })
        assertEquals(listOf(null), groups.filter { it.title == "Zóna nélkül" }.map { it.zoneId })
        // R1 is in z2 and z1 → listed once, under z1 (first in the global zone order); saved member order first.
        assertEquals(listOf("b|R2", "a|R6", "a|R1"), groups[0].items.map { it.ref })
        assertEquals(listOf("Egyedi", "Másik be", "Fűtés · Be"), groups[0].items.map { it.label })
        assertEquals(listOf("Lámpa"), groups[1].items.map { it.label })
        // Unknown zone id → unzoned; capture order kept. "Be" exists on both servers → server prefix.
        assertEquals(listOf("Dimm", "Szalag", "Világítás · Be"), groups[2].items.map { it.label })

        val all = SceneControl.defaultSelection(groups)
        assertEquals(capture.entries.map { "${it.serverId}|${it.outputKey}" }.toSet(), all)
        val withoutZ1 = SceneControl.toggleGroup(all, groups[0])
        assertEquals(all - setOf("b|R2", "a|R6", "a|R1"), withoutZ1)
        assertEquals(all, SceneControl.toggleGroup(withoutZ1 + "a|R6", groups[0])) // partial → whole group on
        val chosen = SceneControl.selectedCapture(capture, withoutZ1)
        assertEquals(4, chosen.entries.size)
        assertEquals(setOf("a", "b"), chosen.serverIds)
    }

    @Test
    fun scenesRoundTripAndCorruptBlobIsEmpty() {
        val scenes = listOf(Scene(id = "x", name = "Este", entries = listOf(SceneEntry("a", "R1", 1)), turnOthersOff = true))
        assertEquals(scenes, SceneStore.parseScenes(SceneStore.encodeScenes(scenes)))
        assertEquals(emptyList<Scene>(), SceneStore.parseScenes(null))
        assertEquals(emptyList<Scene>(), SceneStore.parseScenes("{nope"))
    }
}
