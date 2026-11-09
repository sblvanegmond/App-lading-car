package nl.adempauze.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import nl.adempauze.core.Category
import nl.adempauze.core.GuardedApp
import nl.adempauze.core.Policy
import nl.adempauze.core.Schedule
import nl.adempauze.repository
import nl.adempauze.ui.pause.PauseActivity
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(packageName: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = context.repository
    val apps by repo.apps.collectAsState()
    val settings by repo.settings.collectAsState()
    val stats by repo.stats.collectAsState()
    val app = apps.firstOrNull { it.packageName == packageName }
    if (app == null) {
        // Net verwijderd.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val update: (GuardedApp) -> Unit = { repo.updateApp(it) }
    var confirmOff by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    val today = stats[java.time.LocalDate.now()]?.get(packageName)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(app.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Terug") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.packageName, 52.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Adempauze", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (today == null || today.attempts == 0) "Vandaag nog geen pauzes"
                            else "Vandaag ${pauzes(today.attempts)} · ${today.dismissed}× gelaten · ${today.opened}× geopend",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = app.enabled,
                        onCheckedChange = { on -> if (on) update(app.copy(enabled = true)) else confirmOff = true },
                    )
                }
            }

            SectionCard(title = "Wachttijd") {
                Text(
                    "${app.waitSeconds} seconden",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    breathsText(app.waitSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = app.waitSeconds.toFloat(),
                    onValueChange = { v ->
                        val s = ((v / 5f).roundToInt() * 5).coerceIn(GuardedApp.MIN_WAIT_SECONDS, GuardedApp.MAX_WAIT_SECONDS)
                        if (s != app.waitSeconds) update(app.copy(waitSeconds = s))
                    },
                    valueRange = GuardedApp.MIN_WAIT_SECONDS.toFloat()..GuardedApp.MAX_WAIT_SECONDS.toFloat(),
                    steps = (GuardedApp.MAX_WAIT_SECONDS - GuardedApp.MIN_WAIT_SECONDS) / 5 - 1,
                )
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    title = "Langer bij elke volgende keer",
                    text = "Elke keer dat je ${app.label} vandaag opende, komen er ${Policy.ESCALATION_STEP_SECONDS} seconden bij (tot ${GuardedApp.MAX_WAIT_SECONDS} s). Nu zou het ${Policy.waitSeconds(app, today?.opened ?: 0)} seconden zijn.",
                    checked = app.escalate,
                    onChange = { update(app.copy(escalate = it)) },
                )
            }

            SectionCard(title = "Wanneer") {
                Schedule.entries.forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = app.schedule == s, role = Role.RadioButton) { update(app.copy(schedule = s)) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = app.schedule == s, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(s.label, style = MaterialTheme.typography.bodyLarge)
                            Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Werktijd: ${describeWorkHours(settings.workHours)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) { Text("Wijzigen") }
                }
            }

            SectionCard(title = "Teksten") {
                Row(
                    Modifier.fillMaxWidth().clickable { pickCategory = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Soort app", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${app.category.label}: bepaalt welke vragen je ziet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Wijzigen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                Text("Eigen zinnen", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Komen vaker voorbij dan de ingebouwde teksten. Je kunt {app} en {tijd} gebruiken.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                app.customMessages.forEachIndexed { index, line ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text("“$line”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            update(app.copy(customMessages = app.customMessages.filterIndexed { i, _ -> i != index }))
                        }) { Icon(Icons.Rounded.Close, contentDescription = "Verwijder zin") }
                    }
                }
                var draft by rememberSaveable(packageName) { mutableStateOf("") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(160) },
                    placeholder = { Text(customPlaceholder(app.category)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                update(app.copy(customMessages = app.customMessages + draft.trim()))
                                draft = ""
                            },
                            enabled = draft.isNotBlank(),
                        ) { Text("Voeg toe") }
                    },
                )
            }

            FilledTonalButton(
                onClick = {
                    context.startActivity(PauseActivity.intent(context, app.packageName, PauseActivity.Mode.PREVIEW))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Probeer het pauzescherm")
            }
            TextButton(
                onClick = { confirmRemove = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Verwijderen uit Adempauze", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmOff) {
        HoldOnDialog(
            title = "Pauze voor ${app.label} uitzetten?",
            text = "Dan opent ${app.label} weer meteen, zonder adempauze. Neem even de tijd om te voelen of je dat echt wilt.",
            confirmLabel = "Uitzetten",
            onConfirm = {
                update(app.copy(enabled = false))
                confirmOff = false
            },
            onDismiss = { confirmOff = false },
        )
    }
    if (confirmRemove) {
        HoldOnDialog(
            title = "${app.label} verwijderen?",
            text = "Je instellingen en eigen zinnen voor ${app.label} verdwijnen. Je statistieken blijven bewaard.",
            confirmLabel = "Verwijderen",
            onConfirm = {
                confirmRemove = false
                repo.removeApp(app.packageName)
            },
            onDismiss = { confirmRemove = false },
        )
    }
    if (pickCategory) {
        AlertDialog(
            onDismissRequest = { pickCategory = false },
            title = { Text("Soort app") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Category.entries.forEach { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = app.category == c, role = Role.RadioButton) {
                                    update(app.copy(category = c))
                                    pickCategory = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = app.category == c, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(c.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickCategory = false }) { Text("Sluiten") } },
        )
    }
}

/** De wachttijd uitgedrukt in ademhalingen van tien seconden. */
private fun breathsText(seconds: Int): String = when {
    seconds == 10 -> "Precies één rustige ademhaling: 4 tellen in, 6 tellen uit."
    seconds < 10 -> "Een halve ademhaling. Tip: met 10 seconden doe je er precies één."
    seconds % 10 == 0 -> "${seconds / 10} rustige ademhalingen van 10 seconden."
    else -> "${seconds / 10},5 ademhalingen van 10 seconden."
}

private fun customPlaceholder(category: Category): String = when (category) {
    Category.VIDEO -> "Je wilde vanavond toch lezen?"
    Category.SOCIAL -> "Bel liever even een vriend."
    Category.WORK_MAIL, Category.MAIL -> "Morgen om 9 uur is vroeg genoeg."
    Category.SHOPPING -> "Je spaart voor de vakantie."
    else -> "Wat wil je eigenlijk doen?"
}

@Composable
fun ToggleRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
