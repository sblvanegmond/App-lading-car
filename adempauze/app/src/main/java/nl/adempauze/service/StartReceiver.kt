package nl.adempauze.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Start de dienst opnieuw na het opstarten van de telefoon of na een update van Adempauze. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> GuardService.sync(context)
        }
    }
}
