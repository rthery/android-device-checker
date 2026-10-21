package io.github.rthery.devicechecker.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether adbd runs as root (`adb root`); null when neither the app nor Shizuku can read it. */
object AdbRoot {
    private val _state = MutableStateFlow<Boolean?>(null)
    val state: StateFlow<Boolean?> = _state.asStateFlow()

    suspend fun refresh() {
        val asApp = AppShell.exec("getprop service.adb.root").output.trim()
        val value = asApp.ifEmpty { if (ShizukuShell.isReady) ShizukuShell.exec("getprop service.adb.root").output.trim() else "" }
        _state.value = when {
            value == "1" -> true
            value.isNotEmpty() || ShizukuShell.isReady -> false
            else -> null
        }
    }
}
