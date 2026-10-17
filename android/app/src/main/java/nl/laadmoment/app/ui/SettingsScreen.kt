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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import nl.laadmoment.app.widget.LaadWidget
import nl.laadmoment.core.Fmt
import nl.laadmoment.core.PriceSource
import nl.laadmoment.core.Pricing
import nl.laadmoment.core.RoofPlane
import nl.laadmoment.core.Settings
import nl.laadmoment.core.Snapshot
import nl.laadmoment.core.parseClock
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.math.abs

private typealias Update = ((Settings) -> Settings) -> Unit

@Composable
fun SettingsScreen(snapshot: Snapshot, onUpdate: Update, onBack: () -> Unit) {
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
                Section("🚗  Auto") {
                    val car = settings.car
                    NumberField("Accucapaciteit", car.batteryKwh, "kWh", "De bruikbare capaciteit, uit de handleiding.", min = 1.0, max = 250.0) { v ->
                        onUpdate { it.copy(car = it.car.copy(batteryKwh = v)) }
                    }
                    NumberField("Laadvermogen", car.chargerKw, "kW", "Wat laadpaal én auto samen halen.", min = 0.5, max = 50.0) { v ->
                        onUpdate { it.copy(car = it.car.copy(chargerKw = v)) }
                    }
                    NumberField("Minimaal laadvermogen", car.minChargeKw, "kW", "Hieronder weigert de auto. 6 A op één fase is ongeveer 1,4 kW.", max = 20.0) { v ->
                        onUpdate { it.copy(car = it.car.copy(minChargeKw = v)) }
                    }
                    NumberField("Laadverlies", car.chargeLossPct, "%", "Bij wisselstroom is 8 tot 12 procent normaal.", max = 40.0) { v ->
                        onUpdate { it.copy(car = it.car.copy(chargeLossPct = v)) }
                    }
                }
            }

            item {
                Section("☀️  Zonnepanelen") {
                    settings.pv.arrays.forEachIndexed { index, plane ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PlaneEditor(index, plane) { changed ->
                            onUpdate { s ->
                                val arrays = s.pv.arrays.toMutableList()
                                if (index < arrays.size) arrays[index] = changed
                                s.copy(pv = s.pv.copy(arrays = arrays))
                            }
                        }
                    }
                    SwitchRow(
                        "Tweede dakvlak",
                        settings.pv.arrays.size > 1,
                        "Bijvoorbeeld panelen op oost én west.",
                    ) { on ->
                        onUpdate { s ->
                            val first = s.pv.arrays.firstOrNull() ?: RoofPlane(label = "Dakvlak 1")
                            val arrays = if (on) listOf(first, RoofPlane(label = "Dakvlak 2", kWp = 2.0, tilt = 35.0, azimuth = 90.0)) else listOf(first)
                            s.copy(pv = s.pv.copy(arrays = arrays))
                        }
                    }
                    NumberField("Omvormer maximaal", settings.pv.inverterKw, "kW", "Het AC-vermogen van je omvormer. 0 als hij niet begrenst.", max = 100.0) { v ->
                        onUpdate { it.copy(pv = it.pv.copy(inverterKw = v)) }
                    }
                    NumberField("Systeemrendement", settings.pv.performanceRatio * 100, "%", "Klopt de opbrengst structureel niet, stel dit bij.", min = 30.0, max = 100.0) { v ->
                        onUpdate { it.copy(pv = it.pv.copy(performanceRatio = v / 100)) }
                    }
                    NumberField("Basisverbruik huis", settings.houseBaseLoadKw, "kW", "Gaat van de zon af voordat de auto aan de beurt is.", max = 20.0) { v ->
                        onUpdate { it.copy(houseBaseLoadKw = v) }
                    }
                }
            }

            item {
                Section("💶  Tarieven") {
                    val t = settings.tariffs
                    Hint("Bedragen per kWh, exclusief btw, zoals ze in je contract staan.")
                    NumberField("Opslag leverancier", t.supplierMarkupPerKwh, "€/kWh", max = 1.0) { v ->
                        onUpdate { it.copy(tariffs = it.tariffs.copy(supplierMarkupPerKwh = v)) }
                    }
                    NumberField("Energiebelasting", t.energyTaxPerKwh, "€/kWh", "In 2026 is dat € 0,0916. Controleer het elk jaar in januari.", max = 1.0) { v ->
                        onUpdate { it.copy(tariffs = it.tariffs.copy(energyTaxPerKwh = v)) }
                    }
                    NumberField("Btw", t.vatPct, "%", max = 50.0) { v ->
                        onUpdate { it.copy(tariffs = it.tariffs.copy(vatPct = v)) }
                    }
                    NumberField("Terugleververgoeding", t.feedInPerKwh, "€/kWh", "Wat je krijgt voor een kWh die je teruglevert.", min = -1.0, max = 1.0) { v ->
                        onUpdate { it.copy(tariffs = it.tariffs.copy(feedInPerKwh = v)) }
                    }
                    SwitchRow(
                        "Saldering geldt nog voor mij",
                        t.netMetering,
                        "Dan levert zon in de auto niets extra's op: een teruggeleverde kWh streept weg tegen een gekochte. " +
                            "De salderingsregeling stopt op 1 januari 2027.",
                    ) { on -> onUpdate { it.copy(tariffs = it.tariffs.copy(netMetering = on)) } }
                    Hint("Voorbeeld: een marktprijs van 8 ct wordt bij jou ${Fmt.cents(Pricing.allInPrice(0.08, t))} all-in.")
                }
            }

            item {
                Section("📍  Locatie") {
                    TextInput("Plaats", settings.location.name) { name -> onUpdate { it.copy(location = it.location.copy(name = name)) } }
                    NumberField("Breedtegraad", settings.location.latitude, "°N", min = -90.0, max = 90.0) { v ->
                        onUpdate { it.copy(location = it.location.copy(latitude = v)) }
                    }
                    NumberField("Lengtegraad", settings.location.longitude, "°O", min = -180.0, max = 180.0) { v ->
                        onUpdate { it.copy(location = it.location.copy(longitude = v)) }
                    }
                    Hint("Standaard Grootegast. Voor de zon maakt een paar kilometer niets uit.")
                }
            }

            item {
                Section("📈  Prijsbron") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PriceSource.entries.forEach { source ->
                            FilterChip(
                                colors = chipColors(),
                                selected = settings.priceSource == source,
                                onClick = { onUpdate { it.copy(priceSource = source) } },
                                label = { Text(source.label) },
                            )
                        }
                    }
                    Hint("Beide geven de Nederlandse day-ahead prijzen. Geeft de ene niets, dan springt de andere in.")
                }
            }

            item {
                Section("🔔  Meldingen") {
                    SwitchRow("Ochtendoverzicht", settings.notifications.morningEnabled, "Elke ochtend de beste laaduren van de dag.") { on ->
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
                    SwitchRow("Seintje bij het beste moment", settings.notifications.windowReminder, "Een kwartier voordat het beste laadvenster begint.") { on ->
                        if (on) ensureNotificationPermission()
                        onUpdate { it.copy(notifications = it.notifications.copy(windowReminder = on)) }
                    }
                }
            }

            item {
                Section("ℹ️  Over") {
                    Hint("Laadmoment ${versionName(context)}. Alles wordt op je telefoon uitgerekend; alleen je locatie gaat naar de weerdienst.")
                    Hint("Prijzen: EnergyZero en Energy-Charts (Fraunhofer ISE). Weer: Open-Meteo.")
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

/**
 * A number field that accepts a comma or a dot, and only passes on values
 * that parse and fall inside the range; while typing "0," nothing changes.
 */
@Composable
private fun NumberField(
    label: String,
    value: Double,
    unit: String,
    hint: String? = null,
    min: Double = 0.0,
    max: Double = 100_000.0,
    onValue: (Double) -> Unit,
) {
    var text by remember(label) { mutableStateOf(plain(value)) }
    val parsed = parse(text)
    val valid = parsed != null && parsed in min..max
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            val number = parse(next)
            if (number != null && number in min..max) onValue(number)
        },
        label = { Text(label) },
        suffix = { Text(unit) },
        isError = !valid,
        supportingText = when {
            !valid -> ({ Text("Vul een getal in tussen ${plain(min)} en ${plain(max)}.") })
            hint != null -> ({ Text(hint) })
            else -> null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextInput(label: String, value: String, onValue: (String) -> Unit) {
    var text by remember(label) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it.trim()) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val DIRECTIONS = listOf(
    "Zuid" to 0.0,
    "Zuidwest" to 45.0,
    "West" to 90.0,
    "Noordwest" to 135.0,
    "Noord" to 180.0,
    "Noordoost" to -135.0,
    "Oost" to -90.0,
    "Zuidoost" to -45.0,
)

@Composable
private fun PlaneEditor(index: Int, plane: RoofPlane, onChange: (RoofPlane) -> Unit) {
    Text(if (index == 0) "Dakvlak 1" else "Dakvlak ${index + 1}", style = MaterialTheme.typography.labelLarge)
    NumberField("Vermogen", plane.kWp, "kWp", "Het piekvermogen van de panelen op dit vlak.", max = 100.0) { onChange(plane.copy(kWp = it)) }
    NumberField("Hellingshoek", plane.tilt, "°", "0 is een plat dak, 35 is een gewoon schuin dak.", max = 90.0) { onChange(plane.copy(tilt = it)) }
    var open by remember { mutableStateOf(false) }
    val current = DIRECTIONS.minBy { angleBetween(it.second, plane.azimuth) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("Richting: ${current.first}")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DIRECTIONS.forEach { (name, azimuth) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    open = false
                    onChange(plane.copy(azimuth = azimuth))
                })
            }
        }
    }
}

private fun angleBetween(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
}

private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

private fun plain(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',')

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

/** The platform time picker, in 24-hour format. */
internal fun pickTime(context: Context, current: String, onPick: (String) -> Unit) {
    val time = parseClock(current) ?: LocalTime.of(7, 0)
    TimePickerDialog(context, { _, hour, minute -> onPick("%02d:%02d".format(hour, minute)) }, time.hour, time.minute, true).show()
}
