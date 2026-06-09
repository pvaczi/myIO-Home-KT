package org.vaczi.myiohome

import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsStoreTest {

    @Test
    fun unsetMeansEmpty() {
        // Discovery-first: a fresh install / cleared storage starts with no servers.
        assertEquals(emptyList<MyioServer>(), SettingsStore.parseServers(null))
    }

    @Test
    fun blankCorruptOrEmptyArrayMeansEmpty() {
        assertEquals(emptyList<MyioServer>(), SettingsStore.parseServers(""))
        assertEquals(emptyList<MyioServer>(), SettingsStore.parseServers("{not json"))
        assertEquals(emptyList<MyioServer>(), SettingsStore.parseServers("[]"))
    }

    @Test
    fun parsesStoredServers() {
        val stored = listOf(MyioServer("Garázs", "10.0.0.9", port = 8080, user = "u", password = "p"))
        val blob = Json.encodeToString(stored)
        assertEquals(stored, SettingsStore.parseServers(blob))
    }

    @Test
    fun pollIntervalIsPerServerAndRoundTrips() {
        val stored = listOf(
            MyioServer("Gyors", "10.0.0.1", pollIntervalSec = 1),
            MyioServer("Lassú", "10.0.0.2", pollIntervalSec = 10),
        )
        val parsed = SettingsStore.parseServers(Json.encodeToString(stored))
        assertEquals(1, parsed[0].pollIntervalSec)
        assertEquals(10, parsed[1].pollIntervalSec)
    }

    @Test
    fun legacyServersWithoutPollIntervalDefault() {
        // Slots persisted before the per-server interval existed must decode to the default.
        val legacy = """[{"name":"A","host":"10.0.0.5"}]"""
        val parsed = SettingsStore.parseServers(legacy)
        assertEquals(SettingsStore.DEFAULT_POLL_SEC, parsed.single().pollIntervalSec)
    }

    @Test
    fun hiddenFlagsRoundTripAndDefaultOff() {
        val stored = listOf(
            MyioServer("Rejtett", "10.0.0.1", hidden = true, hiddenOutputs = listOf("R3", "ST5")),
            MyioServer("Sima", "10.0.0.2"),
        )
        val parsed = SettingsStore.parseServers(Json.encodeToString(stored))
        assertEquals(true, parsed[0].hidden)
        assertEquals(listOf("R3", "ST5"), parsed[0].hiddenOutputs)
        // Defaults: legacy slots (no fields) decode as visible with nothing hidden.
        assertEquals(false, parsed[1].hidden)
        assertEquals(emptyList<String>(), parsed[1].hiddenOutputs)
    }

    @Test
    fun legacyServersWithoutIdGetDistinctIds() {
        // Pre-id persisted format with two slots sharing a host — this used to crash the editor
        // (duplicate LazyColumn keys). Each must now decode to a distinct, non-blank id.
        val legacy = """[{"name":"A","host":"192.168.1.10"},{"name":"B","host":"192.168.1.10"}]"""
        val parsed = SettingsStore.parseServers(legacy)
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.id.isNotBlank() })
        assertNotEquals(parsed[0].id, parsed[1].id)
    }
}
