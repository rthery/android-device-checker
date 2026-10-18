package io.github.rthery.devicechecker.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandAllowlistTest {

    private fun allowed(command: String) = assertNull(command, CommandAllowlist.check(command))
    private fun refused(command: String) = assertNotNull(command, CommandAllowlist.check(command))

    @Test
    fun allowsReadOnlySources() {
        allowed("getprop")
        allowed("getprop ro.product.device")
        allowed("cat /sys/class/power_supply/battery/technology")
        allowed("cat /proc/version /proc/cpuinfo")
        allowed("dumpsys battery")
        allowed("dumpsys batterystats --charged")
        allowed("settings get secure reduce_bright_colors_activated")
        allowed("settings list global")
        allowed("cmd package list packages foundation.e")
        allowed("cmd wifi get-country-code")
    }

    @Test
    fun allowsTextFiltersInPipelines() {
        allowed("dumpsys power | grep -iE 'wake lock|PARTIAL' | head -20")
        allowed("dumpsys usb | grep \"current_functions\" | wc -l")
        allowed("cmd package list packages | sort | tail -n 5")
    }

    @Test
    fun refusesOtherCommands() {
        refused("")
        refused("rm -rf /sdcard")
        refused("svc power reboot")
        refused("setprop persist.sys.foo 1")
        refused("logcat -b crash -c")
        refused("am start -a android.intent.action.VIEW")
    }

    @Test
    fun refusesWrites() {
        refused("settings put secure foo 1")
        refused("settings delete global foo")
        refused("settings get secure")
        refused("cmd package uninstall com.example")
        refused("cmd wifi set-wifi-enabled disabled")
        refused("dumpsys batterystats --reset")
        refused("dumpsys battery unplug")
        refused("dumpsys battery set level 5")
        refused("dumpsys deviceidle force-idle")
    }

    @Test
    fun catStaysUnderSysAndProc() {
        refused("cat /data/system/users/0/settings_secure.xml")
        refused("cat /sys/../data/misc/wifi/WifiConfigStore.xml")
        refused("cat")
        refused("dumpsys usb | grep x /data/local/tmp/secret")
    }

    @Test
    fun usesPositiveListsForArguments() {
        refused("dumpsys activity processes")
        refused("dumpsys deviceidle whitelist +com.example")
        refused("cmd uimode night yes")
        refused("cmd activity get-standby-bucket")
        refused("cmd settings put global foo 1")
        allowed("cmd settings get secure location_mode")
        refused("dumpsys usb | grep x secret.txt")
        refused("dumpsys usb | grep -e x secret.txt")
        refused("dumpsys usb | grep -r x")
        refused("dumpsys usb | grep -f patterns")
        refused("dumpsys usb | sort input.txt")
        refused("dumpsys usb | sort -no out")
        refused("dumpsys usb | head --lines=5")
        allowed("dumpsys usb | grep -A 2 -i 'connected' | cut -d ' ' -f 2 | tr -d x")
        allowed("dumpsys usb | head -n 5 | tail -5")
    }

    @Test
    fun catCannotFollowProcessLinks() {
        allowed("cat /proc/net/dev")
        allowed("cat /sys/class/leds/red/brightness")
        refused("cat /proc/self/root/data/system/locksettings.db")
        refused("cat /proc/thread-self/root/data/x")
        refused("cat /proc/1/root/data/x")
        refused("cat /proc/1234/environ")
        refused("cat /proc/net/../self/cwd/x")
        refused("cat /sys/fs/../../data/x")
        refused("cat /sys/x/./y")
        refused("cat /sys//x")
        refused("cat /proc/sys/fd/0")
        refused("cat /proc/sys/exe")
        assertEquals(false, CommandAllowlist.isSafeKernelPath("/proc/self/root/data"))
        assertEquals(true, CommandAllowlist.isSafeKernelPath("/proc/device-tree/memory/qsecom_region@0/reg"))
    }

    @Test
    fun refusesGlobsAndExtraSortFlags() {
        refused("cat /sys/class/thermal/thermal_zone*/temp")
        refused("cat /proc/?/root/x")
        refused("cat /proc/[0-9]/root/x")
        allowed("dumpsys usb | grep 'a*b?[c]'")
        allowed("dumpsys usb | sort -nru")
        refused("dumpsys usb | sort -k 2")
        refused("dumpsys usb | sort -T /sdcard")
        refused("dumpsys usb | sort -nro x")
        refused("dumpsys battery --reset")
        refused("dumpsys battery -ar")
        refused("dumpsys procstats --checkin")
        refused("dumpsys batterystats -c")
    }

    @Test
    fun refusesShellMetacharacters() {
        refused("getprop; rm -rf /")
        refused("getprop && reboot")
        refused("getprop || reboot")
        refused("getprop > /sdcard/out")
        refused("cat /proc/version < /dev/zero")
        refused("getprop \$(reboot)")
        refused("getprop `reboot`")
        refused("dumpsys usb | grep \"\$HOME\"")
        refused("getprop ro.x\nreboot")
        refused("getprop 'unclosed")
        refused("dumpsys power | sh")
        refused("dumpsys power | tail -f")
        refused("cmd package list packages | sort -o /sdcard/x")
    }

    @Test
    fun singleQuotesKeepMetacharactersLiteral() {
        allowed("dumpsys power | grep -E 'a|b;c\$'")
        assertEquals(listOf(listOf("dumpsys", "power"), listOf("grep", "-E", "a|b;c\$")), CommandAllowlist.tokenize("dumpsys power | grep -E 'a|b;c\$'"))
    }
}
