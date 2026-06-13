package org.vaczi.myiohome.data.model

import kotlinx.serialization.Serializable

/**
 * Roller shutter / blind built from a myIO **protection pair** (two same-type outputs — almost always
 * two PCAs — wired up/down and interlocked so they never run together). myIO has no native cover entity;
 * we synthesise one. Detection is structural (the protection pair), **not** name-based — mirrors the
 * proven `myIO-bridge` `detect_covers()`. Names are used only to orient up vs down.
 *
 * No real position feedback exists (the device's PCA timer stops the motor at the end stop), so the UI
 * offers up / stop / down and a direction indicator, not a position slider.
 */

enum class CoverState { OPENING, CLOSING, STOPPED }

enum class CoverOutputType { PCA, RELAY }

/** A detected cover. [upId]/[downId] are real device ids; [writable] = both endpoints are writable. */
data class Cover(
    val name: String,
    val upId: Int,
    val downId: Int,
    val type: CoverOutputType,
    val state: CoverState,
    val writable: Boolean,
) {
    /** Stable key (orientation-independent) for zone assignment, hiding and override storage. */
    fun outputKey(): String = coverKey(upId, downId)
}

/** Orientation-independent key from a protection pair's two element ids. */
fun coverKey(a: Int, b: Int): String = "C${minOf(a, b)}"

/**
 * Per-cover user override (persisted by CoverStore, keyed by [coverKey]). [disabled] = treat the pair as
 * plain outputs (not a cover). [swapped] = invert the auto-detected up/down orientation. [name] = custom
 * display name (else derived from the endpoint name, stripping the direction word).
 */
@Serializable
data class CoverOverride(
    val disabled: Boolean = false,
    val swapped: Boolean = false,
    val name: String? = null,
)

object CoverDetection {

    // Direction hints matched as whole tokens (lowercased). Hungarian "Le"/"Fel", English "down"/"up".
    private val DOWN_HINTS = setOf("le", "lent", "down")
    private val UP_HINTS = setOf("fel", "fent", "up")

    private fun tokens(name: String): List<String> =
        name.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }

    private fun hasAny(name: String, hints: Set<String>): Boolean = tokens(name).any { it in hints }

    /** Strip a trailing direction word ("… bal Le" → "… bal"). Falls back to the trimmed original. */
    private fun baseName(name: String): String {
        val toks = name.trim().split(Regex("\\s+"))
        if (toks.size > 1) {
            val last = toks.last().lowercase()
            if (last in DOWN_HINTS || last in UP_HINTS) return toks.dropLast(1).joinToString(" ")
        }
        return name.trim()
    }

    /**
     * Detect covers from [state]'s protection pairs. Only same-type pairs (both PCA or both relay) qualify;
     * mixed pairs are thermostat/inhibit links. [overrides] (keyed by [coverKey]) can disable a pair, swap
     * its orientation, or rename it. Duplicate protection pairs collapse by [coverKey].
     */
    fun detect(state: ServerState, overrides: Map<String, CoverOverride> = emptyMap()): List<Cover> {
        val pcas = state.pcas.associateBy { it.id }
        val relays = state.relays.associateBy { it.id }
        val seen = HashSet<String>()
        val result = ArrayList<Cover>()
        for (p in state.protectionPairs) {
            val bothPca = p.element0 in pcas && p.element1 in pcas
            val bothRelay = p.element0 in relays && p.element1 in relays
            if (!bothPca && !bothRelay) continue
            val key = coverKey(p.element0, p.element1)
            if (!seen.add(key)) continue
            val ov = overrides[key] ?: CoverOverride()
            if (ov.disabled) continue

            val name0 = if (bothPca) pcas.getValue(p.element0).name else relays.getValue(p.element0).name
            val name1 = if (bothPca) pcas.getValue(p.element1).name else relays.getValue(p.element1).name

            // Heuristic: element0 is "up" unless its name says down (or element1's name says up).
            val e0Down = hasAny(name0, DOWN_HINTS)
            val e1Down = hasAny(name1, DOWN_HINTS)
            val e0Up = hasAny(name0, UP_HINTS)
            val e1Up = hasAny(name1, UP_HINTS)
            val heuristicSwap = when {
                e0Down && !e1Down -> true
                e1Down && !e0Down -> false
                e1Up && !e0Up -> true
                e0Up && !e1Up -> false
                else -> false
            }
            val swap = heuristicSwap xor ov.swapped
            val upId = if (swap) p.element1 else p.element0
            val downId = if (swap) p.element0 else p.element1

            val upState = outputOn(state, upId, bothPca)
            val downState = outputOn(state, downId, bothPca)
            val coverState = when {
                upState -> CoverState.OPENING
                downState -> CoverState.CLOSING
                else -> CoverState.STOPPED
            }
            val writable = if (bothPca) {
                pcas.getValue(upId).writable && pcas.getValue(downId).writable
            } else {
                relays.getValue(upId).writable && relays.getValue(downId).writable
            }
            val upName = if (bothPca) pcas.getValue(upId).name else relays.getValue(upId).name
            val name = ov.name?.takeIf { it.isNotBlank() }
                ?: baseName(upName).ifBlank { baseName(name0).ifBlank { "Redőny $upId" } }

            result += Cover(
                name = name,
                upId = upId,
                downId = downId,
                type = if (bothPca) CoverOutputType.PCA else CoverOutputType.RELAY,
                state = coverState,
                writable = writable,
            )
        }
        return result
    }

    private fun outputOn(state: ServerState, id: Int, pca: Boolean): Boolean =
        if (pca) (state.pcas.firstOrNull { it.id == id }?.state ?: 0) != 0
        else state.relays.firstOrNull { it.id == id }?.on ?: false
}
