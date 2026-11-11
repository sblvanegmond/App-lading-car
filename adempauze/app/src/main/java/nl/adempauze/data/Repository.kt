package nl.adempauze.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.adempauze.core.AccessPass
import nl.adempauze.core.DayCounts
import nl.adempauze.core.GuardedApp
import nl.adempauze.core.PauseOutcome
import nl.adempauze.core.Settings
import java.time.LocalDate

/**
 * Alles wat Adempauze bewaart, in één plek. Het staat in SharedPreferences op
 * de telefoon; er gaat niets naar internet. De hoofdschermen en de
 * achtergronddienst draaien in hetzelfde proces en delen deze instantie.
 */
class Repository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("adempauze", Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow(Codec.decodeApps(prefs.getString(KEY_APPS, null)))
    val apps: StateFlow<List<GuardedApp>> = _apps.asStateFlow()

    private val _settings = MutableStateFlow(Codec.decodeSettings(prefs.getString(KEY_SETTINGS, null)))
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _stats = MutableStateFlow(Codec.decodeStats(prefs.getString(KEY_STATS, null)))
    val stats: StateFlow<Map<LocalDate, Map<String, DayCounts>>> = _stats.asStateFlow()

    private var passes: Map<String, AccessPass> = Codec.decodePasses(prefs.getString(KEY_PASSES, null))

    // --- Apps -----------------------------------------------------------------

    fun app(packageName: String): GuardedApp? = _apps.value.firstOrNull { it.packageName == packageName }

    @Synchronized
    fun addApps(newApps: List<GuardedApp>) {
        val existing = _apps.value.map { it.packageName }.toSet()
        saveApps(_apps.value + newApps.filter { it.packageName !in existing })
    }

    @Synchronized
    fun updateApp(app: GuardedApp) {
        saveApps(_apps.value.map { if (it.packageName == app.packageName) app else it })
    }

    @Synchronized
    fun removeApp(packageName: String) {
        saveApps(_apps.value.filterNot { it.packageName == packageName })
        clearPass(packageName)
    }

    private fun saveApps(list: List<GuardedApp>) {
        val sorted = list.sortedBy { it.label.lowercase() }
        _apps.value = sorted
        prefs.edit { putString(KEY_APPS, Codec.encodeApps(sorted)) }
    }

    // --- Instellingen -----------------------------------------------------------

    @Synchronized
    fun updateSettings(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit { putString(KEY_SETTINGS, Codec.encodeSettings(next)) }
    }

    // --- Statistieken -----------------------------------------------------------

    fun counts(packageName: String, date: LocalDate = LocalDate.now()): DayCounts =
        _stats.value[date]?.get(packageName) ?: DayCounts()

    fun totals(date: LocalDate = LocalDate.now()): DayCounts =
        _stats.value[date]?.values?.fold(DayCounts()) { acc, c -> acc + c } ?: DayCounts()

    @Synchronized
    fun record(packageName: String, outcome: PauseOutcome, date: LocalDate = LocalDate.now()) {
        val day = _stats.value[date].orEmpty()
        val c = day[packageName] ?: DayCounts()
        val updated = when (outcome) {
            PauseOutcome.ATTEMPT -> c.copy(attempts = c.attempts + 1)
            PauseOutcome.OPENED -> c.copy(opened = c.opened + 1)
            PauseOutcome.DISMISSED -> c.copy(dismissed = c.dismissed + 1)
        }
        val cutoff = date.minusDays(STATS_DAYS_KEPT)
        val next = (_stats.value + (date to (day + (packageName to updated))))
            .filterKeys { it.isAfter(cutoff) }
        _stats.value = next
        prefs.edit { putString(KEY_STATS, Codec.encodeStats(next)) }
    }

    // --- Toegangspassen -----------------------------------------------------------

    @Synchronized
    fun pass(packageName: String): AccessPass? = passes[packageName]

    @Synchronized
    fun allPasses(): Map<String, AccessPass> = passes

    @Synchronized
    fun grantPass(packageName: String, minutes: Int, now: Long = System.currentTimeMillis()): AccessPass {
        val pass = AccessPass(now + minutes * 60_000L, minutes)
        passes = passes.filterValues { it.untilMillis > now } + (packageName to pass)
        prefs.edit { putString(KEY_PASSES, Codec.encodePasses(passes)) }
        return pass
    }

    @Synchronized
    fun clearPass(packageName: String) {
        if (packageName !in passes) return
        passes = passes - packageName
        prefs.edit { putString(KEY_PASSES, Codec.encodePasses(passes)) }
    }

    // --- Eerste keer ----------------------------------------------------------------

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING, value) }

    companion object {
        private const val KEY_APPS = "apps"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_STATS = "stats"
        private const val KEY_PASSES = "passes"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val STATS_DAYS_KEPT = 90L
    }
}
