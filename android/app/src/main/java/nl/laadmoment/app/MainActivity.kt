package nl.laadmoment.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.laadmoment.app.ui.HomeScreen
import nl.laadmoment.app.ui.LaadmomentTheme
import nl.laadmoment.app.ui.MainViewModel
import nl.laadmoment.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // "screen=settings" opens the settings straight away; the build
        // workflow uses it to photograph both screens.
        val startInSettings = intent?.getStringExtra("screen") == "settings"
        setContent {
            LaadmomentTheme {
                var settingsOpen by rememberSaveable { mutableStateOf(startInSettings) }
                val state by viewModel.state.collectAsStateWithLifecycle()
                if (settingsOpen) {
                    BackHandler { settingsOpen = false }
                    SettingsScreen(
                        settings = state.settings,
                        onUpdate = viewModel::update,
                        onBack = { settingsOpen = false },
                    )
                } else {
                    HomeScreen(
                        state = state,
                        onRefresh = { viewModel.refresh(force = true) },
                        onOpenSettings = { settingsOpen = true },
                        onUpdate = viewModel::update,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh(force = false)
    }
}
