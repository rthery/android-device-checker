package io.github.rthery.devicechecker.data

import io.github.rthery.devicechecker.checks.CheckResult

/** Do not apply another device's baseline or silently interpret a future schema. */
fun decodeStockCapture(text: String, codename: String): StockSnapshot {
    val snapshot = stockJson.decodeFromString(StockSnapshot.serializer(), text)
    require(snapshot.format == 1) { "Unsupported stock capture format: ${snapshot.format}" }
    require(snapshot.codename == codename) { "This capture is for ${snapshot.codename}, not $codename." }
    return snapshot
}

private val stockComparisonIds = setOf(
    "kernel.clocks", "sensors.list", "codecs.hardware", "audio.devices", "camera.list",
    "display.resolution", "display.density", "display.hdr", "wifi.mac", "bluetooth.mac",
)

/** Only automatic comparisons, preserving explicit tester verdicts and never starting a recording. */
fun stockRefreshIds(catalog: TestCatalog, results: Map<String, CheckResult>): Set<String> =
    stockComparisonIds.filter { it in catalog.checksById && results[it]?.byTester != true }.toSet()
