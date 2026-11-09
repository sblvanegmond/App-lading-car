package nl.adempauze.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.adempauze.core.GuardedApp
import nl.adempauze.core.Policy
import nl.adempauze.repository
import nl.adempauze.util.InstalledApp
import nl.adempauze.util.InstalledApps

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    onBack: () -> Unit,
    onAdded: (List<String>) -> Unit,
    /** Voor de tests: een vaste lijst in plaats van de apps op het toestel. */
    preset: List<InstalledApp>? = null,
) {
    val context = LocalContext.current
    val repo = context.repository
    val guarded by repo.apps.collectAsState()
    val already = remember(guarded) { guarded.map { it.packageName }.toSet() }
    val installed by produceState<List<InstalledApp>?>(preset) {
        if (preset == null) value = withContext(Dispatchers.IO) { InstalledApps.load(context) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Apps kiezen") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Terug") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp)) {
                    Button(
                        onClick = {
                            val apps = installed.orEmpty().filter { it.packageName in selected }
                            repo.addApps(apps.map { GuardedApp(it.packageName, it.label, category = it.category) })
                            onAdded(apps.map { it.packageName })
                        },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text(
                            when (selected.size) {
                                0 -> "Kies een of meer apps"
                                1 -> "1 app toevoegen"
                                else -> "${selected.size} apps toevoegen"
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val list = installed
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val q = query.trim().lowercase()
        val visible = list.filter { q.isEmpty() || q in it.label.lowercase() }
        val suggested = if (q.isEmpty()) {
            visible.filter { it.packageName !in already && it.category in Policy.suggestedCategories && it.packageName in Policy.knownApps }
        } else {
            emptyList()
        }
        val toggle: (InstalledApp) -> Unit = { app ->
            if (app.packageName !in already) {
                selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName
            }
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Zoek een app") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (suggested.isNotEmpty()) {
                item { ListHeader("Voorgesteld") }
                items(suggested, key = { "s-" + it.packageName }) { app ->
                    PickerRow(app, checked = app.packageName in selected, disabled = false) { toggle(app) }
                }
                item { ListHeader("Alle apps") }
            }
            items(visible, key = { it.packageName }) { app ->
                val isAlready = app.packageName in already
                PickerRow(app, checked = isAlready || app.packageName in selected, disabled = isAlready) { toggle(app) }
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        "Geen app gevonden met “$query”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickerRow(app: InstalledApp, checked: Boolean, disabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (disabled) "Heeft al een adempauze" else app.category.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = if (disabled) null else { _ -> onClick() }, enabled = !disabled)
    }
}
