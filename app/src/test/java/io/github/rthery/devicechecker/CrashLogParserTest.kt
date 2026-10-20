package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.fromCurrentBoot
import io.github.rthery.devicechecker.checks.parseCrashLog
import io.github.rthery.devicechecker.checks.parseDropbox
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLogParserTest {

    private val nativeCrash = """
        10-18 09:29:16.455  2218  2580 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 2580 (bt_stack_manage), pid 2218 (droid.bluetooth)
        10-18 09:29:17.529  9339  9339 F DEBUG   : Cmdline: com.android.bluetooth
        10-18 09:29:17.529  9339  9339 F DEBUG   : pid: 2218, tid: 2580, name: bt_stack_manage  >>> com.android.bluetooth <<<
        10-18 09:29:17.529  9339  9339 F DEBUG   : Abort message: 'assertion failed - Receive set scan enable with error code COMMAND_DISALLOWED'
        10-18 09:29:17.530  9339  9339 F DEBUG   :       #00 pc 000000000008cae4  /apex/com.android.runtime/lib64/bionic/libc.so (abort+180) (BuildId: 2b0f)
        10-18 09:29:17.530  9339  9339 F DEBUG   :       #04 pc 00000000009df090  /apex/com.android.btservices/lib64/libbluetooth_jni.so (bluetooth::hci::LeScanningManager::impl::check_status(bluetooth::hci::CommandCompleteView)+1120) (BuildId: be93)
    """.trimIndent()

    private val javaCrash = """
        10-18 06:10:01.000  1500  1500 E AndroidRuntime: FATAL EXCEPTION: main
        10-18 06:10:01.000  1500  1500 E AndroidRuntime: Process: com.android.phone, PID: 1500
        10-18 06:10:01.000  1500  1500 E AndroidRuntime: java.lang.IllegalAccessError: Method 'void java.lang.System.arraycopy' is inaccessible
    """.trimIndent()

    @Test
    fun parsesNativeCrash() {
        val crash = parseCrashLog(nativeCrash).single()
        assertEquals("com.android.bluetooth", crash.process)
        assertEquals("signal 6 SIGABRT", crash.kind)
        assertEquals("assertion failed - Receive set scan enable with error code COMMAND_DISALLOWED", crash.reason)
        assertEquals(2, crash.frames.size)
        assertEquals("libbluetooth_jni.so bluetooth::hci::LeScanningManager::impl::check_status(bluetooth::hci::CommandCompleteView)+1120", crash.frames[1])
    }

    @Test
    fun parsesJavaCrashAfterNativeOne() {
        val crashes = parseCrashLog(nativeCrash + "\n" + javaCrash)
        assertEquals(2, crashes.size)
        assertEquals("com.android.phone", crashes[1].process)
        assertEquals("java.lang.IllegalAccessError: Method 'void java.lang.System.arraycopy' is inaccessible", crashes[1].reason)
    }

    @Test
    fun parsesDropboxEntries() {
        val output = """
            2026-10-18 00:02:47 SYSTEM_TOMBSTONE (compressed text, 15176 bytes)
            Cmdline: /vendor/bin/slim_daemon --pip DISABLED --sap PREMIUM
            2026-10-18 09:29:18 SYSTEM_TOMBSTONE (compressed text, 15389 bytes)
            Cmdline: com.android.bluetooth
            Abort message: 'assertion failed - COMMAND_DISALLOWED'
            2026-10-18 09:48:50 system_app_wtf (compressed text, 989 bytes)
            Process: com.android.networkstack.process
            Subject: TcpSocketTracker
        """.trimIndent()
        val entries = parseDropbox(output)
        assertEquals(3, entries.size)
        assertEquals("/vendor/bin/slim_daemon --pip DISABLED --sap PREMIUM", entries[0].process)
        assertEquals("assertion failed - COMMAND_DISALLOWED", entries[1].reason)
        assertEquals(true, entries[2].isWtf)
        assertEquals("TcpSocketTracker", entries[2].reason)
    }

    private val previousBuild = "google/bullhead/bullhead:13/TQ3A.230901.001/eng.romain.20261012.101500:userdebug/test-keys"
    private val currentBuild = "google/bullhead/bullhead:13/TQ3A.230901.001/eng.romain.20261019.221000:userdebug/test-keys"

    /** The browser crash-looped on the previous build; after the OTA dropbox still holds those entries. */
    private val dropboxAcrossOta = """
        2026-10-20 09:10:01 data_app_crash (text, 2210 bytes)
        Process: foundation.e.browser
        Build: $previousBuild
        2026-10-20 09:10:07 data_app_crash (text, 2210 bytes)
        Process: foundation.e.browser
        Build: $previousBuild
        2026-10-20 09:10:15 data_app_crash (text, 2210 bytes)
        Process: foundation.e.browser
        Build: $previousBuild
        2026-10-20 09:12:30 SYSTEM_TOMBSTONE (compressed text, 15176 bytes)
        Build fingerprint: '$previousBuild'
        Cmdline: /vendor/bin/slim_daemon
        2026-10-20 10:05:44 data_app_crash (text, 1980 bytes)
        Process: foundation.e.apps
        Build: $currentBuild
        2026-10-20 10:06:02 SYSTEM_TOMBSTONE (compressed text, 15389 bytes)
        Build fingerprint: '$currentBuild'
        Cmdline: com.android.bluetooth
        2026-10-20 10:07:00 system_app_wtf (compressed text, 989 bytes)
        Process: com.android.networkstack.process
        Subject: TcpSocketTracker
    """.trimIndent()

    @Test
    fun readsTheBuildOfEachDropboxEntry() {
        val entries = parseDropbox(dropboxAcrossOta)
        assertEquals(previousBuild, entries[0].build)
        assertEquals(previousBuild, entries[3].build)
        assertEquals(currentBuild, entries[5].build)
        assertEquals(null, entries[6].build)
    }

    @Test
    fun keepsOnlyEntriesFromTheRunningBuildAndBoot() {
        val entries = parseDropbox(dropboxAcrossOta)
        val kept = entries.fromCurrentBoot(bootMillis = entries[0].timeMillis, fingerprints = setOf(currentBuild))
        assertEquals(listOf("foundation.e.apps", "com.android.bluetooth", "com.android.networkstack.process"), kept.map { it.process })
    }

    @Test
    fun dropsEntriesLoggedBeforeBoot() {
        val entries = parseDropbox(dropboxAcrossOta)
        val kept = entries.fromCurrentBoot(bootMillis = entries[5].timeMillis, fingerprints = setOf(currentBuild))
        assertEquals(listOf("com.android.bluetooth", "com.android.networkstack.process"), kept.map { it.process })
    }
}
