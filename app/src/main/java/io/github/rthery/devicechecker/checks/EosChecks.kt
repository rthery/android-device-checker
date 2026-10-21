package io.github.rthery.devicechecker.checks

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import io.github.rthery.devicechecker.catalog.Rom

private val EOS_CORE_APPS = listOf("foundation.e.apps", "foundation.e.advancedprivacy")
private const val EOS_BROWSER = "foundation.e.browser"
private const val GMS_PACKAGE = "com.google.android.gms"

/** Declared by every microG GmsCore whatever its label (/e/OS 4.4 ships it as "App Compatibility Services") or signer. */
internal val MICROG_PERMISSIONS = listOf("org.microg.gms.EXTENDED_ACCESS", "org.microg.gms.PROVISION", "org.microg.gms.STATUS_BROADCAST")
private val GOOGLE_DNS = setOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")

val eosChecks = listOf(
    Check("eos.buildtype", "/e/OS build type") {
        if (rom != Rom.EOS) return@Check notApplicable("Not an /e/OS build")
        when (val type = Build.TYPE) {
            "user" -> pass("Build type is user")
            "userdebug" -> warn("Build type is userdebug. Fine for validation, ship a user build publicly.", "Build.DISPLAY says ${Build.DISPLAY}: /e/OS hardcodes \"-user\" in it even on userdebug builds.")
            else -> fail("Build type is $type, expected user for public releases")
        }
    },

    Check("eos.apps", "/e/OS core apps") {
        if (rom != Rom.EOS) return@Check notApplicable("Not an /e/OS build")
        val missing = EOS_CORE_APPS.filterNot { isInstalled(it) }
        if (missing.isEmpty()) pass("App Lounge and Advanced Privacy are installed")
        else fail("Missing: ${missing.joinToString()}")
    },

    Check("eos.browser", "/e/OS browser") {
        if (rom != Rom.EOS) return@Check notApplicable("Not an /e/OS build")
        if (isInstalled(EOS_BROWSER)) pass("The /e/OS browser is installed") else fail("$EOS_BROWSER is not installed")
    },

    Check("eos.microg", "microG services") {
        if (rom != Rom.EOS) return@Check notApplicable("Not an /e/OS build")
        val packageManager = context.packageManager
        val gms = try {
            packageManager.getPackageInfo(GMS_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@Check fail("$GMS_PACKAGE is not installed")
        }
        val label = gms.applicationInfo?.loadLabel(packageManager)?.toString().orEmpty()
        val details = "Label: $label\nVersion: ${gms.versionName}"
        val microg = isMicrog { permission ->
            runCatching { packageManager.getPermissionInfo(permission, 0).packageName }.getOrNull()
        }
        if (microg) info("microG ${gms.versionName} is installed. Run its self-check.", details)
        else warn("$GMS_PACKAGE is installed but declares no microG permission: it is not microG", details)
    },

    Check("eos.dns", "Default DNS") {
        if (rom != Rom.EOS) return@Check notApplicable("Not an /e/OS build")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return@Check warn("No active network. Connect to Wi-Fi or mobile data and run again.")
        val servers = connectivity.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        val mode = runCatching { Settings.Global.getString(context.contentResolver, "private_dns_mode") }.getOrNull()
        val specifier = runCatching { Settings.Global.getString(context.contentResolver, "private_dns_specifier") }.getOrNull()
        val details = "DNS servers: ${servers.joinToString().ifEmpty { "none" }}\nPrivate DNS: ${mode ?: "unknown"}${specifier?.let { " ($it)" }.orEmpty()}"
        val google = servers.filter { it in GOOGLE_DNS }
        when {
            google.isEmpty() -> pass("No Google DNS server in use", details)
            mode == "hostname" -> info("Google DNS is listed for this network, but private DNS overrides it", details)
            else -> warn("Google DNS in use: ${google.joinToString()}", "$details\nThe network may hand these out. Check on another network before failing the build.")
        }
    },

    Check("eos.verifiedboot", "Relocked bootloader") {
        if (rom != Rom.EOS) return@Check notApplicable("Not an /e/OS build")
        val deviceState = prop("ro.boot.vbmeta.device_state")
        val flashLocked = prop("ro.boot.flash.locked")
        val bootState = prop("ro.boot.verifiedbootstate")
        val verityMode = prop("ro.boot.veritymode")
        val details = "ro.boot.vbmeta.device_state=$deviceState\nro.boot.flash.locked=$flashLocked\nro.boot.verifiedbootstate=$bootState\nro.boot.veritymode=$verityMode"
        val locked = deviceState == "locked" || flashLocked == "1"
        val verityOff = verityMode == "disabled" || verityMode == "logging"
        when {
            !locked && (deviceState == "unlocked" || flashLocked == "0" || bootState == "orange") ->
                info("Bootloader unlocked. Relocking only applies to official builds on lockable devices.", details)
            deviceState.isEmpty() && flashLocked.isEmpty() && bootState.isEmpty() ->
                notApplicable("This bootloader does not report its lock or verified boot state").copy(details = details)
            !locked -> warn("Lock state is not exposed", details)
            bootState in setOf("green", "yellow") && !verityOff -> pass("Bootloader locked, verified boot $bootState", details)
            else -> fail("Bootloader locked but verified boot is $bootState and verity ${verityMode.ifEmpty { "unknown" }}", details)
        }
    },
)

/** [permissionOwner] returns the package that declares a permission, or null when none does. */
internal fun isMicrog(permissionOwner: (String) -> String?): Boolean = MICROG_PERMISSIONS.any { permissionOwner(it) == GMS_PACKAGE }
