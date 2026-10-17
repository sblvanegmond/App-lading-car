package nl.laadmoment.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.laadmoment.core.Settings

/** The settings as one JSON document in the app's private preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("laadmoment", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(Settings.fromJson(prefs.getString(KEY, null)))

    val flow: StateFlow<Settings> = state.asStateFlow()
    val current: Settings get() = state.value

    @Synchronized
    fun update(transform: (Settings) -> Settings): Settings {
        val next = transform(state.value)
        if (next != state.value) {
            prefs.edit().putString(KEY, next.toJson()).apply()
            state.value = next
        }
        return next
    }

    private companion object {
        const val KEY = "settings.v1"
    }
}
