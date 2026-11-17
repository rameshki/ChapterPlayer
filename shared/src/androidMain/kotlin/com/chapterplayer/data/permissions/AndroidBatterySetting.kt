package com.chapterplayer.data.permissions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's battery optimisation for this app. Opens the system's "unrestricted apps" list (no permission needed;
 * the direct dialog needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Google Play restricts) and falls back to the app's
 * own settings page. Some phones keep a second list of their own (Samsung: Never sleeping apps); this reads only the
 * system's answer.
 */
class AndroidBatterySetting(private val context: Context) : BatterySetting {
    override val available: Boolean = true

    override fun isUnrestricted(): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        true // when unreadable, do not nag
    }

    override fun openSettings() {
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        for (intent in listOf(list, details)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // try the next screen
            } catch (_: SecurityException) {
                // try the next screen
            }
        }
    }
}
