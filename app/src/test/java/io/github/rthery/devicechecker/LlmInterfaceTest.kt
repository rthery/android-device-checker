package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.data.StockSnapshot
import io.github.rthery.devicechecker.data.TestCatalog
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.buildCatalog
import io.github.rthery.devicechecker.data.nextSteps
import io.github.rthery.devicechecker.data.stateOf
import io.github.rthery.devicechecker.data.stockJson
import io.github.rthery.devicechecker.interactive.HeadlessRegistry
import io.github.rthery.devicechecker.profile.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmInterfaceTest {

    private val tests = TestCatalog.builtIn.all

    @Test
    fun everyHeadlessTestIsInTheCatalog() {
        val missing = HeadlessRegistry.all.map { it.id }.filterNot { id -> tests.any { it.id == id } }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun catalogGivesEveryTestAWayToRecordAResult() {
        val catalog = buildCatalog(tests, "/sdcard/reports")
        assertEquals(tests.size, catalog.tests.size)
        assertTrue(catalog.tests.all { it.setResult.contains("--es test ${it.id}") })
        assertTrue(catalog.tests.filter { it.kind == "AUTO" }.all { it.run?.contains("RUN_CHECKS") == true })
        val photo = catalog.tests.single { it.id == "camera.capture" }
        assertTrue(photo.headless && photo.needsHuman)
        assertTrue(photo.run!!.contains("--es run camera.capture"))
    }

    @Test
    fun nextStepsAskForSetupThenReviewOfArtifacts() {
        val results = mapOf(
            "camera.capture" to CheckResult(CheckStatus.INFO, "2 photos saved", artifacts = listOf("artifacts/camera.capture/camera0.jpg")),
            "selinux" to CheckResult(CheckStatus.FAIL, "Permissive"),
        )
        val states = tests.associate { it.id to stateOf(it, results, emptyMap(), lineageBased = true, run = null) }
        val steps = nextSteps(tests, states, results, DeviceProfile(), shizukuReady = false, hasStock = false)
        assertEquals("Connect Shizuku", steps.first().title)
        assertTrue(steps.any { it.title == "Import a stock capture" })
        val review = steps.single { it.id == "camera.capture" }
        assertTrue(review.needsHuman && review.why.contains("camera0.jpg") && review.command!!.contains("SET_RESULT"))
        assertTrue(steps.any { it.id == "selinux" && it.why.contains("Permissive") })
        assertEquals(TestState.FAIL, states["selinux"])
    }

    @Test
    fun stockCaptureRoundTripsAndToleratesNewerFields() {
        val snapshot = StockSnapshot(
            capturedAt = 1, device = "LGE Nexus 5X", codename = "bullhead", android = "8.1.0", sdk = 27,
            fingerprint = "google/bullhead", profile = DeviceProfile(stockWidthPx = 1080, stockHeightPx = 1920),
            sensors = listOf("Accelerometer: BMI160"), cpuFrequencies = listOf("cpu0/cpufreq min=384000 max=1440000"),
        )
        val text = stockJson.encodeToString(StockSnapshot.serializer(), snapshot)
        val decoded = stockJson.decodeFromString(StockSnapshot.serializer(), text.replace("\"format\": 1", "\"format\": 1, \"futureField\": true"))
        assertEquals(snapshot, decoded)
        assertNotNull(decoded.profile.stockWidthPx)
    }
}
