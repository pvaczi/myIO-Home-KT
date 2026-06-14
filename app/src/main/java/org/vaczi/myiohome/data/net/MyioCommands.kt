package org.vaczi.myiohome.data.net

import org.vaczi.myiohome.data.model.CoverOutputType

/**
 * Builds the myIO control command bodies POSTed to `/` (see `MyIO/AGENTS.md` "Vezérlő parancsok").
 *
 * id conventions:
 *  - relays: 1-based device id (matches the domain id directly)
 *  - PCA: real 2001+ device id
 *  - FET/PWM: the wire command uses a 1-based index, while the domain id is 101+, so we map
 *    index = id − 100 (e.g. domain id 101 → `fet*1`)
 *  - PCA: thermostat commands use a 1-based index (id − 2000), unlike the on/off/brightness commands
 *    which use the real 2001+ id.
 */
object MyioCommands {

    /** Pure-mapping for FET/PWM: domain id (101+) → 1-based wire index. */
    fun fetIndex(id: Int): Int = id - 100

    /** Pure-mapping for PCA thermostat commands: domain id (2001+) → 1-based wire index. */
    fun pcaIndex(id: Int): Int = id - 2000

    fun relay(id: Int, on: Boolean): String = if (on) "r_ON=$id" else "r_OFF=$id"

    fun pcaOnOff(id: Int, on: Boolean): String = if (on) "PCA_ON=$id" else "PCA_OFF=$id"

    fun pcaBrightness(id: Int, value: Int): String = "PCA*$id=${value.coerceIn(0, 255)}"

    fun fetOnOff(id: Int, on: Boolean): String =
        if (on) "f_ON=${fetIndex(id)}" else "f_OFF=${fetIndex(id)}"

    fun fetBrightness(id: Int, value: Int): String = "fet*${fetIndex(id)}=${value.coerceIn(0, 255)}"

    // --- Thermostat setpoints (value is tenths of °C, e.g. 23.5 °C → 235). ON = turn-on, OFF =
    // turn-off threshold; heating mode has ON < OFF, cooling mode OFF < ON. -------------------------

    /** Relay turn-on / turn-off temperature (documented: `min_temp_ON*x` / `max_temp_OFF*x`, x = relay id). */
    fun relayThermostatOn(id: Int, tenths: Int): String = "min_temp_ON*$id=$tenths"
    fun relayThermostatOff(id: Int, tenths: Int): String = "max_temp_OFF*$id=$tenths"

    /** FET/PWM turn-on / turn-off temperature (documented: `mix_temp_MIN*x` / `mix_temp_MAX*x`). */
    fun fetThermostatOn(id: Int, tenths: Int): String = "mix_temp_MIN*${fetIndex(id)}=$tenths"
    fun fetThermostatOff(id: Int, tenths: Int): String = "mix_temp_MAX*${fetIndex(id)}=$tenths"

    /** PCA turn-on / turn-off temperature (from the legacy app: `PCA_temp_MIN*x` / `PCA_temp_MAX*x`). */
    fun pcaThermostatOn(id: Int, tenths: Int): String = "PCA_temp_MIN*${pcaIndex(id)}=$tenths"
    fun pcaThermostatOff(id: Int, tenths: Int): String = "PCA_temp_MAX*${pcaIndex(id)}=$tenths"

    // --- Cover (shutter) motor control (mirrors the bridge): always turn the opposite direction OFF first,
    // then the wanted direction ON, so the protection pair can't deadlock. Sent sequentially. -----------

    private fun on(type: CoverOutputType, id: Int) =
        if (type == CoverOutputType.PCA) pcaOnOff(id, true) else relay(id, true)
    private fun off(type: CoverOutputType, id: Int) =
        if (type == CoverOutputType.PCA) pcaOnOff(id, false) else relay(id, false)

    /** OPEN/up: stop the down direction, drive up. */
    fun coverOpen(type: CoverOutputType, upId: Int, downId: Int): List<String> =
        listOf(off(type, downId), on(type, upId))

    /** CLOSE/down: stop the up direction, drive down. */
    fun coverClose(type: CoverOutputType, upId: Int, downId: Int): List<String> =
        listOf(off(type, upId), on(type, downId))

    /** STOP: both directions off. */
    fun coverStop(type: CoverOutputType, upId: Int, downId: Int): List<String> =
        listOf(off(type, upId), off(type, downId))
}
