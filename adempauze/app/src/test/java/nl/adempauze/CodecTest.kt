package nl.adempauze

import nl.adempauze.core.AccessPass
import nl.adempauze.core.Category
import nl.adempauze.core.DayCounts
import nl.adempauze.core.GuardedApp
import nl.adempauze.core.Schedule
import nl.adempauze.core.Settings
import nl.adempauze.core.WorkHours
import nl.adempauze.data.Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class CodecTest {
    @Test
    fun appsHeenEnTerug() {
        val apps = listOf(
            GuardedApp("com.google.android.youtube", "YouTube", waitSeconds = 15, escalate = true, category = Category.VIDEO,
                customMessages = listOf("Lezen!", "Naar buiten")),
            GuardedApp("com.microsoft.office.outlook", "Outlook", enabled = false, schedule = Schedule.OUTSIDE_WORK,
                category = Category.WORK_MAIL),
        )
        assertEquals(apps, Codec.decodeApps(Codec.encodeApps(apps)))
    }

    @Test
    fun instellingenHeenEnTerug() {
        val s = Settings(WorkHours(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 8 * 60, 12 * 60), haptics = false, sessionMinutes = listOf(2, 10))
        assertEquals(s, Codec.decodeSettings(Codec.encodeSettings(s)))
    }

    @Test
    fun statistiekenEnPassenHeenEnTerug() {
        val stats = mapOf(LocalDate.of(2026, 11, 9) to mapOf("a" to DayCounts(5, 2, 3)))
        assertEquals(stats, Codec.decodeStats(Codec.encodeStats(stats)))
        val passes = mapOf("a" to AccessPass(123_456L, 5))
        assertEquals(passes, Codec.decodePasses(Codec.encodePasses(passes)))
    }

    @Test
    fun kapotteOpslagGeeftStandaardwaarden() {
        assertTrue(Codec.decodeApps("{kapot").isEmpty())
        assertEquals(Settings(), Codec.decodeSettings("[]"))
        assertTrue(Codec.decodeStats("nee").isEmpty())
        val clamped = Codec.decodeApps("""[{"package":"x","wait":999,"schedule":"ONBEKEND"}]""").single()
        assertEquals(GuardedApp.MAX_WAIT_SECONDS, clamped.waitSeconds)
        assertEquals(Schedule.ALWAYS, clamped.schedule)
    }
}
