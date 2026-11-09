package nl.adempauze

import android.app.Application
import android.content.Context
import nl.adempauze.data.Repository

class AdempauzeApp : Application() {
    val repository: Repository by lazy { Repository(this) }
}

val Context.repository: Repository
    get() = (applicationContext as AdempauzeApp).repository

object BuildConfigInfo {
    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
