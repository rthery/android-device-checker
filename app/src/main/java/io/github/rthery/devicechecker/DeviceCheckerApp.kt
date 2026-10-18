package io.github.rthery.devicechecker

import android.app.Application
import io.github.rthery.devicechecker.shell.ShizukuShell

class DeviceCheckerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuShell.init()
    }
}
