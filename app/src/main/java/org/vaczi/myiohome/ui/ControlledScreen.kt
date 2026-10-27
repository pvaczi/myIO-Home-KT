package org.vaczi.myiohome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.vaczi.myiohome.data.model.Sensor
import org.vaczi.myiohome.data.model.SensorKind
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.outputKey
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
fun ControlledList(
    state: ServerState,
    actions: MyioActions,
    enabled: Boolean,
    expertMode: Boolean,
    /** Output keys to exclude from display (non-expert hidden set); empty in expert mode. */
    hiddenKeys: Set<String> = emptySet(),
) {
    val items = remember(state, hiddenKeys) { state.controlledItems(actions).filter { it.key !in hiddenKeys } }

    if (items.isEmpty()) {
        EmptyListHint("Nincs termosztát-vezérelt kimenet")
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
                    thresholdSummary(item.onTenths, item.offTenths, item.unit, item.sensor.kind == SensorKind.HUMIDITY),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.value == "BE") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!item.writable) {
                    LockBadge()
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
 * Setpoint editor in the original app's mental model: the user adjusts a **target** (desired
 * temperature/humidity = the midpoint of the on/off band) and — only in expert mode — a **hysteresis**
 * (±band) with precise −/+ steppers (0.1 per tap, hold to repeat), like the factory app's NumberPickers.
 * The heating/cooling **direction is preserved** from the existing config, so a plain user never has to
 * set the thresholds "backwards". The current sensor reading is shown on top. Values are kept in tenths
 * (integer) throughout, so there is no float drift on round-trip.
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
    // Target and hysteresis are shown rounded to a tenth, but the edit is kept as a shift of the device's
    // own thresholds: rounding them back would rewrite an odd band on a plain Save (225/230 → 225/231).
    val initialTargetT = ((onTenths + offTenths) / 2.0).roundToInt()
    // Not floored at 1: a zero-width device band must show ±0.0, or the readout runs a tenth ahead of
    // what is written. The stepper's floor is 1 only for a band that is already wider.
    val initialHystT = (abs(offTenths - onTenths) / 2.0).roundToInt()
    val hMinT = minOf(1, initialHystT)

    var targetShift by remember { mutableStateOf(0) }
    var hystShift by remember { mutableStateOf(0) }
    val targetT = initialTargetT + targetShift
    val hystT = initialHystT + hystShift

    // Step bounds in tenths follow the unit (web-theme defaults), expanded if current values fall outside.
    val tMinT = if (isHumidity) 0 else minOf(50, initialTargetT - 10)
    val tMaxT = if (isHumidity) 1000 else maxOf(400, initialTargetT + 10)
    val hMaxT = if (isHumidity) 300 else maxOf(100, initialHystT + 5)

    val (onT, offT) = shiftedThresholds(onTenths, offTenths, targetShift, hystShift)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Jelenleg: ${formatSensor(sensor)}", style = MaterialTheme.typography.titleMedium)
                // Live: follows the thresholds as edited, so swapping their order flips the mode.
                Text(
                    "Üzemmód: ${thermostatModeLabel(onT, offT, isHumidity)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Stepper("Kívánt érték", targetT, unit) { dir ->
                    targetShift = (targetT + dir).coerceIn(tMinT, tMaxT) - initialTargetT
                }
                if (expertMode) {
                    Stepper("Hiszterézis (±)", hystT, unit) { dir ->
                        hystShift = (hystT + dir).coerceIn(hMinT, hMaxT) - initialHystT
                    }
                } else {
                    // Not editable for a plain user, but still worth seeing — show it read-only.
                    ReadOnlyValue("Hiszterézis (±)", hystT, unit)
                }

                Text(
                    "Bekapcsol %.1f, kikapcsol %.1f %s".format(onT / 10.0, offT / 10.0, unit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!thresholdsValid(onT, offT)) {
                    Text(EQUAL_THRESHOLDS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            // Unchanged → nothing to write to the device; equal thresholds are never saved.
            TextButton(
                enabled = thresholdsValid(onT, offT),
                onClick = { if (targetShift == 0 && hystShift == 0) onDismiss() else onConfirm(onT, offT) },
            ) {
                Text("Mentés")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}

/**
 * The thresholds after moving the target by [targetShift] and widening the band by [hystShift] on each
 * side (tenths). Applied to the device's own [onTenths]/[offTenths], so a zero shift returns them exactly.
 * Heating (on ≤ off) widens by lowering on and raising off; cooling the reverse. Pure → unit-testable.
 */
internal fun shiftedThresholds(onTenths: Int, offTenths: Int, targetShift: Int, hystShift: Int): Pair<Int, Int> {
    val heating = onTenths <= offTenths
    val onT = onTenths + targetShift + if (heating) -hystShift else hystShift
    val offT = offTenths + targetShift + if (heating) hystShift else -hystShift
    return onT to offT
}

/**
 * Precise value editor: large readout flanked by −/+ buttons. One tap = ±0.1; press-and-hold repeats
 * (accelerating after a short delay) so large jumps are still quick — the feel of the factory app's wheel.
 * [valueTenths] is in tenths of the unit; [onStep] receives the direction (−1 / +1) for one 0.1 step.
 */
@Composable
private fun Stepper(label: String, valueTenths: Int, unit: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RepeatIconButton(Icons.Filled.Remove, "Csökkentés") { onStep(-1) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.1f %s".format(valueTenths / 10.0, unit), style = MaterialTheme.typography.headlineMedium)
        }
        RepeatIconButton(Icons.Filled.Add, "Növelés") { onStep(1) }
    }
}

/** Read-only counterpart of [Stepper]: same centered label + value, dimmed, no −/+ buttons. */
@Composable
private fun ReadOnlyValue(label: String, valueTenths: Int, unit: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "%.1f %s".format(valueTenths / 10.0, unit),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Icon button that fires once on tap and repeats while held (350 ms initial delay, then every 60 ms). */
@Composable
private fun RepeatIconButton(icon: ImageVector, contentDescription: String, onStep: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    LaunchedEffect(pressed.value) {
        if (pressed.value) {
            onStep()
            delay(350)
            while (pressed.value) {
                onStep()
                delay(60)
            }
        }
    }
    FilledTonalIconButton(onClick = {}, interactionSource = interaction) {
        Icon(icon, contentDescription = contentDescription)
    }
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
            ControlledItem(r.outputKey(), r.name.ifBlank { "Relé ${r.id}" }, if (r.on) "BE" else "KI",
                sensor, r.sensorOnTenths, r.sensorOffTenths, r.writable) { on, off -> actions.onSetRelayThermostat(r, on, off) },
        )
    }
    pcas.forEach { p ->
        val sensor = controllingSensor(p.sensorId) ?: return@forEach
        val v = if (p.isPwm) "${percent(p.state)}%" else if (p.on) "BE" else "KI"
        add(
            ControlledItem(p.outputKey(), p.name.ifBlank { "PCA ${p.id}" }, v,
                sensor, p.sensorOnTenths, p.sensorOffTenths, p.writable) { on, off -> actions.onSetPcaThermostat(p, on, off) },
        )
    }
    pwms.forEach { w ->
        val sensor = controllingSensor(w.sensorId) ?: return@forEach
        add(
            ControlledItem(w.outputKey(), w.name.ifBlank { "Dimmer ${w.id}" }, "${percent(w.state)}%",
                sensor, w.sensorOnTenths, w.sensorOffTenths, w.writable) { on, off -> actions.onSetPwmThermostat(w, on, off) },
        )
    }
}

private fun Sensor.unit(): String = if (kind == SensorKind.HUMIDITY) "%" else "°C"

private fun thresholdSummary(onTenths: Int, offTenths: Int, unit: String, humidity: Boolean): String {
    val target = (onTenths + offTenths) / 20.0
    val hysteresis = abs(offTenths - onTenths) / 20.0
    return "Cél %.1f %s · ±%.1f · %s".format(target, unit, hysteresis, thermostatModeLabel(onTenths, offTenths, humidity))
}
