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
