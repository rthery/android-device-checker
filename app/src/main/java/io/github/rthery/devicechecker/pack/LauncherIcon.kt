package io.github.rthery.devicechecker.pack

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Icons bundled in the APK, one launcher activity-alias each. Packs pick one by [key]; they cannot ship images. */
enum class LauncherIcon(val key: String, private val alias: String) {
    AOSP("aosp", "LauncherAosp"),
    LINEAGE("lineage", "LauncherLineage"),
    EOS("eos", "LauncherEos");

    companion object {
        val keys: Set<String> = entries.map { it.key }.toSet()

        fun from(key: String?): LauncherIcon = entries.firstOrNull { it.key == key } ?: AOSP

        /** Enables the chosen alias before disabling the others, so there is always a launcher entry. */
        fun apply(context: Context, icon: LauncherIcon) {
            val packageManager = context.packageManager
            fun component(entry: LauncherIcon) = ComponentName(context.packageName, "${context.packageName}.${entry.alias}")
            fun set(entry: LauncherIcon, enabled: Boolean) {
                val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                val current = packageManager.getComponentEnabledSetting(component(entry))
                val effective = if (current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    if (entry == AOSP) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else current
                if (effective != state) packageManager.setComponentEnabledSetting(component(entry), state, PackageManager.DONT_KILL_APP)
            }
            set(icon, true)
            entries.filter { it != icon }.forEach { set(it, false) }
        }
    }
}
