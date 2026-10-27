package org.vaczi.myiohome.ui

/** Thermostat direction derived from the order of the on/off thresholds. */
internal enum class ThermostatMode { HEATING, COOLING, INVALID }

/**
 * on < off → heating (on below the band, off above); off < on → cooling; equal → [ThermostatMode.INVALID]
 * (the device would chatter / the direction is ambiguous). Pure → unit-testable.
 */
internal fun thermostatMode(onTenths: Int, offTenths: Int): ThermostatMode = when {
    onTenths < offTenths -> ThermostatMode.HEATING
    offTenths < onTenths -> ThermostatMode.COOLING
    else -> ThermostatMode.INVALID
}

/** Equal thresholds must not be saved. */
internal fun thresholdsValid(onTenths: Int, offTenths: Int): Boolean = onTenths != offTenths

/** Hungarian mode label; humidity sensors get the humidifier/dehumidifier wording, "—" for equal thresholds. */
internal fun thermostatModeLabel(onTenths: Int, offTenths: Int, humidity: Boolean): String =
    when (thermostatMode(onTenths, offTenths)) {
        ThermostatMode.HEATING -> if (humidity) "Párásítás" else "Fűtés"
        ThermostatMode.COOLING -> if (humidity) "Párátlanítás" else "Hűtés"
        ThermostatMode.INVALID -> "—"
    }

internal const val EQUAL_THRESHOLDS_HINT = "A be- és kikapcsolási érték nem lehet azonos"
