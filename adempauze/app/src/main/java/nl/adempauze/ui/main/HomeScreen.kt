package nl.adempauze.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.adempauze.core.GuardedApp
import nl.adempauze.core.Policy
import nl.adempauze.core.Schedule
import nl.adempauze.repository
import nl.adempauze.service.GuardService
import nl.adempauze.ui.pause.Breath
import nl.adempauze.ui.pause.BreathingCircle
import nl.adempauze.ui.pause.PausePalette
import nl.adempauze.ui.theme.NumberStyle
import nl.adempauze.util.Permissions
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun HomeScreen(
    resumeTick: Int,
    onAddApps: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSetup: () -> Unit,
    /** Voor schermafbeeldingen: doe alsof de dienst aan staat. */
    forceServiceEnabled: Boolean? = null,
) {
    val context = LocalContext.current
    val repo = context.repository
    val apps by repo.apps.collectAsState()
    val stats by repo.stats.collectAsState()
    val settings by repo.settings.collectAsState()
    val running by GuardService.running.collectAsState()
    val serviceEnabled = forceServiceEnabled ?: remember(resumeTick, running) { Permissions.isServiceEnabled(context) }
    val batteryOk = forceServiceEnabled ?: remember(resumeTick) { Permissions.isIgnoringBatteryOptimizations(context) }

    val today = LocalDate.now()
    val todayStats = stats[today].orEmpty()
    val total = todayStats.values.fold(nl.adempauze.core.DayCounts()) { a, c -> a + c }

    var confirmOff by remember { mutableStateOf<GuardedApp?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.navigationBars,
        floatingActionButton = {
            if (apps.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAddApps,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("App toevoegen") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 20.dp, bottom = 8.dp, start = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            greeting(LocalTime.now().hour),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text("Adempauze", style = MaterialTheme.typography.displaySmall)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Instellingen")
                    }
                }
            }

            item {
                if (!serviceEnabled) {
                    SetupNeededCard(onOpenSetup)
                } else {
                    ActiveRow(batteryOk = batteryOk, onFixBattery = { Permissions.requestIgnoreBatteryOptimizations(context) })
                }
            }

            item { TodayCard(total.attempts, total.dismissed, total.opened, onOpenStats) }

            item {
                Text(
                    "Jouw apps",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                )
            }

            if (apps.isEmpty()) {
                item { EmptyApps(onAddApps) }
            } else {
                items(apps, key = { it.packageName }) { app ->
                    val c = todayStats[app.packageName]
                    GuardedAppRow(
                        app = app,
                        subtitle = buildString {
                            append(appSubtitle(app, settings.workHours))
                            if (c != null && c.attempts > 0) append(" · ${c.attempts}× vandaag")
                        },
                        onClick = { onOpenApp(app.packageName) },
                        onToggle = { on ->
                            if (on) repo.updateApp(app.copy(enabled = true)) else confirmOff = app
                        },
                    )
                }
            }
        }
    }

    confirmOff?.let { app ->
        HoldOnDialog(
            title = "Pauze voor ${app.label} uitzetten?",
            text = "Dan opent ${app.label} weer meteen, zonder adempauze. Neem even de tijd om te voelen of je dat echt wilt.",
            confirmLabel = "Uitzetten",
            onConfirm = {
                repo.updateApp(app.copy(enabled = false))
                confirmOff = null
            },
            onDismiss = { confirmOff = null },
        )
    }
}

fun appSubtitle(app: GuardedApp, work: nl.adempauze.core.WorkHours): String {
    if (!app.enabled) return "Uit"
    val wait = if (app.escalate) "${app.waitSeconds} s+" else "${app.waitSeconds} s"
    val schedule = when (app.schedule) {
        Schedule.ALWAYS -> "altijd"
        Schedule.OUTSIDE_WORK -> "buiten werktijd"
        Schedule.DURING_WORK -> "onder werktijd"
    }
    val activeNow = Policy.isActive(app, work, java.time.LocalDateTime.now())
    return if (app.schedule != Schedule.ALWAYS && !activeNow) "$wait · $schedule (nu niet)" else "$wait · $schedule"
}

@Composable
private fun SetupNeededCard(onOpenSetup: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Nog één stap", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Zet Adempauze aan bij Toegankelijkheid. Pas dan kan het een pauze laten zien als je een app opent.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSetup) { Text("Instellen") }
        }
    }
}

@Composable
private fun ActiveRow(batteryOk: Boolean, onFixBattery: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                "Actief: je apps krijgen een adempauze",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (!batteryOk) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(20.dp))
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Zet de batterij op onbeperkt, zodat Samsung Adempauze niet in slaap brengt.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFixBattery) { Text("Regelen") }
            }
        }
    }
}

@Composable
private fun TodayCard(attempts: Int, dismissed: Int, opened: Int, onOpenStats: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onOpenStats)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vandaag", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("Je week", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(dismissed.toString(), style = NumberStyle, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(10.dp))
            Text(
                "keer bewust\nniet geopend",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatPill(attempts.toString(), "pauzes")
            StatPill(opened.toString(), "geopend")
            StatPill(if (attempts == 0) "–" else "${(dismissed * 100) / attempts}%", "gelaten")
        }
    }
}

@Composable
private fun EmptyApps(onAddApps: () -> Unit) {
    SectionCard {
        Text(
            "Nog geen apps gekozen",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Kies de apps waar je minder tijd in wilt steken, zoals YouTube, Instagram of je werkmail.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddApps) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Apps kiezen")
        }
    }
}

@Composable
private fun GuardedAppRow(app: GuardedApp, subtitle: String, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = app.enabled, onCheckedChange = onToggle)
        }
    }
}

// --- Welkom -------------------------------------------------------------------------

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    val palette = remember { PausePalette.forHour(13) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsed = it - start }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .size(200.dp)
                .background(palette.top, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BreathingCircle(
                level = Breath.level(elapsed),
                progress = 0f,
                ringAlpha = 0f,
                palette = palette,
                label = if (Breath.isInhaling(elapsed)) "Adem in" else "Adem uit",
                modifier = Modifier.size(180.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("Adempauze", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Eén rustige ademhaling voordat je een app opent. Zo kies je bewust, in plaats van uit gewoonte.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        WelcomeStep(Icons.Rounded.TouchApp, "Kies je apps", "Bijvoorbeeld YouTube, Instagram of je werkmail.")
        WelcomeStep(Icons.Rounded.Spa, "Adem tien seconden", "Je ziet een ademhalingsoefening en een vraag die past bij de app en het moment.")
        WelcomeStep(Icons.Rounded.Timer, "Kies bewust", "Laat de app liggen, of open hem voor een paar minuten. Is je tijd om, dan vraagt Adempauze of je echt verder wilt.")
        Spacer(Modifier.height(12.dp))
        Text(
            "Onderzoek naar zo'n korte pauze (PNAS, 2023) liet zien dat mensen hun afleidende apps na zes weken gemiddeld 57% minder vaak openden.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                context.repository.onboardingDone = true
                onStart()
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Aan de slag", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WelcomeStep(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
