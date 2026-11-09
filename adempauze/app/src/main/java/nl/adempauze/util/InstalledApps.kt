package nl.adempauze.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.adempauze.core.Category
import nl.adempauze.core.Policy
import nl.adempauze.service.GuardService
import java.text.Collator
import java.util.Locale

data class InstalledApp(
    val packageName: String,
    val label: String,
    val category: Category,
)

object InstalledApps {

    /** Alle apps met een icoon in de app-lijst, op naam gesorteerd. */
    fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance(Locale("nl", "NL"))
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                val label = pm.getApplicationLabel(info).toString()
                InstalledApp(info.packageName, label, Policy.detectCategory(info.packageName, label, info.category))
            }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun label(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    private val icons = object : LruCache<String, Bitmap>(80) {}

    fun icon(context: Context, packageName: String, sizePx: Int = 144): Bitmap? {
        icons.get(packageName)?.let { return it }
        return runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
        }.getOrNull()?.also { icons.put(packageName, it) }
    }
}

/** Laadt een app-icoon op de achtergrond. */
@Composable
fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { InstalledApps.icon(context, packageName)?.asImageBitmap() }
    }
    return icon
}

object Permissions {

    /** Staat Adempauze aan onder Toegankelijkheid? */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return GuardService.running.value
        val me = ComponentName(context, GuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun openAccessibilitySettings(context: Context) {
        // Probeer meteen de pagina van Adempauze; lukt dat niet, dan de lijst.
        val component = ComponentName(context, GuardService::class.java).flattenToString()
        val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 33 && runCatching { context.startActivity(direct) }.isSuccess) return
        runCatching { context.startActivity(list) }
    }

    /** App-info: daar staat (via ⋮) "Beperkte instellingen toestaan". */
    fun openAppInfo(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(request) }.isSuccess) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun isLikelySideloaded(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }
        installer != "com.android.vending"
    }.getOrDefault(true)

}
