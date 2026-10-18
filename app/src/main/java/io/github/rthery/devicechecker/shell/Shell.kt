package io.github.rthery.devicechecker.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ShellOutput(val exitCode: Int, val output: String) {
    val succeeded: Boolean get() = exitCode == 0 && output.isNotBlank()
}

interface Shell {
    suspend fun exec(command: String): ShellOutput
}

object AppShell : Shell {
    override suspend fun exec(command: String): ShellOutput = withContext(Dispatchers.IO) {
        runShellCommand(command)
    }
}

internal fun runShellCommand(command: String): ShellOutput = try {
    val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    ShellOutput(process.waitFor(), output.trim())
} catch (e: Exception) {
    ShellOutput(-1, e.message.orEmpty())
}
