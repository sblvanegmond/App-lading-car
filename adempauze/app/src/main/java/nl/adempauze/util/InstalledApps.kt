package nl.adempauze.util

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
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

    /** Mag Adempauze zien welke app vooraan staat (toegang tot gebruiksgegevens)? */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Mag Adempauze het pauzescherm over andere apps heen openen? */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Beide toestemmingen gegeven: dan kan Adempauze zijn werk doen. */
    fun isReady(context: Context): Boolean = hasUsageAccess(context) && canDrawOverlays(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    private fun start(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** Opent "Toegang tot gebruiksgegevens", zo mogelijk meteen bij Adempauze. */
    fun openUsageAccessSettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(packageUri(context)),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    /** Opent "Weergeven boven andere apps" voor Adempauze. */
    fun openOverlaySettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    )

    /** App-info: daar staat (via ⋮) "Beperkte instellingen toestaan". */
    fun openAppInfo(context: Context) = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri(context)),
    )

    @android.annotation.SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) = start(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
}
