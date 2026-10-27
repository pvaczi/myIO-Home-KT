package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.vaczi.myiohome.data.local.AppTheme
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.Scene
import org.vaczi.myiohome.data.local.SceneEntry
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
    fun roundTripKeepsZoneIcon() {
        val zones = listOf(Zone(id = "z1", name = "Nappali", icon = "living"), Zone(id = "z2", name = "Kert"))
        val decoded = SettingsBackup.decode(SettingsBackup.encode(AppTheme.DYNAMIC, emptyList(), zones, emptyMap()))!!
        assertEquals(zones, decoded.zones)
        assertEquals("living", decoded.zones[0].icon)
        assertNull(decoded.zones[1].icon)
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
    fun roundTripsScenes() {
        val scenes = listOf(
            Scene(id = "s1", name = "Este", entries = listOf(SceneEntry("a", "R1", 1), SceneEntry("b", "P2001", 128)), turnOthersOff = true),
        )
        val blob = SettingsBackup.encode(AppTheme.GREEN, emptyList(), emptyList(), emptyMap(), scenes = scenes)
        assertEquals(scenes, SettingsBackup.decode(blob)!!.scenes)
    }

    @Test
    fun oldFormatBackupWithoutScenesDecodesEmpty() {
        val old = """{"format":"myio-home-settings","version":1,"theme":"GREEN","servers":[],"zones":[],"assignments":{}}"""
        assertEquals(emptyList<Scene>(), SettingsBackup.decode(old)!!.scenes)
    }

    @Test
    fun roundTripsGlobalZoneOrder() {
        val order = mapOf("z1" to listOf("s1|R1", "s2|C2001", "s1|P2005"))
        val blob = SettingsBackup.encode(AppTheme.GREEN, emptyList(), emptyList(), emptyMap(), zoneOrder = order)
        assertEquals(order, SettingsBackup.decode(blob)!!.zoneOrder)
    }

    @Test
    fun oldBackupWithoutZoneOrderDecodesEmptyAndKeepsLegacyOrder() {
        val old = """{"format":"myio-home-settings","version":1,"theme":"GREEN","servers":[],"zones":[],""" +
            """"assignments":{"s1":{"assignments":{"R1":["z1"]},"memberOrder":{"z1":["R1"]}}}}"""
        val decoded = SettingsBackup.decode(old)!!
        assertEquals(emptyMap<String, List<String>>(), decoded.zoneOrder)
        assertEquals(mapOf("z1" to listOf("R1")), decoded.assignments["s1"]!!.memberOrder)
    }

    @Test
    fun fullEncodeKeepsCredentialsAndShareFields() {
        val servers = listOf(
            MyioServer(name = "Fűtés", host = "10.0.0.1", user = "admin", password = "titok", shareUser = "family", sharePassword = "fam1"),
        )
        val decoded = SettingsBackup.decode(SettingsBackup.encode(AppTheme.GREEN, servers, emptyList(), emptyMap(), full = true))!!
        assertEquals(servers, decoded.servers)
        assertEquals(true, decoded.full)
    }

    @Test
    fun shareEncodeUnchangedAndCarriesNoFullExtras() {
        val servers = listOf(MyioServer(name = "F", host = "h", password = "titok", shareUser = "family", sharePassword = "fam1"))
        val decoded = SettingsBackup.decode(
            SettingsBackup.encode(
                AppTheme.GREEN, servers, emptyList(), emptyMap(),
                expertMode = true, hideZoned = true, selectedId = "x", lastTabs = mapOf("a" to "b"),
            ),
        )!!
        assertEquals(false, decoded.full)
        assertEquals("family", decoded.servers[0].user)
        assertEquals("fam1", decoded.servers[0].password)
        assertNull(decoded.servers[0].shareUser)
        assertNull(decoded.expertMode)
        assertNull(decoded.hideZoned)
        assertNull(decoded.selectedId)
        assertEquals(emptyMap<String, String>(), decoded.lastTabs)
    }

    @Test
    fun fullBackupRoundTripsEveryIncludedField() {
        val servers = listOf(
            MyioServer(name = "A", host = "10.0.0.1", pollIntervalSec = 7, hidden = true, hiddenOutputs = listOf("R1"), shareUser = "u", sharePassword = "p"),
            MyioServer(name = "B", host = "10.0.0.2"),
        )
        val zones = listOf(Zone(id = "z1", name = "Nappali"))
        val assignments = mapOf(servers[0].id to ZoneAssignments(assignments = mapOf("R1" to listOf("z1"))))
        val covers = mapOf(servers[1].id to mapOf("C1" to CoverOverride(swapped = true)))
        val scenes = listOf(Scene(id = "s1", name = "Este", entries = listOf(SceneEntry(servers[0].id, "R1", 1))))
        val order = mapOf("z1" to listOf("${servers[0].id}|R1"))
        val tabs = mapOf(servers[0].id to "Relé")
        val blob = SettingsBackup.encode(
            AppTheme.DYNAMIC, servers, zones, assignments, covers, scenes, order,
            full = true, expertMode = true, hideZoned = true, selectedId = servers[1].id, lastTabs = tabs,
        )
        val d = SettingsBackup.decode(blob)!!
        assertEquals(AppTheme.DYNAMIC, d.theme)
        assertEquals(servers, d.servers)
        assertEquals(zones, d.zones)
        assertEquals(assignments, d.assignments)
        assertEquals(covers, d.coverOverrides)
        assertEquals(scenes, d.scenes)
        assertEquals(order, d.zoneOrder)
        assertEquals(true, d.expertMode)
        assertEquals(true, d.hideZoned)
        assertEquals(servers[1].id, d.selectedId)
        assertEquals(tabs, d.lastTabs)
    }

    @Test
    fun oldFormatDecodesAsShareFile() {
        val old = """{"format":"myio-home-settings","version":1,"theme":"GREEN","servers":[],"zones":[],"assignments":{}}"""
        val d = SettingsBackup.decode(old)!!
        assertEquals(false, d.full)
        assertNull(d.expertMode)
    }

    @Test
    fun emptyConfigRoundTrips() {
        val blob = SettingsBackup.encode(AppTheme.GREEN, emptyList(), emptyList(), emptyMap())
        val decoded = SettingsBackup.decode(blob)!!
        assertEquals(AppTheme.GREEN, decoded.theme)
        assertEquals(emptyList<MyioServer>(), decoded.servers)
    }
}
