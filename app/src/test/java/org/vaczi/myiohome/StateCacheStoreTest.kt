package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.StateCacheStore

class StateCacheStoreTest {

    @Test
    fun repointedSlotGetsANewCacheKey() {
        val slot = MyioServer("Fűtés", "10.0.0.11", id = "s1")
        assertEquals("s1@10.0.0.11:80", StateCacheStore.cacheKey(slot))
        assertNotEquals(StateCacheStore.cacheKey(slot), StateCacheStore.cacheKey(slot.copy(host = "10.0.0.12")))
        assertNotEquals(StateCacheStore.cacheKey(slot), StateCacheStore.cacheKey(slot.copy(port = 8080)))
        // Renaming the slot keeps the cache.
        assertEquals(StateCacheStore.cacheKey(slot), StateCacheStore.cacheKey(slot.copy(name = "Új név")))
    }

    @Test
    fun staleEntriesAreSnapshotsOfUnknownKeysOnly() {
        val present = setOf("state_s1@10.0.0.11:80", "state_s1", "state_gone@10.0.0.9:80", "other_pref")
        assertEquals(
            setOf("state_s1", "state_gone@10.0.0.9:80"),
            StateCacheStore.staleEntries(present, keep = setOf("s1@10.0.0.11:80")),
        )
    }
}
