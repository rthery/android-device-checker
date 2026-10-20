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
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.ui.englishContext
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

private val CONSTELLATION_NAMES = mapOf(
    GnssStatus.CONSTELLATION_GPS to "GPS",
    GnssStatus.CONSTELLATION_GLONASS to "GLONASS",
    GnssStatus.CONSTELLATION_GALILEO to "Galileo",
    GnssStatus.CONSTELLATION_BEIDOU to "BeiDou",
    GnssStatus.CONSTELLATION_QZSS to "QZSS",
    GnssStatus.CONSTELLATION_SBAS to "SBAS",
    GnssStatus.CONSTELLATION_IRNSS to "NavIC",
)

private fun constellationName(context: Context, type: Int) = CONSTELLATION_NAMES[type] ?: context.getString(R.string.hw_radio_constellation_other)

/** Satellite lines and the full details text, in the language of [context]. */
private fun gnssDetails(context: Context, elapsedMs: Long, bestCn0: Float, seen: Map<Int, Triple<Int, Int, Int>>): Pair<String, String> {
    val satellites = seen.entries.joinToString("\n") { (type, counts) ->
        context.getString(R.string.hw_radio_gnss_satellites, constellationName(context, type), counts.first, counts.second, counts.third)
    }
    return satellites to context.getString(R.string.hw_radio_gnss_details, elapsedMs / 1000, bestCn0, satellites)
}

@Composable
private fun GnssTest(scope: TestScope) {
    if (!scope.profile.stockHasGps) {
        NotApplicable(scope, R.string.hw_radio_stock_no_gps)
        return
    }
    RequirePermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), stringResource(R.string.hw_radio_gnss_permission)) {
        GnssContent(scope)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun GnssContent(scope: TestScope) {
    val location = remember { scope.activity.getSystemService(LocationManager::class.java) }
    val context = LocalContext.current
    val english = remember(context) { context.englishContext() }
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableLongStateOf(0L) }
    var seen by remember { mutableStateOf<Map<Int, Triple<Int, Int, Int>>>(emptyMap()) }
    var bestCn0 by remember { mutableStateOf(0f) }
    var fix by remember { mutableStateOf<Location?>(null) }
    var ttffMs by remember { mutableIntStateOf(-1) }

    if (!Compat.isLocationEnabled(scope.activity, location)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Instructions(stringResource(R.string.hw_radio_gnss_location_off))
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
                val counts = mutableMapOf<Int, Triple<Int, Int, Int>>()
                var cn0 = 0f
                for (i in 0 until status.satelliteCount) {
                    val name = status.getConstellationType(i).takeIf { it in CONSTELLATION_NAMES } ?: GnssStatus.CONSTELLATION_UNKNOWN
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

    val (satellites, details) = gnssDetails(english, elapsed, bestCn0, seen)
    val listed = seen.values.sumOf { it.first }
    val withSignal = seen.values.sumOf { it.second }
    if (fix == null && listed > 0 && withSignal == 0 && elapsed >= NO_SIGNAL_GIVE_UP_MS) {
        LaunchedEffect(Unit) {
            scope.finish(fail("$listed satellites listed but none with signal after ${elapsed / 1000} s", "Satellites listed with zero C/N0 come from almanac or assistance data, not from the radio. Check the GNSS RF path and firmware, then compare on stock.\n$satellites"))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(stringResource(R.string.hw_radio_gnss_instructions, NO_SIGNAL_GIVE_UP_MS / 60_000))
        Mono(gnssDetails(context, elapsed, bestCn0, seen).second)
        val current = fix
        if (current != null) {
            val ttff = if (ttffMs >= 0) stringResource(R.string.hw_radio_gnss_ttff_seconds, (ttffMs / 1000.0).toString()) else stringResource(R.string.hw_radio_gnss_ttff_unknown)
            Mono(stringResource(R.string.hw_radio_gnss_fix, current.latitude, current.longitude, current.accuracy.toInt(), ttff))
            Button(onClick = {
                scope.finish(pass("GPS fix in ${if (ttffMs >= 0) ttffMs / 1000 else elapsed / 1000} s, accuracy ${current.accuracy.toInt()} m", "$details\nTTFF ${ttffMs} ms"))
            }) { Text(stringResource(R.string.save_result)) }
        } else {
            OutlinedButton(onClick = {
                scope.finish(fail(if (seen.isEmpty()) "No satellites after ${elapsed / 1000} s" else "No fix after ${elapsed / 1000} s", details))
            }) { Text(stringResource(R.string.hw_radio_gnss_give_up)) }
        }
    }
}

@Composable
private fun NfcTest(scope: TestScope) {
    if (!scope.profile.stockHasNfc) {
        NotApplicable(scope, R.string.hw_radio_stock_no_nfc)
        return
    }
    val adapter = remember { NfcAdapter.getDefaultAdapter(scope.activity) }
    if (adapter == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Instructions(stringResource(R.string.hw_radio_nfc_no_adapter))
            Button(onClick = { scope.finish(fail("No NFC adapter")) }) { Text(stringResource(R.string.save_result)) }
        }
        return
    }
    var enabled by remember { mutableStateOf(adapter.isEnabled) }
    val english = LocalContext.current.englishContext()
    var tag by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(tag) {
        tag?.let { (uid, tech) -> scope.finish(pass("NFC works: tag read", english.getString(R.string.hw_radio_nfc_tag, uid, tech))) }
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
                val details = found.id.joinToString("") { "%02X".format(it) } to found.techList.joinToString { it.substringAfterLast('.') }
                Handler(Looper.getMainLooper()).post { tag = details }
            }, flags, null)
            onDispose { adapter.disableReaderMode(scope.activity) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!enabled) {
            Instructions(stringResource(R.string.hw_radio_nfc_off))
            ShortcutButton(Shortcuts.nfc)
        } else {
            Instructions(stringResource(R.string.hw_radio_nfc_on))
        }
        val current = tag
        if (current != null) {
            Mono(stringResource(R.string.hw_radio_nfc_tag, current.first, current.second))
            Text(stringResource(R.string.hw_radio_nfc_tag_read))
        } else {
            OutlinedButton(onClick = { scope.finish(fail("No tag read")) }) { Text(stringResource(R.string.hw_radio_nfc_give_up)) }
        }
    }
}

@Composable
private fun BluetoothTest(scope: TestScope) {
    if (!scope.profile.stockHasBluetooth) {
        NotApplicable(scope, R.string.hw_radio_stock_no_bluetooth)
        return
    }
    RequirePermissions(Compat.bluetoothScanPermissions(), stringResource(R.string.hw_radio_bt_permission)) {
        BluetoothContent(scope)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothContent(scope: TestScope) {
    val adapter = remember { scope.activity.getSystemService(BluetoothManager::class.java).adapter }
    if (adapter == null) {
        Button(onClick = { scope.finish(fail("No Bluetooth adapter")) }) { Text(stringResource(R.string.hw_radio_bt_save_no_adapter)) }
        return
    }
    var enabled by remember { mutableStateOf(adapter.isEnabled) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { enabled = adapter.isEnabled }
    val found = remember { mutableStateMapOf<String, String>() }
    var scanning by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    if (!enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Instructions(stringResource(R.string.hw_radio_bt_off))
            Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text(stringResource(R.string.hw_radio_bt_turn_on)) }
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
        Instructions(stringResource(R.string.hw_radio_bt_instructions, BLUETOOTH_SCAN_MS / 1000))
        Button(enabled = !scanning, onClick = {
            found.clear()
            scanning = true
        }) { Text(if (scanning) stringResource(R.string.hw_radio_bt_scanning, found.size) else stringResource(R.string.hw_radio_bt_scan)) }
        Mono(stringResource(R.string.hw_radio_bt_adapter, adapter.name.toString()) + "\n" + found.entries.joinToString("\n") { (address, info) -> "$address $info" })
        if (finished) {
            Button(onClick = {
                val details = found.entries.joinToString("\n") { (address, info) -> "$address $info" }
                scope.finish(if (found.isEmpty()) fail("No devices found in ${BLUETOOTH_SCAN_MS / 1000} s") else pass("${found.size} device(s) found", details))
            }) { Text(stringResource(R.string.save_result)) }
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
fun NotApplicable(scope: TestScope, @StringRes reason: Int) {
    val english = LocalContext.current.englishContext()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Instructions(stringResource(R.string.hw_not_applicable_from_stock, stringResource(reason)))
        Button(onClick = { scope.finish(notApplicable(english.getString(reason))) }) { Text(stringResource(R.string.hw_save_not_applicable)) }
    }
}
