package org.vaczi.myiohome.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.vaczi.myiohome.data.local.AppTheme

/**
 * App-level settings: choose the theme (persisted) and jump to the server-slot editor.
 * Reached from the top-bar gear icon. Server *switching* lives on the main screen (tap the title);
 * this screen is for configuration that isn't part of day-to-day control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    theme: AppTheme,
    onSelectTheme: (AppTheme) -> Unit,
    onEditServers: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Beállítások") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Téma")
            ThemeOption("Zöld (eredeti)", "A klasszikus myIO zöldes megjelenés", AppTheme.GREEN, theme, onSelectTheme)
            ThemeOption("Dinamikus / kék", "Material You – a háttérképből vett színek (Android 12+)", AppTheme.DYNAMIC, theme, onSelectTheme)

            HorizontalDivider()

            SectionHeader("Szerverek")
            ListItem(
                headlineContent = { Text("Szerverek szerkesztése…") },
                supportingContent = { Text("Slotok hozzáadása, módosítása, törlése") },
                leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = false,
                    role = Role.Button,
                    onClick = onEditServers,
                ),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A single-choice theme row (M3 RadioButton list pattern). The whole row is the touch target. */
@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    value: AppTheme,
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
) {
    val isSelected = value == selected
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            RadioButton(selected = isSelected, onClick = null) // row handles the click
        },
        modifier = Modifier.fillMaxWidth().selectable(
            selected = isSelected,
            role = Role.RadioButton,
            onClick = { onSelect(value) },
        ),
    )
}
