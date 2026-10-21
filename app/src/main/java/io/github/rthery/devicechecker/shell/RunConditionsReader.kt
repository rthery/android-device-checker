package io.github.rthery.devicechecker.shell

import io.github.rthery.devicechecker.checks.RunConditions

/** The root state right now, for a result that just went through Shizuku. */
suspend fun currentRunConditions(): RunConditions {
    AdbRoot.refresh()
    return RunConditions(ShizukuShell.uid, AdbRoot.state.value)
}

/** Remembers whether any command ran, so a test result can say it used Shizuku. */
class TrackedShell(private val inner: Shell) : Shell {
    @Volatile
    var used = false
        private set

    override suspend fun exec(command: String): ShellOutput {
        used = true
        return inner.exec(command)
    }
}
