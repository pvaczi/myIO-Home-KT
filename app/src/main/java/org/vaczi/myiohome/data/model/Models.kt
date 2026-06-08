package org.vaczi.myiohome.data.model

import kotlinx.serialization.Serializable

/**
 * Domain models for one myIO server's state.
 *
 * Mirrors the proven C++ bridge model (myIO-bridge `src/common/types.hpp`), adapted to the
 * port-80 JSON endpoints (`sens_out.json` = state only, `d_sens_out.json` = state + names).
 * Thermostat control is expressed by [sensorId] != 0 (the original app's "controlled relays").
 *
 * These are [Serializable] so the last [ServerState] can be cached to disk for instant startup
 * (see StateCacheStore); only constructor properties are persisted, not the computed helpers.
 */

/** Relay output (id 1..100). state is on/off. */
@Serializable
data class RelayOutput(
    val id: Int,
    val name: String,
    val on: Boolean,
    val sensorId: Int = 0,
    val sensorOnTenths: Int = 0,   // ×10 → °C (e.g. 230 = 23.0 °C)
    val sensorOffTenths: Int = 0,
) {
    val sensorControlled: Boolean get() = sensorId != 0
}

/**
 * PCA output (id 2001..2128). state 0..255; isPwm = brightness controllable.
 * [turnOn]/[turnOff] are the device-configured levels applied on PCA_ON / PCA_OFF (from d_sens_out).
 */
@Serializable
data class PcaOutput(
    val id: Int,
    val name: String,
    val state: Int,
    val isPwm: Boolean = false,
    val sensorId: Int = 0,
    val sensorOnTenths: Int = 0,
    val sensorOffTenths: Int = 0,
    val turnOn: Int = 255,
    val turnOff: Int = 0,
) {
    val on: Boolean get() = state != 0
    val sensorControlled: Boolean get() = sensorId != 0
}

/**
 * Legacy PWM (FET) output (id 101..113). state 0..255.
 * [turnOn]/[turnOff] are the device-configured levels applied on f_ON / f_OFF (from d_sens_out).
 */
@Serializable
data class PwmOutput(
    val id: Int,
    val name: String,
    val state: Int,
    val sensorId: Int = 0,
    val sensorOnTenths: Int = 0,
    val sensorOffTenths: Int = 0,
    val turnOn: Int = 255,
    val turnOff: Int = 0,
) {
    val on: Boolean get() = state != 0
    val sensorControlled: Boolean get() = sensorId != 0
}

@Serializable
enum class SensorKind { TEMPERATURE, HUMIDITY, ENERGY }

/** Sensor reading. [value] is already scaled to a human unit (°C, %, kWh). */
@Serializable
data class Sensor(
    val id: Int,
    val name: String,
    val kind: SensorKind,
    val value: Double,
)

/** Server metadata from `info.json`. */
data class ServerInfo(
    val serial: String,
    val version: String,
    val relayCount: Int,
    val pcaCount: Int,
    val api: Int,
)

/** Complete polled state of one myIO server. */
@Serializable
data class ServerState(
    val relays: List<RelayOutput> = emptyList(),
    val pcas: List<PcaOutput> = emptyList(),
    val pwms: List<PwmOutput> = emptyList(),
    val sensors: List<Sensor> = emptyList(),
)
