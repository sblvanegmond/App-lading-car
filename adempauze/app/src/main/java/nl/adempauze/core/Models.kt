package nl.adempauze.core

import java.time.DayOfWeek

/** Soort app. Bepaalt welke teksten je op het pauzescherm ziet. */
enum class Category(val label: String) {
    VIDEO("Video"),
    SOCIAL("Sociale media"),
    WORK_MAIL("Werkmail en -chat"),
    MAIL("Mail"),
    MESSAGING("Berichten"),
    NEWS("Nieuws"),
    SHOPPING("Winkelen"),
    GAMES("Games"),
    BROWSER("Browser"),
    OTHER("Overig"),
}

/** Wanneer de pauze voor een app geldt. */
enum class Schedule(val label: String, val description: String) {
    ALWAYS("Altijd", "Elke keer dat je de app opent."),
    OUTSIDE_WORK("Buiten werktijd", "Alleen 's avonds, 's nachts en in het weekend. Handig voor werkmail."),
    DURING_WORK("Onder werktijd", "Alleen tijdens je werkuren. Handig voor YouTube of social media."),
}

/** Een app waarvoor je een adempauze hebt ingesteld. */
data class GuardedApp(
    val packageName: String,
    val label: String,
    val enabled: Boolean = true,
    val waitSeconds: Int = DEFAULT_WAIT_SECONDS,
    /** Elke keer dat je de app vandaag opende, duurt de pauze 5 seconden langer. */
    val escalate: Boolean = false,
    val schedule: Schedule = Schedule.ALWAYS,
    val category: Category = Category.OTHER,
    /** Eigen zinnen; die komen vaker voorbij dan de ingebouwde teksten. */
    val customMessages: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_WAIT_SECONDS = 10
        const val MIN_WAIT_SECONDS = 5
        const val MAX_WAIT_SECONDS = 60
    }
}

data class WorkHours(
    val days: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    ),
    /** Minuten na middernacht. */
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 17 * 60 + 30,
)

data class Settings(
    val workHours: WorkHours = WorkHours(),
    val haptics: Boolean = true,
    /** Keuzes op het pauzescherm: hoe lang je de app wilt gebruiken. */
    val sessionMinutes: List<Int> = listOf(1, 5, 15, 30),
)

/** Tellingen voor één app op één dag. */
data class DayCounts(
    val attempts: Int = 0,
    val opened: Int = 0,
    val dismissed: Int = 0,
) {
    operator fun plus(other: DayCounts) = DayCounts(
        attempts + other.attempts,
        opened + other.opened,
        dismissed + other.dismissed,
    )
}

enum class PauseOutcome { ATTEMPT, OPENED, DISMISSED }

/** Toestemming om een app tot een bepaald moment te gebruiken. */
data class AccessPass(val untilMillis: Long, val minutes: Int)
