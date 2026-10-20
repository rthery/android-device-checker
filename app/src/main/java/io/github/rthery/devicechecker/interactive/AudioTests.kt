package io.github.rthery.devicechecker.interactive

import io.github.rthery.devicechecker.compat.Compat.addressCompat
import io.github.rthery.devicechecker.compat.Compat
import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import io.github.rthery.devicechecker.R
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.checks.CheckStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

private const val SAMPLE_RATE = 48_000
private const val TONE_HZ = 880.0
private const val TONE_MS = 1_500
private const val RECORD_MS = 3_000
private const val SIGNAL_THRESHOLD_DBFS = -45.0
private const val SKIP_START_MS = 200
private const val MAX_FIRST_READ_MS = 500L
private const val MIN_VOLUME_RATIO = 0.6f

private val TESTABLE_OUTPUTS = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    Compat.bleHeadsetType, AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
)

internal val TESTABLE_INPUTS = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, Compat.bleHeadsetType,
)

/** Earpiece and SCO only play in communication mode, everything else as media. */
private fun AudioDeviceInfo.needsCommunicationMode() =
    type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == Compat.bleHeadsetType

fun AudioDeviceInfo.label(): String {
    val base = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (call)"
        Compat.bleHeadsetType -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line out"
        else -> "Type $type"
    }
    val extra = labelDetail()
    return if (extra != null) "$base ($extra)" else base
}

private fun AudioDeviceInfo.labelDetail(): String? =
    addressCompat().takeIf { it.isNotBlank() && type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }

/** On-screen counterpart of [label], which stays English for results. */
@Composable
private fun AudioDeviceInfo.displayLabel(): String {
    val base = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> stringResource(R.string.hw_audio_device_speaker)
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> stringResource(R.string.hw_audio_device_earpiece)
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> stringResource(R.string.hw_audio_device_builtin_mic)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> stringResource(R.string.hw_audio_device_wired_headset)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> stringResource(R.string.hw_audio_device_wired_headphones)
        AudioDeviceInfo.TYPE_USB_HEADSET -> stringResource(R.string.hw_audio_device_usb_headset)
        AudioDeviceInfo.TYPE_USB_DEVICE -> stringResource(R.string.hw_audio_device_usb_audio)
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> stringResource(R.string.hw_audio_device_bluetooth_a2dp)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> stringResource(R.string.hw_audio_device_bluetooth_sco)
        Compat.bleHeadsetType -> stringResource(R.string.hw_audio_device_bluetooth_le)
        AudioDeviceInfo.TYPE_HDMI -> stringResource(R.string.hw_audio_device_hdmi)
        AudioDeviceInfo.TYPE_LINE_ANALOG -> stringResource(R.string.hw_audio_device_line_out)
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> stringResource(R.string.hw_audio_device_digital_line_out)
        else -> stringResource(R.string.hw_audio_device_type, type)
    }
    val extra = labelDetail()
    return if (extra != null) stringResource(R.string.hw_audio_device_with_detail, base, extra) else base
}

val audioTests = listOf(
    InteractiveTest("audio.output", "Audio outputs", "Plays a tone on each output and asks if you heard it.") { AudioOutputTest(it) },
    InteractiveTest("audio.input", "Microphones", "Records each microphone, measures the level and plays it back.") { AudioInputTest(it) },
)

@Composable
private fun rememberAudioDevices(audio: AudioManager, flag: Int, types: Set<Int>): List<AudioDeviceInfo> {
    var devices by remember { mutableStateOf(audio.getDevices(flag).filter { it.type in types }) }
    DisposableEffect(audio) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                devices = audio.getDevices(flag).filter { it.type in types }
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                devices = audio.getDevices(flag).filter { it.type in types }
            }
        }
        audio.registerAudioDeviceCallback(callback, null)
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    return devices.distinctBy { it.type to it.addressCompat() }
}

@Composable
private fun AudioOutputTest(scope: TestScope) {
    val audio = remember { scope.activity.getSystemService(AudioManager::class.java) }
    val devices = rememberAudioDevices(audio, AudioManager.GET_DEVICES_OUTPUTS, TESTABLE_OUTPUTS)
    val heard = remember { mutableStateMapOf<String, Boolean>() }
    var playing by remember { mutableStateOf<String?>(null) }
    val coroutines = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(stringResource(R.string.hw_audio_output_instructions))
        devices.forEach { device ->
            val key = "${device.type}:${device.addressCompat()}"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.displayLabel(), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = playing == null, onClick = {
                            coroutines.launch {
                                playing = key
                                playTone(audio, device)
                                playing = null
                            }
                        }) { Text(stringResource(if (playing == key) R.string.hw_audio_playing else R.string.hw_audio_play)) }
                        OutlinedButton(onClick = { heard[key] = true }) { Text(stringResource(if (heard[key] == true) R.string.hw_audio_heard_checked else R.string.hw_audio_heard)) }
                        OutlinedButton(onClick = { heard[key] = false }) { Text(stringResource(if (heard[key] == false) R.string.hw_audio_silent_checked else R.string.hw_audio_silent)) }
                    }
                }
            }
        }
        val missingEarpiece = scope.profile.isPhone && devices.none { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        Button(
            enabled = heard.isNotEmpty(),
            onClick = {
                val results = ItemResults()
                if (missingEarpiece) results.add(CheckStatus.FAIL, "Earpiece not exposed")
                devices.forEach { device ->
                    when (heard["${device.type}:${device.addressCompat()}"]) {
                        true -> results.add(CheckStatus.PASS, "${device.label()} heard")
                        false -> results.add(CheckStatus.FAIL, "${device.label()} silent")
                        null -> results.add(CheckStatus.WARN, "${device.label()} not tested")
                    }
                }
                scope.finish(results.toResult("Every output played", "No outputs"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save_result)) }
    }
}

private suspend fun playTone(audio: AudioManager, device: AudioDeviceInfo) = withContext(Dispatchers.IO) {
    val communication = device.needsCommunicationMode()
    val samples = ShortArray(SAMPLE_RATE * TONE_MS / 1000) { i ->
        val fade = minOf(1.0, i / 2400.0, (SAMPLE_RATE * TONE_MS / 1000 - i) / 2400.0)
        (sin(2 * PI * TONE_HZ * i / SAMPLE_RATE) * Short.MAX_VALUE * 0.6 * fade).toInt().toShort()
    }
    val usage = if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(samples.size * 2)
        .build()
    val stream = if (communication) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    val previousVolume = audio.getStreamVolume(stream)
    val audibleVolume = (audio.getStreamMaxVolume(stream) * MIN_VOLUME_RATIO).toInt()
    var unroute: (() -> Unit)? = null
    try {
        if (communication) unroute = Compat.routeCommunication(audio, device)
        if (previousVolume < audibleVolume) runCatching { audio.setStreamVolume(stream, audibleVolume, 0) }
        track.write(samples, 0, samples.size)
        track.setPreferredDevice(device)
        track.play()
        delay(TONE_MS + 200L)
    } finally {
        track.release()
        if (previousVolume < audibleVolume) runCatching { audio.setStreamVolume(stream, previousVolume, 0) }
        unroute?.invoke()
    }
}

@Composable
private fun AudioInputTest(scope: TestScope) {
    RequirePermissions(listOf(Manifest.permission.RECORD_AUDIO), stringResource(R.string.hw_audio_input_permission)) {
        AudioInputContent(scope)
    }
}

internal class Recording(val rmsDbfs: Double, val peakDbfs: Double, val firstReadMs: Long, val samples: ShortArray, val activeMic: String?) {
    val hasSignal get() = rmsDbfs > SIGNAL_THRESHOLD_DBFS
    val slowStart get() = firstReadMs > MAX_FIRST_READ_MS
    fun describe() = "RMS ${"%.1f".format(rmsDbfs)} dBFS, peak ${"%.1f".format(peakDbfs)} dBFS, first buffer $firstReadMs ms${activeMic?.let { ", captured by $it" }.orEmpty()}"

    /** On-screen counterpart of [describe], which stays English for results. */
    @Composable
    fun displayDescription() = stringResource(R.string.hw_audio_recording_levels, rmsDbfs, peakDbfs, firstReadMs) +
        activeMic?.let { stringResource(R.string.hw_audio_recording_captured_by, it) }.orEmpty()
}

private enum class Quality(val label: String, @StringRes val display: Int) {
    CLEAR("Clear", R.string.hw_audio_quality_clear),
    DISTORTED("Distorted", R.string.hw_audio_quality_distorted),
    SILENT("Silent", R.string.hw_audio_quality_silent),
}

@Composable
private fun AudioInputContent(scope: TestScope) {
    val audio = remember { scope.activity.getSystemService(AudioManager::class.java) }
    val devices = rememberAudioDevices(audio, AudioManager.GET_DEVICES_INPUTS, TESTABLE_INPUTS)
    val recordings = remember { mutableStateMapOf<String, Recording>() }
    val played = remember { mutableStateMapOf<String, Boolean>() }
    val quality = remember { mutableStateMapOf<String, Quality>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    val coroutines = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(stringResource(R.string.hw_audio_input_instructions))
        devices.forEach { device ->
            val key = "${device.type}:${device.addressCompat()}"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.displayLabel(), style = MaterialTheme.typography.titleSmall)
                    if (busy == key) LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                    recordings[key]?.let { rec ->
                        Text(
                            rec.displayDescription() + if (rec.slowStart) stringResource(R.string.hw_audio_slow_first_buffer) else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = busy == null, onClick = {
                            coroutines.launch {
                                busy = key
                                recordings[key] = record(audio, device) { level = it }
                                busy = null
                            }
                        }) { Text(stringResource(if (busy == key) R.string.hw_audio_recording else R.string.hw_audio_record)) }
                        recordings[key]?.let { rec ->
                            OutlinedButton(enabled = busy == null, onClick = {
                                coroutines.launch {
                                    busy = "play"
                                    playBack(rec.samples)
                                    played[key] = true
                                    busy = null
                                }
                            }) { Text(stringResource(R.string.hw_audio_play_back)) }
                        }
                    }
                    if (played[key] == true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Quality.entries.forEach { entry ->
                                FilterChip(selected = quality[key] == entry, onClick = { quality[key] = entry }, label = { Text(stringResource(entry.display)) })
                            }
                        }
                    }
                }
            }
        }
        Button(
            enabled = recordings.isNotEmpty() && busy == null,
            onClick = {
                val results = ItemResults()
                devices.forEach { device ->
                    val key = "${device.type}:${device.addressCompat()}"
                    val rec = recordings[key]
                    val heard = quality[key]
                    when {
                        rec == null -> results.add(CheckStatus.WARN, "${device.label()} not tested")
                        rec.slowStart -> results.add(CheckStatus.FAIL, "${device.label()} first buffer took ${rec.firstReadMs} ms (${rec.describe()})")
                        heard == null -> results.add(CheckStatus.WARN, "${device.label()} recorded but not judged by ear (${rec.describe()})")
                        heard == Quality.CLEAR && rec.hasSignal -> results.add(CheckStatus.PASS, "${device.label()} sounds clear (${rec.describe()})")
                        heard == Quality.CLEAR -> results.add(CheckStatus.WARN, "${device.label()} judged clear but very quiet (${rec.describe()})")
                        else -> results.add(CheckStatus.FAIL, "${device.label()} ${heard.label.lowercase()} (${rec.describe()})")
                    }
                }
                scope.finish(results.toResult("Every microphone captured sound", "No microphones"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save_result)) }
    }
}

@SuppressLint("MissingPermission")
internal suspend fun record(audio: AudioManager, device: AudioDeviceInfo, onLevel: (Float) -> Unit): Recording = withContext(Dispatchers.IO) {
    val communication = device.needsCommunicationMode()
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val recorder = AudioRecord.Builder()
        .setAudioSource(if (communication) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.UNPROCESSED.takeIf { audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true" } ?: MediaRecorder.AudioSource.MIC)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
        .setBufferSizeInBytes(minBuffer * 4)
        .build()
    val total = SAMPLE_RATE * RECORD_MS / 1000
    val samples = ShortArray(total)
    var activeMic: String? = null
    var firstReadMs = -1L
    var unroute: (() -> Unit)? = null
    try {
        if (communication) unroute = Compat.routeCommunication(audio, device)
        recorder.setPreferredDevice(device)
        val startedAt = System.currentTimeMillis()
        recorder.startRecording()
        var offset = 0
        while (offset < total && isActive) {
            val read = recorder.read(samples, offset, minOf(minBuffer / 2, total - offset))
            if (firstReadMs < 0) firstReadMs = System.currentTimeMillis() - startedAt
            if (read <= 0) break
            val chunkPeak = (offset until offset + read).maxOf { abs(samples[it].toInt()) }
            withContext(Dispatchers.Main) { onLevel(chunkPeak / Short.MAX_VALUE.toFloat()) }
            if (activeMic == null) {
                activeMic = Compat.activeMicrophones(recorder)
            }
            offset += read
        }
    } finally {
        recorder.stop()
        recorder.release()
        unroute?.invoke()
    }
    val body = samples.drop(SAMPLE_RATE * SKIP_START_MS / 1000)
    val peak = body.maxOfOrNull { abs(it.toInt()) }?.coerceAtLeast(1) ?: 1
    val rms = sqrt(body.sumOf { it.toDouble() * it } / body.size.coerceAtLeast(1)).coerceAtLeast(1.0)
    Recording(20 * log10(rms / Short.MAX_VALUE), 20 * log10(peak.toDouble() / Short.MAX_VALUE), firstReadMs, samples, activeMic)
}

private suspend fun playBack(samples: ShortArray) = withContext(Dispatchers.IO) {
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(samples.size * 2)
        .build()
    try {
        track.write(samples, 0, samples.size)
        track.play()
        delay(samples.size * 1000L / SAMPLE_RATE + 200)
    } finally {
        track.release()
    }
}
