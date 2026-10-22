package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.systemMountSources
import org.junit.Assert.assertEquals
import org.junit.Test

class VerityMountsTest {

    /** /proc/mounts on a Nexus 5X running /e/OS 4.4.1: system-as-root, no dm-verity. */
    private val bullhead = listOf(
        "rootfs / rootfs rw,seclabel 0 0",
        "/dev/block/platform/soc.0/f9824900.sdhci/by-name/system / ext4 ro,seclabel,nodev,relatime 0 0",
        "tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,mode=755 0 0",
        "/dev/block/platform/soc.0/f9824900.sdhci/by-name/userdata /data ext4 rw,seclabel 0 0",
    )

    @Test
    fun systemAsRootUsesTheBlockDeviceNotRootfs() {
        assertEquals(mapOf("/" to "/dev/block/platform/soc.0/f9824900.sdhci/by-name/system"), systemMountSources(bullhead))
    }

    @Test
    fun deviceMapperSourcesAreKept() {
        val avb = listOf("/dev/block/dm-0 / ext4 ro 0 0", "/dev/block/dm-1 /vendor ext4 ro 0 0")
        assertEquals(mapOf("/" to "/dev/block/dm-0", "/vendor" to "/dev/block/dm-1"), systemMountSources(avb))
    }
}
