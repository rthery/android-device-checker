package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.data.StockSnapshot
import io.github.rthery.devicechecker.data.TestCatalog
import io.github.rthery.devicechecker.data.decodeStockCapture
import io.github.rthery.devicechecker.data.stockJson
import io.github.rthery.devicechecker.data.stockRefreshIds
import io.github.rthery.devicechecker.profile.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockImportTest {
    private val snapshot = StockSnapshot(
        capturedAt = 1234L, device = "Test phone", codename = "testphone", android = "8.1", sdk = 27,
        fingerprint = "stock", profile = DeviceProfile(), cpuFrequencies = listOf("cpu0/cpufreq min=384000 max=1440000"),
    )
    private fun encode(value: StockSnapshot) = stockJson.encodeToString(StockSnapshot.serializer(), value)

    @Test
    fun acceptsMatchingCaptureAndPreservesMeasurements() {
        assertEquals(snapshot, decodeStockCapture(encode(snapshot), "testphone"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnotherDeviceBeforeApplyingItsProfile() {
        decodeStockCapture(encode(snapshot), "otherphone")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedCaptureFormat() {
        decodeStockCapture(encode(snapshot.copy(format = 2)), "testphone")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnOrdinaryTestReport() {
        decodeStockCapture("{\"requirements\":[]}", "testphone")
    }

    @Test
    fun refreshesComparisonsWithoutStartingInteractiveTestsOrOverwritingTesterVerdicts() {
        val catalog = TestCatalog.builtIn
        val selected = stockRefreshIds(catalog, mapOf("sensors.list" to CheckResult(CheckStatus.PASS, "Reviewed", byTester = true)))
        assertTrue(selected.containsAll(listOf("kernel.clocks", "display.resolution", "wifi.mac", "bluetooth.mac", "codecs.hardware")))
        assertFalse("sensors.list" in selected)
        assertTrue(selected.all { it in catalog.checksById })
        assertFalse(selected.any { it in catalog.interactiveById })
        assertFalse("codecs.roundtrip" in selected)
    }
}
