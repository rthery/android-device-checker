package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.Access
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.codecResult
import io.github.rthery.devicechecker.checks.parseStackCodecs
import io.github.rthery.devicechecker.checks.withoutMissingAptx
import io.github.rthery.devicechecker.interactive.formatLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothCodecsTest {

    private val dump = """
        Profile: A2dpService
          mActiveDevice: null
          mMaxConnectedAudioDevices: 5
          codecConfigPriorities:
            SBC: 1001
            AAC: 2001
            aptX: 3001
            aptX HD: 4001
            LDAC: 5001
            Opus: 7001
          mA2dpOffloadEnabled: false
    """.trimIndent()

    @Test
    fun parsesCodecPriorities() {
        assertEquals(listOf("SBC", "AAC", "aptX", "aptX HD", "LDAC", "Opus"), parseStackCodecs(dump))
    }

    @Test
    fun noPrioritiesMeansNoList() {
        assertEquals(emptyList<String>(), parseStackCodecs("Bluetooth Status\n  enabled: true"))
    }

    @Test
    fun dropsAptxWithoutItsLibrary() {
        val listed = listOf("SBC", "AAC", "aptX", "aptX HD", "LDAC")
        assertEquals(listOf("SBC", "AAC", "aptX", "LDAC"), withoutMissingAptx(listed, "libaptX_encoder.so\n"))
        assertEquals(listOf("SBC", "AAC", "LDAC"), withoutMissingAptx(listed, ""))
    }

    @Test
    fun addedOlderAptxPasses() {
        val result = codecResult(parseStackCodecs(dump), listOf("SBC", "AAC", "LDAC"), "", Access.SHIZUKU)
        assertEquals(CheckStatus.PASS, result.status)
        assertEquals("Every stock codec, plus aptX, aptX HD, Opus", result.summary)
    }

    @Test
    fun missingStockCodecFails() {
        assertEquals(CheckStatus.FAIL, codecResult(listOf("SBC", "AAC"), listOf("SBC", "AAC", "LDAC"), "", Access.SHIZUKU).status)
    }

    @Test
    fun addedAptxAdaptiveFails() {
        assertEquals(CheckStatus.FAIL, codecResult(listOf("SBC", "aptX Adaptive"), listOf("SBC"), "", Access.SHIZUKU).status)
    }

    @Test
    fun noStockListWarns() {
        assertEquals(CheckStatus.WARN, codecResult(listOf("SBC"), emptyList(), "", Access.SHIZUKU).status)
    }

    @Test
    fun formatsCodecFlags() {
        assertEquals("96 kHz 32-bit", formatLabel(0x8, 0x4))
        assertEquals("", formatLabel(0, 0))
    }
}
