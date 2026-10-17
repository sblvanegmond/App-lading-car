package nl.laadmoment.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import nl.laadmoment.app.laadmoment
import nl.laadmoment.app.widget.LaadWidget
import nl.laadmoment.core.Briefing
import nl.laadmoment.core.Fmt
import nl.laadmoment.core.Settings
import nl.laadmoment.core.Snapshot
import nl.laadmoment.core.Window
import nl.laadmoment.core.nextTimeOccurrence
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Three jobs, all through WorkManager so they survive a reboot and respect
 * the battery:
 *  - refresh: every hour, keeps the widget current and the reminder aimed;
 *  - morning: once a day at the chosen time, the best hours;
 *  - reminder: a quarter of an hour before a block of best hours starts.
 */
object Scheduler {
    private const val REFRESH = "verversen"
    private const val MORNING = "ochtend"
    private const val REMINDER = "seintje"
    private const val QUICK = "nu-verversen"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Called at start-up and from the hourly refresh; leaves planned work alone. */
    fun ensure(context: Context, settings: Settings) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            REFRESH,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS).setConstraints(network).build(),
        )
        scheduleMorning(context, settings, replace = false)
        if (!settings.notifications.windowReminder) work.cancelUniqueWork(REMINDER)
    }

    /** One refresh as soon as there is a network, for a widget that was just placed. */
    fun refreshSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            QUICK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(network).build(),
        )
    }

    /**
     * The morning job does not reschedule itself (replacing the running job
     * from inside it would cancel it); the hourly refresh plans the next one.
     */
    fun scheduleMorning(context: Context, settings: Settings, replace: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!settings.notifications.morningEnabled) {
            work.cancelUniqueWork(MORNING)
            return
        }
        val now = Instant.now()
        val next = nextTimeOccurrence(now, settings.notifications.morningTime, ZoneId.systemDefault())
        work.enqueueUniqueWork(
            MORNING,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MorningWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    fun scheduleReminder(context: Context, settings: Settings, windows: List<Window>) {
        val work = WorkManager.getInstance(context)
        val now = Instant.now()
        val window = windows.firstOrNull { it.from.isAfter(now.plusSeconds(5 * 60)) }
        if (!settings.notifications.windowReminder || window == null) {
            work.cancelUniqueWork(REMINDER)
            return
        }
        val at = window.from.minusSeconds(15 * 60)
        val delay = Duration.between(now, at).toMillis().coerceAtLeast(0)
        work.enqueueUniqueWork(
            REMINDER,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(ReminderWorker.KEY_FROM to window.from.epochSecond))
                .build(),
        )
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.laadmoment
        val settings = app.settings.current
        val result = app.forecasts.load(settings, force = false)
        val snapshot = Snapshot.of(settings, result.forecast)
        LaadWidget.updateAll(applicationContext, snapshot)
        Scheduler.scheduleReminder(applicationContext, settings, snapshot.windows)
        Scheduler.ensure(applicationContext, settings)
        return Result.success()
    }
}

class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.laadmoment
        val settings = app.settings.current
        if (!settings.notifications.morningEnabled) return Result.success()
        val snapshot = Snapshot.of(settings, app.forecasts.load(settings, force = true).forecast)
        Notifier.morning(applicationContext, Briefing.title(snapshot), Briefing.body(snapshot))
        LaadWidget.updateAll(applicationContext, snapshot)
        Scheduler.scheduleReminder(applicationContext, settings, snapshot.windows)
        return Result.success()
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.laadmoment
        val settings = app.settings.current
        if (!settings.notifications.windowReminder) return Result.success()
        val expected = inputData.getLong(KEY_FROM, 0L)
        val snapshot = Snapshot.of(settings, app.forecasts.load(settings, force = false).forecast)
        // The best hours may have moved since this was scheduled; only nudge
        // for a block that still starts within the next half hour.
        val window = snapshot.windows.firstOrNull { it.to.isAfter(snapshot.now) } ?: return Result.success()
        val minutes = Duration.between(snapshot.now, window.from).toMinutes()
        if (minutes > 30 || (expected != 0L && window.from.epochSecond != expected && minutes > 20)) {
            Scheduler.scheduleReminder(applicationContext, settings, snapshot.windows)
            return Result.success()
        }
        val range = Fmt.range(window.from, window.to, snapshot.zone)
        val title = if (minutes <= 1) "Nu laden: $range" else "Over $minutes min: laden $range"
        val sun = if (window.sunny) "☀️ zon · " else ""
        Notifier.reminder(applicationContext, title, "${sun}gemiddeld ${Fmt.cents(window.avgPrice)}/kWh")
        return Result.success()
    }

    companion object {
        const val KEY_FROM = "from"
    }
}
