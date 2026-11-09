package nl.adempauze.ui.pause

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.adempauze.core.Category
import nl.adempauze.core.GuardedApp
import nl.adempauze.core.MessageContext
import nl.adempauze.core.Messages
import nl.adempauze.core.PauseOutcome
import nl.adempauze.core.Policy
import nl.adempauze.repository
import nl.adempauze.service.GuardService
import nl.adempauze.ui.theme.AdempauzeTheme
import nl.adempauze.util.InstalledApps
import java.time.LocalDateTime

/**
 * Het pauzescherm. De toegankelijkheidsdienst start het bovenop de app die je
 * opent. Na de ademhaling kies je: laten (naar het startscherm) of openen
 * voor een aantal minuten.
 */
class PauseActivity : ComponentActivity() {

    enum class Mode { OPEN, TIME_UP, PREVIEW }

    private var ui by mutableStateOf<PauseUi?>(null)
    private var celebrate by mutableStateOf<Int?>(null)
    private var decided = false
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ui = build(intent, record = savedInstanceState == null)
        if (ui == null) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this) { dismiss() }
        setContent {
            val current = ui ?: return@setContent
            AdempauzeTheme(dark = true) {
                key(current.id) {
                    PauseScreen(
                        ui = current,
                        celebrateCount = celebrate,
                        onOpen = ::open,
                        onDismiss = ::dismiss,
                        onCelebrationDone = ::leave,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = build(intent, record = true) ?: return
        decided = false
        leaving = false
        celebrate = null
        ui = next
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing || isChangingConfigurations) return
        // Scherm uit? Dan laten we het pauzescherm gewoon staan.
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) return
        // Weggeswiped of op de thuisknop gedrukt: dat telt als "niet geopend".
        val current = ui
        if (!decided && current != null && current.mode != Mode.PREVIEW) {
            repository.record(current.packageName, PauseOutcome.DISMISSED)
        }
        decided = true
        finish()
    }

    private fun open(minutes: Int) {
        val current = ui ?: return
        if (decided) return
        decided = true
        if (current.mode != Mode.PREVIEW) {
            repository.record(current.packageName, PauseOutcome.OPENED)
            val pass = repository.grantPass(current.packageName, minutes)
            GuardService.onOpened(current.packageName, pass)
        }
        // De app ligt er al onder; door dit scherm te sluiten zie je hem weer.
        finish()
    }

    private fun dismiss() {
        val current = ui ?: return
        if (decided) {
            leave()
            return
        }
        decided = true
        if (current.mode != Mode.PREVIEW) {
            repository.record(current.packageName, PauseOutcome.DISMISSED)
            repository.clearPass(current.packageName)
            GuardService.onDismissed(current.packageName)
        }
        celebrate = repository.totals().dismissed.coerceAtLeast(1)
    }

    /** Na het "goed gekozen"-scherm: naar het startscherm, niet terug naar de app. */
    private fun leave() {
        if (leaving) return
        leaving = true
        if (ui?.mode != Mode.PREVIEW) GuardService.goHome(this)
        finish()
    }

    private fun build(intent: Intent, record: Boolean): PauseUi? {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return null
        val mode = runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") }.getOrDefault(Mode.OPEN)
        val minutesUsed = intent.getIntExtra(EXTRA_MINUTES, 0)
        val repo = repository
        val app = repo.app(pkg)
            ?: if (mode == Mode.PREVIEW) {
                GuardedApp(pkg, InstalledApps.label(this, pkg) ?: pkg, category = Category.OTHER)
            } else {
                return null
            }
        if (record && mode != Mode.PREVIEW) repo.record(pkg, PauseOutcome.ATTEMPT)

        val settings = repo.settings.value
        val now = LocalDateTime.now()
        val counts = repo.counts(pkg)
        val ctx = MessageContext(
            appLabel = app.label,
            category = app.category,
            now = now,
            attemptsToday = counts.attempts.coerceAtLeast(1),
            openedToday = counts.opened,
            isWorkTime = Policy.isWorkTime(settings.workHours, now),
            customMessages = app.customMessages,
        )
        return PauseUi(
            id = System.nanoTime(),
            packageName = pkg,
            appLabel = app.label,
            mode = mode,
            waitSeconds = Policy.waitSeconds(app, counts.opened),
            message = if (mode == Mode.TIME_UP) Messages.pickTimeUp(ctx) else Messages.pick(ctx),
            attemptsToday = ctx.attemptsToday,
            openedToday = counts.opened,
            sessionMinutes = settings.sessionMinutes,
            minutesUsed = minutesUsed,
            haptics = settings.haptics,
            hour = now.hour,
        )
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_MINUTES = "minutes"

        fun intent(context: Context, packageName: String, mode: Mode, minutesUsed: Int = 0): Intent =
            Intent(context, PauseActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_MINUTES, minutesUsed)
    }
}
