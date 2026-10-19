package io.github.rthery.devicechecker.interactive

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.core.content.ContextCompat
import android.os.Looper
import android.os.Handler
import io.github.rthery.devicechecker.compat.Compat
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.notApplicable
import io.github.rthery.devicechecker.checks.pass
import kotlinx.coroutines.delay

private const val BLUETOOTH_SCAN_MS = 12_000L
private const val NO_SIGNAL_GIVE_UP_MS = 180_000L

val radioTests = listOf(
    InteractiveTest("gnss.fix", "GPS fix", "Waits for a GPS fix and reports satellites and time to first fix.") { GnssTest(it) },
    InteractiveTest("nfc.tag", "NFC", "Checks NFC, then reads a tag or card. The result is saved as soon as a tag is read.") { NfcTest(it) },
    InteractiveTest("bluetooth.scan", "Bluetooth scan", "Turns on discovery and BLE scanning and lists what it finds.") { BluetoothTest(it) },
)

private fun constellationName(type: Int) = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
    else -> "Other"
}

@Composable
private fun GnssTest(scope: TestScope) {
    if (!scope.profile.stockHasGps) {
        NotApplicable(scope, "Stock has no GPS")
        return
    }
    RequirePermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), "A GPS fix needs the precise location permission.") {
        GnssContent(scope)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun GnssContent(scope: TestScope) {
    val location = remember { scope.activity.getSystemService(LocationManager::class.java) }
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableLongStateOf(0L) }
    var seen by remember { mutableStateOf<Map<String, Triple<Int, Int, Int>>>(emptyMap()) }
    var bestCn0 by remember { mutableStateOf(0f) }
    var fix by remember { mutableStateOf<Location?>(null) }
    var ttffMs by remember { mutableIntStateOf(-1) }

    if (!Compat.isLocationEnabled(scope.activity, location)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Instructions("Location is off. Turn it on, then come back.")
            ShortcutButton(Shortcuts.location)
        }
        return
    }

    DisposableEffect(location) {
        val statusCallback = object : GnssStatus.Callback() {
            override fun onFirstFix(ttff: Int) {
                ttffMs = ttff
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val counts = mutableMapOf<String, Triple<Int, Int, Int>>()
                var cn0 = 0f
                for (i in 0 until status.satelliteCount) {
                    val name = constellationName(status.getConstellationType(i))
                    val (predicted, heard, used) = counts[name] ?: Triple(0, 0, 0)
                    val signal = status.getCn0DbHz(i) > 0f
                    counts[name] = Triple(predicted + 1, heard + if (signal) 1 else 0, used + if (status.usedInFix(i)) 1 else 0)
                    cn0 = maxOf(cn0, status.getCn0DbHz(i))
                }
                seen = counts
                bestCn0 = cn0
            }
        }
        val listener = LocationListener { if (fix == null) fix = it }
        location.registerGnssStatusCallback(statusCallback, Handler(Looper.getMainLooper()))
        location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
        onDispose {
            location.unregisterGnssStatusCallback(statusCallback)
            location.removeUpdates(listener)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - startedAt
            delay(1_000)
        }
    }

    val satellites = seen.entries.joinToString("\n") { (name, counts) -> "$name: ${counts.first} listed, ${counts.second} with signal, ${counts.third} used" }
    val listed = seen.values.sumOf { it.first }
    val withSignal = seen.values.sumOf { it.second }
    if (fix == null && listed > 0 && withSignal == 0 && elapsed >= NO_SIGNAL_GIVE_UP_MS) {
        LaunchedEffect(Unit) {
            scope.finish(fail("$listed satellites listed but none with signal after ${elapsed / 1000} s", "Satellites listed with zero C/N0 come from almanac or assistance data, not from the radio. Check the GNSS RF path and firmware, then compare on stock.\n$satellites"))
        }
    }
    val details = "Elapsed ${elapsed / 1000} s\nBest C/N0 ${"%.1f".format(bestCn0)} dB-Hz\n$satellites"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions("Go outside with a view of the sky. The test passes on its own on a fix, and fails on its own after ${NO_SIGNAL_GIVE_UP_MS / 60_000} min if satellites are listed but none has signal.")
        Mono(details)
        val current = fix
        if (current != null) {
            Mono("Fix: ${"%.5f".format(current.latitude)}, ${"%.5f".format(current.longitude)} ±${current.accuracy.toInt()} m\nTime to first fix: ${if (ttffMs >= 0) "${ttffMs / 1000.0} s" else "n/a"}")
            Button(onClick = {
                scope.finish(pass("GPS fix in ${if (ttffMs >= 0) ttffMs / 1000 else elapsed / 1000} s, accuracy ${current.accuracy.toInt()} m", "$details\nTTFF ${ttffMs} ms"))
            }) { Text("Save result") }
        } else {
            OutlinedButton(onClick = {
                scope.finish(fail(if (seen.isEmpty()) "No satellites after ${elapsed / 1000} s" else "No fix after ${elapsed / 1000} s", details))
            }) { Text("Give up (no fix)") }
        }
    }
}

@Composable
private fun NfcTest(scope: TestScope) {
    if (!scope.profile.stockHasNfc) {
        NotApplicable(scope, "Stock has no NFC")
        return
    }
    val adapter = remember { NfcAdapter.getDefaultAdapter(scope.activity) }
    if (adapter == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Instructions("No NFC adapter is exposed.")
            Button(onClick = { scope.finish(fail("No NFC adapter")) }) { Text("Save result") }
        }
        return
    }
    var enabled by remember { mutableStateOf(adapter.isEnabled) }
    var tag by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tag) {
        tag?.let { scope.finish(pass("NFC works: tag read", it)) }
    }
    LaunchedEffect(Unit) {
        while (!enabled) {
            delay(1_000)
            enabled = adapter.isEnabled
        }
    }
    if (enabled) {
        DisposableEffect(adapter) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
            adapter.enableReaderMode(scope.activity, { found: Tag ->
                val details = "UID ${found.id.joinToString("") { "%02X".format(it) }}\nTech: ${found.techList.joinToString { it.substringAfterLast('.') }}"
                Handler(Looper.getMainLooper()).post { tag = details }
            }, flags, null)
            onDispose { adapter.disableReaderMode(scope.activity) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!enabled) {
            Instructions("NFC is off. Turn it on.")
            ShortcutButton(Shortcuts.nfc)
        } else {
            Instructions("NFC is on. Hold a tag or card against the back of the phone.")
        }
        val current = tag
        if (current != null) {
            Mono(current)
            Text("Tag read. Saving result…")
        } else {
            OutlinedButton(onClick = { scope.finish(fail("No tag read")) }) { Text("Give up") }
        }
    }
}

@Composable
private fun BluetoothTest(scope: TestScope) {
    if (!scope.profile.stockHasBluetooth) {
        NotApplicable(scope, "Stock has no Bluetooth")
        return
    }
    RequirePermissions(Compat.bluetoothScanPermissions(), "Scanning needs the nearby devices permission (location before Android 12).") {
        BluetoothContent(scope)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothContent(scope: TestScope) {
    val adapter = remember { scope.activity.getSystemService(BluetoothManager::class.java).adapter }
    if (adapter == null) {
        Button(onClick = { scope.finish(fail("No Bluetooth adapter")) }) { Text("Save result (no adapter)") }
        return
    }
    var enabled by remember { mutableStateOf(adapter.isEnabled) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { enabled = adapter.isEnabled }
    val found = remember { mutableStateMapOf<String, String>() }
    var scanning by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    if (!enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Instructions("Bluetooth is off.")
            Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on Bluetooth") }
        }
        return
    }

    if (scanning) {
        LaunchedEffect(Unit) {
            scanBluetooth(scope.activity, BLUETOOTH_SCAN_MS) { address, info -> found.putIfAbsent(address, info) }
            scanning = false
            finished = true
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions("Scans for ${BLUETOOTH_SCAN_MS / 1000} s. Any phone, watch or headset nearby counts. Pairing and audio are covered by the audio output test.")
        Button(enabled = !scanning, onClick = {
            found.clear()
            scanning = true
        }) { Text(if (scanning) "Scanning... (${found.size})" else "Scan") }
        Mono("Adapter ${adapter.name}\n" + found.entries.joinToString("\n") { (address, info) -> "$address $info" })
        if (finished) {
            Button(onClick = {
                val details = found.entries.joinToString("\n") { (address, info) -> "$address $info" }
                scope.finish(if (found.isEmpty()) fail("No devices found in ${BLUETOOTH_SCAN_MS / 1000} s") else pass("${found.size} device(s) found", details))
            }) { Text("Save result") }
        }
    }
}

/** Classic discovery plus a BLE scan at once; [onFound] runs on the main thread for each new device. */
@SuppressLint("MissingPermission")
internal suspend fun scanBluetooth(context: Context, durationMs: Long, onFound: (String, String) -> Unit) {
    val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
            onFound(device.address, "classic ${device.name ?: "?"} $rssi dBm")
        }
    }
    val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onFound(result.device.address, "LE ${result.scanRecord?.deviceName ?: "?"} ${result.rssi} dBm")
        }
    }
    withContext(Dispatchers.Main) {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED)
        adapter.startDiscovery()
        adapter.bluetoothLeScanner?.startScan(leCallback)
        try {
            delay(durationMs)
        } finally {
            adapter.cancelDiscovery()
            adapter.bluetoothLeScanner?.stopScan(leCallback)
            context.unregisterReceiver(receiver)
        }
    }
}

@Composable
fun NotApplicable(scope: TestScope, reason: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Instructions("$reason (from the stock profile).")
        Button(onClick = { scope.finish(notApplicable(reason)) }) { Text("Save as not applicable") }
    }
}
