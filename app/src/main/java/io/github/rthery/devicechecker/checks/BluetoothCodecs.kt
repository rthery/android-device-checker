package io.github.rthery.devicechecker.checks

const val BLUETOOTH_DUMP_COMMAND = "dumpsys bluetooth_manager"

/** The A2DP source codecs the stack lists under codecConfigPriorities (Android 13 and later). */
fun parseStackCodecs(dump: String): List<String> {
    val lines = dump.lines()
    val start = lines.indexOfFirst { it.trim() == "codecConfigPriorities:" }
    if (start < 0) return emptyList()
    val entry = Regex("^\\s+([^:]+):\\s*-?\\d+\\s*$")
    return lines.drop(start + 1).map { entry.find(it)?.groupValues?.get(1)?.trim() }.takeWhile { it != null }.filterNotNull()
}

const val APTX_LIBRARY_COMMAND = "ls /system/lib64 /system/lib /system_ext/lib64 /vendor/lib64 /vendor/lib 2>/dev/null | grep -i '^libaptx'; true"

/** Before Android 13 the stack loads the aptX encoders from these libraries, so a listed aptX codec without its library is not offered. */
fun withoutMissingAptx(codecs: List<String>, libraries: String): List<String> {
    val present = libraries.lines().map { it.trim().lowercase() }.toSet()
    return codecs.filter {
        when (bluetoothCodecKey(it)) {
            "aptx" -> "libaptx_encoder.so" in present
            "aptxhd" -> "libaptxhd_encoder.so" in present
            else -> true
        }
    }
}

fun bluetoothCodecKey(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

class CodecComparison(val missing: List<String>, val added: List<String>) {
    /** aptX Adaptive must not be added when stock lacks it. */
    val forbidden get() = added.filter { bluetoothCodecKey(it).startsWith("aptxadaptive") }
}

fun compareCodecs(build: List<String>, stock: List<String>): CodecComparison {
    val buildKeys = build.map(::bluetoothCodecKey).toSet()
    val stockKeys = stock.map(::bluetoothCodecKey).toSet()
    return CodecComparison(stock.filter { bluetoothCodecKey(it) !in buildKeys }, build.filter { bluetoothCodecKey(it) !in stockKeys })
}

fun codecResult(build: List<String>, stock: List<String>, details: String, access: Access): CheckResult {
    val list = build.joinToString(", ")
    if (stock.isEmpty()) return warn("Set the stock codec list in the device profile", "Build: $list\n\n$details", access)
    val comparison = compareCodecs(build, stock)
    val summary = "Build: $list. Stock: ${stock.joinToString(", ")}"
    return when {
        comparison.forbidden.isNotEmpty() -> fail("Adds ${comparison.forbidden.joinToString(", ")}, which stock lacks", "$summary\n\n$details", access)
        comparison.missing.isNotEmpty() -> fail("Missing stock codec ${comparison.missing.joinToString(", ")}", "$summary\n\n$details", access)
        comparison.added.isNotEmpty() -> pass("Every stock codec, plus ${comparison.added.joinToString(", ")}", "$summary\n\n$details", access)
        else -> pass("Same codecs as stock: $list", "$summary\n\n$details", access)
    }
}
