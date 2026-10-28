package org.vaczi.myiohome.data.model

import kotlinx.serialization.Serializable

/**
 * Domain models for one myIO server's state.
 *
 * Mirrors the proven C++ bridge model (myIO-bridge `src/common/types.hpp`), adapted to the
 * port-80 JSON endpoints (`sens_out.json` = state only, `d_sens_out.json` = state + names).
 * [sensorId]: 0 = no activator, 255 = the SUN (sunrise/sunset time automation; the output is still an ordinary,
 * manually switchable lamp), 1..254 = a sensor id (thermostat-style control, see [isSensorDriven]).
 *
 * These are [Serializable] so the last [ServerState] can be cached to disk for instant startup
 * (see StateCacheStore); only constructor properties are persisted, not the computed helpers.
 */

/**
 * Relay output (id 1..100). state is on/off.
 * [writable] = the logged-in myIO user may switch it; false renders a locked, disabled tile. Non-readable
 * outputs (no view permission) are dropped upstream (MyioRepository), so everything reaching the UI is at
 * least readable. Defaults true → fail-open before the per-user permissions load (or on older firmware).
 */
@Serializable
data class RelayOutput(
    val id: Int,
    val name: String,
    val on: Boolean,
    val sensorId: Int = 0,
    val sensorOnTenths: Int = 0,   // ×10 → °C (e.g. 230 = 23.0 °C)
    val sensorOffTenths: Int = 0,
    val writable: Boolean = true,
) {
    /** True if a sensor (id 1..254) drives this output; 0 (none) and 255 (sun automation) are NOT sensor-driven. */
    val isSensorDriven: Boolean get() = sensorId in 1..254
}

/**
 * PCA output (id 2001..2128). state 0..255; isPwm = brightness controllable.
 * [turnOn]/[turnOff] are the device-configured levels applied on PCA_ON / PCA_OFF (from the index header).
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
    val writable: Boolean = true,
) {
    val on: Boolean get() = state != 0
    /** True if a sensor (id 1..254) drives this output; 0 (none) and 255 (sun automation) are NOT sensor-driven. */
    val isSensorDriven: Boolean get() = sensorId in 1..254
}

/**
 * Legacy PWM (FET) output (id 101..113). state 0..255.
 * [turnOn]/[turnOff] are the device-configured levels applied on f_ON / f_OFF (from the index header).
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
    val writable: Boolean = true,
) {
    val on: Boolean get() = state != 0
    /** True if a sensor (id 1..254) drives this output; 0 (none) and 255 (sun automation) are NOT sensor-driven. */
    val isSensorDriven: Boolean get() = sensorId in 1..254
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

/**
 * Mutually-exclusive output pair (myIO `R_PROT.XML` / `sens_out` `protection` section). Typically a
 * shutter motor: [element0] and [element1] are the two directions that must never run together.
 * Cover detection consumes these (see CoverDetection).
 */
@Serializable
data class ProtectionPair(val index: Int, val element0: Int, val element1: Int)

/** Complete polled state of one myIO server. */
@Serializable
data class ServerState(
    val relays: List<RelayOutput> = emptyList(),
    val pcas: List<PcaOutput> = emptyList(),
    val pwms: List<PwmOutput> = emptyList(),
    val sensors: List<Sensor> = emptyList(),
    val protectionPairs: List<ProtectionPair> = emptyList(),
)
