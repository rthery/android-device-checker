package io.github.rthery.devicechecker.interactive

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.notApplicable
import io.github.rthery.devicechecker.checks.pass
import io.github.rthery.devicechecker.shell.CodecService
import io.github.rthery.devicechecker.shell.CodecState
import io.github.rthery.devicechecker.shell.ICodecService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val SWITCH_TIMEOUT_MS = 8_000L
private const val POLL_MS = 250L
private const val TONES_PER_CODEC = 2

val bluetoothCodecTest = InteractiveTest(
    "bluetooth.codecs.headset", "Bluetooth codecs on a headset",
    "Switches a connected headset through every codec it shares with the phone, plays a tone on each, then asks how it sounded.",
) { BluetoothCodecTest(it) }

fun codecName(type: Int): String = when (type) {
    0 -> "SBC"
    1 -> "AAC"
    2 -> "aptX"
    3 -> "aptX HD"
    4 -> "LDAC"
    5 -> "LC3"
    6 -> "Opus"
    else -> "codec $type"
}

/** Codec configs carry one flag per field; these are the AOSP flag values. */
fun formatLabel(sampleRate: Int, bitsPerSample: Int): String {
    val rate = mapOf(0x1 to "44.1 kHz", 0x2 to "48 kHz", 0x4 to "88.2 kHz", 0x8 to "96 kHz", 0x10 to "176.4 kHz", 0x20 to "192 kHz")[sampleRate]
    val bits = mapOf(0x1 to "16-bit", 0x2 to "24-bit", 0x4 to "32-bit")[bitsPerSample]
    return listOfNotNull(rate, bits).joinToString(" ")
}

private enum class Phase { IDLE, RUNNING, ASK, DONE }

@Composable
private fun BluetoothCodecTest(scope: TestScope) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Instructions(stringResource(R.string.bt_codecs_needs_13))
        Button(onClick = { scope.finish(notApplicable("Codec switching needs Android 13 system APIs")) }) { Text(stringResource(R.string.save_result)) }
        return
    }
    if (scope.privilegedShell == null) {
        Instructions(stringResource(R.string.bt_codecs_needs_shizuku))
        return
    }
    val coroutines = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var switchFailures by remember { mutableStateOf(0) }
    var setupError by remember { mutableStateOf<String?>(null) }
    val audio = remember { scope.activity.getSystemService(AudioManager::class.java) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(stringResource(R.string.bt_codecs_intro))
        if (phase == Phase.IDLE) {
            Button(onClick = {
                phase = Phase.RUNNING
                lines.clear()
                setupError = null
                coroutines.launch {
                    val outcome = CodecService.use { service -> cycle(service, audio) { lines += it } }
                    when {
                        outcome == null -> setupError = "Shizuku did not start the codec service"
                        outcome.error != null -> setupError = outcome.error
                        else -> switchFailures = outcome.failures
                    }
                    phase = if (setupError == null) Phase.ASK else Phase.IDLE
                }
            }) { Text(stringResource(R.string.bt_codecs_start)) }
        }
        if (phase == Phase.RUNNING) Text(stringResource(R.string.bt_codecs_running))
        setupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (lines.isNotEmpty()) Mono(lines.joinToString("\n"))
        if (phase == Phase.ASK) {
            YesNo(stringResource(R.string.bt_codecs_question)) { clean ->
                phase = Phase.DONE
                val details = lines.joinToString("\n")
                scope.finish(
                    when {
                        switchFailures > 0 -> fail("$switchFailures codec(s) did not switch", details)
                        !clean -> fail("Tester heard a problem on a codec", details)
                        else -> pass("Every shared codec switched and sounded clean", details)
                    }.copy(byTester = true),
                )
            }
        }
    }
}

private class CycleOutcome(val failures: Int, val error: String? = null)

private suspend fun cycle(service: ICodecService, audio: AudioManager, log: (String) -> Unit): CycleOutcome {
    val start = CodecState.parse(service.state())
    start.error?.let { return CycleOutcome(0, it) }
    val original = start.currentType
    log("Headset: ${start.device}")
    log("Phone offers: ${start.local.joinToString(", ", transform = ::codecName)}")
    log("Shared: ${start.selectable.joinToString(", ", transform = ::codecName)}")
    log("Before: ${original?.let(::codecName)} ${formatLabel(start.sampleRate, start.bitsPerSample)}")
    val a2dp = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        ?: return CycleOutcome(0, "No Bluetooth A2DP output in AudioManager")
    var failures = 0
    for (type in start.selectable) {
        service.select(type).takeIf { it.isNotEmpty() }?.let { log("${codecName(type)}: ${errorOf(it)}") }
        val began = SystemClock.elapsedRealtime()
        val reached = awaitCodec(service, type)
        if (reached == null) {
            failures++
            log("${codecName(type)}: did not switch in ${SWITCH_TIMEOUT_MS / 1000} s")
            continue
        }
        log("${codecName(type)}: switched in ${(SystemClock.elapsedRealtime() - began) / 100 / 10.0} s, ${formatLabel(reached.sampleRate, reached.bitsPerSample)}")
        repeat(TONES_PER_CODEC) { playTone(audio, a2dp) }
    }
    service.reset(start.selectable.toIntArray())
    val restored = original?.let { awaitCodec(service, it) }
    log("After reset: ${restored?.currentType?.let(::codecName) ?: "not back to ${original?.let(::codecName)}"}")
    return CycleOutcome(failures)
}

private suspend fun awaitCodec(service: ICodecService, type: Int): CodecState? {
    val deadline = SystemClock.elapsedRealtime() + SWITCH_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
        val state = CodecState.parse(service.state())
        if (state.currentType == type) return state
        delay(POLL_MS)
    }
    return null
}

private fun errorOf(json: String): String = runCatching { JSONObject(json).optString("error") }.getOrNull()?.ifEmpty { null } ?: json
