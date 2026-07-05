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

/**
 * A detected cover. [upId]/[downId] are real device ids; [writable] = both endpoints are writable.
 * [key] is the persisted, orientation-independent identity (see [CoverDetection.detect] for how it's
 * assigned — normally `coverKey(upId, downId)`, but disambiguated when another pair shares the same
 * min id) and is what [outputKey] returns; do not recompute it from [upId]/[downId] elsewhere.
 */
data class Cover(
    val name: String,
    val upId: Int,
    val downId: Int,
    val type: CoverOutputType,
    val state: CoverState,
    val writable: Boolean,
    val key: String = coverKey(upId, downId),
) {
    /** Stable key (orientation-independent) for zone assignment, hiding and override storage. */
    fun outputKey(): String = key
}

/** Orientation-independent key from a protection pair's two element ids. */
fun coverKey(a: Int, b: Int): String = "C${minOf(a, b)}"

/** All raw up/down endpoint ids covered by this list — used to exclude cover members from the raw relay/PCA tiles. */
fun List<Cover>.memberIds(): Set<Int> = flatMap { listOf(it.upId, it.downId) }.toSet()

/** Fallback key for a pair whose primary [coverKey] is already taken by a different pair sharing the min id. */
private fun coverKeyDisambiguated(a: Int, b: Int): String = "C${minOf(a, b)}-${maxOf(a, b)}"

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

/**
 * Expand a set of hidden output keys so a hidden **disabled cover** keeps hiding its two endpoints.
 * When a cover is demoted with [CoverOverride.disabled], detection drops it and its two motors resurface
 * as plain P/R tiles — but the user hid it under its `C…` key, so without this the deliberately-hidden
 * device would become controllable again (notably in the non-expert/family view). [disabledMembers] maps
 * each disabled cover's key to its endpoint output keys (see [CoverDetection.detectAll]). Applied at
 * read time (not persisted) so re-enabling the cover restores everything automatically.
 */
fun expandHiddenForDisabledCovers(
    hidden: Set<String>,
    disabledMembers: Map<String, List<String>>,
): Set<String> {
    if (disabledMembers.isEmpty()) return hidden
    val extra = disabledMembers.asSequence().filter { it.key in hidden }.flatMap { it.value }.toSet()
    return if (extra.isEmpty()) hidden else hidden + extra
}

/**
 * Expand a zone-assignment map the same way: a disabled cover's zone membership (stored under its `C…`
 * key) is copied onto its endpoint output keys, so the now-plain motors show up in the zone/home views
 * instead of orphaning the assignment. Existing per-endpoint assignments (rare) are merged, not replaced.
 */
fun expandAssignmentsForDisabledCovers(
    assignments: Map<String, List<String>>,
    disabledMembers: Map<String, List<String>>,
): Map<String, List<String>> {
    if (disabledMembers.isEmpty()) return assignments
    val additions = HashMap<String, List<String>>()
    for ((coverKey, memberKeys) in disabledMembers) {
        val zones = assignments[coverKey] ?: continue
        if (zones.isEmpty()) continue
        for (m in memberKeys) {
            additions[m] = ((assignments[m] ?: emptyList()) + additions[m].orEmpty() + zones).distinct()
        }
    }
    return if (additions.isEmpty()) assignments else assignments + additions
}

object CoverDetection {

    /**
     * Result of a full detection pass: the active [covers] plus [disabledMembers] — for every pair that
     * *would* be a cover but is turned off via [CoverOverride.disabled], its cover key mapped to the raw
     * P/R output keys of its two endpoints. The latter lets read-time hidden/zone filters that key off the
     * cover's `C…` key extend to the endpoints once the pair is demoted to plain outputs (see
     * [expandHiddenForDisabledCovers] / [expandAssignmentsForDisabledCovers]).
     */
    data class Detection(
        val covers: List<Cover>,
        val disabledMembers: Map<String, List<String>>,
    )

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
     * its orientation, or rename it. Duplicate protection pairs (the same two element ids) collapse to one
     * cover; distinct pairs that merely *share* their min id (e.g. (2001,2002) and (2001,2003)) are kept as
     * separate covers — the second gets a disambiguated key (see [coverKeyDisambiguated]) rather than being
     * silently dropped. The primary `coverKey(min)` always goes to whichever pair is encountered first, so
     * existing persisted overrides/zone-assignments keep resolving to the same cover.
     */
    /** Active covers only (back-compat convenience over [detectAll]). */
    fun detect(state: ServerState, overrides: Map<String, CoverOverride> = emptyMap()): List<Cover> =
        detectAll(state, overrides).covers

    fun detectAll(state: ServerState, overrides: Map<String, CoverOverride> = emptyMap()): Detection {
        val pcas = state.pcas.associateBy { it.id }
        val relays = state.relays.associateBy { it.id }
        val seenPairs = HashSet<Pair<Int, Int>>()
        val seenKeys = HashSet<String>()
        val result = ArrayList<Cover>()
        val disabledMembers = HashMap<String, List<String>>()
        for (p in state.protectionPairs) {
            val bothPca = p.element0 in pcas && p.element1 in pcas
            val bothRelay = p.element0 in relays && p.element1 in relays
            if (!bothPca && !bothRelay) continue
            val pairIds = minOf(p.element0, p.element1) to maxOf(p.element0, p.element1)
            if (!seenPairs.add(pairIds)) continue
            val primaryKey = coverKey(p.element0, p.element1)
            val key = if (seenKeys.add(primaryKey)) primaryKey else coverKeyDisambiguated(p.element0, p.element1)
            if (key != primaryKey) seenKeys.add(key)
            val ov = overrides[key] ?: CoverOverride()
            if (ov.disabled) {
                // Record the endpoints' raw output keys so hidden/zone filters keyed on the cover's key can
                // extend to the now-plain outputs (P<id> for PCA, R<id> for relay — matches outputKey()).
                val prefix = if (bothPca) "P" else "R"
                disabledMembers[key] = listOf("$prefix${p.element0}", "$prefix${p.element1}")
                continue
            }

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

            val upState = outputOn(pcas, relays, upId, bothPca)
            val downState = outputOn(pcas, relays, downId, bothPca)
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
                key = key,
            )
        }
        return Detection(covers = result, disabledMembers = disabledMembers)
    }

    /** O(1) lookup via the id→output maps [detect] already built, instead of rescanning the state lists. */
    private fun outputOn(pcas: Map<Int, PcaOutput>, relays: Map<Int, RelayOutput>, id: Int, pca: Boolean): Boolean =
        if (pca) (pcas[id]?.state ?: 0) != 0
        else relays[id]?.on ?: false
}
