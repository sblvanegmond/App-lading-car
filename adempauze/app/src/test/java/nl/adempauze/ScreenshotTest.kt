package nl.adempauze

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import nl.adempauze.core.Category
import nl.adempauze.core.GuardedApp
import nl.adempauze.core.PauseOutcome
import nl.adempauze.core.Schedule
import nl.adempauze.ui.main.AppDetailScreen
import nl.adempauze.ui.main.AppPickerScreen
import nl.adempauze.ui.main.HomeScreen
import nl.adempauze.ui.main.SetupScreen
import nl.adempauze.ui.main.StatsScreen
import nl.adempauze.ui.main.WelcomeScreen
import nl.adempauze.ui.pause.PauseActivity
import nl.adempauze.ui.pause.PauseScreen
import nl.adempauze.ui.pause.PauseUi
import nl.adempauze.ui.theme.AdempauzeTheme
import nl.adempauze.util.InstalledApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Maakt schermafbeeldingen van alle schermen (app/build/screenshots).
 * Formaat ongeveer als een Galaxy S26.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val repo get() = ApplicationProvider.getApplicationContext<AdempauzeApp>().repository

    private fun shot(name: String, dark: Boolean = false, settleMs: Long? = null, content: @Composable () -> Unit) {
        if (settleMs != null) compose.mainClock.autoAdvance = false
        compose.setContent { AdempauzeTheme(dark = dark) { content() } }
        if (settleMs != null) compose.mainClock.advanceTimeBy(settleMs)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun pauseUi(
        label: String = "YouTube",
        mode: PauseActivity.Mode = PauseActivity.Mode.OPEN,
        message: String = "Eén video worden er al snel tien. Weet je welke je wilt zien?",
        hour: Int = 14,
        attempts: Int = 3,
        opened: Int = 1,
        minutesUsed: Int = 0,
    ) = PauseUi(
        id = 1, packageName = "com.google.android.youtube", appLabel = label, mode = mode, waitSeconds = 10,
        message = message, attemptsToday = attempts, openedToday = opened, sessionMinutes = listOf(1, 5, 15, 30),
        minutesUsed = minutesUsed, haptics = false, hour = hour,
    )

    private fun seed() {
        repo.addApps(
            listOf(
                GuardedApp("com.google.android.youtube", "YouTube", category = Category.VIDEO, escalate = true),
                GuardedApp("com.instagram.android", "Instagram", category = Category.SOCIAL, waitSeconds = 15),
                GuardedApp("com.microsoft.office.outlook", "Outlook", category = Category.WORK_MAIL, schedule = Schedule.OUTSIDE_WORK,
                    customMessages = listOf("Morgen om 9 uur is vroeg genoeg.")),
                GuardedApp("com.zhiliaoapp.musically", "TikTok", category = Category.SOCIAL, enabled = false),
            ),
        )
        val today = LocalDate.now()
        val pattern = listOf(
            "com.google.android.youtube" to listOf(9 to 5, 8 to 4, 7 to 4, 7 to 3, 6 to 2, 5 to 2, 4 to 1),
            "com.instagram.android" to listOf(6 to 4, 6 to 3, 5 to 3, 4 to 2, 4 to 1, 3 to 1, 2 to 0),
            "com.microsoft.office.outlook" to listOf(3 to 2, 2 to 1, 3 to 1, 1 to 0, 2 to 1, 1 to 0, 1 to 0),
        )
        pattern.forEach { (pkg, days) ->
            days.forEachIndexed { i, (attempts, opened) ->
                val date = today.minusDays((6 - i).toLong())
                repeat(attempts) { repo.record(pkg, PauseOutcome.ATTEMPT, date) }
                repeat(opened) { repo.record(pkg, PauseOutcome.OPENED, date) }
                repeat(attempts - opened) { repo.record(pkg, PauseOutcome.DISMISSED, date) }
            }
        }
        // Vorige week vaker, voor de vergelijking.
        repeat(40) { repo.record("com.google.android.youtube", PauseOutcome.ATTEMPT, today.minusDays(9)) }
    }

    @Test fun pauzeAdemIn() = shot("01_pauze_adem_in", settleMs = 1_000) {
        PauseScreen(pauseUi(), null, {}, {}, {}, frozenElapsedMs = 3_200)
    }

    @Test fun pauzeAdemUit() = shot("02_pauze_adem_uit", settleMs = 1_000) {
        PauseScreen(
            pauseUi(label = "Instagram", message = "De feed heeft geen bodem. Jij mag wel een grens hebben.", hour = 9, attempts = 1, opened = 0),
            null, {}, {}, {}, frozenElapsedMs = 7_000,
        )
    }

    @Test fun pauzeKeuze() = shot("03_pauze_keuze", settleMs = 2_000) {
        PauseScreen(
            pauseUi(
                label = "Outlook",
                message = "Het is 21:14. Je werkdag zit erop. Wat je nu leest, los je toch pas morgen op.",
                hour = 21, attempts = 2, opened = 0,
            ),
            null, {}, {}, {}, frozenElapsedMs = 12_000,
        )
    }

    @Test fun pauzeTijdIsOm() = shot("04_pauze_tijd_om", settleMs = 2_000) {
        PauseScreen(
            pauseUi(
                mode = PauseActivity.Mode.TIME_UP, minutesUsed = 15, hour = 23, attempts = 6, opened = 4,
                message = "Het is al 23:41. Je slaap is meer waard dan nog even YouTube.",
            ),
            null, {}, {}, {}, frozenElapsedMs = 1_500,
        )
    }

    @Test fun pauzeGoedGekozen() = shot("05_pauze_goed_gekozen", settleMs = 900) {
        PauseScreen(pauseUi(), celebrateCount = 4, {}, {}, {}, frozenElapsedMs = 12_000)
    }

    @Test fun welkom() = shot("06_welkom", settleMs = 2_000) {
        WelcomeScreen(onStart = {})
    }

    @Test fun hoofdscherm() {
        seed()
        shot("07_hoofdscherm") { HomeScreen(0, {}, {}, {}, {}, {}, forceServiceEnabled = true) }
    }

    @Test fun hoofdschermDonker() {
        seed()
        shot("08_hoofdscherm_donker", dark = true) { HomeScreen(0, {}, {}, {}, {}, {}, forceServiceEnabled = true) }
    }

    @Test fun hoofdschermNogInstellen() = shot("09_hoofdscherm_leeg") {
        HomeScreen(0, {}, {}, {}, {}, {}, forceServiceEnabled = false)
    }

    @Test fun appKiezen() {
        repo.addApps(listOf(GuardedApp("com.whatsapp", "WhatsApp", category = Category.MESSAGING)))
        val apps = listOf(
            InstalledApp("com.android.chrome", "Chrome", Category.BROWSER),
            InstalledApp("com.instagram.android", "Instagram", Category.SOCIAL),
            InstalledApp("nl.nos.app", "NOS", Category.NEWS),
            InstalledApp("com.microsoft.office.outlook", "Outlook", Category.WORK_MAIL),
            InstalledApp("com.sec.android.app.camera", "Camera", Category.OTHER),
            InstalledApp("com.spotify.music", "Spotify", Category.OTHER),
            InstalledApp("com.whatsapp", "WhatsApp", Category.MESSAGING),
            InstalledApp("com.google.android.youtube", "YouTube", Category.VIDEO),
        )
        shot("10_apps_kiezen") { AppPickerScreen(onBack = {}, onAdded = {}, preset = apps) }
    }

    @Test fun appInstellingen() {
        seed()
        shot("11_app_instellingen") { AppDetailScreen("com.microsoft.office.outlook", {}, {}) }
    }

    @Test fun statistieken() {
        seed()
        shot("12_statistieken") { StatsScreen(onBack = {}) }
    }

    @Test fun statistiekenDonker() {
        seed()
        shot("13_statistieken_donker", dark = true) { StatsScreen(onBack = {}) }
    }

    @Test fun instellen() = shot("14_instellen") {
        SetupScreen(resumeTick = 0, onBack = {}, forceEnabled = false)
    }
}
