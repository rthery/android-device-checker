package io.github.rthery.devicechecker.interactive

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.hardware.biometrics.BiometricManager
import android.provider.Settings
import android.provider.Telephony
import androidx.annotation.StringRes
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.ui.UiText

/** Opens the first intent that resolves, so OEM or version differences fall back gracefully. */
class Shortcut(val label: UiText, private val intents: (Context) -> List<Intent>) {
    constructor(@StringRes label: Int, intents: (Context) -> List<Intent>) : this(UiText.Res(label), intents)

    fun launch(context: Context): Boolean = intents(context).any { intent ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

private fun settingsComponent(className: String) = Intent().setComponent(ComponentName("com.android.settings", className))

object Shortcuts {
    val wifi = Shortcut(R.string.shortcut_wifi) { listOf(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS)) }
    val internet = Shortcut(R.string.shortcut_internet) { listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
    val tethering = Shortcut(R.string.shortcut_tethering) {
        listOf(Intent("android.settings.TETHER_SETTINGS"), settingsComponent("com.android.settings.TetherSettings"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }
    val bluetooth = Shortcut(R.string.shortcut_bluetooth) { listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    val usb = Shortcut(R.string.shortcut_usb) {
        listOf(settingsComponent("com.android.settings.Settings\$ConnectedDeviceDashboardActivity"), Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }
    val storage = Shortcut(R.string.shortcut_storage) { listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_MEMORY_CARD_SETTINGS)) }
    val nfc = Shortcut(R.string.shortcut_nfc) { listOf(Intent(Settings.ACTION_NFC_SETTINGS)) }
    val location = Shortcut(R.string.shortcut_location) { listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    val fingerprintEnroll = Shortcut(R.string.shortcut_fingerprint_enroll) {
        listOf(
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BiometricManager.Authenticators.BIOMETRIC_STRONG),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
    }
    val dialer = Shortcut(R.string.shortcut_dialer) { listOf(Intent(Intent.ACTION_DIAL)) }
    val messaging = Shortcut(R.string.shortcut_messaging) { context ->
        listOfNotNull(
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Telephony.Sms.getDefaultSmsPackage(context)?.let(context.packageManager::getLaunchIntentForPackage),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
        )
    }
    val sims = Shortcut(R.string.shortcut_sims) {
        listOf(Intent("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"), Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }
    val reset = Shortcut(R.string.shortcut_reset) { listOf(settingsComponent("com.android.settings.Settings\$ResetDashboardActivity"), Intent(Settings.ACTION_SETTINGS)) }
    val updater = Shortcut(R.string.shortcut_updater) {
        listOf(Intent().setComponent(ComponentName("org.lineageos.updater", "org.lineageos.updater.UpdatesActivity")), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }
    val batteryUsage = Shortcut(R.string.shortcut_battery_usage) { listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
    val microg = Shortcut(R.string.shortcut_microg) {
        listOf(Intent().setComponent(ComponentName("com.google.android.gms", "org.microg.gms.ui.SettingsActivity")), Intent(Settings.ACTION_SETTINGS))
    }
    val privacy = Shortcut(R.string.shortcut_privacy) { listOf(Intent(Settings.ACTION_PRIVACY_SETTINGS)) }
    val sound = Shortcut(R.string.shortcut_sound) { listOf(Intent(Settings.ACTION_SOUND_SETTINGS)) }
}
