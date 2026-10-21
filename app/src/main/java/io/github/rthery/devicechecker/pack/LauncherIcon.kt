package io.github.rthery.devicechecker.pack

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StyleRes
import io.github.rthery.devicechecker.R

/**
 * Icons bundled in the APK, one launcher activity-alias each. Packs pick one by [key]; they cannot ship images.
 * The splash screen ignores the alias icon, so [splashTheme] carries it.
 */
enum class LauncherIcon(val key: String, private val alias: String, @StyleRes val splashTheme: Int) {
    AOSP("aosp", "LauncherAosp", R.style.Theme_DeviceChecker_SplashAosp),
    LINEAGE("lineage", "LauncherLineage", R.style.Theme_DeviceChecker_SplashLineage),
    EOS("eos", "LauncherEos", R.style.Theme_DeviceChecker_SplashEos);

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
