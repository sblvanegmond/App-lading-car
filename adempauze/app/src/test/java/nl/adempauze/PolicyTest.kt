package nl.adempauze

import android.content.pm.ApplicationInfo
import nl.adempauze.core.Category
import nl.adempauze.core.GuardedApp
import nl.adempauze.core.Policy
import nl.adempauze.core.Schedule
import nl.adempauze.core.WorkHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class PolicyTest {
    private val youtube = GuardedApp("com.google.android.youtube", "YouTube", category = Category.VIDEO)
    private val work = WorkHours()

    // 9 november 2026 is een maandag.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute)

    @Test
    fun standaardWachttijdIsTienSeconden() {
        assertEquals(10, Policy.waitSeconds(youtube, openedToday = 7))
    }

    @Test
    fun oplopendeWachttijdTeltVijfSecondenPerKeerOpenen() {
        val app = youtube.copy(escalate = true)
        assertEquals(10, Policy.waitSeconds(app, 0))
        assertEquals(15, Policy.waitSeconds(app, 1))
        assertEquals(30, Policy.waitSeconds(app, 4))
        assertEquals(60, Policy.waitSeconds(app, 50))
    }

    @Test
    fun werktijdOpWerkdag() {
        assertTrue(Policy.isWorkTime(work, at(9, 9, 0)))
        assertTrue(Policy.isWorkTime(work, at(9, 17, 29)))
        assertFalse(Policy.isWorkTime(work, at(9, 17, 30)))
        assertFalse(Policy.isWorkTime(work, at(9, 8, 59)))
    }

    @Test
    fun weekendIsGeenWerktijd() {
        assertEquals(DayOfWeek.SATURDAY, at(14, 11).dayOfWeek)
        assertFalse(Policy.isWorkTime(work, at(14, 11)))
    }

    @Test
    fun nachtdienstLooptOverMiddernacht() {
        val night = WorkHours(days = setOf(DayOfWeek.MONDAY), startMinute = 22 * 60, endMinute = 6 * 60)
        assertTrue(Policy.isWorkTime(night, at(9, 23)))  // maandagavond
        assertTrue(Policy.isWorkTime(night, at(10, 3)))  // dinsdagnacht hoort bij maandag
        assertFalse(Policy.isWorkTime(night, at(10, 23))) // dinsdagavond niet
    }

    @Test
    fun werkmailAlleenBuitenWerktijd() {
        val mail = GuardedApp("com.microsoft.office.outlook", "Outlook", schedule = Schedule.OUTSIDE_WORK)
        assertFalse(Policy.isActive(mail, work, at(9, 10)))
        assertTrue(Policy.isActive(mail, work, at(9, 21)))
        assertTrue(Policy.isActive(mail, work, at(14, 10)))
    }

    @Test
    fun uitgezetteAppKrijgtGeenPauze() {
        assertFalse(Policy.isActive(youtube.copy(enabled = false), work, at(9, 12)))
    }

    @Test
    fun soortAppHerkennen() {
        assertEquals(Category.VIDEO, Policy.detectCategory("com.google.android.youtube", "YouTube", ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals(Category.WORK_MAIL, Policy.detectCategory("com.microsoft.office.outlook", "Outlook", ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertEquals(Category.WORK_MAIL, Policy.detectCategory("nl.bedrijf.app", "Werkmail", ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals(Category.GAMES, Policy.detectCategory("com.king.candy", "Candy", ApplicationInfo.CATEGORY_GAME))
        assertEquals(Category.NEWS, Policy.detectCategory("nl.x", "Het Nieuws", ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals(Category.OTHER, Policy.detectCategory("nl.x", "Rekenmachine", ApplicationInfo.CATEGORY_UNDEFINED))
    }
}
