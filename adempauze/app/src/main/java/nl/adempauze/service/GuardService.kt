package nl.adempauze.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.adempauze.R
import nl.adempauze.core.AccessPass
import nl.adempauze.core.Policy
import nl.adempauze.data.Repository
import nl.adempauze.repository
import nl.adempauze.ui.main.MainActivity
import nl.adempauze.ui.pause.PauseActivity
import nl.adempauze.util.Permissions
import java.time.LocalDateTime

/**
 * Houdt bij welke app vooraan staat en legt zo nodig het pauzescherm eroverheen.
 *
 * Adempauze gebruikt hiervoor geen toegankelijkheidsdienst (die laat Play
 * Protect bij zelf geïnstalleerde apps niet toe), maar de gebruiksgegevens van
 * Android: zolang het scherm aan is, kijkt deze dienst twee keer per seconde
 * welke app het laatst naar voren kwam. Het ziet alleen de naam van die app.
 * Gaat het scherm uit, dan stopt het kijken.
 *
 * Het pauzescherm mag vanuit de achtergrond openen omdat je Adempauze
 * "weergeven boven andere apps" hebt gegeven.
 */
class GuardService : Service() {

    private lateinit var repo: Repository
    private lateinit var usage: UsageStatsManager
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler

    // Alles hieronder wordt alleen op de worker-thread aangeraakt.
    private val interceptor = Interceptor()
    private val expiryTokens = mutableMapOf<String, Any>()
    private var lastHandledTs = 0L
    private var screenOn = true

    private val poll = object : Runnable {
        override fun run() {
            if (!screenOn) return
            runCatching { checkForeground() }
            worker.postDelayed(this, POLL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> worker.post {
                    screenOn = false
                    worker.removeCallbacks(poll)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> worker.post {
                    if (!screenOn) {
                        screenOn = true
                        worker.removeCallbacks(poll)
                        worker.post(poll)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = applicationContext.repository
        usage = getSystemService(UsageStatsManager::class.java)
        startInForeground()

        thread = HandlerThread("adempauze-kijker").also { it.start() }
        worker = Handler(thread.looper)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        instance = this
        _running.value = true
        worker.post {
            screenOn = interactive
            // Wat er vóór de start gebeurde, laten we rusten.
            lastHandledTs = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            repo.allPasses().forEach { (pkg, pass) -> if (pass.untilMillis > now) scheduleExpiry(pkg, pass) }
            if (screenOn) worker.post(poll)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Bij elke start via startForegroundService moet startForeground opnieuw.
        startInForeground()
        if (!shouldRun(this)) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _running.value = false
        runCatching { unregisterReceiver(screenReceiver) }
        if (::worker.isInitialized) worker.removeCallbacksAndMessages(null)
        if (::thread.isInitialized) thread.quitSafely()
        super.onDestroy()
    }

    // --- Kijken welke app vooraan staat (worker-thread) -------------------------------

    private fun checkForeground() {
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(maxOf(lastHandledTs, now - LOOKBACK_MS) - OVERLAP_MS, now + 1) ?: return
        val event = UsageEvents.Event()
        var newestPkg: String? = null
        var newestTs = lastHandledTs
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == ACTIVITY_RESUMED && event.timeStamp > newestTs) {
                newestTs = event.timeStamp
                newestPkg = event.packageName
            }
        }
        if (newestPkg == null) return
        lastHandledTs = newestTs
        onForeground(newestPkg)
    }

    private fun onForeground(pkg: String) {
        if (pkg == packageName) {
            interceptor.onOwnWindow(pkg)
            return
        }
        if (pkg in IGNORED) return
        val now = System.currentTimeMillis()
        val app = repo.app(pkg)
        val active = app != null && Policy.isActive(app, repo.settings.value.workHours, LocalDateTime.now())
        val passUntil = repo.pass(pkg)?.untilMillis ?: 0L
        if (interceptor.onForeground(pkg, active, passUntil, now)) {
            showPause(pkg, PauseActivity.Mode.OPEN, 0)
        }
    }

    private fun showPause(pkg: String, mode: PauseActivity.Mode, minutes: Int) {
        val intent = PauseActivity.intent(this, pkg, mode, minutes)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        runCatching { startActivity(intent) }
    }

    private fun scheduleExpiry(pkg: String, pass: AccessPass) {
        expiryTokens[pkg]?.let { worker.removeCallbacksAndMessages(it) }
        val token = Any()
        expiryTokens[pkg] = token
        val delay = pass.untilMillis - System.currentTimeMillis()
        if (delay <= 0) return
        HandlerCompat.postDelayed(worker, { onPassExpired(pkg, pass.minutes) }, token, delay + 250)
    }

    /** Je ingestelde tijd is om. Zit je nog in de app, dan vraagt Adempauze of je door wilt. */
    private fun onPassExpired(pkg: String, minutes: Int) {
        expiryTokens.remove(pkg)
        // Scherm uit? Dan komt de vraag vanzelf bij het ontgrendelen, want de pas is verlopen.
        if (!screenOn || interceptor.foreground != pkg) return
        val app = repo.app(pkg) ?: return
        if (!Policy.isActive(app, repo.settings.value.workHours, LocalDateTime.now())) return
        val pass = repo.pass(pkg)
        if (pass != null && pass.untilMillis > System.currentTimeMillis()) return
        showPause(pkg, PauseActivity.Mode.TIME_UP, minutes)
    }

    // --- Melding van de voorgronddienst ----------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Adempauze actief", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Laat zien dat Adempauze meekijkt welke app je opent."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Adempauze is actief")
            .setContentText("Je gekozen apps krijgen eerst een adempauze.")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        /** Zo vaak kijken we, zolang het scherm aan is. */
        private const val POLL_MS = 500L
        private const val LOOKBACK_MS = 10_000L
        private const val OVERLAP_MS = 1_000L

        /** UsageEvents.Event.ACTIVITY_RESUMED (API 29+), gelijk aan MOVE_TO_FOREGROUND daarvoor. */
        private const val ACTIVITY_RESUMED = 1

        private const val CHANNEL = "actief"
        private const val NOTIFICATION_ID = 1

        /** Vensters van het systeem die niet betekenen dat je van app wisselt. */
        private val IGNORED = setOf(
            "android",
            "com.android.systemui",
            "com.android.intentresolver",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        @Volatile
        private var instance: GuardService? = null

        private val _running = MutableStateFlow(false)
        /** Draait de dienst? Gebruikt door het hoofdscherm. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Moet de dienst draaien: toestemmingen gegeven en minstens één app aan. */
        fun shouldRun(context: Context): Boolean =
            Permissions.isReady(context) && context.repository.apps.value.any { it.enabled }

        /** Start of stop de dienst, afhankelijk van [shouldRun]. */
        fun sync(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            if (shouldRun(context)) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else if (_running.value) {
                context.stopService(intent)
            }
        }

        /** Je koos "openen": de app is vrij tot de pas verloopt. */
        fun onOpened(pkg: String, pass: AccessPass) {
            val s = instance ?: return
            s.worker.post {
                s.interceptor.onOpened(pkg)
                s.scheduleExpiry(pkg, pass)
            }
        }

        /** Je koos "Nee, laat maar". */
        fun onDismissed(pkg: String) {
            val s = instance ?: return
            s.worker.post { s.interceptor.onDismissed(pkg, System.currentTimeMillis()) }
        }

        /** Naar het startscherm. */
        fun goHome(context: Context) {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(home) }
        }
    }
}
