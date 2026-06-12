package org.vaczi.myiohome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.vaczi.myiohome.data.model.Sensor
import org.vaczi.myiohome.data.model.SensorKind
import org.vaczi.myiohome.data.model.ServerState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Thermostat-controlled outputs (FragmentControlledRelays parity): a relay / PCA / FET counts as
 * thermostat-controlled only if its activator (`sensorId`) refers to a real temperature/humidity
 * sensor present in the state — exactly what the factory app required (it iterated the sensor list).
 * Time-based activators (e.g. sunset-on / midnight-off garden lights) reference no such sensor, so
 * they are correctly excluded instead of being shown as bogus thermostats. The unit (°C / %) follows
 * the controlling sensor's kind. Heating mode = on-threshold below off-threshold; cooling is the reverse.
 */
@Composable
fun ControlledList(state: ServerState, actions: MyioActions, enabled: Boolean, expertMode: Boolean) {
    val items = remember(state) { state.controlledItems(actions) }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nincs termosztát-vezérelt kimenet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var editing by remember { mutableStateOf<ControlledItem?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.key }) { item ->
            // No write permission → the setpoint editor stays locked even when connected.
            ControlledRow(item = item, enabled = enabled && item.writable, onEdit = { editing = item })
            HorizontalDivider()
        }
    }

    editing?.let { item ->
        ThermostatDialog(
            name = item.name,
            sensor = item.sensor,
            onTenths = item.onTenths,
            offTenths = item.offTenths,
            expertMode = expertMode,
            onDismiss = { editing = null },
            onConfirm = { on, off -> item.apply(on, off); editing = null },
        )
    }
}

@Composable
private fun ControlledRow(item: ControlledItem, enabled: Boolean, onEdit: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.name) },
        supportingContent = {
            Column {
                Text(
                    "${item.sensor.name.ifBlank { "Szenzor ${item.sensor.id}" }}: ${formatSensor(item.sensor)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    thresholdSummary(item.onTenths, item.offTenths, item.unit),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!item.writable) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Csak megtekintés (nincs vezérlési jog)",
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    IconButton(onClick = onEdit, enabled = enabled) {
                        Icon(Icons.Filled.Edit, contentDescription = "Küszöbök szerkesztése")
                    }
                }
            }
        },
        modifier = if (enabled) Modifier.clickable(onClick = onEdit) else Modifier,
    )
}

/**
 * Setpoint editor in the original app's mental model: the user drags a **target** slider (the desired
 * temperature/humidity = the midpoint of the on/off band) and — only in expert mode — a **hysteresis**
 * slider (the ±band). The heating/cooling **direction is preserved** from the existing config, so a
 * plain user never has to set the thresholds "backwards". The current sensor reading is shown on top.
 */
@Composable
private fun ThermostatDialog(
    name: String,
    sensor: Sensor,
    onTenths: Int,
    offTenths: Int,
    expertMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (onTenths: Int, offTenths: Int) -> Unit,
) {
    val unit = sensor.unit()
    val isHumidity = sensor.kind == SensorKind.HUMIDITY
    // on <= off → heating (turn on below target, off above); preserved across the edit.
    val heating = onTenths <= offTenths
    val initialTarget = (onTenths + offTenths) / 20f
    val initialHyst = (abs(offTenths - onTenths) / 20f).coerceAtLeast(0.1f)

    var target by remember { mutableStateOf(initialTarget) }
    var hyst by remember { mutableStateOf(initialHyst) }

    // Slider bounds follow the unit (web-theme defaults), expanded if the current values fall outside.
    val tMin = if (isHumidity) 0f else minOf(5f, initialTarget - 1f)
    val tMax = if (isHumidity) 100f else maxOf(40f, initialTarget + 1f)
    val hMax = if (isHumidity) 30f else maxOf(10f, initialHyst + 0.5f)

    val onU = if (heating) target - hyst else target + hyst
    val offU = if (heating) target + hyst else target - hyst

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Jelenleg: ${formatSensor(sensor)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Üzemmód: ${if (heating) "fűtés" else "hűtés"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Kívánt érték: %.1f %s".format(target, unit))
                Slider(
                    value = target,
                    onValueChange = { target = (it * 10).roundToInt() / 10f },
                    valueRange = tMin..tMax,
                )

                if (expertMode) {
                    Text("Hiszterézis: ±%.1f %s".format(hyst, unit))
                    Slider(
                        value = hyst,
                        onValueChange = { hyst = (it * 10).roundToInt() / 10f },
                        valueRange = 0.1f..hMax,
                    )
                }

                Text(
                    "Bekapcsol %.1f, kikapcsol %.1f %s".format(onU, offU, unit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm((onU * 10).roundToInt(), (offU * 10).roundToInt())
            }) { Text("Mentés") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}

/** UI descriptor for one controlled output, carrying its controlling sensor and setpoint command. */
private class ControlledItem(
    val key: String,
    val name: String,
    val value: String,
    val sensor: Sensor,
    val onTenths: Int,
    val offTenths: Int,
    val writable: Boolean,
    val apply: (onTenths: Int, offTenths: Int) -> Unit,
) {
    val unit: String get() = sensor.unit()
}

/**
 * The controlling temperature/humidity sensor for an activator id, or null for non-thermostat ones.
 * `0` and `255` are the firmware's "no thermostat" sentinels (the official web theme guards both:
 * `activator > 0 && activator < 255`). The sensor cross-reference below already excludes them
 * implicitly (no real sensor has id 0 or 255), but we guard 255 explicitly as belt-and-suspenders
 * against a firmware that might report it alongside a stray sensor entry.
 */
private fun ServerState.controllingSensor(sensorId: Int): Sensor? =
    if (sensorId <= 0 || sensorId >= 255) null
    else sensors.firstOrNull { it.id == sensorId && (it.kind == SensorKind.TEMPERATURE || it.kind == SensorKind.HUMIDITY) }

/** True if any output is genuinely thermostat-controlled (drives whether the Termosztát tab shows). */
internal fun ServerState.hasControlledOutputs(): Boolean =
    relays.any { controllingSensor(it.sensorId) != null } ||
        pcas.any { controllingSensor(it.sensorId) != null } ||
        pwms.any { controllingSensor(it.sensorId) != null }

private fun ServerState.controlledItems(actions: MyioActions): List<ControlledItem> = buildList {
    relays.forEach { r ->
        val sensor = controllingSensor(r.sensorId) ?: return@forEach
        add(
            ControlledItem("r${r.id}", r.name.ifBlank { "Relé ${r.id}" }, if (r.on) "BE" else "KI",
                sensor, r.sensorOnTenths, r.sensorOffTenths, r.writable) { on, off -> actions.onSetRelayThermostat(r, on, off) },
        )
    }
    pcas.forEach { p ->
        val sensor = controllingSensor(p.sensorId) ?: return@forEach
        val v = if (p.isPwm) "${percent(p.state)}%" else if (p.on) "BE" else "KI"
        add(
            ControlledItem("p${p.id}", p.name.ifBlank { "PCA ${p.id}" }, v,
                sensor, p.sensorOnTenths, p.sensorOffTenths, p.writable) { on, off -> actions.onSetPcaThermostat(p, on, off) },
        )
    }
    pwms.forEach { w ->
        val sensor = controllingSensor(w.sensorId) ?: return@forEach
        add(
            ControlledItem("w${w.id}", w.name.ifBlank { "Dimmer ${w.id}" }, "${percent(w.state)}%",
                sensor, w.sensorOnTenths, w.sensorOffTenths, w.writable) { on, off -> actions.onSetPwmThermostat(w, on, off) },
        )
    }
}

private fun Sensor.unit(): String = if (kind == SensorKind.HUMIDITY) "%" else "°C"

private fun thresholdSummary(onTenths: Int, offTenths: Int, unit: String): String {
    val target = (onTenths + offTenths) / 20.0
    val hysteresis = abs(offTenths - onTenths) / 20.0
    return "Cél %.1f %s · ±%.1f · %s".format(target, unit, hysteresis, modeWord(onTenths, offTenths))
}

private fun modeWord(onTenths: Int, offTenths: Int): String = when {
    onTenths < offTenths -> "fűtés"
    offTenths < onTenths -> "hűtés"
    else -> "—"
}
