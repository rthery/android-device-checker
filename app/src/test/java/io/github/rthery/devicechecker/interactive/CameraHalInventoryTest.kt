package io.github.rthery.devicechecker.interactive

import org.junit.Assert.*
import org.junit.Test

class CameraHalInventoryTest {
    @Test fun `parses legacy provider and numbered HAL devices`() {
        val parsed = parseCameraHalInventory("""
            == Camera Provider HAL legacy/0-1 (v2.5, remote) static info: 2 devices: ==
            == Camera HAL device device@3.3/legacy/0 (v3.3) static information: ==
            == Camera HAL device device@3.3/legacy/1 (v3.3) static information: ==
        """.trimIndent())
        assertEquals(listOf("legacy/0-1 (v2.5, remote)"), parsed.providers)
        assertEquals(listOf("device@3.3/legacy/0 (v3.3)", "device@3.3/legacy/1 (v3.3)"), parsed.devices)
        assertTrue(parsed.complete)
    }

    @Test fun `deduplicates entries and recognizes explicitly headed AIDL forms`() {
        val parsed = parseCameraHalInventory("""
            == Camera Provider HAL legacy/0-1 (v2.5, remote) static info: ==
            == Camera Provider HAL legacy/0-1 (v2.5, remote) static info: ==
            == Camera Provider aidl/0 (AIDL, remote) ==
            == Camera device device@1/0 (AIDL) ==
        """.trimIndent())
        assertEquals(listOf("legacy/0-1 (v2.5, remote)", "aidl/0 (AIDL, remote)"), parsed.providers)
        assertEquals(listOf("device@1/0 (AIDL)"), parsed.devices)
    }

    @Test fun `empty denied or unrecognized dump has no invented version`() {
        listOf("", "Permission Denial: can't dump CameraService", "No devices").forEach { dump ->
            val parsed = parseCameraHalInventory(dump)
            assertTrue(parsed.providers.isEmpty())
            assertTrue(parsed.devices.isEmpty())
            assertFalse(parsed.complete)
        }
    }
}
