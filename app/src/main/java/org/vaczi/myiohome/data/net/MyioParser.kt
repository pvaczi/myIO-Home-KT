package org.vaczi.myiohome.data.net

import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.ProtectionPair
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.Sensor
import org.vaczi.myiohome.data.model.SensorKind
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import kotlinx.serialization.json.Json

/**
 * Parses the myIO port-80 JSON responses into domain models.
 * Tolerant of unknown/extra fields. An output entry without `state` (bare `{"id":N}`: the user lacks read
 * permission) is dropped, never shown as an OFF output.
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
                .filter { it.state != null }
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
                .filter { it.state != null }
                .map {
                    PcaOutput(
                        id = it.id,
                        name = it.description.orEmpty(),
                        state = it.state ?: 0,
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
                .filter { it.state != null }
                .map {
                    PwmOutput(
                        id = it.id,
                        name = it.description.orEmpty(),
                        state = it.state ?: 0,
                        sensorId = it.sensor,
                        sensorOnTenths = it.sensorON,
                        sensorOffTenths = it.sensorOFF,
                        turnOn = it.turnON,
                        turnOff = it.turnOFF,
                    )
                },
            sensors = root.sensors.values
                .sortedBy { it.id }
                .flatMap { it.toSensors() },
            protectionPairs = root.protection
                .mapNotNull { (key, p) ->
                    // Skip empty slots (element 0). The map key is the protection index.
                    if (p.element0 == 0 || p.element1 == 0) null
                    else ProtectionPair(key.toIntOrNull() ?: 0, p.element0, p.element1)
                }
                .sortedBy { it.index },
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
     * A device entry can carry more than one physical reading (e.g. a combined temp+humidity probe
     * reports both `temp` and `hum` under the same id) — emit one [Sensor] per present field rather
     * than picking just the first match, or the secondary reading is silently lost.
     */
    private fun SensorDto.toSensors(): List<Sensor> = buildList {
        if (temp != null) add(Sensor(id, description.orEmpty(), SensorKind.TEMPERATURE, temp / 100.0))
        if (hum != null) add(Sensor(id, description.orEmpty(), SensorKind.HUMIDITY, hum / 100.0))
        // imp == 0 means no energy meter is connected (matches the bridge dsens_parser) → drop it.
        if (imp != null && imp != 0) add(Sensor(id, description.orEmpty(), SensorKind.ENERGY, imp / 100.0))
    }
}
