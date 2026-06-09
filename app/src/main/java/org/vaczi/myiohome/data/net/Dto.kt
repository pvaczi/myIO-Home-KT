package org.vaczi.myiohome.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the myIO port-80 JSON endpoints, captured from real firmware 3.7.5.1.
 *
 * Each state section is a JSON object keyed by 0-based index string ("0","1",...) whose values
 * carry the real device id. `sens_out.json` omits `description`; `d_sens_out.json` includes it
 * plus extra fields (inverse, alarm, timer, delay) which we ignore (ignoreUnknownKeys).
 */

@Serializable
data class StateRootDto(
    val relays: Map<String, RelayDto> = emptyMap(),
    @SerialName("PCA") val pca: Map<String, PcaDto> = emptyMap(),
    @SerialName("PWM") val pwm: Map<String, PwmDto> = emptyMap(),
    val sensors: Map<String, SensorDto> = emptyMap(),
)

@Serializable
data class RelayDto(
    val id: Int,
    val state: Int = 0,
    val sensor: Int = 0,
    val sensorON: Int = 0,
    val sensorOFF: Int = 0,
    val description: String? = null,
)

@Serializable
data class PcaDto(
    val id: Int,
    val state: Int = 0,
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
    val state: Int = 0,
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
