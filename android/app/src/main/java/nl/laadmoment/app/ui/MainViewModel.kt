package nl.laadmoment.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.laadmoment.app.laadmoment
import nl.laadmoment.app.widget.LaadWidget
import nl.laadmoment.app.work.Scheduler
import nl.laadmoment.core.Forecast
import nl.laadmoment.core.Settings
import nl.laadmoment.core.Snapshot
import java.time.Instant

data class UiState(
    val snapshot: Snapshot,
    val loading: Boolean,
    val errors: List<String>,
) {
    val settings: Settings get() = snapshot.settings
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.laadmoment
    private val forecast = MutableStateFlow(app.forecasts.cached())
    private val loading = MutableStateFlow(false)
    private val errors = MutableStateFlow<List<String>>(emptyList())

    /** Moves the "now" marker along while the screen is open. */
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(30_000)
        }
    }

    val state: StateFlow<UiState> =
        combine(app.settings.flow, forecast, loading, errors, clock) { settings, data, busy, problems, now ->
            UiState(Snapshot.of(settings, data, now), busy, problems)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                UiState(Snapshot.of(app.settings.current, forecast.value), loading = false, errors = emptyList()),
            )

    private var refreshJob: Job? = null
    private var widgetJob: Job? = null
    private var refetchJob: Job? = null

    fun refresh(force: Boolean) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            loading.value = true
            try {
                val result = app.forecasts.load(app.settings.current, force)
                result.forecast?.let { forecast.value = it }
                errors.value = result.errors
                afterChange(result.forecast)
            } finally {
                loading.value = false
            }
        }
    }

    fun update(transform: (Settings) -> Settings) {
        val before = app.settings.current
        val after = app.settings.update(transform)
        if (after == before) return
        if (after.weatherKey() != before.weatherKey() || after.priceSource != before.priceSource) {
            // Wait until the typing stops before asking the services again.
            refetchJob?.cancel()
            refetchJob = viewModelScope.launch {
                delay(1_200)
                refreshJob?.cancel()
                refresh(force = true)
            }
        }
        if (after.notifications != before.notifications) {
            Scheduler.scheduleMorning(getApplication(), after, replace = true)
        }
        // Sliders fire many times a second; the widget only needs the end result.
        widgetJob?.cancel()
        widgetJob = viewModelScope.launch {
            delay(1_500)
            afterChange(forecast.value)
        }
    }

    private suspend fun afterChange(data: Forecast?) {
        val snapshot = withContext(Dispatchers.Default) { Snapshot.of(app.settings.current, data) }
        LaadWidget.updateAll(getApplication(), snapshot)
        Scheduler.scheduleReminder(getApplication(), app.settings.current, snapshot.plan)
    }
}
