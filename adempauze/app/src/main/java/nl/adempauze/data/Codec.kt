package nl.adempauze.data

import nl.adempauze.core.AccessPass
import nl.adempauze.core.Category
import nl.adempauze.core.DayCounts
import nl.adempauze.core.GuardedApp
import nl.adempauze.core.Schedule
import nl.adempauze.core.Settings
import nl.adempauze.core.WorkHours
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Omzetten van en naar JSON voor de opslag. Onbekende of kapotte velden vallen
 * terug op de standaardwaarde, zodat een oude of beschadigde opslag de app
 * nooit laat crashen.
 */
object Codec {

    fun encodeApps(apps: List<GuardedApp>): String = JSONArray().apply {
        apps.forEach { app ->
            put(JSONObject().apply {
                put("package", app.packageName)
                put("label", app.label)
                put("enabled", app.enabled)
                put("wait", app.waitSeconds)
                put("escalate", app.escalate)
                put("schedule", app.schedule.name)
                put("category", app.category.name)
                put("custom", JSONArray(app.customMessages))
            })
        }
    }.toString()

    fun decodeApps(json: String?): List<GuardedApp> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val pkg = o.optString("package").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val custom = o.optJSONArray("custom")
                GuardedApp(
                    packageName = pkg,
                    label = o.optString("label", pkg),
                    enabled = o.optBoolean("enabled", true),
                    waitSeconds = o.optInt("wait", GuardedApp.DEFAULT_WAIT_SECONDS)
                        .coerceIn(GuardedApp.MIN_WAIT_SECONDS, GuardedApp.MAX_WAIT_SECONDS),
                    escalate = o.optBoolean("escalate", false),
                    schedule = enumOr(o.optString("schedule"), Schedule.ALWAYS),
                    category = enumOr(o.optString("category"), Category.OTHER),
                    customMessages = if (custom == null) emptyList() else
                        (0 until custom.length()).map { custom.optString(it) }.filter { it.isNotBlank() },
                )
            }.distinctBy { it.packageName }
        }.getOrDefault(emptyList())
    }

    fun encodeSettings(s: Settings): String = JSONObject().apply {
        put("workDays", JSONArray(s.workHours.days.map { it.value }.sorted()))
        put("workStart", s.workHours.startMinute)
        put("workEnd", s.workHours.endMinute)
        put("haptics", s.haptics)
        put("sessions", JSONArray(s.sessionMinutes))
    }.toString()

    fun decodeSettings(json: String?): Settings {
        val defaults = Settings()
        if (json.isNullOrBlank()) return defaults
        return runCatching {
            val o = JSONObject(json)
            val days = o.optJSONArray("workDays")?.let { a ->
                (0 until a.length()).mapNotNull { runCatching { DayOfWeek.of(a.getInt(it)) }.getOrNull() }.toSet()
            } ?: defaults.workHours.days
            val sessions = o.optJSONArray("sessions")?.let { a ->
                (0 until a.length()).map { a.optInt(it) }.filter { it in 1..240 }
            }?.takeIf { it.isNotEmpty() } ?: defaults.sessionMinutes
            Settings(
                workHours = WorkHours(
                    days = days,
                    startMinute = o.optInt("workStart", defaults.workHours.startMinute).coerceIn(0, 1439),
                    endMinute = o.optInt("workEnd", defaults.workHours.endMinute).coerceIn(0, 1439),
                ),
                haptics = o.optBoolean("haptics", defaults.haptics),
                sessionMinutes = sessions,
            )
        }.getOrDefault(defaults)
    }

    /** Statistieken: { "2026-11-09": { "com.google.android.youtube": [pogingen, geopend, gelaten] } } */
    fun encodeStats(stats: Map<LocalDate, Map<String, DayCounts>>): String = JSONObject().apply {
        stats.forEach { (date, perApp) ->
            put(date.toString(), JSONObject().apply {
                perApp.forEach { (pkg, c) -> put(pkg, JSONArray(listOf(c.attempts, c.opened, c.dismissed))) }
            })
        }
    }.toString()

    fun decodeStats(json: String?): Map<LocalDate, Map<String, DayCounts>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            buildMap {
                o.keys().forEach { key ->
                    val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
                    val perApp = o.optJSONObject(key) ?: return@forEach
                    put(date, buildMap {
                        perApp.keys().forEach { pkg ->
                            val a = perApp.optJSONArray(pkg) ?: return@forEach
                            put(pkg, DayCounts(a.optInt(0), a.optInt(1), a.optInt(2)))
                        }
                    })
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun encodePasses(passes: Map<String, AccessPass>): String = JSONObject().apply {
        passes.forEach { (pkg, p) ->
            put(pkg, JSONObject().apply {
                put("until", p.untilMillis)
                put("minutes", p.minutes)
            })
        }
    }.toString()

    fun decodePasses(json: String?): Map<String, AccessPass> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            buildMap {
                o.keys().forEach { pkg ->
                    val p = o.optJSONObject(pkg) ?: return@forEach
                    put(pkg, AccessPass(p.optLong("until"), p.optInt("minutes")))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
