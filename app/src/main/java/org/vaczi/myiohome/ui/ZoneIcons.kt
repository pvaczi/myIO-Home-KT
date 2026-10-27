package org.vaczi.myiohome.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Deck
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HotTub
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Light
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Yard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One selectable zone icon: a stable persisted [key], a Hungarian [label] (accessibility) and the [vector]. */
internal data class ZoneIconOption(val key: String, val label: String, val vector: ImageVector)

/**
 * The fixed zone-icon set. Keys are persisted in [org.vaczi.myiohome.data.local.Zone.icon] — never rename
 * or reuse one. The first entry is the default (the old label icon, also used for a null/unknown key).
 */
internal object ZoneIcons {
    val default = ZoneIconOption("label", "Alapértelmezett", Icons.AutoMirrored.Filled.Label)

    val all: List<ZoneIconOption> = listOf(
        default,
        ZoneIconOption("living", "Nappali", Icons.Filled.Weekend),
        ZoneIconOption("bedroom", "Hálószoba", Icons.Filled.Bed),
        ZoneIconOption("kitchen", "Konyha", Icons.Filled.Kitchen),
        ZoneIconOption("bathroom", "Fürdőszoba", Icons.Filled.Bathtub),
        ZoneIconOption("office", "Iroda", Icons.Filled.Computer),
        ZoneIconOption("kids", "Gyerekszoba", Icons.Filled.ChildCare),
        ZoneIconOption("garage", "Garázs", Icons.Filled.DirectionsCar),
        ZoneIconOption("garden", "Kert", Icons.Filled.Yard),
        ZoneIconOption("terrace", "Terasz", Icons.Filled.Deck),
        ZoneIconOption("hall", "Lépcső, előtér", Icons.Filled.Stairs),
        ZoneIconOption("sauna", "Szauna", Icons.Filled.HotTub),
        ZoneIconOption("outdoor_light", "Kültéri világítás", Icons.Filled.Light),
    )

    private val byKey = all.associateBy { it.key }

    /** Pure lookup: null or unknown key → [default]. */
    fun forKey(key: String?): ZoneIconOption = byKey[key] ?: default
}

/** Icon vector for a zone's stored [key] (null/unknown → the default label icon). */
internal fun zoneIconVector(key: String?): ImageVector = ZoneIcons.forKey(key).vector

/** Grid of the fixed icon set; the selected one is highlighted. [selected] null means the default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZoneIconPicker(selected: String?, onSelect: (String?) -> Unit) {
    val current = ZoneIcons.forKey(selected).key
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZoneIcons.all.forEach { opt ->
            val isSel = opt.key == current
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    if (isSel) 2.dp else 1.dp,
                    if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.clickable { onSelect(if (opt === ZoneIcons.default) null else opt.key) },
            ) {
                Icon(opt.vector, contentDescription = opt.label, modifier = Modifier.padding(8.dp).size(24.dp))
            }
        }
    }
}
