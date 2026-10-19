package io.github.rthery.devicechecker.interactive

data class CameraHalInventory(val providers: List<String>, val devices: List<String>, val complete: Boolean)

/** Extract explicitly named camera HAL interfaces without inferring versions from device IDs. */
fun parseCameraHalInventory(dump: String): CameraHalInventory {
    val providers = linkedSetOf<String>()
    val devices = linkedSetOf<String>()
    val patterns = listOf(
        Regex("^\\s*==\\s*Camera Provider HAL\\s+(.+?)\\s+static info:.*==\\s*$", RegexOption.IGNORE_CASE) to providers,
        Regex("^\\s*==\\s*Camera HAL device\\s+(.+?)\\s+static information:.*==\\s*$", RegexOption.IGNORE_CASE) to devices,
        Regex("^\\s*==\\s*Camera Provider\\s+(.+?)\\s*==\\s*$", RegexOption.IGNORE_CASE) to providers,
        Regex("^\\s*==\\s*Camera device\\s+(.+?)\\s*==\\s*$", RegexOption.IGNORE_CASE) to devices
    )
    dump.lineSequence().forEach { line ->
        patterns.firstNotNullOfOrNull { (regex, output) ->
            regex.matchEntire(line)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)?.let { value -> output.add(value); true }
        }
    }
    return CameraHalInventory(providers.toList(), devices.toList(), providers.isNotEmpty() || devices.isNotEmpty())
}
