package org.vaczi.myiohome.data.model

/**
 * Stable, type-prefixed key identifying one output/sensor across the app — used as the zone-assignment
 * key (see ZoneConfig). A bare domain id isn't unique (a relay id can collide with a sensor id), so we
 * prefix by type: R=relay, P=PCA, F=PWM/FET, S+kind-initial=sensor (e.g. "ST3" = temperature sensor 3).
 */
fun RelayOutput.outputKey(): String = "R$id"
fun PcaOutput.outputKey(): String = "P$id"
fun PwmOutput.outputKey(): String = "F$id"
fun Sensor.outputKey(): String = "S${kind.name.first()}$id"
