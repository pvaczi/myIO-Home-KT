package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.vaczi.myiohome.data.local.ZoneOrder
import org.vaczi.myiohome.data.local.ZoneStore
import org.vaczi.myiohome.data.local.memberRef

class ZoneOrderTest {

    @Test
    fun migratesLegacyPerServerOrdersInSlotOrder() {
        val legacy = mapOf(
            "s1" to mapOf("z1" to listOf("ST1", "ST2")),
            "s2" to mapOf("z1" to listOf("P5", "C7"), "z2" to listOf("R1")),
        )
        val merged = ZoneOrder.migrate(emptyMap(), listOf("s1", "s2"), legacy)
        assertEquals(listOf("s1|ST1", "s1|ST2", "s2|P5", "s2|C7"), merged["z1"])
        assertEquals(listOf("s2|R1"), merged["z2"])
        // Slot order decides, not map order.
        val swapped = ZoneOrder.migrate(emptyMap(), listOf("s2", "s1"), legacy)
        assertEquals(listOf("s2|P5", "s2|C7", "s1|ST1", "s1|ST2"), swapped["z1"])
    }

    @Test
    fun migrationLeavesExistingZoneOrdersAlone() {
        val existing = mapOf("z1" to listOf("s1|R9"))
        val merged = ZoneOrder.migrate(existing, listOf("s1"), mapOf("s1" to mapOf("z1" to listOf("R1"), "z2" to listOf("R2"))))
        assertEquals(listOf("s1|R9"), merged["z1"])
        assertEquals(listOf("s1|R2"), merged["z2"])
    }

    @Test
    fun sortPutsMissingMembersLastInGivenOrder() {
        val order = listOf("s2|C7", "s1|R1")
        assertEquals(
            listOf("s2|C7", "s1|R1", "s1|R2", "s2|R3"),
            ZoneOrder.sort(order, listOf("s1|R1", "s1|R2", "s2|R3", "s2|C7")),
        )
        // Stale refs in the order are ignored; no order = untouched.
        assertEquals(listOf("a", "b"), ZoneOrder.sort(listOf("zz", "b"), listOf("a", "b")).reversed())
        assertEquals(listOf("a", "b"), ZoneOrder.sort(null, listOf("a", "b")))
    }

    @Test
    fun aggregateMoveSwapsWithDisplayedNeighbour() {
        val displayed = listOf("a|1", "b|1", "a|2")
        assertEquals(listOf("a|1", "a|2", "b|1"), ZoneOrder.move(null, displayed, 2, up = true))
        assertEquals(listOf("b|1", "a|1", "a|2"), ZoneOrder.move(null, displayed, 0, up = false))
        assertNull(ZoneOrder.move(null, displayed, 0, up = true))
        assertNull(ZoneOrder.move(null, displayed, 2, up = false))
    }

    @Test
    fun singleServerMoveStaysRelativeToItsOwnNeighbours() {
        // Global order interleaves two servers; server a's view is [a|1, a|2].
        val saved = listOf("a|1", "b|9", "a|2", "b|8")
        val moved = ZoneOrder.move(saved, listOf("a|1", "a|2"), 1, up = true)!!
        // a|2 jumps before its same-server neighbour a|1; the other server's refs keep their relative order.
        assertEquals(listOf("a|2", "a|1", "b|9", "b|8"), moved)
        assertEquals(listOf("b|9", "b|8"), moved.filter { it.startsWith("b|") })
        // Down from the first: lands right after its neighbour.
        assertEquals(listOf("b|9", "a|2", "a|1", "b|8"), ZoneOrder.move(saved, listOf("a|1", "a|2"), 0, up = false))
    }

    @Test
    fun moveAppendsDisplayedMembersMissingFromSavedOrder() {
        // Saved knows only b|1; the display was [b|1, a|1, a|2] → persisting must not reshuffle it.
        val moved = ZoneOrder.move(listOf("b|1"), listOf("b|1", "a|1", "a|2"), 2, up = true)!!
        assertEquals(listOf("b|1", "a|2", "a|1"), moved)
    }

    @Test
    fun pruneDropsMatchingRefsButKeepsEmptyOrders() {
        val order = mapOf("z1" to listOf("s1|R1", "s2|R1"), "z2" to listOf("s1|R2"))
        val pruned = ZoneOrder.prune(order) { it.startsWith("s1|") }
        assertEquals(mapOf("z1" to listOf("s2|R1"), "z2" to emptyList<String>()), pruned)
    }

    @Test
    fun forServerFiltersAndStripsThePrefix() {
        val order = mapOf("z1" to listOf("s1|R1", "s2|C7", "s1|P5"))
        assertEquals(mapOf("z1" to listOf("R1", "P5")), ZoneOrder.forServer(order, "s1"))
        assertEquals(mapOf("z1" to listOf("C7")), ZoneOrder.forServer(order, "s2"))
    }

    @Test
    fun coverKeysWorkAsMembers() {
        assertEquals("s1|C2001", memberRef("s1", "C2001"))
        val moved = ZoneOrder.move(null, listOf("s1|C2001", "s1|R1"), 1, up = true)!!
        assertEquals(listOf("s1|R1", "s1|C2001"), moved)
    }

    @Test
    fun parseOrderIsTolerant() {
        assertEquals(emptyMap<String, List<String>>(), ZoneStore.parseOrder(null))
        assertEquals(emptyMap<String, List<String>>(), ZoneStore.parseOrder("{broken"))
        assertEquals(mapOf("z1" to listOf("s1|R1")), ZoneStore.parseOrder("""{"z1":["s1|R1"]}"""))
    }
}
