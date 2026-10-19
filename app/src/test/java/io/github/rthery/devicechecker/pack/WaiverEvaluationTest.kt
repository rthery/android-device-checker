package io.github.rthery.devicechecker.pack

import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.catalog.Level
import io.github.rthery.devicechecker.catalog.Requirement
import io.github.rthery.devicechecker.catalog.Section
import io.github.rthery.devicechecker.catalog.TestMode
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.data.ManualVerdict
import io.github.rthery.devicechecker.data.Outcome
import io.github.rthery.devicechecker.data.Verdict
import io.github.rthery.devicechecker.data.evaluate
import org.junit.Assert.assertEquals
import org.junit.Test

class WaiverEvaluationTest {

    private val wifi = Requirement(
        "hw.wifi.works", Section.WIFI, Level.MUST, "Wi-Fi works.", TestMode.AUTOMATED,
        checkIds = listOf("wifi.present", "network.wifi", "network.trafficstats"),
    )
    private val results = mapOf(
        "wifi.present" to CheckResult(CheckStatus.PASS, "present"),
        "network.wifi" to CheckResult(CheckStatus.PASS, "online"),
        "network.trafficstats" to CheckResult(CheckStatus.FAIL, "unsupported"),
    )

    @Test
    fun withoutWaiverAFailingCheckFails() {
        assertEquals(Outcome.FAIL, evaluate(wifi, results, null, rom = Rom.LINEAGE))
    }

    @Test
    fun wholeRequirementWaiverWaives() {
        val waiver = Waiver("hw.wifi.works", "No eBPF on kernel 3.10")
        assertEquals(Outcome.WAIVED, evaluate(wifi, results, null, rom = Rom.LINEAGE, waiver = waiver))
    }

    @Test
    fun checkWaiverIgnoresOnlyThatCheck() {
        val waiver = Waiver("hw.wifi.works", "No eBPF on kernel 3.10", checks = listOf("network.trafficstats"))
        assertEquals(Outcome.PASS, evaluate(wifi, results, null, rom = Rom.LINEAGE, waiver = waiver))
        val broken = results + ("network.wifi" to CheckResult(CheckStatus.FAIL, "offline"))
        assertEquals(Outcome.FAIL, evaluate(wifi, broken, null, rom = Rom.LINEAGE, waiver = waiver))
    }

    @Test
    fun waivingEveryCheckWaivesTheRequirement() {
        val waiver = Waiver("hw.wifi.works", "reason", checks = wifi.checkIds)
        assertEquals(Outcome.WAIVED, evaluate(wifi, results, null, rom = Rom.LINEAGE, waiver = waiver))
    }

    @Test
    fun testerVerdictBeatsWaiver() {
        val waiver = Waiver("hw.wifi.works", "reason")
        val verdict = ManualVerdict(Verdict.FAIL, "", 0)
        assertEquals(Outcome.FAIL, evaluate(wifi, results, verdict, rom = Rom.LINEAGE, waiver = waiver))
    }

    @Test
    fun waiverAppliesToOffDeviceRequirements() {
        val offDevice = Requirement("general.ownership", Section.GENERAL, Level.MUST, "Owns the device.", TestMode.OUT_OF_SCOPE)
        assertEquals(Outcome.NOT_ON_DEVICE, evaluate(offDevice, emptyMap(), null, rom = Rom.LINEAGE))
        assertEquals(Outcome.WAIVED, evaluate(offDevice, emptyMap(), null, rom = Rom.LINEAGE, waiver = Waiver("general.ownership", "reason")))
    }
}
