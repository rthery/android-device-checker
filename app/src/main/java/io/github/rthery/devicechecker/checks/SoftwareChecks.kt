package io.github.rthery.devicechecker.checks

import io.github.rthery.devicechecker.catalog.Rom
import io.github.rthery.devicechecker.compat.Compat
import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.KeyGenerator

private val SU_PATHS = listOf(
    "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
    "/system/sbin/su", "/product/bin/su", "/system_ext/bin/su", "/debug_ramdisk/su",
)

private const val SECURITY_PATCH_WARN_DAYS = 90L

val softwareChecks = listOf(
    Check("build.type", "Build type") {
        val type = Build.TYPE
        if (rom != Rom.LINEAGE) return@Check notApplicable("userdebug is a LineageOS rule; this $type build is not LineageOS")
        if (type == "userdebug") pass("Build type is userdebug") else fail("Build type is $type, expected userdebug")
    },

    Check("selinux", "SELinux mode") {
        val fromFile = readFileAsApp("/sys/fs/selinux/enforce")
        if (fromFile != null) {
            return@Check if (fromFile == "1") pass("Enforcing") else fail("Permissive (enforce=$fromFile)")
        }
        val read = read("getenforce") { it == "Enforcing" || it == "Permissive" }
        when (read.output) {
            null -> needsShell()
            "Enforcing" -> pass("Enforcing", access = read.access)
            else -> fail(read.output, access = read.access)
        }
    },

    Check("su.absent", "No su binary") {
        val found = SU_PATHS.filter { File(it).exists() }
        val onPath = read("command -v su").output
        val all = (found + listOfNotNull(onPath)).distinct()
        if (all.isEmpty()) {
            pass("No su binary found")
        } else {
            fail(
                "su found: ${all.joinToString()}",
                "If you installed Magisk or KernelSU yourself, this is expected. The requirement is that the build itself does not ship su.",
            )
        }
    },

    Check("binder.64bit", "64-bit Binder API") {
        if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
            return@Check pass("64-bit userspace (${Build.SUPPORTED_64_BIT_ABIS.joinToString()}), which uses the 64-bit Binder API")
        }
        val config = read("zcat /proc/config.gz | grep BINDER_IPC_32BIT") { true }.output
        when {
            config == null -> warn("32-bit userspace and /proc/config.gz is not readable", "Check CONFIG_ANDROID_BINDER_IPC_32BIT in the kernel defconfig.")
            config.contains("=y") -> fail("Kernel uses the 32-bit Binder API", config)
            else -> pass("CONFIG_ANDROID_BINDER_IPC_32BIT is not set", config)
        }
    },

    Check("verity", "System verity") {
        val verityMode = prop("ro.boot.veritymode")
        val bootState = prop("ro.boot.verifiedbootstate")
        val deviceState = prop("ro.boot.vbmeta.device_state")
        val details = "ro.boot.veritymode=$verityMode\nro.boot.verifiedbootstate=$bootState\nro.boot.vbmeta.device_state=$deviceState"
        if (Build.TYPE != "userdebug") return@Check notApplicable("Applies to userdebug builds; this is a ${Build.TYPE} build (dm-verity ${verityMode.ifEmpty { "state unknown" }})")
        when (verityMode) {
            "enforcing", "eio" -> fail("dm-verity is active on this userdebug build ($verityMode)", details)
            "disabled", "logging" -> pass("dm-verity is $verityMode", details)
            else -> verityFromMounts(details)
        }
    },

    Check("crypto.status", "Storage encryption") {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val status = dpm.storageEncryptionStatus
        val details = "ro.crypto.state=${prop("ro.crypto.state")}\nro.crypto.type=${prop("ro.crypto.type")}"
        when (status) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> pass("File-based encryption is active", details)
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> pass("Encryption is active", details)
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> warn("Encrypted with the default key (no lock screen credential)", details)
            DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE ->
                if (profile.stockHasForcedEncryption) fail("Storage is not encrypted, stock forces encryption", details)
                else warn("Storage is not encrypted", details)
            else -> fail("Encryption is not supported (status $status)", details)
        }
    },

    Check("keystore.hardware", "Hardware-backed keystore") {
        if (!profile.stockHasHardwareEncryption) return@Check notApplicable("Stock has no hardware-backed encryption")
        val alias = "device_checker_probe"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }.generateKey()
            val key = keyStore.getKey(alias, null) as javax.crypto.SecretKey
            val keyInfo = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val hasStrongBox = android.os.Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            when (Compat.keySecurity(keyInfo)) {
                Compat.KeySecurity.STRONGBOX -> pass("Keys live in StrongBox")
                Compat.KeySecurity.TEE -> pass("Keys live in secure hardware (TEE)", if (hasStrongBox) "StrongBox is also declared." else "")
                Compat.KeySecurity.SOFTWARE -> fail("Keystore is software-only")
                Compat.KeySecurity.UNKNOWN -> warn("Unknown keystore security level")
            }
        } finally {
            runCatching { keyStore.deleteEntry(alias) }
        }
    },

    Check("build.fingerprint", "Build fingerprint") {
        val fingerprint = Build.FINGERPRINT
        val parts = Regex("""^[^/]+/[^/]+/[^:]+:([^/]+)/[^/]+/[^:]+:([^/]+)/([^/]+)$""").find(fingerprint)?.groupValues
        val partitions = listOf("system", "vendor", "product", "odm").map { "ro.$it.build.fingerprint=${prop("ro.$it.build.fingerprint")}" }.joinToString("\n")
        val details = "Build.FINGERPRINT=$fingerprint\nBuild.TYPE=${Build.TYPE}, release ${Build.VERSION.RELEASE}\n$partitions"
        when {
            parts == null -> warn("Unusual fingerprint format", details)
            parts[1] != Build.VERSION.RELEASE || parts[2] != Build.TYPE ->
                fail("Fingerprint claims Android ${parts[1]} ${parts[2]}/${parts[3]} on an Android ${Build.VERSION.RELEASE} ${Build.TYPE} build: looks spoofed for Play Integrity", details)
            else -> pass("Fingerprint matches the running build", details)
        }
    },

    Check("frp", "Factory Reset Protection") {
        if (!profile.stockHasFrp) return@Check notApplicable("Stock has no FRP")
        val partition = prop("ro.frp.pst")
        if (partition.isNotEmpty()) pass("FRP partition declared", "ro.frp.pst=$partition")
        else warn("ro.frp.pst is empty, FRP will not work with Google apps")
    },

    Check("security.patch", "Security patch level") {
        val patch = Build.VERSION.SECURITY_PATCH
        val details = "Kernel ${System.getProperty("os.version")}\nVendor patch ${prop("ro.vendor.build.security_patch").ifEmpty { "unknown" }}"
        val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(patch), LocalDate.now()) }.getOrNull()
        when {
            age == null -> warn("Cannot parse security patch level '$patch'", details)
            age > SECURITY_PATCH_WARN_DAYS -> warn("Security patch $patch is $age days old", details)
            else -> info("Security patch $patch", details)
        }
    },

    Check("updater.present", "LineageOS Updater") {
        if (rom != Rom.LINEAGE) return@Check notApplicable("Not a LineageOS build")
        if (isInstalled("org.lineageos.updater")) info("Updater is installed. Test an OTA upgrade by hand.")
        else fail("org.lineageos.updater is not installed")
    },

    Check("browser.jelly", "LineageOS browser") {
        if (rom != Rom.LINEAGE) return@Check notApplicable("Not a LineageOS build")
        if (isInstalled("org.lineageos.jelly")) pass("Jelly is installed")
        else warn("Jelly is not installed", "Another LineageOS-sourced browser also satisfies the requirement.")
    },

    Check("livedisplay", "LiveDisplay HAL") {
        if (!lineageBased) return@Check notApplicable("Not a LineageOS-based build")
        val read = read("service list 2>/dev/null; lshal --neat 2>/dev/null") { true }
        val output = read.output ?: return@Check needsShell()
        val matches = output.lines().filter { it.contains("vendor.lineage.livedisplay", ignoreCase = true) }.distinct()
        if (matches.isNotEmpty()) pass("LiveDisplay HAL registered", matches.joinToString("\n"), read.access)
        else warn("No vendor.lineage.livedisplay HAL found", "The lineagelivedisplay framework service is always present and does not count.", read.access)
    },

    Check("blobs.megvii", "No Megvii or SenseTime blobs") {
        val command = "ls -R /vendor/lib /vendor/lib64 /odm/lib /odm/lib64 /system/lib /system/lib64 2>/dev/null; true"
        val read = read(command) { it.lines().size > 20 }
        val output = read.output ?: return@Check needsShell()
        val hits = output.lines().filter { line -> BANNED_VENDOR_MARKERS.any { line.contains(it, ignoreCase = true) } }.distinct()
        if (hits.isEmpty()) pass("No library names match Megvii or SenseTime", access = read.access)
        else fail("Suspicious libraries found", hits.joinToString("\n"), read.access)
    },
)

private val BANNED_VENDOR_MARKERS = listOf("megvii", "megface", "mgface", "sensetime", "stmobile")

internal fun CheckContext.isInstalled(packageName: String): Boolean = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

private val SYSTEM_MOUNT_POINTS = setOf("/", "/system", "/vendor", "/product", "/system_ext")

/** Block devices behind the system partitions, by mount point, from /proc/mounts lines. */
internal fun systemMountSources(mounts: List<String>): Map<String, String> = mounts
    .map { it.split(" ") }
    .filter { it.size >= 2 && it[1] in SYSTEM_MOUNT_POINTS && it[0].startsWith("/dev/") }
    .associate { it[1] to it[0] }

// Devices without AVB or legacy verity set no verity property; dm-verity always sits on device-mapper.
private fun verityFromMounts(details: String): CheckResult {
    val sources = systemMountSources(runCatching { File("/proc/self/mounts").readLines() }.getOrDefault(emptyList()))
    val text = details + "\n" + sources.entries.joinToString("\n") { "${it.key} <- ${it.value}" }
    return when {
        sources.isEmpty() -> warn("Verity state is not exposed and the system mounts are unreadable", text)
        sources.values.any { it.startsWith("/dev/block/dm-") } -> warn("System is on device-mapper but no verity state is exposed", text)
        else -> pass("No dm-verity: the system partitions mount straight from their block devices", text)
    }
}
