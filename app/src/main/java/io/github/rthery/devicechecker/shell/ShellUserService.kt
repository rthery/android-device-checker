package io.github.rthery.devicechecker.shell

import kotlin.system.exitProcess

/** Runs inside the Shizuku-spawned process, with the shell (or root) uid. */
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val result = runShellCommand(command)
        return "${result.exitCode}\n${result.output}"
    }
}
