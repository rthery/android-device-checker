package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.ElfFile
import io.github.rthery.devicechecker.checks.parseElfTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfTypesTest {

    /** toybox file output on a Nexus 5X running /e/OS 4.4.1. */
    private val bullhead = """
        /apex/com.android.runtime/bin/linkerconfig:  ELF executable, 64-bit LSB arm64, static, for Android 33, BuildID=ad0d2e, stripped
        /system/bin/init:     ELF shared object, 64-bit LSB arm64, dynamic (/system/bin/bootstrap/linker64), for Android 33, stripped
        /system/bin/ls:       symbolic link to toybox
        /system/bin/am:       /system/bin/sh script
        /system/vendor/bin/ATFWD-daemon:  ELF shared object, 64-bit LSB arm64, dynamic (/system/bin/linker64), for Android 23, stripped
    """.trimIndent()

    @Test
    fun keepsOnlyElfFilesWithTheirType() {
        assertEquals(
            listOf(
                ElfFile("/apex/com.android.runtime/bin/linkerconfig", executable = true, static = true),
                ElfFile("/system/bin/init", executable = false, static = false),
                ElfFile("/system/vendor/bin/ATFWD-daemon", executable = false, static = false),
            ),
            parseElfTypes(bullhead),
        )
    }

    @Test
    fun dynamicExecutableIsNonPie() {
        val old = parseElfTypes("/vendor/bin/oldd: ELF executable, 32-bit LSB arm, dynamic (/system/bin/linker), stripped").single()
        assertTrue(old.executable && !old.static && old.isVendor)
    }
}
