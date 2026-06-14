package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.vaczi.myiohome.data.local.AppTheme
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.SettingsBackup
import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments
import org.vaczi.myiohome.data.model.CoverOverride

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
    fun exportSubstitutesShareCredentialsAndDropsThem() {
        val servers = listOf(
            // Has a share account → exported copy logs in as it; share-cred fields are stripped.
            MyioServer(name = "Fűtés", host = "10.0.0.1", user = "admin", password = "admin", shareUser = "family", sharePassword = "example123"),
            // No share account → own credentials shared unchanged.
            MyioServer(name = "Világítás", host = "10.0.0.2", user = "admin", password = "admin"),
        )

        val decoded = SettingsBackup.decode(SettingsBackup.encode(AppTheme.GREEN, servers, emptyList(), emptyMap()))!!

        val futes = decoded.servers[0]
        assertEquals("family", futes.user)
        assertEquals("example123", futes.password)
        assertNull(futes.shareUser)
        assertNull(futes.sharePassword)

        val vilagitas = decoded.servers[1]
        assertEquals("admin", vilagitas.user)
        assertEquals("admin", vilagitas.password)
        assertNull(vilagitas.shareUser)
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
    fun roundTripsCoverOverrides() {
        val servers = listOf(MyioServer(name = "Redőny", host = "10.0.0.3"))
        val coverOverrides = mapOf(
            servers[0].id to mapOf(
                "C2001" to CoverOverride(swapped = true),
                "C2005" to CoverOverride(name = "Konyha redőny", disabled = true),
            ),
        )
        val blob = SettingsBackup.encode(AppTheme.GREEN, servers, emptyList(), emptyMap(), coverOverrides)
        val decoded = SettingsBackup.decode(blob)!!
        assertEquals(coverOverrides, decoded.coverOverrides)
    }

    @Test
    fun olderBackupWithoutCoverOverridesDecodesEmpty() {
        // A v1-style blob (no coverOverrides key) must still decode, with an empty map.
        val blob = SettingsBackup.encode(AppTheme.GREEN, emptyList(), emptyList(), emptyMap())
        assertEquals(emptyMap<String, Map<String, CoverOverride>>(), SettingsBackup.decode(blob)!!.coverOverrides)
    }

    @Test
    fun emptyConfigRoundTrips() {
        val blob = SettingsBackup.encode(AppTheme.GREEN, emptyList(), emptyList(), emptyMap())
        val decoded = SettingsBackup.decode(blob)!!
        assertEquals(AppTheme.GREEN, decoded.theme)
        assertEquals(emptyList<MyioServer>(), decoded.servers)
    }
}
