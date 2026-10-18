package io.github.rthery.devicechecker.interactive

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.provider.Settings

/** Opens the first intent that resolves, so OEM or version differences fall back gracefully. */
class Shortcut(val label: String, private val intents: () -> List<Intent>) {
    fun launch(context: Context): Boolean = intents().any { intent ->
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
    val wifi = Shortcut("Wi-Fi settings") { listOf(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS)) }
    val internet = Shortcut("Internet panel") { listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
    val tethering = Shortcut("Hotspot and tethering") {
        listOf(Intent("android.settings.TETHER_SETTINGS"), settingsComponent("com.android.settings.TetherSettings"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }
    val bluetooth = Shortcut("Bluetooth settings") { listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    val usb = Shortcut("Connected devices > USB") {
        listOf(settingsComponent("com.android.settings.Settings\$ConnectedDeviceDashboardActivity"), Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }
    val storage = Shortcut("Storage settings") { listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_MEMORY_CARD_SETTINGS)) }
    val nfc = Shortcut("NFC settings") { listOf(Intent(Settings.ACTION_NFC_SETTINGS)) }
    val location = Shortcut("Location settings") { listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    val fingerprintEnroll = Shortcut("Enroll a fingerprint") {
        listOf(
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BiometricManager.Authenticators.BIOMETRIC_STRONG),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
    }
    val dialer = Shortcut("Open dialer") { listOf(Intent(Intent.ACTION_DIAL)) }
    val updater = Shortcut("Open Updater") {
        listOf(Intent().setComponent(ComponentName("org.lineageos.updater", "org.lineageos.updater.UpdatesActivity")), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }
    val batteryUsage = Shortcut("Battery usage") { listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
    val sound = Shortcut("Sound settings") { listOf(Intent(Settings.ACTION_SOUND_SETTINGS)) }
}
