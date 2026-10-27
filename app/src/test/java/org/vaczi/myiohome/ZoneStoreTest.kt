package org.vaczi.myiohome

import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments
import org.vaczi.myiohome.data.local.ZoneConfig
import org.vaczi.myiohome.data.local.ZoneStore
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.Sensor
import org.vaczi.myiohome.data.model.SensorKind
import org.vaczi.myiohome.data.model.outputKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ZoneStoreTest {

    @Test
    fun emptyOrCorruptBlobMeansEmptyZones() {
        assertEquals(emptyList<Zone>(), ZoneStore.parseZones(null))
        assertEquals(emptyList<Zone>(), ZoneStore.parseZones(""))
        assertEquals(emptyList<Zone>(), ZoneStore.parseZones("{not json"))
    }

    @Test
    fun emptyOrCorruptBlobMeansEmptyAssignments() {
        assertEquals(ZoneAssignments(), ZoneStore.parseAssignments(null))
        assertEquals(ZoneAssignments(), ZoneStore.parseAssignments(""))
        assertEquals(ZoneAssignments(), ZoneStore.parseAssignments("{not json"))
    }

    @Test
    fun parsesZonesWithAndWithoutIcon() {
        val zones = ZoneStore.parseZones("""[{"id":"a","name":"Old"},{"id":"b","name":"New","icon":"bedroom"}]""")
        assertEquals(null, zones[0].icon)
        assertEquals("bedroom", zones[1].icon)
    }

    @Test
    fun unknownIconKeyFallsBackToDefault() {
        assertTrue(org.vaczi.myiohome.ui.ZoneIcons.forKey("no_such_icon") === org.vaczi.myiohome.ui.ZoneIcons.default)
        assertTrue(org.vaczi.myiohome.ui.ZoneIcons.forKey(null) === org.vaczi.myiohome.ui.ZoneIcons.default)
        assertEquals("sauna", org.vaczi.myiohome.ui.ZoneIcons.forKey("sauna").key)
        val keys = org.vaczi.myiohome.ui.ZoneIcons.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun roundTripsGlobalZones() {
        val zones = listOf(Zone(id = "z1", name = "Nappali"), Zone(id = "z2", name = "Kert"))
        val blob = Json.encodeToString(ListSerializer(Zone.serializer()), zones)
        assertEquals(zones, ZoneStore.parseZones(blob))
    }

    @Test
    fun roundTripsAssignments() {
        val a = ZoneAssignments(
            assignments = mapOf("R3" to listOf("z1", "z2"), "P2005" to listOf("z2")),
            memberOrder = mapOf("z1" to listOf("P2005", "R3")),
        )
        val blob = Json.encodeToString(ZoneAssignments.serializer(), a)
        assertEquals(a, ZoneStore.parseAssignments(blob))
    }

    @Test
    fun orderedMembersAppliesSavedOrderAndAppendsTheRest() {
        val cfg = ZoneConfig(memberOrder = mapOf("z1" to listOf("R3", "R1")))
        // Saved keys come first in saved order; unseen keys keep their default order at the end.
        assertEquals(
            listOf("R3", "R1", "R2", "P5"),
            cfg.orderedMembers("z1", listOf("R1", "R2", "R3", "P5")),
        )
    }

    @Test
    fun orderedMembersWithoutSavedOrderKeepsDefault() {
        val cfg = ZoneConfig()
        assertEquals(listOf("R1", "R2"), cfg.orderedMembers("z1", listOf("R1", "R2")))
    }

    @Test
    fun orderedMembersIgnoresStaleKeysNoLongerInZone() {
        // "R9" was reordered earlier but is no longer a member → it must not appear.
        val cfg = ZoneConfig(memberOrder = mapOf("z1" to listOf("R9", "R2", "R1")))
        assertEquals(listOf("R2", "R1"), cfg.orderedMembers("z1", listOf("R1", "R2")))
    }

    @Test
    fun assignmentHelpers() {
        val cfg = ZoneConfig(assignments = mapOf("R3" to listOf("z1")))
        assertTrue(cfg.isAssigned("R3"))
        assertFalse(cfg.isAssigned("R4"))
        assertEquals(listOf("z1"), cfg.zonesOf("R3"))
        assertEquals(emptyList<String>(), cfg.zonesOf("R4"))
    }

    @Test
    fun outputKeysAreTypePrefixedAndDistinct() {
        assertEquals("R3", RelayOutput(id = 3, name = "x", on = false).outputKey())
        // A temperature sensor with id 3 must not collide with relay 3.
        val temp = Sensor(id = 3, name = "x", kind = SensorKind.TEMPERATURE, value = 21.0).outputKey()
        assertEquals("ST3", temp)
    }
}
