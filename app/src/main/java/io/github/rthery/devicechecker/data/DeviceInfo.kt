package io.github.rthery.devicechecker.data

import android.os.Build
import io.github.rthery.devicechecker.shell.AppShell

data class DeviceInfo(val entries: List<Pair<String, String>>, val lineageBased: Boolean = false) {
    companion object {
        suspend fun isLineageBased(): Boolean = AppShell.exec("getprop ro.lineage.version").output.isNotBlank()

        suspend fun read(): DeviceInfo {
            suspend fun prop(name: String) = AppShell.exec("getprop $name").output.ifBlank { "unknown" }
            val lineageBased = isLineageBased()
            return DeviceInfo(
                lineageBased = lineageBased,
                entries = listOf(
                    "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${prop("ro.lineage.device").takeIf { it != "unknown" } ?: Build.DEVICE})",
                    "Build" to Build.DISPLAY,
                    "LineageOS-based" to if (lineageBased) "yes (${prop("ro.lineage.version")})" else "no",
                    "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "Security patch" to Build.VERSION.SECURITY_PATCH,
                    "Kernel" to System.getProperty("os.version").orEmpty(),
                    "Fingerprint" to Build.FINGERPRINT,
                )
            )
        }
    }
}
