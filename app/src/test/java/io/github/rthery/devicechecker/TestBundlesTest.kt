package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.data.Category
import io.github.rthery.devicechecker.data.SummaryPart
import io.github.rthery.devicechecker.data.TestCatalog
import io.github.rthery.devicechecker.data.TestState
import io.github.rthery.devicechecker.data.bundleState
import io.github.rthery.devicechecker.data.bundleTests
import io.github.rthery.devicechecker.data.testSummary
import io.github.rthery.devicechecker.data.stateOf
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestBundlesTest {
    private val catalog = TestCatalog.builtIn
    private val groups = bundleTests(catalog.all)

    @Test
    fun everyTestAppearsExactlyOnceAndKeepsItsIdentity() {
        val members = groups.flatMap { it.tests }
        assertEquals(catalog.all.sortedBy { it.id }, members.sortedBy { it.id })
        assertEquals(members.size, members.map { it.id }.toSet().size)
        assertEquals(groups.size, groups.map { it.id }.toSet().size)
        assertTrue("Related checks should reduce the list substantially", groups.size < catalog.all.size / 2)
    }

    @Test
    fun relatedChecksAndActionsAreTogetherWithoutMixingUnrelatedHardware() {
        fun members(id: String) = groups.single { group -> group.tests.any { it.id == id } }.tests.map { it.id }.toSet()
        assertTrue(members("wifi.present").containsAll(listOf("network.wifi", "wifi.mac", "tether.wifi")))
        assertEquals(setOf("gnss.present", "gnss.fix"), members("gnss.fix"))
        assertEquals(setOf("fingerprint.present", "fingerprint.auth"), members("fingerprint.auth"))
        assertEquals(setOf("nfc.tag"), members("nfc.tag"))
        assertTrue("audio.input" !in members("audio.output"))
        assertTrue("codecs.hardware" !in members("camera.capture"))
        assertEquals(Category.CONNECTIVITY, groups.single { it.tests.any { t -> t.id == "call.audio" } }.category)
    }

    @Test
    fun bundleStateNeverHidesAFailureOrAnUnfinishedCheck() {
        val tests = groups.single { it.tests.any { test -> test.id == "gnss.fix" } }.tests
        val states = tests.associate { it.id to TestState.PASS }
        assertEquals(TestState.PASS, bundleState(tests, states))
        assertEquals(TestState.NEEDS, bundleState(tests, states + ("gnss.fix" to TestState.NEEDS)))
        assertEquals(TestState.FAIL, bundleState(tests, states + ("gnss.present" to TestState.FAIL)))
        assertEquals(TestState.NOT_RUN, bundleState(tests, states + ("gnss.fix" to TestState.NOT_RUN)))
        assertEquals(TestState.RUNNING, bundleState(tests, states + ("gnss.present" to TestState.RUNNING)))
    }

    @Test
    fun categorySummaryAccountsForPassedPendingWarningsAndSkippedChecks() {
        val tests = catalog.all.take(6)
        val states = tests.mapIndexed { index, test -> test.id to listOf(TestState.PASS, TestState.FAIL, TestState.WARN, TestState.NOT_RUN, TestState.NA, TestState.WAIVED)[index] }.toMap()
        assertEquals(
            listOf(SummaryPart.FAILED, SummaryPart.TO_CHECK, SummaryPart.NOT_RUN, SummaryPart.PASSED, SummaryPart.NA, SummaryPart.WAIVED).map { it to 1 },
            testSummary(tests, states),
        )
    }

    @Test
    fun unknownPackTestsRemainVisibleAsSeparateEntries() {
        val custom = catalog.all.first().copy(id = "pack.example.custom", source = "Example")
        val bundled = bundleTests(catalog.all + custom)
        assertEquals(listOf(custom), bundled.single { custom in it.tests }.tests)
    }

    @Test
    fun functionalPassConfirmsPresenceButNeverMasksAProbeFailure() {
        val probe = catalog.byId.getValue("gnss.present")
        val results = mapOf("gnss.present" to CheckResult(CheckStatus.INFO, "Run GPS"), "gnss.fix" to CheckResult(CheckStatus.PASS, "Fix"))
        assertEquals(TestState.PASS, stateOf(probe, results, emptyMap(), Rom.LINEAGE, null))
        assertEquals(TestState.FAIL, stateOf(probe, results + (probe.id to CheckResult(CheckStatus.FAIL, "Missing hardware")), emptyMap(), Rom.LINEAGE, null))
        assertEquals(TestState.WARN, stateOf(probe, results + (probe.id to CheckResult(CheckStatus.WARN, "Missing configuration")), emptyMap(), Rom.LINEAGE, null))
        assertEquals(TestState.NEEDS, stateOf(probe, results - "gnss.fix", emptyMap(), Rom.LINEAGE, null))
    }
}
