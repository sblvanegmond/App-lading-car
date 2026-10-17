@file:OptIn(ExperimentalMaterial3Api::class)

package nl.laadmoment.app.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import nl.laadmoment.app.widget.LaadWidget
import nl.laadmoment.core.Settings
import nl.laadmoment.core.Snapshot
import nl.laadmoment.core.parseClock
import java.time.LocalTime

@Composable
fun SettingsScreen(snapshot: Snapshot, onUpdate: ((Settings) -> Settings) -> Unit, onBack: () -> Unit) {
    val settings = snapshot.settings
    val context = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Zonder toestemming kan Laadmoment geen meldingen tonen.", Toast.LENGTH_LONG).show()
    }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Instellingen", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Section("📱  Widget op je beginscherm") {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val widthDp = maxWidth.value.toInt()
                        AndroidView(
                            factory = { FrameLayout(it) },
                            update = { frame ->
                                frame.removeAllViews()
                                frame.addView(LaadWidget.views(frame.context, snapshot, widthDp, 120).apply(frame.context, frame))
                            },
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                        )
                    }
                    if (LaadWidget.canPin(context)) {
                        Button(onClick = { LaadWidget.requestPin(context) }) { Text("Zet op beginscherm") }
                    }
                    Hint("Of houd je beginscherm ingedrukt → Widgets → Laadmoment. De widget ververst elk uur.")
                }
            }

            item {
                Section("🔔  Meldingen") {
                    SwitchRow("Ochtendoverzicht", settings.notifications.morningEnabled, "Elke ochtend je beste uren van de dag.") { on ->
                        if (on) ensureNotificationPermission()
                        onUpdate { it.copy(notifications = it.notifications.copy(morningEnabled = on)) }
                    }
                    if (settings.notifications.morningEnabled) {
                        OutlinedButton(onClick = {
                            pickTime(context, settings.notifications.morningTime) { time ->
                                onUpdate { it.copy(notifications = it.notifications.copy(morningTime = time)) }
                            }
                        }) { Text("Om ${settings.notifications.morningTime}") }
                    }
                    SwitchRow("Seintje voor je beste uren", settings.notifications.windowReminder, "Een kwartier voordat een blok beste uren begint.") { on ->
                        if (on) ensureNotificationPermission()
                        onUpdate { it.copy(notifications = it.notifications.copy(windowReminder = on)) }
                    }
                }
            }

            item {
                Section("ℹ️  Over") {
                    Hint("Laadmoment ${versionName(context)}, voor ${settings.location.name}.")
                    Hint("Prijzen: EnergyZero, met Energy-Charts (Fraunhofer ISE) als reserve. Weer en zon: Open-Meteo.")
                    Hint("De prijzen zijn inclusief energiebelasting, btw en opslag. Dat schuift alle uren evenveel op, dus de beste uren blijven dezelfde.")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Hint(hint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

/** The platform time picker, in 24-hour format. */
internal fun pickTime(context: Context, current: String, onPick: (String) -> Unit) {
    val time = parseClock(current) ?: LocalTime.of(7, 0)
    TimePickerDialog(context, { _, hour, minute -> onPick("%02d:%02d".format(hour, minute)) }, time.hour, time.minute, true).show()
}
