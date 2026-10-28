package org.vaczi.myiohome.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the myIO port-80 JSON endpoints, captured from real firmware 3.7.5.1.
 *
 * Each state section is a JSON object keyed by 0-based index string ("0","1",...) whose values
 * carry the real device id. `sens_out.json` omits `description` and is filtered by the logged-in user's
 * rights: an output the user may not read arrives as a bare `{"id":N}` — hence the nullable `state`
 * (null = not readable; the parser drops it). Extra fields we do not use are ignored (ignoreUnknownKeys).
 */

@Serializable
data class StateRootDto(
    val relays: Map<String, RelayDto> = emptyMap(),
    @SerialName("PCA") val pca: Map<String, PcaDto> = emptyMap(),
    @SerialName("PWM") val pwm: Map<String, PwmDto> = emptyMap(),
    val sensors: Map<String, SensorDto> = emptyMap(),
    // Mutually-exclusive output pairs (R_PROT.XML). Used to detect shutters/covers: a pair where both
    // elements are the same output type (both PCA or both relay) is a cover's up/down motor pair.
    val protection: Map<String, ProtectionDto> = emptyMap(),
)

/** One protection (inhibit) pair from `sens_out.json`. `sens_out` omits the `element0d/1d` names. */
@Serializable
data class ProtectionDto(
    val element0: Int = 0,
    val element1: Int = 0,
)

@Serializable
data class RelayDto(
    val id: Int,
    val state: Int? = null,
    val sensor: Int = 0,
    val sensorON: Int = 0,
    val sensorOFF: Int = 0,
    val description: String? = null,
)

@Serializable
data class PcaDto(
    val id: Int,
    val state: Int? = null,
    val pwm: Int = 0,
    val sensor: Int = 0,
    val sensorON: Int = 0,
    val sensorOFF: Int = 0,
    val turnON: Int = 255,
    val turnOFF: Int = 0,
    val description: String? = null,
)

@Serializable
data class PwmDto(
    val id: Int,
    val state: Int? = null,
    val sensor: Int = 0,
    val sensorON: Int = 0,
    val sensorOFF: Int = 0,
    val turnON: Int = 255,
    val turnOFF: Int = 0,
    val description: String? = null,
)

@Serializable
data class SensorDto(
    val id: Int,
    val temp: Int? = null,
    val hum: Int? = null,
    val imp: Int? = null,
    val description: String? = null,
)

@Serializable
data class InfoRootDto(val info: InfoDto)

@Serializable
data class InfoDto(
    val serial: String = "",
    val version: String = "",
    @SerialName("RQ") val rq: String = "0",
    @SerialName("PCAQ") val pcaq: String = "0",
    val api: Int = 0,
)
