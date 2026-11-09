package nl.adempauze.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.os.HandlerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.adempauze.core.AccessPass
import nl.adempauze.core.Policy
import nl.adempauze.data.Repository
import nl.adempauze.repository
import nl.adempauze.ui.pause.PauseActivity
import java.time.LocalDateTime

/**
 * De toegankelijkheidsdienst. Hij krijgt van Android alleen een seintje als er
 * een ander venster op de voorgrond komt, met de naam van de app. Is dat een
 * app uit jouw lijst en heb je geen lopende pas, dan legt hij het pauzescherm
 * eroverheen.
 */
class GuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val interceptor = Interceptor()
    private val expiryTokens = mutableMapOf<String, Any>()
    private lateinit var repo: Repository

    private var ignored: Set<String> = emptySet()
    private var ignoredBuiltAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        repo = applicationContext.repository
        refreshIgnored()
        instance = this
        _running.value = true
        val now = System.currentTimeMillis()
        repo.allPasses().forEach { (pkg, pass) -> if (pass.untilMillis > now) scheduleExpiry(pkg, pass) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            interceptor.onOwnWindow(pkg)
            return
        }
        if (SystemClock.elapsedRealtime() - ignoredBuiltAt > IGNORED_REFRESH_MS) refreshIgnored()
        if (pkg in ignored) return

        val now = System.currentTimeMillis()
        val app = repo.app(pkg)
        val active = app != null && Policy.isActive(app, repo.settings.value.workHours, LocalDateTime.now())
        val passUntil = repo.pass(pkg)?.untilMillis ?: 0L
        if (interceptor.onForeground(pkg, active, passUntil, now)) {
            showPause(pkg, PauseActivity.Mode.OPEN, 0)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        _running.value = false
    }

    private fun showPause(pkg: String, mode: PauseActivity.Mode, minutes: Int) {
        val intent = PauseActivity.intent(this, pkg, mode, minutes)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        runCatching { startActivity(intent) }
    }

    /** Toetsenborden, systeembalk en dergelijke: die betekenen niet dat je van app wisselt. */
    private fun refreshIgnored() {
        val imes = runCatching {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.map { it.packageName }
                .orEmpty()
        }.getOrDefault(emptyList())
        ignored = ALWAYS_IGNORED + imes
        ignoredBuiltAt = SystemClock.elapsedRealtime()
    }

    private fun scheduleExpiry(pkg: String, pass: AccessPass) {
        expiryTokens[pkg]?.let { handler.removeCallbacksAndMessages(it) }
        val token = Any()
        expiryTokens[pkg] = token
        val delay = pass.untilMillis - System.currentTimeMillis()
        if (delay <= 0) return
        HandlerCompat.postDelayed(handler, { onPassExpired(pkg, pass.minutes) }, token, delay + 250)
    }

    /** Je ingestelde tijd is om. Zit je nog in de app, dan vraagt Adempauze of je door wilt. */
    private fun onPassExpired(pkg: String, minutes: Int) {
        expiryTokens.remove(pkg)
        if (interceptor.foreground != pkg) return
        val app = repo.app(pkg) ?: return
        if (!Policy.isActive(app, repo.settings.value.workHours, LocalDateTime.now())) return
        val pass = repo.pass(pkg)
        if (pass != null && pass.untilMillis > System.currentTimeMillis()) return
        showPause(pkg, PauseActivity.Mode.TIME_UP, minutes)
    }

    companion object {
        private const val IGNORED_REFRESH_MS = 10 * 60_000L

        private val ALWAYS_IGNORED = setOf(
            "android",
            "com.android.systemui",
            "com.android.intentresolver",
            "com.samsung.android.app.cocktailbarservice", // Edge-panelen
            "com.samsung.android.honeyboard",
            "com.samsung.android.app.aodservice",
            "com.samsung.android.biometrics.app.setting",
            "com.google.android.inputmethod.latin",
        )

        @Volatile
        private var instance: GuardService? = null

        private val _running = MutableStateFlow(false)
        /** Draait de dienst? Gebruikt door het hoofdscherm. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Je koos "openen": de app is vrij tot de pas verloopt. */
        fun onOpened(pkg: String, pass: AccessPass) {
            instance?.let {
                it.interceptor.onOpened(pkg)
                it.scheduleExpiry(pkg, pass)
            }
        }

        /** Je koos "Nee, laat maar". */
        fun onDismissed(pkg: String) {
            instance?.interceptor?.onDismissed(pkg, System.currentTimeMillis())
        }

        /** Naar het startscherm. Via de dienst gaat dat het netst; anders met een intent. */
        fun goHome(context: Context) {
            val viaService = instance?.performGlobalAction(GLOBAL_ACTION_HOME) == true
            if (!viaService) {
                val home = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(home) }
            }
        }
    }
}
