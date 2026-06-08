package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.vaczi.myiohome.data.local.AppTheme
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.SettingsBackup
import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments

class SettingsBackupTest {

    @Test
    fun roundTripsAllSettings() {
        val servers = listOf(
            MyioServer(name = "Fűtés", host = "10.0.0.1", pollIntervalSec = 5),
            MyioServer(name = "Világítás", host = "10.0.0.2"),
        )
        val zones = listOf(Zone(id = "z1", name = "Nappali"), Zone(id = "z2", name = "Konyha"))
        val assignments = mapOf(
            servers[0].id to ZoneAssignments(assignments = mapOf("R1" to listOf("z1"))),
            servers[1].id to ZoneAssignments(
                assignments = mapOf("P2001" to listOf("z1", "z2")),
                memberOrder = mapOf("z1" to listOf("P2001")),
            ),
        )

        val blob = SettingsBackup.encode(AppTheme.DYNAMIC, servers, zones, assignments)
        val decoded = SettingsBackup.decode(blob)!!

        assertEquals(AppTheme.DYNAMIC, decoded.theme)
        assertEquals(servers, decoded.servers)
        assertEquals(zones, decoded.zones)
        assertEquals(assignments, decoded.assignments)
    }

    @Test
    fun rejectsArbitraryJson() {
        // Valid JSON object but not ours (no magic) → null, even though every field has a default.
        assertNull(SettingsBackup.decode("""{"hello":"world"}"""))
        assertNull(SettingsBackup.decode("{}"))
        assertNull(SettingsBackup.decode("""{"format":"something-else"}"""))
    }

    @Test
    fun rejectsBlankOrCorrupt() {
        assertNull(SettingsBackup.decode(null))
        assertNull(SettingsBackup.decode(""))
        assertNull(SettingsBackup.decode("not json at all"))
        assertNull(SettingsBackup.decode("[1,2,3]"))
    }

    @Test
    fun emptyConfigRoundTrips() {
        val blob = SettingsBackup.encode(AppTheme.GREEN, emptyList(), emptyList(), emptyMap())
        val decoded = SettingsBackup.decode(blob)!!
        assertEquals(AppTheme.GREEN, decoded.theme)
        assertEquals(emptyList<MyioServer>(), decoded.servers)
    }
}
