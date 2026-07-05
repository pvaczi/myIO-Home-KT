package org.vaczi.myiohome

import org.vaczi.myiohome.data.model.CoverDetection
import org.vaczi.myiohome.data.model.CoverOutputType
import org.vaczi.myiohome.data.model.CoverOverride
import org.vaczi.myiohome.data.model.CoverState
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.ProtectionPair
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.coverKey
import org.vaczi.myiohome.data.model.expandAssignmentsForDisabledCovers
import org.vaczi.myiohome.data.model.expandHiddenForDisabledCovers
import org.vaczi.myiohome.data.model.memberIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure cover-detection tests (structural: protection pair, name only orients up/down). */
class CoverDetectionTest {

    private fun pca(id: Int, name: String, state: Int = 0, writable: Boolean = true) =
        PcaOutput(id = id, name = name, state = state, writable = writable)

    @Test
    fun detectsPcaPairAndOrientsByName() {
        // Real device naming: element0 = "Le" (down), element1 = "Fel" (up) → must swap.
        val state = ServerState(
            pcas = listOf(pca(2001, "Nappali ablak Le"), pca(2002, "Nappali ablak Fel")),
            protectionPairs = listOf(ProtectionPair(0, 2001, 2002)),
        )
        val covers = CoverDetection.detect(state)
        assertEquals(1, covers.size)
        val c = covers.single()
        assertEquals(CoverOutputType.PCA, c.type)
        assertEquals(2002, c.upId)   // "Fel"
        assertEquals(2001, c.downId) // "Le"
        assertEquals("Nappali ablak", c.name) // direction word stripped
        assertTrue(c.writable)
    }

    @Test
    fun mixedTypePairIsNotACover() {
        // Relay inhibiting a PCA = thermostat/inhibit, not a cover.
        val state = ServerState(
            relays = listOf(RelayOutput(id = 5, name = "Szivattyú", on = false)),
            pcas = listOf(pca(2001, "Szelep")),
            protectionPairs = listOf(ProtectionPair(0, 5, 2001)),
        )
        assertTrue(CoverDetection.detect(state).isEmpty())
    }

    @Test
    fun duplicatePairCollapses() {
        val state = ServerState(
            pcas = listOf(pca(2051, "Iroda Le"), pca(2052, "Iroda Fel")),
            protectionPairs = listOf(ProtectionPair(0, 2051, 2052), ProtectionPair(1, 2051, 2052)),
        )
        assertEquals(1, CoverDetection.detect(state).size)
    }

    @Test
    fun overlappingPairsSharingMinIdBothAppearOnDistinctKeys() {
        // (2001,2002) and (2001,2003) share the min element (2001) so a naive "C<min>" key would collide
        // and silently drop the second pair. Both must survive, on two different keys, and the first pair
        // keeps the plain "C2001" key so existing persisted overrides/zone-assignments still resolve.
        val state = ServerState(
            pcas = listOf(
                pca(2001, "A Le"), pca(2002, "A Fel"),
                pca(2003, "B Fel"),
            ),
            protectionPairs = listOf(
                ProtectionPair(0, 2001, 2002),
                ProtectionPair(1, 2001, 2003),
            ),
        )
        val covers = CoverDetection.detect(state)
        assertEquals(2, covers.size)
        val keys = covers.map { it.outputKey() }.toSet()
        // First-seen pair keeps the plain "C<min>" key; the second, colliding pair gets a disambiguated
        // "C<min>-<max>" key instead of being silently dropped by the dedup.
        assertEquals(setOf("C2001", "C2001-2003"), keys)
        assertEquals(coverKey(2001, 2002), "C2001") // sanity: the primary key format didn't change
    }

    @Test
    fun memberIdsCollectsAllUpAndDownIds() {
        val state = ServerState(
            pcas = listOf(pca(2001, "X Le"), pca(2002, "X Fel"), pca(2003, "Y Le"), pca(2004, "Y Fel")),
            protectionPairs = listOf(ProtectionPair(0, 2001, 2002), ProtectionPair(1, 2003, 2004)),
        )
        val covers = CoverDetection.detect(state)
        assertEquals(setOf(2001, 2002, 2003, 2004), covers.memberIds())
    }

    @Test
    fun disabledOverrideDropsCover() {
        val state = ServerState(
            pcas = listOf(pca(2001, "X Le"), pca(2002, "X Fel")),
            protectionPairs = listOf(ProtectionPair(0, 2001, 2002)),
        )
        val ov = mapOf(coverKey(2001, 2002) to CoverOverride(disabled = true))
        assertTrue(CoverDetection.detect(state, ov).isEmpty())
    }

    @Test
    fun swappedOverrideInvertsOrientation() {
        val state = ServerState(
            pcas = listOf(pca(2001, "X Le"), pca(2002, "X Fel")),
            protectionPairs = listOf(ProtectionPair(0, 2001, 2002)),
        )
        // Heuristic puts up=2002; the override inverts it.
        val ov = mapOf(coverKey(2001, 2002) to CoverOverride(swapped = true))
        val c = CoverDetection.detect(state, ov).single()
        assertEquals(2001, c.upId)
        assertEquals(2002, c.downId)
    }

    @Test
    fun ambiguousNamesKeepElement0AsUp() {
        // No direction words → default element0 = up.
        val state = ServerState(
            pcas = listOf(pca(2005, "Függöny háló"), pca(2006, "Függöny háló")),
            protectionPairs = listOf(ProtectionPair(0, 2005, 2006)),
        )
        val c = CoverDetection.detect(state).single()
        assertEquals(2005, c.upId)
        assertEquals(2006, c.downId)
    }

    @Test
    fun stateReflectsRunningDirection() {
        val opening = CoverDetection.detect(
            ServerState(
                pcas = listOf(pca(2001, "X Le", state = 0), pca(2002, "X Fel", state = 255)),
                protectionPairs = listOf(ProtectionPair(0, 2001, 2002)),
            )
        ).single()
        assertEquals(CoverState.OPENING, opening.state)

        val closing = CoverDetection.detect(
            ServerState(
                pcas = listOf(pca(2001, "X Le", state = 255), pca(2002, "X Fel", state = 0)),
                protectionPairs = listOf(ProtectionPair(0, 2001, 2002)),
            )
        ).single()
        assertEquals(CoverState.CLOSING, closing.state)
    }

    @Test
    fun nonWritableEndpointMakesCoverNonWritable() {
        val state = ServerState(
            pcas = listOf(pca(2001, "X Le", writable = false), pca(2002, "X Fel")),
            protectionPairs = listOf(ProtectionPair(0, 2001, 2002)),
        )
        assertFalse(CoverDetection.detect(state).single().writable)
    }

    @Test
    fun detectAllReportsDisabledPairMembersByCoverKey() {
        val state = ServerState(
            pcas = listOf(pca(2001, "X Le"), pca(2002, "X Fel")),
            protectionPairs = listOf(ProtectionPair(0, 2001, 2002)),
        )
        val ov = mapOf(coverKey(2001, 2002) to CoverOverride(disabled = true))
        val d = CoverDetection.detectAll(state, ov)
        assertTrue(d.covers.isEmpty()) // dropped from the active covers
        // …but its two endpoints are reported as raw P-keys under the cover's key, so read-time filters can
        // extend the C-key's hidden flag / zone membership to them.
        assertEquals(mapOf("C2001" to listOf("P2001", "P2002")), d.disabledMembers)
    }

    @Test
    fun detectAllRelayPairUsesRKeys() {
        val state = ServerState(
            relays = listOf(RelayOutput(id = 5, name = "Kapu Le", on = false), RelayOutput(id = 6, name = "Kapu Fel", on = false)),
            protectionPairs = listOf(ProtectionPair(0, 5, 6)),
        )
        val ov = mapOf(coverKey(5, 6) to CoverOverride(disabled = true))
        assertEquals(mapOf("C5" to listOf("R5", "R6")), CoverDetection.detectAll(state, ov).disabledMembers)
    }

    @Test
    fun expandHiddenPropagatesDisabledCoverKeyToEndpoints() {
        val disabled = mapOf("C2001" to listOf("P2001", "P2002"))
        // The cover key is hidden → its endpoints join the hidden set.
        assertEquals(
            setOf("C2001", "P2001", "P2002"),
            expandHiddenForDisabledCovers(setOf("C2001"), disabled),
        )
        // The cover key isn't hidden → nothing added (endpoints stay controllable).
        assertEquals(setOf("R3"), expandHiddenForDisabledCovers(setOf("R3"), disabled))
        // No disabled covers → identity.
        assertEquals(setOf("C2001"), expandHiddenForDisabledCovers(setOf("C2001"), emptyMap()))
    }

    @Test
    fun expandAssignmentsPropagatesDisabledCoverKeyToEndpoints() {
        val disabled = mapOf("C2001" to listOf("P2001", "P2002"))
        val assignments = mapOf("C2001" to listOf("zoneA"), "R3" to listOf("zoneB"))
        val expanded = expandAssignmentsForDisabledCovers(assignments, disabled)
        assertEquals(listOf("zoneA"), expanded["P2001"])
        assertEquals(listOf("zoneA"), expanded["P2002"])
        assertEquals(listOf("zoneB"), expanded["R3"]) // untouched
        // An unassigned disabled cover adds nothing.
        assertEquals(
            mapOf("R3" to listOf("zoneB")),
            expandAssignmentsForDisabledCovers(mapOf("R3" to listOf("zoneB")), disabled),
        )
    }

    @Test
    fun customNameOverrideWins() {
        val state = ServerState(
            pcas = listOf(pca(2001, "X Le"), pca(2002, "X Fel")),
            protectionPairs = listOf(ProtectionPair(0, 2001, 2002)),
        )
        val ov = mapOf(coverKey(2001, 2002) to CoverOverride(name = "Konyha redőny"))
        assertEquals("Konyha redőny", CoverDetection.detect(state, ov).single().name)
    }
}
