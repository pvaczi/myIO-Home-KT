package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.CoverOutputType
import org.vaczi.myiohome.data.model.CoverState
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.Sensor
import org.vaczi.myiohome.data.model.SensorKind
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.ui.SearchSource
import org.vaczi.myiohome.ui.foldText
import org.vaczi.myiohome.ui.searchOutputs

/** Search matching (accent folding, type label, id) and the "on" filter. */
class OutputSearchTest {

    private val cover = Cover("Nappali redőny", upId = 2010, downId = 2011, type = CoverOutputType.PCA, state = CoverState.OPENING, writable = true)

    private val state = ServerState(
        relays = listOf(RelayOutput(1, "Kávéfőző", on = true), RelayOutput(2, "Szivattyú", on = false)),
        pcas = listOf(
            PcaOutput(2001, "Állólámpa", state = 0),
            PcaOutput(2002, "Tükör", state = 90, isPwm = true),
            PcaOutput(2010, "Fel", state = 255),
            PcaOutput(2011, "Le", state = 0),
        ),
        pwms = listOf(PwmOutput(101, "Szalag", state = 0)),
        sensors = listOf(Sensor(7, "Hőmérséklet", SensorKind.TEMPERATURE, 21.0)),
    )

    private fun src(hidden: Set<String> = emptySet(), id: String = "s1", st: ServerState = state) =
        SearchSource(id, st, listOf(cover), hidden, enabled = true)

    private fun names(query: String, onlyOn: Boolean = false, vararg s: SearchSource) =
        searchOutputs(if (s.isEmpty()) listOf(src()) else s.toList(), query, onlyOn).map { it.tile.name }

    @Test
    fun foldsAccentsAndCase() {
        assertEquals("kavefozo", foldText("Kávéfőző"))
        assertEquals("allolampa", foldText("ÁLLÓLÁMPA"))
        assertEquals("redony", foldText("redőny"))
        assertEquals("uvegtigris", foldText("Üvegtigris"))
    }

    @Test
    fun nameMatchIsAccentAndCaseInsensitive() {
        assertEquals(listOf("Kávéfőző"), names("KAVEFOZO"))
        assertEquals(listOf("Állólámpa"), names("allol"))
        assertEquals(listOf("Tükör"), names("tukor"))
    }

    @Test
    fun typeLabelMatches() {
        assertEquals(listOf("Kávéfőző", "Szivattyú"), names("rele"))
        assertEquals(listOf("Nappali redőny"), names("redony"))
        assertEquals(listOf("Hőmérséklet"), names("szenzor"))
        assertEquals(listOf("Szalag"), names("pwm"))
    }

    @Test
    fun numericIdMatchesExactly() {
        assertEquals(listOf("Állólámpa"), names("2001"))
        assertEquals(listOf("Kávéfőző"), names("1"))
        // either cover endpoint id finds the cover (the endpoints are not separate tiles)
        assertEquals(listOf("Nappali redőny"), names("2011"))
    }

    @Test
    fun emptyQueryNeedsTheChip() {
        assertTrue(names("").isEmpty())
        assertTrue(names("   ").isEmpty())
    }

    @Test
    fun emptyQueryWithChipListsEverythingOn() {
        // relay on, PCA level > 0, cover moving; cover endpoints are not listed separately; sensors never
        assertEquals(listOf("Kávéfőző", "Tükör", "Nappali redőny"), names("", onlyOn = true))
    }

    @Test
    fun chipIntersectsWithQuery() {
        assertEquals(listOf("Tükör"), names("t", onlyOn = true))
        assertTrue(names("szivattyu", onlyOn = true).isEmpty())
    }

    @Test
    fun hiddenOutputsAreExcluded() {
        assertEquals(listOf("Tükör", "Nappali redőny"), names("", true, src(hidden = setOf("R1"))))
        assertEquals(listOf("Tükör"), names("", true, src(hidden = setOf("R1", cover.outputKey()))))
    }

    @Test
    fun aggregateKeepsServerOrderAndTagsOwner() {
        val other = ServerState(relays = listOf(RelayOutput(1, "Kávéfőző 2", on = true)))
        val hits = searchOutputs(listOf(src(id = "a"), src(id = "b", st = other)), "kavefozo", false)
        assertEquals(listOf("a", "b"), hits.map { it.serverId })
    }
}
