package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.KernelVersion
import io.github.rthery.devicechecker.checks.extractMac
import io.github.rthery.devicechecker.checks.macFromLinkLocal
import io.github.rthery.devicechecker.checks.hasSchedulerLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellOutputParserTest {

    @Test
    fun `scheduler read errors are not scheduler lines`() {
        assertFalse(hasSchedulerLines("cat: /sys/block/*/queue/scheduler: No such file or directory\n*: "))
        assertFalse(hasSchedulerLines(""))
    }

    @Test
    fun `scheduler lines are accepted`() {
        assertTrue(hasSchedulerLines("mmcblk0: noop deadline [cfq]\nsda: [mq-deadline] none"))
    }

    @Test
    fun `kernel version parses vendor release strings`() {
        assertEquals(KernelVersion(4, 14), KernelVersion.parse("4.14.356-perf+"))
        assertEquals(KernelVersion(3, 10), KernelVersion.parse("3.10.73-g5a0e4a1"))
        assertNull(KernelVersion.parse("unknown"))
        assertTrue(KernelVersion(4, 9) < KernelVersion(4, 14))
        assertTrue(KernelVersion(5, 4) > KernelVersion(4, 19))
    }

    @Test
    fun `mac comes from sysfs or ip link`() {
        assertEquals("64:bc:0c:12:34:56", extractMac("64:BC:0C:12:34:56\n"))
        val ipLink = """
            30: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP mode DORMANT group default qlen 1000
                link/ether 64:bc:0c:12:34:56 brd ff:ff:ff:ff:ff:ff
        """.trimIndent()
        assertEquals("64:bc:0c:12:34:56", extractMac(ipLink))
        assertNull(extractMac("Device \"wlan0\" does not exist."))
    }

    @Test
    fun `mac comes from an eui-64 link-local address`() {
        assertEquals("64:bc:0c:7f:c0:d0", macFromLinkLocal("fe80::66bc:cff:fe7f:c0d0"))
        assertEquals("64:bc:0c:7f:c0:d0", macFromLinkLocal("fe80::66bc:cff:fe7f:c0d0%wlan0"))
        assertEquals("64:bc:0c:7f:c0:d0", macFromLinkLocal("fe80::66bc:cff:fe7f:c0d0/64"))
    }

    @Test
    fun `privacy and global addresses give no mac`() {
        assertNull(macFromLinkLocal("fe80::1c2d:3e4f:5a6b:7c8d"))
        assertNull(macFromLinkLocal("2a01:e0a::66bc:cff:fe7f:c0d0"))
        assertNull(macFromLinkLocal("192.168.27.180"))
        assertNull(macFromLinkLocal(""))
    }
}
