package org.vaczi.myiohome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * Full-screen output search (name / type / id) with the "Csak a bekapcsoltak" filter. Results are the live
 * control tiles (same composables as the Otthon dashboard), each routed to its own server, so they stay
 * operable and update with polling; with an empty query and the chip on it is the "Bekapcsolt kimenetek"
 * live view. [sources] is rebuilt from the live state on every recomposition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    sources: List<SearchSource>,
    actionsFor: (serverId: String) -> MyioActions,
    coverActionsFor: (serverId: String) -> CoverActions,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlyOn by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onClose)
    val results = remember(sources, query, onlyOn) { searchOutputs(sources, query, onlyOn) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza")
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Keresés név, típus vagy azonosító szerint") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Törlés")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilterChip(
                selected = onlyOn,
                onClick = { onlyOn = !onlyOn },
                label = { Text("Csak a bekapcsoltak") },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            when {
                query.isBlank() && !onlyOn ->
                    EmptyListHint("Írj be egy nevet, típust (relé, PCA, PWM, redőny, szenzor) vagy azonosítót, vagy kapcsold be a „Csak a bekapcsoltak” szűrőt")
                results.isEmpty() ->
                    EmptyListHint(if (onlyOn) "Nincs találat a bekapcsoltak között" else "Nincs találat")
                else -> TwoColumnLazyGrid {
                    gridItems(results, key = { "${it.serverId}/${it.tile.key}" }) { r ->
                        HomeTileCell(r.tile, actionsFor(r.serverId), coverActionsFor(r.serverId), r.enabled)
                    }
                }
            }
        }
    }
}
