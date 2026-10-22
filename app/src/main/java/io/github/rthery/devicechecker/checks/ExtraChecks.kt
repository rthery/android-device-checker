package io.github.rthery.devicechecker.checks

import io.github.rthery.devicechecker.compat.Compat
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.random.Random

private const val CONNECTIVITY_URL = "https://connectivitycheck.gstatic.com/generate_204"
private const val NETWORK_TIMEOUT_MS = 10_000
private const val SD_TEST_BYTES = 16 * 1024 * 1024
private const val SDP_MAX_UA = 500_000
private const val SDP_USB3_MAX_UA = 900_000

val extraChecks = listOf(
    Check("audio.effects", "Voice processing effects") {
        val audio = context.getSystemService(AudioManager::class.java)
        val mics = Compat.microphones(audio)
        val aec = AcousticEchoCanceler.isAvailable()
        val ns = NoiseSuppressor.isAvailable()
        val agc = AutomaticGainControl.isAvailable()
        val details = "Echo canceller: $aec\nNoise suppressor: $ns\nGain control: $agc\nMicrophones:\n" +
            mics.joinToString("\n") { "  $it" }
        if (aec && ns) pass("Echo cancellation and noise suppression available, ${mics.size} mic(s)", details)
        else warn("Missing ${listOfNotNull("echo cancellation".takeIf { !aec }, "noise suppression".takeIf { !ns }).joinToString(" and ")}", details)
    },

    Check("network.wifi", "Wi-Fi internet") {
        if (!profile.stockHasWifi) return@Check notApplicable("Stock has no Wi-Fi")
        probeInternet(NetworkCapabilities.TRANSPORT_WIFI)
            ?: warn("No Wi-Fi network with internet. Connect to Wi-Fi and run again.")
    },

    Check("network.cellular", "Mobile data") {
        if (!profile.stockHasTelephony) return@Check notApplicable("Stock has no RIL")
        val telephony = context.getSystemService(TelephonyManager::class.java)
        if (telephony.simState != TelephonyManager.SIM_STATE_READY) return@Check warn("No SIM ready. Insert a SIM with data and run again.")
        probeInternet(NetworkCapabilities.TRANSPORT_CELLULAR)
            ?: fail("SIM ready but no mobile data network came up in ${NETWORK_TIMEOUT_MS / 1000} s", "Check that mobile data is on and the APN is set.")
    },

    Check("network.trafficstats", "Traffic accounting") { trafficAccounting() },

    Check("sd.readwrite", "SD card write and read") {
        if (!profile.stockHasSdSlot) return@Check notApplicable("Stock has no SD slot")
        val dir = context.getExternalFilesDirs(null).drop(1).firstOrNull {
            it != null && Environment.isExternalStorageRemovable(it) && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
        } ?: return@Check warn("No SD card mounted. Insert one and run again.")
        withContext(Dispatchers.IO) { sdRoundTrip(dir) }
    },

    Check("charging.usb", "USB charging current") {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val read = read(
            "for f in type real_type usb_type current_max input_current_max voltage_max; do v=\$(cat /sys/class/power_supply/usb/\$f 2>/dev/null) && echo \"\$f=\$v\"; done; true"
        ) { it.contains("=") }
        val values = read.output?.lines()?.mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()
        val currentNow = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val details = values.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\nbattery current_now=$currentNow µA\nplugged=$plugged"
        if (plugged != BatteryManager.BATTERY_PLUGGED_USB && plugged != BatteryManager.BATTERY_PLUGGED_AC) {
            return@Check warn("Not charging. Plug into a computer USB port and run again.", details, read.access)
        }
        if (values.isEmpty()) return@Check needsShell()
        val type = (values["real_type"] ?: values["usb_type"] ?: values["type"]).orEmpty()
        val limit = (values["input_current_max"] ?: values["current_max"])?.toIntOrNull()?.let(::toMicroAmps)
        val isSdp = type.equals("USB", ignoreCase = true) || type.contains("SDP", ignoreCase = true)
        when {
            isSdp && limit != null && limit > SDP_USB3_MAX_UA -> fail("Standard USB port but input limit is ${limit / 1000} mA", details, read.access)
            isSdp && limit != null && limit > SDP_MAX_UA -> warn("Standard USB port with ${limit / 1000} mA limit (only valid on USB 3)", details, read.access)
            else -> info("Charger type $type, limit ${limit?.div(1000) ?: "?"} mA. Compare with stock.", details, read.access)
        }
    },

    Check("battery.capacity", "Battery capacity") {
        val read = read(
            "for d in battery bms; do for f in charge_full_design charge_full cycle_count; do " +
                "v=\$(cat /sys/class/power_supply/\$d/\$f 2>/dev/null) && echo \"\$d/\$f=\$v\"; done; done; true"
        ) { it.contains("charge_full") }
        val output = read.output
            ?: return@Check if (hasPrivilegedShell) warn("No charge_full_design under the battery or bms power supply") else needsShell()
        val values = output.lines().mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].substringAfter('/') to it[1].trim().toLongOrNull() } }
            .filter { it.second != null }.distinctBy { it.first }.toMap()
        val design = values["charge_full_design"]?.let(::toMilliAmpHours)
        val full = values["charge_full"]?.let(::toMilliAmpHours)
        val health = if (design != null && full != null && design > 0) " (${full * 100 / design} % of design)" else ""
        info("Design ${design ?: "?"} mAh, full charge ${full ?: "?"} mAh$health. Compare with the device spec.", output, read.access)
    },

    Check("bluetooth.codecs", "Bluetooth audio codecs") {
        if (!profile.stockHasBluetooth) return@Check notApplicable("Stock has no Bluetooth")
        val read = read(BLUETOOTH_DUMP_COMMAND) { it.contains("Bluetooth Status") || it.contains("codecConfigPriorities") }
        val output = read.output ?: return@Check needsShell("Reading the Bluetooth stack needs Shizuku.")
        val listed = parseStackCodecs(output)
        val codecs = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) withoutMissingAptx(listed, read(APTX_LIBRARY_COMMAND).output.orEmpty()) else listed
        if (codecs.isEmpty()) {
            val lines = output.lines().filter { it.contains("codec", ignoreCase = true) }.distinct().take(20).joinToString("\n")
            return@Check warn("This Bluetooth stack does not list its codecs. Compare with stock by hand.", lines, read.access)
        }
        codecResult(codecs, profile.stockBluetoothCodecs, "Headset test: bluetooth.codecs.headset switches a connected headset through them.", read.access)
    },
)

/** Some kernels report mA, others µA: anything under 20 A in mA is converted. */
private fun toMicroAmps(value: Int): Int = if (value in 1 until 20_000) value * 1000 else value

/** Kernels report µAh, a few report mAh. */
private fun toMilliAmpHours(value: Long): Long = if (value >= 100_000) value / 1000 else value

// Arbitrary, outside the reserved 0xFFFFFF00-0xFFFFFFFF system range.
private const val CHECKER_STATS_TAG = 0x0dc1

private suspend fun CheckContext.trafficAccounting(): CheckResult {
    val uid = android.os.Process.myUid()
    val totalBefore = TrafficStats.getTotalRxBytes()
    val uidBefore = TrafficStats.getUidRxBytes(uid)
    val polled = execPrivileged("dumpsys netstats --poll") != null
    val start = System.currentTimeMillis() - HISTORY_WINDOW_MS
    val tagBefore = if (polled) taggedRxBytes(uid, start) else null
    val untagged = fetchDefault()
    val tagged = fetchDefault(CHECKER_STATS_TAG)
    val totalAfter = TrafficStats.getTotalRxBytes()
    val uidAfter = TrafficStats.getUidRxBytes(uid)
    if (polled) execPrivileged("dumpsys netstats --poll")
    val tagAfter = if (polled) taggedRxBytes(uid, start) else null
    val unsupported = TrafficStats.UNSUPPORTED.toLong()
    val details = "Requests: untagged ${untagged ?: "failed"}, tagged 0x${CHECKER_STATS_TAG.toString(16)} ${tagged ?: "failed"}\n" +
        "Total RX $totalBefore -> $totalAfter\nApp RX $uidBefore -> $uidAfter\n" +
        "Tagged RX in history ${tagBefore ?: "n/a"} -> ${tagAfter ?: "n/a"}\n" +
        "ro.kernel.ebpf.supported=${prop("ro.kernel.ebpf.supported").ifEmpty { "unset" }}\nKernel ${System.getProperty("os.version")}"
    val access = if (polled) Access.SHIZUKU else Access.APP
    return when {
        untagged == null || tagged == null -> warn("No internet to generate traffic. Connect and run again.", details, access)
        totalAfter == unsupported -> fail("Traffic stats unsupported: data usage and per-app limits will not work", details, access)
        totalAfter <= totalBefore -> fail("Device total counter did not move", details, access)
        uidAfter == unsupported || uidAfter <= uidBefore -> fail("Per-app traffic is not counted", details, access)
        tagAfter == null -> warn("Per-app traffic is counted (${uidAfter - uidBefore} bytes). Connect Shizuku to check tagged history.", details, access)
        tagAfter <= (tagBefore ?: 0L) -> fail("Tagged traffic does not reach the data usage history", details, access)
        else -> pass("Per-app and tagged traffic counted (${uidAfter - uidBefore} bytes, tag ${tagAfter - (tagBefore ?: 0L)})", details, access)
    }
}

private const val HISTORY_WINDOW_MS = 2 * 60 * 60 * 1000L

// The tag is per thread, so it is set on the IO thread that opens the socket.
private suspend fun fetchDefault(tag: Int? = null): Int? = withContext(Dispatchers.IO) {
    val previousTag = TrafficStats.getThreadStatsTag()
    if (tag != null) TrafficStats.setThreadStatsTag(tag)
    runCatching {
        val connection = URL(CONNECTIVITY_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            connection.useCaches = false
            // Sockets are tagged when created, so a pooled connection would keep its old tag.
            connection.setRequestProperty("Connection", "close")
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.getOrNull().also { TrafficStats.setThreadStatsTag(previousTag) }
}

private suspend fun CheckContext.taggedRxBytes(uid: Int, start: Long): Long? = withContext(Dispatchers.IO) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return@withContext null
    val type = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectivityManager.TYPE_WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectivityManager.TYPE_MOBILE
        else -> return@withContext null
    }
    val stats = context.getSystemService(NetworkStatsManager::class.java)
    runCatching {
        stats.queryDetailsForUidTag(type, null, start, System.currentTimeMillis() + HISTORY_WINDOW_MS, uid, CHECKER_STATS_TAG).use { history ->
            var bytes = 0L
            val bucket = NetworkStats.Bucket()
            while (history.getNextBucket(bucket)) bytes += bucket.rxBytes
            bytes
        }
    }.getOrNull()
}

private suspend fun CheckContext.probeInternet(transport: Int): CheckResult? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val request = NetworkRequest.Builder()
        .addTransportType(transport)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    var callback: ConnectivityManager.NetworkCallback? = null
    val network = suspendCancellableCoroutine<Network?> { cont ->
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cont.isActive) cont.resume(network)
            }

            override fun onUnavailable() {
                if (cont.isActive) cont.resume(null)
            }
        }
        callback = networkCallback
        connectivity.requestNetwork(request, networkCallback, NETWORK_TIMEOUT_MS)
        cont.invokeOnCancellation { runCatching { connectivity.unregisterNetworkCallback(networkCallback) } }
    }
    try {
        network ?: return null
        val started = System.currentTimeMillis()
        val code = withContext(Dispatchers.IO) {
            val connection = network.openConnection(URL(CONNECTIVITY_URL)) as HttpURLConnection
            try {
                connection.connectTimeout = NETWORK_TIMEOUT_MS
                connection.readTimeout = NETWORK_TIMEOUT_MS
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
        val link = connectivity.getLinkProperties(network)
        val caps = connectivity.getNetworkCapabilities(network)
        val hasDefaultRoute = link?.routes?.any { it.isDefaultRoute } == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val details = "GET $CONNECTIVITY_URL -> $code in ${System.currentTimeMillis() - started} ms\n" +
            "Interface ${link?.interfaceName}, addresses ${link?.linkAddresses?.joinToString()}\n" +
            "Default route: $hasDefaultRoute, validated: $validated\nDNS ${link?.dnsServers?.joinToString()}"
        return when {
            !hasDefaultRoute -> fail("Address but no default route (netlink address flags?)", details)
            code != HttpURLConnection.HTTP_NO_CONTENT -> warn("Unexpected HTTP $code (captive portal?)", details)
            !validated -> warn("Internet reachable but the network is not validated", details)
            else -> pass("Internet reachable, validated, default route present", details)
        }
    } catch (e: Exception) {
        val link = connectivity.getLinkProperties(network ?: return null)
        val hasDefaultRoute = link?.routes?.any { it.isDefaultRoute } == true
        val details = "Request failed: ${e.message}\nAddresses ${link?.linkAddresses?.joinToString()}\nDefault route: $hasDefaultRoute"
        val vpnActive = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (vpnActive) {
            val validated = connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            return when {
                !hasDefaultRoute -> fail("Address but no default route (netlink address flags?)", details)
                validated -> pass("Validated by the system; a VPN blocks the app's own request", details)
                else -> warn("A VPN blocks the app's own request and the system has not validated this network", details)
            }
        }
        return fail("Network up but request failed: ${e.message}", details)
    } finally {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
    }
}

private fun sdRoundTrip(dir: File): CheckResult {
    val file = File(dir, "device_checker_probe.bin")
    val data = Random.nextBytes(SD_TEST_BYTES)
    val expected = MessageDigest.getInstance("SHA-256").digest(data)
    return try {
        val writeStart = System.nanoTime()
        file.outputStream().use { out ->
            out.write(data)
            out.fd.sync()
        }
        val writeMs = (System.nanoTime() - writeStart) / 1_000_000.0
        val readStart = System.nanoTime()
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val readMs = (System.nanoTime() - readStart) / 1_000_000.0
        val mount = runCatching {
            File("/proc/self/mounts").readLines()
                .filter { line -> line.split(" ").getOrNull(1)?.let { it.startsWith("/storage/") && dir.absolutePath.startsWith(it) } == true }
                .maxByOrNull { it.split(" ")[1].length }
        }.getOrNull()
        val mb = SD_TEST_BYTES / 1024.0 / 1024.0
        val details = "Path ${dir.absolutePath}\nWrite ${"%.1f".format(mb / (writeMs / 1000))} MB/s, read ${"%.1f".format(mb / (readMs / 1000))} MB/s (read may be cached)\n${mount.orEmpty()}"
        if (actual.contentEquals(expected)) pass("${mb.toInt()} MB written and read back intact", details) else fail("Data read back differs", details)
    } catch (e: Exception) {
        fail("SD I/O failed: ${e.message}")
    } finally {
        file.delete()
    }
}
