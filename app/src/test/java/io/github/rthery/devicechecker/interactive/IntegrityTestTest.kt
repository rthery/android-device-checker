package io.github.rthery.devicechecker.interactive

import io.github.rthery.devicechecker.checks.CheckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityTestTest {
    private val resultScreen = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
        <node index="0" text="" resource-id="gr.nikolasspyr.integritycheck:id/json_response" class="android.widget.Button" content-desc="Show JSON response" clickable="true" bounds="[827,84][975,210]" />
        <node index="1" text="" resource-id="gr.nikolasspyr.integritycheck:id/basic_integrity_icon" class="android.widget.ImageView" content-desc="Fail" bounds="[154,679][280,805]" />
        <node index="2" text="CHECK" resource-id="gr.nikolasspyr.integritycheck:id/check_btn" class="android.widget.Button" content-desc="" clickable="true" bounds="[425,1214][656,1340]" />
        </hierarchy>
    """.trimIndent()

    private val jsonDialog = """
        <node index="0" text="Raw JSON response" resource-id="gr.nikolasspyr.integritycheck:id/alertTitle" class="android.widget.TextView" content-desc="" bounds="[126,331][514,388]" />
        <node index="0" text='{&#10;    "deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_BASIC_INTEGRITY"]}&#10;}' resource-id="gr.nikolasspyr.integritycheck:id/message" class="android.widget.TextView" content-desc="" bounds="[63,388][1017,1448]" />
        <node index="1" text="OK" resource-id="android:id/button1" class="android.widget.Button" content-desc="" bounds="[828,1453][996,1579]" />
    """.trimIndent()

    @Test
    fun readsIdsDescriptionsAndCenters() {
        val nodes = parseUiDump(resultScreen)
        assertEquals(3, nodes.size)
        assertEquals("Fail", nodes.first { it.resourceId.endsWith("basic_integrity_icon") }.description)
        assertEquals(540 to 1277, nodes.first { it.text == "CHECK" }.center)
    }

    @Test
    fun readsSingleQuotedJsonText() {
        val message = parseUiDump(jsonDialog).first { it.resourceId.endsWith(":id/message") }
        assertTrue(message.text.startsWith("{\n"))
        assertTrue(parseIntegrityJson(message.text)!!.meetsBasic)
    }

    @Test
    fun parsesAFullVerdict() {
        val json = """
            {"deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_BASIC_INTEGRITY"]},
             "appIntegrity": {"appRecognitionVerdict": "PLAY_RECOGNIZED"},
             "accountDetails": {"appLicensingVerdict": "LICENSED"},
             "environmentDetails": {"playProtectVerdict": "NO_ISSUES"}}
        """.trimIndent()
        val verdict = parseIntegrityJson(json)!!
        assertEquals(listOf("MEETS_BASIC_INTEGRITY"), verdict.device)
        assertEquals("PLAY_RECOGNIZED", verdict.appRecognition)
        assertEquals("LICENSED", verdict.licensing)
        assertEquals("NO_ISSUES", verdict.playProtect)
        assertEquals(CheckStatus.PASS, integrityResult(verdict, "com.android.vending", json).status)
    }

    @Test
    fun noDeviceVerdictFails() {
        val json = """{"deviceIntegrity": {"recentDeviceActivity": {"deviceActivityLevel": "UNEVALUATED"}, "deviceAttributes": {}},
            "appIntegrity": {"appRecognitionVerdict": "UNEVALUATED"}}"""
        val verdict = parseIntegrityJson(json)!!
        assertFalse(verdict.meetsBasic)
        val result = integrityResult(verdict, "com.android.vending", json)
        assertEquals(CheckStatus.FAIL, result.status)
        assertTrue(result.details.contains("Device: no verdict"))
    }

    @Test
    fun sideloadedInstallIsNoted() {
        val json = """{"deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_BASIC_INTEGRITY"]}}"""
        val result = integrityResult(parseIntegrityJson(json)!!, null, json)
        assertEquals(CheckStatus.PASS, result.status)
        assertTrue(result.details.contains("Not installed from the Play Store"))
    }

    @Test
    fun rejectsNonVerdictJson() {
        assertNull(parseIntegrityJson("not json"))
        assertNull(parseIntegrityJson("""{"error": "x"}"""))
    }

    @Test
    fun iconFallback() {
        assertEquals(CheckStatus.PASS, integrityIconResult("Pass", null).status)
        assertEquals(CheckStatus.FAIL, integrityIconResult("Fail", null).status)
        assertEquals(CheckStatus.WARN, integrityIconResult("Loading", null).status)
    }

    @Test
    fun parsesInstaller() {
        assertEquals("com.android.vending", parseInstaller("package:gr.nikolasspyr.integritycheck  installer=com.android.vending"))
        assertNull(parseInstaller("package:gr.nikolasspyr.integritycheck  installer=null"))
    }
}
