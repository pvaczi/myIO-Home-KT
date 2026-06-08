package org.vaczi.myiohome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
fun ControlledList(state: ServerState, actions: MyioActions, enabled: Boolean) {
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
            ControlledRow(item = item, enabled = enabled, onEdit = { editing = item })
            HorizontalDivider()
        }
    }

    editing?.let { item ->
        ThermostatDialog(
            name = item.name,
            unit = item.unit,
            onTenths = item.onTenths,
            offTenths = item.offTenths,
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
                IconButton(onClick = onEdit, enabled = enabled) {
                    Icon(Icons.Filled.Edit, contentDescription = "Küszöbök szerkesztése")
                }
            }
        },
        modifier = if (enabled) Modifier.clickable(onClick = onEdit) else Modifier,
    )
}

@Composable
private fun ThermostatDialog(
    name: String,
    unit: String,
    onTenths: Int,
    offTenths: Int,
    onDismiss: () -> Unit,
    onConfirm: (onTenths: Int, offTenths: Int) -> Unit,
) {
    var on by remember { mutableStateOf(tenthsToText(onTenths)) }
    var off by remember { mutableStateOf(tenthsToText(offTenths)) }
    val onValue = on.toValueOrNull()
    val offValue = off.toValueOrNull()
    val valid = onValue != null && offValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = on,
                    onValueChange = { on = it.filterDecimal() },
                    label = { Text("Bekapcsolás ($unit)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = off,
                    onValueChange = { off = it.filterDecimal() },
                    label = { Text("Kikapcsolás ($unit)") },
                    singleLine = true,
                )
                if (onValue != null && offValue != null) {
                    Text(
                        text = modeLabel((onValue * 10).roundToInt(), (offValue * 10).roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm((onValue!! * 10).roundToInt(), (offValue!! * 10).roundToInt()) },
            ) { Text("Mentés") }
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
    val apply: (onTenths: Int, offTenths: Int) -> Unit,
) {
    val unit: String get() = sensor.unit()
}

/** The controlling temperature/humidity sensor for an activator id, or null for non-thermostat ones. */
private fun ServerState.controllingSensor(sensorId: Int): Sensor? =
    sensors.firstOrNull { it.id == sensorId && (it.kind == SensorKind.TEMPERATURE || it.kind == SensorKind.HUMIDITY) }

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
                sensor, r.sensorOnTenths, r.sensorOffTenths) { on, off -> actions.onSetRelayThermostat(r, on, off) },
        )
    }
    pcas.forEach { p ->
        val sensor = controllingSensor(p.sensorId) ?: return@forEach
        val v = if (p.isPwm) "${percent(p.state)}%" else if (p.on) "BE" else "KI"
        add(
            ControlledItem("p${p.id}", p.name.ifBlank { "PCA ${p.id}" }, v,
                sensor, p.sensorOnTenths, p.sensorOffTenths) { on, off -> actions.onSetPcaThermostat(p, on, off) },
        )
    }
    pwms.forEach { w ->
        val sensor = controllingSensor(w.sensorId) ?: return@forEach
        add(
            ControlledItem("w${w.id}", w.name.ifBlank { "Dimmer ${w.id}" }, "${percent(w.state)}%",
                sensor, w.sensorOnTenths, w.sensorOffTenths) { on, off -> actions.onSetPwmThermostat(w, on, off) },
        )
    }
}

private fun Sensor.unit(): String = if (kind == SensorKind.HUMIDITY) "%" else "°C"

private fun thresholdSummary(onTenths: Int, offTenths: Int, unit: String): String {
    val target = (onTenths + offTenths) / 20.0
    val hysteresis = abs(offTenths - onTenths) / 10.0
    return "BE %.1f → KI %.1f %s · cél %.1f · hiszt. %.1f (%s)".format(
        onTenths / 10.0, offTenths / 10.0, unit, target, hysteresis, modeWord(onTenths, offTenths),
    )
}

private fun modeLabel(onTenths: Int, offTenths: Int): String = "Üzemmód: ${modeWord(onTenths, offTenths)}"

private fun modeWord(onTenths: Int, offTenths: Int): String = when {
    onTenths < offTenths -> "fűtés"
    offTenths < onTenths -> "hűtés"
    else -> "—"
}

private fun tenthsToText(tenths: Int): String = "%.1f".format(tenths / 10.0)

private fun String.toValueOrNull(): Double? = replace(',', '.').toDoubleOrNull()

private fun String.filterDecimal(): String = replace(',', '.').filter { it.isDigit() || it == '.' }
