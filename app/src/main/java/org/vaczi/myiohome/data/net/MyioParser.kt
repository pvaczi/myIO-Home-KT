package org.vaczi.myiohome.data.net

import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.Sensor
import org.vaczi.myiohome.data.model.SensorKind
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import kotlinx.serialization.json.Json

/**
 * Parses the myIO port-80 JSON responses into domain models.
 * Tolerant of the unknown/extra fields present in `d_sens_out.json`.
 */
object MyioParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseState(body: String): ServerState {
        val root = json.decodeFromString(StateRootDto.serializer(), body)
        return ServerState(
            relays = root.relays.values
                .sortedBy { it.id }
                .map {
                    RelayOutput(
                        id = it.id,
                        name = it.description.orEmpty(),
                        on = it.state != 0,
                        sensorId = it.sensor,
                        sensorOnTenths = it.sensorON,
                        sensorOffTenths = it.sensorOFF,
                    )
                },
            pcas = root.pca.values
                .sortedBy { it.id }
                .map {
                    PcaOutput(
                        id = it.id,
                        name = it.description.orEmpty(),
                        state = it.state,
                        isPwm = it.pwm == 1,
                        sensorId = it.sensor,
                        sensorOnTenths = it.sensorON,
                        sensorOffTenths = it.sensorOFF,
                        turnOn = it.turnON,
                        turnOff = it.turnOFF,
                    )
                },
            pwms = root.pwm.values
                .sortedBy { it.id }
                .map {
                    PwmOutput(
                        id = it.id,
                        name = it.description.orEmpty(),
                        state = it.state,
                        sensorId = it.sensor,
                        sensorOnTenths = it.sensorON,
                        sensorOffTenths = it.sensorOFF,
                        turnOn = it.turnON,
                        turnOff = it.turnOFF,
                    )
                },
            sensors = root.sensors.values
                .sortedBy { it.id }
                .mapNotNull { it.toSensorOrNull() },
        )
    }

    fun parseInfo(body: String): ServerInfo {
        val info = json.decodeFromString(InfoRootDto.serializer(), body).info
        return ServerInfo(
            serial = info.serial,
            version = info.version,
            relayCount = info.rq.toIntOrNull() ?: 0,
            pcaCount = info.pcaq.toIntOrNull() ?: 0,
            api = info.api,
        )
    }

    /**
     * Parses `/p_desc.xml` (`<N>name</N>` entries, N is 1-based → real PCA id = N + 2000).
     * Recovers PCA names for id > 2100, which firmware returns empty in d_sens_out.json.
     * Returns only non-blank names, keyed by real PCA id.
     */
    fun parsePcaDesc(body: String): Map<Int, String> =
        Regex("<(\\d+)>([^<]*)</\\d+>").findAll(body)
            .mapNotNull { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val name = m.groupValues[2].trim()
                if (name.isEmpty()) null else (n + 2000) to name
            }
            .toMap()

    private fun SensorDto.toSensorOrNull(): Sensor? = when {
        temp != null -> Sensor(id, description.orEmpty(), SensorKind.TEMPERATURE, temp / 100.0)
        hum != null -> Sensor(id, description.orEmpty(), SensorKind.HUMIDITY, hum / 100.0)
        // imp == 0 means no energy meter is connected (matches the bridge dsens_parser) → drop it.
        imp != null && imp != 0 -> Sensor(id, description.orEmpty(), SensorKind.ENERGY, imp / 100.0)
        else -> null
    }
}
