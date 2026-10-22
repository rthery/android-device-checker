package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.data.DeviceInfo
import io.github.rthery.devicechecker.data.buildJsonReport
import io.github.rthery.devicechecker.data.toHtml
import io.github.rthery.devicechecker.profile.DeviceProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlReportTest {
    private val html = buildJsonReport(DeviceInfo(listOf("Device" to "<b>Nexus</b> & co")), DeviceProfile(), emptyMap(), emptyMap()).toHtml()

    @Test
    fun deviceTextIsEscapedAndThePageIsSelfContained() {
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("&lt;b&gt;Nexus&lt;/b&gt; &amp; co"))
        assertFalse(html.contains("<b>Nexus</b>"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("http://") || html.contains("https://fonts"))
    }

    @Test
    fun everyRequirementGetsASection() {
        val sections = Regex("<section class=\"card req\">").findAll(html).count()
        assertTrue(sections > 50)
    }
}
