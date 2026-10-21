package io.github.rthery.devicechecker.checks

import android.provider.Settings
import java.net.Inet6Address
import java.net.InetAddress

private val MAC_REGEX = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
private const val PLACEHOLDER_MAC = "02:00:00:00:00:00"
private val MAC_IN_TEXT = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")

/** `ip link` is the fallback for policies that keep adb shell out of /sys/class/net. */
internal const val WIFI_MAC_COMMAND = "cat /sys/class/net/wlan0/address 2>/dev/null || ip link show wlan0"

/** The first MAC in `cat` or `ip link` output; ip prints the address before the broadcast one. */
internal fun extractMac(output: String): String? = MAC_IN_TEXT.find(output.lowercase())?.value

/** The MAC encoded in an EUI-64 IPv6 link-local address, or null for random or stable-privacy ones. */
internal fun macFromLinkLocal(address: String): String? {
    val ip = runCatching { InetAddress.getByName(address.substringBefore('/').substringBefore('%')) }.getOrNull()
    val bytes = (ip as? Inet6Address)?.takeIf { it.isLinkLocalAddress }?.address ?: return null
    if (bytes[11] != 0xff.toByte() || bytes[12] != 0xfe.toByte()) return null
    val mac = byteArrayOf((bytes[8].toInt() xor 0x02).toByte(), bytes[9], bytes[10], bytes[13], bytes[14], bytes[15])
    return mac.joinToString(":") { "%02x".format(it) }
}

val identityChecks = listOf(
    Check("wifi.mac", "Wi-Fi MAC address") {
        if (!profile.stockHasWifi) return@Check notApplicable("Stock has no Wi-Fi")
        val read = read(WIFI_MAC_COMMAND) { extractMac(it) != null }
        val mac = read.output?.let(::extractMac) ?: return@Check needsShell(manualCommand = "cat /sys/class/net/wlan0/address")
        compareMac(mac, profile.stockWifiMac, read.access)
    },

    Check("bluetooth.mac", "Bluetooth MAC address") {
        if (!profile.stockHasBluetooth) return@Check notApplicable("Stock has no Bluetooth")
        val asApp = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
            ?.normalizedMac()?.takeIf { it.matches(MAC_REGEX) && it != PLACEHOLDER_MAC }
        if (asApp != null) return@Check compareMac(asApp, profile.stockBluetoothMac, Access.APP)
        val read = read("settings get secure bluetooth_address") { it.normalizedMac().matches(MAC_REGEX) }
        val mac = read.output?.normalizedMac() ?: return@Check needsShell()
        compareMac(mac, profile.stockBluetoothMac, read.access)
    },
)

private fun String.normalizedMac() = trim().lowercase()

private fun compareMac(actual: String, stock: String, access: Access): CheckResult {
    val expected = stock.normalizedMac()
    return when {
        actual == PLACEHOLDER_MAC -> fail("MAC is the $PLACEHOLDER_MAC placeholder", access = access)
        expected.isEmpty() -> warn("Set the stock MAC in the device profile", "Current: $actual", access)
        actual == expected -> pass("$actual matches stock", access = access)
        else -> fail("$actual, stock is $expected", access = access)
    }
}
