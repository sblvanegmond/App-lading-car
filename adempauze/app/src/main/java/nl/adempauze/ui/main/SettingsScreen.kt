package nl.adempauze.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.adempauze.BuildConfigInfo
import nl.adempauze.repository
import nl.adempauze.service.GuardService
import nl.adempauze.util.Permissions
import java.time.DayOfWeek

private val SESSION_CHOICES = listOf(1, 2, 5, 10, 15, 20, 30, 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(resumeTick: Int, onBack: () -> Unit, onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val repo = context.repository
    val settings by repo.settings.collectAsState()
    val running by GuardService.running.collectAsState()
    val enabled = remember(resumeTick, running) { Permissions.isServiceEnabled(context) }
    var editTime by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Instellingen") },
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
            SectionCard(title = "Werktijd") {
                Text(
                    "Gebruikt bij apps die je alleen buiten of juist onder werktijd wilt pauzeren, zoals je werkmail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val on = day in settings.workHours.days
                        FilterChip(
                            selected = on,
                            onClick = {
                                repo.updateSettings {
                                    val days = if (on) it.workHours.days - day else it.workHours.days + day
                                    it.copy(workHours = it.workHours.copy(days = days))
                                }
                            },
                            label = { Text(dayShort.getValue(day)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeBox("Van", settings.workHours.startMinute, Modifier.weight(1f)) { editTime = "start" }
                    TimeBox("Tot", settings.workHours.endMinute, Modifier.weight(1f)) { editTime = "end" }
                }
            }

            SectionCard(title = "Keuzes op het pauzescherm") {
                Text(
                    "Hoe lang je een app kunt openen. Kies er één tot vier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SESSION_CHOICES.forEach { m ->
                        val on = m in settings.sessionMinutes
                        FilterChip(
                            selected = on,
                            onClick = {
                                repo.updateSettings {
                                    val next = if (on) it.sessionMinutes - m else it.sessionMinutes + m
                                    if (next.isEmpty() || next.size > 4) it else it.copy(sessionMinutes = next.sorted())
                                }
                            },
                            label = { Text("$m min") },
                        )
                    }
                }
            }

            SectionCard(title = "Gevoel") {
                ToggleRow(
                    title = "Trillen bij ademhaling",
                    text = "Een zacht tikje bij elke wissel tussen in- en uitademen.",
                    checked = settings.haptics,
                    onChange = { on -> repo.updateSettings { it.copy(haptics = on) } },
                )
            }

            SectionCard(title = "Toestemmingen") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (enabled) "Toegankelijkheid staat aan" else "Toegankelijkheid staat uit",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenSetup) { Text("Instellen en uitleg") }
            }

            SectionCard(title = "Privacy") {
                Text(
                    "Adempauze heeft geen internettoegang en geen account. Het ziet alleen de naam van de app die vooraan komt, " +
                        "leest niets op je scherm en bewaart alles op je telefoon.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Versie ${BuildConfigInfo.versionName(context)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    when (editTime) {
        "start" -> TimeDialog("Werkdag begint", settings.workHours.startMinute, onPick = { m ->
            repo.updateSettings { it.copy(workHours = it.workHours.copy(startMinute = m)) }
            editTime = null
        }, onDismiss = { editTime = null })
        "end" -> TimeDialog("Werkdag eindigt", settings.workHours.endMinute, onPick = { m ->
            repo.updateSettings { it.copy(workHours = it.workHours.copy(endMinute = m)) }
            editTime = null
        }, onDismiss = { editTime = null })
    }
}

@Composable
private fun TimeBox(label: String, minute: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatMinute(minute), style = MaterialTheme.typography.titleLarge)
    }
}

// --- Instellen -------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(resumeTick: Int, onBack: () -> Unit, forceEnabled: Boolean? = null) {
    val context = LocalContext.current
    val running by GuardService.running.collectAsState()
    val enabled = forceEnabled ?: remember(resumeTick, running) { Permissions.isServiceEnabled(context) }
    val batteryOk = forceEnabled ?: remember(resumeTick) { Permissions.isIgnoringBatteryOptimizations(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Adempauze instellen") },
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
            Text(
                "Adempauze gebruikt de toegankelijkheidsfunctie van Android om te zien wélke app je opent. " +
                    "Het leest geen tekst op je scherm en heeft geen internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            if (enabled) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Adempauze staat aan", style = MaterialTheme.typography.titleMedium)
                            Text("Open een van je apps om het te proberen.", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = onBack) { Text("Klaar") }
                    }
                }
            }

            SetupStep(
                number = 1,
                icon = Icons.Rounded.AccessibilityNew,
                done = enabled,
                title = "Zet Adempauze aan bij Toegankelijkheid",
                text = "Instellingen › Toegankelijkheid › Geïnstalleerde apps › Adempauze › aanzetten en toestaan.",
                action = if (enabled) null else "Toegankelijkheid openen",
                onAction = { Permissions.openAccessibilitySettings(context) },
            )
            SetupStep(
                number = 2,
                icon = Icons.Rounded.Lock,
                done = enabled,
                title = "Melding ‘Beperkte instelling’?",
                text = "Omdat je Adempauze zelf hebt geïnstalleerd, houdt Android de schakelaar eerst tegen. " +
                    "Open App-info, tik rechtsboven op ⋮ en kies ‘Beperkte instellingen toestaan’. Doe daarna stap 1 opnieuw.\n\n" +
                    "Zie je die optie niet? Probeer dan eerst stap 1; de optie verschijnt pas nadat Android de schakelaar één keer heeft tegengehouden.",
                action = if (enabled) null else "App-info openen",
                onAction = { Permissions.openAppInfo(context) },
            )
            SetupStep(
                number = 3,
                icon = Icons.Rounded.BatteryChargingFull,
                done = batteryOk,
                title = "Batterij: onbeperkt (aangeraden)",
                text = "Samsung zet apps die je weinig opent soms ‘in slaap’. Met onbeperkt batterijgebruik blijft Adempauze altijd werken. " +
                    "Het kost vrijwel geen stroom: de app doet alleen iets als je een app opent.",
                action = if (batteryOk) null else "Toestaan",
                onAction = { Permissions.requestIgnoreBatteryOptimizations(context) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    icon: ImageVector,
    done: Boolean,
    title: String,
    text: String,
    action: String?,
    onAction: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Klaar", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                } else {
                    Text(number.toString(), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (action != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}
