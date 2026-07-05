package org.vaczi.myiohome.data.net

/** Per-output access rights of the logged-in myIO user. [readable] = may see; [writable] = may control. */
data class Perm(val readable: Boolean, val writable: Boolean)

/**
 * Everything the app needs from one `GET /` index fetch, keyed by domain id:
 * names (relay/PCA/PWM/sensor), per-user [Perm]s, and the device-configured on/off levels.
 * Maps are sparse — a missing id means "unknown" (callers fail open: readable + writable, keep names).
 */
data class IndexMetadata(
    val relayNames: Map<Int, String> = emptyMap(),
    val pcaNames: Map<Int, String> = emptyMap(),
    val pwmNames: Map<Int, String> = emptyMap(),
    val sensorNames: Map<Int, String> = emptyMap(),
    val relayPerms: Map<Int, Perm> = emptyMap(),
    val pcaPerms: Map<Int, Perm> = emptyMap(),
    val pwmPerms: Map<Int, Perm> = emptyMap(),
    val pcaTurnOn: Map<Int, Int> = emptyMap(),
    val pcaTurnOff: Map<Int, Int> = emptyMap(),
    val pwmTurnOn: Map<Int, Int> = emptyMap(),
    val pwmTurnOff: Map<Int, Int> = emptyMap(),
)

/**
 * Parses the myIO device index page (`GET /`), whose JS-variable header carries the full per-user view
 * of the device. This single fetch replaces both `d_sens_out.json` (names + on/off levels) and the
 * HTTP/0.9 `/p_desc.xml` workaround: unlike d_sens_out, the index's `PCA_description[]` includes the
 * names of PCA id > 2100 (the firmware name bug is specific to d_sens_out), and it additionally exposes
 * the logged-in user's **read/write permissions** — which the JSON state endpoints never expose.
 *
 * Id mapping (proven on hardware): relay array index i → id i+1; PCA index i → id 2001+i and
 * `PCA_description[N]` → id 2000+N; PWM (FET) index i → id 101+i and `fet_description[N]` → id 100+N;
 * temperature/humidity `*_description[N]` are keyed by the sensor id directly (matches sens_out).
 *
 * Permission digits live in the state arrays themselves:
 *  - `relays[]` is 3-digit `RWS` (read, write, state): readable = hundreds≠0, writable = tens≠0.
 *  - `PCA[]` / `fet[]` are 5-digit `RWVVV` (read, write, 3-digit value): readable = ten-thousands≠0,
 *    writable = thousands≠0.
 * On/off levels: PCA `PCAMAX[]`/`PCAMIN[]`, PWM `fetMAX[]`/`fetMIN[]` (index i → the same id as the
 * state array). A `0` entry means "no rights" → not readable.
 *
 * Best-effort and tolerant: any array/section the firmware omits just yields an empty map, so the
 * caller keeps its previous values and the affected outputs stay fail-open.
 */
object MyioIndexParser {

    fun parse(index: String): IndexMetadata {
        val relays = intArray(index, "relays")
        val pca = intArray(index, "PCA")
        val fet = intArray(index, "fet")
        val pcaMax = intArray(index, "PCAMAX")
        val pcaMin = intArray(index, "PCAMIN")
        val fetMax = intArray(index, "fetMAX")
        val fetMin = intArray(index, "fetMIN")

        return IndexMetadata(
            relayNames = descriptions(index, "relay_description") { it },
            pcaNames = descriptions(index, "PCA_description") { it + 2000 },
            pwmNames = descriptions(index, "fet_description") { it + 100 },
            // Temperature + humidity descriptions are both keyed by the real sensor id (energy meters
            // carry no name array → those keep the generic poll label).
            sensorNames = descriptions(index, "thermo_description") { it } +
                descriptions(index, "hum_description") { it },
            relayPerms = relays.toIndexedMap { i -> i + 1 }.mapValues { perm3(it.value) },
            pcaPerms = pca.toIndexedMap { i -> 2001 + i }.mapValues { perm5(it.value) },
            pwmPerms = fet.toIndexedMap { i -> 101 + i }.mapValues { perm5(it.value) },
            pcaTurnOn = pcaMax.toIndexedMap { i -> 2001 + i },
            pcaTurnOff = pcaMin.toIndexedMap { i -> 2001 + i },
            pwmTurnOn = fetMax.toIndexedMap { i -> 101 + i },
            pwmTurnOff = fetMin.toIndexedMap { i -> 101 + i },
        )
    }

    /** 3-digit relay code `RWS`: readable = hundreds digit set, writable = tens digit set. */
    private fun perm3(v: Int) = Perm(readable = v / 100 != 0, writable = (v / 10) % 10 != 0)

    /** 5-digit PCA/FET code `RWVVV`: readable = ten-thousands set, writable = thousands set. */
    private fun perm5(v: Int) = Perm(readable = v / 10000 != 0, writable = (v / 1000) % 10 != 0)

    /**
     * `var <name>=[1,2,3];` → the int list (empty if the variable is absent). Position-preserving: a
     * non-numeric element becomes `0` rather than being dropped, so later elements keep their correct
     * array index (and thus id). `0` is also the firmware's own "no rights" code, so this is a safe
     * fail-closed default for that slot rather than a fail-open shift of every id after it.
     */
    private fun intArray(index: String, name: String): List<Int> {
        val body = Regex("var\\s+${Regex.escape(name)}\\s*=\\s*\\[([^\\]]*)\\]").find(index)
            ?.groupValues?.get(1)?.trim() ?: return emptyList()
        if (body.isEmpty()) return emptyList()
        return body.split(',')
            .map { it.trim().toIntOrNull() ?: 0 }
    }

    /** Map array position → id via [idOf], dropping the unused 0-based index. */
    private fun List<Int>.toIndexedMap(idOf: (Int) -> Int): Map<Int, Int> =
        withIndex().associate { (i, v) -> idOf(i) to v }

    /**
     * `<name> [N]='text';` repeated lines → id → text (non-blank only), with [idOf] turning the 1-based
     * declaration index N into the domain id. Tolerant of the space the firmware emits before `[`.
     */
    private fun descriptions(index: String, name: String, idOf: (Int) -> Int): Map<Int, String> =
        Regex("${Regex.escape(name)}\\s*\\[(\\d+)\\]\\s*=\\s*'([^']*)'").findAll(index)
            .mapNotNull { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val text = m.groupValues[2].trim()
                if (text.isEmpty()) null else idOf(n) to text
            }
            .toMap()
}
