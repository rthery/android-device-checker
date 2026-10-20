package io.github.rthery.devicechecker.data

import android.content.Context
import android.os.Build
import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.shell.AppShell

data class DeviceInfo(val entries: List<Pair<String, String>>, val rom: Rom? = null) {
    val lineageBased: Boolean get() = rom != null

    companion object {
        /**
         * /e/OS keeps the LineageOS markers and adds its legal URL. Some SELinux policies hide
         * ro.lineage.version from apps, so the LineageOS system feature also counts.
         */
        suspend fun detectRom(context: Context): Rom? {
            val lineage = AppShell.exec("getprop ro.lineage.version").output.isNotBlank() ||
                context.packageManager.hasSystemFeature(LINEAGE_FEATURE)
            if (!lineage) return null
            val legal = AppShell.exec("getprop ro.elegal.url").output
            return if (EOS_LEGAL_HOSTS.any { legal.contains(it, ignoreCase = true) }) Rom.EOS else Rom.LINEAGE
        }

        suspend fun read(context: Context): DeviceInfo {
            suspend fun prop(name: String) = AppShell.exec("getprop $name").output.ifBlank { "unknown" }
            val rom = detectRom(context)
            return DeviceInfo(
                rom = rom,
                entries = listOf(
                    "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${prop("ro.lineage.device").takeIf { it != "unknown" } ?: Build.DEVICE})",
                    "Build" to Build.DISPLAY,
                    "ROM family" to (rom?.let { family -> prop("ro.lineage.version").takeIf { it != "unknown" }?.let { "${family.label} ($it)" } ?: family.label } ?: "not LineageOS-based"),
                    "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "Security patch" to Build.VERSION.SECURITY_PATCH,
                    "Kernel" to System.getProperty("os.version").orEmpty(),
                    "Fingerprint" to Build.FINGERPRINT,
                )
            )
        }
    }
}

private val EOS_LEGAL_HOSTS = listOf("e.foundation", "murena")
private const val LINEAGE_FEATURE = "org.lineageos.android"
