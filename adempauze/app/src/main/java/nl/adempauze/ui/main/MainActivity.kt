package nl.adempauze.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import nl.adempauze.repository
import nl.adempauze.ui.theme.AdempauzeTheme
import nl.adempauze.util.Permissions

class MainActivity : ComponentActivity() {

    /** Telt op bij elke terugkeer naar de app, zodat de schermen de toestemmingen opnieuw controleren. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = if (!repository.onboardingDone && !Permissions.isServiceEnabled(this)) Route.WELCOME else Route.HOME
        setContent {
            AdempauzeTheme {
                AdempauzeNav(start, resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

object Route {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val PICKER = "picker"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val SETUP = "setup"
    const val APP = "app:"
}

@Composable
fun AdempauzeNav(start: String, resumeTick: Int) {
    var stack by rememberSaveable(
        saver = listSaver<MutableState<List<String>>, String>(save = { it.value }, restore = { mutableStateOf(it) }),
    ) { mutableStateOf(listOf(start)) }
    val current = stack.last()

    fun push(route: String) {
        stack = stack + route
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun replaceAll(route: String) {
        stack = listOf(route)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    AnimatedContent(
        targetState = current,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "scherm",
    ) { route ->
        when {
            route == Route.WELCOME -> WelcomeScreen(onStart = { replaceAll(Route.HOME); push(Route.SETUP) })
            route == Route.HOME -> HomeScreen(
                resumeTick = resumeTick,
                onAddApps = { push(Route.PICKER) },
                onOpenApp = { push(Route.APP + it) },
                onOpenSettings = { push(Route.SETTINGS) },
                onOpenStats = { push(Route.STATS) },
                onOpenSetup = { push(Route.SETUP) },
            )
            route == Route.PICKER -> AppPickerScreen(onBack = ::pop, onAdded = { added ->
                pop()
                if (added.size == 1) push(Route.APP + added.first())
            })
            route == Route.SETTINGS -> SettingsScreen(resumeTick = resumeTick, onBack = ::pop, onOpenSetup = { push(Route.SETUP) })
            route == Route.STATS -> StatsScreen(onBack = ::pop)
            route == Route.SETUP -> SetupScreen(resumeTick = resumeTick, onBack = ::pop)
            route.startsWith(Route.APP) -> AppDetailScreen(
                packageName = route.removePrefix(Route.APP),
                onBack = ::pop,
                onOpenSettings = { push(Route.SETTINGS) },
            )
        }
    }
}
