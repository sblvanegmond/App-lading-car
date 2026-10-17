package nl.laadmoment.app

import android.app.Application
import android.content.Context
import nl.laadmoment.app.data.ForecastRepository
import nl.laadmoment.app.data.SettingsStore
import nl.laadmoment.app.work.Notifier
import nl.laadmoment.app.work.Scheduler

class LaadmomentApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var forecasts: ForecastRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        forecasts = ForecastRepository(this)
        Notifier.createChannels(this)
        Scheduler.ensure(this, settings.current)
    }
}

val Context.laadmoment: LaadmomentApp get() = applicationContext as LaadmomentApp
